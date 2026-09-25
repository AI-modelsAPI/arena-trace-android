package com.ati.arena.web

/**
 * Auto-refresh policy for the failure mode where the conversation UI stops
 * updating: the reply shows an error card ("Something went wrong with this
 * response, please try again." / "出现了一些问题…请重试"), or the model finished
 * but nothing was rendered. page-side watchdog.js reports a compact status
 * ("WATCH|{…}"); this class decides when an auto reload is allowed.
 *
 * Tightened v2 rules:
 *  - the page only reports within 2 minutes of real conversation activity
 *    (send / stream growth / Stop button); this class re-checks that window;
 *  - the same problem (same path + same error key) auto-reloads at most twice,
 *    then nudges for a manual refresh once, then stays silent.
 *
 * Pure Kotlin, no Android/WebView types: fully unit-tested.
 */
object ReplyWatchdog {

    /** Minimum pause between two auto reloads (per page path). */
    const val COOLDOWN_MS = 30_000L

    /**
     * A task (probe, cleanup, quick send) blocks auto reload only when it started
     * recently — a stuck task must not wedge the watchdog forever.
     */
    const val TASK_BLOCK_MS = 120_000L

    /**
     * Reports older than this many ms after the conversation's last activity are
     * ignored: an idle conversation is never auto-refreshed.
     */
    const val FRESH_MS = 120_000L

    /**
     * An error report stays actionable this long (the user may have scrolled past
     * a stale card; a reload still fixes it). Empty-reply reports go cold much
     * faster ([STALE_EMPTY_MS]).
     */
    const val ERROR_ACCEPT_MS = 45_000L

    const val STALE_EMPTY_MS = 8_000L

    /** The same problem is auto-refreshed at most this many times. */
    const val MAX_SAME_KEY_RELOADS = 2

    /** Error snippets from the page are capped (defence in depth for log spam). */
    const val MAX_SNIPPET_CHARS = 24

    data class Status(
        /** "error:<snippet>" or "empty". */
        val key: String,
        val path: String,
        /** A Stop button was visible at scan time (streaming in progress). */
        val generating: Boolean,
        /** Length of the reply text the watchdog last saw. */
        val textLen: Int,
        /** Epoch ms when the state was first observed (page clock). */
        val at: Long,
        /** Epoch ms of the last conversation activity (send/stream); 0 = unknown. */
        val act: Long = 0L,
    )

    sealed interface Decision {
        /** Reload now. */
        data object Reload : Decision

        /** Track the event (state updates); do not reload. */
        data class Track(val reason: Reason) : Decision

        /** A page status that doesn't call for any action — drop it silently. */
        data object Ignore : Decision
    }

    enum class Reason { BUSY, TASK_RUNNING, COOLDOWN, STALE, NAG, CAPPED }

    data class State(
        /** Paths already auto-refreshed at (epoch ms). */
        val lastReloadAt: Map<String, Long> = emptyMap(),
        /** Auto reloads per problem ("path|key"). */
        val reloads: Map<String, Int> = emptyMap(),
        /** Problems the manual-refresh nudge was already shown for. */
        val nagged: Set<String> = emptySet(),
    )

    /**
     * Parse one "WATCH|{json}" line. Returns null for anything else, anything
     * malformed, and non-agent paths.
     */
    fun parseLine(line: String): Status? {
        if (!line.startsWith(PREFIX)) return null
        val o = runCatching { org.json.JSONObject(line.substring(PREFIX.length)) }.getOrNull() ?: return null
        val key = o.optString("k")
        val path = o.optString("path")
        if (key.isEmpty() || path.isEmpty()) return null
        if (!path.startsWith("/agent") && !path.startsWith("/c/")) return null
        val textKey = when {
            key == KEY_EMPTY -> KEY_EMPTY
            key.startsWith(KEY_ERROR_PREFIX) ->
                KEY_ERROR_PREFIX + key.removePrefix(KEY_ERROR_PREFIX).take(MAX_SNIPPET_CHARS)
            else -> return null
        }
        return Status(
            key = textKey,
            path = path.take(128),
            generating = o.optBoolean("generating", false),
            textLen = o.optInt("len", 0).coerceIn(0, 1_000_000),
            at = o.optLong("at", 0L),
            act = o.optLong("act", 0L),
        )
    }

    /**
     * Decide what to do with a page status.
     *
     * @param nowMs        current time
     * @param linkTabOpen  the in-app link tab covers the page (back stack frozen)
     * @param loading      a page load is in flight (auto reload would fight it)
     * @param taskStartedAt epoch ms when the current task started; 0 = no task
     */
    fun decide(
        state: State,
        status: Status?,
        nowMs: Long,
        linkTabOpen: Boolean,
        loading: Boolean,
        taskStartedAt: Long,
    ): Decision {
        status ?: return Decision.Ignore
        if (linkTabOpen || loading) return Decision.Track(Reason.BUSY)
        if (taskStartedAt > 0L && nowMs - taskStartedAt < TASK_BLOCK_MS) return Decision.Track(Reason.TASK_RUNNING)

        // No recent conversation activity: an idle chat is never ours to fix.
        if (status.act > 0L && nowMs - status.act > FRESH_MS) return Decision.Ignore

        // A streaming reply usually gets its content in the end — don't interrupt.
        if (status.generating && status.key == KEY_EMPTY) return Decision.Ignore

        // Stale reports: empty goes cold quickly, errors stay actionable longer.
        if (status.at > 0L) {
            val age = nowMs - status.at
            val limit = if (status.key == KEY_EMPTY) STALE_EMPTY_MS else ERROR_ACCEPT_MS
            if (age > limit) return Decision.Track(Reason.STALE)
        }

        // Budget per problem: at most MAX_SAME_KEY_RELOADS automatic reloads,
        // then one manual-refresh nudge, then silence.
        val problem = status.path + '|' + status.key
        if ((state.reloads[problem] ?: 0) >= MAX_SAME_KEY_RELOADS) {
            return Decision.Track(if (problem in state.nagged) Reason.CAPPED else Reason.NAG)
        }

        val last = state.lastReloadAt[status.path] ?: 0L
        if (last > 0L && nowMs - last < COOLDOWN_MS) return Decision.Track(Reason.COOLDOWN)
        return Decision.Reload
    }

    /** Apply the outcome of [decide] to [state]; call on every Track/Reload. */
    fun applied(state: State, status: Status, decision: Decision, nowMs: Long): State {
        val problem = status.path + '|' + status.key
        return when (decision) {
            Decision.Reload -> state.copy(
                lastReloadAt = state.lastReloadAt + (status.path to nowMs),
                reloads = state.reloads + (problem to (state.reloads[problem] ?: 0) + 1),
            )
            is Decision.Track -> if (decision.reason == Reason.NAG) {
                state.copy(nagged = state.nagged + problem)
            } else {
                state
            }
            Decision.Ignore -> state
        }
    }

    const val PREFIX = "WATCH|"
    const val KEY_EMPTY = "empty"
    const val KEY_ERROR_PREFIX = "error:"
}
