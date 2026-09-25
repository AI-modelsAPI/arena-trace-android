package com.ati.arena.protocol

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

/**
 * Protocol-parity tests for the token → trace pipeline, mirroring the
 * extension's tests/core.test.mjs. Pure JVM: no Android framework, no network.
 */
class ArenaProtocolTest {

    private val far = 9_999_999_999L
    // now is fixed so tests never depend on wall-clock.
    private val now = 1_000_000_000L

    /** Build an unsigned JWT-shaped token whose payload is the given claims JSON. */
    private fun jwt(claims: String): String {
        val payload = Base64.getUrlEncoder().withoutPadding()
            .encodeToString(claims.toByteArray(Charsets.UTF_8))
        return "eyJhbGciOiJIUzI1NiJ9.$payload.test"
    }

    private fun validate(token: String, sessionId: String) =
        ArenaProtocol.validateToken(token, sessionId, now)

    // ---- validateToken ----

    @Test fun validTokenIsConstrainedToSessionAndOneRun() {
        val claims = """{"pub":true,"iss":"https://id.trigger.dev","aud":"https://api.trigger.dev","exp":$far,"scopes":["read:runs:run_abc123","read:sessions:session-123","write:sessions:session-123"]}"""
        assertEquals("run_abc123", validate(jwt(claims), "session-123")?.runId)
    }

    @Test fun rejectsTokenForAnotherSession() {
        val claims = """{"pub":true,"iss":"https://id.trigger.dev","aud":"https://api.trigger.dev","exp":$far,"scopes":["read:runs:run_abc123","read:sessions:session-123"]}"""
        assertNull(validate(jwt(claims), "another-session"))
    }

    @Test fun rejectsExpiredToken() {
        val claims = """{"pub":true,"iss":"https://id.trigger.dev","aud":"https://api.trigger.dev","exp":1,"scopes":["read:runs:run_abc123"]}"""
        assertNull(validate(jwt(claims), "session-123"))
    }

    @Test fun rejectsScalarAudMismatch() {
        // Bug #1: scalar aud must be validated, not skipped.
        val claims = """{"pub":true,"iss":"https://id.trigger.dev","aud":"https://evil.test","exp":$far,"scopes":["read:runs:run_abc123"]}"""
        assertNull(validate(jwt(claims), "session-123"))
    }

    @Test fun acceptsScalarAudMatch() {
        val claims = """{"pub":true,"iss":"https://id.trigger.dev","aud":"https://api.trigger.dev","exp":$far,"scopes":["read:runs:run_abc123"]}"""
        assertEquals("run_abc123", validate(jwt(claims), "session-123")?.runId)
    }

    @Test fun rejectsNonPublicToken() {
        val claims = """{"pub":false,"iss":"https://id.trigger.dev","aud":"https://api.trigger.dev","exp":$far,"scopes":["read:runs:run_abc123"]}"""
        assertNull(validate(jwt(claims), "session-123"))
    }

    @Test fun rejectsTwoDistinctRunScopes() {
        val claims = """{"pub":true,"iss":"https://id.trigger.dev","aud":"https://api.trigger.dev","exp":$far,"scopes":["read:runs:run_abc123","read:runs:run_other","read:sessions:session-123"]}"""
        assertNull(validate(jwt(claims), "session-123"))
    }

    @Test fun rejectsBrokenToken() {
        assertNull(validate("broken", "session-123"))
    }

    @Test fun acceptsModernRunClaim() {
        // Bug #2: modern token uses a scalar `run` claim and no scopes array.
        val claims = """{"pub":true,"iss":"https://id.trigger.dev","aud":"https://api.trigger.dev","exp":$far,"run":"run_abc123"}"""
        assertEquals("run_abc123", validate(jwt(claims), "session-123")?.runId)
    }

    @Test fun acceptsModernRunClaimWithoutAud() {
        val claims = """{"pub":true,"iss":"https://id.trigger.dev","exp":$far,"run":"run_abc123"}"""
        assertEquals("run_abc123", validate(jwt(claims), "session-123")?.runId)
    }

    @Test fun rejectsMalformedRunId() {
        // Bug #3: run scope id must match run_[A-Za-z0-9]+.
        val claims = """{"pub":true,"iss":"https://id.trigger.dev","aud":"https://api.trigger.dev","exp":$far,"scopes":["read:runs:../secret"]}"""
        assertNull(validate(jwt(claims), "session-123"))
    }

    @Test fun rejectsMalformedRunClaim() {
        val claims = """{"pub":true,"iss":"https://id.trigger.dev","aud":"https://api.trigger.dev","exp":$far,"run":"not-a-run"}"""
        assertNull(validate(jwt(claims), "session-123"))
    }

    // ---- extractModels ----

    private fun trace(json: String) = JSONObject(json)

    private val streamEvent = """{"runId":"run_abc123","message":"ai.streamText.doStream","spanId":"span1","style":{"icon":"ai-provider-xai","accessory":{"items":[{"text":"grok-4.6","icon":"tabler-cube"},{"text":"$0.0133","icon":"tabler-currency-dollar"}]}}}"""

    @Test fun extractsExactModelSpanAndDeduplicates() {
        val other = streamEvent.replace("run_abc123", "run_other")
        val title = streamEvent.replace("ai.streamText.doStream", "chat title")
        val models = ArenaProtocol.extractModels(
            trace("""{"events":[$streamEvent,$streamEvent,$other,$title]}"""), "run_abc123")
        assertEquals(listOf("grok-4.6"), models)
    }

    @Test fun emptyEventsYieldNoModels() {
        assertEquals(emptyList<String>(), ArenaProtocol.extractModels(trace("""{"events":[]}"""), "run_abc123"))
    }

    @Test fun malformedTraceYieldsNoModels() {
        // Extension throws; the Android port returns empty so the poll simply retries.
        assertEquals(emptyList<String>(), ArenaProtocol.extractModels(trace("""{}"""), "run_abc123"))
    }

    // A same-run non-model span carrying a cube item; the main loop must ignore
    // it, so it only surfaces if extraction wrongly falls back to any span.
    private val decoySpan = """{"runId":"run_abc123","message":"custom.op","spanId":"decoy","style":{"accessory":{"items":[{"text":"decoy-model","icon":"tabler-cube"}]}}}"""

    @Test fun extractsGenerateSpanVariant() {
        // Bug #5: generateText.doGenerate is a real model span, matched by the
        // main loop — proven by the decoy span NOT appearing (fallback would add it).
        val generate = """{"runId":"run_abc123","message":"ai.generateText.doGenerate","spanId":"span2","style":{"icon":"ai-provider-openai","accessory":{"items":[{"text":"gpt-6","icon":"tabler-cube"}]}}}"""
        assertEquals(listOf("gpt-6"), ArenaProtocol.extractModels(trace("""{"events":[$generate,$decoySpan]}"""), "run_abc123"))
    }

    @Test fun extractsFromDataEventsEnvelope() {
        // Bug #4: trace may be wrapped in {data:{events:[...]}}.
        assertEquals(listOf("grok-4.6"), ArenaProtocol.extractModels(trace("""{"data":{"events":[$streamEvent]}}"""), "run_abc123"))
    }

    @Test fun extractsFromSpansEnvelope() {
        assertEquals(listOf("grok-4.6"), ArenaProtocol.extractModels(trace("""{"spans":[$streamEvent]}"""), "run_abc123"))
    }

    @Test fun acceptsAlternateCubeIcons() {
        // Bug #6: cube, tabler-box also count.
        val box = streamEvent.replace("tabler-cube", "tabler-box")
        assertEquals(listOf("grok-4.6"), ArenaProtocol.extractModels(trace("""{"events":[$box]}"""), "run_abc123"))
    }

    @Test fun fallsBackToAnySpanUnderRun() {
        // Bug #7: when no model span matches, scan any span for the same run.
        val misc = """{"runId":"run_abc123","message":"custom.op","spanId":"span9","style":{"accessory":{"items":[{"text":"claude-opus-5","icon":"cube"}]}}}"""
        assertEquals(listOf("claude-opus-5"), ArenaProtocol.extractModels(trace("""{"events":[$misc]}"""), "run_abc123"))
    }

    @Test fun ignoresCostAndUnrelatedRuns() {
        val other = streamEvent.replace("run_abc123", "run_other")
        val models = ArenaProtocol.extractModels(trace("""{"events":[$other]}"""), "run_abc123")
        assertEquals(emptyList<String>(), models)
    }

    // ---- fatal status ----

    @Test fun fatalTraceStatuses() {
        assertTrue(ArenaProtocol.isFatalTraceStatus(401))
        assertTrue(ArenaProtocol.isFatalTraceStatus(403))
        assertTrue(ArenaProtocol.isFatalTraceStatus(429))
        assertEquals(false, ArenaProtocol.isFatalTraceStatus(404))
        assertEquals(false, ArenaProtocol.isFatalTraceStatus(500))
    }
    // ---- inspectToken / isExpired (identify replayed turns without using stale tokens) ----

    @Test fun inspectAcceptsExpiredTokenButValidateRejectsIt() {
        val t = jwt("""{"pub":true,"iss":"https://id.trigger.dev","exp":${now - 60},"scopes":["read:runs:run_old"]}""")
        assertNull(validate(t, "s1"))
        val claims = ArenaProtocol.inspectToken(t, "s1")
        assertEquals("run_old", claims?.runId)
        assertTrue(ArenaProtocol.isExpired(claims!!, now))
    }

    @Test fun inspectStillEnforcesStructure() {
        assertNull(ArenaProtocol.inspectToken(jwt("""{"pub":false,"iss":"https://id.trigger.dev","exp":$far,"run":"run_a"}"""), "s1"))
        assertNull(ArenaProtocol.inspectToken(jwt("""{"pub":true,"iss":"https://id.trigger.dev","exp":$far,"run":"run_a","scopes":["read:sessions:other"]}"""), "s1"))
        assertNull(ArenaProtocol.inspectToken("a.b", "s1"))
        assertNull(ArenaProtocol.inspectToken("x".repeat(ArenaProtocol.MAX_TOKEN_LENGTH + 1), "s1"))
    }

    @Test fun expiryUsesSkew() {
        val c = ArenaProtocol.Claims("run_a", now + ArenaProtocol.EXPIRY_SKEW_SECONDS)
        assertTrue(ArenaProtocol.isExpired(c, now))
        assertEquals(false, ArenaProtocol.isExpired(c.copy(exp = now + ArenaProtocol.EXPIRY_SKEW_SECONDS + 1), now))
    }

    @Test fun runIdValidation() {
        assertTrue(ArenaProtocol.isValidRunId("run_abc123"))
        assertEquals(false, ArenaProtocol.isValidRunId("run_"))
        assertEquals(false, ArenaProtocol.isValidRunId("abc"))
        assertEquals(false, ArenaProtocol.isValidRunId(null))
    }
}
