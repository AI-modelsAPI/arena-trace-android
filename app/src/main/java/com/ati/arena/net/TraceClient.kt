package com.ati.arena.net

import com.ati.arena.protocol.ArenaProtocol
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Port of the extension's token → trace pipeline: poll Trigger.dev run events for
 * the model spans and read their cube model labels. Protocol parsing lives in
 * [ArenaProtocol]; this class owns the HTTP polling loop only.
 *
 * The token is used ONLY as the Authorization header of this request; it is never
 * logged or stored.
 */
class TraceClient(private val client: OkHttpClient = defaultClient()) {

    data class Result(
        val ok: Boolean,
        val models: List<String> = emptyList(),
        val runId: String = "",
        val error: String = "",
    )

    /** Validate [token] for [sessionId], then poll its run's events. */
    suspend fun fetchModels(token: String, sessionId: String): Result {
        val claims = ArenaProtocol.validateToken(token, sessionId, nowSeconds())
            ?: return Result(false, error = "令牌格式不符合预期或已过期")
        return fetchModels(token, claims)
    }

    /** Polls up to [MAX_ATTEMPTS] times, [POLL_INTERVAL_MS] apart, mirroring the extension's lookup loop. */
    suspend fun fetchModels(token: String, claims: ArenaProtocol.Claims): Result {
        if (!ArenaProtocol.isValidRunId(claims.runId)) return Result(false, error = "run id 无效")
        var lastError = ""
        repeat(MAX_ATTEMPTS) {
            if (ArenaProtocol.isExpired(claims, nowSeconds())) {
                return Result(false, runId = claims.runId, error = "令牌已过期，请发送新的消息")
            }
            val request = Request.Builder()
                .url("https://api.trigger.dev/api/v1/runs/${claims.runId}/events")
                .header("Authorization", "Bearer $token")
                .header("Accept", "application/json")
                .build()
            try {
                client.newCall(request).await().use { res ->
                    when {
                        res.isSuccessful -> {
                            val body = res.body ?: return@use
                            if (body.contentLength() > MAX_BODY_BYTES) {
                                return Result(false, runId = claims.runId, error = "trace 超过 4 MB，停止解析")
                            }
                            val text = body.string()
                            if (text.length > MAX_BODY_BYTES) {
                                return Result(false, runId = claims.runId, error = "trace 超过 4 MB，停止解析")
                            }
                            val json = runCatching { JSONObject(text) }.getOrNull()
                            if (json == null) {
                                lastError = "trace 响应不是 JSON"
                            } else {
                                val models = ArenaProtocol.extractModels(json, claims.runId)
                                if (models.isNotEmpty()) return Result(true, models, claims.runId)
                            }
                        }
                        ArenaProtocol.isFatalTraceStatus(res.code) ->
                            return Result(false, runId = claims.runId, error = "trace 返回 HTTP ${res.code}")
                        else -> { lastError = "trace 返回 HTTP ${res.code}" }
                    }
                    Unit
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: IOException) {
                lastError = "网络错误：${e.javaClass.simpleName}"
            }
            delay(POLL_INTERVAL_MS)
        }
        return Result(false, runId = claims.runId, error = lastError.ifEmpty { "trace 未返回模型名称" })
    }

    private fun nowSeconds() = System.currentTimeMillis() / 1000

    companion object {
        private const val MAX_ATTEMPTS = 8
        private const val POLL_INTERVAL_MS = 3_000L
        private const val MAX_BODY_BYTES = 4L * 1024 * 1024

        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .callTimeout(25, TimeUnit.SECONDS)
            .followRedirects(false)
            .build()
    }
}

/** Suspend until the call completes; cancelling the coroutine cancels the HTTP call. */
internal suspend fun Call.await(): Response = suspendCancellableCoroutine { cont ->
    cont.invokeOnCancellation { runCatching { cancel() } }
    enqueue(object : Callback {
        override fun onResponse(call: Call, response: Response) {
            if (cont.isActive) cont.resume(response) else response.close()
        }

        override fun onFailure(call: Call, e: IOException) {
            if (cont.isActive) cont.resumeWithException(e)
        }
    })
}
