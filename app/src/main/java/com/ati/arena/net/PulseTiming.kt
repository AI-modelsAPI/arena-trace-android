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
    /** Fallback cooldown when a 429 carries no usable Retry-After. */
    const val DEFAULT_RETRY_AFTER_MS = 120_000L

    /** Longest cooldown honoured from a Retry-After header. */
    const val MAX_RETRY_AFTER_MS = 10L * 60 * 1000

    /**
     * Parse an HTTP Retry-After header: delta-seconds ("120") or an HTTP-date
     * ("Wed, 21 Oct 2026 07:28:00 GMT"). Result is clamped to 1s…[MAX_RETRY_AFTER_MS];
     * missing/garbage values fall back to [DEFAULT_RETRY_AFTER_MS].
     */
    fun retryAfterMs(header: String?, nowMs: Long): Long {
        val value = header?.trim().orEmpty()
        if (value.isEmpty()) return DEFAULT_RETRY_AFTER_MS
        val ms = value.toLongOrNull()?.let { it * 1000 }
            ?: runCatching {
                java.time.ZonedDateTime.parse(value, java.time.format.DateTimeFormatter.RFC_1123_DATE_TIME)
                    .toInstant().toEpochMilli() - nowMs
            }.getOrNull()
            ?: return DEFAULT_RETRY_AFTER_MS
        return ms.coerceIn(1_000L, MAX_RETRY_AFTER_MS)
    }

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
