package com.ati.arena.web

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import com.ati.arena.web.ReplyWatchdog.Decision
import com.ati.arena.web.ReplyWatchdog.Reason
import com.ati.arena.web.ReplyWatchdog.State
import com.ati.arena.web.ReplyWatchdog.Status

class ReplyWatchdogTest {

    // at/act=0 = "unknown page clock": staleness and freshness checks are skipped.
    private fun status(
        key: String = "empty",
        path: String = "/c/s1",
        generating: Boolean = false,
        len: Int = 0,
        at: Long = 0L,
        act: Long = 0L,
    ) = Status(key = key, path = path, generating = generating, textLen = len, at = at, act = act)

    private fun decide(
        state: State = State(),
        s: Status? = status(),
        now: Long = 1_000_000L,
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
            "WATCH|{\"k\":\"empty\",\"path\":\"/c/s1\",\"generating\":false,\"len\":0,\"at\":123,\"act\":99}",
        )
        assertEquals(Status(key = "empty", path = "/c/s1", generating = false, textLen = 0, at = 123L, act = 99L), s)
        val e = ReplyWatchdog.parseLine(
            "WATCH|{\"k\":\"error:Something went wrong with this response, please try again.\",\"path\":\"/agent/s2\",\"generating\":false,\"len\":5,\"at\":9}",
        )
        assertEquals("error:Something went wrong wit", e?.key) // snippet capped
        assertEquals(5, e?.textLen)
    }

    // ---------------------------------------------------------------- fresh / idle gate

    @Test
    fun reload_onFreshErrorWhenIdle() {
        assertEquals(Decision.Reload, decide(s = status("error:x")))
    }

    @Test
    fun reload_onFreshEmptyWhenIdle() {
        assertEquals(Decision.Reload, decide(s = status("empty")))
    }

    @Test
    fun ignore_idleConversation() {
        val now = 1_000_000L
        // Last activity 5 minutes ago: the user is just reading. Never touch it,
        // even when an error card is on screen (it may be from a previous turn).
        val staleAct = status("error:x", act = now - 300_000L, at = now - 1_000L)
        assertEquals(Decision.Ignore, decide(s = staleAct, now = now))
        val staleActEmpty = status("empty", act = now - 200_000L)
        assertEquals(Decision.Ignore, decide(s = staleActEmpty, now = now))
        // Just inside the window it still acts.
        val freshAct = status("error:x", act = now - 60_000L, at = now - 1_000L)
        assertEquals(Decision.Reload, decide(s = freshAct, now = now))
    }

    @Test
    fun ignore_emptyWhileStreaming() {
        assertEquals(Decision.Ignore, decide(s = status("empty", generating = true)))
    }

    // ---------------------------------------------------------------- busy guards

    @Test
    fun track_busyBlocksReload() {
        assertEquals(Decision.Track(Reason.BUSY), decide(linkTabOpen = true))
        assertEquals(Decision.Track(Reason.BUSY), decide(loading = true))
    }

    @Test
    fun track_taskRunningOnlyRecently() {
        val now = 1_000_000L
        assertEquals(Decision.Track(Reason.TASK_RUNNING), decide(taskStartedAt = now - 60_000L, now = now))
        assertEquals(Decision.Reload, decide(taskStartedAt = now - 130_000L, now = now))
    }

    // ---------------------------------------------------------------- staleness

    @Test
    fun track_staleAfterLimits() {
        val now = 1_000_000L
        // empty goes cold at 8s
        assertEquals(Decision.Track(Reason.STALE), decide(s = status(at = now - 9_000L), now = now))
        // errors stay actionable for 45s
        assertEquals(Decision.Reload, decide(s = status("error:x", at = now - 40_000L), now = now))
        assertEquals(Decision.Track(Reason.STALE), decide(s = status("error:x", at = now - 50_000L), now = now))
    }

    // ---------------------------------------------------------------- cooldown

    @Test
    fun cooldownPerPath() {
        val now = 1_000_000L
        val state = State(lastReloadAt = mapOf("/c/s1" to now - 10_000L))
        assertEquals(Decision.Track(Reason.COOLDOWN), decide(state = state, now = now))
        assertEquals(Decision.Reload, decide(state = state, now = now + 25_000L))
        assertEquals(Decision.Reload, decide(state = state, s = status(path = "/c/s2"), now = now))
    }

    @Test
    fun noCooldownOnFirstEverReload() {
        assertEquals(Decision.Reload, decide(state = State(lastReloadAt = mapOf("/c/s1" to 0L)), now = 5_000L))
    }

    // ---------------------------------------------------------------- problem budget

    @Test
    fun sameProblemReloadsAtMostTwice() {
        var state = State()
        var now = 1_000_000L
        val problem = "/c/s1|error:x"
        fun step(s: Status = status("error:x")): Decision {
            val d = decide(state = state, s = s, now = now)
            state = ReplyWatchdog.applied(state, s, d, now)
            return d
        }
        assertEquals(Decision.Reload, step()); now += 31_000L
        assertEquals(Decision.Reload, step()); now += 31_000L
        // budget exhausted → one nudge, then silence
        assertEquals(Decision.Track(Reason.NAG), step())
        assertEquals(ReplyWatchdog.State().nagged + problem, state.nagged)
        assertEquals(Decision.Track(Reason.CAPPED), step()); now += 31_000L
        assertEquals(Decision.Track(Reason.CAPPED), step())
    }

    @Test
    fun aNewProblemGetsItsOwnBudget() {
        val problem = "/c/s1|error:x"
        val state = State(
            reloads = mapOf(problem to 10),
            nagged = setOf(problem),
        )
        // same problem: capped forever (path=/c/s1 | error:x)
        assertEquals(Decision.Track(Reason.CAPPED), decide(state = state, s = status("error:x")))
        // different error text, or empty instead of error: a fresh budget
        assertEquals(Decision.Reload, decide(state = state, s = status("error:y")))
        assertEquals(Decision.Reload, decide(state = state, s = status("empty")))
    }

    @Test
    fun applied_updatesState() {
        val state = State()
        val now = 1_000_000L
        val s = status("error:x")
        val next = ReplyWatchdog.applied(state, s, Decision.Reload, now)
        assertEquals(mapOf("/c/s1" to now), next.lastReloadAt)
        assertEquals(mapOf("/c/s1|error:x" to 1), next.reloads)
        val nagged = ReplyWatchdog.applied(state, s, Decision.Track(Reason.NAG), now)
        assertEquals(setOf("/c/s1|error:x"), nagged.nagged)
        // plain tracks don't change anything
        val plain = ReplyWatchdog.applied(state, s, Decision.Track(Reason.COOLDOWN), now)
        assertEquals(state, plain)
    }
}
