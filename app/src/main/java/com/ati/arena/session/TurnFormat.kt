package com.ati.arena.session

import com.ati.arena.session.TurnTracker.Status
import com.ati.arena.session.TurnTracker.Turn

/**
 * Human-readable rendering of a conversation's turn log (pure, unit-tested).
 * The log is always rendered from ONE conversation's turns, so entries from
 * another chat can never appear in it.
 */
object TurnFormat {

    /** Newest turns shown on the compact history line. */
    const val MAX_HISTORY = 6

    private const val PENDING_LABEL = "识别中…"

    /** Label for one turn: its model, "识别中…", or the failure note. */
    fun label(turn: Turn): String = when (turn.status) {
        Status.RESOLVED -> turn.models.joinToString(" / ")
        Status.PENDING -> PENDING_LABEL
        Status.FAILED -> turn.note.ifEmpty { TurnTracker.NOTE_UNKNOWN }
    }

    /**
     * Headline for the newest turn, e.g. "第 3 轮 · model-b（非首轮模型）".
     * [firstModel] is the conversation's first resolved model ("" when unknown).
     */
    fun headline(turns: List<Turn>, firstModel: String): String {
        val latest = turns.maxByOrNull { it.number } ?: return ""
        val prefix = "第 ${latest.number} 轮 · "
        if (latest.status != Status.RESOLVED) return prefix + label(latest)
        val previous = turns.filter { it.number < latest.number && it.status == Status.RESOLVED }
            .maxByOrNull { it.number }
        val routed = firstModel.isNotEmpty() && latest.model != firstModel
        val changedFromPrevious = previous != null && previous.model != latest.model
        return when {
            routed && changedFromPrevious -> prefix + "已切换模型 → " + label(latest)
            routed -> prefix + label(latest) + "（非首轮模型）"
            else -> prefix + label(latest)
        }
    }

    /** "本会话 3 轮: R1 m1 · R2 m2 · R3 识别中…" — newest last, capped at [max]. */
    fun historyLine(turns: List<Turn>, max: Int = MAX_HISTORY): String {
        if (turns.isEmpty()) return ""
        val sorted = turns.sortedBy { it.number }
        val shown = sorted.takeLast(max).joinToString(" · ") { "R${it.number} ${label(it)}" }
        val total = sorted.last().number
        return "本会话 $total 轮: " + (if (sorted.size > max) "… · " else "") + shown
    }

    /** Headline + history line, the two-line HUD status. */
    fun status(turns: List<Turn>, firstModel: String): String {
        if (turns.isEmpty()) return ""
        return headline(turns, firstModel) + "\n" + historyLine(turns)
    }
}
