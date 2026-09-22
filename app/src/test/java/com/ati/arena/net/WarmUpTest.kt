package com.ati.arena.net

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WarmUpTest {

    @Test fun advancesWhenClearanceCookiePresent() {
        // cf_clearance issued → safe to advance even if the DOM probe is still noisy.
        assertTrue(WarmUp.shouldAdvance(clearanceCookiePresent = true, isChallengePage = true))
    }

    @Test fun advancesWhenNoLongerChallenging() {
        // No clearance cookie yet, but the page is a real Arena page → advance.
        assertTrue(WarmUp.shouldAdvance(clearanceCookiePresent = false, isChallengePage = false))
    }

    @Test fun waitsWhileChallengingAndNoClearance() {
        // The fragile case: still on the interstitial, no clearance → keep polling.
        assertFalse(WarmUp.shouldAdvance(clearanceCookiePresent = false, isChallengePage = true))
    }

    @Test fun advancesWhenBothSignalsReady() {
        assertTrue(WarmUp.shouldAdvance(clearanceCookiePresent = true, isChallengePage = false))
    }
}
