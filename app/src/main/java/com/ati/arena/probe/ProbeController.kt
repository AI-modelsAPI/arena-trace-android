package com.ati.arena.probe

import android.webkit.WebView
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.CoroutineScope
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * Native orchestrator for the auto-probe. It runs the loop the extension's
 * background.js used to run, but drives the page through discrete JS-RPC calls
 * (window.ArenaProbe.call) and gets model names from the app's existing
 * snoop -> TraceClient pipeline, keyed by sessionId.
 *
 * Everything is explicit-start / cancellable-stop. The probe sends REAL messages
 * that consume quota — the UI must make that clear before [start] is called.
 */
class ProbeController(
    private val webView: WebView,
    private val scope: CoroutineScope,
    /** Resolve a sessionId to its model name(s), or null if not yet known. */
    private val modelForSession: (sessionId: String) -> String?,
    private val onProgress: (String) -> Unit,
    private val onFinished: (summary: String) -> Unit,
    /**
     * Cleanup lifecycle for the floating-ball status display:
     * (archivedCount, active). active=true while sweeping, false when it ends.
     */
    private val onCleanupState: (archived: Int, active: Boolean) -> Unit = { _, _ -> },
) {
    data class Config(
        val targets: List<String>,
        val maxRounds: Int,
        /** true = keep probing until EVERY target is hit (default); false = stop at first hit. */
        val findAll: Boolean,
        val autoRename: Boolean,
    )

    private var job: Job? = null
    val isRunning: Boolean get() = job?.isActive == true

    // JS-RPC plumbing: reqId -> waiter. Resolved by ProbeBridge.onResult.
    private val pending = ConcurrentHashMap<String, CompletableDeferred<JSONObject>>()
    private val reqSeq = AtomicLong(0)

    // Per-model suffix counter (extension kept this in localStorage).
    private val suffixCounters = ConcurrentHashMap<String, Int>()

    /** Called from ProbeBridge.onResult (any thread). */
    fun deliverResult(reqId: String, resultJson: String) {
        val waiter = pending.remove(reqId) ?: return
        val obj = runCatching { JSONObject(resultJson) }.getOrElse {
            JSONObject().put("ok", false).put("error", "结果解析失败")
        }
        waiter.complete(obj)
    }

    fun stop() {
        job?.cancel()
        job = null
    }

    /**
     * One-off: fill the CURRENTLY open conversation's composer with [text] and send.
     * Independent of the probe loop (does not touch [job]); refused while a probe
     * is running so the two don't fight over the composer.
     */
    fun quickSend(text: String, onResult: (String) -> Unit) {
        if (isRunning) { onResult("探针运行中，请先停止再发送"); return }
        if (text.isBlank()) { onResult("请先在面板设置要发送的内容"); return }
        scope.launch(Dispatchers.Main) {
            try {
                rpc("sendToCurrent", JSONObject().put("text", text))
                onResult("已发送到当前对话")
            } catch (_: CancellationException) {
                onResult("发送已取消")
            } catch (e: Exception) {
                onResult("发送失败：${e.message}")
            }
        }
    }

    fun start(cfg: Config) {
        if (isRunning) { onProgress("探针已在运行"); return }
        job = scope.launch(Dispatchers.Main) {
            val hits = mutableListOf<ProbeLogic.Hit>()
            val allTargets = cfg.targets
            try {
                onProgress("开始探针 · 目标 ${allTargets.joinToString("、")} · ${if (cfg.findAll) "命中全部才停" else "命中即停"} · 最多 ${cfg.maxRounds} 轮")
                for (round in 1..cfg.maxRounds) {
                    ensureActive()

                    // For findAll we surface which targets are still outstanding, but we
                    // ALWAYS match against the full target list — a hit target is never
                    // removed from the pool and may be hit again in a later round.
                    val outstanding = ProbeLogic.remainingTargets(allTargets, hits)
                    val pacingLabel = if (cfg.findAll) "待命中 ${outstanding.joinToString("、")}" else "命中即停"
                    val prompt = ProbeLogic.PROMPTS[(round - 1) % ProbeLogic.PROMPTS.size]
                    onProgress("第 $round 轮 · 发送 \"$prompt\" · $pacingLabel")

                    // 1) fresh chat, 2) confirm Agent Mode, 3) send probe prompt
                    rpc("newChat")
                    rpc("ensureAgentMode")
                    val sendData = rpc("send", JSONObject().put("prompt", prompt))
                    val sessionId = sendData.optString("session")
                    if (sessionId.isEmpty()) {
                        onProgress("未拿到会话 id，跳过本轮")
                        pace()
                        continue
                    }

                    // 4) wait for the model name via the snoop -> trace pipeline
                    val models = awaitModels(sessionId)
                    if (models.isEmpty()) { onProgress("第 $round 轮未识别模型，继续"); pace(); continue }
                    onProgress("识别到：${models.joinToString(" / ")}")

                    // 5) match against the FULL target list every round (non-draining).
                    val roundHits = ProbeLogic.matchTargets(models, allTargets)
                    for (h in roundHits) {
                        hits.add(h)
                        onProgress("命中目标 ${h.target} → ${h.model}")
                    }
                    // Rename the round's session at most once (one session, one title).
                    if (roundHits.isNotEmpty() && cfg.autoRename) {
                        renameHit(sessionId, models.firstOrNull() ?: roundHits.first().model)
                    }

                    if (cfg.findAll) {
                        if (ProbeLogic.allTargetsHit(allTargets, hits)) { onProgress("全部目标已命中，停止"); break }
                    } else if (roundHits.isNotEmpty()) {
                        onProgress("命中，按设置停止"); break
                    }
                    // Space out rounds so we don't hammer Arena / trigger 429s.
                    pace()
                }
                val hitStr = if (hits.isEmpty()) "无" else hits.joinToString("、") { "${it.target}→${it.model}" }
                onFinished("探针结束 · 命中：$hitStr")
            } catch (_: CancellationException) {
                onFinished("探针已停止（命中 ${hits.size} 个）")
            } catch (e: Exception) {
                onFinished("探针中断：${e.message}")
            }
        }
    }

    /** Inter-round pacing (also lets a stop request take effect between rounds). */
    private suspend fun pace() {
        delay(ROUND_PACING_MS)
    }

    private suspend fun renameHit(sessionId: String, model: String) {
        val (suffix, updated) = ProbeLogic.nextSuffix(model, suffixCounters)
        suffixCounters.clear(); suffixCounters.putAll(updated)
        val title = "$model-$suffix"
        runCatching { rpc("rename", JSONObject().put("sessionId", sessionId).put("title", title)) }
            .onSuccess { onProgress("已重命名为 $title") }
            .onFailure { onProgress("重命名失败：${it.message}") }
    }

    /** Poll the app's snoop/trace result for [sessionId] up to a timeout. */
    private suspend fun awaitModels(sessionId: String): List<String> {
        val deadline = System.currentTimeMillis() + MODEL_WAIT_MS
        while (System.currentTimeMillis() < deadline) {
            val m = modelForSession(sessionId)
            if (!m.isNullOrBlank()) return m.split(" / ").map { it.trim() }.filter { it.isNotEmpty() }
            delay(500)
        }
        return emptyList()
    }

    /**
     * Sidebar title sweep: archive chats whose title is bare arithmetic (our
     * probe residue). Never deletes; never touches user-named chats.
     *
     * archive() requires being ON the target chat's own page (its guard checks
     * location.pathname === /agent/{sessionId}), so we navigate to each candidate
     * via a real sidebar click (openConversation) before archiving. The candidate
     * list is recomputed each pass because titles/sidebar entries shift as chats
     * get archived. keepSessionId (the currently-open chat) is never archived.
     */
    fun cleanup(keepSessionId: String?) {
        if (isRunning) { onProgress("探针运行中，请先停止再清理"); return }
        job = scope.launch(Dispatchers.Main) {
            var ok = 0
            var failed = 0
            onCleanupState(0, true)
            try {
                onProgress("扫描侧栏算式标题…")
                val done = HashSet<String>()   // sessions we've handled (archived or failed)
                while (true) {
                    ensureActive()
                    var candidate = nextCandidate(keepSessionId, done)
                    // After an archive the sidebar Sheet may still be repopulating,
                    // so an empty result isn't conclusive — retry once with a fresh
                    // sidebar load before deciding the sweep is finished.
                    if (candidate == null) {
                        delay(800)
                        candidate = nextCandidate(keepSessionId, done)
                        if (candidate == null) break
                    }
                    val c = candidate
                    if (ok + failed == 0) onProgress("发现算式标题对话，开始归档")
                    try {
                        // Navigate to the chat first; archive's guard needs its page.
                        rpc("openConversation", JSONObject().put("sessionId", c.sessionId))
                        rpc("archive", JSONObject().put("sessionId", c.sessionId))
                        ok++; done.add(c.sessionId); onProgress("已归档 ${c.title}")
                        onCleanupState(ok, true)
                    } catch (ce: CancellationException) {
                        throw ce
                    } catch (e: Exception) {
                        failed++; done.add(c.sessionId)
                        onProgress("归档 ${c.title} 失败：${e.message}")
                        if (failed >= 3) { onProgress("连续失败已中止"); break }
                    }
                    delay(600)
                }
                if (ok == 0 && failed == 0) onFinished("没有需要归档的算式标题对话")
                else onFinished("清理完成 · 已归档 $ok" + (if (failed > 0) "，失败 $failed" else "") + "（仅归档，未删除）")
            } catch (_: CancellationException) {
                onFinished("清理已停止（已归档 $ok）")
            } catch (e: Exception) {
                onFinished("清理中断：${e.message}")
            } finally {
                onCleanupState(ok, false)
            }
        }
    }

    /** Re-scan the sidebar and return the first arithmetic-title chat not yet handled. */
    private suspend fun nextCandidate(
        keepSessionId: String?,
        done: Set<String>,
    ): ProbeLogic.SidebarItem? {
        val sidebar = fetchSidebar()
        return ProbeLogic.arithmeticCleanupCandidates(sidebar, keepSessionId)
            .firstOrNull { it.sessionId !in done }
    }

    private suspend fun fetchSidebar(): List<ProbeLogic.SidebarItem> {        val listData = rpc("sidebarList")
        val items = listData.optJSONArray("items")
        return buildList {
            if (items != null) for (i in 0 until items.length()) {
                val o = items.getJSONObject(i)
                add(ProbeLogic.SidebarItem(o.optString("sessionId"), o.optString("title")))
            }
        }
    }

    /** Issue one JS-RPC call and await its result; throws on ok:false or timeout. */
    private suspend fun rpc(action: String, args: JSONObject? = null): JSONObject {
        val reqId = "r" + reqSeq.incrementAndGet()
        val waiter = CompletableDeferred<JSONObject>()
        pending[reqId] = waiter
        val argsJson = (args ?: JSONObject()).toString()
        val call = "window.ArenaProbe && window.ArenaProbe.call(" +
            jsStr(action) + "," + jsStr(argsJson) + "," + jsStr(reqId) + ");"
        withContext(Dispatchers.Main) { webView.evaluateJavascript(call, null) }
        val res = withTimeoutOrNull(RPC_TIMEOUT_MS) { waiter.await() }
        pending.remove(reqId)
        if (res == null) throw IllegalStateException("$action 超时")
        if (!res.optBoolean("ok", false)) throw IllegalStateException(res.optString("error", "$action 失败"))
        return res.optJSONObject("data") ?: JSONObject()
    }

    private fun jsStr(s: String): String =
        "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"")
            .replace("\n", "\\n").replace("\r", "\\r") + "\""

    private companion object {
        const val RPC_TIMEOUT_MS = 35_000L
        const val MODEL_WAIT_MS = 45_000L
        // Space out probe rounds so we don't hammer Arena and trip 429 rate limits.
        const val ROUND_PACING_MS = 2_000L
    }
}
