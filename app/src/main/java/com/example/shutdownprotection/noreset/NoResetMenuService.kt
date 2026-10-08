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
import java.time.Instant

/** Menu dismissal only. No settings writes, shell, Shizuku, enrollment or lock-task calls. */
class NoResetMenuService : AccessibilityService() {
    private val handler = Handler(Looper.getMainLooper())
    private val gate = NoResetGate()
    private lateinit var store: NoResetStore
    private var generation = 0L
    private var testDeadline = 0L
    private var interrupted = false
    private var eligibilityProblem: String? = null
    private val handledWindows = LinkedHashSet<Int>()
    private val changes = SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
        generation++
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        store = NoResetStore(this)
        refreshEligibility()
        interrupted = false
        testDeadline = 0 // A service/process restart never resumes a temporary test.
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
        generation++
        testDeadline = SystemClock.elapsedRealtime() + 60_000
        handler.postDelayed({
            testDeadline = 0
            generation++
            lastResult = "60-second test ended."
            recordLifecycle("test_expired", "Accessibility remains enabled; no temporary dismissal remains.")
        }, 60_000)
        return true
    }

    fun stop(): Boolean {
        generation++
        testDeadline = 0
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

    fun resumeAfterSave() { refreshEligibility(); interrupted = false; generation++; testDeadline = 0; handler.removeCallbacksAndMessages(null) }
    fun stopAndDisable() { stop(); recordLifecycle("explicit_service_disable", "Owner selected Stop and turn off service."); disableSelf() }
    fun secondsRemaining(): Long = ((testDeadline - SystemClock.elapsedRealtime() + 999) / 1000).coerceAtLeast(0)
    fun ready(): Boolean = instance === this && !interrupted && eligibilityProblem == null
    fun problem(): String? = eligibilityProblem

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
        if (instance !== this || event == null || !RenoMenuFingerprint.event(event.eventType,
                event.packageName?.toString(), event.className?.toString())) return
        val config = store.read()
        if (!active(config) || event.windowId in handledWindows) return
        val candidate = ++generation
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
                    RenoMenuFingerprint.window(window.id, id, window.type, window.isActive, window.isFocused,
                        root.packageName?.toString(), root.className?.toString(), window.title?.toString())
                } finally { @Suppress("DEPRECATION") root.recycle() }
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
        // Retry only metadata arrival, never an already requested Back. Faster 20ms checks.
        if (attempt < 6) handler.postDelayed({ dismiss(id, candidate, revision, attempt + 1, eventTime, receivedAt) }, 20)
    }

    override fun onInterrupt() {
        interrupted = true; testDeadline = 0; generation++; handler.removeCallbacksAndMessages(null)
        lastResult = "Service interrupted. Reopen the app before enabling protection."
        recordLifecycle("interrupted", lastResult)
    }
    override fun onUnbind(intent: Intent?): Boolean { disconnect(); return super.onUnbind(intent) }
    override fun onDestroy() { disconnect(); super.onDestroy() }
    private fun disconnect() {
        generation++; testDeadline = 0; handler.removeCallbacksAndMessages(null)
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
        fun supported(): Boolean = Build.MODEL == "CPH2825" && Build.VERSION.SDK_INT == 36 &&
            Build.DISPLAY == "CPH2825_16.0.10.501(EX01)"
    }
}
