package com.ati.arena.bridge

import android.webkit.JavascriptInterface

/**
 * JS-RPC channel exposed to probe.js as `window.ArenaProbeBridge`.
 *
 * The native [com.ati.arena.probe.ProbeController] issues a request
 * (window.ArenaProbe.call(action, argsJson, reqId)); probe.js resolves it by
 * calling onResult(reqId, resultJson). onLog carries human-readable progress.
 */
class ProbeBridge(
    private val resultHandler: (reqId: String, resultJson: String) -> Unit,
    private val logHandler: (String) -> Unit = {},
) {
    /** resultJson: {"ok":true,"data":{…}} or {"ok":false,"error":"…"} */
    @JavascriptInterface
    fun onResult(reqId: String, resultJson: String) = resultHandler(reqId, resultJson)

    @JavascriptInterface
    fun onLog(line: String) = logHandler(line)
}
