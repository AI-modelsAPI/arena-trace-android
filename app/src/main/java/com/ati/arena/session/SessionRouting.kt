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
    data class SnoopEvent(val sessionId: String, val token: String, val pagePath: String?)

    private const val MIN_TOKEN_LENGTH = 20

    /** Parse the bridge payload; null when malformed or obviously not a token event. */
    fun parse(json: String?): SnoopEvent? {
        if (json.isNullOrEmpty() || json.length > 64 * 1024) return null
        val obj = runCatching { JSONObject(json) }.getOrNull() ?: return null
        val sessionId = obj.optString("sessionId")
        val token = obj.optString("token")
        if (!HistoryLogic.isValidSessionId(sessionId) || token.length < MIN_TOKEN_LENGTH) return null
        val page = if (obj.has("page") && !obj.isNull("page")) obj.optString("page") else null
        return SnoopEvent(sessionId, token, page)
    }

    fun isNewChatPath(path: String?): Boolean = path != null && path.trimEnd('/') == "/agent"

    /**
     * @param streamSession  conversation id taken from the stream URL
     * @param pagePath       location.pathname when the token was captured
     * @param newChatSession conversation already adopted for the new-chat page, if any
     * @param isKnown        whether a conversation was seen before (tracked or stored)
     *
     * On the new-chat page only ONE conversation is accepted: the first one not
     * seen before (the chat being created). A late token from the chat the user
     * just left is "known" and therefore rejected there.
     *
     * On a CONVERSATION page any stream that belongs to a conversation we already
     * know (live or stored) is accepted, even when the page id and stream id
     * differ — Arena has used both /agent/{id} and /c/{evalId} URL forms, and the
     * two ids are not guaranteed identical. Attribution always follows the stream
     * URL's own session id, so a known late stream is attributed to its own chat,
     * which is correct. An UNKNOWN session on a conversation page is rejected:
     * streams only become known through the new-chat adoption or an exact id
     * match, so this can't smuggle a foreign chat in.
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
            pageSession != null -> pageSession == streamSession || isKnown(streamSession)
            !isNewChatPath(pagePath) -> false
            newChatSession != null -> newChatSession == streamSession
            else -> !isKnown(streamSession)
        }
    }
}
