package com.ati.arena.net

import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.time.Instant
import java.util.concurrent.TimeUnit

/** Reads https://arena.ai/api/me/pulse with the WebView's cookies. */
object PulseClient {
    private val client = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS)
        .build()

    private const val MAX_BODY_CHARS = 256 * 1024

    data class Pulse(val percent: Int, val refreshedAt: Long)

    sealed interface Result {
        data class Ok(val pulse: Pulse) : Result
        /** retryAfterMs: cooldown hint for 429; 0 when unknown. */
        data class Err(val message: String, val retryAfterMs: Long = 0) : Result
    }

    /** Blocking; call from a background dispatcher. */
    fun fetch(cookies: String): Result {
        if (cookies.isBlank()) return Result.Err("未登录 Arena")
        val req = Request.Builder()
            .url("https://arena.ai/api/me/pulse")
            .header("Cookie", cookies)
            .header("Accept", "application/json")
            .build()
        return try {
            client.newCall(req).execute().use { res ->
                when {
                    res.code == 429 -> Result.Err(
                        "额度接口限流（429）",
                        PulseTiming.retryAfterMs(res.header("Retry-After"), System.currentTimeMillis()),
                    )
                    !res.isSuccessful -> Result.Err("额度接口返回 HTTP ${res.code}")
                    else -> {
                        val text = res.body?.string().orEmpty()
                        if (text.length > MAX_BODY_CHARS) return Result.Err("额度返回过大")
                        val obj = runCatching { JSONObject(text) }.getOrNull()
                            ?: return Result.Err("额度返回格式未识别（可能未登录）")
                        val percent = obj.optInt("pulse", -1)
                        if (percent !in 0..100) return Result.Err("额度返回格式未识别")
                        // Raw timestamp the quota window was last refreshed; the caller
                        // converts this into an anchored reset instant (see PulseTiming).
                        val refreshedAt = runCatching {
                            Instant.parse(obj.getString("refreshedAt")).toEpochMilli()
                        }.getOrDefault(0L)
                        Result.Ok(Pulse(percent, refreshedAt))
                    }
                }
            }
        } catch (e: java.io.IOException) {
            Result.Err("额度读取失败：网络错误（${e.javaClass.simpleName}）")
        } catch (e: RuntimeException) {
            Result.Err("额度读取失败：" + (e.message ?: e.javaClass.simpleName))
        }
    }
}
