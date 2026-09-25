package com.ati.arena.web

/**
 * Auto-refresh policy for the failure mode where the conversation UI stops
 * updating: the reply shows an error card ("Something went wrong with this
 * response, please try again." / "出现了一些问题…请重试"), or the model finished
 * but nothing was rendered. page-side watchdog.js reports a compact status
 * ("WATCH|{…}"); this class decides when an auto reload is allowed.
 *
 * Pure Kotlin, no Android/WebView types: fully unit-tested.
 */
object ReplyWatchdog {

    /** Minimum pause between two auto reloads (per page path). */
    const val COOLDOWN_MS = 30_000L

    /** After this many auto refreshes for the same path, stop nagging (every 3rd). */
    const val NAG_INTERVAL = 3

    /** Auto reloads are never attempted while the page is still loading. */
    const val RECENT_LOAD_MS = 3_000L

    /**
     * A task (probe, cleanup, quick send) blocks auto reload only when it started
     * recently — a stuck task must not wedge the watchdog forever.
     */
    const val TASK_BLOCK_MS = 120_000L

    /**
     * Accepts older error reports for up to 45s (the user may have scrolled past
     * a stale error card; a reload still fixes it, so late = fine).
     */
    const val ERROR_ACCEPT_MS = 45_000L

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
    )

    sealed interface Decision {
        /** Reload now. */
        data object Reload : Decision

        /** Track the event (state, count, lastReloadAt); do not reload. */
        data class Track(val reason: Reason) : Decision

        /** A page status that doesn't call for any action — drop it silently. */
        data object Ignore : Decision
    }

    enum class Reason { BUSY, TASK_RUNNING, COOLDOWN, NAG, STALE }

    data class State(
        /** Paths already auto-refreshed at (epoch ms). */
        val lastReloadAt: Map<String, Long> = emptyMap(),
        /** Consecutive suppressed reloads per path (for the 3rd-time nag). */
        val counts: Map<String, Int> = emptyMap(),
    )

    /**
     * Parse one "WATCH|{json}" line. Returns null for anything else, anything
     * malformed, and non-agent paths. Line without the prefix → null.
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

        // A streaming reply usually gets its content in the end — don't interrupt.
        if (status.generating && status.key == KEY_EMPTY) return Decision.Ignore

        // Stale error reports are still actionable for a while; empty goes cold quickly.
        if (status.at > 0L) {
            val age = nowMs - status.at
            val limit = if (status.key == KEY_EMPTY) 8_000L else ERROR_ACCEPT_MS
            if (age > limit) return Decision.Track(Reason.STALE)
        }

        val count = (state.counts[status.path] ?: 0) + 1
        if (count % NAG_INTERVAL == 0 && count > 1) return Decision.Track(Reason.NAG)

        val last = state.lastReloadAt[status.path] ?: 0L
        if (last > 0L && nowMs - last < COOLDOWN_MS) return Decision.Track(Reason.COOLDOWN)
        return Decision.Reload
    }

    /** Apply the outcome of [decide] to [state]; call on every Track/Reload. */
    fun applied(state: State, status: Status, decision: Decision, nowMs: Long): State {
        val counts = state.counts + (status.path to (state.counts[status.path] ?: 0) + 1)
        val last = state.lastReloadAt + (status.path to if (decision is Decision.Reload) nowMs else state.lastReloadAt[status.path] ?: 0L)
        return State(lastReloadAt = last, counts = counts)
    }

    const val PREFIX = "WATCH|"
    const val KEY_EMPTY = "empty"
    const val KEY_ERROR_PREFIX = "error:"
}
