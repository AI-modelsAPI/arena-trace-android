package com.ati.arena.session

/**
 * Per-conversation turn log, keyed by run.
 *
 * Arena runs every turn as its own Trigger.dev run, and the session stream carries
 * that run's public token — usually on MANY records, again when a conversation's
 * stream is reopened (replay), and interleaved with other runs. The previous
 * design treated "a token different from the last one" as a new turn and kept a
 * single global log, so switching chats or an A→B→A token sequence produced
 * phantom turns and showed one chat's models in another chat's log.
 *
 * Rules enforced here:
 *  - one run = one turn: re-seeing a run (same key) never creates a new turn;
 *  - turns are numbered per conversation in first-seen order and never renumbered;
 *  - every conversation has its own log, so a switch can't mix entries;
 *  - logs can be seeded from persisted history so numbering and models survive
 *    app restarts and chat switches.
 *
 * Keys are opaque run keys (see HistoryLogic.runKey) — never raw tokens.
 * Not thread-safe: callers use it from the main thread only. Pure Kotlin.
 */
class TurnTracker(
    private val maxSessions: Int = DEFAULT_MAX_SESSIONS,
    private val maxTurnsPerSession: Int = DEFAULT_MAX_TURNS,
) {

    enum class Status { PENDING, RESOLVED, FAILED }

    data class Turn(
        val number: Int,
        val key: String,
        val status: Status,
        val models: List<String> = emptyList(),
        val note: String = "",
        /** Optional strength/effort tier from the trace ("high", "max", …). */
        val strength: String = "",
    ) {
        /** Primary model label ("" until resolved). */
        val model: String get() = models.firstOrNull().orEmpty()
    }

    data class Registration(val turn: Turn, val isNew: Boolean)

    /** A persisted turn used to seed a conversation's log. */
    data class Seed(val key: String, val number: Int, val models: List<String>, val strength: String = "")

    private class Log {
        var nextNumber = 1
        val byKey = LinkedHashMap<String, Turn>()
    }

    // Access-ordered: the least recently used conversation is evicted first.
    private val logs = LinkedHashMap<String, Log>(16, 0.75f, true)
    private val seeded = HashSet<String>()

    fun isSeeded(sessionId: String): Boolean = sessionId in seeded

    /**
     * Merge persisted turns into [sessionId]'s log (idempotent). Unknown keys are
     * inserted with their stored numbers; a pending/failed in-memory turn is
     * upgraded when the seed carries models. Numbers already taken are kept.
     */
    fun seed(sessionId: String, seeds: List<Seed>) {
        if (sessionId.isEmpty()) return
        seeded.add(sessionId)
        if (seeds.isEmpty()) return
        val log = logFor(sessionId)
        val taken = log.byKey.values.mapTo(HashSet()) { it.number }
        for (s in seeds.sortedBy { it.number }) {
            if (s.key.isEmpty() || s.number <= 0) continue
            val existing = log.byKey[s.key]
            if (existing == null) {
                if (s.number in taken) continue
                log.byKey[s.key] = if (s.models.isNotEmpty()) {
                    Turn(s.number, s.key, Status.RESOLVED, s.models, strength = s.strength)
                } else {
                    Turn(s.number, s.key, Status.FAILED, note = NOTE_UNKNOWN)
                }
                taken.add(s.number)
            } else if (existing.status != Status.RESOLVED && s.models.isNotEmpty()) {
                log.byKey[s.key] = existing.copy(
                    status = Status.RESOLVED,
                    models = s.models,
                    note = "",
                    strength = s.strength.ifEmpty { existing.strength },
                )
            }
            if (s.number >= log.nextNumber) log.nextNumber = s.number + 1
        }
        trim(log)
    }

    /** A run was observed in [sessionId]'s stream. Returns its turn; [Registration.isNew] on first sight. */
    fun onRun(sessionId: String, key: String): Registration {
        val log = logFor(sessionId)
        log.byKey[key]?.let { return Registration(it, false) }
        val turn = Turn(log.nextNumber++, key, Status.PENDING)
        log.byKey[key] = turn
        trim(log)
        return Registration(turn, true)
    }

    /**
     * Record the models resolved for a run (and optionally its strength tier).
     * A re-resolve without a new tier keeps the previously stored one.
     */
    fun resolve(sessionId: String, key: String, models: List<String>, strength: String = ""): Turn? {
        val log = logs[sessionId] ?: return null
        val current = log.byKey[key] ?: return null
        val clean = models.map { it.trim() }.filter { it.isNotEmpty() }.distinct()
        if (clean.isEmpty()) return fail(sessionId, key, NOTE_NO_MODEL)
        val tier = strength.trim().take(MAX_STRENGTH_LENGTH).ifEmpty { current.strength }
        val updated = current.copy(status = Status.RESOLVED, models = clean, note = "", strength = tier)
        log.byKey[key] = updated
        return updated
    }

    /** Mark a run as not resolvable (a resolved turn is never downgraded). */
    fun fail(sessionId: String, key: String, note: String): Turn? {
        val log = logs[sessionId] ?: return null
        val current = log.byKey[key] ?: return null
        if (current.status == Status.RESOLVED) return current
        val updated = current.copy(status = Status.FAILED, note = note)
        log.byKey[key] = updated
        return updated
    }

    fun turn(sessionId: String, key: String): Turn? = logs[sessionId]?.byKey?.get(key)

    /** All known turns of a conversation, oldest first. */
    fun turns(sessionId: String): List<Turn> =
        logs[sessionId]?.byKey?.values?.sortedBy { it.number } ?: emptyList()

    /** The highest-numbered resolved turn (the conversation's current model). */
    fun latestResolved(sessionId: String): Turn? =
        logs[sessionId]?.byKey?.values?.filter { it.status == Status.RESOLVED }?.maxByOrNull { it.number }

    /** Primary model of the conversation's earliest resolved turn ("" if none). */
    fun firstModel(sessionId: String): String =
        logs[sessionId]?.byKey?.values?.filter { it.status == Status.RESOLVED }
            ?.minByOrNull { it.number }?.model.orEmpty()

    /** Models of the latest resolved turn, or empty. */
    fun currentModels(sessionId: String): List<String> = latestResolved(sessionId)?.models ?: emptyList()

    /** true when the latest resolved turn's model differs from the conversation's first. */
    fun isRouted(sessionId: String): Boolean {
        val first = firstModel(sessionId)
        val latest = latestResolved(sessionId)?.model.orEmpty()
        return first.isNotEmpty() && latest.isNotEmpty() && latest != first
    }

    fun forget(sessionId: String) {
        logs.remove(sessionId)
        seeded.remove(sessionId)
    }

    fun clear() {
        logs.clear()
        seeded.clear()
    }

    private fun logFor(sessionId: String): Log {
        logs[sessionId]?.let { return it }
        val log = Log()
        logs[sessionId] = log
        while (logs.size > maxSessions) {
            val eldest = logs.keys.first()
            logs.remove(eldest)
            seeded.remove(eldest)
        }
        return log
    }

    private fun trim(log: Log) {
        if (log.byKey.size <= maxTurnsPerSession) return
        val drop = log.byKey.values.sortedBy { it.number }.take(log.byKey.size - maxTurnsPerSession)
        drop.forEach { log.byKey.remove(it.key) }
    }

    companion object {
        const val DEFAULT_MAX_SESSIONS = 32
        const val DEFAULT_MAX_TURNS = 200
        const val NOTE_UNKNOWN = "未识别"
        const val NOTE_NO_MODEL = "trace 未返回模型名称"
        const val MAX_STRENGTH_LENGTH = 16
    }
}
