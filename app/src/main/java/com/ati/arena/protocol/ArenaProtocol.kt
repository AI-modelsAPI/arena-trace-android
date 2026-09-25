package com.ati.arena.protocol

import org.json.JSONArray
import org.json.JSONObject
import java.util.Base64

/**
 * Pure, framework-independent port of the extension's core.js protocol logic:
 * public run-token validation and trace model extraction. No Android, no network.
 */
object ArenaProtocol {

    data class Claims(val runId: String, val exp: Long)

    /** Upper bound on a token's length; anything longer is not a Trigger.dev public token. */
    const val MAX_TOKEN_LENGTH = 16_384

    /** Seconds of remaining validity below which a token is treated as expired. */
    const val EXPIRY_SKEW_SECONDS = 5L

    private const val ISSUER = "https://id.trigger.dev"
    private const val AUDIENCE = "https://api.trigger.dev"
    private const val MAX_MODEL_LABEL = 200

    private val RUN_ID = Regex("^run_[A-Za-z0-9]+$")
    private val RUN_SCOPE = Regex("^read:runs:(run_[A-Za-z0-9]+)$")
    private val MODEL_SPANS = setOf(
        "ai.streamText.doStream",
        "ai.generateText.doGenerate",
        "ai.streamObject.doStream",
        "ai.generateObject.doGenerate",
    )
    private val CUBE_ICONS = setOf("tabler-cube", "cube", "tabler-box")

    /** Strength/effort tiers we surface (" · high"). Whitelisting prevents random chip text from showing. */
    private val EFFORT_WORDS = setOf("minimal", "low", "medium", "high", "xhigh", "max", "ultra")

    /** JSON keys that can carry a reasoning effort, e.g. {"reasoning_effort":"high"}. */
    private val EFFORT_PATTERN = Regex(
        """"(?:reasoning[-_]?effort|thinking[-_]?effort|reasoningEffort|thinkingEffort|effort)"\s*:\s*"([A-Za-z]+)"""",
    )

    /** Events larger than this are never scanned for effort keys. */
    private const val MAX_EVENT_SCAN_CHARS = 128 * 1024

    fun isValidRunId(runId: String?): Boolean = runId != null && RUN_ID.matches(runId)

    /** Returns validated claims, or null when the token is not a usable public run token. */
    fun validateToken(token: String, sessionId: String, nowSeconds: Long): Claims? =
        inspectToken(token, sessionId)?.takeUnless { isExpired(it, nowSeconds) }

    /** True when [claims] are expired (or about to expire) at [nowSeconds]. */
    fun isExpired(claims: Claims, nowSeconds: Long): Boolean =
        claims.exp <= nowSeconds + EXPIRY_SKEW_SECONDS

    /**
     * Structural validation WITHOUT the expiry check: same issuer / audience / run /
     * session rules as [validateToken]. Used only to IDENTIFY which run a token
     * belongs to — e.g. an expired token replayed when an old conversation's
     * stream is reopened still names the run (= turn) it came from. Never use an
     * expired token for a network request; call [validateToken] for that.
     */
    fun inspectToken(token: String, sessionId: String): Claims? {
        val parts = token.split('.')
        if (parts.size != 3 || token.length > MAX_TOKEN_LENGTH) return null
        val claims = runCatching {
            JSONObject(String(Base64.getUrlDecoder().decode(pad(parts[1])), Charsets.UTF_8))
        }.getOrNull() ?: return null

        if (claims.opt("pub") != true) return null
        if (claims.optString("iss") != ISSUER) return null

        // aud is optional; when present (scalar or array) it must include the API audience.
        val aud = audienceList(claims.opt("aud"))
        if (aud.isNotEmpty() && !aud.contains(AUDIENCE)) return null

        val exp = claims.optDouble("exp", Double.NaN)
        if (exp.isNaN() || exp.isInfinite() || exp <= 0) return null

        val runId = runIdFromClaims(claims) ?: return null

        // If the token names any sessions, one of them must be the current stream session.
        val scopes = claims.optJSONArray("scopes")
        if (scopes != null) {
            val sessionScopes = ArrayList<String>()
            for (i in 0 until scopes.length()) {
                val scope = scopes.opt(i) as? String ?: continue
                if (scope.startsWith("read:sessions:")) sessionScopes.add(scope)
            }
            if (sessionScopes.isNotEmpty() && !sessionScopes.contains("read:sessions:$sessionId")) return null
        }
        return Claims(runId, exp.toLong())
    }

    /** Resolve exactly one run id: modern scalar `run` first, else a single read:runs scope. */
    private fun runIdFromClaims(claims: JSONObject): String? {
        val run = claims.opt("run")
        if (run is String && RUN_ID.matches(run)) return run
        val scopes = claims.optJSONArray("scopes") ?: return null
        val runs = LinkedHashSet<String>()
        for (i in 0 until scopes.length()) {
            val scope = scopes.opt(i) as? String ?: continue
            RUN_SCOPE.find(scope)?.let { runs.add(it.groupValues[1]) }
        }
        return runs.singleOrNull()
    }

    private fun pad(value: String): String {
        val remainder = value.length % 4
        return if (remainder == 0) value else value + "=".repeat(4 - remainder)
    }

    private fun audienceList(aud: Any?): List<String> = when (aud) {
        null, JSONObject.NULL -> emptyList()
        is String -> listOf(aud)
        is JSONArray -> (0 until aud.length()).mapNotNull { aud.opt(it) as? String }
        else -> emptyList()
    }

    private fun traceEvents(trace: JSONObject): JSONArray? {
        trace.optJSONArray("events")?.let { return it }
        trace.optJSONObject("data")?.optJSONArray("events")?.let { return it }
        trace.optJSONArray("spans")?.let { return it }
        val data = trace.optJSONArray("data")
        if (data != null && (0 until data.length()).all { data.opt(it) is JSONObject }) return data
        return null
    }

    private fun spanName(event: JSONObject): String =
        event.optString("message", event.optString("name", event.optString("spanName", "")))

    private fun cubeModels(event: JSONObject): List<String> {
        val items = event.optJSONObject("style")
            ?.optJSONObject("accessory")
            ?.optJSONArray("items") ?: return emptyList()
        val models = ArrayList<String>()
        for (j in 0 until items.length()) {
            val item = items.optJSONObject(j) ?: continue
            if (item.optString("icon") in CUBE_ICONS) {
                val text = item.optString("text").trim()
                if (text.isNotEmpty() && text.length <= MAX_MODEL_LABEL) models.add(text)
            }
        }
        return models
    }

    /** Model names read from the trace's model spans for the given run. */
    fun extractModels(trace: JSONObject, runId: String): List<String> {
        val events = traceEvents(trace) ?: return emptyList()
        val found = LinkedHashSet<String>()
        for (i in 0 until events.length()) {
            val event = events.optJSONObject(i) ?: continue
            if (event.optString("runId") != runId || spanName(event) !in MODEL_SPANS) continue
            found.addAll(cubeModels(event))
        }
        if (found.isEmpty()) {
            for (i in 0 until events.length()) {
                val event = events.optJSONObject(i) ?: continue
                if (event.optString("runId") != runId) continue
                found.addAll(cubeModels(event))
            }
        }
        return found.toList()
    }

    /**
     * Optional strength/effort tier ("high", "max", …) for the run: accessory chips
     * first (model spans, then any span of the run), then effort keys in the event
     * JSON. [models] excludes model chips that happen to be tier words (e.g. a
     * model literally named "Max"). Null when the trace carries no tier — most
     * traces today have none, and then nothing is displayed.
     */
    fun extractEffort(trace: JSONObject, runId: String, models: List<String> = emptyList()): String? {
        val excluded = models.mapTo(HashSet()) { it.trim().lowercase() }
        val events = traceEvents(trace) ?: return null
        for (pass in 0..1) {
            for (i in 0 until events.length()) {
                val event = events.optJSONObject(i) ?: continue
                if (event.optString("runId") != runId) continue
                if (pass == 0 && spanName(event) !in MODEL_SPANS) continue
                effortFromChips(event, excluded)?.let { return it }
            }
        }
        for (i in 0 until events.length()) {
            val event = events.optJSONObject(i) ?: continue
            if (event.optString("runId") != runId) continue
            effortFromJson(event, excluded)?.let { return it }
        }
        return null
    }

    private fun effortFromChips(event: JSONObject, excluded: Set<String>): String? {
        val items = event.optJSONObject("style")
            ?.optJSONObject("accessory")
            ?.optJSONArray("items") ?: return null
        for (j in 0 until items.length()) {
            val text = items.optJSONObject(j)?.optString("text") ?: continue
            val word = text.trim().lowercase()
            if (word in EFFORT_WORDS && word !in excluded) return word
        }
        return null
    }

    private fun effortFromJson(event: JSONObject, excluded: Set<String>): String? {
        val raw = event.toString()
        if (raw.length > MAX_EVENT_SCAN_CHARS) return null
        val word = EFFORT_PATTERN.find(raw)?.groupValues?.getOrNull(1)?.lowercase() ?: return null
        return if (word in EFFORT_WORDS && word !in excluded) word else null
    }

    fun isFatalTraceStatus(status: Int): Boolean =
        status == 401 || status == 403 || status == 429
}
