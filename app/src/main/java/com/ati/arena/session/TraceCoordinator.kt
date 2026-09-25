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

    /** Handle one captured token. Main thread only. */
    fun onSnoop(event: SessionRouting.SnoopEvent, fallbackPagePath: String?) {
        when (val action = intake.onToken(event, fallbackPagePath) { inFlight[it]?.isActive == true }) {
            TurnIntake.Action.Ignored, is TurnIntake.Action.Known -> Unit
            is TurnIntake.Action.Updated -> onChanged(action.sessionId)
            is TurnIntake.Action.Fetch -> {
                onLog("第 ${action.turn.number} 轮 · 已截获运行令牌，解析模型中")
                launchFetch(action)
                onChanged(action.sessionId)
            }
        }
    }

    private fun launchFetch(action: TurnIntake.Action.Fetch) {
        val key = action.turn.key
        // LAZY: the job is registered before its body can run, and removes itself.
        val job = scope.launch(Dispatchers.Main, start = CoroutineStart.LAZY) {
            try {
                val claims = ArenaProtocol.inspectToken(action.token, action.sessionId)
                val result = if (claims == null) {
                    TraceClient.Result(false, error = "令牌格式不符合预期")
                } else {
                    gate.withPermit { withContext(Dispatchers.IO) { traceClient.fetchModels(action.token, claims) } }
                }
                val turn = intake.onTraceResult(action.sessionId, key, if (result.ok) result.models else emptyList(), result.error)
                if (turn != null) {
                    if (turn.status == TurnTracker.Status.RESOLVED) {
                        onLog("第 ${turn.number} 轮 · 模型: " + turn.models.joinToString("、"))
                    } else if (turn.status == TurnTracker.Status.FAILED) {
                        onLog("第 ${turn.number} 轮 · 未能解析模型（${turn.note.ifEmpty { result.error ?: "未知原因" }}）")
                    }
                }
                onChanged(action.sessionId)
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
