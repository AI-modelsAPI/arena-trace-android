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
        // Hardened variants: zero-width chars and fullwidth/unicode operators.
        assertTrue(ProbeLogic.isArithmeticTitle("1+1=\u200B"))
        assertTrue(ProbeLogic.isArithmeticTitle("\u200B12 - 4 ="))
        assertTrue(ProbeLogic.isArithmeticTitle("12＋12="))
        assertTrue(ProbeLogic.isArithmeticTitle("12－4 ="))
        assertTrue(ProbeLogic.isArithmeticTitle("6＊7＝"))
        assertTrue(ProbeLogic.isArithmeticTitle("8−2=")) // U+2212 minus
    }

    @Test fun userTitlesAreNotArithmetic() {
        assertFalse(ProbeLogic.isArithmeticTitle("claude-opus-5"))
        assertFalse(ProbeLogic.isArithmeticTitle("My chat about math 1+1"))
        assertFalse(ProbeLogic.isArithmeticTitle(""))
        assertFalse(ProbeLogic.isArithmeticTitle("gpt6-001"))
        // Answered arithmetic ("1+1=2") could be a human-titled chat — left alone.
        assertFalse(ProbeLogic.isArithmeticTitle("1+1=2"))
        assertFalse(ProbeLogic.isArithmeticTitle("3*4=12"))
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

    @Test
    fun randomPrompt_isSendableAndCleanable() {
        val seen = mutableSetOf<String>()
        repeat(200) {
            val p = ProbeLogic.randomPrompt()
            // Passes the JS send-guard shape and the cleanup title matcher.
            assertTrue("not own prompt: $p", ProbeLogic.isOwnPrompt(p))
            assertTrue("not arithmetic title: $p", ProbeLogic.isArithmeticTitle(p))
            seen.add(p)
        }
        // Randomized: 200 draws must not collapse onto one prompt.
        assertTrue("prompts not random: ${seen.size} distinct", seen.size > 100)
    }

    @Test fun ownPromptRejectsUserText() {
        assertFalse(ProbeLogic.isOwnPrompt("hello"))
        assertFalse(ProbeLogic.isOwnPrompt("1+1=2")) // answered → not a bare probe send
        // Regex-based now: ANY bare arithmetic send counts, incl. randomPrompt().
        assertTrue(ProbeLogic.isOwnPrompt("1+2="))
        assertTrue(ProbeLogic.isOwnPrompt("473×82="))
    }

    @Test fun compileTargetRejectsEmpty() {
        assertNull(ProbeLogic.compileTarget(""))
        assertNull(ProbeLogic.compileTarget("  "))
    }
    // ---- normalizeTitle / isArithmeticTitle robustness (missed-cleanup fixes) ----

    @Test fun normalizeTitleStripsInvisibleCharacters() {
        assertEquals("1+1=", ProbeLogic.normalizeTitle("\u200B1+1=\u200D"))
        assertEquals("1+1=", ProbeLogic.normalizeTitle("\u200E1+1=\u200F"))
        assertEquals("1+1=", ProbeLogic.normalizeTitle("\uFEFF1\u2060+1\u00AD="))
        assertEquals("1+1=", ProbeLogic.normalizeTitle("\u202A1+1=\u202C"))
    }

    @Test fun normalizeTitleUnifiesSpacesAndFullwidth() {
        assertEquals("12 + 4 =", ProbeLogic.normalizeTitle("12\u00A0+\u202F4\u2009="))
        assertEquals("12+34=", ProbeLogic.normalizeTitle("\uFF11\uFF12\uFF0B\uFF13\uFF14\uFF1D"))
        assertEquals("1+1=", ProbeLogic.normalizeTitle("  1+1=\n"))
    }

    @Test fun normalizeTitleUnifiesOperatorLookalikes() {
        assertEquals("9-3=", ProbeLogic.normalizeTitle("9\u22123="))
        assertEquals("9-3=", ProbeLogic.normalizeTitle("9\u20133="))
        assertEquals("9×3=", ProbeLogic.normalizeTitle("9\u27153="))
        assertEquals("9×3=", ProbeLogic.normalizeTitle("9\u22C53="))
        assertEquals("9×3=", ProbeLogic.normalizeTitle("9\u22173="))
        assertEquals("9÷3=", ProbeLogic.normalizeTitle("9\u22153="))
    }

    @Test fun arithmeticTitlesThatUsedToSlipThroughNowMatch() {
        for (t in listOf(
            "\u200B473×82=", "473\u00A0×\u00A082\u00A0=", "\uFF14\uFF17\uFF13\uFF0B\uFF18\uFF12\uFF1D",
            "57\u2212906=", "57\u2013906=", "8\u27156=", "8\u22C56=", "\u200E12 / 4 =",
        )) assertTrue(t, ProbeLogic.isArithmeticTitle(t))
    }

    @Test fun normalizationDoesNotWidenWhatCountsAsArithmetic() {
        for (t in listOf("1+1=2", "\u200B1+1=2", "12345+1=", "1+1", "x+1=", "Math: 1+1=", "1+1=?")) {
            assertFalse(t, ProbeLogic.isArithmeticTitle(t))
        }
    }

    // ---- planCleanup ----

    private fun item(id: String, title: String) = ProbeLogic.SidebarItem(id, title)

    @Test fun planSeparatesTheOpenChatFromTheRest() {
        val sidebar = listOf(item("a", "1+1="), item("b", "Notes"), item("c", "2×3="), item("d", "5-1="))
        val plan = ProbeLogic.planCleanup(sidebar, currentSessionId = "c")
        assertEquals(listOf("a", "d"), plan.others.map { it.sessionId })
        assertEquals("c", plan.current?.sessionId)
        assertEquals(3, plan.size)
    }

    @Test fun planIgnoresANonArithmeticOpenChat() {
        val plan = ProbeLogic.planCleanup(listOf(item("a", "1+1="), item("b", "Notes")), currentSessionId = "b")
        assertNull(plan.current)
        assertEquals(listOf("a"), plan.others.map { it.sessionId })
    }

    @Test fun planHonoursExclusionsAndEmptiness() {
        val plan = ProbeLogic.planCleanup(listOf(item("a", "1+1="), item("b", "2+2=")), null, exclude = setOf("a"))
        assertEquals(listOf("b"), plan.others.map { it.sessionId })
        assertTrue(ProbeLogic.planCleanup(listOf(item("x", "Hello")), "x").isEmpty)
    }

    @Test fun candidatesKeepScanPosition() {
        val c = ProbeLogic.arithmeticCleanupCandidates(listOf(ProbeLogic.SidebarItem("a", "1+1=", position = 480)))
        assertEquals(480, c.single().position)
    }

    // ---- hit titles: prefix + suffix ----

    @Test fun sanitizePrefixCleansInput() {
        assertEquals("[探针] ", ProbeLogic.sanitizePrefix("  [探针]   "))
        assertEquals("ab", ProbeLogic.sanitizePrefix("a\u0000\u200Bb"))
        assertEquals("a b", ProbeLogic.sanitizePrefix("a\n\tb"))
        assertEquals(ProbeLogic.MAX_PREFIX_LENGTH, ProbeLogic.sanitizePrefix("x".repeat(99)).length)
        assertEquals("", ProbeLogic.sanitizePrefix(null))
    }

    @Test fun hitTitleUsesPrefixVerbatim() {
        assertEquals("claude-opus-5-001", ProbeLogic.hitTitle("", "claude-opus-5", "001"))
        assertEquals("[探针] claude-opus-5-001", ProbeLogic.hitTitle("[探针] ", "claude-opus-5", "001"))
        assertEquals("P_gpt-6-012", ProbeLogic.hitTitle("P_", "gpt-6", "012"))
    }

    @Test fun hitTitleAlwaysFitsAndKeepsSuffix() {
        val t = ProbeLogic.hitTitle("[prefix] ", "m".repeat(150), "001")
        assertEquals(ProbeLogic.MAX_TITLE_LENGTH, t.length)
        assertTrue(t.startsWith("[prefix] m"))
        assertTrue(t.endsWith("-001"))
        val tiny = ProbeLogic.hitTitle("abcdef", "model", "001", maxLength = 8)
        assertTrue(tiny.length <= 8)
        assertTrue(tiny.endsWith("-001"))
    }

    @Test fun suffixCountsPerPrefixAndModel() {
        var counters: Map<String, Int> = emptyMap()
        val (a1, c1) = ProbeLogic.nextSuffixFor("[A] ", "gpt-6", counters); counters = c1
        val (a2, c2) = ProbeLogic.nextSuffixFor("[A] ", "gpt-6", counters); counters = c2
        val (b1, c3) = ProbeLogic.nextSuffixFor("[B] ", "gpt-6", counters); counters = c3
        val (n1, _) = ProbeLogic.nextSuffixFor("", "gpt-6", counters)
        assertEquals(listOf("001", "002", "001", "001"), listOf(a1, a2, b1, n1))
    }

    @Test fun noPrefixKeepsLegacyCounterKey() {
        val legacy = mapOf(ProbeLogic.normalize("claude-opus-5") to 4)
        assertEquals("005", ProbeLogic.nextSuffixFor("", "claude-opus-5", legacy).first)
        assertEquals("005", ProbeLogic.nextSuffixFor("   ", "claude-opus-5", legacy).first)
    }

    @Test fun countersRoundTripAndTolerateCorruption() {
        val map = linkedMapOf("gpt6" to 3, "p:[a]|gpt6" to 1)
        assertEquals(map, ProbeLogic.countersFromJson(ProbeLogic.countersToJson(map)))
        assertTrue(ProbeLogic.countersFromJson("not json").isEmpty())
        assertTrue(ProbeLogic.countersFromJson(null).isEmpty())
        assertEquals(mapOf("ok" to 2), ProbeLogic.countersFromJson("""{"ok":2,"neg":-1,"zero":0}"""))
    }

    @Test fun countersAreCapped() {
        var counters: Map<String, Int> = emptyMap()
        for (i in 0..ProbeLogic.MAX_COUNTERS + 5) counters = ProbeLogic.nextSuffixFor("", "model$i", counters).second
        assertEquals(ProbeLogic.MAX_COUNTERS, counters.size)
        assertTrue(counters.containsKey(ProbeLogic.normalize("model${ProbeLogic.MAX_COUNTERS + 5}")))
    }

    @Test fun parseRoundsClampsAndDefaults() {
        assertEquals(ProbeLogic.DEFAULT_ROUNDS, ProbeLogic.parseRounds(""))
        assertEquals(ProbeLogic.DEFAULT_ROUNDS, ProbeLogic.parseRounds("abc"))
        assertEquals(ProbeLogic.MIN_ROUNDS, ProbeLogic.parseRounds("0"))
        assertEquals(ProbeLogic.MAX_ROUNDS, ProbeLogic.parseRounds("5000"))
        assertEquals(12, ProbeLogic.parseRounds(" 12 "))
    }
}
