package com.ati.arena

import android.annotation.SuppressLint
import android.content.res.ColorStateList
import android.graphics.Color
import android.os.Bundle
import android.view.MotionEvent
import android.view.View
import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.ImageButton
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.ati.arena.bridge.ArenaBridge
import com.ati.arena.bridge.ProbeBridge
import com.ati.arena.net.PulseClient
import com.ati.arena.net.PulseTiming
import com.ati.arena.net.TraceClient
import com.ati.arena.net.WarmUp
import com.ati.arena.probe.ProbeController
import com.ati.arena.probe.ProbeLogic
import com.ati.arena.store.HistoryLogic
import com.ati.arena.store.Store
import com.ati.arena.ui.FloatingBallView
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import kotlin.math.abs
import kotlin.math.hypot

class MainActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private lateinit var panel: View
    private lateinit var ball: FloatingBallView
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
    // Anchored quota-reset instant (ms). Held stable across refetches so the
    // countdown doesn't restart every minute; see PulseTiming.
    private var resetAtAnchor = 0L
    // While true, renderPulse must NOT overwrite the ball center — a transient
    // status (cleanup progress) is being shown there instead.
    private var ballBusy = false
    private var lastCookieSig = ""
    private var currentModel = ""
    // Dedup guard: the SSE tap can surface the same run token repeatedly. Pulling
    // the trace once per token (not once per event) avoids redundant Trigger.dev
    // requests that otherwise pile up and risk 429s.
    private var lastToken = ""

    // sessionId -> resolved model name(s), populated by the snoop→trace pipeline.
    // The auto-probe reads this to associate a probe round with its model.
    private val sessionModels = ConcurrentHashMap<String, String>()
    private lateinit var probe: ProbeController
    private lateinit var store: Store
    private var lastSessionId = ""
    // Guards restoreModelForSession so it fires once per session change (SPA nav
    // can call the URL-changed handler repeatedly for the same URL).
    private var lastRestoredSession = ""

    // Warm-up: poll for cf_clearance / non-challenge instead of a fixed timer.
    private var advancedToAgent = false
    private var warmUpElapsed = 0L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        webView = findViewById(R.id.web)
        panel = findViewById(R.id.panel)
        ball = findViewById(R.id.ball)
        hudModel = findViewById(R.id.hud_model)
        hudPulse = findViewById(R.id.hud_pulse)
        hudPulseBar = findViewById(R.id.hud_pulse_bar)
        hudStatus = findViewById(R.id.hud_status)
        store = Store(this)

        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(webView, true)

        webView.settings.javaScriptEnabled = true
        webView.settings.domStorageEnabled = true
        webView.addJavascriptInterface(ArenaBridge(::onSnoopEvent), "ArenaTrace")
        webView.addJavascriptInterface(
            ProbeBridge(
                resultHandler = { reqId, json -> probe.deliverResult(reqId, json) },
                logHandler = { line -> runOnUiThread { appendProbeLog(line) } },
            ),
            "ArenaProbeBridge",
        )
        probe = ProbeController(
            webView = webView,
            scope = lifecycleScope,
            modelForSession = { sid -> sessionModels[sid] },
            onProgress = { line -> appendProbeLog(line) },
            onFinished = { summary -> appendProbeLog(summary); setProbeRunningUi(false) },
            onCleanupState = { archived, active -> showCleanupOnBall(archived, active) },
        )
        webView.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView, url: String) {
                onArenaPageFinished(view, url)
            }
            // Arena is a SPA: opening a saved chat from the sidebar is a client-side
            // (pushState) navigation that does NOT fire onPageFinished. This callback
            // does fire on those in-app URL changes, so it's where we echo the
            // remembered model when the user switches conversations.
            override fun doUpdateVisitedHistory(view: WebView, url: String, isReload: Boolean) {
                onArenaUrlChanged(url)
            }
        }
        setupControls()
        // Cloudflare warm-up: load the site root first so the managed challenge
        // can run and issue cf_clearance, then advance to /agent once ready.
        webView.loadUrl(ARENA_HOME)
        startPulseLoop()
    }

    private fun setupControls() {
        ball.setOnClickListener { togglePanel() }
        // Long-press the ball → send the panel's quick-send text to the current chat.
        ball.setOnLongClickListener { triggerQuickSend(); true }
        // Ball and panel are mutually exclusive and share ONE logical position.
        // Each is independently draggable; a real drag updates the shared anchor
        // so the other view reappears exactly where this one was left.
        makeDraggable(ball) { captureAnchor(ball) }
        makeDraggable(panel) { captureAnchor(panel) }
        findViewById<ImageButton>(R.id.panel_collapse).setOnClickListener { collapseToBall() }
        findViewById<Button>(R.id.nav_back).setOnClickListener {
            if (webView.canGoBack()) webView.goBack()
        }
        findViewById<Button>(R.id.nav_forward).setOnClickListener {
            if (webView.canGoForward()) webView.goForward()
        }
        findViewById<Button>(R.id.nav_reload).setOnClickListener { webView.reload() }
        setupProbeControls()
    }

    private fun setupProbeControls() {
        val targets = findViewById<EditText>(R.id.probe_targets)
        val rounds = findViewById<EditText>(R.id.probe_rounds)
        val findAll = findViewById<CheckBox>(R.id.probe_find_all)
        val rename = findViewById<CheckBox>(R.id.probe_rename)
        val quick = findViewById<EditText>(R.id.quick_text)

        // Restore the last-used panel values.
        store.loadPanelPrefs().let {
            targets.setText(it.targets)
            rounds.setText(it.maxRounds.toString())
            findAll.isChecked = it.findAll
            rename.isChecked = it.autoRename
            quick.setText(it.quickText)
        }

        fun persistPanel() = store.savePanelPrefs(
            Store.PanelPrefs(
                targets = targets.text.toString(),
                maxRounds = rounds.text.toString().toIntOrNull()?.coerceIn(1, 100) ?: 5,
                findAll = findAll.isChecked,
                autoRename = rename.isChecked,
                quickText = quick.text.toString(),
            )
        )
        findAll.setOnCheckedChangeListener { _, _ -> persistPanel() }
        rename.setOnCheckedChangeListener { _, _ -> persistPanel() }

        findViewById<Button>(R.id.probe_start).setOnClickListener {
            persistPanel()
            val cfg = ProbeController.Config(
                targets = ProbeLogic.parseTargets(targets.text.toString()),
                maxRounds = rounds.text.toString().toIntOrNull()?.coerceIn(1, 100) ?: 5,
                findAll = findAll.isChecked,
                autoRename = rename.isChecked,
            )
            if (cfg.targets.isEmpty()) { appendProbeLog("请填写至少一个目标"); return@setOnClickListener }
            findViewById<TextView>(R.id.probe_log).text = ""
            setProbeRunningUi(true)
            probe.start(cfg)
        }
        findViewById<Button>(R.id.probe_stop).setOnClickListener {
            probe.stop(); setProbeRunningUi(false)
        }
        findViewById<Button>(R.id.probe_cleanup).setOnClickListener {
            findViewById<TextView>(R.id.probe_log).text = ""
            // Collapse to the ball so the sweep's archive dialogs aren't blocked by
            // our own panel, and show the running count in the ball center.
            collapseToBall()
            probe.cleanup(keepSessionId = lastSessionId.ifEmpty { null })
        }
        // Quick send: fills the current conversation's composer and sends. The
        // panel button and the ball long-press share this one path.
        findViewById<Button>(R.id.quick_send).setOnClickListener { persistPanel(); triggerQuickSend() }
    }

    /** Read the quick-send text from the panel and dispatch it to the current chat. */
    private fun triggerQuickSend() {
        val text = findViewById<EditText>(R.id.quick_text).text.toString()
        if (text.isBlank()) { appendProbeLog("请先填写要发送的内容"); return }
        appendProbeLog("正在发送到当前对话…")
        probe.quickSend(text) { result ->
            appendProbeLog(result)
            // The long-press fires with the panel collapsed, so echo the outcome
            // briefly in the ball center too.
            flashBall(if (result.startsWith("已发送")) "已发送" else "发送", if (result.startsWith("已发送")) "✓" else "×")
        }
    }

    /** Briefly show a two-line status in the ball center, then release it. */
    private fun flashBall(top: String, bottom: String) {
        ballBusy = true
        ball.centerIsModel = false
        ball.centerTop = top
        ball.centerBottom = bottom
        ball.postDelayed({ ballBusy = false; renderPulse() }, 2500)
    }

    private fun setProbeRunningUi(running: Boolean) {
        findViewById<Button>(R.id.probe_start).isEnabled = !running
        findViewById<Button>(R.id.probe_stop).isEnabled = running
        findViewById<Button>(R.id.probe_cleanup).isEnabled = !running
    }

    /** Keep the last ~12 progress lines in the panel's log area. */
    private fun appendProbeLog(line: String) {
        val log = findViewById<TextView>(R.id.probe_log)
        val lines = (log.text.toString().split("\n") + line).filter { it.isNotBlank() }.takeLast(12)
        log.text = lines.joinToString("\n")
    }

    // Single logical position for the floating widget, stored as a CENTER point in
    // parent coordinates. Ball and panel have different sizes, so anchoring by a
    // size-independent center keeps the ball from drifting across expand/collapse.
    private var anchorCX = Float.NaN
    private var anchorCY = Float.NaN

    private fun togglePanel() {
        if (panel.visibility == View.VISIBLE) collapseToBall() else expandToPanel()
    }

    /**
     * Expand: the panel takes the ball's spot; the ball hides (never shown together).
     * We do NOT re-capture the anchor here — the anchor is only ever updated by an
     * actual drag. Re-capturing from the (possibly clamped) panel is exactly what
     * made the ball drift to the panel's middle after collapse.
     */
    private fun expandToPanel() {
        placeAtAnchor(panel)
        panel.visibility = View.VISIBLE
        ball.visibility = View.INVISIBLE
    }

    /** Collapse: the ball reappears at the exact shared anchor; the panel hides. */
    private fun collapseToBall() {
        placeAtAnchor(ball)
        ball.visibility = View.VISIBLE
        panel.visibility = View.INVISIBLE
    }

    /** Record the shared center from whichever view the user last saw/dragged. */
    private fun captureAnchor(v: View) {
        if (v.width == 0 || v.height == 0) return
        anchorCX = v.x + v.width / 2f
        anchorCY = v.y + v.height / 2f
    }

    /** Position [target] centered on the shared anchor, clamped inside the parent. */
    private fun placeAtAnchor(target: View) {
        val parent = target.parent as? View ?: return
        if (anchorCX.isNaN()) captureAnchor(if (target === ball) panel else ball)
        if (anchorCX.isNaN()) return
        val maxX = (parent.width - target.width).toFloat().coerceAtLeast(0f)
        val maxY = (parent.height - target.height).toFloat().coerceAtLeast(0f)
        target.x = (anchorCX - target.width / 2f).coerceIn(0f, maxX)
        target.y = (anchorCY - target.height / 2f).coerceIn(0f, maxY)
    }

    /** Physical back button navigates the WebView history before leaving the app. */
    @Suppress("DEPRECATION")
    override fun onBackPressed() {
        if (panel.visibility == View.VISIBLE) { collapseToBall(); return }
        if (webView.canGoBack()) webView.goBack() else super.onBackPressed()
    }

    /**
     * Drag to reposition [v]; a tap (movement under slop) still fires OnClick.
     * [onMoved] runs after each drag step (used to sync the shared anchor).
     */
    @SuppressLint("ClickableViewAccessibility")
    private fun makeDraggable(v: View, onMoved: () -> Unit = {}) {
        var downRawX = 0f
        var downRawY = 0f
        var startX = 0f
        var startY = 0f
        var dragging = false
        val touchSlop = resources.displayMetrics.density * 8
        v.setOnTouchListener { view, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downRawX = e.rawX; downRawY = e.rawY
                    startX = view.x; startY = view.y
                    dragging = false
                    false
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = e.rawX - downRawX
                    val dy = e.rawY - downRawY
                    if (!dragging && hypot(dx, dy) > touchSlop) dragging = true
                    if (dragging) {
                        val parent = view.parent as View
                        view.x = (startX + dx).coerceIn(0f, (parent.width - view.width).toFloat())
                        view.y = (startY + dy).coerceIn(0f, (parent.height - view.height).toFloat())
                        onMoved()
                    }
                    dragging
                }
                MotionEvent.ACTION_UP -> {
                    // A real drag consumes the event (suppresses the click). A tap
                    // returns false so the View generates exactly ONE click itself —
                    // calling performClick() here as well double-fired the toggle
                    // (panel opened then instantly closed).
                    dragging && (abs(e.rawX - downRawX) > touchSlop || abs(e.rawY - downRawY) > touchSlop)
                }
                else -> false
            }
        }
    }

    /**
     * Two-phase navigation. On the home page we poll two reliable signals
     * (cf_clearance cookie present, or the page no longer looks like a Cloudflare
     * challenge) and advance to /agent as soon as either is satisfied — instead of
     * a fixed 1.2s wait that breaks under network jitter.
     */
    private fun onArenaPageFinished(view: WebView, url: String) {
        if (!url.startsWith("https://arena.ai/")) return
        val path = runCatching { java.net.URI(url).path ?: "" }.getOrDefault("")
        val onHome = path == "/" || path.isEmpty()
        if (onHome && !advancedToAgent) {
            warmUpElapsed = 0L
            pollWarmUp(view)
            return
        }
        injectSnoop(view)
        // A full page load of a saved conversation → echo its remembered model.
        HistoryLogic.sessionFromPath(path)?.let { restoreModelForSession(it) }
    }

    /**
     * SPA navigation (pushState) handler — fires when the user opens a saved chat
     * from the sidebar without a full page reload. We only echo the remembered
     * model; snoop.js stays injected across SPA nav so no re-injection is needed.
     */
    private fun onArenaUrlChanged(url: String) {
        if (!url.startsWith("https://arena.ai/")) return
        val path = runCatching { java.net.URI(url).path ?: "" }.getOrDefault("")
        HistoryLogic.sessionFromPath(path)?.let { restoreModelForSession(it) }
    }

    /**
     * Show the locally remembered model(s) for a conversation, if any. Guarded so
     * it runs once per session change and never clobbers a model we've already
     * resolved live for the same session (the live snoop→trace result wins).
     */
    private fun restoreModelForSession(sessionId: String) {
        if (sessionId == lastRestoredSession) return
        lastRestoredSession = sessionId
        lastSessionId = sessionId
        // If we already resolved this exact session live this run, keep that.
        sessionModels[sessionId]?.let { live ->
            currentModel = live
            hudModel.text = live
            applyBallModel(live.substringBefore(" / "))
            return
        }
        val models = store.modelsFor(sessionId)
        if (models.isEmpty()) return
        val joined = models.joinToString(" / ")
        sessionModels[sessionId] = joined
        currentModel = joined
        hudModel.text = joined
        hudStatus.text = "已恢复本地记录的模型"
        applyBallModel(models.first())
    }

    /** Recheck readiness every [WARMUP_POLL_MS]; give up to a hard cap, then advance anyway. */
    private fun pollWarmUp(view: WebView) {
        if (advancedToAgent || isFinishing) return
        val cookies = CookieManager.getInstance().getCookie(ARENA_HOME) ?: ""
        val clearance = cookies.contains("cf_clearance")
        // Probe the DOM for a Cloudflare interstitial. An unknown/failed probe is
        // treated as "still challenging" so we keep waiting (never jump early).
        view.evaluateJavascript(CHALLENGE_PROBE_JS) { raw ->
            val isChallenge = raw?.contains("true") != false // null or "true" → still challenging
            val timedOut = warmUpElapsed >= WARMUP_MAX_MS
            if (WarmUp.shouldAdvance(clearance, isChallenge) || timedOut) {
                advance(view)
            } else {
                warmUpElapsed += WARMUP_POLL_MS
                view.postDelayed({ pollWarmUp(view) }, WARMUP_POLL_MS)
            }
        }
    }

    private fun advance(view: WebView) {
        if (advancedToAgent || isFinishing) return
        advancedToAgent = true
        view.loadUrl(ARENA_AGENT)
    }

    private fun injectSnoop(view: WebView) {
        // Order matters: conversation-rename exposes ArenaConversationRename, which
        // probe.js's rename/archive actions call; inject it before probe.js.
        for (asset in listOf("snoop.js", "conversation-rename.js", "probe.js")) {
            val js = assets.open(asset).bufferedReader().use { it.readText() }
            view.evaluateJavascript(js, null)
        }
    }

    private fun onSnoopEvent(json: String) {
        val obj = runCatching { JSONObject(json) }.getOrNull() ?: return
        val sessionId = obj.optString("sessionId")
        val token = obj.optString("token")
        if (token.length < 20) return
        // Same token already handled → don't re-pull the trace (dedup, avoids 429s).
        if (token == lastToken) return
        lastToken = token
        if (sessionId.isNotEmpty()) lastSessionId = sessionId
        runOnUiThread { hudStatus.text = "已截获运行令牌，正在拉取 trace…" }
        traceJob?.cancel()
        traceJob = lifecycleScope.launch(Dispatchers.IO) {
            val result = traceClient.fetchModels(token, sessionId)
            withContext(Dispatchers.Main) {
                if (result.ok) {
                    currentModel = result.models.joinToString(" / ")
                    if (sessionId.isNotEmpty()) {
                        sessionModels[sessionId] = currentModel
                        // Whitelist-only persistence: model name(s) keyed by sessionId.
                        // Never tokens, trace, cookies, or conversation text.
                        store.saveModels(sessionId, result.models)
                    }
                    hudModel.text = currentModel
                    hudStatus.text = "已识别模型 · run " + result.runId
                    applyBallModel(result.models.firstOrNull() ?: currentModel)
                } else {
                    hudStatus.text = result.error
                }
            }
        }
    }

    /**
     * Quota: refetch at most every 60s; an account switch (cookie change) forces
     * an earlier refetch but no more often than every 15s (a login/refresh flow
     * mutates cookies rapidly, and hammering /api/me/pulse invites 429s). The
     * 429 Retry-After backoff (pulseBlockedUntil) always takes precedence.
     * Countdown ticks every second regardless.
     */
    private fun startPulseLoop() {
        lifecycleScope.launch(Dispatchers.IO) {
            while (isActive) {
                val cookies = CookieManager.getInstance().getCookie(ARENA_HOME) ?: ""
                val sig = Integer.toHexString(cookies.hashCode())
                val now = System.currentTimeMillis()
                val accountChanged = sig != lastCookieSig
                val minGap = if (accountChanged) 15_000L else 60_000L
                if (now - lastPulseFetch >= minGap && now >= pulseBlockedUntil) {
                    lastCookieSig = sig
                    lastPulseFetch = now
                    when (val r = PulseClient.fetch(cookies)) {
                        is PulseClient.Result.Ok -> {
                            pulse = r.pulse
                            pulseError = ""
                            // Convert refreshedAt → reset instant, then anchor it so
                            // per-minute refetch jitter can't restart the countdown.
                            val candidate = PulseTiming.resetTimeFromRefreshedAt(r.pulse.refreshedAt, now)
                            resetAtAnchor = PulseTiming.anchorReset(resetAtAnchor, candidate, now)
                        }
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
        // A transient status (cleanup progress) owns the ball center; still update
        // the ring % and panel text, but leave the center label alone.
        if (p == null) {
            hudPulse.text = if (pulseError.isNotEmpty()) "额度：$pulseError" else "额度读取中…"
            hudPulseBar.progress = 0
            ball.percent = -1
            if (!ballBusy && currentModel.isEmpty()) { ball.centerIsModel = false; ball.centerTop = "…"; ball.centerBottom = "" }
            return
        }
        val countdown = if (resetAtAnchor > 0) {
            val ms = resetAtAnchor - System.currentTimeMillis()
            if (ms > 0) " · ${formatCountdown(ms)} 后重置" else " · 已到重置时间"
        } else ""
        hudPulse.text = "剩余额度 ${p.percent}%$countdown" + (if (pulseError.isNotEmpty()) " · $pulseError" else "")
        hudPulseBar.progress = p.percent
        val color = FloatingBallView.colorFor(p.percent)
        hudPulseBar.progressTintList = ColorStateList.valueOf(color)
        ball.percent = p.percent
        // No model yet → show quota % centered; otherwise the model name/version
        // (set once in applyBallModel) stays in the ring. Never while ballBusy.
        if (!ballBusy && currentModel.isEmpty()) { ball.centerIsModel = false; ball.centerTop = "${p.percent}%"; ball.centerBottom = "" }
    }

    /**
     * Show cleanup (archive-sweep) progress in the ball center. While active the
     * center reads "清理" / "N" (count archived); when it ends we briefly show the
     * final count, then release the center back to the quota/model display.
     */
    private fun showCleanupOnBall(archived: Int, active: Boolean) {
        if (active) {
            ballBusy = true
            ball.centerIsModel = false
            ball.centerTop = "清理"
            ball.centerBottom = if (archived > 0) "$archived" else "…"
        } else {
            ball.centerIsModel = false
            ball.centerTop = "已归档"
            ball.centerBottom = "$archived"
            // Hold the final count ~3s, then hand the center back to renderPulse.
            ball.postDelayed({
                ballBusy = false
                renderPulse()
            }, 3000)
        }
    }

    private fun formatCountdown(ms: Long): String {
        val s = maxOf(0L, (ms + 999) / 1000)
        return "%d:%02d:%02d".format(s / 3600, s % 3600 / 60, s % 60)
    }

    /**
     * Split a model id into a name line and a version line for the ball center,
     * e.g. "gemini-2.5-pro" → name "gemini", version "2.5-pro";
     *      "claude-opus-4-8" → name "claude-opus", version "4-8";
     *      "gpt-4o" → name "gpt", version "4o".
     * The first numeric token starts the version; everything before it is the name.
     */
    private fun applyBallModel(model: String) {
        // While a transient status owns the ball center (cleanup sweep), don't let a
        // navigation/trace-triggered model update overwrite it.
        if (ballBusy) return
        val id = model.trim()
        if (id.isEmpty()) { ball.centerIsModel = false; ball.centerTop = "…"; ball.centerBottom = ""; return }
        ball.centerIsModel = true
        val parts = id.split('-', ' ', '_', '/').filter { it.isNotBlank() }
        val versionStart = parts.indexOfFirst { it.first().isDigit() }
        if (versionStart <= 0) {
            ball.centerTop = clip(id, 10); ball.centerBottom = ""
        } else {
            val name = parts.take(versionStart).joinToString("-")
            val version = parts.drop(versionStart).joinToString("-")
            ball.centerTop = clip(name, 10)
            ball.centerBottom = clip(version, 10)
        }
    }

    private fun clip(s: String, max: Int) = if (s.length > max) s.take(max) else s

    override fun onDestroy() {
        traceJob?.cancel()
        probe.stop()
        webView.destroy()
        super.onDestroy()
    }

    private companion object {
        const val ARENA_HOME = "https://arena.ai/"
        const val ARENA_AGENT = "https://arena.ai/agent"
        const val WARMUP_POLL_MS = 400L
        const val WARMUP_MAX_MS = 15_000L
        // Returns "true" when the page still looks like a Cloudflare interstitial.
        const val CHALLENGE_PROBE_JS =
            "(function(){try{" +
                "var t=(document.title||'').toLowerCase();" +
                "var hasForm=!!document.querySelector('#challenge-form,#challenge-running,#cf-challenge-running,iframe[src*=\"challenges.cloudflare\"]');" +
                "var moment=t.indexOf('just a moment')>=0||t.indexOf('attention required')>=0;" +
                "return (hasForm||moment)?'true':'false';" +
                "}catch(e){return 'true';}})();"
    }
}
