package com.ati.arena.bridge

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BridgeMessageTest {

    @Test
    fun parsesTheThreeMessageKinds() {
        assertEquals(
            BridgeMessage.Snoop("{\"sessionId\":\"s1\"}"),
            BridgeMessage.parse("{\"t\":\"snoop\",\"p\":\"{\\\"sessionId\\\":\\\"s1\\\"}\"}"),
        )
        assertEquals(
            BridgeMessage.Result("r1_ab", "{\"ok\":true}"),
            BridgeMessage.parse("{\"t\":\"result\",\"id\":\"r1_ab\",\"p\":\"{\\\"ok\\\":true}\"}"),
        )
        assertEquals(BridgeMessage.Log("已发送"), BridgeMessage.parse("{\"t\":\"log\",\"p\":\"  已发送 \"}"))
    }

    @Test
    fun rejectsMalformedMessages() {
        assertNull(BridgeMessage.parse(null))
        assertNull(BridgeMessage.parse(""))
        assertNull(BridgeMessage.parse("not json"))
        assertNull(BridgeMessage.parse("[1,2]"))
        assertNull(BridgeMessage.parse("{\"t\":\"snoop\"}"))
        assertNull(BridgeMessage.parse("{\"t\":\"snoop\",\"p\":42}"))
        assertNull(BridgeMessage.parse("{\"t\":\"unknown\",\"p\":\"x\"}"))
        assertNull(BridgeMessage.parse("{\"t\":1,\"p\":\"x\"}"))
        assertNull(BridgeMessage.parse("{\"t\":\"log\",\"p\":\"   \"}"))
    }

    @Test
    fun resultNeedsASaneRequestId() {
        assertNull(BridgeMessage.parse("{\"t\":\"result\",\"p\":\"{}\"}"))
        assertNull(BridgeMessage.parse("{\"t\":\"result\",\"id\":\"\",\"p\":\"{}\"}"))
        assertNull(BridgeMessage.parse("{\"t\":\"result\",\"id\":7,\"p\":\"{}\"}"))
        val longId = "r".repeat(BridgeMessage.MAX_REQ_ID_CHARS + 1)
        assertNull(BridgeMessage.parse("{\"t\":\"result\",\"id\":\"$longId\",\"p\":\"{}\"}"))
    }

    @Test
    fun capsSizes() {
        val longLine = "x".repeat(BridgeMessage.MAX_LOG_CHARS + 50)
        val log = BridgeMessage.parse("{\"t\":\"log\",\"p\":\"$longLine\"}") as BridgeMessage.Log
        assertEquals(BridgeMessage.MAX_LOG_CHARS, log.line.length)
        val huge = "{\"t\":\"snoop\",\"p\":\"" + "a".repeat(BridgeMessage.MAX_MESSAGE_CHARS) + "\"}"
        assertNull(BridgeMessage.parse(huge))
    }
}
