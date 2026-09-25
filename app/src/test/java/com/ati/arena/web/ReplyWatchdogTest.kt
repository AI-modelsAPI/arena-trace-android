package com.ati.arena.web

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import com.ati.arena.web.ReplyWatchdog.Decision
import com.ati.arena.web.ReplyWatchdog.Reason
import com.ati.arena.web.ReplyWatchdog.Status
import com.ati.arena.web.ReplyWatchdog.State

class ReplyWatchdogTest {

    // at=0 = "unknown page clock": the staleness check is skipped.
    private fun status(key: String = "empty", path: String = "/c/s1", generating: Boolean = false, len: Int = 0, at: Long = 0L) =
        Status(key = key, path = path, generating = generating, textLen = len, at = at)

    private fun decide(
        state: State = State(),
        s: Status? = status(),
        now: Long = 20_000L,
        linkTabOpen: Boolean = false,
        loading: Boolean = false,
        taskStartedAt: Long = 0L,
    ) = ReplyWatchdog.decide(state, s, now, linkTabOpen, loading, taskStartedAt)

    // ---------------------------------------------------------------- parseLine

    @Test
    fun parseLine_ignoresEverythingElse() {
        assertNull(ReplyWatchdog.parseLine("just a log line"))
        assertNull(ReplyWatchdog.parseLine(""))
        assertNull(ReplyWatchdog.parseLine("WATCH"))
        assertNull(ReplyWatchdog.parseLine("WATCH|not json"))
        assertNull(ReplyWatchdog.parseLine("WATCH|{\"k\":\"empty\"}")) // no path
        assertNull(ReplyWatchdog.parseLine("WATCH|{\"path\":\"/c/s1\"}")) // no key
        assertNull(ReplyWatchdog.parseLine("WATCH|{\"k\":\"bogus\",\"path\":\"/c/s1\"}"))
        assertNull(ReplyWatchdog.parseLine("WATCH|{\"k\":\"empty\",\"path\":\"/settings\"}"))
    }

    @Test
    fun parseLine_acceptsValidStatus() {
        val s = ReplyWatchdog.parseLine(
            "WATCH|{\"k\":\"empty\",\"path\":\"/c/s1\",\"generating\":false,\"len\":0,\"at\":123}",
        )
        assertEquals(Status(key = "empty", path = "/c/s1", generating = false, textLen = 0, at = 123L), s)
        val e = ReplyWatchdog.parseLine(
            "WATCH|{\"k\":\"error:Something went wrong with this response, please try again.\",\"path\":\"/agent/s2\",\"generating\":false,\"len\":5,\"at\":9}",
        )
        assertEquals("error:Something went wrong wit", e?.key) // snippet capped
        assertEquals(5, e?.textLen)
    }

    // ---------------------------------------------------------------- decide

    @Test
    fun reload_onFreshErrorWhenIdle() {
        assertEquals(Decision.Reload, decide(s = status("error:x")))
    }

    @Test
    fun reload_onFreshEmptyWhenIdle() {
        assertEquals(Decision.Reload, decide(s = status("empty")))
    }

    @Test
    fun ignore_emptyWhileStreaming() {
        assertEquals(Decision.Ignore, decide(s = status("empty", generating = true)))
    }

    @Test
    fun track_busyBlocksReload() {
        assertEquals(Decision.Track(Reason.BUSY), decide(linkTabOpen = true))
        assertEquals(Decision.Track(Reason.BUSY), decide(loading = true))
    }

    @Test
    fun track_taskRunningOnlyRecently() {
        val now = 200_000L
        // task started 60s ago → blocked
        assertEquals(Decision.Track(Reason.TASK_RUNNING), decide(taskStartedAt = now - 60_000L, now = now))
        // task started 130s ago → watchdog may fire anyway (stuck task must not wedge it)
        assertEquals(Decision.Reload, decide(taskStartedAt = now - 130_000L, now = now))
    }

    @Test
    fun track_staleAfterLimits() {
        val now = 100_000L
        // empty goes cold at 8s
        assertEquals(Decision.Track(Reason.STALE), decide(s = status(at = now - 9_000L), now = now))
        // errors stay actionable for 45s
        assertEquals(Decision.Reload, decide(s = status("error:x", at = now - 40_000L), now = now))
        assertEquals(Decision.Track(Reason.STALE), decide(s = status("error:x", at = now - 50_000L), now = now))
    }

    @Test
    fun noCooldownOnFirstEverReload() {
        // lastReloadAt == 0 (never reloaded) must not count as "just reloaded".
        assertEquals(Decision.Reload, decide(state = State(lastReloadAt = mapOf("/c/s1" to 0L)), now = 5_000L))
    }

    @Test
    fun cooldownPerPath() {
        val now = 100_000L
        val state = State(lastReloadAt = mapOf("/c/s1" to now - 10_000L))
        assertEquals(Decision.Track(Reason.COOLDOWN), decide(state = state, now = now))
        assertEquals(Decision.Reload, decide(state = state, now = now + 25_000L))
        // a different path is not throttled
        assertEquals(Decision.Reload, decide(state = state, s = status(path = "/c/s2"), now = now))
    }

    @Test
    fun everyThirdEventNags() {
        var state = State()
        var now = 1_000_000L
        fun step(s: Status = status()): Decision {
            val d = decide(state = state, s = s, now = now)
            state = ReplyWatchdog.applied(state, s, d, now)
            return d
        }
        // 1st → reload. A repeated problem inside the cooldown window only counts;
        // the 3rd distinct event gets the manual-refresh nudge instead of a reload.
        assertEquals(Decision.Reload, step()); now += 1_000L
        assertEquals(Decision.Track(Reason.COOLDOWN), step()); now += 31_000L
        assertEquals(Decision.Track(Reason.NAG), step()); now += 31_000L
        assertEquals(Decision.Reload, step())
    }

    @Test
    fun applied_updatesState() {
        val state = State()
        val now = 100_000L
        val s = status()
        val next = ReplyWatchdog.applied(state, s, Decision.Reload, now)
        assertEquals(mapOf("/c/s1" to now), next.lastReloadAt)
        assertEquals(mapOf("/c/s1" to 1), next.counts)
    }
}
