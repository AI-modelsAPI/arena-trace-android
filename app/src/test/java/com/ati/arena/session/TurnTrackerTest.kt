package com.ati.arena.session

import com.ati.arena.session.TurnTracker.Seed
import com.ati.arena.session.TurnTracker.Status
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TurnTrackerTest {

    // ---- one run = one turn ----

    @Test
    fun runsAreNumberedInFirstSeenOrder() {
        val t = TurnTracker()
        assertEquals(1, t.onRun("s1", "k1").turn.number)
        assertEquals(2, t.onRun("s1", "k2").turn.number)
        assertEquals(3, t.onRun("s1", "k3").turn.number)
        assertEquals(listOf(1, 2, 3), t.turns("s1").map { it.number })
    }

    @Test
    fun reSeeingARunNeverCreatesATurn() {
        // The stream repeats a run's token on many records and replays it when
        // the conversation is reopened — all of that is ONE turn.
        val t = TurnTracker()
        assertTrue(t.onRun("s1", "k1").isNew)
        repeat(5) { assertFalse(t.onRun("s1", "k1").isNew) }
        assertEquals(1, t.turns("s1").size)
    }

    @Test
    fun interleavedRunsDoNotProducePhantomTurns() {
        // Old bug: dedup compared only against the LAST token, so A→B→A counted
        // three turns and logged run A twice.
        val t = TurnTracker()
        t.onRun("s1", "kA")
        t.onRun("s1", "kB")
        val again = t.onRun("s1", "kA")
        assertFalse(again.isNew)
        assertEquals(1, again.turn.number)
        assertEquals(2, t.turns("s1").size)
    }

    // ---- conversations are isolated ----

    @Test
    fun switchingConversationsNeverMixesLogs() {
        val t = TurnTracker()
        t.onRun("s1", "a1"); t.resolve("s1", "a1", listOf("model-a"))
        t.onRun("s1", "a2"); t.resolve("s1", "a2", listOf("model-b"))

        t.onRun("s2", "b1"); t.resolve("s2", "b1", listOf("model-x"))
        assertEquals(listOf("model-x"), t.turns("s2").map { it.model })

        // Back to s1: its log is intact and numbering continues.
        assertEquals(listOf("model-a", "model-b"), t.turns("s1").map { it.model })
        assertEquals(3, t.onRun("s1", "a3").turn.number)
        assertEquals(listOf("model-x"), t.turns("s2").map { it.model })
    }

    @Test
    fun sameModelInAnotherConversationIsNotRouted() {
        val t = TurnTracker()
        t.onRun("s1", "a1"); t.resolve("s1", "a1", listOf("model-a"))
        t.onRun("s1", "a2"); t.resolve("s1", "a2", listOf("model-b"))
        assertTrue(t.isRouted("s1"))

        t.onRun("s2", "b1"); t.resolve("s2", "b1", listOf("model-b"))
        assertFalse(t.isRouted("s2"))
        assertEquals("model-b", t.firstModel("s2"))
    }

    // ---- resolve / fail ----

    @Test
    fun resolvedTurnIsNeverDowngraded() {
        val t = TurnTracker()
        t.onRun("s1", "k1")
        t.resolve("s1", "k1", listOf("m1"))
        val after = t.fail("s1", "k1", "boom")
        assertEquals(Status.RESOLVED, after?.status)
        assertEquals("m1", t.turn("s1", "k1")?.model)
    }

    @Test
    fun resolveWithNoModelsMarksFailed() {
        val t = TurnTracker()
        t.onRun("s1", "k1")
        assertEquals(Status.FAILED, t.resolve("s1", "k1", listOf(" ", ""))?.status)
    }

    @Test
    fun unknownSessionOrRunIsIgnored() {
        val t = TurnTracker()
        assertNull(t.resolve("nope", "k1", listOf("m")))
        t.onRun("s1", "k1")
        assertNull(t.fail("s1", "other", "x"))
    }

    // ---- routed / current model use turn ORDER, not resolution order ----

    @Test
    fun latestResolvedFollowsTurnNumberEvenIfResolvedOutOfOrder() {
        val t = TurnTracker()
        t.onRun("s1", "k1"); t.onRun("s1", "k2")
        t.resolve("s1", "k2", listOf("second"))
        t.resolve("s1", "k1", listOf("first")) // late answer for the older turn
        assertEquals("second", t.latestResolved("s1")?.model)
        assertEquals("first", t.firstModel("s1"))
        assertTrue(t.isRouted("s1"))
        assertEquals(listOf("second"), t.currentModels("s1"))
    }

    @Test
    fun backToFirstModelIsNotRouted() {
        val t = TurnTracker()
        t.onRun("s1", "k1"); t.resolve("s1", "k1", listOf("a"))
        t.onRun("s1", "k2"); t.resolve("s1", "k2", listOf("b"))
        t.onRun("s1", "k3"); t.resolve("s1", "k3", listOf("a"))
        assertFalse(t.isRouted("s1"))
    }

    // ---- seeding from history ----

    @Test
    fun seedingRestoresNumbersAndContinuesAfterThem() {
        val t = TurnTracker()
        t.seed("s1", listOf(Seed("k1", 1, listOf("m1")), Seed("k2", 2, emptyList())))
        assertTrue(t.isSeeded("s1"))
        val turns = t.turns("s1")
        assertEquals(Status.RESOLVED, turns[0].status)
        assertEquals(Status.FAILED, turns[1].status)
        // A replayed known run keeps its number; a new run continues at 3.
        assertFalse(t.onRun("s1", "k2").isNew)
        assertEquals(3, t.onRun("s1", "k3").turn.number)
    }

    @Test
    fun seedUpgradesPendingTurnAndIsIdempotent() {
        val t = TurnTracker()
        t.onRun("s1", "k1")
        t.seed("s1", listOf(Seed("k1", 1, listOf("m1"))))
        t.seed("s1", listOf(Seed("k1", 1, listOf("m1"))))
        assertEquals(1, t.turns("s1").size)
        assertEquals("m1", t.turn("s1", "k1")?.model)
    }

    // ---- bounds ----

    @Test
    fun leastRecentlyUsedConversationIsEvicted() {
        val t = TurnTracker(maxSessions = 2)
        t.onRun("s1", "k"); t.onRun("s2", "k")
        t.turns("s1") // touch s1
        t.onRun("s3", "k")
        assertTrue(t.turns("s2").isEmpty())
        assertEquals(1, t.turns("s1").size)
        assertEquals(1, t.turns("s3").size)
    }

    @Test
    fun turnsPerConversationAreCappedKeepingTheNewest() {
        val t = TurnTracker(maxTurnsPerSession = 3)
        for (i in 1..5) t.onRun("s1", "k$i")
        assertEquals(listOf(3, 4, 5), t.turns("s1").map { it.number })
    }

    @Test
    fun forgetAndClear() {
        val t = TurnTracker()
        t.onRun("s1", "k"); t.seed("s1", emptyList())
        t.forget("s1")
        assertTrue(t.turns("s1").isEmpty())
        assertFalse(t.isSeeded("s1"))
        t.onRun("s2", "k")
        t.clear()
        assertTrue(t.turns("s2").isEmpty())
    }

    @Test
    fun resolveAppliesStrengthAndKeepsItAcrossRefreshes() {
        val t = TurnTracker()
        t.onRun("s1", "a1")
        t.resolve("s1", "a1", listOf("m"), "high")
        assertEquals("high", t.turn("s1", "a1")?.strength)
        t.resolve("s1", "a1", listOf("m"))
        assertEquals("high", t.turn("s1", "a1")?.strength)
        t.resolve("s1", "a1", listOf("m"), " max ")
        assertEquals("max", t.turn("s1", "a1")?.strength)
        // Seeds restore the tier and merge it into a pending re-sighting.
        t.seed("s2", listOf(Seed("k9", 3, listOf("x"), "ultra")))
        assertEquals("ultra", t.turn("s2", "k9")?.strength)
    }
}
