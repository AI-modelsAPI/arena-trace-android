package com.ati.arena.probe

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProbeLogicTest {

    // ---- parseTargets ----

    @Test fun parseTargetsSplitsAndTrims() {
        assertEquals(
            listOf("opus5", "fable5", "gpt6"),
            ProbeLogic.parseTargets("opus5, fable5; gpt6")
        )
    }

    @Test fun parseTargetsHandlesChinesePunctuationAndNewlines() {
        assertEquals(
            listOf("opus5", "fable5", "gpt6"),
            ProbeLogic.parseTargets("opus5，fable5；\ngpt6.")
        )
    }

    @Test fun parseTargetsDropsTooShortAndDedups() {
        assertEquals(listOf("opus5"), ProbeLogic.parseTargets("a, opus5, opus5, x"))
    }

    @Test fun parseTargetsCapsAtTwenty() {
        val many = (1..30).joinToString(",") { "model$it" }
        assertEquals(20, ProbeLogic.parseTargets(many).size)
    }

    // ---- matchTargets: model families ----

    @Test fun matchesOpus5Family() {
        val hits = ProbeLogic.matchTargets(listOf("claude-opus-5", "gpt-4o"), listOf("opus5"))
        assertEquals(1, hits.size)
        assertEquals("claude-opus-5", hits[0].model)
    }

    @Test fun matchesGpt6AstraAndPro() {
        assertEquals(1, ProbeLogic.matchTargets(listOf("chatgpt-6-astra"), listOf("gpt6")).size)
        assertEquals(1, ProbeLogic.matchTargets(listOf("gpt 6 pro"), listOf("gpt6")).size)
    }

    @Test fun opus5DoesNotMatchOpus50() {
        // negative lookahead (?!\d) guards against 5 → 50 over-matching.
        assertTrue(ProbeLogic.matchTargets(listOf("opus-50"), listOf("opus5")).isEmpty())
    }

    @Test fun aliasChatgpt6NormalizesToGpt6() {
        assertEquals(1, ProbeLogic.matchTargets(listOf("ChatGPT-6"), listOf("chatgpt6")).size)
    }

    // ---- matchTargets: literal /regex/ syntax ----

    @Test fun literalRegexTargetMatches() {
        val hits = ProbeLogic.matchTargets(listOf("gemini-2.5-pro"), listOf("/gemini.*pro/"))
        assertEquals(1, hits.size)
    }

    @Test fun literalRegexIsCaseInsensitiveEvenWithoutFlag() {
        assertEquals(1, ProbeLogic.matchTargets(listOf("GEMINI-PRO"), listOf("/gemini/")).size)
    }

    @Test fun plainTokenSeparatorsAreFuzzy() {
        // "gpt 4o" target should match "gpt-4o" (separator made fuzzy).
        assertEquals(1, ProbeLogic.matchTargets(listOf("gpt-4o"), listOf("gpt 4o")).size)
    }

    @Test fun noMatchReturnsEmpty() {
        assertTrue(ProbeLogic.matchTargets(listOf("gpt-4o"), listOf("opus5")).isEmpty())
    }

    @Test fun matchTargetsCanHitSeveralTargetsInOneRound() {
        // A round matches against the FULL target list; one response's models can
        // satisfy multiple targets at once.
        val hits = ProbeLogic.matchTargets(
            listOf("claude-opus-5", "chatgpt-6-astra"),
            listOf("opus5", "fable5", "gpt6")
        )
        assertEquals(setOf("opus5", "gpt6"), hits.map { it.target }.toSet())
    }

    // ---- remainingTargets / allTargetsHit ----

    @Test fun remainingExcludesHitTargets() {
        val hits = listOf(ProbeLogic.Hit("opus5", "claude-opus-5"))
        assertEquals(
            listOf("fable5", "gpt6"),
            ProbeLogic.remainingTargets(listOf("opus5", "fable5", "gpt6"), hits)
        )
    }

    @Test fun allTargetsHitAccumulatesAcrossRounds() {
        val targets = listOf("opus5", "fable5", "gpt6")
        // Hitting the same target twice does not "consume" it; other targets stay open.
        val partial = listOf(
            ProbeLogic.Hit("opus5", "claude-opus-5"),
            ProbeLogic.Hit("opus5", "claude-opus-5"),
        )
        assertFalse(ProbeLogic.allTargetsHit(targets, partial))
        val complete = partial + listOf(
            ProbeLogic.Hit("fable5", "claude-fable-5"),
            ProbeLogic.Hit("gpt6", "chatgpt-6"),
        )
        assertTrue(ProbeLogic.allTargetsHit(targets, complete))
    }

    @Test fun allTargetsHitFalseWhenNoTargets() {
        assertFalse(ProbeLogic.allTargetsHit(emptyList(), emptyList()))
    }

    // ---- isArithmeticTitle ----

    @Test fun arithmeticTitlesRecognized() {
        assertTrue(ProbeLogic.isArithmeticTitle("1+1="))
        assertTrue(ProbeLogic.isArithmeticTitle(" 12 - 4 = "))
        assertTrue(ProbeLogic.isArithmeticTitle("5*5="))
        assertTrue(ProbeLogic.isArithmeticTitle("8÷2="))
    }

    @Test fun userTitlesAreNotArithmetic() {
        assertFalse(ProbeLogic.isArithmeticTitle("claude-opus-5"))
        assertFalse(ProbeLogic.isArithmeticTitle("My chat about math 1+1"))
        assertFalse(ProbeLogic.isArithmeticTitle(""))
        assertFalse(ProbeLogic.isArithmeticTitle("gpt6-001"))
    }

    // ---- arithmeticCleanupCandidates ----

    @Test fun cleanupPicksOnlyArithmeticTitles() {
        val sidebar = listOf(
            ProbeLogic.SidebarItem("s1", "1+1="),
            ProbeLogic.SidebarItem("s2", "claude-opus-5-001"),
            ProbeLogic.SidebarItem("s3", "3*4="),
            ProbeLogic.SidebarItem("s4", "My project notes"),
        )
        val out = ProbeLogic.arithmeticCleanupCandidates(sidebar)
        assertEquals(listOf("s1", "s3"), out.map { it.sessionId })
    }

    @Test fun cleanupKeepsCurrentSessionAndDedups() {
        val sidebar = listOf(
            ProbeLogic.SidebarItem("s1", "1+1="),
            ProbeLogic.SidebarItem("s1", "1+1="),
            ProbeLogic.SidebarItem("keep", "2+2="),
        )
        val out = ProbeLogic.arithmeticCleanupCandidates(sidebar, keepSessionId = "keep")
        assertEquals(listOf("s1"), out.map { it.sessionId })
    }

    // ---- nextSuffix ----

    @Test fun suffixStartsAtOneAndPadsToThree() {
        val (suffix, store) = ProbeLogic.nextSuffix("claude-opus-5", emptyMap())
        assertEquals("001", suffix)
        assertEquals(1, store[ProbeLogic.normalize("claude-opus-5")])
    }

    @Test fun suffixIncrementsPerModelIndependently() {
        var store = mapOf(ProbeLogic.normalize("opus5") to 2)
        val (s1, store1) = ProbeLogic.nextSuffix("opus5", store)
        assertEquals("003", s1)
        val (s2, _) = ProbeLogic.nextSuffix("gpt6", store1)
        assertEquals("001", s2) // different model → its own counter
    }

    // ---- prompts / own-prompt guard ----

    @Test fun promptsAreFiftyArithmeticSends() {
        assertEquals(50, ProbeLogic.PROMPTS.size)
        assertEquals("1+1=", ProbeLogic.PROMPTS.first())
        assertEquals("50+50=", ProbeLogic.PROMPTS.last())
        assertTrue(ProbeLogic.PROMPTS.all { ProbeLogic.isOwnPrompt(it) })
    }

    @Test fun ownPromptRejectsUserText() {
        assertFalse(ProbeLogic.isOwnPrompt("hello"))
        assertFalse(ProbeLogic.isOwnPrompt("1+2=")) // not one of the N+N= set
    }

    @Test fun compileTargetRejectsEmpty() {
        assertNull(ProbeLogic.compileTarget(""))
        assertNull(ProbeLogic.compileTarget("  "))
    }
}
