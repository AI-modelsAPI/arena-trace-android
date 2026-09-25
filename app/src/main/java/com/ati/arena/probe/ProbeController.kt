package com.ati.arena.probe

import android.webkit.WebView
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * Native orchestrator for the auto-probe and the arithmetic-title cleanup. It
 * drives the page through discrete JS-RPC calls (window.ArenaProbe.call) and
 * gets model names from the app's snoop → trace pipeline, keyed by sessionId.
 *
 * Everything is explicit-start / cancellable-stop. The probe sends REAL messages
 * that consume quota — the UI must make that clear before [start] is called.
 * All callbacks are delivered on the main thread.
 */
class ProbeController(
    private val webView: WebView,
    private val scope: CoroutineScope,
    /** Current model name(s) of a conversation, or empty while unknown. */
    private val modelsForSession: (sessionId: String) -> List<String>,
    private val counters: SuffixCounters,
    /** Re-inject the page scripts (used when an RPC finds them missing). */
    private val ensureScripts: () -> Unit,
    private val listener: Listener,
) {

    interface Listener {
        fun onProgress(message: String)
        fun onFinished(summary: String)
        /** (round, maxRounds, hits, active) — active=false once the probe ends. */
        fun onProbeState(round: Int, maxRounds: Int, hits: Int, active: Boolean) {}
        /** (archived, active) — active=false once the sweep ends. */
        fun onCleanupState(archived: Int, active: Boolean) {}
    }

    /** Persistent per-name suffix counters for hit titles. */
    interface SuffixCounters {
        fun load(): Map<String, Int>
        fun save(counters: Map<String, Int>)
    }

    data class Config(
        val targets: List<String>,
        val maxRounds: Int,
        /** true = keep probing until EVERY target is hit; false = stop at first hit. */
        val findAll: Boolean,
        val autoRename: Boolean,
        /** Prefix for hit titles ("[探针] " → "[探针] model-001"); may be empty. */
        val renamePrefix: String = "",
    )

    private var job: Job? = null
    val isRunning: Boolean get() = job?.isActive == true

    // JS-RPC plumbing: reqId -> waiter. Resolved by ProbeBridge.onResult.
    private val pending = ConcurrentHashMap<String, CompletableDeferred<JSONObject>>()
    private val reqSeq = AtomicLong(0)

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
     * Refused while a probe/cleanup is running so they don't fight over the page.
     */
    fun quickSend(text: String, onResult: (String) -> Unit) {
        if (isRunning) { onResult("任务运行中，请先停止再发送"); return }
        if (text.isBlank()) { onResult("请先在面板设置要发送的内容"); return }
        scope.launch(Dispatchers.Main) {
            val message = try {
                rpc("sendToCurrent", JSONObject().put("text", text))
                "已发送到当前对话"
            } catch (_: CancellationException) {
                "发送已取消"
            } catch (e: Exception) {
                "发送失败：${e.message}"
            }
            onResult(message)
        }
    }

    // ---------------------------------------------------------------- probe

    fun start(cfg: Config) {
        if (isRunning) { listener.onProgress("已有任务在运行"); return }
        if (cfg.targets.isEmpty()) { listener.onProgress("请先填写目标模型"); return }
        job = scope.launch(Dispatchers.Main) {
            val hits = mutableListOf<ProbeLogic.Hit>()
            val targets = cfg.targets
            listener.onProbeState(0, cfg.maxRounds, 0, true)
            try {
                listener.onProgress(
                    "开始探针 · 目标 ${targets.joinToString("、")} · " +
                        "${if (cfg.findAll) "命中全部才停" else "命中即停"} · 最多 ${cfg.maxRounds} 轮",
                )
                for (round in 1..cfg.maxRounds) {
                    ensureActive()
                    listener.onProbeState(round, cfg.maxRounds, hits.size, true)

                    // Every round matches against the FULL target list — a hit target is
                    // never removed from the pool; findAll only reports what's outstanding.
                    val outstanding = ProbeLogic.remainingTargets(targets, hits)
                    val pacing = if (cfg.findAll) "待命中 ${outstanding.joinToString("、")}" else "命中即停"
                    val prompt = ProbeLogic.randomPrompt()
                    listener.onProgress("第 $round 轮 · 发送 \"$prompt\" · $pacing")

                    rpc("newChat")
                    rpc("ensureAgentMode")
                    val sessionId = rpc("send", JSONObject().put("prompt", prompt)).optString("session")
                    if (sessionId.isEmpty()) {
                        listener.onProgress("未拿到会话 id，跳过本轮")
                        pace(); continue
                    }

                    val models = awaitModels(sessionId)
                    if (models.isEmpty()) {
                        listener.onProgress("第 $round 轮未识别模型，继续")
                        pace(); continue
                    }
                    listener.onProgress("识别到：${models.joinToString(" / ")}")

                    val roundHits = ProbeLogic.matchTargets(models, targets)
                    roundHits.forEach { listener.onProgress("命中目标 ${it.target} → ${it.model}") }
                    hits.addAll(roundHits)
                    listener.onProbeState(round, cfg.maxRounds, hits.size, true)
                    // One session, one title.
                    if (roundHits.isNotEmpty() && cfg.autoRename) {
                        renameHit(sessionId, models.firstOrNull() ?: roundHits.first().model, cfg.renamePrefix)
                    }

                    if (cfg.findAll) {
                        if (ProbeLogic.allTargetsHit(targets, hits)) { listener.onProgress("全部目标已命中，停止"); break }
                    } else if (roundHits.isNotEmpty()) {
                        listener.onProgress("命中，按设置停止"); break
                    }
                    pace()
                }
                val hitText = if (hits.isEmpty()) "无" else hits.joinToString("、") { "${it.target}→${it.model}" }
                listener.onFinished("探针结束 · 命中：$hitText")
            } catch (_: CancellationException) {
                listener.onFinished("探针已停止（命中 ${hits.size} 个）")
            } catch (e: Exception) {
                listener.onFinished("探针中断：${e.message}")
            } finally {
                // Rename opens the sidebar to reach a chat's ⋯ menu; close it only if
                // the probe opened it.
                closeSidebarIfOurs()
                listener.onProbeState(0, cfg.maxRounds, hits.size, false)
            }
        }
    }

    /** Inter-round pacing (also lets a stop request take effect between rounds). */
    private suspend fun pace() = delay(ROUND_PACING_MS)

    /**
     * Rename a hit conversation to "<prefix><model>-<NNN>". The counter is only
     * committed after Arena confirms the rename, so failures don't burn numbers.
     */
    private suspend fun renameHit(sessionId: String, model: String, prefix: String) {
        val (suffix, updated) = ProbeLogic.nextSuffixFor(prefix, model, counters.load())
        val title = ProbeLogic.hitTitle(prefix, model, suffix)
        try {
            rpc("rename", JSONObject().put("sessionId", sessionId).put("title", title))
            counters.save(updated)
            listener.onProgress("已重命名为 $title")
        } catch (ce: CancellationException) {
            throw ce
        } catch (e: Exception) {
            listener.onProgress("重命名失败：${e.message}")
        }
    }

    /** Wait for the snoop → trace pipeline to resolve [sessionId]'s model. */
    private suspend fun awaitModels(sessionId: String): List<String> {
        val deadline = System.currentTimeMillis() + MODEL_WAIT_MS
        while (System.currentTimeMillis() < deadline) {
            val models = modelsForSession(sessionId)
            if (models.isNotEmpty()) return models
            delay(500)
        }
        return emptyList()
    }

    // -------------------------------------------------------------- cleanup

    private enum class Outcome { ARCHIVED, SKIPPED, FAILED }

    private data class Scan(val items: List<ProbeLogic.SidebarItem>, val current: String?, val complete: Boolean)

    /**
     * Archive every conversation whose title is bare arithmetic (probe residue).
     * Never deletes; never touches user-named chats (the page re-checks each title
     * right before archiving).
     *
     * Why the old sweep missed chats, and what this does instead:
     *  - the whole virtualized sidebar is scanned top → bottom (the old scan only
     *    saw rows mounted at the bottom, so the newest residue was skipped);
     *  - rows are archived from their ⋯ menu using the scan position as a hint;
     *  - failures are retried and the sidebar is re-scanned (up to [MAX_PASSES]),
     *    because lazy loading and archiving shift rows around;
     *  - the OPEN chat, when it is itself arithmetic residue, is left for a fresh
     *    chat first and then archived (it used to be skipped every time);
     *  - a final scan reports anything still left.
     */
    fun cleanup() {
        if (isRunning) { listener.onProgress("已有任务在运行，请先停止"); return }
        job = scope.launch(Dispatchers.Main) {
            var archived = 0
            val done = HashSet<String>()                 // archived or deliberately skipped
            val failures = LinkedHashMap<String, String>() // sessionId -> title, still failing
            val attempts = HashMap<String, Int>()
            var currentNote: String? = null
            var incomplete = false
            var verified: Scan? = null   // a scan that found nothing left to do
            listener.onCleanupState(0, true)
            try {
                for (pass in 1..MAX_PASSES) {
                    ensureActive()
                    listener.onProgress(if (pass == 1) "扫描侧栏（含懒加载的旧对话）…" else "复查侧栏（第 $pass 遍）…")
                    val scan = scanSidebar()
                    incomplete = !scan.complete
                    val exhausted = attempts.filterValues { it >= MAX_ATTEMPTS_PER_CHAT }.keys
                    val plan = ProbeLogic.planCleanup(scan.items, scan.current, done + exhausted)
                    if (plan.isEmpty) { verified = scan; break }
                    if (pass == 1) listener.onProgress("发现 ${plan.size} 个算式标题对话（共扫描 ${scan.items.size} 个），开始归档")

                    var consecutive = 0
                    for (item in plan.others) {
                        ensureActive()
                        when (archiveOne(item)) {
                            Outcome.ARCHIVED -> {
                                archived++; done += item.sessionId; failures.remove(item.sessionId); consecutive = 0
                                listener.onProgress("已归档 ${item.title}")
                                listener.onCleanupState(archived, true)
                            }
                            Outcome.SKIPPED -> { done += item.sessionId; failures.remove(item.sessionId) }
                            Outcome.FAILED -> {
                                attempts[item.sessionId] = (attempts[item.sessionId] ?: 0) + 1
                                failures[item.sessionId] = item.title
                                if (++consecutive >= MAX_CONSECUTIVE_FAILURES) {
                                    listener.onProgress("连续失败 $consecutive 次，本遍中止，稍后复查")
                                    break
                                }
                            }
                        }
                        delay(ARCHIVE_GAP_MS)
                    }

                    plan.current?.let { current ->
                        ensureActive()
                        when (val r = archiveOpenChat(current)) {
                            null -> {
                                archived++; done += current.sessionId; failures.remove(current.sessionId)
                                listener.onCleanupState(archived, true)
                            }
                            else -> { currentNote = r; done += current.sessionId }
                        }
                    }
                    delay(PASS_GAP_MS)
                }

                // Verification: arithmetic chats still in the sidebar (reuses the last
                // pass's scan when it already came back clean).
                val finalScan = verified ?: runCatching { scanSidebar() }.getOrNull()
                val remaining = finalScan?.let { ProbeLogic.planCleanup(it.items, null).size } ?: -1
                listener.onFinished(summary(archived, failures.values.toList(), currentNote, remaining, incomplete))
            } catch (_: CancellationException) {
                listener.onFinished("清理已停止（已归档 $archived）")
            } catch (e: Exception) {
                listener.onFinished("清理中断：${e.message}（已归档 $archived）")
            } finally {
                closeSidebarIfOurs()
                listener.onCleanupState(archived, false)
            }
        }
    }

    private fun summary(archived: Int, failed: List<String>, currentNote: String?, remaining: Int, incomplete: Boolean): String {
        if (archived == 0 && failed.isEmpty() && currentNote == null && remaining <= 0) {
            return "没有需要归档的算式标题对话"
        }
        val parts = mutableListOf("清理完成 · 已归档 $archived")
        if (failed.isNotEmpty()) parts += "失败 ${failed.size}：" + failed.take(3).joinToString("、") + if (failed.size > 3) "…" else ""
        currentNote?.let { parts += it }
        if (remaining > 0) parts += "侧栏仍有 $remaining 个算式标题（可再点一次清理）"
        if (incomplete) parts += "侧栏过长未扫完"
        return parts.joinToString(" · ") + "（仅归档，未删除）"
    }

    /** Archive one row (one retry). */
    private suspend fun archiveOne(item: ProbeLogic.SidebarItem): Outcome {
        var lastError = ""
        repeat(2) { attempt ->
            try {
                rpc(
                    "archiveFromSidebar",
                    JSONObject().put("sessionId", item.sessionId).put("pos", item.position),
                    ARCHIVE_TIMEOUT_MS,
                )
                return Outcome.ARCHIVED
            } catch (ce: CancellationException) {
                throw ce
            } catch (e: Exception) {
                lastError = e.message.orEmpty()
                // The page refuses rows that are no longer arithmetic or are now open.
                if (lastError.contains("不是算式") || lastError.contains("当前打开")) {
                    listener.onProgress("跳过 ${item.title}：$lastError")
                    return Outcome.SKIPPED
                }
                if (attempt == 0) delay(RETRY_DELAY_MS)
            }
        }
        listener.onProgress("归档 ${item.title} 失败：$lastError")
        return Outcome.FAILED
    }

    /**
     * The open chat is arithmetic residue (typically the probe's last round).
     * Leave it for a fresh chat — refused by the page when there's an unsent
     * draft or a reply is still generating — then archive it from the sidebar.
     * Returns null on success, or a note explaining why it was left alone.
     */
    private suspend fun archiveOpenChat(item: ProbeLogic.SidebarItem): String? {
        try {
            rpc("newChat")
        } catch (ce: CancellationException) {
            throw ce
        } catch (e: Exception) {
            return "当前打开的「${item.title}」未归档：${e.message}"
        }
        delay(OPEN_CHAT_SETTLE_MS)
        return when (archiveOne(item)) {
            Outcome.ARCHIVED -> { listener.onProgress("已归档当前对话 ${item.title}（已切到新对话）"); null }
            Outcome.SKIPPED -> "当前对话「${item.title}」已跳过"
            Outcome.FAILED -> "当前对话「${item.title}」归档失败"
        }
    }

    private suspend fun scanSidebar(): Scan {
        val data = rpc("sidebarScan", null, SCAN_TIMEOUT_MS)
        val items = data.optJSONArray("items")
        val list = buildList {
            if (items != null) for (i in 0 until items.length()) {
                val o = items.optJSONObject(i) ?: continue
                val id = o.optString("sessionId")
                if (id.isNotEmpty()) add(ProbeLogic.SidebarItem(id, o.optString("title"), o.optInt("pos", -1)))
            }
        }
        val current = if (data.isNull("current")) null else data.optString("current").ifEmpty { null }
        return Scan(list, current, data.optBoolean("complete", true))
    }

    /** Close the sidebar if (and only if) the probe opened it. Runs even after a stop. */
    private suspend fun closeSidebarIfOurs() {
        withContext(NonCancellable) {
            runCatching {
                withTimeoutOrNull(5_000) { rpc("collapseSidebar", JSONObject().put("onlyIfOpenedByProbe", true), 4_000) }
            }
        }
    }

    // ------------------------------------------------------------------ rpc

    /**
     * Issue one JS-RPC call and await its result; throws on ok:false or timeout.
     * If the page scripts are missing (e.g. after a reload), they are re-injected
     * once and the call is retried — instead of waiting for a timeout.
     */
    private suspend fun rpc(action: String, args: JSONObject? = null, timeoutMs: Long = RPC_TIMEOUT_MS): JSONObject {
        val argsJson = (args ?: JSONObject()).toString()
        var reinjected = false
        while (true) {
            val reqId = "r${reqSeq.incrementAndGet()}_${UUID.randomUUID().toString().substring(0, 8)}"
            val waiter = CompletableDeferred<JSONObject>()
            pending[reqId] = waiter
            try {
                // The bridge is checked too: on the message channel it is defined by
                // bridge.js, so a page that lost the scripts lost it as well.
                val call = "(function(){if(!window.ArenaProbe||!window.ArenaConversationRename||!window.ArenaProbeBridge)return 'missing';" +
                    "window.ArenaProbe.call(${JSONObject.quote(action)},${JSONObject.quote(argsJson)},${JSONObject.quote(reqId)});" +
                    "return 'ok';})()"
                val status = CompletableDeferred<String>()
                withContext(Dispatchers.Main) { webView.evaluateJavascript(call) { status.complete(it.orEmpty()) } }
                val state = withTimeoutOrNull(EVAL_TIMEOUT_MS) { status.await() }.orEmpty()
                if (state.contains("missing")) {
                    if (reinjected) throw IllegalStateException("页面脚本未就绪，请刷新页面后重试")
                    reinjected = true
                    withContext(Dispatchers.Main) { ensureScripts() }
                    delay(REINJECT_SETTLE_MS)
                    continue
                }
                val res = withTimeoutOrNull(timeoutMs) { waiter.await() }
                    ?: throw IllegalStateException("$action 超时")
                if (!res.optBoolean("ok", false)) throw IllegalStateException(res.optString("error", "$action 失败"))
                return res.optJSONObject("data") ?: JSONObject()
            } finally {
                pending.remove(reqId)
            }
        }
    }

    private companion object {
        const val RPC_TIMEOUT_MS = 35_000L
        const val SCAN_TIMEOUT_MS = 180_000L
        const val ARCHIVE_TIMEOUT_MS = 60_000L
        const val EVAL_TIMEOUT_MS = 5_000L
        const val REINJECT_SETTLE_MS = 400L
        const val MODEL_WAIT_MS = 45_000L
        // Space out probe rounds so we don't hammer Arena and trip 429 rate limits.
        const val ROUND_PACING_MS = 2_000L
        const val MAX_PASSES = 3
        const val MAX_ATTEMPTS_PER_CHAT = 2
        const val MAX_CONSECUTIVE_FAILURES = 3
        const val ARCHIVE_GAP_MS = 500L
        const val PASS_GAP_MS = 800L
        const val RETRY_DELAY_MS = 1_200L
        const val OPEN_CHAT_SETTLE_MS = 800L
    }
}
