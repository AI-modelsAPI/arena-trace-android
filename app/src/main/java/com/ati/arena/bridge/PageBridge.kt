package com.ati.arena.bridge

import android.webkit.WebView
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature

/**
 * Page → app channel for the Arena WebView.
 *
 * Preferred (androidx.webkit, any current WebView): an origin-restricted message
 * listener. `window.ArenaTraceMsg` exists only in https://arena.ai frames, and
 * assets/bridge.js maps the page scripts' calls onto it. No Java object is exposed
 * to the page at all.
 *
 * Fallback (old WebView without WEB_MESSAGE_LISTENER): the previous
 * `addJavascriptInterface` objects (window.ArenaTrace / window.ArenaProbeBridge).
 * Exactly one of the two is installed, so nothing is ever delivered twice.
 *
 * Install before the first page load. All callbacks may arrive on any thread.
 */
class PageBridge(
    private val webView: WebView,
    private val onSnoop: (payload: String) -> Unit,
    private val onResult: (reqId: String, resultJson: String) -> Unit,
    private val onLog: (line: String) -> Unit,
    private val onWatch: (payload: String) -> Unit = {},
) {
    /** true = origin-restricted message channel; false = legacy JS interfaces. */
    val usesMessageChannel: Boolean = installMessageChannel()

    private var documentStartAdded = false

    init {
        if (!usesMessageChannel) {
            webView.addJavascriptInterface(ArenaBridge(onSnoop), "ArenaTrace")
            webView.addJavascriptInterface(ProbeBridge(onResult, onLog), "ArenaProbeBridge")
        }
    }

    /**
     * Run [script] at document start (before any page script) in every https://arena.ai
     * frame of future navigations. Returns false when the WebView can't; the caller
     * keeps injecting after page load in any case (the scripts are idempotent).
     */
    fun addDocumentStartScript(script: String): Boolean {
        if (documentStartAdded) return true
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) return false
        documentStartAdded = runCatching {
            WebViewCompat.addDocumentStartJavaScript(webView, script, setOf(ARENA_ORIGIN))
        }.isSuccess
        return documentStartAdded
    }

    private fun installMessageChannel(): Boolean {
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) return false
        return runCatching {
            WebViewCompat.addWebMessageListener(webView, CHANNEL, setOf(ARENA_ORIGIN)) { _, message, sourceOrigin, isMainFrame, _ ->
                // The origin rule already restricts this; re-check anyway (defence in depth).
                if (isMainFrame && sourceOrigin.scheme == "https" && sourceOrigin.host == ARENA_HOST) {
                    dispatch(runCatching { message.data }.getOrNull())
                }
            }
        }.isSuccess
    }

    private fun dispatch(raw: String?) {
        when (val m = BridgeMessage.parse(raw)) {
            is BridgeMessage.Snoop -> onSnoop(m.payload)
            is BridgeMessage.Result -> onResult(m.reqId, m.payload)
            is BridgeMessage.Log -> onLog(m.line)
            is BridgeMessage.Watch -> onWatch(m.payload)
            null -> Unit
        }
    }

    companion object {
        const val ARENA_HOST = "arena.ai"
        const val ARENA_ORIGIN = "https://$ARENA_HOST"
        const val CHANNEL = "ArenaTraceMsg"
    }
}
