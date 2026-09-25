package com.ati.arena.store

import android.content.Context
import android.content.SharedPreferences
import com.ati.arena.probe.ProbeLogic
import com.ati.arena.session.TurnIntake
import org.json.JSONObject

/**
 * Thin SharedPreferences wrapper. All merge/validation logic lives in the
 * framework-free [HistoryLogic] / [ProbeLogic] so it can be unit tested; this
 * class only does the Android read/write.
 *
 * Whitelist only: conversation → model history (with opaque per-turn keys), the
 * probe panel form values and the hit-title suffix counters. Never persists
 * tokens, raw run ids, cookies, trace data, or chat text.
 *
 * Intended for the main thread; history access is synchronized so a stray
 * off-main call can't corrupt the in-memory cache.
 */
class Store(context: Context) : TurnIntake.History {

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    // Parsed once, then kept in memory; every change is written back with apply().
    private var historyCache: JSONObject? = null

    private fun history(): JSONObject =
        historyCache ?: HistoryLogic.parseRoot(prefs.getString(KEY_HISTORY, "{}")).also { historyCache = it }

    private fun commitHistory(root: JSONObject) {
        prefs.edit().putString(KEY_HISTORY, root.toString()).apply()
    }

    // ---- conversation → resolved model(s) history ----

    /** Record the current model(s) for a conversation (no-op for bad/empty/unchanged input). */
    @Synchronized
    fun saveModels(sessionId: String, models: List<String>) {
        val root = history()
        if (HistoryLogic.putModels(root, sessionId, models)) commitHistory(root)
    }

    /** Record one observed turn (models may be empty until it resolves). */
    @Synchronized
    override fun saveRun(sessionId: String, key: String, number: Int, models: List<String>) {
        val root = history()
        if (HistoryLogic.putRun(root, sessionId, key, number, models)) commitHistory(root)
    }

    @Synchronized
    override fun runsFor(sessionId: String): List<HistoryLogic.RunRecord> = HistoryLogic.runsIn(history(), sessionId)

    /** Previously resolved model(s) for a conversation, or empty when unknown. */
    @Synchronized
    override fun modelsFor(sessionId: String): List<String> = HistoryLogic.modelsIn(history(), sessionId)

    // ---- hit-title suffix counters (per prefix + model) ----

    fun loadSuffixCounters(): Map<String, Int> =
        ProbeLogic.countersFromJson(prefs.getString(KEY_SUFFIX_COUNTERS, null))

    fun saveSuffixCounters(counters: Map<String, Int>) {
        prefs.edit().putString(KEY_SUFFIX_COUNTERS, ProbeLogic.countersToJson(counters)).apply()
    }

    // ---- probe panel preferences ----

    data class PanelPrefs(
        val targets: String = ProbeLogic.DEFAULT_TARGETS_TEXT,
        val maxRounds: Int = ProbeLogic.DEFAULT_ROUNDS,
        val findAll: Boolean = true,
        val autoRename: Boolean = true,
        /** Prefix for hit titles, e.g. "[探针] " → "[探针] claude-opus-5-001". */
        val renamePrefix: String = "",
        val quickText: String = "",
    )

    fun loadPanelPrefs(): PanelPrefs {
        val defaults = PanelPrefs()
        return PanelPrefs(
            targets = prefs.getString(KEY_TARGETS, defaults.targets) ?: defaults.targets,
            maxRounds = prefs.getInt(KEY_ROUNDS, defaults.maxRounds)
                .coerceIn(ProbeLogic.MIN_ROUNDS, ProbeLogic.MAX_ROUNDS),
            findAll = prefs.getBoolean(KEY_FIND_ALL, defaults.findAll),
            autoRename = prefs.getBoolean(KEY_RENAME, defaults.autoRename),
            renamePrefix = ProbeLogic.sanitizePrefix(prefs.getString(KEY_RENAME_PREFIX, defaults.renamePrefix)),
            quickText = prefs.getString(KEY_QUICK_TEXT, defaults.quickText) ?: defaults.quickText,
        )
    }

    fun savePanelPrefs(p: PanelPrefs) {
        prefs.edit()
            .putString(KEY_TARGETS, p.targets)
            .putInt(KEY_ROUNDS, p.maxRounds.coerceIn(ProbeLogic.MIN_ROUNDS, ProbeLogic.MAX_ROUNDS))
            .putBoolean(KEY_FIND_ALL, p.findAll)
            .putBoolean(KEY_RENAME, p.autoRename)
            .putString(KEY_RENAME_PREFIX, ProbeLogic.sanitizePrefix(p.renamePrefix))
            .putString(KEY_QUICK_TEXT, p.quickText)
            .apply()
    }

    // ---- overlay UI state ----

    /**
     * Where the floating pill sits (side + vertical fraction), whether it shows its
     * refresh button, and the last panel tab.
     */
    data class UiPrefs(
        val pillOnRight: Boolean = true,
        val pillY: Float = DEFAULT_PILL_Y,
        val lastTab: Int = 0,
        val pillRefresh: Boolean = true,
    )

    fun loadUiPrefs(): UiPrefs = runCatching {
        UiPrefs(
            pillOnRight = prefs.getBoolean(KEY_PILL_RIGHT, true),
            pillY = prefs.getFloat(KEY_PILL_Y, DEFAULT_PILL_Y).let { if (it.isNaN()) DEFAULT_PILL_Y else it.coerceIn(0f, 1f) },
            lastTab = prefs.getInt(KEY_LAST_TAB, 0).coerceAtLeast(0),
            pillRefresh = prefs.getBoolean(KEY_PILL_REFRESH, true),
        )
    }.getOrDefault(UiPrefs())

    fun saveUiPrefs(p: UiPrefs) {
        prefs.edit()
            .putBoolean(KEY_PILL_RIGHT, p.pillOnRight)
            .putFloat(KEY_PILL_Y, p.pillY.coerceIn(0f, 1f))
            .putInt(KEY_LAST_TAB, p.lastTab.coerceAtLeast(0))
            .putBoolean(KEY_PILL_REFRESH, p.pillRefresh)
            .apply()
    }

    private companion object {
        const val DEFAULT_PILL_Y = 0.18f
        const val KEY_PILL_RIGHT = "ui_pill_right"
        const val KEY_PILL_Y = "ui_pill_y"
        const val KEY_LAST_TAB = "ui_last_tab"
        const val KEY_PILL_REFRESH = "ui_pill_refresh"
        const val PREFS_NAME = "arena_trace"
        const val KEY_HISTORY = "session_models"
        const val KEY_TARGETS = "probe_targets"
        const val KEY_ROUNDS = "probe_rounds"
        const val KEY_FIND_ALL = "probe_find_all"
        const val KEY_RENAME = "probe_rename"
        const val KEY_RENAME_PREFIX = "probe_rename_prefix"
        const val KEY_SUFFIX_COUNTERS = "probe_suffix_counters"
        const val KEY_QUICK_TEXT = "quick_send_text"
    }
}
