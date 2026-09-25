package com.ati.arena

import android.os.Bundle
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.ati.arena.bridge.ArenaBridge
import com.ati.arena.bridge.ProbeBridge
import com.ati.arena.net.PulseClient
import com.ati.arena.net.PulseTiming
import com.ati.arena.net.TraceClient
import com.ati.arena.net.WarmUp
import com.ati.arena.probe.ProbeController
import com.ati.arena.session.SessionRouting
import com.ati.arena.session.TraceCoordinator
import com.ati.arena.session.TurnIntake
import com.ati.arena.store.HistoryLogic
import com.ati.arena.store.Store
import com.ati.arena.ui.ControlPanel
import com.ati.arena.ui.HudFormat
import com.ati.arena.ui.TaskState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Hosts the Arena WebView and wires the pieces together:
 *  - page scripts (snoop / rename / probe) → [TraceCoordinator] → per-conversation turns;
 *  - [ProbeController] for probe runs, cleanup sweeps and quick send;
 *  - the quota pulse loop;
 *  - [ControlPanel], the overlay UI, which only renders what it is given here.
 */
class MainActivity : AppCompatActivity(), ControlPanel.Actions {

    private lateinit var webView: WebView
    private lateinit var panel: ControlPanel
    private lateinit var store: Store
    private lateinit var probe: ProbeController

    // Token → turn → trace pipeline. Every conversation has its own turn log; the
    // panel always renders the log of the conversation on screen (displaySession).
    private lateinit var coordinator: TraceCoordinator
    private val intake: TurnIntake get() = coordinator.intake

    // Page state (main thread). currentPath is the last path reported by the
    // WebView; displaySession is the conversation whose turns are shown
    // ("" = new-chat composer before its conversation exists, or none).
    private var currentPath = ""
    private var displaySession = ""

    // Quota. resetAtAnchor is held stable across refetches so the countdown doesn't
    // restart every minute (see PulseTiming).
    private var pulse: PulseClient.Pulse? = null
    private var pulseError = ""
    private var lastPulseFetch = 0L
    private var pulseBlockedUntil = 0L
    private var resetAtAnchor = 0L
    private var lastCookieSig = ""

    // Warm-up: poll for cf_clearance / non-challenge instead of a fixed timer.
    private var advancedToAgent = false
    private var warmUpElapsed = 0L

    private val pageScripts: List<String> by lazy {
        // Order matters: conversation-rename exposes ArenaConversationRename, which
        // probe.js's rename/archive actions call; inject it before probe.js.
        listOf("snoop.js", "conversation-rename.js", "probe.js").map { asset ->
            assets.open(asset).bufferedReader().use { it.readText() }
        }
    }

    private val backCallback = object : OnBackPressedCallback(true) {
        override fun handleOnBackPressed() {
            when {
                panel.handleBack() -> Unit
                webView.canGoBack() -> webView.goBack()
                else -> {
                    // Nothing of ours to go back to: let the system handle it.
                    isEnabled = false
                    onBackPressedDispatcher.onBackPressed()
                    isEnabled = true
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        webView = findViewById(R.id.web)
        store = Store(this)
        coordinator = TraceCoordinator(
            scope = lifecycleScope,
            intake = TurnIntake(store),
            traceClient = TraceClient(),
            onChanged = ::onTurnsChanged,
        )
        panel = ControlPanel(this, store, this)
        probe = ProbeController(
            webView = webView,
            scope = lifecycleScope,
            modelsForSession = { sid -> intake.modelsFor(sid) },
            counters = object : ProbeController.SuffixCounters {
                override fun load() = store.loadSuffixCounters()
                override fun save(counters: Map<String, Int>) = store.saveSuffixCounters(counters)
            },
            ensureScripts = { injectPageScripts(webView) },
            listener = object : ProbeController.Listener {
                override fun onProgress(message: String) = panel.log(message)
                override fun onFinished(summary: String) = panel.log(summary)
                override fun onProbeState(round: Int, maxRounds: Int, hits: Int, active: Boolean) {
                    val state = TaskState.Probe(round, maxRounds, hits)
                    if (active) panel.showTask(state) else panel.finishTask(state)
                }
                override fun onCleanupState(archived: Int, active: Boolean) {
                    val state = TaskState.Cleanup(archived)
                    if (active) panel.showTask(state) else panel.finishTask(state)
                }
            },
        )
        configureWebView()
        onBackPressedDispatcher.addCallback(this, backCallback)
        // Cloudflare warm-up: load the site root first so the managed challenge can
        // run and issue cf_clearance, then advance to /agent once ready.
        webView.loadUrl(ARENA_HOME)
        startPulseLoop()
    }

    private fun configureWebView() {
        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(webView, true)
        webView.settings.javaScriptEnabled = true
        webView.settings.domStorageEnabled = true
        webView.addJavascriptInterface(ArenaBridge(::onSnoopPayload), "ArenaTrace")
        webView.addJavascriptInterface(
            ProbeBridge(
                resultHandler = { reqId, json -> probe.deliverResult(reqId, json) },
                logHandler = { line -> runOnUiThread { if (!isDestroyed) panel.log(line) } },
            ),
            "ArenaProbeBridge",
        )
        webView.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView, url: String) {
                onArenaPageFinished(view, url)
            }

            // Arena is a SPA: opening a saved chat from the sidebar is a client-side
            // (pushState) navigation that does NOT fire onPageFinished; this does.
            override fun doUpdateVisitedHistory(view: WebView, url: String, isReload: Boolean) {
                onArenaUrlChanged(url)
            }
        }
    }

    // ---------------------------------------------------------------- panel actions

    override fun startProbe(config: ProbeController.Config) = probe.start(config)

    override fun startCleanup() = probe.cleanup()

    override fun stopTask() = probe.stop()

    override fun quickSend(text: String) {
        panel.log("正在发送到当前对话…")
        probe.quickSend(text) { result ->
            panel.log(result)
            panel.flash(HudFormat.quickSendFlash(result))
        }
    }

    override fun navigate(nav: ControlPanel.Nav) {
        when (nav) {
            ControlPanel.Nav.BACK -> if (webView.canGoBack()) webView.goBack()
            ControlPanel.Nav.FORWARD -> if (webView.canGoForward()) webView.goForward()
            ControlPanel.Nav.RELOAD -> webView.reload()
        }
    }

    // ---------------------------------------------------------------- navigation

    /**
     * Two-phase navigation. On the home page we poll two reliable signals
     * (cf_clearance cookie present, or the page no longer looks like a Cloudflare
     * challenge) and advance to /agent as soon as either is satisfied.
     */
    private fun onArenaPageFinished(view: WebView, url: String) {
        if (!url.startsWith(ARENA_HOME)) return
        val path = pathOf(url)
        val onHome = path == "/" || path.isEmpty()
        if (onHome && !advancedToAgent) {
            warmUpElapsed = 0L
            pollWarmUp(view)
            return
        }
        injectPageScripts(view)
        onPathChanged(path)
    }

    /** SPA navigation (pushState): the page scripts stay injected; only the conversation changes. */
    private fun onArenaUrlChanged(url: String) {
        if (!url.startsWith(ARENA_HOME)) return
        onPathChanged(pathOf(url))
    }

    private fun pathOf(url: String): String = runCatching { java.net.URI(url).path ?: "" }.getOrDefault("")

    /**
     * The page path changed (full load or SPA navigation). The panel follows the
     * conversation on screen:
     *  - /agent/{id} → that conversation's own turn log (or its stored model);
     *  - /agent      → a fresh composer: nothing to show until its stream starts;
     *  - other pages → leave the display alone.
     * Switching never carries another chat's model or turns over.
     */
    private fun onPathChanged(path: String) {
        val changed = path != currentPath
        currentPath = path
        if (changed) intake.onNavigate(path)
        val next = HistoryLogic.sessionFromPath(path)
            ?: if (SessionRouting.isNewChatPath(path)) intake.newChatSession.orEmpty() else return
        if (!changed && next == displaySession) return
        displaySession = next
        refreshModelDisplay()
    }

    /** A conversation's turns changed (new turn, resolved, failed). Main thread. */
    private fun onTurnsChanged(sessionId: String) {
        // On the new-chat page, adopt the conversation being created.
        if (displaySession.isEmpty() && SessionRouting.isNewChatPath(currentPath) &&
            intake.newChatSession == sessionId
        ) {
            displaySession = sessionId
        }
        if (sessionId == displaySession) refreshModelDisplay()
    }

    /** Render purely from (displaySession, its turns, stored history). */
    private fun refreshModelDisplay() {
        val view = if (displaySession.isEmpty()) null else intake.view(displaySession)
        panel.showSession(view, SessionRouting.isNewChatPath(currentPath))
    }

    /** Recheck readiness every [WARMUP_POLL_MS]; give up at a hard cap, then advance anyway. */
    private fun pollWarmUp(view: WebView) {
        if (advancedToAgent || isFinishing) return
        val cookies = CookieManager.getInstance().getCookie(ARENA_HOME).orEmpty()
        val clearance = cookies.contains("cf_clearance")
        // An unknown/failed DOM probe counts as "still challenging" (never jump early).
        view.evaluateJavascript(CHALLENGE_PROBE_JS) { raw ->
            val isChallenge = raw?.contains("true") != false
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

    /** Inject the page scripts (each is idempotent, so re-injection is safe). */
    private fun injectPageScripts(view: WebView) {
        for (js in pageScripts) view.evaluateJavascript(js, null)
    }

    /**
     * snoop.js → ArenaTrace.onSnoop (JavaBridge thread). Parsed here, handled on
     * the main thread, where all turn state lives. Tokens are never logged.
     */
    private fun onSnoopPayload(json: String) {
        val event = SessionRouting.parse(json) ?: return
        runOnUiThread {
            if (!isDestroyed) coordinator.onSnoop(event, currentPath)
        }
    }

    // ---------------------------------------------------------------- quota

    /**
     * Quota: refetch at most every 60s; an account switch (cookie change) forces an
     * earlier refetch but no more often than every 15s. A 429 Retry-After backoff
     * always takes precedence. The countdown ticks every second regardless, and
     * nothing runs while the activity is not visible.
     */
    private fun startPulseLoop() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                while (isActive) {
                    val cookies = CookieManager.getInstance().getCookie(ARENA_HOME).orEmpty()
                    val sig = Integer.toHexString(cookies.hashCode())
                    val now = System.currentTimeMillis()
                    val accountChanged = sig != lastCookieSig
                    val minGap = if (accountChanged) 15_000L else 60_000L
                    if (now - lastPulseFetch >= minGap && now >= pulseBlockedUntil) {
                        lastCookieSig = sig
                        lastPulseFetch = now
                        when (val r = withContext(Dispatchers.IO) { PulseClient.fetch(cookies) }) {
                            is PulseClient.Result.Ok -> {
                                pulse = r.pulse
                                pulseError = ""
                                val candidate = PulseTiming.resetTimeFromRefreshedAt(r.pulse.refreshedAt, now)
                                resetAtAnchor = PulseTiming.anchorReset(resetAtAnchor, candidate, now)
                            }
                            is PulseClient.Result.Err -> {
                                pulseError = r.message
                                if (r.retryAfterMs > 0) pulseBlockedUntil = now + minOf(r.retryAfterMs, 600_000)
                            }
                        }
                    }
                    renderQuota()
                    delay(1000)
                }
            }
        }
    }

    private fun renderQuota() {
        val p = pulse
        panel.showQuota(
            percent = p?.percent ?: -1,
            detail = HudFormat.quotaDetail(p != null, resetAtAnchor, System.currentTimeMillis(), pulseError),
        )
    }

    // ---------------------------------------------------------------- lifecycle

    override fun onResume() {
        super.onResume()
        webView.onResume()
    }

    override fun onPause() {
        webView.onPause()
        super.onPause()
    }

    override fun onDestroy() {
        panel.release()
        coordinator.cancelAll()
        probe.stop()
        (webView.parent as? ViewGroup)?.removeView(webView)
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
                "var hasForm=!!document.querySelector('#challenge-form,#challenge-running,#cf-challenge-running,iframe[src*=\\\"challenges.cloudflare\\\"]');" +
                "var moment=t.indexOf('just a moment')>=0||t.indexOf('attention required')>=0;" +
                "return (hasForm||moment)?'true':'false';" +
                "}catch(e){return 'true';}})();"
    }
}
