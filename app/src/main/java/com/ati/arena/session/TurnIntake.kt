package com.ati.arena.session

import com.ati.arena.protocol.ArenaProtocol
import com.ati.arena.session.TurnTracker.Status
import com.ati.arena.session.TurnTracker.Turn
import com.ati.arena.store.HistoryLogic

/**
 * Pure decision layer between the page's stream tap and the trace fetcher.
 * Given one captured token it decides — without I/O or threads — whether to
 * ignore it, whether its turn is already known, whether it can be answered from
 * local history, or whether the trace must be fetched. Main-thread only.
 *
 * All the "turn log shows the wrong models after switching chats" defects are
 * handled here:
 *  - tokens captured on non-conversation pages are ignored ([SessionRouting]);
 *  - attribution follows the stream URL's session id, so interleaved streams of
 *    different conversations never end up in one conversation's log;
 *  - a run is identified by its run key, so repeated/replayed/interleaved tokens
 *    never create phantom turns and never cancel each other;
 *  - a conversation page whose id differs from its stream id is aliased to the
 *    stream session by the first NEW run seen on it;
 *  - every conversation is seeded from its own persisted turns before use.
 */
class TurnIntake(
    private val history: History,
    private val nowSeconds: () -> Long = { System.currentTimeMillis() / 1000 },
    val turns: TurnTracker = TurnTracker(),
) {

    /** Persistence port (implemented by Store; faked in tests). */
    interface History {
        fun runsFor(sessionId: String): List<HistoryLogic.RunRecord>
        fun modelsFor(sessionId: String): List<String>
        fun saveRun(sessionId: String, key: String, number: Int, models: List<String>, strength: String = "")
    }

    /** Why a token produced no action. Content-free, safe to log. */
    enum class IgnoreReason {
        /** The token was captured on a page that may not belong to a conversation. */
        ROUTING,

        /** Not a run token (raw-JWT fallback junk, malformed payload, …). */
        TOKEN,
    }

    sealed interface Action {
        /** Not for a conversation on screen, or not a usable token. */
        data class Ignored(val reason: IgnoreReason) : Action

        /** Turn already known / being fetched — nothing to do. */
        data class Known(val sessionId: String, val turn: Turn) : Action

        /** Turn state changed without a network call (history hit, expired token). */
        data class Updated(val sessionId: String, val turn: Turn) : Action

        /** Fetch the trace for this run with this (still valid) token. */
        data class Fetch(val sessionId: String, val turn: Turn, val runId: String, val token: String) : Action

        /**
         * Refetch a resolved turn's trace: stream activity suggests the run
         * grew (arena may append each reply's spans to one conversation run).
         */
        data class Query(val sessionId: String, val turn: Turn, val runId: String, val token: String) : Action
    }

    /** Snapshot of one conversation for the UI. */
    data class SessionView(
        val sessionId: String,
        val turns: List<Turn>,
        val currentModels: List<String>,
        val firstModel: String,
        val routed: Boolean,
        /** Models come only from local history (nothing observed live this run). */
        val restored: Boolean,
    ) {
        val currentModel: String get() = currentModels.firstOrNull().orEmpty()
        val latestTurn: Turn? get() = turns.maxByOrNull { it.number }
    }

    private val lastAttemptAt = HashMap<String, Long>()
    private val liveSessions = HashSet<String>()

    /**
     * Page conversation id → stream session id. Arena's conversation pages
     * (/c/{evalId}, and possibly /agent/{id}) can carry an id that differs from
     * the id in the realtime stream URL, while all turn data is attributed to
     * the stream id. The first token captured ON a conversation page records the
     * mapping, so the panel can show the page conversation's log by looking up
     * its stream conversation. In-memory only: a fresh app learns the alias from
     * the next captured token (aliases are never persisted).
     */
    private val aliases = HashMap<String, String>()

    /** Conversation adopted for the current new-chat page (see [SessionRouting.accepts]). */
    var newChatSession: String? = null
        private set

    /** Call when the page path CHANGES; entering the new-chat page starts a fresh adoption. */
    fun onNavigate(path: String?) {
        if (SessionRouting.isNewChatPath(path)) newChatSession = null
    }

    /**
     * Resolve a (possibly page) conversation id to the id that owns its turn
     * data — itself, unless a stream captured on its page recorded an alias.
     * Follows at most a few hops, cycle-safe.
     */
    fun conversationFor(sessionId: String): String {
        var current = sessionId
        var hops = 0
        while (hops < 4) {
            val next = aliases[current] ?: return current
            if (next == current) return current
            current = next
            hops++
        }
        return current
    }

    /** Record [pageSession] → [streamSession] when the two differ. */
    private fun alias(pageSession: String, streamSession: String) {
        if (pageSession == streamSession || aliases.size >= MAX_ALIAS_ENTRIES) return
        aliases[pageSession] = streamSession
    }

    private fun isKnown(sessionId: String): Boolean =
        turns.turns(sessionId).isNotEmpty() ||
            runCatching { history.runsFor(sessionId).isNotEmpty() || history.modelsFor(sessionId).isNotEmpty() }
                .getOrDefault(false)

    /** Load a conversation's persisted turns once (numbering and models survive restarts). */
    fun ensureSeeded(sessionId: String) {
        if (!HistoryLogic.isValidSessionId(sessionId) || turns.isSeeded(sessionId)) return
        val seeds = runCatching { history.runsFor(sessionId) }.getOrDefault(emptyList())
            .map { TurnTracker.Seed(it.key, it.number, it.models, it.strength) }
        turns.seed(sessionId, seeds)
    }

    /**
     * Handle one captured token.
     * @param fallbackPagePath page path known natively, used when the event lacks one
     * @param inFlight         whether a trace fetch for this run key is already running
     */
    fun onToken(
        event: SessionRouting.SnoopEvent,
        fallbackPagePath: String?,
        inFlight: (key: String) -> Boolean,
    ): Action {
        val page = event.pagePath ?: fallbackPagePath
        if (!SessionRouting.accepts(event.sessionId, page, newChatSession, ::isKnown)) {
            return Action.Ignored(IgnoreReason.ROUTING)
        }
        val claims = ArenaProtocol.inspectToken(event.token, event.sessionId)
            ?: return Action.Ignored(IgnoreReason.TOKEN)
        val sessionId = event.sessionId
        if (SessionRouting.isNewChatPath(page) && newChatSession == null) newChatSession = sessionId
        ensureSeeded(sessionId)
        liveSessions.add(sessionId)

        // Turns are keyed by the TOKEN, not the run id: arena may deliver the
        // same run scope (or even the same token) for every turn of a
        // conversation; keying by run id collapsed all of them into turn 1.
        val key = HistoryLogic.tokenKey(event.token)
        val (turn, isNew) = turns.onRun(sessionId, key)
        if (isNew) {
            persist(sessionId, turn)
            // A conversation page may carry a different id than its stream: map
            // the page id to the stream id so the UI resolves turns by either id.
            // Only a NEW turn may claim the page — a replayed/known run is more
            // likely a late stream of the chat the user just left.
            HistoryLogic.sessionFromPath(page)?.let { alias(it, sessionId) }
        }

        if (turn.status == Status.RESOLVED) return Action.Known(sessionId, turn)
        if (inFlight(key)) return Action.Known(sessionId, turn)

        val now = nowSeconds()
        if (ArenaProtocol.isExpired(claims, now)) {
            if (turn.status == Status.FAILED && turn.note == NOTE_EXPIRED) return Action.Known(sessionId, turn)
            val failed = turns.fail(sessionId, key, NOTE_EXPIRED) ?: turn
            return Action.Updated(sessionId, failed)
        }
        // A failed turn is retried with a fresh sighting, but not more often than the cooldown.
        if (turn.status == Status.FAILED) {
            val last = lastAttemptAt[key] ?: 0L
            if (now - last < RETRY_COOLDOWN_SECONDS) return Action.Known(sessionId, turn)
        }
        lastAttemptAt[key] = now
        trimAttempts()
        // Keep the freshest token in MEMORY ONLY (never persisted) so a later
        // stream-activity ping can refetch this turn's trace — arena may reuse
        // one run per conversation and extend its trace with every reply.
        rememberToken(key, claims, event.token)
        return Action.Fetch(sessionId, turn, claims.runId, event.token)
    }

    private class TokenMemory(val runId: String, val token: String, val exp: Long, var seenAtDay: Long)

    private val tokens = HashMap<String, TokenMemory>()
    private val lastRefreshAt = HashMap<String, Long>()

    private fun rememberToken(key: String, claims: ArenaProtocol.Claims, token: String) {
        if (tokens.size >= MAX_TOKEN_ENTRIES) {
            val oldest = tokens.entries.minByOrNull { it.value.seenAtDay }?.key
            if (oldest != null) tokens.remove(oldest)
        }
        tokens[key] = TokenMemory(claims.runId, token, claims.exp, nowSeconds())
    }

    /**
     * Stream data keeps arriving for [event.sessionId] (snoop.js throttles this
     * to a low rate; a separate cooldown applies here). When the conversation's
     * newest resolved turn could have grown (arena appends each reply to the
     * run's trace), ask for a refetch. The token lives in memory only, so this
     * stops working after an app restart until the next captured token.
     */
    fun onActivity(
        event: SessionRouting.SnoopEvent,
        fallbackPagePath: String?,
        inFlight: (key: String) -> Boolean,
    ): Action {
        val page = event.pagePath ?: fallbackPagePath
        if (!SessionRouting.accepts(event.sessionId, page, newChatSession, ::isKnown)) {
            return Action.Ignored(IgnoreReason.ROUTING)
        }
        val sessionId = event.sessionId
        val turn = turns.turns(sessionId).maxByOrNull { it.number } ?: return Action.Known(
            sessionId, TurnTracker.Turn(0, "", Status.FAILED),
        )
        if (turn.status != Status.RESOLVED) return Action.Known(sessionId, turn)
        if (inFlight(turn.key)) return Action.Known(sessionId, turn)
        val memory = tokens[turn.key] ?: return Action.Known(sessionId, turn)
        val now = nowSeconds()
        val last = lastRefreshAt[turn.key] ?: 0L
        if (now - last < REFRESH_COOLDOWN_SECONDS) return Action.Known(sessionId, turn)
        if (memory.exp <= now + 5) return Action.Known(sessionId, turn)
        lastRefreshAt[turn.key] = now
        if (lastRefreshAt.size > MAX_ATTEMPT_ENTRIES) lastRefreshAt.clear()
        return Action.Query(sessionId, turn, memory.runId, memory.token)
    }

    /** Apply a trace result. Returns the updated turn (null if the turn is gone). */
    fun onTraceResult(sessionId: String, key: String, models: List<String>, error: String, strength: String = ""): Turn? {
        val clean = HistoryLogic.sanitizeModels(models)
        return if (clean.isNotEmpty()) {
            val turn = turns.resolve(sessionId, key, clean, strength) ?: return null
            persist(sessionId, turn)
            turn
        } else {
            turns.fail(sessionId, key, error.ifBlank { TurnTracker.NOTE_NO_MODEL })
        }
    }

    /** Current models of a conversation (live turns first, then history). Aliases resolve. */
    fun modelsFor(sessionId: String): List<String> {
        val id = conversationFor(sessionId)
        ensureSeeded(id)
        return turns.currentModels(id).ifEmpty {
            runCatching { history.modelsFor(id) }.getOrDefault(emptyList())
        }
    }

    fun view(sessionId: String): SessionView {
        if (!HistoryLogic.isValidSessionId(sessionId)) {
            return SessionView(sessionId, emptyList(), emptyList(), "", routed = false, restored = false)
        }
        val id = conversationFor(sessionId)
        ensureSeeded(id)
        val list = turns.turns(id)
        val live = turns.currentModels(id)
        val models = live.ifEmpty { runCatching { history.modelsFor(id) }.getOrDefault(emptyList()) }
        return SessionView(
            sessionId = sessionId,
            turns = list,
            currentModels = models,
            firstModel = turns.firstModel(id),
            routed = turns.isRouted(id),
            restored = models.isNotEmpty() && id !in liveSessions,
        )
    }

    private fun persist(sessionId: String, turn: Turn) {
        runCatching { history.saveRun(sessionId, turn.key, turn.number, turn.models, turn.strength) }
    }

    private fun trimAttempts() {
        if (lastAttemptAt.size <= MAX_ATTEMPT_ENTRIES) return
        val it = lastAttemptAt.entries.sortedBy { e -> e.value }.take(lastAttemptAt.size - MAX_ATTEMPT_ENTRIES)
        it.forEach { e -> lastAttemptAt.remove(e.key) }
    }

    companion object {
        const val NOTE_EXPIRED = "令牌已过期"
        const val RETRY_COOLDOWN_SECONDS = 20L
        /** Minimum gap between activity-driven trace refetches of one turn. */
        const val REFRESH_COOLDOWN_SECONDS = 45L
        private const val MAX_ATTEMPT_ENTRIES = 512
        private const val MAX_ALIAS_ENTRIES = 512
        private const val MAX_TOKEN_ENTRIES = 512
    }
}
