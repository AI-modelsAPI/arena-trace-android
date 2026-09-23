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
    private lateinit var dock: View
    private lateinit var scrim: View
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

    // Per-turn tracking for the CURRENT conversation. Arena issues a fresh run
    // token per turn, and a turn may be routed to a different model — so each new
    // token is a new turn. We surface which model answered each turn so the user
    // can see mid-conversation model routing.
    private var turnSessionId = ""
    private var turnCount = 0
    private var lastTurnModel = ""
    private var firstTurnModel = ""    // the conversation's first resolved model
    // true when the current turn's model differs from the conversation's first —
    // the ball center draws the name orange-yellow to flag the routing change.
    private var currentRouted = false
    private val turnHistory = ArrayDeque<String>()  // recent "R{n} model", newest last

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
        dock = findViewById(R.id.ball_dock)
        scrim = findViewById(R.id.panel_scrim)
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
            onProbeState = { round, maxRounds, hits, active -> showProbeOnBall(round, maxRounds, hits, active) },
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
        // The ball must be clickable/long-clickable so it reliably receives the
        // full touch stream (DOWN→MOVE→UP); otherwise taps leak to the WebView and
        // the GestureDetector never sees a complete tap.
        ball.isClickable = true
        ball.isLongClickable = true
        // Single tap → radial dock (probe / cleanup / refresh).
        // Double tap → full panel. Long press → quick send.
        val detector = android.view.GestureDetector(this, object : android.view.GestureDetector.SimpleOnGestureListener() {
            override fun onSingleTapConfirmed(e: MotionEvent): Boolean { toggleDock(); return true }
            override fun onDoubleTap(e: MotionEvent): Boolean { expandToPanel(); return true }
            override fun onLongPress(e: MotionEvent) { triggerQuickSend() }
        })
        // Ball: consume DOWN so we keep the event stream; taps go to the detector.
        makeDraggable(ball, consumeDown = true, onTap = { detector.onTouchEvent(it) }) {
            captureAnchor(ball); syncDockPosition()
        }
        // Panel: do NOT consume DOWN (its buttons/inputs need the events).
        makeDraggable(panel, consumeDown = false) { captureAnchor(panel) }

        // Tap anywhere outside the panel/dock → collapse whichever is open.
        scrim.setOnClickListener {
            if (panel.visibility == View.VISIBLE) collapseToBall()
            if (dockShown) hideDock()
        }

        findViewById<ImageButton>(R.id.panel_collapse).setOnClickListener { collapseToBall() }
        findViewById<Button>(R.id.nav_back).setOnClickListener {
            if (webView.canGoBack()) webView.goBack()
        }
        findViewById<Button>(R.id.nav_forward).setOnClickListener {
            if (webView.canGoForward()) webView.goForward()
        }
        findViewById<Button>(R.id.nav_reload).setOnClickListener { webView.reload() }

        // Radial dock buttons.
        findViewById<ImageButton>(R.id.dock_probe).setOnClickListener { hideDock(); startProbeFromDock() }
        findViewById<ImageButton>(R.id.dock_cleanup).setOnClickListener { hideDock(); startCleanupFromDock() }
        findViewById<ImageButton>(R.id.dock_refresh).setOnClickListener { hideDock(); webView.reload() }

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
            startProbeFromPanel()
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

    /** Build a probe Config from the current panel values. */
    private fun probeConfigFromPanel(): ProbeController.Config = ProbeController.Config(
        targets = ProbeLogic.parseTargets(findViewById<EditText>(R.id.probe_targets).text.toString()),
        maxRounds = findViewById<EditText>(R.id.probe_rounds).text.toString().toIntOrNull()?.coerceIn(1, 100) ?: 5,
        findAll = findViewById<CheckBox>(R.id.probe_find_all).isChecked,
        autoRename = findViewById<CheckBox>(R.id.probe_rename).isChecked,
    )

    private fun startProbeFromPanel() {
        val cfg = probeConfigFromPanel()
        if (cfg.targets.isEmpty()) { appendProbeLog("请填写至少一个目标"); return }
        findViewById<TextView>(R.id.probe_log).text = ""
        setProbeRunningUi(true)
        // Collapse to the ball so the probe's own new-chat / send actions aren't
        // blocked by our panel; the ball center shows a live briefing.
        collapseToBall()
        probe.start(cfg)
    }

    /** Dock (single-tap radial) → start the probe with the saved/panel config. */
    private fun startProbeFromDock() {
        if (probe.isRunning) { probe.stop(); setProbeRunningUi(false); return }
        val cfg = probeConfigFromPanel()
        if (cfg.targets.isEmpty()) { flashBall("探针", "无目标"); return }
        setProbeRunningUi(true)
        probe.start(cfg)
    }

    /** Dock (single-tap radial) → run the arithmetic-title cleanup sweep. */
    private fun startCleanupFromDock() {
        probe.cleanup(keepSessionId = lastSessionId.ifEmpty { null })
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

    /**
     * Expand the full panel (double-tap). The panel takes the ball's spot; the ball
     * and dock hide. A transparent full-screen scrim goes behind the panel so a tap
     * anywhere outside collapses it. Animated in with a quick scale+fade.
     */
    private fun expandToPanel() {
        hideDock()
        placeAtAnchor(panel)
        scrim.visibility = View.VISIBLE
        panel.visibility = View.VISIBLE
        ball.visibility = View.INVISIBLE
        panel.alpha = 0f
        panel.scaleX = 0.85f
        panel.scaleY = 0.85f
        panel.pivotX = panel.width.toFloat()
        panel.pivotY = 0f
        panel.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(180L).start()
    }

    /** Collapse: the ball reappears at the exact shared anchor; the panel + scrim hide. */
    private fun collapseToBall() {
        scrim.visibility = View.GONE
        placeAtAnchor(ball)
        ball.visibility = View.VISIBLE
        panel.visibility = View.INVISIBLE
    }

    // ── Radial dock (single tap) ──────────────────────────────────────────────
    private var dockShown = false

    private fun toggleDock() {
        if (dockShown) hideDock() else showDock()
    }

    /**
     * Show the dock to the LEFT of the ball, visually connected to it, animating
     * out from the ball's edge (translationX + fade + slight scale).
     */
    private fun showDock() {
        if (panel.visibility == View.VISIBLE) return
        syncDockPosition()
        dock.visibility = View.VISIBLE
        dockShown = true
        scrim.visibility = View.VISIBLE // tap outside closes the dock too
        dock.alpha = 0f
        dock.translationX = 24f * resources.displayMetrics.density
        dock.scaleX = 0.8f
        dock.pivotX = dock.width.toFloat()
        dock.pivotY = dock.height / 2f
        dock.animate().alpha(1f).translationX(0f).scaleX(1f).setDuration(200L).start()
        // Stagger the three buttons in for a lively pop.
        val ids = intArrayOf(R.id.dock_refresh, R.id.dock_cleanup, R.id.dock_probe)
        ids.forEachIndexed { i, id ->
            val b = findViewById<View>(id)
            b.alpha = 0f; b.scaleX = 0.4f; b.scaleY = 0.4f
            b.animate().alpha(1f).scaleX(1f).scaleY(1f).setStartDelay(60L + i * 55L).setDuration(220L).start()
        }
    }

    private fun hideDock() {
        if (!dockShown) return
        dockShown = false
        if (panel.visibility != View.VISIBLE) scrim.visibility = View.GONE
        dock.animate().alpha(0f).translationX(20f * resources.displayMetrics.density)
            .setDuration(150L).withEndAction { dock.visibility = View.GONE }.start()
    }

    /** Position the dock immediately to the left of the ball, vertically centered on it. */
    private fun syncDockPosition() {
        val parent = ball.parent as? View ?: return
        if (dock.width == 0) {
            dock.measure(
                View.MeasureSpec.makeMeasureSpec(parent.width, View.MeasureSpec.AT_MOST),
                View.MeasureSpec.makeMeasureSpec(parent.height, View.MeasureSpec.AT_MOST),
            )
        }
        val dockW = if (dock.width > 0) dock.width else dock.measuredWidth
        val dockH = if (dock.height > 0) dock.height else dock.measuredHeight
        val overlap = 18f * resources.displayMetrics.density // tuck under the ball so they connect
        val ballCx = ball.x + ball.width / 2f
        val ballCy = ball.y + ball.height / 2f
        var x = ball.x - dockW + overlap
        var y = ballCy - dockH / 2f
        x = x.coerceIn(0f, (parent.width - dockW).toFloat().coerceAtLeast(0f))
        y = y.coerceIn(0f, (parent.height - dockH).toFloat().coerceAtLeast(0f))
        dock.x = x
        dock.y = y
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

    /** Physical back button: close panel/dock first, else navigate WebView history. */
    @Suppress("DEPRECATION")
    override fun onBackPressed() {
        if (panel.visibility == View.VISIBLE) { collapseToBall(); return }
        if (dockShown) { hideDock(); return }
        if (webView.canGoBack()) webView.goBack() else super.onBackPressed()
    }

    /**
     * Drag to reposition [v]; a tap (movement under slop) is forwarded to [onTap]
     * (so the gesture detector sees single/double/long presses). [onMoved] runs
     * after each drag step (used to sync the shared anchor + dock position).
     */
    @SuppressLint("ClickableViewAccessibility")
    private fun makeDraggable(
        v: View,
        consumeDown: Boolean = false,
        onTap: (MotionEvent) -> Unit = {},
        onMoved: () -> Unit = {},
    ) {
        var downRawX = 0f
        var downRawY = 0f
        var startX = 0f
        var startY = 0f
        var dragging = false
        val touchSlop = resources.displayMetrics.density * 8
        v.setOnTouchListener { view, e ->
            // Always let the gesture detector observe events (for tap/double/long).
            onTap(e)
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downRawX = e.rawX; downRawY = e.rawY
                    startX = view.x; startY = view.y
                    dragging = false
                    // Consuming DOWN keeps the whole gesture on this view so the
                    // detector reliably sees UP (single/double tap). Required for
                    // the ball; the panel returns false so its children work.
                    consumeDown
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
                    dragging || consumeDown
                }
                MotionEvent.ACTION_UP -> {
                    // A real drag consumes the event so it doesn't also fire a tap.
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
        // A full page load of a saved conversation → echo its remembered model;
        // a new-chat composer (/agent, no id) → reset the display to 待确认.
        applyNavigation(path)
    }

    /**
     * SPA navigation (pushState) handler — fires when the user opens a saved chat
     * from the sidebar without a full page reload. We only echo the remembered
     * model; snoop.js stays injected across SPA nav so no re-injection is needed.
     */
    private fun onArenaUrlChanged(url: String) {
        if (!url.startsWith("https://arena.ai/")) return
        val path = runCatching { java.net.URI(url).path ?: "" }.getOrDefault("")
        applyNavigation(path)
    }

    /**
     * Route a navigation to the right model-display update:
     *  - /agent/{id}  → restore that conversation's remembered/live model
     *  - /agent       → a fresh composer, so the model is unknown → reset to 待确认
     *    (without this, the ball/panel kept showing the PREVIOUS chat's model).
     *  - anything else → leave the current display alone.
     */
    private fun applyNavigation(path: String) {
        val sessionId = HistoryLogic.sessionFromPath(path)
        when {
            sessionId != null -> restoreModelForSession(sessionId)
            path.trimEnd('/') == "/agent" -> clearModelDisplay()
        }
    }

    /** Reset the model display to "待确认" for a brand-new conversation. */
    private fun clearModelDisplay() {
        if (lastRestoredSession == NEW_CHAT_MARKER) return // already reset for this new chat
        lastRestoredSession = NEW_CHAT_MARKER
        lastSessionId = ""
        currentModel = ""
        // New conversation → drop per-turn history so counting restarts at 1.
        turnSessionId = ""
        turnCount = 0
        lastTurnModel = ""
        firstTurnModel = ""
        currentRouted = false
        turnHistory.clear()
        if (ballBusy) return
        hudModel.text = "模型待确认"
        hudStatus.text = "等待会话流…"
        ball.centerIsModel = false
        ball.centerRouted = false
        renderPulse() // repaint the ball center as quota % / placeholder
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

        // A fresh token = a new turn. Reset the per-turn counter when the session
        // changes; otherwise this is the next turn of the SAME conversation.
        if (sessionId != turnSessionId) {
            turnSessionId = sessionId
            turnCount = 0
            lastTurnModel = ""
            turnHistory.clear()
        }
        turnCount += 1
        val thisTurn = turnCount

        runOnUiThread { hudStatus.text = "第 $thisTurn 轮 · 已截获令牌，正在识别模型…" }
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
                    recordTurn(thisTurn, currentModel)
                    hudModel.text = currentModel
                    applyBallModel(result.models.firstOrNull() ?: currentModel)
                } else {
                    hudStatus.text = "第 $thisTurn 轮 · ${result.error}"
                }
            }
        }
    }

    /**
     * Record which model answered [turn] and refresh the status line so the user
     * can see per-turn model routing (Arena sometimes routes different turns of
     * one conversation to different models). Also flags the ball center orange-
     * yellow when a turn's model differs from the conversation's FIRST model.
     */
    private fun recordTurn(turn: Int, model: String) {
        if (firstTurnModel.isEmpty()) firstTurnModel = model
        // Routed = this turn's model differs from the conversation's first model.
        currentRouted = model != firstTurnModel
        val changedFromPrev = lastTurnModel.isNotEmpty() && model != lastTurnModel
        lastTurnModel = model
        turnHistory.addLast("R$turn $model")
        while (turnHistory.size > 6) turnHistory.removeFirst()
        val head = when {
            currentRouted && changedFromPrev -> "第 $turn 轮 · 已切换模型 → $model"
            currentRouted -> "第 $turn 轮 · $model（非首轮模型）"
            else -> "第 $turn 轮 · $model"
        }
        hudStatus.text = head + "\n本会话: " + turnHistory.joinToString(" · ")
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

    /**
     * Show a live probe briefing in the ball center. While active: "探针" on top
     * and "round/max · 命中N" below. When it ends: "探针" / "命中N" held ~3s, then
     * the center is released back to the quota/model display.
     */
    private fun showProbeOnBall(round: Int, maxRounds: Int, hits: Int, active: Boolean) {
        if (active) {
            ballBusy = true
            ball.centerIsModel = false
            ball.centerTop = if (round <= 0) "探针" else "R$round/$maxRounds"
            ball.centerBottom = "命中$hits"
        } else {
            ball.centerIsModel = false
            ball.centerTop = "探针完"
            ball.centerBottom = "命中$hits"
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
        if (id.isEmpty()) { ball.centerIsModel = false; ball.centerRouted = false; ball.centerTop = "…"; ball.centerBottom = ""; return }
        ball.centerIsModel = true
        // Orange-yellow when this turn's model differs from the conversation's first.
        ball.centerRouted = currentRouted
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
        // Sentinel for lastRestoredSession meaning "the new-chat composer": lets us
        // reset the display to 待确认 exactly once per fresh chat.
        const val NEW_CHAT_MARKER = "\u0000new-chat"
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
