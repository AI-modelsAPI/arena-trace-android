package com.ati.arena.web

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import com.ati.arena.web.ReplyWatchdog.Decision
import com.ati.arena.web.ReplyWatchdog.Reason
import com.ati.arena.web.ReplyWatchdog.State
import com.ati.arena.web.ReplyWatchdog.Status

class ReplyWatchdogTest {

    private val BASE = 1_000_000L

    /**
     * Default clock conventions:
     *  - at=0 → "page clock unknown": the staleness window check is skipped;
     *  - act=BASE-1000 → the conversation was recently active (fresh);
     *  - act=0 → NO known activity at all: treated as idle, never reload.
     */
    private fun status(
        key: String = "empty",
        path: String = "/c/s1",
        generating: Boolean = false,
        len: Int = 0,
        at: Long = 0L,
        act: Long = BASE - 1_000L,
    ) = Status(key = key, path = path, generating = generating, textLen = len, at = at, act = act)

    private fun decide(
        state: State = State(),
        s: Status? = status(),
        now: Long = BASE,
        linkTabOpen: Boolean = false,
        loading: Boolean = false,
        taskStartedAt: Long = 0L,
    ) = ReplyWatchdog.decide(state, s, now, linkTabOpen, loading, taskStartedAt)

    /** Run one sighting (applies the decision) and return the new state. */
    private fun sight(state: State, s: Status, now: Long): State =
        ReplyWatchdog.applied(state, s, decide(state = state, s = s, now = now), now)

    /** A problem already confirmed once (skips the WAIT window). */
    private fun confirmed(state: State = State(), problem: String = "/c/s1|empty", at: Long = BASE - 30_000L) =
        state.copy(firstSeen = state.firstSeen + (problem to at))

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
    fun waitThenReload_onFreshProblem() {
        val s = status("error:x")
        // First sighting: only tracked.
        assertEquals(Decision.Track(Reason.WAIT), decide(s = s))
        val after = sight(State(), s, BASE)
        // Second sighting past CONFIRM_MS: reload.
        assertEquals(Decision.Reload, decide(state = after, s = s, now = BASE + ReplyWatchdog.CONFIRM_MS))
        // Too soon: still waiting.
        assertEquals(Decision.Track(Reason.WAIT), decide(state = after, s = s, now = BASE + ReplyWatchdog.CONFIRM_MS - 1))
    }

    @Test
    fun ignore_whenNoActivityIsKnown() {
        // act=0 used to slip through the freshness check — reloading a chat the
        // user only reads is exactly what we must not do anymore.
        assertEquals(Decision.Ignore, decide(s = status("empty", act = 0L)))
        assertEquals(Decision.Ignore, decide(s = status("error:x", act = 0L)))
    }

    @Test
    fun ignore_idleConversation() {
        val now = BASE
        // Last activity 5 minutes ago: the user is just reading. Never touch it,
        // even when an error card is on screen (it may be from a previous turn).
        assertEquals(Decision.Ignore, decide(s = status("error:x", act = now - 300_000L, at = now - 1_000L), now = now))
        assertEquals(Decision.Ignore, decide(s = status("empty", act = now - 200_000L), now = now))
        // Just inside the window: confirmed problem still reloads.
        val confirmedState = confirmed(problem = "/c/s1|error:x")
        assertEquals(
            Decision.Reload,
            decide(state = confirmedState, s = status("error:x", act = now - 60_000L, at = now - 1_000L), now = now),
        )
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
        val now = BASE
        assertEquals(Decision.Track(Reason.TASK_RUNNING), decide(taskStartedAt = now - 60_000L, now = now))
        assertEquals(Decision.Track(Reason.WAIT), decide(taskStartedAt = now - 130_000L, now = now))
        val after = sight(State(), status(), now)
        assertEquals(Decision.Reload, decide(state = after, s = status(), now = now + ReplyWatchdog.CONFIRM_MS, taskStartedAt = now - 130_000L))
    }

    // ---------------------------------------------------------------- staleness

    @Test
    fun track_staleAfterLimits() {
        val now = BASE
        // empty goes cold (covers the page-side 15s grace)
        assertEquals(Decision.Track(Reason.STALE), decide(s = status(at = now - ReplyWatchdog.STALE_EMPTY_MS - 1), now = now))
        // errors stay actionable for 45s
        assertEquals(Decision.Track(Reason.WAIT), decide(s = status("error:x", at = now - 40_000L), now = now))
        assertEquals(Decision.Track(Reason.STALE), decide(s = status("error:x", at = now - 50_000L), now = now))
    }

    // ---------------------------------------------------------------- cooldown

    @Test
    fun cooldownPerPath() {
        val now = BASE
        // Confirmed problem (skip WAIT), path reloaded 10s ago → cooldown.
        val state = State(lastReloadAt = mapOf("/c/s1" to now - 10_000L)).let { confirmed(it) }
        assertEquals(Decision.Track(Reason.COOLDOWN), decide(state = state, now = now))
        assertEquals(Decision.Reload, decide(state = state, now = now + 25_000L))
        // A different path has no cooldown.
        val otherPath = state.copy(firstSeen = state.firstSeen + ("/c/s2|empty" to now - 30_000L))
        assertEquals(Decision.Reload, decide(state = otherPath, s = status(path = "/c/s2"), now = now))
    }

    @Test
    fun noCooldownOnFirstEverReload() {
        val state = confirmed(State(lastReloadAt = mapOf("/c/s1" to 0L)))
        assertEquals(Decision.Reload, decide(state = state, now = 5_000L + BASE))
    }

    // ---------------------------------------------------------------- problem budget

    @Test
    fun sameProblemReloadsAtMostTwice() {
        var state = State()
        var now = BASE
        fun step(s: Status = status("error:x")): Decision {
            val d = decide(state = state, s = s, now = now)
            state = ReplyWatchdog.applied(state, s, d, now)
            return d
        }
        fun confirm() {
            assertEquals(Decision.Track(Reason.WAIT), step())
            now += ReplyWatchdog.CONFIRM_MS
        }
        confirm(); assertEquals(Decision.Reload, step()); now += 31_000L
        confirm(); assertEquals(Decision.Reload, step()); now += 31_000L
        // budget exhausted → one nudge, then silence (capped problems skip the
        // confirmation window entirely)
        assertEquals(Decision.Track(Reason.NAG), step())
        assertEquals(setOf("/c/s1|error:x"), state.nagged)
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
        val withErrY = state.copy(firstSeen = state.firstSeen + ("/c/s1|error:y" to BASE - 30_000L))
        assertEquals(Decision.Reload, decide(state = withErrY, s = status("error:y")))
        val withEmpty = state.copy(firstSeen = state.firstSeen + ("/c/s1|empty" to BASE - 30_000L))
        assertEquals(Decision.Reload, decide(state = withEmpty, s = status("empty")))
    }

    // ---------------------------------------------------------------- state

    @Test
    fun applied_updatesState() {
        val state = State()
        val now = BASE
        val s = status("error:x")
        val problem = "/c/s1|error:x"
        val waited = ReplyWatchdog.applied(state, s, Decision.Track(Reason.WAIT), now)
        assertEquals(mapOf(problem to now), waited.firstSeen)
        // A second WAIT never rewinds the clock.
        val waitedAgain = ReplyWatchdog.applied(waited, s, Decision.Track(Reason.WAIT), now + 999_999L)
        assertEquals(mapOf(problem to now), waitedAgain.firstSeen)
        // Reload counts and clears the sighting (fresh cycle afterwards).
        val reloaded = ReplyWatchdog.applied(waited, s, Decision.Reload, now + 2_000L + BASE)
        assertEquals(mapOf("/c/s1" to now + 2_000L + BASE), reloaded.lastReloadAt)
        assertEquals(mapOf(problem to 1), reloaded.reloads)
        assertEquals(emptyMap<String, Long>(), reloaded.firstSeen)
        val nagged = ReplyWatchdog.applied(state, s, Decision.Track(Reason.NAG), now)
        assertEquals(setOf(problem), nagged.nagged)
        // plain tracks don't change anything
        val plain = ReplyWatchdog.applied(state, s, Decision.Track(Reason.COOLDOWN), now)
        assertEquals(state, plain)
    }
}
