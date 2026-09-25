package com.ati.arena.session

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionRoutingTest {

    private val token = "a".repeat(30)

    @Test
    fun acceptsTokenOfTheConversationOnScreen() {
        assertTrue(SessionRouting.accepts("s1", "/agent/s1"))
        assertTrue(SessionRouting.accepts("s1", "/agent/s1/"))
        assertTrue(SessionRouting.accepts("s1", "/c/s1"))
        assertTrue(SessionRouting.accepts("s1", "/c/s1/"))
    }

    @Test
    fun conversationPageAcceptsAnyStreamSession() {
        // Arena pairs conversation pages with stream session ids that need not
        // match the page id, and the stream id can rotate between turns. Every
        // stream is accepted on a conversation page — attribution by stream id
        // keeps different chats separated. (Rejecting here is what used to drop
        // every turn after the first.)
        assertTrue(SessionRouting.accepts("s1", "/agent/s2"))
        assertTrue(SessionRouting.accepts("s1", "/c/s2"))
        assertTrue(SessionRouting.accepts("s1", "/agent/s2") { false })
        assertTrue(SessionRouting.accepts("s1", "/c/s2") { false })
    }

    @Test
    fun acceptsTheFirstStreamOfANewChat() {
        assertTrue(SessionRouting.accepts("s1", "/agent"))
        assertTrue(SessionRouting.accepts("s1", "/agent/"))
    }

    @Test
    fun newChatPageAdoptsOnlyOneUnknownConversation() {
        // A late token from the chat the user just left is "known" → rejected.
        assertFalse(SessionRouting.accepts("old", "/agent", null) { it == "old" })
        assertTrue(SessionRouting.accepts("fresh", "/agent", null) { it == "old" })
        // Once adopted, only that conversation is accepted on the new-chat page.
        assertTrue(SessionRouting.accepts("fresh", "/agent", "fresh") { true })
        assertFalse(SessionRouting.accepts("other", "/agent", "fresh") { false })
    }

    @Test
    fun rejectsNonConversationPagesAndBadIds() {
        assertFalse(SessionRouting.accepts("s1", "/"))
        assertFalse(SessionRouting.accepts("s1", "/leaderboard"))
        assertFalse(SessionRouting.accepts("s1", null))
        assertFalse(SessionRouting.accepts("", "/agent"))
        assertFalse(SessionRouting.accepts("bad id", "/agent"))
    }

    @Test
    fun parsesSnoopPayload() {
        val e = SessionRouting.parse("""{"sessionId":"s1","token":"$token","page":"/agent/s1"}""")
        assertEquals(SessionRouting.SnoopEvent("s1", token, "/agent/s1"), e)
    }

    @Test
    fun parseToleratesMissingPage() {
        assertNull(SessionRouting.parse("""{"sessionId":"s1","token":"$token"}""")?.pagePath)
        assertNull(SessionRouting.parse("""{"sessionId":"s1","token":"$token","page":null}""")?.pagePath)
    }

    @Test
    fun parseRejectsMalformedPayloads() {
        assertNull(SessionRouting.parse(null))
        assertNull(SessionRouting.parse("not json"))
        assertNull(SessionRouting.parse("""{"sessionId":"s1","token":"short"}"""))
        assertNull(SessionRouting.parse("""{"sessionId":"bad id","token":"$token"}"""))
        assertNull(SessionRouting.parse("""{"token":"$token"}"""))
    }

    @Test
    fun newChatPathDetection() {
        assertTrue(SessionRouting.isNewChatPath("/agent"))
        assertFalse(SessionRouting.isNewChatPath("/agent/s1"))
        assertFalse(SessionRouting.isNewChatPath(null))
    }
}
