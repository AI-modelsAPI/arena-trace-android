package com.ati.arena.session

import com.ati.arena.session.TurnTracker.Status
import com.ati.arena.session.TurnTracker.Turn
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TurnFormatTest {

    private fun ok(n: Int, model: String) = Turn(n, "k$n", Status.RESOLVED, listOf(model))
    private fun pending(n: Int) = Turn(n, "k$n", Status.PENDING)
    private fun failed(n: Int, note: String) = Turn(n, "k$n", Status.FAILED, note = note)

    @Test
    fun headlineForFirstTurn() {
        assertEquals("第 1 轮 · model-a", TurnFormat.headline(listOf(ok(1, "model-a")), "model-a"))
    }

    @Test
    fun headlineFlagsASwitchAwayFromThePreviousTurn() {
        val turns = listOf(ok(1, "model-a"), ok(2, "model-b"))
        assertEquals("第 2 轮 · 已切换模型 → model-b", TurnFormat.headline(turns, "model-a"))
    }

    @Test
    fun headlineFlagsNonFirstModelUnchangedFromPrevious() {
        val turns = listOf(ok(1, "model-a"), ok(2, "model-b"), ok(3, "model-b"))
        assertEquals("第 3 轮 · model-b（非首轮模型）", TurnFormat.headline(turns, "model-a"))
    }

    @Test
    fun headlineForPendingAndFailedTurns() {
        assertEquals("第 2 轮 · 识别中…", TurnFormat.headline(listOf(ok(1, "a"), pending(2)), "a"))
        assertEquals("第 2 轮 · 令牌已过期", TurnFormat.headline(listOf(ok(1, "a"), failed(2, "令牌已过期")), "a"))
    }

    @Test
    fun historyLineListsTurnsNewestLast() {
        val line = TurnFormat.historyLine(listOf(ok(1, "a"), ok(2, "b"), pending(3)))
        assertEquals("本会话 3 轮: R1 a · R2 b · R3 识别中…", line)
    }

    @Test
    fun historyLineIsCappedAndMarksElision() {
        val turns = (1..8).map { ok(it, "m$it") }
        val line = TurnFormat.historyLine(turns)
        assertTrue(line.startsWith("本会话 8 轮: … · R3 m3"))
        assertTrue(line.endsWith("R8 m8"))
    }

    @Test
    fun emptyLogRendersNothing() {
        assertEquals("", TurnFormat.status(emptyList(), ""))
        assertEquals("", TurnFormat.historyLine(emptyList()))
    }

    @Test
    fun multiModelTurnLabelJoinsModels() {
        val t = Turn(1, "k1", Status.RESOLVED, listOf("a", "b"))
        assertEquals("a / b", TurnFormat.label(t))
    }

    @Test
    fun labelAppendsStrengthTierOnlyWhenPresent() {
        val t = Turn(3, "k3", Status.RESOLVED, listOf("model-a"), strength = "high")
        assertEquals("model-a · high", TurnFormat.label(t))
        assertEquals("第 3 轮 · model-a · high", TurnFormat.headline(listOf(t), "model-a"))
        // No tier → rendering unchanged anywhere.
        assertEquals("model-a", TurnFormat.label(ok(1, "model-a")))
        assertEquals("第 1 轮 · model-a", TurnFormat.headline(listOf(ok(1, "model-a")), "model-a"))
    }
}
