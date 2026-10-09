package com.example.shutdownprotection.noreset

import android.accessibilityservice.AccessibilityService
import android.app.admin.DevicePolicyManager
import android.content.Intent
import android.content.SharedPreferences
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.example.shutdownprotection.BuildConfig
import java.time.Instant

/** Menu dismissal only. No settings writes, shell, Shizuku, enrollment or lock-task calls. */
class NoResetMenuService : AccessibilityService() {
    private val handler = Handler(Looper.getMainLooper())
    private val gate = NoResetGate()
    private lateinit var store: NoResetStore
    private var generation = 0L
    private var testDeadline = 0L
    private var renoPasswordTrial = false
    private var authTrialStartedElapsed = 0L
    private var authTrialDiagnosticCount = 0
    private var authTrialLastMenuLogElapsed = 0L
    private var interrupted = false
    private var eligibilityProblem: String? = null
    private var observationDeadline = 0L
    private var manualTrialDeadline = 0L
    private val observations = ArrayDeque<String>()
    private val handledWindows = LinkedHashSet<Int>()
    private val handledAuthWindows = LinkedHashSet<Int>()
    private val renoShutdown = RenoShutdownContext()
    private var renoMenuMonitorRunnable: Runnable? = null
    private val changes = SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
        generation++
        manualTrialDeadline = 0
        cancelRenoMenuMonitor()
        renoShutdown.clear()
        if (renoPasswordTrial) {
            renoPasswordTrial = false
            testDeadline = 0
            handler.removeCallbacksAndMessages(null)
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        cancelRenoMenuMonitor()
        handler.removeCallbacksAndMessages(null)
        store = NoResetStore(this)
        refreshEligibility()
        interrupted = false
        renoShutdown.clear()
        testDeadline = 0 // A service/process restart never resumes a temporary test.
        renoPasswordTrial = false
        observationDeadline = 0
        manualTrialDeadline = 0
        observations.clear()
        store.preferences.registerOnSharedPreferenceChangeListener(changes)
        instance = this
        lastResult = "Service connected. No dismissal recorded in this session."
        recordLifecycle("connected", eligibilityProblem ?: "Ready; schedule and test govern actions separately.")
    }

    fun startTest(): Boolean {
        refreshEligibility()
        if (instance !== this || eligibilityProblem != null || !supported() || !store.read().valid || store.read().enabled) return false
        interrupted = false // A new explicit test is the owner's resume action.
        cancelRenoMenuMonitor()
        handler.removeCallbacksAndMessages(null)
        renoPasswordTrial = false
        observationDeadline = 0
        manualTrialDeadline = 0
        generation++
        renoShutdown.clear()
        testDeadline = SystemClock.elapsedRealtime() + 60_000
        handler.postDelayed({
            testDeadline = 0
            generation++
            cancelRenoMenuMonitor()
            renoShutdown.clear()
            lastResult = "60-second test ended."
            recordLifecycle("test_expired", "Accessibility remains enabled; no temporary dismissal remains.")
        }, 60_000)
        return true
    }

    /** Explicit Reno-only trial that leaves the power menu open to reach its shutdown prompt. */
    fun startRenoPasswordTrial(): Boolean {
        refreshEligibility()
        val config = store.read()
        if (!renoAuthEnabled() || instance !== this || interrupted || eligibilityProblem != null ||
            !config.valid || config.enabled || secondsRemaining() > 0) return false
        cancelRenoMenuMonitor()
        handler.removeCallbacksAndMessages(null)
        observationDeadline = 0
        manualTrialDeadline = 0
        generation++
        renoShutdown.clear()
        renoPasswordTrial = true
        authTrialStartedElapsed = SystemClock.elapsedRealtime()
        authTrialDiagnosticCount = 0
        authTrialLastMenuLogElapsed = 0
        testDeadline = SystemClock.elapsedRealtime() + 60_000
        handler.postDelayed({
            renoPasswordTrial = false
            testDeadline = 0
            generation++
            cancelRenoMenuMonitor()
            renoShutdown.clear()
            lastResult = "60-second password cancellation test ended."
            recordLifecycle("test_expired", "Accessibility remains enabled; no temporary dismissal remains.")
        }, 60_000)
        return true
    }

    fun renoPasswordTrialActive(): Boolean = renoPasswordTrial && secondsRemaining() > 0

    fun stop(): Boolean {
        renoPasswordTrial = false
        cancelRenoMenuMonitor()
        renoShutdown.clear()
        generation++
        testDeadline = 0
        observationDeadline = 0
        manualTrialDeadline = 0
        handler.removeCallbacksAndMessages(null)
        // Interrupt blocks stale work even if the durable Stop write fails.
        interrupted = true
        val saved = store.disable()
        if (saved) interrupted = false
        lastResult = if (saved) "Stopped. Accessibility remains enabled and idle."
            else "Stop could not be saved. Turn off this Accessibility service in Settings."
        recordLifecycle("stop", lastResult)
        return saved
    }

    fun resumeAfterSave() { renoPasswordTrial = false; cancelRenoMenuMonitor(); renoShutdown.clear(); refreshEligibility(); interrupted = false; generation++; testDeadline = 0; observationDeadline = 0; manualTrialDeadline = 0; handler.removeCallbacksAndMessages(null) }
    fun stopAndDisable() { stop(); recordLifecycle("explicit_service_disable", "Owner selected Stop and turn off service."); disableSelf() }
    fun secondsRemaining(): Long = ((testDeadline - SystemClock.elapsedRealtime() + 999) / 1000).coerceAtLeast(0)
    fun ready(): Boolean = instance === this && !interrupted && eligibilityProblem == null
    fun problem(): String? = eligibilityProblem

    /** Compatibility experiments require explicit owner input; they never learn/arm an automatic profile. */
    fun startCompatibilityObservation(): Boolean {
        if (!compatibilityTrialAllowed()) return false
        observations.clear()
        observationDeadline = SystemClock.elapsedRealtime() + 45_000
        return true
    }

    fun startCompatibilityBackTrial(): Boolean {
        if (!compatibilityTrialAllowed() || manualTrialSeconds() > 0) return false
        val candidate = ++generation
        val revision = store.read().revision
        manualTrialDeadline = SystemClock.elapsedRealtime() + 20_000
        handler.postDelayed({
            // A late callback is discarded; Stop, edits and disconnect also invalidate it.
            if (candidate != generation) return@postDelayed
            if (candidate == generation && compatibilityTrialAllowed() &&
                store.read().revision == revision && manualTrialDeadline > 0 &&
                SystemClock.elapsedRealtime() <= manualTrialDeadline + 1_000) {
                manualTrialDeadline = 0
                val accepted = performGlobalAction(GLOBAL_ACTION_BACK)
                lastResult = "One requested Back: accepted=$accepted. Confirm menu closure yourself."
            }
            manualTrialDeadline = 0
        }, 20_000)
        return true
    }

    private fun compatibilityTrialAllowed(): Boolean = BuildConfig.COMPATIBILITY_EDITION &&
        instance === this && !interrupted && secondsRemaining() == 0L && store.read().let { it.valid && !it.enabled } &&
        runCatching { getSystemService(DevicePolicyManager::class.java).isDeviceOwnerApp(packageName) == false }.getOrDefault(false)

    fun manualTrialSeconds(): Long = ((manualTrialDeadline - SystemClock.elapsedRealtime() + 999) / 1000).coerceAtLeast(0)
    fun observationSeconds(): Long = ((observationDeadline - SystemClock.elapsedRealtime() + 999) / 1000).coerceAtLeast(0)
    fun observationReport(): String = observations.joinToString("\n\n").ifEmpty { "No System UI window event recorded yet." }

    private fun refreshEligibility() {
        val owner = runCatching { getSystemService(DevicePolicyManager::class.java).isDeviceOwnerApp(packageName) }.getOrNull()
        eligibilityProblem = when {
            !supported() -> "This firmware is unsupported. Accessibility stays enabled, but no menu action is sent."
            owner == true -> "Use managed device mode on this enrolled device. No Accessibility dismissal is sent."
            owner == null -> "Device mode could not be checked. No dismissal is sent; Accessibility stays enabled."
            else -> null
        }
    }

    private fun active(config: NoResetConfig): Boolean = ready() && supported() &&
        gate.mayDismiss(config, Instant.now(), SystemClock.elapsedRealtime(), testDeadline)

    private fun authTrialLog(message: String) {
        if (!renoPasswordTrialActive() || authTrialDiagnosticCount >= 120) return
        val trialElapsed = (SystemClock.elapsedRealtime() - authTrialStartedElapsed).coerceAtLeast(0)
        authTrialDiagnosticCount++
        android.util.Log.i("PowerPauseAuthTrial", "seq=$authTrialDiagnosticCount trialElapsedMs=$trialElapsed $message")
    }

    private fun authTrialContextInfo(revision: Long): String {
        if (!renoPasswordTrialActive()) return ""
        val now = SystemClock.elapsedRealtime()
        val snapshot = renoShutdown.peek(revision, now)
        return " menuWindow=${snapshot?.menuWindowId ?: -1} lastMenuAgeMs=${snapshot?.let { now - it.lastVerifiedMenuElapsed } ?: -1} episode=${snapshot?.episodeId ?: -1}"
    }

    private fun authNodesSummary(nodes: List<RenoAuthNode>): String = nodes.take(15).joinToString(",") {
        "${it.clazz?.take(80) ?: "?"}@${it.id?.take(120) ?: "-"}:${it.children}"
    }

    private data class RenoAuthHierarchyResult(
        val matched: Boolean,
        val headingMatched: Boolean,
        val nodes: List<RenoAuthNode>,
    )

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (instance === this && event != null && BuildConfig.COMPATIBILITY_EDITION &&
            SystemClock.elapsedRealtime() < observationDeadline && event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED &&
            event.packageName?.toString() == "com.android.systemui") {
            val entry = "API=${Build.VERSION.SDK_INT}; model=${Build.MODEL.take(80)}; window=${event.windowId}; class=${event.className?.toString()?.take(200)}"
            if (observations.lastOrNull() != entry) {
                if (observations.size == 8) observations.removeFirst()
                observations.addLast(entry)
            }
        }
        if (instance !== this || event == null) return
        val profile = profile() ?: return
        val config = store.read()
        if (!active(config)) { cancelRenoMenuMonitor(); renoShutdown.clear(); return }
        if (renoAuthEnabled() && RenoAuthFingerprint.event(event.eventType, event.packageName?.toString(), event.className?.toString())) {
            val trial = renoPasswordTrialActive()
            val receivedUptime = SystemClock.uptimeMillis()
            val ageMs = receivedUptime - event.eventTime
            if (event.windowId < 0) {
                if (trial) authTrialLog("auth_event=refused reason=invalid_id window=${event.windowId} eventUptime=${event.eventTime} receiveUptime=$receivedUptime ageMs=$ageMs revision=${config.revision} generation=$generation")
                return
            }
            if (event.windowId in handledAuthWindows) {
                if (trial) authTrialLog("auth_event=refused reason=dedupe window=${event.windowId} eventUptime=${event.eventTime} receiveUptime=$receivedUptime ageMs=$ageMs revision=${config.revision} generation=$generation")
                return
            }
            val contextAtEvent = renoShutdown.peek(config.revision, SystemClock.elapsedRealtime())
            if (!renoShutdown.permits(config.revision, SystemClock.elapsedRealtime())) {
                if (contextAtEvent == null) cancelRenoMenuMonitor()
                if (trial) authTrialLog("auth_event=refused reason=no_fresh_menu window=${event.windowId} eventUptime=${event.eventTime} receiveUptime=$receivedUptime ageMs=$ageMs menuWindow=${contextAtEvent?.menuWindowId ?: -1} lastMenuAgeMs=${contextAtEvent?.let { SystemClock.elapsedRealtime() - it.lastVerifiedMenuElapsed } ?: -1} episode=${contextAtEvent?.episodeId ?: -1} revision=${config.revision} generation=$generation")
                return
            }
            if (trial) authTrialLog("auth_event=accepted context=present window=${event.windowId} eventUptime=${event.eventTime} receiveUptime=$receivedUptime ageMs=$ageMs menuWindow=${contextAtEvent?.menuWindowId ?: -1} lastMenuAgeMs=${contextAtEvent?.let { SystemClock.elapsedRealtime() - it.lastVerifiedMenuElapsed } ?: -1} episode=${contextAtEvent?.episodeId ?: -1} revision=${config.revision} generation=$generation")
            val candidate = ++generation
            dismissAuth(event.windowId, candidate, config.revision, 0)
            return
        }
        val matchesEvent = when (profile) {
            MenuProfile.RENO -> RenoMenuFingerprint.event(event.eventType, event.packageName?.toString(), event.className?.toString())
            MenuProfile.POCO -> PocoMenuFingerprint.event(event.eventType, event.packageName?.toString(), event.className?.toString())
        }
        if (!matchesEvent) return
        val trial = renoPasswordTrialActive()
        val previouslyHandled = event.windowId in handledWindows
        if (previouslyHandled && !trial) return
        if (renoAuthEnabled()) {
            val receivedUptime = SystemClock.uptimeMillis()
            val ageMs = receivedUptime - event.eventTime
            val validEventTime = event.windowId >= 0 && event.eventTime >= 0 && receivedUptime >= event.eventTime && ageMs <= 750
            val contextElapsed = SystemClock.elapsedRealtime()
            val created = renoShutdown.begin(event.windowId, config.revision, contextElapsed, event.eventTime, receivedUptime)
            var monitorState = "unchanged"
            if (created) {
                handledAuthWindows.clear()
                val snapshot = renoShutdown.peek(config.revision, SystemClock.elapsedRealtime())
                if (snapshot != null) {
                    cancelRenoMenuMonitor()
                    scheduleRenoMenuMonitor(snapshot, 0)
                    monitorState = "started"
                }
            } else {
                val snapshot = renoShutdown.peek(config.revision, SystemClock.elapsedRealtime())
                if (snapshot?.menuWindowId == event.windowId && isExactFocusedRenoMenu(event.windowId)) {
                    val verifiedAt = SystemClock.elapsedRealtime()
                    if (renoShutdown.markMenuVerified(snapshot.episodeId, config.revision, verifiedAt)) {
                        renoShutdown.peek(config.revision, verifiedAt)?.let {
                            cancelRenoMenuMonitor()
                            scheduleRenoMenuMonitor(it, 100)
                            monitorState = "duplicate_live_verified"
                        }
                    }
                } else if (!validEventTime || snapshot == null) {
                    monitorState = "rejected"
                    if (snapshot == null) cancelRenoMenuMonitor()
                }
            }
            if (trial) {
                val disposition = when { created -> "created"; monitorState == "rejected" -> "rejected"; else -> "duplicate" }
                val snapshot = renoShutdown.peek(config.revision, SystemClock.elapsedRealtime())
                authTrialLog("menu_context=$disposition monitor=$monitorState window=${event.windowId} eventUptime=${event.eventTime} receiveUptime=$receivedUptime ageMs=$ageMs lastMenuAgeMs=${snapshot?.let { SystemClock.elapsedRealtime() - it.lastVerifiedMenuElapsed } ?: -1} episode=${snapshot?.episodeId ?: -1} revision=${config.revision} generation=$generation previouslyHandled=$previouslyHandled")
            }
        }
        val candidate = ++generation
        if (trial) {
            // Keep the original menu open during the owner's explicit prompt test.
            // If the swipe already replaced it, only the exact auth fingerprint can pass.
            dismissAuthNow(null, candidate, config.revision, attempt = -1, finalAttempt = true)
            return
        }
        dismiss(event.windowId, candidate, config.revision, 0, event.eventTime, SystemClock.uptimeMillis())
    }

    private fun dismiss(id: Int, candidate: Long, revision: Long, attempt: Int, eventTime: Long, receivedAt: Long) {
        val config = store.read()
        if (instance !== this || generation != candidate || config.revision != revision || !active(config) || id in handledWindows) return
        try {
            val window = windows.firstOrNull { it.id == id }
            val root = window?.root
            if (window != null && root != null) {
                val matches = try {
                    when (profile()) {
                        MenuProfile.RENO -> RenoMenuFingerprint.window(window.id, id, window.type, window.isActive, window.isFocused,
                            root.packageName?.toString(), root.className?.toString(), window.title?.toString())
                        MenuProfile.POCO -> PocoMenuFingerprint.window(window.id, id, window.type, window.isActive, window.isFocused,
                            root.packageName?.toString(), root.className?.toString(), window.title?.toString()) && pocoHierarchy(root)
                        null -> false
                    }
                } finally { @Suppress("DEPRECATION") root.recycle() }
                if (!matches && renoAuthEnabled() && window.isActive && window.isFocused && window.title?.toString() != " ") {
                    cancelRenoMenuMonitor()
                    renoShutdown.clear()
                }
                // Re-evaluate durable intent and the clock immediately before the action.
                val latest = store.read()
                if (matches && latest.revision == revision && generation == candidate && active(latest)) {
                    generation++
                    // Closing animations can emit duplicate events for the same still-focused
                    // dialog. Request Back at most once per registered menu window.
                    handledWindows.add(id)
                    if (handledWindows.size > 64) handledWindows.remove(handledWindows.first())
                    val actionAt = SystemClock.uptimeMillis()
                    val accepted = performGlobalAction(GLOBAL_ACTION_BACK)
                    actionCount++
                    lastResult = "Back request $actionCount; window=$id; accepted=$accepted; event delivery=" +
                        "${(receivedAt - eventTime).coerceAtLeast(0)}ms; detection=${actionAt - receivedAt}ms."
                    // Optional diagnostics use apply (no synchronous file writes/fsync in this path).
                    getSharedPreferences("no_reset_status", MODE_PRIVATE).edit()
                        .putString("last_action", lastResult).putLong("action_epoch_ms", System.currentTimeMillis()).apply()
                    return
                }
            }
        } catch (_: RuntimeException) {
            lastResult = "Window information unavailable. No Back sent."
            return
        }
        // The swipe can replace the original window before its event is handled.
        // Only a fresh, exact Reno power-menu event can admit this second stage.
        if (renoAuthEnabled() && dismissAuthNow(null, candidate, revision)) return
        // Retry only metadata arrival, never an already requested Back. Faster 20ms checks.
        if (attempt < 6) handler.postDelayed({ dismiss(id, candidate, revision, attempt + 1, eventTime, receivedAt) }, 20)
    }

    private fun renoAuthEnabled(): Boolean = supportsRenoPasswordTrial()

    private fun dismissAuth(id: Int, candidate: Long, revision: Long, attempt: Int) {
        val blocked = when {
            instance !== this -> "service_disconnected"
            generation != candidate -> "generation_changed"
            store.read().revision != revision -> "revision_changed"
            !active(store.read()) -> "gate_closed"
            !renoShutdown.permits(revision, SystemClock.elapsedRealtime()) -> "no_context_or_expired"
            else -> null
        }
        if (blocked != null) {
            if (blocked == "no_context_or_expired" && renoShutdown.peek(revision, SystemClock.elapsedRealtime()) == null) cancelRenoMenuMonitor()
            authTrialLog("auth_attempt=$attempt result=blocked reason=$blocked window=$id generation=$generation revision=$revision${authTrialContextInfo(revision)}")
            return
        }
        if (dismissAuthNow(id, candidate, revision, attempt, finalAttempt = attempt >= 6)) return
        if (attempt < 6) handler.postDelayed({ dismissAuth(id, candidate, revision, attempt + 1) }, 20)
    }

    private fun dismissAuthNow(id: Int?, candidate: Long, revision: Long, attempt: Int = -1, finalAttempt: Boolean = false): Boolean {
        var windowMatch: Boolean? = null
        var hierarchyMatch: Boolean? = null
        var headingMatch: Boolean? = null
        var observedNodes: List<RenoAuthNode> = emptyList()
        var observedWindowId = id
        fun record(outcome: String) {
            if (!renoPasswordTrialActive()) return
            val nodes = if (finalAttempt && outcome != "matched") " nodes=${authNodesSummary(observedNodes)}" else ""
            authTrialLog("auth_attempt=$attempt result=$outcome window=${observedWindowId ?: -1} windowMatch=${windowMatch ?: false} hierarchyMatch=${hierarchyMatch?.toString() ?: "unknown"} headingMatch=${headingMatch?.toString() ?: "unknown"} generation=$generation revision=$revision${authTrialContextInfo(revision)}$nodes")
        }
        if (!renoAuthEnabled()) { record("profile_disabled"); return false }
        if (instance !== this) { record("service_disconnected"); return false }
        if (generation != candidate) { record("generation_changed"); return false }
        if (!renoShutdown.permits(revision, SystemClock.elapsedRealtime())) {
            if (renoShutdown.peek(revision, SystemClock.elapsedRealtime()) == null) cancelRenoMenuMonitor()
            record("no_context_or_expired")
            return false
        }
        try {
            val window = windows.firstOrNull { it.isActive && it.isFocused && (id == null || it.id == id) }
            if (window == null) { record("no_focused_window"); return false }
            observedWindowId = window.id
            if (window.id in handledAuthWindows) { record("dedupe"); return false }
            val root = window.root
            if (root == null) { record("no_root"); return false }
            val hierarchy = try {
                windowMatch = RenoAuthFingerprint.window(window.id, id ?: window.id, window.type, window.isActive, window.isFocused,
                    root.packageName?.toString(), root.className?.toString(), window.title?.toString())
                if (windowMatch == true) {
                    val result = renoAuthHierarchy(root)
                    hierarchyMatch = result.matched
                    headingMatch = result.headingMatched
                    observedNodes = result.nodes
                    result
                } else null
            } finally { @Suppress("DEPRECATION") root.recycle() }
            val matches = windowMatch == true && hierarchy?.matched == true
            val latest = store.read()
            val blockedAfterRead = when {
                !matches -> if (windowMatch != true) "window_fingerprint" else "hierarchy_fingerprint"
                generation != candidate -> "generation_changed"
                latest.revision != revision -> "revision_changed"
                !active(latest) -> "gate_closed"
                else -> null
            }
            if (blockedAfterRead != null) { record(blockedAfterRead); return false }
            val menuId = renoShutdown.consume(revision, SystemClock.elapsedRealtime())
            if (menuId == null) {
                if (renoShutdown.peek(revision, SystemClock.elapsedRealtime()) == null) cancelRenoMenuMonitor()
                record("context_expired_before_action")
                return false
            }
            cancelRenoMenuMonitor()
            val actionGeneration = ++generation
            handledAuthWindows.add(window.id)
            if (handledAuthWindows.size > 64) handledAuthWindows.remove(handledAuthWindows.first())
            // Cancel reveals the same original power window; allow one fresh menu event to close it.
            handledWindows.remove(menuId)
            val actionElapsed = SystemClock.elapsedRealtime()
            val accepted = performGlobalAction(GLOBAL_ACTION_BACK)
            actionCount++
            lastResult = "Shutdown password cancellation $actionCount; window=${window.id}; accepted=$accepted."
            android.util.Log.i("PowerPauseAction", lastResult) // Action metadata only, never password input.
            getSharedPreferences("no_reset_status", MODE_PRIVATE).edit().putString("last_action", lastResult)
                .putLong("action_epoch_ms", System.currentTimeMillis()).apply()
            record("back_requested accepted=$accepted actionElapsedMs=${(actionElapsed - authTrialStartedElapsed).coerceAtLeast(0)} actionGeneration=$actionGeneration menuWindow=$menuId")
            scheduleAuthTrialProbes(window.id, actionGeneration, revision)
            return true
        } catch (_: RuntimeException) { record("window_read_exception"); return false }
    }

    private fun renoAuthHierarchy(root: AccessibilityNodeInfo): RenoAuthHierarchyResult {
        val nodes = ArrayList<RenoAuthNode>(15)
        var heading: String? = null
        fun walk(node: AccessibilityNodeInfo, depth: Int): Boolean {
            if (nodes.size >= 15 || depth > 6 || node.childCount > 4) return false
            val id = node.viewIdResourceName
            nodes.add(RenoAuthNode(node.className?.toString(), id, node.childCount))
            if (id == "com.android.systemui:id/title") {
                if (node.isPassword || node.className?.toString() != "android.widget.TextView") return false
                heading = node.text?.toString() // Exact static heading only; never credential input.
            }
            if (id == "com.android.systemui:id/input_layout") return true // Never descend into password input.
            for (index in 0 until node.childCount) {
                val child = node.getChild(index) ?: return false
                try { if (!walk(child, depth + 1)) return false } finally { @Suppress("DEPRECATION") child.recycle() }
            }
            return true
        }
        val walked = walk(root, 0)
        return RenoAuthHierarchyResult(
            matched = walked && RenoAuthFingerprint.hierarchy(nodes, heading),
            headingMatched = heading == "Enter Lock screen password",
            nodes = nodes.toList(),
        )
    }

    private fun isExactFocusedRenoMenu(menuWindowId: Int): Boolean {
        return try {
            val window = windows.firstOrNull { it.id == menuWindowId && it.isActive && it.isFocused } ?: return false
            val root = window.root ?: return false
            try {
                RenoMenuFingerprint.window(window.id, menuWindowId, window.type, window.isActive, window.isFocused,
                    root.packageName?.toString(), root.className?.toString(), window.title?.toString())
            } finally { @Suppress("DEPRECATION") root.recycle() }
        } catch (_: RuntimeException) { false }
    }

    private fun scheduleRenoMenuMonitor(snapshot: RenoShutdownContext.Snapshot, delayMs: Long) {
        cancelRenoMenuMonitor()
        val task = object : Runnable {
            override fun run() {
                if (renoMenuMonitorRunnable !== this) return
                renoMenuMonitorRunnable = null
                if (instance !== this@NoResetMenuService || !renoAuthEnabled() || interrupted) return
                val config = store.read()
                if (!active(config) || config.revision != snapshot.revision) {
                    renoShutdown.clear()
                    return
                }
                val now = SystemClock.elapsedRealtime()
                val current = renoShutdown.peek(snapshot.revision, now) ?: return
                if (current.episodeId != snapshot.episodeId || current.menuWindowId != snapshot.menuWindowId) return
                if (!isExactFocusedRenoMenu(snapshot.menuWindowId)) {
                    if (renoPasswordTrialActive()) authTrialLog("menu_monitor=stopped reason=not_exact_focused window=${snapshot.menuWindowId} episode=${snapshot.episodeId} lastMenuAgeMs=${now - current.lastVerifiedMenuElapsed} revision=${snapshot.revision}")
                    return
                }
                val verifiedAt = SystemClock.elapsedRealtime()
                if (!renoShutdown.markMenuVerified(snapshot.episodeId, snapshot.revision, verifiedAt)) return
                val updated = renoShutdown.peek(snapshot.revision, verifiedAt) ?: return
                if (renoPasswordTrialActive() &&
                    (authTrialLastMenuLogElapsed == 0L || verifiedAt - authTrialLastMenuLogElapsed >= 1_000)) {
                    authTrialLastMenuLogElapsed = verifiedAt
                    authTrialLog("menu_monitor=verified window=${snapshot.menuWindowId} episode=${snapshot.episodeId} lastMenuAgeMs=0 revision=${snapshot.revision}")
                }
                scheduleRenoMenuMonitor(updated, 100)
            }
        }
        renoMenuMonitorRunnable = task
        handler.postDelayed(task, delayMs)
    }

    private fun cancelRenoMenuMonitor() {
        renoMenuMonitorRunnable?.let(handler::removeCallbacks)
        renoMenuMonitorRunnable = null
    }

    private fun scheduleAuthTrialProbes(windowId: Int, actionGeneration: Long, revision: Long) {
        listOf(40L, 200L, 500L).forEach { delayMs ->
            handler.postDelayed({
                if (instance !== this || !renoPasswordTrialActive() || interrupted || generation != actionGeneration ||
                    store.read().revision != revision) return@postDelayed
                var focused = false
                var fingerprint = false
                try {
                    val window = windows.firstOrNull { it.id == windowId && it.isActive && it.isFocused }
                    if (window != null) {
                        focused = true
                        val root = window.root
                        if (root != null) {
                            try {
                                val windowMatches = RenoAuthFingerprint.window(window.id, windowId, window.type,
                                    window.isActive, window.isFocused, root.packageName?.toString(),
                                    root.className?.toString(), window.title?.toString())
                                if (windowMatches) fingerprint = renoAuthHierarchy(root).matched
                            } finally { @Suppress("DEPRECATION") root.recycle() }
                        }
                    }
                } catch (_: RuntimeException) { }
                authTrialLog("post_back_probe delayMs=$delayMs authWindow=$windowId focused=$focused fingerprint=$fingerprint generation=$generation revision=$revision")
            }, delayMs)
        }
    }

    private fun pocoHierarchy(root: AccessibilityNodeInfo): Boolean {
        val nodes = ArrayList<MenuNode>(9)
        fun snapshot(node: AccessibilityNodeInfo) = MenuNode(node.packageName?.toString(), node.className?.toString(),
            node.viewIdResourceName, node.contentDescription?.toString(), node.childCount)
        fun descend(node: AccessibilityNodeInfo, depth: Int): Boolean {
            nodes.add(snapshot(node))
            if (depth < 3) {
                if (node.childCount != 1) return false
                val child = node.getChild(0) ?: return false
                return try { descend(child, depth + 1) } finally { @Suppress("DEPRECATION") child.recycle() }
            }
            if (node.childCount != 5) return false
            for (index in 0..4) {
                val child = node.getChild(index) ?: return false
                try { nodes.add(snapshot(child)) } finally { @Suppress("DEPRECATION") child.recycle() }
            }
            return true
        }
        return descend(root, 0) && PocoMenuFingerprint.hierarchy(nodes)
    }

    override fun onInterrupt() {
        renoPasswordTrial = false
        cancelRenoMenuMonitor()
        renoShutdown.clear()
        interrupted = true; testDeadline = 0; observationDeadline = 0; manualTrialDeadline = 0; generation++; handler.removeCallbacksAndMessages(null)
        lastResult = "Service interrupted. Reopen the app before enabling protection."
        recordLifecycle("interrupted", lastResult)
    }
    override fun onUnbind(intent: Intent?): Boolean { disconnect(); return super.onUnbind(intent) }
    override fun onDestroy() { disconnect(); super.onDestroy() }
    private fun disconnect() {
        renoPasswordTrial = false
        cancelRenoMenuMonitor()
        renoShutdown.clear(); handledAuthWindows.clear()
        generation++; testDeadline = 0; observationDeadline = 0; manualTrialDeadline = 0; observations.clear(); handler.removeCallbacksAndMessages(null)
        if (::store.isInitialized) store.preferences.unregisterOnSharedPreferenceChangeListener(changes)
        if (instance === this) instance = null
        handledWindows.clear()
        recordLifecycle("disconnected", "Android disconnected the service. This callback does not identify the cause.")
    }

    private fun recordLifecycle(event: String, detail: String) {
        val now = System.currentTimeMillis()
        val edit = getSharedPreferences("no_reset_status", MODE_PRIVATE).edit().putString("lifecycle", event)
            .putString("lifecycle_detail", detail).putLong("lifecycle_epoch_ms", now)
        if (event == "explicit_service_disable") edit.putLong("explicit_disable_epoch_ms", now)
        edit.apply()
    }

    companion object {
        var instance: NoResetMenuService? = null
            private set
        var lastResult: String = "No action recorded."
            private set
        var actionCount: Int = 0
            private set
        fun profile(): MenuProfile? = MenuProfiles.select(BuildConfig.COMPATIBILITY_EDITION, Build.MODEL,
            Build.VERSION.SDK_INT, Build.DISPLAY, Build.VERSION.INCREMENTAL, Build.MANUFACTURER)
        fun supported(): Boolean = profile() != null
        fun supportsRenoPasswordTrial(): Boolean = !BuildConfig.MANAGED_TOOLS && profile() == MenuProfile.RENO
    }
}
