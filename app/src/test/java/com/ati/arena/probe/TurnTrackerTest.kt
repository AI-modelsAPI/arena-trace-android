package com.ati.arena.probe

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TurnTrackerTest {

    // ---- turn counting within one conversation ----

    @Test
    fun tokensWithinSameConversationIncrementTurns() {
        val t = TurnTracker()
        // Binding to the very first conversation counts as a switch (from none).
        assertEquals(1 to true, t.onToken("s1"))
        assertEquals(2 to false, t.onToken("s1"))
        assertEquals(3 to false, t.onToken("s1"))
        assertEquals(3, t.turnCount)
    }

    @Test
    fun emptySessionTokenKeepsCurrentConversation() {
        val t = TurnTracker()
        t.onToken("s1")
        // SSE events can surface before the URL carries a session id — such a
        // token is the NEXT turn of the same conversation, not a switch.
        assertEquals(2 to false, t.onToken(""))
        assertEquals("s1", t.sessionId)
    }

    // ---- conversation switching ----

    @Test
    fun differentSessionSwitchesAndRestartsAtTurnOne() {
        val t = TurnTracker()
        t.onToken("s1")
        t.onToken("s1")
        val (turn, switched) = t.onToken("s2")
        assertTrue(switched)
        assertEquals(1, turn)
        assertEquals("s2", t.sessionId)
    }

    @Test
    fun sameModelInNewConversationIsNotRouted() {
        // The reported bug: after switching chats, later rounds showing the SAME
        // model were still flagged routed (yellow ball) because the previous
        // conversation's first model lingered.
        val t = TurnTracker()
        t.onToken("s1")
        t.record(1, "model-x")

        t.onToken("s2") // switch — must reset firstModel
        assertEquals("", t.firstModel)
        assertFalse(t.routed)
        assertEquals("", t.lastModel)

        t.record(1, "model-x")
        assertFalse("same model as the previous chat must NOT read routed", t.routed)
        assertEquals("model-x", t.firstModel)
    }

    @Test
    fun routedOnlyWhenModelDiffersFromCurrentConversationsFirst() {
        val t = TurnTracker()
        t.onToken("s1")
        t.record(1, "model-a")
        assertFalse(t.routed)

        t.onToken("s1")
        val switched = t.record(2, "model-b")
        assertTrue(t.routed)
        assertTrue(switched.startsWith("第 2 轮 · 已切换模型 → model-b"))

        // Back to the first model → no longer routed.
        t.onToken("s1")
        t.record(3, "model-a")
        assertFalse(t.routed)
    }

    @Test
    fun routedButUnchangedFromPreviousTurnReadsNonFirstModel() {
        val t = TurnTracker()
        t.onToken("s1")
        t.record(1, "model-a")
        t.onToken("s1")
        t.record(2, "model-b")
        t.onToken("s1")
        val line = t.record(3, "model-b") // differs from first, same as previous
        assertTrue(t.routed)
        assertTrue(line.startsWith("第 3 轮 · model-b（非首轮模型）"))
    }

    // ---- history line ----

    @Test
    fun historyLineListsTurnsNewestLast() {
        val t = TurnTracker()
        t.onToken("s1")
        val status = t.record(1, "model-a")
        t.onToken("s1")
        val status2 = t.record(2, "model-b")
        assertEquals("第 1 轮 · model-a\n本会话: R1 model-a", status)
        assertEquals("第 2 轮 · 已切换模型 → model-b\n本会话: R1 model-a · R2 model-b", status2)
    }

    @Test
    fun historyIsCappedAtSixEntries() {
        val t = TurnTracker()
        t.onToken("s1")
        for (i in 1..8) {
            t.onToken("s1")
            t.record(i, "model-a")
        }
        val line = t.historyLine()
        assertFalse(line.contains("R1 "))
        assertFalse(line.contains("R2 "))
        assertTrue(line.contains("R3 model-a"))
        assertTrue(line.contains("R8 model-a"))
        assertEquals(6, line.removePrefix("本会话: ").split(" · ").size)
    }

    @Test
    fun switchClearsHistorySoNewConversationLogIsComplete() {
        // "日志中的轮次不完整": turns from the previous chat must not survive
        // into the new chat's history line.
        val t = TurnTracker()
        t.onToken("s1")
        t.record(1, "model-a")
        t.onToken("s1")
        t.record(2, "model-b")

        t.onToken("s2")
        assertEquals("本会话: ", t.historyLine())
        t.record(1, "model-b")
        assertEquals("本会话: R1 model-b", t.historyLine())
    }

    // ---- reset / clearRouted ----

    @Test
    fun resetClearsEverything() {
        val t = TurnTracker()
        t.onToken("s1")
        t.record(1, "model-a")
        t.reset()
        assertEquals("", t.sessionId)
        assertEquals(0, t.turnCount)
        assertEquals("", t.firstModel)
        assertEquals("", t.lastModel)
        assertFalse(t.routed)
        assertEquals("本会话: ", t.historyLine())
    }

    @Test
    fun clearRoutedKeepsTurnState() {
        val t = TurnTracker()
        t.onToken("s1")
        t.record(1, "model-a")
        t.onToken("s1")
        t.record(2, "model-b")
        assertTrue(t.routed)
        t.clearRouted()
        assertFalse(t.routed)
        assertEquals("model-a", t.firstModel) // turn state untouched
        assertEquals("本会话: R1 model-a · R2 model-b", t.historyLine())
    }
}
