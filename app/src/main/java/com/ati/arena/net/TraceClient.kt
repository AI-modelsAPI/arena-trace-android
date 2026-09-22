package com.ati.arena.net

import com.ati.arena.protocol.ArenaProtocol
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Port of the extension's token → trace pipeline:
 * validate the public run token, then poll Trigger.dev run events for the
 * model spans and read their cube model labels. Protocol parsing lives in
 * [ArenaProtocol]; this class owns the HTTP polling loop only.
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

    /** Polls up to 8 times, 3s apart, mirroring the extension's lookup loop. */
    suspend fun fetchModels(token: String, sessionId: String): Result {
        val claims = ArenaProtocol.validateToken(token, sessionId, System.currentTimeMillis() / 1000)
            ?: return Result(false, error = "令牌格式不符合预期或不属于当前会话")
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
                        val models = ArenaProtocol.extractModels(JSONObject(body), claims.runId)
                        if (models.isNotEmpty()) return Result(true, models, claims.runId)
                    } else if (ArenaProtocol.isFatalTraceStatus(res.code)) {
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
