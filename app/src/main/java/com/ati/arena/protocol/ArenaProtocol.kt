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

    private val RUN_ID = Regex("^run_[A-Za-z0-9]+$")
    private val RUN_SCOPE = Regex("^read:runs:(run_[A-Za-z0-9]+)$")
    private val MODEL_SPANS = setOf(
        "ai.streamText.doStream",
        "ai.generateText.doGenerate",
        "ai.streamObject.doStream",
        "ai.generateObject.doGenerate",
    )
    private val CUBE_ICONS = setOf("tabler-cube", "cube", "tabler-box")

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

    /** Returns validated claims, or null when the token is not a usable public run token. */
    fun validateToken(token: String, sessionId: String, nowSeconds: Long): Claims? {
        val parts = token.split('.')
        if (parts.size != 3 || token.length > 16384) return null
        val claims = runCatching {
            JSONObject(String(Base64.getUrlDecoder().decode(pad(parts[1])), Charsets.UTF_8))
        }.getOrNull() ?: return null

        if (claims.opt("pub") != true) return null
        if (claims.optString("iss") != "https://id.trigger.dev") return null

        // aud is optional; when present (scalar or array) it must include the API audience.
        val aud = audienceList(claims.opt("aud"))
        if (aud.isNotEmpty() && !aud.contains("https://api.trigger.dev")) return null

        val exp = claims.optDouble("exp", Double.NaN)
        if (exp.isNaN() || exp <= nowSeconds + 5) return null

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
                if (text.isNotEmpty() && text.length <= 200) models.add(text)
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

    fun isFatalTraceStatus(status: Int): Boolean =
        status == 401 || status == 403 || status == 429
}
