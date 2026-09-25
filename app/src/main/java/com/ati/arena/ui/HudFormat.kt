package com.ati.arena.ui

import com.ati.arena.probe.ProbeLogic
import com.ati.arena.session.TurnFormat
import com.ati.arena.session.TurnIntake
import com.ati.arena.session.TurnTracker.Status
import com.ati.arena.session.TurnTracker.Turn
import java.time.LocalTime
import java.util.Locale

/**
 * Every piece of text and state the overlay shows, computed from plain data (no
 * Android types), so the UI classes only bind values and this stays unit-tested.
 */
object HudFormat {

    enum class QuotaLevel { UNKNOWN, OK, LOW, CRITICAL }

    /** Colour role of a label: normal, routed (model differs from the first turn), unknown, busy. */
    enum class Tone { NORMAL, ROUTED, MUTED, ACTIVE }

    /** What the floating pill shows next to its quota ring ("" = ring only). */
    data class Pill(val label: String, val tone: Tone, val busy: Boolean)

    /** One row in the turn list. */
    data class TurnRow(val number: Int, val label: String, val status: Status, val routed: Boolean)

    const val LOW_QUOTA = 20
    const val CRITICAL_QUOTA = 10

    /** Model used for the prefix preview ("[探针] claude-opus-5-001"). */
    const val SAMPLE_MODEL = "claude-opus-5"

    // ---------------------------------------------------------------- quota

    fun quotaLevel(percent: Int): QuotaLevel = when {
        percent !in 0..100 -> QuotaLevel.UNKNOWN
        percent < CRITICAL_QUOTA -> QuotaLevel.CRITICAL
        percent < LOW_QUOTA -> QuotaLevel.LOW
        else -> QuotaLevel.OK
    }

    fun quotaText(percent: Int): String = if (percent in 0..100) "$percent%" else "—"

    /** "h:mm:ss", rounded up so the final second reads 0:00:01 rather than 0:00:00. */
    fun countdown(ms: Long): String {
        val s = maxOf(0L, (ms + 999) / 1000)
        return String.format(Locale.ROOT, "%d:%02d:%02d", s / 3600, s % 3600 / 60, s % 60)
    }

    /**
     * Line under the quota figure: the reset countdown when we have data, otherwise
     * why there is no data yet (loading / fetch error).
     */
    fun quotaDetail(hasData: Boolean, resetAtMs: Long, nowMs: Long, error: String): String = when {
        !hasData -> error.ifEmpty { "额度读取中…" }
        resetAtMs <= 0L -> error
        resetAtMs > nowMs -> countdown(resetAtMs - nowMs) + " 后重置"
        else -> "已到重置时间"
    }

    // ---------------------------------------------------------------- session

    /** One-line summary of the conversation on screen, under the model name. */
    fun headline(view: TurnIntake.SessionView?, onNewChat: Boolean): String = when {
        view == null -> if (onNewChat) "新对话 · 等待会话流…" else "打开一个 Arena 对话后自动识别模型"
        view.turns.isNotEmpty() ->
            TurnFormat.headline(view.turns, view.firstModel) + if (view.restored) " · 本地记录" else ""
        view.currentModels.isNotEmpty() -> "已恢复本地记录的模型"
        else -> "等待会话流…"
    }

    /** "共 5 轮 · 首轮 m1 · 当前已切换 · 本地记录" ("" when there are no turns). */
    fun turnSummary(view: TurnIntake.SessionView?): String {
        val turns = view?.turns.orEmpty()
        if (view == null || turns.isEmpty()) return ""
        val parts = mutableListOf("共 ${turns.maxOf { it.number }} 轮")
        if (view.firstModel.isNotEmpty()) parts += "首轮 ${view.firstModel}"
        if (view.routed) parts += "当前已切换"
        if (view.restored) parts += "本地记录"
        return parts.joinToString(" · ")
    }

    /** Turn list rows, newest first. A row is "routed" when its model differs from the first turn's. */
    fun turnRows(turns: List<Turn>, firstModel: String): List<TurnRow> =
        turns.sortedByDescending { it.number }.map { t ->
            TurnRow(
                number = t.number,
                label = TurnFormat.label(t),
                status = t.status,
                routed = t.status == Status.RESOLVED && firstModel.isNotEmpty() && t.model != firstModel,
            )
        }

    // ---------------------------------------------------------------- pill

    fun pill(view: TurnIntake.SessionView?, onNewChat: Boolean, task: TaskState): Pill = when (task) {
        is TaskState.Probe -> Pill(
            if (task.round <= 0) "探针启动中…" else "探针 ${task.round}/${task.maxRounds} · 命中 ${task.hits}",
            Tone.ACTIVE,
            busy = true,
        )
        is TaskState.Cleanup -> Pill(
            if (task.archived <= 0) "清理中…" else "清理中 · 已归档 ${task.archived}",
            Tone.ACTIVE,
            busy = true,
        )
        TaskState.Idle -> {
            val model = view?.currentModels.orEmpty().joinToString(" / ")
            when {
                model.isNotEmpty() -> Pill(model, if (view?.routed == true) Tone.ROUTED else Tone.NORMAL, busy = false)
                view?.latestTurn?.status == Status.PENDING -> Pill("识别中…", Tone.MUTED, busy = false)
                onNewChat -> Pill("新对话", Tone.MUTED, busy = false)
                else -> Pill("", Tone.MUTED, busy = false)
            }
        }
    }

    /** Short pill message shown for a moment after a task ends. */
    fun finishedFlash(task: TaskState): String? = when (task) {
        is TaskState.Probe -> "探针结束 · 命中 ${task.hits}"
        is TaskState.Cleanup -> "清理完成 · 已归档 ${task.archived}"
        TaskState.Idle -> null
    }

    /** Pill message for a quick-send result from [com.ati.arena.probe.ProbeController.quickSend]. */
    fun quickSendFlash(result: String): String = if (result.startsWith("已发送")) "已发送 ✓" else "发送失败"

    // ---------------------------------------------------------------- misc

    /** Helper line under the prefix field: what a hit conversation will be renamed to. */
    fun prefixPreview(prefix: String, counters: Map<String, Int>, model: String = SAMPLE_MODEL): String {
        val p = ProbeLogic.sanitizePrefix(prefix)
        val suffix = ProbeLogic.nextSuffixFor(p, model, counters).first
        return "预览：" + ProbeLogic.hitTitle(p, model, suffix)
    }

    /** "12:03:04 message" for the activity log. */
    fun logEntry(message: String, time: LocalTime): String =
        String.format(Locale.ROOT, "%02d:%02d:%02d %s", time.hour, time.minute, time.second, message.trim())
}
