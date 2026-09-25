package com.ati.arena.bridge

import org.json.JSONObject

/**
 * One message on the origin-restricted page → app channel (window.ArenaTraceMsg,
 * see assets/bridge.js). Wire format: {"t":"snoop"|"result"|"log", "p":"…", "id":"…"}.
 * Anything malformed, oversized or unknown is dropped.
 */
sealed interface BridgeMessage {
    /** snoop.js: {"sessionId","token","page"} payload (parsed by SessionRouting). */
    data class Snoop(val payload: String) : BridgeMessage

    /** probe.js: result of one JS-RPC call. */
    data class Result(val reqId: String, val payload: String) : BridgeMessage

    /** Human-readable progress line. */
    data class Log(val line: String) : BridgeMessage

    companion object {
        const val MAX_MESSAGE_CHARS = 4 * 1024 * 1024
        const val MAX_REQ_ID_CHARS = 128
        const val MAX_LOG_CHARS = 500

        fun parse(raw: String?): BridgeMessage? {
            if (raw.isNullOrEmpty() || raw.length > MAX_MESSAGE_CHARS) return null
            val o = runCatching { JSONObject(raw) }.getOrNull() ?: return null
            val payload = o.opt("p") as? String ?: return null
            return when (o.opt("t")) {
                "snoop" -> Snoop(payload)
                "result" -> {
                    val id = o.opt("id") as? String
                    if (id.isNullOrEmpty() || id.length > MAX_REQ_ID_CHARS) null else Result(id, payload)
                }
                "log" -> payload.trim().takeIf { it.isNotEmpty() }?.let { Log(it.take(MAX_LOG_CHARS)) }
                else -> null
            }
        }
    }
}
