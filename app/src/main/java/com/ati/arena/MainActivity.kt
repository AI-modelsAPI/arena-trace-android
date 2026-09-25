package com.ati.arena

import android.os.Bundle
import android.os.Message
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.ati.arena.bridge.PageBridge
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
import com.ati.arena.ui.LinkTab
import com.ati.arena.ui.TaskState
import com.ati.arena.web.ExternalLinks
import com.ati.arena.web.LinkPolicy
import com.ati.arena.web.ReplyWatchdog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Hosts the Arena WebView and wires the pieces together:
 *  - page scripts (snoop / rename / probe) → [PageBridge] → [TraceCoordinator] →
 *    per-conversation turns;
 *  - [ProbeController] for probe runs, cleanup sweeps and quick send;
 *  - the quota pulse loop;
 *  - [ControlPanel], the overlay UI, which only renders what it is given here;
 *  - [LinkTab]: links never navigate the conversation away, they open in a tab.
 */
class MainActivity : AppCompatActivity(), ControlPanel.Actions {

    private lateinit var webView: WebView
    private lateinit var panel: ControlPanel
    private lateinit var linkTab: LinkTab
    private lateinit var bridge: PageBridge
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

    // Reply watchdog: page-side watchdog.js reports error / empty-reply states;
    // ReplyWatchdog decides when an auto reload is allowed (cooldown, nag, task
    // guard). taskStartedAt doubles as "a task is running" for the guard.
    private var watchdog = ReplyWatchdog.State()
    private var linkTabOpen = false
    private var pageLoading = false
    private var taskStartedAt = 0L

    // Order matters: bridge.js defines the page → app API the others call, and
    // conversation-rename exposes ArenaConversationRename, which probe.js's
    // rename/archive actions use. All four are idempotent.
    private val pageScripts: List<String> by lazy {
        PAGE_SCRIPTS.map { asset -> assets.open(asset).bufferedReader().use { it.readText() } }
    }

    // At document start only the bridge + network tap are needed: snoop must hook
    // fetch/EventSource before Arena's own code runs; the DOM helpers wait for load.
    private val documentStartScript: String by lazy { pageScripts[0] + "\n;\n" + pageScripts[1] }

    private val backCallback = object : OnBackPressedCallback(true) {
        override fun handleOnBackPressed() {
            when {
                panel.handleBack() -> Unit
                linkTab.handleBack() -> Unit
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
            // Progress lines for the panel log; the coordinator is constructed
            // before the panel, so guard with isInitialized.
            onLog = { line ->
                runOnUiThread { if (::panel.isInitialized && !isDestroyed) panel.log(line) }
            },
        )
        panel = ControlPanel(this, store, this)
        // Version marker: every future log paste tells us exactly which build
        // produced it. PackageManager is used so the class has no BuildConfig
        // dependency (keeps the local JVM test harness self-contained).
        val ownVersion = runCatching { packageManager.getPackageInfo(packageName, 0).versionName }.getOrNull()
        panel.log("Arena Trace v" + (ownVersion ?: "?") + " 已就绪")
        linkTab = LinkTab(this) { open ->
            linkTabOpen = open
            panel.setLinkTabOpen(open)
        }
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
                    taskStartedAt = if (active) System.currentTimeMillis() else 0L
                    val state = TaskState.Probe(round, maxRounds, hits)
                    if (active) panel.showTask(state) else panel.finishTask(state)
                }
                override fun onCleanupState(archived: Int, active: Boolean) {
                    taskStartedAt = if (active) System.currentTimeMillis() else 0L
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
        navPollHandler.postDelayed(navPoller, NAV_POLL_MS)
    }

    private fun configureWebView() {
        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(webView, true)
        webView.settings.javaScriptEnabled = true
        webView.settings.domStorageEnabled = true
        // target=_blank / window.open become onCreateWindow → the link tab, instead of
        // replacing the conversation. Script-opened windows still need a user gesture.
        webView.settings.setSupportMultipleWindows(true)
        webView.settings.javaScriptCanOpenWindowsAutomatically = false
        bridge = PageBridge(
            webView,
            onSnoop = ::onSnoopPayload,
            onResult = { reqId, json -> probe.deliverResult(reqId, json) },
            onLog = { line ->
                when {
                    // watchdog.js path push: catches SPA navigations (replaceState,
                    // sidebar switches) that doUpdateVisitedHistory never delivers.
                    line.startsWith(PATH_PREFIX) -> runOnUiThread {
                        if (!isDestroyed) onPathChanged(line.removePrefix(PATH_PREFIX).trim())
                    }
                    line.startsWith(ReplyWatchdog.PREFIX) -> onWatchPayload(line)
                    else -> runOnUiThread { if (!isDestroyed) panel.log(line) }
                }
            },
            onWatch = ::onWatchPayload,
        )
        if (!bridge.usesMessageChannel) panel.log(getString(R.string.log_legacy_bridge))
        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean =
                routeNavigation(view, request)

            override fun onPageStarted(view: WebView, url: String, favicon: android.graphics.Bitmap?) {
                pageLoading = true
            }

            override fun onPageFinished(view: WebView, url: String) {
                pageLoading = false
                onArenaPageFinished(view, url)
            }

            // Arena is a SPA: opening a saved chat from the sidebar is a client-side
            // (pushState) navigation that does NOT fire onPageFinished; this does.
            override fun doUpdateVisitedHistory(view: WebView, url: String, isReload: Boolean) {
                onArenaUrlChanged(url)
            }
        }
        webView.webChromeClient = object : WebChromeClient() {
            override fun onCreateWindow(view: WebView, isDialog: Boolean, isUserGesture: Boolean, resultMsg: Message): Boolean =
                isUserGesture && !isFinishing && linkTab.acceptWindow(resultMsg)

            override fun onProgressChanged(view: WebView, newProgress: Int) {
                panel.showPageProgress(newProgress)
            }
        }
        webView.setDownloadListener { url, _, _, _, _ -> ExternalLinks.download(this, url) }
    }

    /**
     * Same-window main-frame navigations. Arena's own pages, redirects and sign-in
     * flows load in place; a tapped link to another site opens in the link tab;
     * mailto:/tel:/intent: … go to other apps (see [LinkPolicy]).
     */
    private fun routeNavigation(view: WebView, request: WebResourceRequest): Boolean {
        if (!request.isForMainFrame) return false
        val uri = request.url
        val hit = view.hitTestResult?.type
        val linkClick = hit == WebView.HitTestResult.SRC_ANCHOR_TYPE || hit == WebView.HitTestResult.SRC_IMAGE_ANCHOR_TYPE
        return when (LinkPolicy.routeMain(uri.scheme, uri.host, uri.path, request.hasGesture(), request.isRedirect, linkClick)) {
            LinkPolicy.Route.IN_PLACE -> false
            LinkPolicy.Route.NEW_TAB -> {
                linkTab.open(uri.toString())
                true
            }
            LinkPolicy.Route.EXTERNAL_APP -> {
                ExternalLinks.open(this, uri) { fallback -> linkTab.open(fallback) }
                true
            }
            LinkPolicy.Route.BLOCK -> true
        }
    }

    // ---------------------------------------------------------------- panel actions

    override fun startProbe(config: ProbeController.Config) = probe.start(config)

    override fun startCleanup() = probe.cleanup()

    override fun stopTask() = probe.stop()

    override fun quickSend(text: String) {
        panel.log("正在发送到当前对话…")
        taskStartedAt = System.currentTimeMillis()
        probe.quickSend(text) { result ->
            taskStartedAt = 0L
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
        ensureDocumentStartScript()
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
        if (changed && next.isNotEmpty() && next != displaySession) {
            // Visible confirmation that the panel followed the switch.
            panel.log("已切换到当前对话的日志")
        }
        displaySession = next
        refreshModelDisplay()
    }

    // ------------------------------------------------------------ path polling

    private val navPollHandler = android.os.Handler(android.os.Looper.getMainLooper())

    /**
     * Belt-and-braces conversation tracking: WebViewClient callbacks +
     * watchdog.js PATH pushes miss some SPA transitions in practice; a cheap
     * periodic location read cannot. [onPathChanged] is idempotent, repeated
     * same-path polls are a no-op.
     */
    private val navPoller = object : Runnable {
        override fun run() {
            if (isDestroyed) return
            if (::webView.isInitialized) {
                webView.evaluateJavascript("(location && location.pathname) || ''") { raw ->
                    val path = raw?.trim()?.removeSurrounding("\"").orEmpty()
                    if (path.startsWith("/") && path.length <= 512) onPathChanged(path)
                }
            }
            navPollHandler.postDelayed(this, NAV_POLL_MS)
        }
    }

    /** A conversation's turns changed (new turn, resolved, failed). Main thread. */
    private fun onTurnsChanged(sessionId: String) {
        // On the new-chat page, adopt the conversation being created.
        if (displaySession.isEmpty() && SessionRouting.isNewChatPath(currentPath) &&
            intake.newChatSession == sessionId
        ) {
            displaySession = sessionId
        }
        // Turn data always lives under the STREAM session id; the page's own id
        // (which may be a /c/{evalId} alias) resolves to it through the intake.
        if (intake.conversationFor(displaySession) == sessionId) refreshModelDisplay()
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
        ensureDocumentStartScript()
        view.loadUrl(ARENA_AGENT)
    }

    /**
     * From the first real Arena page on, run the bridge + network tap at document
     * start (when the WebView supports it). Deliberately not during the Cloudflare
     * warm-up, so the challenge page runs untouched.
     */
    private fun ensureDocumentStartScript() {
        bridge.addDocumentStartScript(documentStartScript)
    }

    /** Inject the page scripts after load (each is idempotent, so re-injection is safe). */
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

    // ---------------------------------------------------------------- reply watchdog

    /**
     * watchdog.js status ("WATCH|{…}", delivered as a BridgeMessage.Watch on the
     * modern channel). The decide/track logic is pure ([ReplyWatchdog]); here we
     * only supply live page state and perform the reload. No-op while the feature
     * is switched off (tools page) — the switch is read fresh every time.
     */
    private fun onWatchPayload(line: String) {
        val status = ReplyWatchdog.parseLine(line) ?: return
        runOnUiThread {
            if (isDestroyed) return@runOnUiThread
            if (!store.loadPanelPrefs().autoRefresh) return@runOnUiThread
            // A report about a page we already left is worthless — never reload
            // the CURRENT conversation for a previous one's problem.
            if (status.path.trimEnd('/') != currentPath.trimEnd('/')) return@runOnUiThread
            val now = System.currentTimeMillis()
            val decision = ReplyWatchdog.decide(watchdog, status, now, linkTabOpen, pageLoading, taskStartedAt)
            when (decision) {
                ReplyWatchdog.Decision.Reload -> {
                    watchdog = ReplyWatchdog.applied(watchdog, status, decision, now)
                    panel.log(getString(R.string.log_watchdog_reloading))
                    panel.showRecovery()
                    webView.reload()
                }
                is ReplyWatchdog.Decision.Track -> {
                    watchdog = ReplyWatchdog.applied(watchdog, status, decision, now)
                    if (decision.reason == ReplyWatchdog.Reason.NAG) {
                        panel.log(getString(R.string.log_watchdog_nag))
                    }
                }
                ReplyWatchdog.Decision.Ignore -> Unit
            }
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
        linkTab.onResume()
    }

    override fun onPause() {
        linkTab.onPause()
        webView.onPause()
        super.onPause()
    }

    override fun onDestroy() {
        navPollHandler.removeCallbacks(navPoller)
        linkTab.release()
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
        // watchdog.js emits "PATH|<pathname>" whenever the in-page URL changes.
        const val PATH_PREFIX = "PATH|"
        const val NAV_POLL_MS = 1_500L

        // bridge.js must be first (defines the page → app API); watchdog.js polls
        // the conversation DOM and reports through the same bridge. All idempotent.
        val PAGE_SCRIPTS = listOf("bridge.js", "snoop.js", "conversation-rename.js", "probe.js", "watchdog.js")
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
