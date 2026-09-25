package com.ati.arena.session

import com.ati.arena.net.TraceClient
import com.ati.arena.protocol.ArenaProtocol
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext

/**
 * Executes [TurnIntake]'s decisions: one trace fetch per run, at most
 * [maxConcurrent] at a time, never cancelling another run's fetch (the old code
 * cancelled the in-flight lookup whenever any new token arrived, so earlier turns
 * were silently dropped). Main-thread API; network work runs on IO.
 */
class TraceCoordinator(
    private val scope: CoroutineScope,
    val intake: TurnIntake,
    private val traceClient: TraceClient,
    /** A conversation's turn state changed (called on the main thread). */
    private val onChanged: (sessionId: String) -> Unit,
    /**
     * Content-free progress lines for the panel log ("第 N 轮" capture/resolve
     * outcomes). Never receives tokens or page data.
     */
    private val onLog: (String) -> Unit = {},
    maxConcurrent: Int = 2,
) {
    private val gate = Semaphore(maxConcurrent)
    private val inFlight = HashMap<String, Job>()
    // Consecutive identical lines are collapsed (streams replay many tokens).
    private var lastLogged: String? = null

    private fun log(line: String) {
        if (line == lastLogged) return
        lastLogged = line
        onLog(line)
    }

    /** Handle one captured token or stream-activity ping. Main thread only. */
    fun onSnoop(event: SessionRouting.SnoopEvent, fallbackPagePath: String?) {
        val action = if (event.activity) {
            intake.onActivity(event, fallbackPagePath) { inFlight[it]?.isActive == true }
        } else {
            intake.onToken(event, fallbackPagePath) { inFlight[it]?.isActive == true }
        }
        when (action) {
            is TurnIntake.Action.Ignored -> when (action.reason) {
                TurnIntake.IgnoreReason.ROUTING -> log("忽略：令牌不属于当前对话页")
                TurnIntake.IgnoreReason.TOKEN -> log("忽略：不是运行令牌（结构不符）")
            }
            is TurnIntake.Action.Known -> Unit
            is TurnIntake.Action.Updated -> {
                log("第 ${action.turn.number} 轮 · ${action.turn.note.ifEmpty { "令牌不可用" }}")
                onChanged(action.sessionId)
            }
            is TurnIntake.Action.Fetch -> {
                log("第 ${action.turn.number} 轮 · 已截获运行令牌，解析模型中")
                launchFetch(action.sessionId, action.turn, action.runId, action.token, refresh = false)
                onChanged(action.sessionId)
            }
            is TurnIntake.Action.Query -> {
                log("会话流有新数据，重新校验模型…")
                launchFetch(action.sessionId, action.turn, action.runId, action.token, refresh = true)
            }
        }
    }

    private fun launchFetch(sessionId: String, turn: TurnTracker.Turn, runId: String, token: String, refresh: Boolean) {
        val key = turn.key
        val previousModels = turn.models
        // LAZY: the job is registered before its body can run, and removes itself.
        val job = scope.launch(Dispatchers.Main, start = CoroutineStart.LAZY) {
            try {
                val claims = ArenaProtocol.inspectToken(token, sessionId)
                val result = if (claims == null) {
                    TraceClient.Result(false, error = "令牌格式不符合预期")
                } else {
                    gate.withPermit { withContext(Dispatchers.IO) { traceClient.fetchModels(token, claims) } }
                }
                val updated = intake.onTraceResult(sessionId, key, if (result.ok) result.models else emptyList(), result.error)
                if (updated != null && updated.status == TurnTracker.Status.RESOLVED) {
                    if (refresh) {
                        // The run grew a new reply: report only when models changed.
                        if (updated.models != previousModels) {
                            log("模型更新: " + updated.models.joinToString("、"))
                        }
                    } else {
                        log("第 ${updated.number} 轮 · 模型: " + updated.models.joinToString("、"))
                    }
                } else if (!refresh && updated != null && updated.status == TurnTracker.Status.FAILED) {
                    log("第 ${updated.number} 轮 · 未能解析模型（${updated.note.ifEmpty { result.error ?: "未知原因" }}）")
                }
                onChanged(sessionId)
            } finally {
                if (inFlight[key] === coroutineContext[Job]) inFlight.remove(key)
            }
        }
        inFlight[key] = job
        job.start()
    }

    /** Cancel every running fetch (activity teardown). */
    fun cancelAll() {
        inFlight.values.forEach { it.cancel() }
        inFlight.clear()
    }
}
