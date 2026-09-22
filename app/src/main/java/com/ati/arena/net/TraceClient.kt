package com.ati.arena.net

import android.util.Base64
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Port of the extension's token → trace pipeline:
 * validate the public run token, then poll Trigger.dev run events for the
 * ai.streamText.doStream span and read its tabler-cube model label.
 */
class TraceClient {
    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .followRedirects(false)
        .build()

    data class Result(
        val ok: Boolean,
        val models: List<String> = emptyList(),
        val runId: String = "",
        val error: String = ""
    )

    private data class Claims(val runId: String, val exp: Long)

    /** Mirrors core.js validateToken: shape, issuer, audience, expiry, single run scope. */
    private fun validate(token: String, sessionId: String): Claims? {
        val parts = token.split('.')
        if (parts.size != 3 || token.length > 16384) return null
        val claims = runCatching {
            JSONObject(String(Base64.decode(parts[1], Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)))
        }.getOrNull() ?: return null
        if (!claims.optBoolean("pub")) return null
        if (claims.optString("iss") != "https://id.trigger.dev") return null
        val aud = claims.optJSONArray("aud")
        if (aud != null && (0 until aud.length()).none { aud.getString(it) == "https://api.trigger.dev" }) return null
        val exp = claims.optLong("exp", 0)
        if (exp <= System.currentTimeMillis() / 1000 + 5) return null
        val scopes = claims.optJSONArray("scopes") ?: return null
        var runId: String? = null
        var sessionScoped = false
        var hasSessionScopes = false
        for (i in 0 until scopes.length()) {
            val s = scopes.getString(i)
            when {
                s.startsWith("read:runs:") -> {
                    val id = s.removePrefix("read:runs:")
                    if (runId != null && runId != id) return null // must name exactly one run
                    runId = id
                }
                s.startsWith("read:sessions:") -> {
                    hasSessionScopes = true
                    if (s == "read:sessions:$sessionId") sessionScoped = true
                }
            }
        }
        if (runId == null) return null
        if (hasSessionScopes && !sessionScoped) return null
        return Claims(runId, exp)
    }

    /** Mirrors core.js extractModels for the cube label on the stream span. */
    private fun extractModels(trace: JSONObject, runId: String): List<String> {
        val events = trace.optJSONArray("events") ?: return emptyList()
        val found = LinkedHashSet<String>()
        for (i in 0 until events.length()) {
            val event = events.optJSONObject(i) ?: continue
            if (event.optString("runId") != runId) continue
            val name = event.optString("message", event.optString("name", event.optString("spanName")))
            if (name != "ai.streamText.doStream") continue
            val items = event.optJSONObject("style")
                ?.optJSONObject("accessory")
                ?.optJSONArray("items") ?: continue
            for (j in 0 until items.length()) {
                val item = items.optJSONObject(j) ?: continue
                if (item.optString("icon") == "tabler-cube") {
                    val text = item.optString("text").trim()
                    if (text.isNotEmpty() && text.length <= 200) found.add(text)
                }
            }
        }
        return found.toList()
    }

    /** Polls up to 8 times, 3s apart, mirroring the extension's lookup loop. */
    suspend fun fetchModels(token: String, sessionId: String): Result {
        val claims = validate(token, sessionId) ?: return Result(false, error = "令牌格式不符合预期或不属于当前会话")
        repeat(8) { attempt ->
            val req = Request.Builder()
                .url("https://api.trigger.dev/api/v1/runs/" + claims.runId + "/events")
                .header("Authorization", "Bearer $token")
                .header("Accept", "application/json")
                .build()
            try {
                client.newCall(req).execute().use { res ->
                    if (res.isSuccessful) {
                        val body = res.body!!.string()
                        if (body.length > 4 * 1024 * 1024) return Result(false, error = "trace 超过 4 MB，停止解析")
                        val models = extractModels(JSONObject(body), claims.runId)
                        if (models.isNotEmpty()) return Result(true, models, claims.runId)
                    } else if (res.code == 401 || res.code == 403 || res.code == 429) {
                        return Result(false, error = "trace 返回 HTTP ${res.code}")
                    }
                }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
            }
            if (System.currentTimeMillis() / 1000 >= claims.exp - 5)
                return Result(false, error = "令牌已过期，请发送新的消息")
            delay(3000)
        }
        return Result(false, error = "trace 未返回模型名称")
    }
}
