package com.ati.arena.store

import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest

/**
 * Pure, Android-free persistence logic for the local conversation → model history.
 * Framework-independent so it can be unit tested on the JVM; [Store] is the thin
 * SharedPreferences wrapper around it.
 *
 * Stored shape (one object per conversation, oldest evicted beyond [MAX_SESSIONS]):
 * ```
 * { "<sessionId>": { "models": ["latest", "models"],
 *                    "runs": [ {"k": "<runKey>", "n": 1, "m": ["model"]}, … ],
 *                    "updatedAt": <ms> } }
 * ```
 *
 * STRICT WHITELIST — the only things ever persisted are a conversation's resolved
 * model name(s), per-turn numbers, and an opaque one-way [runKey] per turn (so a
 * replayed turn can be recognised). NEVER tokens, raw run ids, trace payloads,
 * cookies, or any conversation text.
 */
object HistoryLogic {

    const val MAX_SESSIONS = 150
    const val MAX_RUNS_PER_SESSION = 50
    const val MAX_MODELS = 8
    const val MAX_MODEL_LENGTH = 200
    const val MAX_STRENGTH_LENGTH = 16
    private const val MAX_TURN_NUMBER = 1_000_000

    /** Arena conversation path is /agent/{sessionId} or /c/{sessionId}; the id charset is bounded. */
    private val SESSION_ID = Regex("^[a-zA-Z0-9-]{1,128}$")
    private val CONVERSATION_PATH = Regex("^/(?:agent|c)/([a-zA-Z0-9-]{1,128})/?$")
    private val CONVERSATION_ANY_PATH = Regex("^/(?:agent|c)/[a-zA-Z0-9-]{1,128}/?$")
    private val RUN_KEY = Regex("^[0-9a-f]{16}$")

    private const val F_MODELS = "models"
    private const val F_RUNS = "runs"
    private const val F_UPDATED = "updatedAt"
    private const val R_KEY = "k"
    private const val R_NUMBER = "n"
    private const val R_MODELS = "m"
    private const val R_STRENGTH = "s"

    /** One persisted turn. */
    data class RunRecord(val key: String, val number: Int, val models: List<String>, val strength: String = "")

    fun isValidSessionId(id: String?): Boolean = id != null && SESSION_ID.matches(id)

    fun isValidRunKey(key: String?): Boolean = key != null && RUN_KEY.matches(key)

    /** Extract the sessionId from a conversation URL path, or null if it isn't one. */
    fun sessionFromPath(path: String?): String? =
        CONVERSATION_PATH.find(path ?: "")?.groupValues?.getOrNull(1)

    /** true when [path] is a conversation page (/agent/{id} or /c/{id}), regardless of the id. */
    fun isConversationPath(path: String?): Boolean = path != null && CONVERSATION_ANY_PATH.matches(path)

    /**
     * Opaque, non-reversible key for a run id (first 16 hex chars of SHA-256).
     * Lets a replayed turn be matched to its stored model without ever persisting
     * the raw run id.
     */
    fun runKey(runId: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(runId.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }.take(16)
    }

    /**
     * Opaque key for a TOKEN, not a run. Arena does not guarantee a distinct
     * run id per turn — a conversation can reuse one run (and even the same
     * token) for every turn, while the token string itself still changes per
     * run. Keying turns by the token's payload segment replays the same token
     * to the same turn (dedupe) yet opens a new turn whenever arena issues a
     * fresh token. The raw token is never persisted: only its hash is used.
     */
    fun tokenKey(token: String): String {
        val payload = token.split('.').getOrNull(1) ?: token
        return runKey(payload)
    }

    /**
     * Sanitize model names to the whitelist: trimmed, non-blank, deduped, length
     * capped, and at most [maxModels] of them. Rejects control characters.
     */
    fun sanitizeModels(models: List<String>, maxModels: Int = MAX_MODELS): List<String> =
        models.map { it.trim() }
            .filter { it.isNotEmpty() && it.length <= MAX_MODEL_LENGTH && it.none { c -> c.code < 0x20 || c.code == 0x7f } }
            .distinct()
            .take(maxModels)

    /** Sanitize a strength/effort tier label: trimmed, control-free, length capped. */
    fun sanitizeStrength(strength: String): String =
        strength.trim().filter { it.code >= 0x20 && it.code != 0x7f }.take(MAX_STRENGTH_LENGTH)

    /** Parse stored JSON leniently: corrupt or missing data yields an empty root. */
    fun parseRoot(json: String?): JSONObject =
        runCatching { JSONObject(json ?: "{}") }.getOrElse { JSONObject() }

    // ---- in-place operations on a parsed root (used by Store) ----

    /**
     * Set a conversation's current model(s). Returns true when the root changed.
     * A no-op (bad id, empty models, unchanged models) returns false.
     */
    fun putModels(
        root: JSONObject,
        sessionId: String,
        models: List<String>,
        maxEntries: Int = MAX_SESSIONS,
        nowMs: Long = System.currentTimeMillis(),
    ): Boolean {
        if (!isValidSessionId(sessionId)) return false
        val clean = sanitizeModels(models)
        if (clean.isEmpty()) return false
        val entry = root.optJSONObject(sessionId) ?: JSONObject()
        if (stringList(entry.optJSONArray(F_MODELS)) == clean) return false
        entry.put(F_MODELS, JSONArray(clean)).put(F_UPDATED, nowMs)
        root.put(sessionId, entry)
        evictOldest(root, maxEntries)
        return true
    }

    /**
     * Upsert one observed turn. [models] may be empty (turn seen, model unknown yet).
     * An existing turn keeps its number; its models are replaced only by a non-empty
     * list. The conversation's "models" always follow its highest-numbered resolved
     * turn, so a late-resolving older turn never overwrites the current model.
     * Returns true when the root changed.
     */
    fun putRun(
        root: JSONObject,
        sessionId: String,
        key: String,
        number: Int,
        models: List<String>,
        strength: String = "",
        maxEntries: Int = MAX_SESSIONS,
        maxRuns: Int = MAX_RUNS_PER_SESSION,
        nowMs: Long = System.currentTimeMillis(),
    ): Boolean {
        if (!isValidSessionId(sessionId) || !isValidRunKey(key) || number !in 1..MAX_TURN_NUMBER) return false
        val clean = sanitizeModels(models)
        val tier = sanitizeStrength(strength)
        val entry = root.optJSONObject(sessionId) ?: JSONObject()
        val runs = readRuns(entry).toMutableList()
        val index = runs.indexOfFirst { it.key == key }
        if (index >= 0) {
            val existing = runs[index]
            val merged = existing.copy(
                models = if (clean.isEmpty()) existing.models else clean,
                strength = if (tier.isEmpty()) existing.strength else tier,
            )
            if (merged == existing) return false
            runs[index] = merged
        } else {
            runs.add(RunRecord(key, number, clean, tier))
        }
        val kept = runs.sortedBy { it.number }.takeLast(maxRuns.coerceAtLeast(1))
        entry.put(F_RUNS, JSONArray().apply { kept.forEach { put(runJson(it)) } })
        kept.filter { it.models.isNotEmpty() }.maxByOrNull { it.number }?.let {
            entry.put(F_MODELS, JSONArray(it.models))
        }
        entry.put(F_UPDATED, nowMs)
        root.put(sessionId, entry)
        evictOldest(root, maxEntries)
        return true
    }

    /** Resolved model name(s) for a session, or empty if unknown. */
    fun modelsIn(root: JSONObject, sessionId: String): List<String> {
        if (!isValidSessionId(sessionId)) return emptyList()
        return stringList(root.optJSONObject(sessionId)?.optJSONArray(F_MODELS))
    }

    /** Persisted turns of a session, ordered by turn number. */
    fun runsIn(root: JSONObject, sessionId: String): List<RunRecord> {
        if (!isValidSessionId(sessionId)) return emptyList()
        val entry = root.optJSONObject(sessionId) ?: return emptyList()
        return readRuns(entry).sortedBy { it.number }
    }

    // ---- string wrappers (stable API used by tests and simple callers) ----

    /**
     * Merge one conversation's current models into the stored history JSON.
     * Returns the new JSON string; a no-op returns the input unchanged.
     */
    fun mergeRecord(
        existingJson: String?,
        sessionId: String,
        models: List<String>,
        maxEntries: Int = MAX_SESSIONS,
        nowMs: Long = System.currentTimeMillis(),
    ): String {
        val root = parseRoot(existingJson)
        return if (putModels(root, sessionId, models, maxEntries, nowMs)) root.toString() else existingJson ?: "{}"
    }

    /** String form of [putRun]; a no-op returns the input unchanged. */
    fun mergeRun(
        existingJson: String?,
        sessionId: String,
        key: String,
        number: Int,
        models: List<String>,
        strength: String = "",
        maxEntries: Int = MAX_SESSIONS,
        maxRuns: Int = MAX_RUNS_PER_SESSION,
        nowMs: Long = System.currentTimeMillis(),
    ): String {
        val root = parseRoot(existingJson)
        return if (putRun(root, sessionId, key, number, models, strength, maxEntries, maxRuns, nowMs)) root.toString()
        else existingJson ?: "{}"
    }

    fun modelsFor(json: String?, sessionId: String): List<String> = modelsIn(parseRoot(json), sessionId)

    fun runsFor(json: String?, sessionId: String): List<RunRecord> = runsIn(parseRoot(json), sessionId)

    // ---- internals ----

    private fun readRuns(entry: JSONObject): List<RunRecord> {
        val arr = entry.optJSONArray(F_RUNS) ?: return emptyList()
        val out = ArrayList<RunRecord>(arr.length())
        val seen = HashSet<String>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val key = o.optString(R_KEY)
            val number = o.optInt(R_NUMBER, 0)
            if (!isValidRunKey(key) || number !in 1..MAX_TURN_NUMBER || !seen.add(key)) continue
            out.add(
                RunRecord(
                    key,
                    number,
                    sanitizeModels(stringList(o.optJSONArray(R_MODELS))),
                    sanitizeStrength(o.optString(R_STRENGTH)),
                ),
            )
        }
        return out
    }

    private fun runJson(r: RunRecord): JSONObject =
        JSONObject().put(R_KEY, r.key).put(R_NUMBER, r.number).put(R_MODELS, JSONArray(r.models))
            .also { if (r.strength.isNotEmpty()) it.put(R_STRENGTH, r.strength) }

    private fun evictOldest(root: JSONObject, maxEntries: Int) {
        if (maxEntries <= 0) return
        while (root.length() > maxEntries) {
            var oldestKey: String? = null
            var oldestTs = Long.MAX_VALUE
            val keys = root.keys()
            while (keys.hasNext()) {
                val k = keys.next()
                val ts = root.optJSONObject(k)?.optLong(F_UPDATED, 0L) ?: 0L
                if (ts < oldestTs) { oldestTs = ts; oldestKey = k }
            }
            if (oldestKey == null) break
            root.remove(oldestKey)
        }
    }

    private fun stringList(arr: JSONArray?): List<String> =
        if (arr == null) emptyList() else (0 until arr.length()).mapNotNull { arr.opt(it) as? String }
}
