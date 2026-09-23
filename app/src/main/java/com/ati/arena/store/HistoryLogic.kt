package com.ati.arena.store

import org.json.JSONArray
import org.json.JSONObject

/**
 * Pure, Android-free persistence logic for the local conversation→model history
 * and the probe panel preferences. Kept framework-independent so it can be unit
 * tested on the JVM; [Store] is the thin SharedPreferences wrapper around it.
 *
 * STRICT WHITELIST — the only things ever persisted are:
 *   - a conversation's resolved model name(s), keyed by sessionId
 *   - probe panel form values (targets text, max rounds, two checkboxes)
 * NEVER tokens, trace payloads, cookies, run ids, or any conversation text.
 */
object HistoryLogic {

    /** Arena conversation path is /agent/{sessionId}; the id charset is bounded. */
    private val SESSION_ID = Regex("^[a-zA-Z0-9-]{1,128}$")
    private val CONVERSATION_PATH = Regex("^/agent/([a-zA-Z0-9-]{1,128})/?$")

    fun isValidSessionId(id: String?): Boolean = id != null && SESSION_ID.matches(id)

    /** Extract the sessionId from a conversation URL path, or null if it isn't one. */
    fun sessionFromPath(path: String?): String? =
        CONVERSATION_PATH.find(path ?: "")?.groupValues?.getOrNull(1)

    /**
     * Sanitize model names to the whitelist: trimmed, non-blank, deduped, length
     * capped, and at most [maxModels] of them. Rejects control characters.
     */
    fun sanitizeModels(models: List<String>, maxModels: Int = 8): List<String> =
        models.map { it.trim() }
            .filter { it.isNotEmpty() && it.length <= 200 && !it.any { c -> c.code < 0x20 || c.code == 0x7f } }
            .distinct()
            .take(maxModels)

    /**
     * Merge one conversation record into the stored history JSON (an object of
     * sessionId -> {"models":[...],"updatedAt":<ms>}), bumping updatedAt and
     * capping total entries by evicting the oldest. Returns the new JSON string.
     * A no-op (empty models, bad id, unchanged models) returns the input unchanged.
     */
    fun mergeRecord(
        existingJson: String?,
        sessionId: String,
        models: List<String>,
        maxEntries: Int = 200,
        nowMs: Long = System.currentTimeMillis(),
    ): String {
        if (!isValidSessionId(sessionId)) return existingJson ?: "{}"
        val clean = sanitizeModels(models)
        if (clean.isEmpty()) return existingJson ?: "{}"

        val root = runCatching { JSONObject(existingJson ?: "{}") }.getOrElse { JSONObject() }

        // Skip a write when the same models are already stored (avoid churn).
        val prev = root.optJSONObject(sessionId)?.optJSONArray("models")
        if (prev != null && jsonArrayToList(prev) == clean) return root.toString()

        root.put(sessionId, JSONObject().put("models", JSONArray(clean)).put("updatedAt", nowMs))
        evictOldest(root, maxEntries)
        return root.toString()
    }

    /** Resolved model name(s) for a session, or empty if unknown. */
    fun modelsFor(json: String?, sessionId: String): List<String> {
        if (!isValidSessionId(sessionId)) return emptyList()
        val root = runCatching { JSONObject(json ?: "{}") }.getOrNull() ?: return emptyList()
        val arr = root.optJSONObject(sessionId)?.optJSONArray("models") ?: return emptyList()
        return jsonArrayToList(arr)
    }

    private fun evictOldest(root: JSONObject, maxEntries: Int) {
        if (maxEntries <= 0) return
        while (root.length() > maxEntries) {
            var oldestKey: String? = null
            var oldestTs = Long.MAX_VALUE
            val keys = root.keys()
            while (keys.hasNext()) {
                val k = keys.next()
                val ts = root.optJSONObject(k)?.optLong("updatedAt", 0L) ?: 0L
                if (ts < oldestTs) { oldestTs = ts; oldestKey = k }
            }
            if (oldestKey == null) break
            root.remove(oldestKey)
        }
    }

    private fun jsonArrayToList(arr: JSONArray): List<String> =
        (0 until arr.length()).mapNotNull { arr.opt(it) as? String }
}
