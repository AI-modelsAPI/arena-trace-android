package com.ati.arena.session

import com.ati.arena.session.SessionRouting.SnoopEvent
import com.ati.arena.session.TurnIntake.Action
import com.ati.arena.session.TurnTracker.Status
import com.ati.arena.store.HistoryLogic
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

class TurnIntakeTest {

    private val now = 1_000_000L
    private val far = now + 3_600

    /** In-memory stand-in for Store, backed by the real HistoryLogic. */
    private class FakeHistory : TurnIntake.History {
        var json = "{}"
        var writes = 0
        override fun runsFor(sessionId: String) = HistoryLogic.runsFor(json, sessionId)
        override fun modelsFor(sessionId: String) = HistoryLogic.modelsFor(json, sessionId)
        override fun saveRun(sessionId: String, key: String, number: Int, models: List<String>) {
            val next = HistoryLogic.mergeRun(json, sessionId, key, number, models, nowMs = 1)
            if (next != json) writes++
            json = next
        }
    }

    private fun token(runId: String, sessionId: String, exp: Long = far): String {
        val claims = """{"pub":true,"iss":"https://id.trigger.dev","aud":"https://api.trigger.dev","exp":$exp,"scopes":["read:runs:$runId","read:sessions:$sessionId"]}"""
        val payload = Base64.getUrlEncoder().withoutPadding().encodeToString(claims.toByteArray())
        return "eyJhbGciOiJIUzI1NiJ9.$payload.sig"
    }

    private fun event(runId: String, session: String, page: String? = "/agent/$session", exp: Long = far) =
        SnoopEvent(session, token(runId, session, exp), page)

    private fun intake(history: FakeHistory = FakeHistory()) = TurnIntake(history, nowSeconds = { now })

    private val notInFlight: (String) -> Boolean = { false }

    // ---- routing ----

    @Test
    fun tokenForAnotherConversationIsRecordedUnderItsOwnSession() {
        // A late token from the chat the user just LEFT must never paint over the
        // new chat: it is attributed to its own stream session, which is what
        // keeps the two logs separated. (Rejecting it used to drop turn 2+.)
        val i = intake()
        val action = i.onToken(event("run_old", "s1", page = "/agent/s2"), null, notInFlight)
        assertTrue(action is Action.Fetch)
        assertEquals("s1", (action as Action.Fetch).sessionId)
        assertTrue(i.turns.turns("s2").isEmpty())
        assertEquals(1, i.turns.turns("s1").size)
    }

    @Test
    fun firstStreamOfANewChatIsAccepted() {
        val i = intake()
        val action = i.onToken(event("run_a", "s1", page = "/agent"), null, notInFlight)
        assertTrue(action is Action.Fetch)
    }

    @Test
    fun lateTokenFromTheChatJustLeftIsRejectedOnTheNewChatPage() {
        val i = intake()
        val a = i.onToken(event("run_a", "s1"), null, notInFlight) as Action.Fetch
        i.onTraceResult("s1", a.turn.key, listOf("model-a"), "")
        // User taps "New chat"; s1's closing stream still delivers a token.
        i.onNavigate("/agent")
        assertEquals(
            Action.Ignored(TurnIntake.IgnoreReason.ROUTING),
            i.onToken(event("run_a2", "s1", page = "/agent"), null, notInFlight),
        )
        // The new conversation's first stream is adopted…
        assertTrue(i.onToken(event("run_n", "s2", page = "/agent"), null, notInFlight) is Action.Fetch)
        assertEquals("s2", i.newChatSession)
        // …and nothing else is accepted on that page afterwards.
        assertEquals(
            Action.Ignored(TurnIntake.IgnoreReason.ROUTING),
            i.onToken(event("run_x", "s3", page = "/agent"), null, notInFlight),
        )
        // A fresh visit to the new-chat page starts a new adoption.
        i.onNavigate("/agent")
        assertEquals(null, i.newChatSession)
    }

    @Test
    fun missingPagePathFallsBackToTheNativePath() {
        val i = intake()
        // The native fallback path drives routing (here: a conversation page, so
        // the stream is accepted — and attributed to its own session id).
        val a = i.onToken(event("run_a", "s1", page = null), "/agent/s2", notInFlight)
        assertTrue(a is Action.Fetch)
        assertEquals("s1", (a as Action.Fetch).sessionId)
        // A non-conversation fallback page still rejects.
        assertEquals(
            Action.Ignored(TurnIntake.IgnoreReason.ROUTING),
            i.onToken(event("run_b", "s3", page = null), "/leaderboard", notInFlight),
        )
    }

    @Test
    fun malformedTokenIsIgnored() {
        val i = intake()
        assertEquals(
            Action.Ignored(TurnIntake.IgnoreReason.TOKEN),
            i.onToken(SnoopEvent("s1", "not.a.valid-token-at-all", "/agent/s1"), null, notInFlight),
        )
    }

    // ---- run identity ----

    @Test
    fun repeatedAndInterleavedTokensMapToTheirOwnTurns() {
        val i = intake()
        val a = i.onToken(event("run_a", "s1"), null, notInFlight) as Action.Fetch
        val b = i.onToken(event("run_b", "s1"), null, notInFlight) as Action.Fetch
        assertEquals(1, a.turn.number)
        assertEquals(2, b.turn.number)
        // run_a again while its fetch is running → known, no third turn.
        val again = i.onToken(event("run_a", "s1"), null) { it == a.turn.key }
        assertTrue(again is Action.Known)
        assertEquals(2, i.turns.turns("s1").size)
    }

    @Test
    fun resolvedTurnIsNotFetchedAgain() {
        val i = intake()
        val a = i.onToken(event("run_a", "s1"), null, notInFlight) as Action.Fetch
        i.onTraceResult("s1", a.turn.key, listOf("model-a"), "")
        assertTrue(i.onToken(event("run_a", "s1"), null, notInFlight) is Action.Known)
    }

    @Test
    fun switchingAwayAndBackKeepsEachLogIntact() {
        val i = intake()
        val a1 = i.onToken(event("run_a1", "s1"), null, notInFlight) as Action.Fetch
        i.onTraceResult("s1", a1.turn.key, listOf("model-a"), "")
        val b1 = i.onToken(event("run_b1", "s2"), null, notInFlight) as Action.Fetch
        i.onTraceResult("s2", b1.turn.key, listOf("model-x"), "")

        // Reopening s1 replays run_a1 (known) then a new turn arrives.
        assertTrue(i.onToken(event("run_a1", "s1"), null, notInFlight) is Action.Known)
        val a2 = i.onToken(event("run_a2", "s1"), null, notInFlight) as Action.Fetch
        assertEquals(2, a2.turn.number)
        i.onTraceResult("s1", a2.turn.key, listOf("model-b"), "")

        assertEquals(listOf("model-a", "model-b"), i.view("s1").turns.map { it.model })
        assertEquals(listOf("model-x"), i.view("s2").turns.map { it.model })
        assertTrue(i.view("s1").routed)
        assertFalse(i.view("s2").routed)
    }

    // ---- expiry / retries ----

    @Test
    fun expiredReplayTokenRegistersTheTurnWithoutFetching() {
        val history = FakeHistory()
        val i = intake(history)
        val action = i.onToken(event("run_old", "s1", exp = now - 10), null, notInFlight)
        assertTrue(action is Action.Updated)
        val turn = (action as Action.Updated).turn
        assertEquals(Status.FAILED, turn.status)
        assertEquals(TurnIntake.NOTE_EXPIRED, turn.note)
        // Numbering is persisted even without a model.
        assertEquals(listOf(1), HistoryLogic.runsFor(history.json, "s1").map { it.number })
        // Seeing it again changes nothing.
        assertTrue(i.onToken(event("run_old", "s1", exp = now - 10), null, notInFlight) is Action.Known)
    }

    @Test
    fun failedTurnIsRetriedOnlyAfterCooldown() {
        var clock = now
        val i = TurnIntake(FakeHistory(), nowSeconds = { clock })
        val a = i.onToken(event("run_a", "s1"), null, notInFlight) as Action.Fetch
        i.onTraceResult("s1", a.turn.key, emptyList(), "trace 返回 HTTP 500")
        assertTrue(i.onToken(event("run_a", "s1"), null, notInFlight) is Action.Known)
        clock += TurnIntake.RETRY_COOLDOWN_SECONDS
        assertTrue(i.onToken(event("run_a", "s1"), null, notInFlight) is Action.Fetch)
    }

    // ---- persistence ----

    @Test
    fun historyAnswersReplayedTurnsAfterRestart() {
        val history = FakeHistory()
        val first = intake(history)
        val a = first.onToken(event("run_a", "s1"), null, notInFlight) as Action.Fetch
        first.onTraceResult("s1", a.turn.key, listOf("model-a"), "")

        // "Restart": a fresh intake over the same storage.
        val second = intake(history)
        assertTrue(second.onToken(event("run_a", "s1"), null, notInFlight) is Action.Known)
        val b = second.onToken(event("run_b", "s1"), null, notInFlight) as Action.Fetch
        assertEquals(2, b.turn.number)
        assertEquals(listOf("model-a"), second.modelsFor("s1"))
    }

    @Test
    fun rawRunIdsAndTokensAreNeverPersisted() {
        val history = FakeHistory()
        val i = intake(history)
        val a = i.onToken(event("run_secret123", "s1"), null, notInFlight) as Action.Fetch
        i.onTraceResult("s1", a.turn.key, listOf("model-a"), "")
        assertFalse(history.json.contains("run_secret123"))
        assertFalse(history.json.contains("eyJ"))
    }

    // ---- view ----

    @Test
    fun viewMarksHistoryOnlyModelsAsRestored() {
        val history = FakeHistory()
        history.json = HistoryLogic.mergeRecord("{}", "s9", listOf("legacy-model"), nowMs = 1)
        val i = intake(history)
        val v = i.view("s9")
        assertEquals(listOf("legacy-model"), v.currentModels)
        assertTrue(v.restored)
        assertTrue(v.turns.isEmpty())
    }

    @Test
    fun viewOfLiveConversationIsNotRestored() {
        val i = intake()
        val a = i.onToken(event("run_a", "s1"), null, notInFlight) as Action.Fetch
        i.onTraceResult("s1", a.turn.key, listOf("model-a"), "")
        val v = i.view("s1")
        assertFalse(v.restored)
        assertEquals("model-a", v.currentModel)
        assertEquals(1, v.latestTurn?.number)
    }

    @Test
    fun viewOfInvalidSessionIsEmpty() {
        val v = intake().view("")
        assertTrue(v.turns.isEmpty())
        assertTrue(v.currentModels.isEmpty())
    }

    // ---- page id ≠ stream id (aliases) ----

    /** Arena's /c/{evalId} pages run under a stream session with a different id. */
    @Test
    fun pageConversationIdAliasesToItsStreamSession() {
        val i = intake()
        val a = i.onToken(event("run_a", "stream-s", page = "/c/eval-123"), null, notInFlight) as Action.Fetch
        i.onTraceResult("stream-s", a.turn.key, listOf("model-a"), "")
        // The panel asks for the PAGE id; the turns live under the stream id.
        assertEquals("stream-s", i.conversationFor("eval-123"))
        assertEquals(listOf("model-a"), i.view("eval-123").currentModels)
        assertEquals(listOf("model-a"), i.modelsFor("eval-123"))
        assertEquals("model-a", i.view("eval-123").turns.single().model)
        // Turn 2 on the same page keeps the alias.
        val b = i.onToken(event("run_b", "stream-s", page = "/c/eval-123"), null, notInFlight) as Action.Fetch
        assertEquals(2, b.turn.number)
        assertEquals(2, i.view("eval-123").turns.size)
    }

    @Test
    fun replayedKnownRunsNeverClaimAPage() {
        // s1 has reached turn 1 while being created on /agent. The user switches
        // to /c/eval-123 and s1's closing stream replays its (resolved) run —
        // that must NOT alias the new page to s1.
        val i = intake()
        val a = i.onToken(event("run_a", "s1", page = "/agent"), null, notInFlight) as Action.Fetch
        i.onTraceResult("s1", a.turn.key, listOf("model-a"), "")
        val replay = i.onToken(event("run_a", "s1", page = "/c/eval-123"), null, notInFlight)
        assertTrue(replay is Action.Known)
        assertEquals("eval-123", i.conversationFor("eval-123"))
        assertTrue(i.view("eval-123").turns.isEmpty())
        // The new conversation's own first NEW run may claim it.
        val own = i.onToken(event("run_x", "stream-x", page = "/c/eval-123"), null, notInFlight) as Action.Fetch
        assertEquals(1, own.turn.number)
        assertEquals("stream-x", i.conversationFor("eval-123"))
    }
}
