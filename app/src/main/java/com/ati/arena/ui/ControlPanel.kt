package com.ati.arena.ui

import android.os.SystemClock
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.Menu
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.PopupMenu
import androidx.core.view.ViewCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.ati.arena.R
import com.ati.arena.probe.ProbeController
import com.ati.arena.probe.ProbeLogic
import com.ati.arena.session.TurnIntake
import com.ati.arena.store.Store
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.button.MaterialButton
import com.google.android.material.button.MaterialButtonToggleGroup
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.progressindicator.LinearProgressIndicator
import com.google.android.material.textfield.TextInputLayout
import java.time.LocalTime

/**
 * The overlay UI: a floating [StatusPillView] plus a bottom-sheet control panel.
 *
 * Holds view state only. Data arrives through the show*()/log()/flash() methods
 * (fed by MainActivity) and every user intent leaves through [Actions]; all text
 * is computed by [HudFormat]. Gestures:
 *  - pill: tap → panel, tap on ⟳ → refresh, long-press → quick actions,
 *    drag → move (snaps to an edge);
 *  - panel: swipe down, tap outside or Back → close.
 *
 * Refresh is one tap from anywhere (pill ⟳, panel header, tools row, quick menu);
 * all of them go through [requestReload]: double taps are swallowed, and a running
 * probe / cleanup asks before it is interrupted.
 */
class ControlPanel(
    private val activity: AppCompatActivity,
    private val store: Store,
    private val actions: Actions,
) {
    interface Actions {
        fun startProbe(config: ProbeController.Config)
        fun startCleanup()
        fun stopTask()
        fun quickSend(text: String)
        fun navigate(nav: Nav)
    }

    enum class Nav { BACK, FORWARD, RELOAD }

    enum class Tab { SESSION, PROBE, TOOLS }

    // ---------------------------------------------------------------- views

    private val root: View = find(R.id.root)
    private val pill: StatusPillView = find(R.id.pill)
    private val scrim: View = find(R.id.scrim)
    private val sheet: View = find(R.id.sheet)
    private val behavior: BottomSheetBehavior<View> = BottomSheetBehavior.from(sheet)
    private val pageProgress: LinearProgressIndicator = find(R.id.page_progress)

    private val hudModel: TextView = find(R.id.hud_model)
    private val hudHeadline: TextView = find(R.id.hud_headline)
    private val hudQuota: TextView = find(R.id.hud_quota)
    private val hudReset: TextView = find(R.id.hud_reset)
    private val quotaBar: LinearProgressIndicator = find(R.id.hud_quota_bar)
    private val activityRow: View = find(R.id.activity_row)
    private val activityLine: TextView = find(R.id.activity_line)
    private val activityChevron: ImageView = find(R.id.activity_chevron)
    private val activityLog: TextView = find(R.id.activity_log)

    private val tabs: MaterialButtonToggleGroup = find(R.id.tabs)
    private val tabButtonIds: Map<Tab, Int> = mapOf(
        Tab.SESSION to R.id.tab_session,
        Tab.PROBE to R.id.tab_probe,
        Tab.TOOLS to R.id.tab_tools,
    )
    private val pages: Map<Tab, View> = mapOf(
        Tab.SESSION to find<View>(R.id.page_session),
        Tab.PROBE to find<View>(R.id.page_probe),
        Tab.TOOLS to find<View>(R.id.page_tools),
    )

    private val turnSummary: TextView = find(R.id.turn_summary)
    private val turnList: RecyclerView = find(R.id.turn_list)
    private val turnEmpty: View = find(R.id.turn_empty)
    private val turnAdapter = TurnAdapter()

    private val targetsLayout: TextInputLayout = find(R.id.probe_targets_layout)
    private val targetsInput: EditText = find(R.id.probe_targets)
    private val roundsInput: EditText = find(R.id.probe_rounds)
    private val findAllSwitch: MaterialSwitch = find(R.id.probe_find_all)
    private val renameSwitch: MaterialSwitch = find(R.id.probe_rename)
    private val prefixLayout: TextInputLayout = find(R.id.probe_prefix_layout)
    private val prefixInput: EditText = find(R.id.probe_prefix)
    private val probeToggle: MaterialButton = find(R.id.probe_toggle)

    private val cleanupToggle: MaterialButton = find(R.id.cleanup_toggle)
    private val quickLayout: TextInputLayout = find(R.id.quick_layout)
    private val quickInput: EditText = find(R.id.quick_text)
    private val quickSendButton: MaterialButton = find(R.id.quick_send)
    private val pillRefreshSwitch: MaterialSwitch = find(R.id.pill_refresh_switch)
    private val autoRefreshSwitch: MaterialSwitch = find(R.id.auto_refresh_switch)

    // ---------------------------------------------------------------- state

    private var session: TurnIntake.SessionView? = null
    private var onNewChat = false
    private var task: TaskState = TaskState.Idle
    private var quotaPercent = Int.MIN_VALUE // forces the first showQuota() to render
    private var flashText: String? = null
    private val clearFlash = Runnable {
        flashText = null
        renderPill()
    }
    private val logLines = ArrayDeque<String>()
    private var logExpanded = false
    private var pillShown = true
    private var linkTabOpen = false
    private var lastReloadAt = 0L
    private var reloadDialog: AlertDialog? = null
    private var recoveryUntil = 0L

    // A running auto refresh also counts as "task running" for the guard dialogs.
    private val busy: Boolean
        get() = task != TaskState.Idle || SystemClock.elapsedRealtime() < recoveryUntil
    private val hideProgress = Runnable {
        pill.refreshing = false
        pageProgress.animate().alpha(0f).setDuration(FADE_MS).withEndAction {
            pageProgress.visibility = View.INVISIBLE
            pageProgress.alpha = 1f
        }.start()
    }
    private var uiPrefs: Store.UiPrefs = store.loadUiPrefs()
    private val drag = FloatingDragHelper(pill, dp(EDGE_MARGIN_DP)) { onRight, y ->
        saveUi(uiPrefs.copy(pillOnRight = onRight, pillY = y))
    }

    init {
        setUpSheet()
        setUpPill()
        setUpTabs()
        setUpProbeForm()
        setUpTools()
        turnList.layoutManager = LinearLayoutManager(activity)
        turnList.adapter = turnAdapter
        activityRow.setOnClickListener { toggleLog() }
        showSession(null, onNewChat = false)
        showQuota(-1, HudFormat.quotaDetail(hasData = false, resetAtMs = 0, nowMs = 0, error = ""))
        renderTaskButtons()
    }

    // ---------------------------------------------------------------- public API

    val isOpen: Boolean get() = behavior.state != BottomSheetBehavior.STATE_HIDDEN

    fun open(tab: Tab? = null) {
        if (linkTabOpen) return // the link tab covers the page; nothing to control there
        tab?.let { selectTab(it) }
        scrim.visibility = View.VISIBLE
        showPill(false)
        behavior.state = BottomSheetBehavior.STATE_EXPANDED
    }

    fun close() {
        if (isOpen) behavior.state = BottomSheetBehavior.STATE_HIDDEN
    }

    /** Back press: closes the panel if it is open. Returns true when consumed. */
    fun handleBack(): Boolean {
        if (!isOpen) return false
        close()
        return true
    }

    /** The link tab covers the page: hide the pill (and the panel) until it closes. */
    fun setLinkTabOpen(open: Boolean) {
        if (linkTabOpen == open) return
        linkTabOpen = open
        if (open) {
            reloadDialog?.dismiss()
            close()
        }
        showPill(!open && !isOpen)
    }

    /**
     * Load progress (0..100) of the Arena page: a thin bar along the top and the pill's
     * ⟳ spinning until it is done. A load that stops reporting for a while is treated
     * as finished, so nothing spins forever.
     */
    fun showPageProgress(progress: Int) {
        pageProgress.removeCallbacks(hideProgress)
        if (progress in 0..99) {
            pill.refreshing = true
            pageProgress.animate().cancel()
            pageProgress.alpha = 1f
            if (pageProgress.visibility != View.VISIBLE) {
                pageProgress.setProgressCompat(0, false)
                pageProgress.visibility = View.VISIBLE
            }
            pageProgress.setProgressCompat(maxOf(progress, MIN_PAGE_PROGRESS), true)
            pageProgress.postDelayed(hideProgress, STALLED_LOAD_MS)
        } else if (pageProgress.visibility == View.VISIBLE) {
            pageProgress.setProgressCompat(100, true)
            pageProgress.postDelayed(hideProgress, PROGRESS_LINGER_MS)
        } else {
            pill.refreshing = false
        }
    }

    fun selectTab(tab: Tab) {
        val id = tabButtonIds.getValue(tab)
        if (tabs.checkedButtonId == id) showPage(tab) else tabs.check(id)
    }

    /** The conversation on screen changed or its turns changed. */
    fun showSession(view: TurnIntake.SessionView?, onNewChat: Boolean) {
        val switched = view?.sessionId != session?.sessionId
        session = view
        this.onNewChat = onNewChat

        val models = view?.currentModels.orEmpty()
        hudModel.text = if (models.isEmpty()) activity.getString(R.string.model_unknown) else models.joinToString(" / ")
        hudModel.setTextColor(
            color(
                when {
                    models.isEmpty() -> R.color.on_surface_muted
                    view?.routed == true -> R.color.warn
                    else -> R.color.on_surface
                },
            ),
        )
        hudHeadline.text = HudFormat.headline(view, onNewChat)

        val rows = HudFormat.turnRows(view?.turns.orEmpty(), view?.firstModel.orEmpty())
        if (switched) turnAdapter.submitList(null) // no cross-conversation diff animation
        turnAdapter.submitList(rows)
        val summary = HudFormat.turnSummary(view)
        turnSummary.text = summary
        turnSummary.visibility = if (summary.isEmpty()) View.GONE else View.VISIBLE
        turnList.visibility = if (rows.isEmpty()) View.GONE else View.VISIBLE
        turnEmpty.visibility = if (rows.isEmpty()) View.VISIBLE else View.GONE
        renderPill()
    }

    /** Quota figure + detail line (called every second by the pulse loop). */
    fun showQuota(percent: Int, detail: String) {
        val p = if (percent in 0..100) percent else -1
        setTextIfChanged(hudQuota, HudFormat.quotaText(p))
        setTextIfChanged(hudReset, detail)
        if (p == quotaPercent) return
        quotaPercent = p
        val level = HudFormat.quotaLevel(p)
        hudQuota.setTextColor(color(levelTextColor(level)))
        quotaBar.setIndicatorColor(color(levelColor(level)))
        quotaBar.setProgressCompat(maxOf(p, 0), true)
        renderPill()
    }

    /** A task is running; called again with fresh progress. */
    fun showTask(state: TaskState) {
        task = state
        if (state != TaskState.Idle) {
            flashText = null
            pill.removeCallbacks(clearFlash)
        }
        renderTaskButtons()
        renderPill()
    }

    /** A task ended; [last] carries its final numbers for the summary flash. */
    fun finishTask(last: TaskState) {
        task = TaskState.Idle
        renderTaskButtons()
        val summary = HudFormat.finishedFlash(last)
        if (summary != null) flash(summary, FINISH_FLASH_MS) else renderPill()
    }

    /** Briefly show [text] on the pill (e.g. "已发送 ✓"). */
    fun flash(text: String, durationMs: Long = FLASH_MS) {
        flashText = text
        pill.removeCallbacks(clearFlash)
        pill.postDelayed(clearFlash, durationMs)
        renderPill()
    }

    /** Append to the activity log; the newest line is always shown in the header. */
    fun log(message: String) {
        val text = message.trim()
        if (text.isEmpty()) return
        logLines.addLast(HudFormat.logEntry(text, LocalTime.now()))
        while (logLines.size > MAX_LOG_LINES) logLines.removeFirst()
        activityLine.text = text
        activityRow.visibility = View.VISIBLE
        if (logExpanded) activityLog.text = visibleLog()
    }

    fun release() {
        pill.removeCallbacks(clearFlash)
        pageProgress.removeCallbacks(hideProgress)
        reloadDialog?.dismiss()
        reloadDialog = null
    }

    // ---------------------------------------------------------------- sheet

    private fun setUpSheet() {
        behavior.isHideable = true
        behavior.skipCollapsed = true
        behavior.isFitToContents = true
        behavior.maxWidth = dp(MAX_SHEET_WIDTH_DP).toInt()
        behavior.state = BottomSheetBehavior.STATE_HIDDEN
        behavior.addBottomSheetCallback(object : BottomSheetBehavior.BottomSheetCallback() {
            override fun onStateChanged(bottomSheet: View, newState: Int) {
                if (newState == BottomSheetBehavior.STATE_HIDDEN) onSheetHidden()
            }

            override fun onSlide(bottomSheet: View, slideOffset: Float) {
                // Scrim follows how much of the sheet is on screen (works for any sheet height).
                val parentHeight = (bottomSheet.parent as? View)?.height ?: return
                val shown = (parentHeight - bottomSheet.top).toFloat() / bottomSheet.height.coerceAtLeast(1)
                scrim.alpha = shown.coerceIn(0f, 1f)
            }
        })
        scrim.setOnClickListener { close() }
        find<View>(R.id.hud_reload).setOnClickListener { requestReload() }
        // Cap the sheet height to the current window (shrinks while the keyboard is up).
        root.addOnLayoutChangeListener { _, _, top, _, bottom, _, oldTop, _, oldBottom ->
            if (bottom - top != oldBottom - oldTop) fitSheetHeight(bottom - top)
        }
    }

    private fun fitSheetHeight(parentHeight: Int) {
        val max = (parentHeight * MAX_SHEET_HEIGHT_FRACTION).toInt()
        if (max <= 0 || behavior.maxHeight == max) return
        behavior.maxHeight = max
        sheet.post { sheet.requestLayout() }
    }

    private fun onSheetHidden() {
        hideKeyboard()
        scrim.alpha = 0f
        scrim.visibility = View.GONE
        showPill(!linkTabOpen)
    }

    // ---------------------------------------------------------------- pill

    private fun setUpPill() {
        pill.setOnClickListener { open() }
        pill.setOnLongClickListener {
            showQuickMenu()
            true
        }
        pill.onRefreshTap = { requestReload() }
        pill.showRefresh = uiPrefs.pillRefresh
        // TalkBack can't aim at the ⟳ zone, so offer refresh as a custom action.
        ViewCompat.addAccessibilityAction(pill, activity.getString(R.string.reload_page)) { _, _ ->
            requestReload()
            true
        }
        drag.attach(uiPrefs.pillOnRight, uiPrefs.pillY)
    }

    private fun showPill(show: Boolean) {
        if (pillShown == show) return
        pillShown = show
        if (show) pill.visibility = View.VISIBLE
        // The end action re-checks the wanted state, so a late fade can't hide a shown pill.
        pill.animate().alpha(if (show) 1f else 0f).setDuration(FADE_MS).withEndAction {
            if (!pillShown) pill.visibility = View.INVISIBLE
        }.start()
    }

    // ---------------------------------------------------------------- refresh

    /** Every refresh control ends up here. */
    private fun requestReload() {
        if (!busy) reloadNow() else confirmReload()
    }

    private fun reloadNow() {
        val now = SystemClock.elapsedRealtime()
        if (now - lastReloadAt < RELOAD_DEBOUNCE_MS) return // accidental double tap
        lastReloadAt = now
        close()
        actions.navigate(Nav.RELOAD)
    }

    /** Refreshing mid-task would break it: ask, and stop the task first if confirmed. */
    private fun confirmReload() {
        if (reloadDialog?.isShowing == true) return
        val message = when {
            task is TaskState.Probe -> R.string.reload_confirm_probe
            task is TaskState.Cleanup -> R.string.reload_confirm_cleanup
            else -> R.string.reload_confirm_recovery
        }
        reloadDialog = MaterialAlertDialogBuilder(activity)
            .setTitle(R.string.reload_confirm_title)
            .setMessage(message)
            .setNegativeButton(R.string.action_cancel, null)
            .setPositiveButton(R.string.reload_confirm_ok) { _, _ ->
                actions.stopTask()
                reloadNow()
            }
            .setOnDismissListener { reloadDialog = null }
            .show()
    }

    /**
     * Auto refresh: the watchdog decided the reply is broken, reload quietly.
     * The pill shows a short "自动刷新…" flash and the page-progress bar.
     */
    fun showRecovery() {
        recoveryUntil = SystemClock.elapsedRealtime() + RECOVERY_FLASH_MS
        val prev = task
        task = TaskState.Recovery
        renderTaskButtons()
        renderPill()
        pill.postDelayed({
            if (task === TaskState.Recovery) {
                task = prev
                renderTaskButtons()
                renderPill()
            }
        }, RECOVERY_FLASH_MS)
    }

    private fun renderPill() {
        val flashing = flashText
        val shown = if (flashing != null && task == TaskState.Idle) {
            HudFormat.Pill(flashing, HudFormat.Tone.ACTIVE, busy = false)
        } else {
            HudFormat.pill(session, onNewChat, task)
        }
        pill.render(shown, quotaPercent)
    }

    private fun showQuickMenu() {
        val popup = PopupMenu(activity, pill, if (drag.onRight) Gravity.END else Gravity.START)
        val menu = popup.menu
        when (task) {
            is TaskState.Probe -> menu.add(Menu.NONE, MENU_STOP, 0, R.string.menu_stop_probe)
            is TaskState.Cleanup -> menu.add(Menu.NONE, MENU_STOP, 0, R.string.menu_stop_cleanup)
            TaskState.Idle, TaskState.Recovery -> {
                menu.add(Menu.NONE, MENU_PROBE, 0, R.string.menu_probe)
                menu.add(Menu.NONE, MENU_CLEANUP, 1, R.string.menu_cleanup)
                menu.add(Menu.NONE, MENU_QUICK_SEND, 2, R.string.menu_quick_send)
            }
        }
        menu.add(Menu.NONE, MENU_RELOAD, 3, R.string.menu_reload)
        menu.add(Menu.NONE, MENU_PANEL, 4, R.string.menu_panel)
        popup.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                MENU_STOP -> actions.stopTask()
                MENU_PROBE -> startProbeFromForm()
                MENU_CLEANUP -> actions.startCleanup()
                MENU_QUICK_SEND -> sendQuickText()
                MENU_RELOAD -> requestReload()
                MENU_PANEL -> open()
            }
            true
        }
        popup.show()
    }

    // ---------------------------------------------------------------- tabs

    private fun setUpTabs() {
        tabs.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (isChecked) tabButtonIds.entries.firstOrNull { it.value == checkedId }?.let { showPage(it.key) }
        }
        selectTab(Tab.entries.getOrElse(uiPrefs.lastTab) { Tab.SESSION })
    }

    private fun showPage(tab: Tab) {
        pages.forEach { (t, page) -> page.visibility = if (t == tab) View.VISIBLE else View.GONE }
        hideKeyboard()
        if (uiPrefs.lastTab != tab.ordinal) saveUi(uiPrefs.copy(lastTab = tab.ordinal))
    }

    // ---------------------------------------------------------------- probe page

    private fun setUpProbeForm() {
        val prefs = store.loadPanelPrefs()
        targetsInput.setText(prefs.targets)
        roundsInput.setText(prefs.maxRounds.toString())
        findAllSwitch.isChecked = prefs.findAll
        renameSwitch.isChecked = prefs.autoRename
        prefixInput.setText(prefs.renamePrefix)
        quickInput.setText(prefs.quickText)
        updatePrefixPreview()

        // Listeners go on after restoring, so restoring never re-saves.
        targetsInput.addTextChangedListener(afterTextChanged {
            targetsLayout.error = null
            persistForm()
        })
        roundsInput.addTextChangedListener(afterTextChanged { persistForm() })
        roundsInput.setOnFocusChangeListener { _, hasFocus -> if (!hasFocus) setRounds(currentRounds()) }
        find<View>(R.id.rounds_minus).setOnClickListener { setRounds(currentRounds() - 1) }
        find<View>(R.id.rounds_plus).setOnClickListener { setRounds(currentRounds() + 1) }
        findAllSwitch.setOnCheckedChangeListener { _, _ -> persistForm() }
        renameSwitch.setOnCheckedChangeListener { _, _ ->
            updatePrefixPreview()
            persistForm()
        }
        prefixInput.addTextChangedListener(afterTextChanged {
            updatePrefixPreview()
            persistForm()
        })
        probeToggle.setOnClickListener {
            when (task) {
                is TaskState.Probe -> actions.stopTask()
                TaskState.Idle -> startProbeFromForm()
                is TaskState.Cleanup, TaskState.Recovery -> Unit
            }
        }
    }

    private fun currentRounds(): Int = ProbeLogic.parseRounds(roundsInput.text?.toString())

    private fun setRounds(n: Int) {
        val text = n.coerceIn(ProbeLogic.MIN_ROUNDS, ProbeLogic.MAX_ROUNDS).toString()
        if (roundsInput.text?.toString() == text) return
        roundsInput.setText(text)
        roundsInput.setSelection(text.length)
    }

    private fun updatePrefixPreview() {
        val enabled = renameSwitch.isChecked
        prefixLayout.isEnabled = enabled
        prefixLayout.helperText = if (enabled) {
            HudFormat.prefixPreview(prefixInput.text?.toString().orEmpty(), store.loadSuffixCounters())
        } else {
            activity.getString(R.string.probe_prefix_disabled)
        }
    }

    private fun probeConfig() = ProbeController.Config(
        targets = ProbeLogic.parseTargets(targetsInput.text?.toString()),
        maxRounds = currentRounds(),
        findAll = findAllSwitch.isChecked,
        autoRename = renameSwitch.isChecked,
        renamePrefix = ProbeLogic.sanitizePrefix(prefixInput.text?.toString()),
    )

    private fun startProbeFromForm() {
        val config = probeConfig()
        if (config.targets.isEmpty()) {
            targetsLayout.error = activity.getString(R.string.probe_targets_error)
            open(Tab.PROBE)
            return
        }
        persistForm()
        close()
        actions.startProbe(config)
    }

    private fun persistForm() {
        store.savePanelPrefs(
            Store.PanelPrefs(
                targets = targetsInput.text?.toString().orEmpty(),
                maxRounds = currentRounds(),
                findAll = findAllSwitch.isChecked,
                autoRename = renameSwitch.isChecked,
                renamePrefix = ProbeLogic.sanitizePrefix(prefixInput.text?.toString()),
                quickText = quickInput.text?.toString().orEmpty(),
                autoRefresh = autoRefreshSwitch.isChecked,
            ),
        )
    }

    // ---------------------------------------------------------------- tools page

    private fun setUpTools() {
        cleanupToggle.setOnClickListener {
            when (task) {
                is TaskState.Cleanup -> actions.stopTask()
                TaskState.Idle -> {
                    close()
                    actions.startCleanup()
                }
                is TaskState.Probe, TaskState.Recovery -> Unit
            }
        }
        quickInput.addTextChangedListener(afterTextChanged {
            quickLayout.error = null
            persistForm()
        })
        quickSendButton.setOnClickListener { sendQuickText() }
        find<View>(R.id.nav_back).setOnClickListener { actions.navigate(Nav.BACK) }
        find<View>(R.id.nav_forward).setOnClickListener { actions.navigate(Nav.FORWARD) }
        find<View>(R.id.nav_reload).setOnClickListener { requestReload() }
        pillRefreshSwitch.isChecked = uiPrefs.pillRefresh
        pillRefreshSwitch.setOnCheckedChangeListener { _, checked ->
            pill.showRefresh = checked
            if (uiPrefs.pillRefresh != checked) saveUi(uiPrefs.copy(pillRefresh = checked))
        }
        autoRefreshSwitch.isChecked = store.loadPanelPrefs().autoRefresh
        autoRefreshSwitch.setOnCheckedChangeListener { _, _ -> persistForm() }
    }

    private fun sendQuickText() {
        val text = quickInput.text?.toString().orEmpty()
        if (text.isBlank()) {
            quickLayout.error = activity.getString(R.string.quick_empty)
            open(Tab.TOOLS)
            return
        }
        persistForm()
        close()
        actions.quickSend(text)
    }

    private fun renderTaskButtons() {
        val probing = task is TaskState.Probe
        val cleaning = task is TaskState.Cleanup
        val idle = task == TaskState.Idle
        probeToggle.setText(if (probing) R.string.probe_stop else R.string.probe_start)
        probeToggle.setIconResource(if (probing) R.drawable.ic_stop else R.drawable.ic_play)
        probeToggle.isEnabled = idle || probing
        cleanupToggle.setText(if (cleaning) R.string.cleanup_stop else R.string.cleanup_start)
        cleanupToggle.setIconResource(if (cleaning) R.drawable.ic_stop else R.drawable.ic_broom)
        cleanupToggle.isEnabled = idle || cleaning
        quickSendButton.isEnabled = idle
    }

    // ---------------------------------------------------------------- log

    private fun toggleLog() {
        logExpanded = !logExpanded
        activityLog.text = visibleLog()
        activityLog.visibility = if (logExpanded) View.VISIBLE else View.GONE
        activityChevron.animate().rotation(if (logExpanded) 180f else 0f).setDuration(FADE_MS).start()
    }

    private fun visibleLog(): String = logLines.toList().takeLast(VISIBLE_LOG_LINES).joinToString("\n")

    // ---------------------------------------------------------------- helpers

    private fun <T : View> find(id: Int): T = activity.findViewById(id)

    private fun color(id: Int): Int = activity.getColor(id)

    private fun dp(v: Float): Float = v * activity.resources.displayMetrics.density

    private fun saveUi(next: Store.UiPrefs) {
        uiPrefs = next
        store.saveUiPrefs(next)
    }

    private fun setTextIfChanged(view: TextView, text: String) {
        if (view.text.toString() != text) view.text = text
    }

    private fun levelColor(level: HudFormat.QuotaLevel): Int = when (level) {
        HudFormat.QuotaLevel.OK -> R.color.brand
        HudFormat.QuotaLevel.LOW -> R.color.warn
        HudFormat.QuotaLevel.CRITICAL -> R.color.danger
        HudFormat.QuotaLevel.UNKNOWN -> R.color.outline
    }

    private fun levelTextColor(level: HudFormat.QuotaLevel): Int = when (level) {
        HudFormat.QuotaLevel.OK -> R.color.on_surface
        HudFormat.QuotaLevel.LOW -> R.color.warn
        HudFormat.QuotaLevel.CRITICAL -> R.color.danger
        HudFormat.QuotaLevel.UNKNOWN -> R.color.on_surface_muted
    }

    private fun hideKeyboard() {
        val focused = sheet.findFocus() ?: return
        activity.getSystemService(InputMethodManager::class.java)?.hideSoftInputFromWindow(focused.windowToken, 0)
        focused.clearFocus()
    }

    private fun afterTextChanged(block: () -> Unit): TextWatcher = object : TextWatcher {
        override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
        override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
        override fun afterTextChanged(s: Editable?) = block()
    }

    private companion object {
        const val EDGE_MARGIN_DP = 8f
        const val MAX_SHEET_WIDTH_DP = 560f
        const val MAX_SHEET_HEIGHT_FRACTION = 0.85f
        const val FADE_MS = 150L
        const val FLASH_MS = 2500L
        const val FINISH_FLASH_MS = 4000L
        const val RELOAD_DEBOUNCE_MS = 800L
        const val RECOVERY_FLASH_MS = 4_000L
        const val MIN_PAGE_PROGRESS = 8
        const val PROGRESS_LINGER_MS = 250L
        const val STALLED_LOAD_MS = 30_000L
        const val MAX_LOG_LINES = 40
        const val VISIBLE_LOG_LINES = 8
        const val MENU_STOP = 1
        const val MENU_PROBE = 2
        const val MENU_CLEANUP = 3
        const val MENU_QUICK_SEND = 4
        const val MENU_RELOAD = 5
        const val MENU_PANEL = 6
    }
}
