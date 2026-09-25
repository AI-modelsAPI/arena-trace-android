package com.ati.arena.ui

import com.ati.arena.session.TurnIntake.SessionView
import com.ati.arena.session.TurnTracker.Status
import com.ati.arena.session.TurnTracker.Turn
import com.ati.arena.ui.HudFormat.QuotaLevel
import com.ati.arena.ui.HudFormat.Tone
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalTime

class HudFormatTest {

    private fun resolved(n: Int, model: String) = Turn(n, "k$n", Status.RESOLVED, listOf(model))
    private fun pending(n: Int) = Turn(n, "k$n", Status.PENDING)
    private fun failed(n: Int, note: String) = Turn(n, "k$n", Status.FAILED, note = note)

    private fun view(
        turns: List<Turn> = emptyList(),
        models: List<String> = turns.lastOrNull { it.status == Status.RESOLVED }?.models.orEmpty(),
        first: String = turns.firstOrNull { it.status == Status.RESOLVED }?.model.orEmpty(),
        routed: Boolean = false,
        restored: Boolean = false,
    ) = SessionView("s1", turns, models, first, routed, restored)

    // ---- quota

    @Test
    fun quotaLevelBoundaries() {
        assertEquals(QuotaLevel.UNKNOWN, HudFormat.quotaLevel(-1))
        assertEquals(QuotaLevel.UNKNOWN, HudFormat.quotaLevel(101))
        assertEquals(QuotaLevel.CRITICAL, HudFormat.quotaLevel(0))
        assertEquals(QuotaLevel.CRITICAL, HudFormat.quotaLevel(9))
        assertEquals(QuotaLevel.LOW, HudFormat.quotaLevel(10))
        assertEquals(QuotaLevel.LOW, HudFormat.quotaLevel(19))
        assertEquals(QuotaLevel.OK, HudFormat.quotaLevel(20))
        assertEquals(QuotaLevel.OK, HudFormat.quotaLevel(100))
    }

    @Test
    fun quotaTextShowsDashWhenUnknown() {
        assertEquals("42%", HudFormat.quotaText(42))
        assertEquals("—", HudFormat.quotaText(-1))
    }

    @Test
    fun countdownRoundsUpAndPads() {
        assertEquals("0:00:00", HudFormat.countdown(0))
        assertEquals("0:00:01", HudFormat.countdown(1))
        assertEquals("0:00:01", HudFormat.countdown(1000))
        assertEquals("1:02:03", HudFormat.countdown((3600 + 2 * 60 + 3) * 1000L))
        assertEquals("0:00:00", HudFormat.countdown(-5000))
    }

    @Test
    fun quotaDetailPrefersCountdownThenError() {
        assertEquals("额度读取中…", HudFormat.quotaDetail(false, 0, 0, ""))
        assertEquals("未登录 Arena", HudFormat.quotaDetail(false, 0, 0, "未登录 Arena"))
        assertEquals("0:01:00 后重置", HudFormat.quotaDetail(true, 61_000, 1_000, "HTTP 429"))
        assertEquals("已到重置时间", HudFormat.quotaDetail(true, 1_000, 2_000, ""))
        assertEquals("HTTP 429", HudFormat.quotaDetail(true, 0, 2_000, "HTTP 429"))
    }

    // ---- session

    @Test
    fun headlineCoversEveryState() {
        assertEquals("新对话 · 等待会话流…", HudFormat.headline(null, onNewChat = true))
        assertEquals("打开一个 Arena 对话后自动识别模型", HudFormat.headline(null, onNewChat = false))
        assertEquals("等待会话流…", HudFormat.headline(view(), onNewChat = false))
        assertEquals("已恢复本地记录的模型", HudFormat.headline(view(models = listOf("m1"), restored = true), false))
        assertEquals("第 1 轮 · m1", HudFormat.headline(view(listOf(resolved(1, "m1"))), false))
        assertEquals(
            "第 1 轮 · m1 · 本地记录",
            HudFormat.headline(view(listOf(resolved(1, "m1")), restored = true), false),
        )
    }

    @Test
    fun turnSummaryListsTotalsAndFlags() {
        assertEquals("", HudFormat.turnSummary(null))
        assertEquals("", HudFormat.turnSummary(view()))
        val v = view(listOf(resolved(1, "m1"), resolved(2, "m2"), pending(3)), routed = true, restored = true)
        assertEquals("共 3 轮 · 首轮 m1 · 当前已切换 · 本地记录", HudFormat.turnSummary(v))
    }

    @Test
    fun turnRowsAreNewestFirstAndFlagRoutedTurns() {
        val rows = HudFormat.turnRows(
            listOf(resolved(1, "m1"), resolved(2, "m2"), failed(3, "令牌已过期"), pending(4), resolved(5, "m1")),
            firstModel = "m1",
        )
        assertEquals(listOf(5, 4, 3, 2, 1), rows.map { it.number })
        assertEquals(listOf(false, false, false, true, false), rows.map { it.routed })
        assertEquals("识别中…", rows[1].label)
        assertEquals("令牌已过期", rows[2].label)
        assertEquals(Status.FAILED, rows[2].status)
    }

    @Test
    fun turnRowsNeverRouteWithoutAFirstModel() {
        val rows = HudFormat.turnRows(listOf(pending(1), resolved(2, "m2")), firstModel = "")
        assertTrue(rows.none { it.routed })
    }

    // ---- pill

    @Test
    fun pillShowsTaskProgressFirst() {
        val v = view(listOf(resolved(1, "m1")))
        val starting = HudFormat.pill(v, false, TaskState.Probe(0, 5, 0))
        assertEquals("探针启动中…", starting.label)
        assertTrue(starting.busy)
        assertEquals(Tone.ACTIVE, starting.tone)
        assertEquals("探针 2/5 · 命中 1", HudFormat.pill(v, false, TaskState.Probe(2, 5, 1)).label)
        assertEquals("清理中…", HudFormat.pill(v, false, TaskState.Cleanup(0)).label)
        assertEquals("清理中 · 已归档 3", HudFormat.pill(v, false, TaskState.Cleanup(3)).label)
    }

    @Test
    fun pillShowsModelWithRoutedTone() {
        val plain = HudFormat.pill(view(listOf(resolved(1, "m1"))), false, TaskState.Idle)
        assertEquals(HudFormat.Pill("m1", Tone.NORMAL, busy = false), plain)
        val routed = HudFormat.pill(
            view(listOf(resolved(1, "m1"), resolved(2, "m2")), routed = true),
            false,
            TaskState.Idle,
        )
        assertEquals(HudFormat.Pill("m2", Tone.ROUTED, busy = false), routed)
    }

    @Test
    fun pillFallsBackToPendingNewChatOrRingOnly() {
        assertEquals("识别中…", HudFormat.pill(view(listOf(pending(1))), false, TaskState.Idle).label)
        assertEquals("新对话", HudFormat.pill(null, true, TaskState.Idle).label)
        val ringOnly = HudFormat.pill(null, false, TaskState.Idle)
        assertEquals("", ringOnly.label)
        assertFalse(ringOnly.busy)
    }

    @Test
    fun flashesSummariseResults() {
        assertEquals("探针结束 · 命中 2", HudFormat.finishedFlash(TaskState.Probe(5, 5, 2)))
        assertEquals("清理完成 · 已归档 7", HudFormat.finishedFlash(TaskState.Cleanup(7)))
        assertNull(HudFormat.finishedFlash(TaskState.Idle))
        assertEquals("已发送 ✓", HudFormat.quickSendFlash("已发送到当前对话"))
        assertEquals("发送失败", HudFormat.quickSendFlash("发送失败：timeout"))
        assertEquals("发送失败", HudFormat.quickSendFlash("任务运行中，请先停止再发送"))
    }

    // ---- misc

    @Test
    fun prefixPreviewUsesTheNextSuffixForThatPrefix() {
        assertEquals("预览：claude-opus-5-001", HudFormat.prefixPreview("", emptyMap()))
        assertEquals("预览：[探针] claude-opus-5-001", HudFormat.prefixPreview("[探针] ", emptyMap()))
        val counters = mapOf("p:[探针]|claudeopus5" to 4)
        assertEquals("预览：[探针] claude-opus-5-005", HudFormat.prefixPreview("[探针] ", counters))
    }

    @Test
    fun logEntryIsTimestamped() {
        assertEquals("09:05:07 hello", HudFormat.logEntry("  hello ", LocalTime.of(9, 5, 7)))
    }
}
