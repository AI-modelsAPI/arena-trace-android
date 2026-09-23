package com.ati.arena.probe

/**
 * Pure, Android-free probe logic ported from the extension's auto-draw.js.
 * Target parsing/matching, arithmetic-title detection, cleanup candidate
 * selection, and the per-model suffix counter live here so they can be unit
 * tested on the JVM. No DOM, no coroutines, no network.
 */
object ProbeLogic {

    val DEFAULT_TARGETS = listOf("opus5", "fable5", "gpt6")

    // 50 short, cheap, unambiguous arithmetic probe prompts ("1+1=" … "50+50=").
    val PROMPTS: List<String> = (1..50).map { "$it+$it=" }

    private val FAMILIES = mapOf(
        "opus5" to """(?:claude[-_\s.]*)?opus[-_\s.]*5(?:[-_.]\d+)?(?!\d)""",
        "fable5" to """(?:claude[-_\s.]*)?fable[-_\s.]*5(?:[-_.]\d+)?(?!\d)""",
        "gpt6" to """(?:chat)?gpt[-_\s.]*6(?:[-_\s.]*astra|[-_\s.]*pro)?(?!\d)""",
    )

    private val ALIASES = mapOf(
        "opus5" to "opus5", "claudeopus5" to "opus5",
        "fable5" to "fable5", "claudefable5" to "fable5",
        "fable51" to "fable5", "claudefable51" to "fable5",
        "gpt6" to "gpt6", "chatgpt6" to "gpt6",
        "gpt6astra" to "gpt6", "gpt6pro" to "gpt6", "astra" to "gpt6",
    )

    /** lower-case + strip everything non-alphanumeric (matches JS normalize). */
    fun normalize(s: String?): String =
        (s ?: "").lowercase().replace(Regex("[^a-z0-9]+"), "")

    /** Split a user target string into 2..80-char tokens, deduped, max 20. */
    fun parseTargets(text: String?): List<String> {
        val parts = (text ?: "")
            .split(Regex("[,，;；\n]+"))
            .map { it.trim().replace(Regex("[.\\s]+$"), "") }
            .filter { it.length in 2..80 }
        return parts.distinct().take(20)
    }

    /**
     * Compile one target into a case-insensitive Regex:
     *  - /body/flags  → literal user regex (i is forced on; body ≤120)
     *  - known alias  → the model family pattern
     *  - otherwise    → the literal escaped, with separators made fuzzy ([-_\s.]*)
     * Returns null when the target can't produce a usable pattern.
     */
    fun compileTarget(target: String?): Regex? {
        val raw = (target ?: "").trim()
        if (raw.isEmpty()) return null

        if (raw.length >= 3 && raw.startsWith("/") && raw.lastIndexOf('/') > 0) {
            val last = raw.lastIndexOf('/')
            val body = raw.substring(1, last)
            var flags = raw.substring(last + 1).replace(Regex("[^gimsuy]"), "")
            if (body.isEmpty() || body.length > 120) return null
            val opts = mutableSetOf(RegexOption.IGNORE_CASE)
            if (flags.contains('s')) opts.add(RegexOption.DOT_MATCHES_ALL)
            if (flags.contains('m')) opts.add(RegexOption.MULTILINE)
            return runCatching { Regex(body, opts) }.getOrNull()
        }

        ALIASES[normalize(raw)]?.let { fam ->
            return runCatching { Regex(FAMILIES.getValue(fam), RegexOption.IGNORE_CASE) }.getOrNull()
        }

        val escaped = raw.replace(Regex("""[.*+?^${'$'}{}()|\[\]\\]"""), "\\\\$0")
            .replace(Regex("[-_\\s.]+"), "[-_\\\\s.]*")
        return runCatching { Regex(escaped, RegexOption.IGNORE_CASE) }.getOrNull()
    }

    data class Hit(val target: String, val model: String)

    /** For each target, find the first model it matches (raw or normalized). */
    fun matchTargets(models: List<String>, targets: List<String>): List<Hit> {
        val hits = mutableListOf<Hit>()
        for (target in targets) {
            val regex = compileTarget(target) ?: continue
            val model = models.firstOrNull { name ->
                regex.containsMatchIn(name) || regex.containsMatchIn(normalize(name))
            }
            if (model != null) hits.add(Hit(target, model))
        }
        return hits
    }

    /** Targets not yet hit (by normalized name). */
    fun remainingTargets(targets: List<String>, hits: List<Hit>): List<String> {
        val found = hits.map { normalize(it.target) }.toSet()
        return targets.filter { normalize(it) !in found }
    }

    /**
     * findAll stop condition: every target has been hit at least once across all
     * rounds. Hit targets are NEVER removed from the matching pool — each round
     * matches against the FULL target list and a target may be hit repeatedly —
     * so this accumulates over the whole run rather than draining a pool.
     */
    fun allTargetsHit(targets: List<String>, hits: List<Hit>): Boolean =
        targets.isNotEmpty() && remainingTargets(targets, hits).isEmpty()

    /**
     * A pure-arithmetic conversation title ("1+1=", "12 - 4 ="). Only our own
     * probe sends produce these — a human names chats with words — so a title
     * sweep can archive probe residue without ever touching user-named chats.
     *
     * Hardened against title variants seen in the wild: zero-width characters
     * Arena injects into sidebar titles, and fullwidth/unicode operators
     * (＋－＊／＝, − U+2212). A title with an ANSWER after "=" ("1+1=2") still
     * does NOT match — that could be a human-titled chat and is left alone.
     */
    fun isArithmeticTitle(t: String?): Boolean {
        val s = (t ?: "")
            .replace(Regex("[\\u200B\\u200C\\u200D\\uFEFF]"), "")
            .replace('＋', '+').replace('－', '-').replace('−', '-')
            .replace('＊', '*').replace('／', '/')
            .replace('＝', '=')
        return Regex("""^\s*\d{1,4}\s*[+\-*/×÷]\s*\d{1,4}\s*=\s*$""").matches(s)
    }

    fun isOwnPrompt(t: String?): Boolean = PROMPTS.contains(t)

    data class SidebarItem(val sessionId: String, val title: String)

    /**
     * Sidebar entries whose title is bare arithmetic (unanswered probe sends),
     * deduped by session, optionally keeping the currently open chat.
     */
    fun arithmeticCleanupCandidates(
        sidebar: List<SidebarItem>,
        keepSessionId: String? = null,
    ): List<SidebarItem> {
        val seen = mutableSetOf<String>()
        val out = mutableListOf<SidebarItem>()
        for (c in sidebar) {
            val id = c.sessionId
            if (id.isEmpty() || id in seen) continue
            if (keepSessionId != null && id == keepSessionId) continue
            if (!isArithmeticTitle(c.title)) continue
            seen.add(id)
            out.add(SidebarItem(id, c.title.take(300)))
        }
        return out
    }

    /**
     * Next 3-digit suffix for a model, given the current per-model counter map.
     * Returns the padded suffix plus the updated counter to persist.
     * (Extension stores this in localStorage; we keep the store in Kotlin.)
     */
    fun nextSuffix(model: String, counters: Map<String, Int>): Pair<String, Map<String, Int>> {
        val key = normalize(model).ifEmpty { "model" }
        val current = counters[key]?.takeIf { it >= 0 } ?: 0
        val n = current + 1
        val updated = counters.toMutableMap().apply { put(key, n) }
        return n.toString().padStart(3, '0') to updated
    }
}
