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

    @Test fun sessionFromPathMatchesCConversation() {
        assertEquals("s1", HistoryLogic.sessionFromPath("/c/s1"))
        assertEquals("s1", HistoryLogic.sessionFromPath("/c/s1/"))
        assertEquals("01a0b8cd-1234", HistoryLogic.sessionFromPath("/c/01a0b8cd-1234"))
        assertEquals(null, HistoryLogic.sessionFromPath("/c"))
        assertEquals(null, HistoryLogic.sessionFromPath("/c/"))
    }

    @Test fun conversationPathDetection() {
        assertEquals(true, HistoryLogic.isConversationPath("/agent/s1"))
        assertEquals(true, HistoryLogic.isConversationPath("/c/s1"))
        assertEquals(false, HistoryLogic.isConversationPath("/agent"))
        assertEquals(false, HistoryLogic.isConversationPath("/c/x y"))
        assertEquals(false, HistoryLogic.isConversationPath(null))
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
    // ---- per-turn records (hashed run keys) ----

    @Test fun runKeyIsOpaqueStableHex() {
        val k = HistoryLogic.runKey("run_abc123")
        assertEquals(16, k.length)
        assertTrue(HistoryLogic.isValidRunKey(k))
        assertEquals(k, HistoryLogic.runKey("run_abc123"))
        assertFalse(k.contains("abc123"))
        assertFalse(k == HistoryLogic.runKey("run_abc124"))
    }

    private val k1 = HistoryLogic.runKey("run_1")
    private val k2 = HistoryLogic.runKey("run_2")
    private val k3 = HistoryLogic.runKey("run_3")

    @Test fun mergeRunStoresObservedThenResolvedTurn() {
        var json = HistoryLogic.mergeRun("{}", "s1", k1, 1, emptyList(), nowMs = 1)
        assertEquals(listOf(HistoryLogic.RunRecord(k1, 1, emptyList())), HistoryLogic.runsFor(json, "s1"))
        assertTrue(HistoryLogic.modelsFor(json, "s1").isEmpty())
        json = HistoryLogic.mergeRun(json, "s1", k1, 1, listOf("m1"), nowMs = 2)
        assertEquals(listOf("m1"), HistoryLogic.runsFor(json, "s1").single().models)
        assertEquals(listOf("m1"), HistoryLogic.modelsFor(json, "s1"))
    }

    @Test fun existingTurnKeepsItsNumber() {
        var json = HistoryLogic.mergeRun("{}", "s1", k1, 1, emptyList(), nowMs = 1)
        json = HistoryLogic.mergeRun(json, "s1", k1, 7, listOf("m1"), nowMs = 2)
        assertEquals(1, HistoryLogic.runsFor(json, "s1").single().number)
    }

    @Test fun sessionModelsFollowTheHighestResolvedTurn() {
        var json = HistoryLogic.mergeRun("{}", "s1", k2, 2, listOf("newer"), nowMs = 1)
        // An older turn resolving later must not overwrite the current model.
        json = HistoryLogic.mergeRun(json, "s1", k1, 1, listOf("older"), nowMs = 2)
        assertEquals(listOf("newer"), HistoryLogic.modelsFor(json, "s1"))
        assertEquals(listOf(1, 2), HistoryLogic.runsFor(json, "s1").map { it.number })
    }

    @Test fun mergeRunIsNoOpWithoutChange() {
        val json = HistoryLogic.mergeRun("{}", "s1", k1, 1, listOf("m1"), nowMs = 1)
        assertEquals(json, HistoryLogic.mergeRun(json, "s1", k1, 1, listOf("m1"), nowMs = 2))
        assertEquals(json, HistoryLogic.mergeRun(json, "s1", k1, 1, emptyList(), nowMs = 3))
    }

    @Test fun mergeRunRejectsInvalidInput() {
        assertEquals("{}", HistoryLogic.mergeRun("{}", "bad id", k1, 1, listOf("m")))
        assertEquals("{}", HistoryLogic.mergeRun("{}", "s1", "run_raw_id", 1, listOf("m")))
        assertEquals("{}", HistoryLogic.mergeRun("{}", "s1", k1, 0, listOf("m")))
    }

    @Test fun runsPerSessionAreCappedKeepingTheNewest() {
        var json = "{}"
        for (n in 1..5) json = HistoryLogic.mergeRun(json, "s1", HistoryLogic.runKey("run_$n"), n, listOf("m$n"), maxRuns = 3, nowMs = n.toLong())
        assertEquals(listOf(3, 4, 5), HistoryLogic.runsFor(json, "s1").map { it.number })
        assertEquals(listOf("m5"), HistoryLogic.modelsFor(json, "s1"))
    }

    @Test fun legacyRecordsWithoutRunsStillWork() {
        val legacy = HistoryLogic.mergeRecord("{}", "s1", listOf("m1"), nowMs = 1)
        assertTrue(HistoryLogic.runsFor(legacy, "s1").isEmpty())
        val json = HistoryLogic.mergeRun(legacy, "s1", k3, 1, listOf("m2"), nowMs = 2)
        assertEquals(listOf("m2"), HistoryLogic.modelsFor(json, "s1"))
    }

    @Test fun corruptRunEntriesAreSkipped() {
        val json = """{"s1":{"models":["m"],"runs":[{"k":"zz","n":1},{"k":"$k1","n":-3},{"k":"$k2","n":2,"m":["ok"]}],"updatedAt":1}}"""
        assertEquals(listOf(HistoryLogic.RunRecord(k2, 2, listOf("ok"))), HistoryLogic.runsFor(json, "s1"))
    }

    @Test fun sanitizeRejectsControlCharacters() {
        assertEquals(listOf("ok"), HistoryLogic.sanitizeModels(listOf("bad\u0000name", "ok")))
    }

    @Test
    fun strengthIsPersistedAndSurvivesModelRefreshes() {
        var json = HistoryLogic.mergeRun("{}", "s1", k1, 1, listOf("m1"), strength = "high", nowMs = 1)
        assertEquals("high", HistoryLogic.runsFor(json, "s1").single().strength)
        json = HistoryLogic.mergeRun(json, "s1", k1, 1, listOf("m1b"), nowMs = 2)
        val run = HistoryLogic.runsFor(json, "s1").single()
        assertEquals(listOf("m1b"), run.models)
        assertEquals("high", run.strength)
        json = HistoryLogic.mergeRun(json, "s1", k1, 1, listOf("m1b"), strength = "  max  ", nowMs = 3)
        assertEquals("max", HistoryLogic.runsFor(json, "s1").single().strength)
        // History never pretends strengths of OTHER runs; junk is stripped/capped.
        assertTrue(HistoryLogic.sanitizeStrength("a".repeat(64)).length <= HistoryLogic.MAX_STRENGTH_LENGTH)
        assertEquals("", HistoryLogic.sanitizeStrength(" \t "))
        assertEquals("", HistoryLogic.runsFor("{}", "s1").getOrNull(0)?.strength ?: "")
    }
}
