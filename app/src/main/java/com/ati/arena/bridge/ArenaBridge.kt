package com.ati.arena.bridge

import android.webkit.JavascriptInterface

/** Bridge called by the injected snoop.js page hook. */
class ArenaBridge(private val onEvent: (String) -> Unit) {
    /** payload: {"sessionId":"…","token":"…"} captured from the Arena SSE stream. */
    @JavascriptInterface
    fun onSnoop(payload: String) = onEvent(payload)
}
