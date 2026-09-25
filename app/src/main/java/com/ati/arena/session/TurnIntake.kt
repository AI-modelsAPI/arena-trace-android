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
 *  - tokens from a conversation that is not on screen are ignored ([SessionRouting]);
 *  - a run is identified by its run key, so repeated/replayed/interleaved tokens
 *    never create phantom turns and never cancel each other;
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
        fun saveRun(sessionId: String, key: String, number: Int, models: List<String>)
    }

    sealed interface Action {
        /** Not for the conversation on screen, or not a usable token. */
        data object Ignored : Action

        /** Turn already known / being fetched — nothing to do. */
        data class Known(val sessionId: String, val turn: Turn) : Action

        /** Turn state changed without a network call (history hit, expired token). */
        data class Updated(val sessionId: String, val turn: Turn) : Action

        /** Fetch the trace for this run with this (still valid) token. */
        data class Fetch(val sessionId: String, val turn: Turn, val runId: String, val token: String) : Action
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

    /** Conversation adopted for the current new-chat page (see [SessionRouting.accepts]). */
    var newChatSession: String? = null
        private set

    /** Call when the page path CHANGES; entering the new-chat page starts a fresh adoption. */
    fun onNavigate(path: String?) {
        if (SessionRouting.isNewChatPath(path)) newChatSession = null
    }

    private fun isKnown(sessionId: String): Boolean =
        turns.turns(sessionId).isNotEmpty() ||
            runCatching { history.runsFor(sessionId).isNotEmpty() || history.modelsFor(sessionId).isNotEmpty() }
                .getOrDefault(false)

    /** Load a conversation's persisted turns once (numbering and models survive restarts). */
    fun ensureSeeded(sessionId: String) {
        if (!HistoryLogic.isValidSessionId(sessionId) || turns.isSeeded(sessionId)) return
        val seeds = runCatching { history.runsFor(sessionId) }.getOrDefault(emptyList())
            .map { TurnTracker.Seed(it.key, it.number, it.models) }
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
        if (!SessionRouting.accepts(event.sessionId, page, newChatSession, ::isKnown)) return Action.Ignored
        val claims = ArenaProtocol.inspectToken(event.token, event.sessionId) ?: return Action.Ignored
        val sessionId = event.sessionId
        if (SessionRouting.isNewChatPath(page) && newChatSession == null) newChatSession = sessionId
        ensureSeeded(sessionId)
        liveSessions.add(sessionId)

        val key = HistoryLogic.runKey(claims.runId)
        val (turn, isNew) = turns.onRun(sessionId, key)
        if (isNew) persist(sessionId, turn)

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
        return Action.Fetch(sessionId, turn, claims.runId, event.token)
    }

    /** Apply a trace result. Returns the updated turn (null if the turn is gone). */
    fun onTraceResult(sessionId: String, key: String, models: List<String>, error: String): Turn? {
        val clean = HistoryLogic.sanitizeModels(models)
        return if (clean.isNotEmpty()) {
            val turn = turns.resolve(sessionId, key, clean) ?: return null
            persist(sessionId, turn)
            turn
        } else {
            turns.fail(sessionId, key, error.ifBlank { TurnTracker.NOTE_NO_MODEL })
        }
    }

    /** Current models of a conversation (live turns first, then history). */
    fun modelsFor(sessionId: String): List<String> {
        ensureSeeded(sessionId)
        return turns.currentModels(sessionId).ifEmpty {
            runCatching { history.modelsFor(sessionId) }.getOrDefault(emptyList())
        }
    }

    fun view(sessionId: String): SessionView {
        if (!HistoryLogic.isValidSessionId(sessionId)) {
            return SessionView(sessionId, emptyList(), emptyList(), "", routed = false, restored = false)
        }
        ensureSeeded(sessionId)
        val list = turns.turns(sessionId)
        val live = turns.currentModels(sessionId)
        val models = live.ifEmpty { runCatching { history.modelsFor(sessionId) }.getOrDefault(emptyList()) }
        return SessionView(
            sessionId = sessionId,
            turns = list,
            currentModels = models,
            firstModel = turns.firstModel(sessionId),
            routed = turns.isRouted(sessionId),
            restored = models.isNotEmpty() && sessionId !in liveSessions,
        )
    }

    private fun persist(sessionId: String, turn: Turn) {
        runCatching { history.saveRun(sessionId, turn.key, turn.number, turn.models) }
    }

    private fun trimAttempts() {
        if (lastAttemptAt.size <= MAX_ATTEMPT_ENTRIES) return
        val it = lastAttemptAt.entries.sortedBy { e -> e.value }.take(lastAttemptAt.size - MAX_ATTEMPT_ENTRIES)
        it.forEach { e -> lastAttemptAt.remove(e.key) }
    }

    companion object {
        const val NOTE_EXPIRED = "令牌已过期"
        const val RETRY_COOLDOWN_SECONDS = 20L
        private const val MAX_ATTEMPT_ENTRIES = 512
    }
}
