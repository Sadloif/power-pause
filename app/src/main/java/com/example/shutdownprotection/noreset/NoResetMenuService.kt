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
    private var interrupted = false
    private var eligibilityProblem: String? = null
    private var observationDeadline = 0L
    private var manualTrialDeadline = 0L
    private val observations = ArrayDeque<String>()
    private val handledWindows = LinkedHashSet<Int>()
    private val handledAuthWindows = LinkedHashSet<Int>()
    private val renoShutdown = RenoShutdownContext()
    private val changes = SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
        generation++
        manualTrialDeadline = 0
        renoShutdown.clear()
        if (renoPasswordTrial) {
            renoPasswordTrial = false
            testDeadline = 0
            handler.removeCallbacksAndMessages(null)
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
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
        handler.removeCallbacksAndMessages(null)
        observationDeadline = 0
        manualTrialDeadline = 0
        generation++
        renoShutdown.clear()
        renoPasswordTrial = true
        testDeadline = SystemClock.elapsedRealtime() + 60_000
        handler.postDelayed({
            renoPasswordTrial = false
            testDeadline = 0
            generation++
            renoShutdown.clear()
            lastResult = "60-second password cancellation test ended."
            recordLifecycle("test_expired", "Accessibility remains enabled; no temporary dismissal remains.")
        }, 60_000)
        return true
    }

    fun renoPasswordTrialActive(): Boolean = renoPasswordTrial && secondsRemaining() > 0

    fun stop(): Boolean {
        renoPasswordTrial = false
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

    fun resumeAfterSave() { renoPasswordTrial = false; renoShutdown.clear(); refreshEligibility(); interrupted = false; generation++; testDeadline = 0; observationDeadline = 0; manualTrialDeadline = 0; handler.removeCallbacksAndMessages(null) }
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
        if (!active(config)) { renoShutdown.clear(); return }
        if (renoAuthEnabled() && RenoAuthFingerprint.event(event.eventType, event.packageName?.toString(), event.className?.toString())) {
            if (event.windowId < 0 || event.windowId in handledAuthWindows || !renoShutdown.permits(config.revision, SystemClock.elapsedRealtime())) return
            val candidate = ++generation
            dismissAuth(event.windowId, candidate, config.revision, 0)
            return
        }
        val matchesEvent = when (profile) {
            MenuProfile.RENO -> RenoMenuFingerprint.event(event.eventType, event.packageName?.toString(), event.className?.toString())
            MenuProfile.POCO -> PocoMenuFingerprint.event(event.eventType, event.packageName?.toString(), event.className?.toString())
        }
        if (!matchesEvent) return
        if (event.windowId in handledWindows && !renoPasswordTrialActive()) return
        if (renoAuthEnabled() && renoShutdown.begin(event.windowId, config.revision, SystemClock.elapsedRealtime(), event.eventTime, SystemClock.uptimeMillis())) handledAuthWindows.clear()
        val candidate = ++generation
        if (renoPasswordTrialActive()) {
            // Keep the original menu open during the owner's explicit prompt test.
            // If the swipe already replaced it, only the exact auth fingerprint can pass.
            dismissAuthNow(null, candidate, config.revision)
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
                if (!matches && renoAuthEnabled() && window.isActive && window.isFocused && window.title?.toString() != " ") renoShutdown.clear()
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
        if (instance !== this || generation != candidate || store.read().revision != revision || !active(store.read()) || !renoShutdown.permits(revision, SystemClock.elapsedRealtime())) return
        if (dismissAuthNow(id, candidate, revision)) return
        if (attempt < 6) handler.postDelayed({ dismissAuth(id, candidate, revision, attempt + 1) }, 20)
    }

    private fun dismissAuthNow(id: Int?, candidate: Long, revision: Long): Boolean {
        if (!renoAuthEnabled() || instance !== this || generation != candidate || !renoShutdown.permits(revision, SystemClock.elapsedRealtime())) return false
        try {
            val window = windows.firstOrNull { it.isActive && it.isFocused && (id == null || it.id == id) } ?: return false
            if (window.id in handledAuthWindows) return false
            val root = window.root ?: return false
            val matches = try {
                RenoAuthFingerprint.window(window.id, id ?: window.id, window.type, window.isActive, window.isFocused,
                    root.packageName?.toString(), root.className?.toString(), window.title?.toString()) && renoAuthHierarchy(root)
            } finally { @Suppress("DEPRECATION") root.recycle() }
            val latest = store.read()
            if (!matches || generation != candidate || latest.revision != revision || !active(latest)) return false
            val menuId = renoShutdown.consume(revision, SystemClock.elapsedRealtime()) ?: return false
            generation++
            handledAuthWindows.add(window.id)
            if (handledAuthWindows.size > 64) handledAuthWindows.remove(handledAuthWindows.first())
            // Cancel reveals the same original power window; allow one fresh menu event to close it.
            handledWindows.remove(menuId)
            val accepted = performGlobalAction(GLOBAL_ACTION_BACK)
            actionCount++
            lastResult = "Shutdown password cancellation $actionCount; window=${window.id}; accepted=$accepted."
            android.util.Log.i("PowerPauseAction", lastResult) // Action metadata only, never password input.
            getSharedPreferences("no_reset_status", MODE_PRIVATE).edit().putString("last_action", lastResult)
                .putLong("action_epoch_ms", System.currentTimeMillis()).apply()
            return true
        } catch (_: RuntimeException) { return false }
    }

    private fun renoAuthHierarchy(root: AccessibilityNodeInfo): Boolean {
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
        return walk(root, 0) && RenoAuthFingerprint.hierarchy(nodes, heading)
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
        renoShutdown.clear()
        interrupted = true; testDeadline = 0; observationDeadline = 0; manualTrialDeadline = 0; generation++; handler.removeCallbacksAndMessages(null)
        lastResult = "Service interrupted. Reopen the app before enabling protection."
        recordLifecycle("interrupted", lastResult)
    }
    override fun onUnbind(intent: Intent?): Boolean { disconnect(); return super.onUnbind(intent) }
    override fun onDestroy() { disconnect(); super.onDestroy() }
    private fun disconnect() {
        renoPasswordTrial = false
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
        fun supportsRenoPasswordTrial(): Boolean = !BuildConfig.MANAGED_TOOLS &&
            !BuildConfig.COMPATIBILITY_EDITION && profile() == MenuProfile.RENO
    }
}
