package com.ati.arena.session

import com.ati.arena.store.HistoryLogic
import org.json.JSONObject

/**
 * Decides which conversation a captured stream token belongs to, and whether it
 * belongs to the conversation that is ON SCREEN. Pure Kotlin (unit-tested).
 *
 * The page can still deliver records from a conversation the user already left
 * (a delayed stream, a prefetch, the old stream closing late). Accepting those
 * used to reset the turn log to the OLD chat and paint its model over the new
 * one. Rule (same as the extension's `pageSession` guard): a token is accepted
 * only when the page, at capture time, showed that very conversation — or showed
 * the new-chat composer, whose first stream is the conversation being created.
 */
object SessionRouting {

    /** A parsed snoop.js event: stream session, run token and the page path at capture time. */
    data class SnoopEvent(
        val sessionId: String,
        val token: String,
        val pagePath: String?,
        /** true for lightweight stream-activity pings (no token attached). */
        val activity: Boolean = false,
    )

    private const val MIN_TOKEN_LENGTH = 20

    /** Parse the bridge payload; null when malformed or obviously not a token event. */
    fun parse(json: String?): SnoopEvent? {
        if (json.isNullOrEmpty() || json.length > 64 * 1024) return null
        val obj = runCatching { JSONObject(json) }.getOrNull() ?: return null
        val sessionId = obj.optString("sessionId")
        if (!HistoryLogic.isValidSessionId(sessionId)) return null
        val page = if (obj.has("page") && !obj.isNull("page")) obj.optString("page") else null
        if (obj.optString("type") == "activity") return SnoopEvent(sessionId, "", page, activity = true)
        val token = obj.optString("token")
        if (token.length < MIN_TOKEN_LENGTH) return null
        return SnoopEvent(sessionId, token, page)
    }

    fun isNewChatPath(path: String?): Boolean = path != null && path.trimEnd('/') == "/agent"

    /**
     * @param streamSession  conversation id taken from the stream URL
     * @param pagePath       location.pathname when the token was captured
     * @param newChatSession conversation already adopted for the new-chat page, if any
     * @param isKnown        whether a conversation was seen before (tracked or stored)
     *
     * On a CONVERSATION page every stream is accepted, even one with an id that
     * neither matches the page nor was seen before. Arena pairs conversation
     * pages (/agent/{id}, /c/{evalId}) with stream session ids that do NOT have
     * to be equal, and a stream may also rotate its id between turns; attribution
     * always follows the stream URL's own session id, so each run still lands in
     * its own conversation's log. "Not on screen" confusion is prevented by that
     * attribution, not by rejecting tokens here (rejecting is what used to drop
     * every turn after the first).
     *
     * On the NEW-CHAT page only ONE conversation is adopted: the first stream not
     * seen before (the chat being created), or — after adoption — exactly that
     * conversation. A late token replayed from the chat the user just left is
     * "known" and therefore rejected there.
     */
    fun accepts(
        streamSession: String,
        pagePath: String?,
        newChatSession: String? = null,
        isKnown: (String) -> Boolean = { false },
    ): Boolean {
        if (!HistoryLogic.isValidSessionId(streamSession)) return false
        val pageSession = HistoryLogic.sessionFromPath(pagePath)
        return when {
            pageSession != null -> true
            !isNewChatPath(pagePath) -> false
            newChatSession != null -> newChatSession == streamSession
            else -> !isKnown(streamSession)
        }
    }
}
