package com.ati.arena.store

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HistoryLogicTest {

    // ---- session id / path ----

    @Test fun validSessionIds() {
        assertTrue(HistoryLogic.isValidSessionId("abc123"))
        assertTrue(HistoryLogic.isValidSessionId("a-b-c-1-2-3"))
    }

    @Test fun invalidSessionIds() {
        assertFalse(HistoryLogic.isValidSessionId(null))
        assertFalse(HistoryLogic.isValidSessionId(""))
        assertFalse(HistoryLogic.isValidSessionId("has space"))
        assertFalse(HistoryLogic.isValidSessionId("has/slash"))
        assertFalse(HistoryLogic.isValidSessionId("a".repeat(129)))
    }

    @Test fun sessionFromPathMatchesAgentConversation() {
        assertEquals("s1", HistoryLogic.sessionFromPath("/agent/s1"))
        assertEquals("s1", HistoryLogic.sessionFromPath("/agent/s1/"))
        assertEquals("a-b-c", HistoryLogic.sessionFromPath("/agent/a-b-c"))
    }

    @Test fun sessionFromPathRejectsNonConversationPaths() {
        assertEquals(null, HistoryLogic.sessionFromPath("/agent"))
        assertEquals(null, HistoryLogic.sessionFromPath("/agent/"))
        assertEquals(null, HistoryLogic.sessionFromPath("/"))
        assertEquals(null, HistoryLogic.sessionFromPath("/agent/s1/extra"))
        assertEquals(null, HistoryLogic.sessionFromPath(null))
    }

    // ---- sanitizeModels (whitelist) ----

    @Test fun sanitizeTrimsDedupsAndCaps() {
        val out = HistoryLogic.sanitizeModels(listOf(" gpt-4o ", "gpt-4o", "", "  ", "claude-opus-5"))
        assertEquals(listOf("gpt-4o", "claude-opus-5"), out)
    }

    @Test fun sanitizeRejectsControlCharsAndOverlong() {
        val out = HistoryLogic.sanitizeModels(listOf("bad\u0000name", "x".repeat(201), "ok-model"))
        assertEquals(listOf("ok-model"), out)
    }

    @Test fun sanitizeCapsCount() {
        val many = (1..20).map { "model$it" }
        assertEquals(8, HistoryLogic.sanitizeModels(many).size)
    }

    // ---- mergeRecord ----

    @Test fun mergeStoresModelsForSession() {
        val json = HistoryLogic.mergeRecord("{}", "s1", listOf("gpt-4o"), nowMs = 1000)
        assertEquals(listOf("gpt-4o"), HistoryLogic.modelsFor(json, "s1"))
    }

    @Test fun mergeIgnoresInvalidSessionOrEmptyModels() {
        assertEquals("{}", HistoryLogic.mergeRecord("{}", "bad id", listOf("gpt-4o")))
        assertEquals("{}", HistoryLogic.mergeRecord("{}", "s1", emptyList()))
        assertEquals("{}", HistoryLogic.mergeRecord("{}", "s1", listOf("  ")))
    }

    @Test fun mergeIsNoOpWhenModelsUnchanged() {
        val first = HistoryLogic.mergeRecord("{}", "s1", listOf("gpt-4o"), nowMs = 1000)
        val second = HistoryLogic.mergeRecord(first, "s1", listOf("gpt-4o"), nowMs = 2000)
        // Unchanged models → same JSON (updatedAt not bumped, no churn).
        assertEquals(first, second)
    }

    @Test fun mergeUpdatesWhenModelsChange() {
        val first = HistoryLogic.mergeRecord("{}", "s1", listOf("gpt-4o"), nowMs = 1000)
        val second = HistoryLogic.mergeRecord(first, "s1", listOf("claude-opus-5"), nowMs = 2000)
        assertEquals(listOf("claude-opus-5"), HistoryLogic.modelsFor(second, "s1"))
    }

    @Test fun mergeEvictsOldestBeyondCap() {
        var json = "{}"
        // Insert 3 entries with increasing timestamps, cap at 2 → oldest (s1) evicted.
        json = HistoryLogic.mergeRecord(json, "s1", listOf("m1"), maxEntries = 2, nowMs = 100)
        json = HistoryLogic.mergeRecord(json, "s2", listOf("m2"), maxEntries = 2, nowMs = 200)
        json = HistoryLogic.mergeRecord(json, "s3", listOf("m3"), maxEntries = 2, nowMs = 300)
        val root = JSONObject(json)
        assertEquals(2, root.length())
        assertFalse(root.has("s1"))
        assertTrue(root.has("s2"))
        assertTrue(root.has("s3"))
    }

    @Test fun mergeToleratesCorruptExistingJson() {
        val json = HistoryLogic.mergeRecord("not json", "s1", listOf("m1"), nowMs = 1)
        assertEquals(listOf("m1"), HistoryLogic.modelsFor(json, "s1"))
    }

    // ---- modelsFor ----

    @Test fun modelsForUnknownSessionIsEmpty() {
        assertTrue(HistoryLogic.modelsFor("{}", "s1").isEmpty())
        assertTrue(HistoryLogic.modelsFor(null, "s1").isEmpty())
        assertTrue(HistoryLogic.modelsFor("{}", "bad id").isEmpty())
    }
}
