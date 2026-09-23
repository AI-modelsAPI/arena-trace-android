package com.ati.arena.store

import android.content.Context
import android.content.SharedPreferences

/**
 * Thin SharedPreferences wrapper. All merge/validation logic lives in the
 * framework-free [HistoryLogic] so it can be unit tested; this class only does
 * the Android read/write. Whitelist only: session→model history and the probe
 * panel form values. Never persists tokens, cookies, trace data, or chat text.
 */
class Store(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences("arena_trace", Context.MODE_PRIVATE)

    // ---- session -> resolved model(s) history ----

    /** Record the resolved model(s) for a conversation (no-op for bad/empty input). */
    fun saveModels(sessionId: String, models: List<String>) {
        val merged = HistoryLogic.mergeRecord(prefs.getString(KEY_HISTORY, "{}"), sessionId, models)
        prefs.edit().putString(KEY_HISTORY, merged).apply()
    }

    /** Previously resolved model(s) for a conversation, or empty when unknown. */
    fun modelsFor(sessionId: String): List<String> =
        HistoryLogic.modelsFor(prefs.getString(KEY_HISTORY, "{}"), sessionId)

    // ---- probe panel preferences ----

    data class PanelPrefs(
        val targets: String,
        val maxRounds: Int,
        val findAll: Boolean,
        val autoRename: Boolean,
        val quickText: String,
    )

    fun loadPanelPrefs(): PanelPrefs = PanelPrefs(
        targets = prefs.getString(KEY_TARGETS, "opus5, fable5, gpt6") ?: "opus5, fable5, gpt6",
        maxRounds = prefs.getInt(KEY_ROUNDS, 5),
        findAll = prefs.getBoolean(KEY_FIND_ALL, true),
        autoRename = prefs.getBoolean(KEY_RENAME, true),
        quickText = prefs.getString(KEY_QUICK_TEXT, "") ?: "",
    )

    fun savePanelPrefs(p: PanelPrefs) {
        prefs.edit()
            .putString(KEY_TARGETS, p.targets)
            .putInt(KEY_ROUNDS, p.maxRounds.coerceIn(1, 100))
            .putBoolean(KEY_FIND_ALL, p.findAll)
            .putBoolean(KEY_RENAME, p.autoRename)
            .putString(KEY_QUICK_TEXT, p.quickText)
            .apply()
    }

    private companion object {
        const val KEY_HISTORY = "session_models"
        const val KEY_TARGETS = "probe_targets"
        const val KEY_ROUNDS = "probe_rounds"
        const val KEY_FIND_ALL = "probe_find_all"
        const val KEY_RENAME = "probe_rename"
        const val KEY_QUICK_TEXT = "quick_send_text"
    }
}
