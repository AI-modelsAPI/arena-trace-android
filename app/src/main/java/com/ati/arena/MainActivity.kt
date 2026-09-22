package com.ati.arena

import android.content.res.ColorStateList
import android.graphics.Color
import android.os.Bundle
import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.ati.arena.bridge.ArenaBridge
import com.ati.arena.net.PulseClient
import com.ati.arena.net.TraceClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

class MainActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private lateinit var hudModel: TextView
    private lateinit var hudPulse: TextView
    private lateinit var hudPulseBar: ProgressBar
    private lateinit var hudStatus: TextView

    private val traceClient = TraceClient()
    private var traceJob: Job? = null
    private var pulse: PulseClient.Pulse? = null
    private var pulseError = ""
    private var lastPulseFetch = 0L
    private var pulseBlockedUntil = 0L
    private var lastCookieSig = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        webView = findViewById(R.id.web)
        hudModel = findViewById(R.id.hud_model)
        hudPulse = findViewById(R.id.hud_pulse)
        hudPulseBar = findViewById(R.id.hud_pulse_bar)
        hudStatus = findViewById(R.id.hud_status)

        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(webView, true)

        webView.settings.javaScriptEnabled = true
        webView.settings.domStorageEnabled = true
        webView.addJavascriptInterface(ArenaBridge(::onSnoopEvent), "ArenaTrace")
        webView.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView, url: String) {
                if (url.startsWith("https://arena.ai/")) injectSnoop(view)
            }
        }
        webView.loadUrl("https://arena.ai/agent")
        startPulseLoop()
    }

    private fun injectSnoop(view: WebView) {
        val js = assets.open("snoop.js").bufferedReader().use { it.readText() }
        view.evaluateJavascript(js, null)
    }

    private fun onSnoopEvent(json: String) {
        val obj = runCatching { JSONObject(json) }.getOrNull() ?: return
        val sessionId = obj.optString("sessionId")
        val token = obj.optString("token")
        if (token.length < 20) return
        runOnUiThread { hudStatus.text = "已截获运行令牌，正在拉取 trace…" }
        traceJob?.cancel()
        traceJob = lifecycleScope.launch(Dispatchers.IO) {
            val result = traceClient.fetchModels(token, sessionId)
            withContext(Dispatchers.Main) {
                if (result.ok) {
                    hudModel.text = result.models.joinToString(" / ")
                    hudStatus.text = "已识别模型 · run " + result.runId
                } else {
                    hudStatus.text = result.error
                }
            }
        }
    }

    /** Quota: refetch at most every 60s, immediately on account switch; countdown ticks every second. */
    private fun startPulseLoop() {
        lifecycleScope.launch(Dispatchers.IO) {
            while (isActive) {
                val cookies = CookieManager.getInstance().getCookie("https://arena.ai") ?: ""
                val sig = Integer.toHexString(cookies.hashCode())
                val now = System.currentTimeMillis()
                val accountChanged = sig != lastCookieSig
                if ((accountChanged || now - lastPulseFetch > 60_000) && now >= pulseBlockedUntil) {
                    lastCookieSig = sig
                    lastPulseFetch = now
                    when (val r = PulseClient.fetch(cookies)) {
                        is PulseClient.Result.Ok -> { pulse = r.pulse; pulseError = "" }
                        is PulseClient.Result.Err -> {
                            pulseError = r.message
                            if (r.retryAfterMs > 0) pulseBlockedUntil = now + minOf(r.retryAfterMs, 600_000)
                        }
                    }
                }
                withContext(Dispatchers.Main) { renderPulse() }
                delay(1000)
            }
        }
    }

    private fun renderPulse() {
        val p = pulse
        if (p == null) {
            hudPulse.text = if (pulseError.isNotEmpty()) "额度：$pulseError" else "额度读取中…"
            hudPulseBar.progress = 0
            return
        }
        val countdown = if (p.resetAt > 0) {
            val ms = p.resetAt - System.currentTimeMillis()
            if (ms > 0) " · ${formatCountdown(ms)} 后重置" else " · 已到重置时间"
        } else ""
        hudPulse.text = "剩余额度 ${p.percent}%$countdown" + (if (pulseError.isNotEmpty()) " · $pulseError" else "")
        hudPulseBar.progress = p.percent
        val color = when {
            p.percent < 10 -> Color.parseColor("#d0352b") // 红
            p.percent < 20 -> Color.parseColor("#d9a514") // 黄
            else -> Color.parseColor("#17a565")           // 绿
        }
        hudPulseBar.progressTintList = ColorStateList.valueOf(color)
    }

    private fun formatCountdown(ms: Long): String {
        val s = maxOf(0L, (ms + 999) / 1000)
        return "%d:%02d:%02d".format(s / 3600, s % 3600 / 60, s % 60)
    }

    override fun onDestroy() {
        traceJob?.cancel()
        webView.destroy()
        super.onDestroy()
    }
}
