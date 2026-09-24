package com.ati.arena.probe

/**
 * Per-conversation turn tracking for the floating ball + HUD status line.
 *
 * Arena issues a fresh run token per turn, and a turn may be routed to a
 * different model — so each new token is a new turn. The routed flag (ball
 * center drawn orange-yellow) is true when a turn's model differs from the
 * CURRENT conversation's first resolved model.
 *
 * Reset discipline: a non-empty sessionId that differs from the tracked one
 * means the chat was SWITCHED (sidebar tap, probe newChat). That switch —
 * observed at token time — is the authoritative reset point. Navigation URL
 * events also reset, but they can be missed (replaceState) or skipped by
 * sentinel guards, so they are only a best-effort complement. Pure Kotlin so
 * the switching semantics are unit-testable.
 */
class TurnTracker {

    /** Conversation the tracked turns belong to; "" before the first token. */
    var sessionId: String = ""
        private set

    /** 1-based number of the current turn within [sessionId]. */
    var turnCount: Int = 0
        private set

    /** First resolved model of the current conversation ("" until resolved). */
    var firstModel: String = ""
        private set

    /** Model of the previous turn ("" before the first resolved turn). */
    var lastModel: String = ""
        private set

    /** true when the current turn's model differs from the conversation's first. */
    var routed: Boolean = false
        private set

    private val history = ArrayDeque<String>()

    /** Forget all turn state, optionally binding to a new conversation. */
    fun reset(newSessionId: String = "") {
        sessionId = newSessionId
        turnCount = 0
        firstModel = ""
        lastModel = ""
        routed = false
        history.clear()
    }

    /**
     * Clear only the routed flag. Used when echoing a REMEMBERED model for a
     * chat we are not live-tracking: the flag compares against the tracked
     * conversation's first model, so it must not leak onto another chat.
     */
    fun clearRouted() {
        routed = false
    }

    /**
     * A fresh run token arrived = a new turn. A non-empty [sessionId] differing
     * from the tracked conversation resets all turn state first. Empty-session
     * tokens (SSE events surfaced before the URL carries an id) keep the
     * current conversation.
     *
     * @return the new turn number and whether the conversation switched.
     */
    fun onToken(sessionId: String): Pair<Int, Boolean> {
        val switched = sessionId.isNotEmpty() && sessionId != this.sessionId
        if (switched) reset(sessionId)
        turnCount += 1
        return turnCount to switched
    }

    /**
     * Record the resolved model for [turn] and build the HUD status text:
     * a headline plus the per-turn history line ("本会话: R1 … · R2 …").
     */
    fun record(turn: Int, model: String): String {
        if (firstModel.isEmpty()) firstModel = model
        routed = model != firstModel
        val changedFromPrev = lastModel.isNotEmpty() && model != lastModel
        lastModel = model
        history.addLast("R$turn $model")
        while (history.size > MAX_HISTORY) history.removeFirst()
        val head = when {
            routed && changedFromPrev -> "第 $turn 轮 · 已切换模型 → $model"
            routed -> "第 $turn 轮 · $model（非首轮模型）"
            else -> "第 $turn 轮 · $model"
        }
        return head + "\n" + historyLine()
    }

    /** "本会话: R1 m1 · R2 m2" — newest last, capped at [MAX_HISTORY]. */
    fun historyLine(): String = "本会话: " + history.joinToString(" · ")

    companion object {
        /** Keep only the most recent turns on the HUD's second line. */
        const val MAX_HISTORY = 6
    }
}
