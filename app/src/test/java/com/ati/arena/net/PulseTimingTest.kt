package com.ati.arena.net

import org.junit.Assert.assertEquals
import org.junit.Test

class PulseTimingTest {

    private val hour = 60L * 60 * 1000
    private val day = 24L * hour
    private val now = 1_000_000_000_000L

    // ---- resetTimeFromRefreshedAt ----

    @Test fun refreshedAtInPastIsWindowStartSoResetIsOneWindowLater() {
        // Quota refreshed 3h ago → resets 21h from now (24h window).
        val refreshed = now - 3 * hour
        assertEquals(refreshed + day, PulseTiming.resetTimeFromRefreshedAt(refreshed, now))
    }

    @Test fun refreshedAtInFutureIsTreatedAsResetInstant() {
        val future = now + 5 * hour
        assertEquals(future, PulseTiming.resetTimeFromRefreshedAt(future, now))
    }

    @Test fun unknownRefreshedAtIsZero() {
        assertEquals(0L, PulseTiming.resetTimeFromRefreshedAt(0L, now))
        assertEquals(0L, PulseTiming.resetTimeFromRefreshedAt(-1L, now))
    }

    // ---- anchorReset: the actual bug (countdown restarting every minute) ----

    @Test fun firstAnchorAdoptsCandidate() {
        val candidate = now + 20 * hour
        assertEquals(candidate, PulseTiming.anchorReset(0L, candidate, now))
    }

    @Test fun forwardDriftDoesNotRestartCountdown() {
        // Anchor set to reset in 21h. One minute later a refetch reports refreshedAt
        // one minute newer → candidate ~1min later. Must KEEP the anchor, not jump.
        val anchor = now + 21 * hour
        val later = now + 60_000
        val candidate = PulseTiming.resetTimeFromRefreshedAt(later - 3 * hour, later) // ~ later+21h
        val kept = PulseTiming.anchorReset(anchor, candidate, later)
        assertEquals(anchor, kept)
    }

    @Test fun withinToleranceKeepsAnchor() {
        val anchor = now + 21 * hour
        val candidate = anchor + 30_000 // 30s later, within 2min tolerance
        assertEquals(anchor, PulseTiming.anchorReset(anchor, candidate, now))
    }

    @Test fun earlierThanToleranceAdoptsCandidate() {
        // Quota actually reset sooner than expected → adopt the earlier reset.
        val anchor = now + 21 * hour
        val candidate = now + 18 * hour
        assertEquals(candidate, PulseTiming.anchorReset(anchor, candidate, now))
    }

    @Test fun afterPreviousWindowElapsedAdoptsNewCandidate() {
        // A real rollover: the old anchor is already in the past.
        val anchor = now - 60_000
        val candidate = now + day
        assertEquals(candidate, PulseTiming.anchorReset(anchor, candidate, now))
    }

    @Test fun zeroCandidateKeepsPreviousAnchor() {
        val anchor = now + 10 * hour
        assertEquals(anchor, PulseTiming.anchorReset(anchor, 0L, now))
    }
}
