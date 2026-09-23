package com.ati.arena.net

/**
 * Pure timing logic for the quota-reset countdown. Framework-free so it can be
 * unit tested on the JVM.
 *
 * Bug it fixes: /api/me/pulse returns `refreshedAt`, the timestamp the quota was
 * last refreshed. The old code used that value directly as the reset instant, so
 * every ~60s refetch supplied a newer `refreshedAt` and the countdown jumped back
 * to ~24h. We instead (1) derive the true reset instant from refreshedAt and
 * (2) ANCHOR it so second/minute-level drift on refetch never restarts the count.
 */
object PulseTiming {
    /** Arena quota renews on a 24-hour window. */
    const val QUOTA_WINDOW_MS = 24L * 60 * 60 * 1000

    /** Ignore reset-time changes smaller than this (refetch jitter / forward drift). */
    const val DRIFT_TOLERANCE_MS = 2L * 60 * 1000

    /**
     * Turn a parsed `refreshedAt` epoch into the window's reset instant.
     *  - <= 0            → unknown (0; caller shows no countdown)
     *  - in the past     → it's the current window's START → reset one window later
     *  - in the future   → treat it as the reset instant itself
     */
    fun resetTimeFromRefreshedAt(refreshedAtMs: Long, nowMs: Long, windowMs: Long = QUOTA_WINDOW_MS): Long {
        if (refreshedAtMs <= 0) return 0
        return if (refreshedAtMs <= nowMs) refreshedAtMs + windowMs else refreshedAtMs
    }

    /**
     * Keep the reset countdown stable across refetches.
     *  - no previous anchor            → adopt the candidate
     *  - previous window already ended → adopt (a real rollover happened)
     *  - candidate is meaningfully EARLIER than the anchor (> tolerance) → adopt
     *    (the quota reset sooner than we expected)
     *  - otherwise (candidate within tolerance, or later while the anchor hasn't
     *    elapsed — i.e. refreshedAt drifting forward) → KEEP the anchor
     */
    fun anchorReset(
        previousAnchorMs: Long,
        candidateMs: Long,
        nowMs: Long,
        toleranceMs: Long = DRIFT_TOLERANCE_MS,
    ): Long {
        if (candidateMs <= 0) return previousAnchorMs
        if (previousAnchorMs <= 0) return candidateMs
        if (nowMs >= previousAnchorMs) return candidateMs
        if (candidateMs < previousAnchorMs - toleranceMs) return candidateMs
        return previousAnchorMs
    }
}
