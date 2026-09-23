package com.ati.arena.net

/**
 * Pure decision logic for the Cloudflare warm-up → /agent advance.
 *
 * The old shell used a fixed 1.2s timer before navigating from the home page to
 * /agent, which is fragile: on a slow/unstable link the managed challenge may
 * not have issued cf_clearance yet, and on a fast link the wait is pure latency.
 * Instead we poll two reliable signals and advance as soon as either says "ready".
 */
object WarmUp {
    /**
     * @param clearanceCookiePresent whether arena.ai already carries a cf_clearance cookie
     * @param isChallengePage        whether the currently loaded page still looks like a
     *                               Cloudflare interstitial ("Just a moment…", #challenge-form, …).
     *                               Callers MUST treat an unknown/failed probe as `true`
     *                               (still-challenging) so we keep waiting rather than jumping early.
     * @return true when it is safe to navigate to /agent.
     */
    fun shouldAdvance(clearanceCookiePresent: Boolean, isChallengePage: Boolean): Boolean =
        clearanceCookiePresent || !isChallengePage
}
