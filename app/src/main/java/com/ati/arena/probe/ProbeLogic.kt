package com.ati.arena.probe

import org.json.JSONObject
import java.text.Normalizer
import kotlin.random.Random

/**
 * Pure, Android-free probe logic ported from the extension's auto-draw.js.
 * Target parsing/matching, arithmetic-title detection, cleanup planning, hit
 * titles (custom prefix + per-name suffix counter) live here so they can be unit
 * tested on the JVM. No DOM, no coroutines, no network.
 */
object ProbeLogic {

    val DEFAULT_TARGETS = listOf("opus5", "fable5", "gpt6")
    const val DEFAULT_TARGETS_TEXT = "opus5, fable5, gpt6"

    const val MIN_ROUNDS = 1
    const val MAX_ROUNDS = 100
    const val DEFAULT_ROUNDS = 5

    /** Arena's conversation title limit (the rename dialog rejects longer names). */
    const val MAX_TITLE_LENGTH = 100

    /** Longest custom prefix accepted for hit titles. */
    const val MAX_PREFIX_LENGTH = 40

    /** Suffix counters kept (oldest names dropped beyond this). */
    const val MAX_COUNTERS = 300

    // 50 short, cheap, unambiguous arithmetic probe prompts ("1+1=" … "50+50=").
    // Kept as a deterministic reference set; live probing uses randomPrompt().
    val PROMPTS: List<String> = (1..50).map { "$it+$it=" }

    private val PROMPT_OPERATORS = charArrayOf('+', '-', '*', '/', '×', '÷')

    /** Bare arithmetic the probe is allowed to send ("N op N ="). Kept in sync with probe.js. */
    private val OWN_PROMPT = Regex("""^\s*\d{1,4}\s*[+\-*/×÷]\s*\d{1,4}\s*=\s*$""")

    /** A title that is nothing but an unanswered arithmetic expression (after [normalizeTitle]). */
    private val ARITHMETIC_TITLE = Regex("""^\d{1,4}\s*[+\-*/×÷]\s*\d{1,4}\s*=$""")

    /**
     * A fresh random arithmetic prompt ("473×82=", "57+906="). Every probe round
     * sends a DIFFERENT expression so probe-created chats don't all share one
     * title. The shape stays `N op N =`, which both the JS send-guard
     * (isOwnPrompt in probe.js) and the cleanup sweep (isArithmeticTitle)
     * accept — random content, still instantly identifiable as ours.
     */
    fun randomPrompt(random: Random = Random.Default): String {
        val a = random.nextInt(1, 1000)
        val b = random.nextInt(1, 1000)
        val op = PROMPT_OPERATORS[random.nextInt(PROMPT_OPERATORS.size)]
        return "$a$op$b="
    }

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

    /** Clamp a user-typed round count, falling back to the default when unparsable. */
    fun parseRounds(text: String?): Int =
        text?.trim()?.toIntOrNull()?.coerceIn(MIN_ROUNDS, MAX_ROUNDS) ?: DEFAULT_ROUNDS

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
            val flags = raw.substring(last + 1).replace(Regex("[^gimsuy]"), "")
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

    // ---- arithmetic titles & cleanup ----

    private val DASHES = charArrayOf(
        '\u2010', '\u2011', '\u2012', '\u2013', '\u2014', '\u2015', '\u2212', '\u2796', '\uFE58', '\uFE63', '\uFF0D',
    )
    private val TIMES = charArrayOf('\u00D7', '\u2715', '\u2716', '\u2A09', '\u2A2F', '\u2217', '\u22C5', '\u2219', '\u00B7')
    private val DIVIDES = charArrayOf('\u00F7', '\u2215', '\u2044', '\u2797')

    /**
     * Canonical form of a sidebar title for matching: NFKC (fullwidth digits and
     * ＋－＊／＝ → ASCII, NBSP/thin spaces → space), invisible format characters
     * removed (zero-width, bidi marks, soft hyphen, BOM, word joiner), operator
     * look-alikes unified (− – ✕ ∗ ⋅ ∕ …), whitespace collapsed and trimmed.
     */
    fun normalizeTitle(t: String?): String {
        val nfkc = Normalizer.normalize(t ?: "", Normalizer.Form.NFKC)
        val sb = StringBuilder(nfkc.length)
        for (c in nfkc) {
            val type = Character.getType(c)
            when {
                type == Character.FORMAT.toInt() -> Unit
                c.isWhitespace() || Character.isSpaceChar(c) -> sb.append(' ')
                type == Character.CONTROL.toInt() -> Unit
                c in DASHES -> sb.append('-')
                c in TIMES -> sb.append('×')
                c in DIVIDES -> sb.append('÷')
                c == '\u2795' -> sb.append('+')
                else -> sb.append(c)
            }
        }
        return sb.toString().replace(Regex(" {2,}"), " ").trim()
    }

    /**
     * A pure-arithmetic conversation title ("1+1=", "12 - 4 =", "473×82="). Only
     * our own probe sends produce these — a human names chats with words — so a
     * title sweep can archive probe residue without touching user-named chats.
     *
     * Matching runs on [normalizeTitle], so invisible characters, fullwidth and
     * look-alike operators, and exotic spaces no longer make rows slip through.
     * A title with an ANSWER after "=" ("1+1=2") still does NOT match — that could
     * be a human-titled chat and is left alone.
     */
    fun isArithmeticTitle(t: String?): Boolean = ARITHMETIC_TITLE.matches(normalizeTitle(t))

    /** Matches any prompt shape the probe is allowed to send (kept in sync with
     *  the isOwnPrompt guard in probe.js) — including randomPrompt() output. */
    fun isOwnPrompt(t: String?): Boolean = OWN_PROMPT.matches(t ?: "")

    /**
     * One sidebar row. [position] is the sidebar scroll offset at which the scan
     * saw the row (-1 = unknown); it lets the archiver jump straight back to it.
     */
    data class SidebarItem(val sessionId: String, val title: String, val position: Int = -1)

    /**
     * Sidebar entries whose title is bare arithmetic (unanswered probe sends),
     * deduped by session, optionally keeping one conversation untouched.
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
            out.add(c.copy(title = c.title.take(300)))
        }
        return out
    }

    /**
     * What one cleanup pass should archive.
     * [others] — arithmetic chats that are NOT open, in sidebar order;
     * [current] — the open chat when it is itself arithmetic residue. It is never
     * archived while on screen: the sweep first leaves it for a fresh chat.
     */
    data class CleanupPlan(val others: List<SidebarItem>, val current: SidebarItem?) {
        val isEmpty: Boolean get() = others.isEmpty() && current == null
        val size: Int get() = others.size + (if (current != null) 1 else 0)
    }

    fun planCleanup(
        sidebar: List<SidebarItem>,
        currentSessionId: String?,
        exclude: Set<String> = emptySet(),
    ): CleanupPlan {
        val candidates = arithmeticCleanupCandidates(sidebar).filter { it.sessionId !in exclude }
        val current = currentSessionId?.takeIf { it.isNotEmpty() }
            ?.let { id -> candidates.firstOrNull { it.sessionId == id } }
        return CleanupPlan(candidates.filter { it.sessionId != current?.sessionId }, current)
    }

    // ---- hit titles: custom prefix + per-name suffix counter ----

    /**
     * Clean a user-typed title prefix: control/format characters dropped, runs of
     * whitespace collapsed, leading whitespace trimmed (a trailing space is kept
     * so "[探针] " can act as a separator), capped at [MAX_PREFIX_LENGTH].
     */
    fun sanitizePrefix(raw: String?): String {
        val sb = StringBuilder()
        for (c in raw ?: "") {
            val type = Character.getType(c)
            when {
                c.isWhitespace() || Character.isSpaceChar(c) -> sb.append(' ')
                type == Character.CONTROL.toInt() || type == Character.FORMAT.toInt() -> Unit
                else -> sb.append(c)
            }
        }
        return sb.toString().replace(Regex(" {2,}"), " ").trimStart().take(MAX_PREFIX_LENGTH)
    }

    /**
     * Title for a probe hit: "<prefix><model>-<suffix>", guaranteed to fit
     * [maxLength]. The suffix is always kept; the model is shortened first, then
     * the prefix, when the combination is too long.
     */
    fun hitTitle(prefix: String, model: String, suffix: String, maxLength: Int = MAX_TITLE_LENGTH): String {
        val p = sanitizePrefix(prefix)
        val m = model.trim().ifEmpty { "model" }
        val tail = "-$suffix"
        val budget = (maxLength - tail.length).coerceAtLeast(1)
        if (p.length + m.length <= budget) return p + m + tail
        val keptPrefix = p.take((budget - 1).coerceAtLeast(0).coerceAtMost(p.length))
        val keptModel = m.take((budget - keptPrefix.length).coerceAtLeast(1))
        return (keptPrefix + keptModel).trimEnd() + tail
    }

    /**
     * Next 3-digit suffix for a model, given the current per-model counter map.
     * Returns the padded suffix plus the updated counter to persist.
     */
    fun nextSuffix(model: String, counters: Map<String, Int>): Pair<String, Map<String, Int>> =
        nextSuffixFor("", model, counters)

    /**
     * Next suffix for "<prefix><model>": every distinct prefix+model name counts
     * independently (001, 002, …). Without a prefix the key is the legacy
     * per-model key, so existing counters keep counting.
     */
    fun nextSuffixFor(prefix: String, model: String, counters: Map<String, Int>): Pair<String, Map<String, Int>> {
        val key = counterKey(prefix, model)
        val current = counters[key]?.takeIf { it >= 0 } ?: 0
        val n = current + 1
        val updated = LinkedHashMap(counters).apply {
            remove(key)
            put(key, n) // re-insert so the most recently used names are evicted last
            while (size > MAX_COUNTERS) remove(keys.first())
        }
        return n.toString().padStart(3, '0') to updated
    }

    fun counterKey(prefix: String, model: String): String {
        val modelKey = normalize(model).ifEmpty { "model" }
        val p = sanitizePrefix(prefix).trim().lowercase()
        return if (p.isEmpty()) modelKey else "p:$p|$modelKey"
    }

    /** Serialize suffix counters for storage. */
    fun countersToJson(counters: Map<String, Int>): String {
        val o = JSONObject()
        for ((k, v) in counters) if (k.isNotEmpty() && v > 0) o.put(k, v)
        return o.toString()
    }

    /** Parse stored suffix counters leniently (corrupt data → empty map). */
    fun countersFromJson(json: String?): Map<String, Int> {
        val o = runCatching { JSONObject(json ?: "{}") }.getOrNull() ?: return emptyMap()
        val out = LinkedHashMap<String, Int>()
        val keys = o.keys()
        while (keys.hasNext()) {
            val k = keys.next()
            val v = o.optInt(k, 0)
            if (k.isNotEmpty() && k.length <= 200 && v in 1..999_999) out[k] = v
        }
        return out
    }
}
