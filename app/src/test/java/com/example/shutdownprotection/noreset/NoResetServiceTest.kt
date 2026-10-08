package com.example.shutdownprotection.noreset

import android.accessibilityservice.AccessibilityService
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.os.Build
import android.os.Looper
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import com.example.shutdownprotection.BuildConfig
import com.example.shutdownprotection.ui.MainActivity
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implements
import org.robolectric.annotation.Implementation
import org.robolectric.shadows.ShadowAccessibilityService
import org.robolectric.shadow.api.Shadow
import org.robolectric.shadows.ShadowBuild
import org.robolectric.util.ReflectionHelpers
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], shadows = [NoResetAccessibilityShadow::class])
class NoResetServiceTest {
    private lateinit var service: NoResetMenuService
    private lateinit var store: NoResetStore
    @Before fun setup() {
        ShadowBuild.setModel("CPH2825")
        ReflectionHelpers.setStaticField(Build::class.java, "DISPLAY", "CPH2825_16.0.10.501(EX01)")
        service = Robolectric.buildService(NoResetMenuService::class.java).create().get()
        store = NoResetStore(service)
        store.preferences.edit().clear().commit()
        ReflectionHelpers.callInstanceMethod<Void>(service, "onServiceConnected")
        shadowOf(Looper.getMainLooper()).idle()
    }
    @After fun teardown() { service.onDestroy() }
    private fun menu(title: String = "Phone options", id: Int = 51) {
        val root = AccessibilityNodeInfo.obtain().apply { packageName = "com.android.systemui"; className = "android.widget.FrameLayout" }
        val window = AccessibilityWindowInfo.obtain()
        shadowOf(window).apply { setId(id); setType(3); setActive(true); setFocused(true); setTitle(title); setRoot(root) }
        shadowOf(service).setWindows(listOf(window))
    }
    private fun event(id: Int = 51) = AccessibilityEvent.obtain(32).apply {
        packageName = "com.android.systemui"
        className = "com.oplus.systemui.shutdown.OplusGlobalActionsDialog\$ActionsDialog"
        shadowOf(this).setWindowId(id)
    }
    private fun actions() = shadowOf(service).globalActionsPerformed
    private fun disableCalls() = Shadow.extract<NoResetAccessibilityShadow>(service).disableCalls
    @Test fun idleServiceSendsNoBackThenExplicitTrialSendsExactlyBack() {
        menu(); service.onAccessibilityEvent(event()); assertTrue(actions().isEmpty())
        assertTrue(service.startTest()); service.onAccessibilityEvent(event())
        assertEquals(listOf(AccessibilityService.GLOBAL_ACTION_BACK), actions())
    }
    @Test fun unrelatedSystemWindowStaysOpenDuringTrial() {
        menu("Notification shade"); assertTrue(service.startTest())
        service.onAccessibilityEvent(event()); shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(150))
        assertTrue(actions().isEmpty())
    }
    @Test fun expiryEndsTrialAndDoesNotResumeItOnServiceConnection() {
        menu(); assertTrue(service.startTest())
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(60))
        service.onAccessibilityEvent(event()); assertTrue(actions().isEmpty())
        assertSame(service, NoResetMenuService.instance)
        assertTrue(service.ready()); assertEquals(0, disableCalls())
        assertTrue(service.startTest())
        service.onDestroy()
        ReflectionHelpers.callInstanceMethod<Void>(service, "onServiceConnected")
        service.onAccessibilityEvent(event()); assertTrue(actions().isEmpty())
    }
    @Test fun elapsedExpiryRefusesBackEvenWhenEndCallbackHasNotRun() {
        menu(); assertTrue(service.startTest())
        org.robolectric.shadows.ShadowSystemClock.advanceBy(Duration.ofSeconds(60))
        // Deliberately do not idle the looper: its scheduled end callback remains pending.
        service.onAccessibilityEvent(event()); assertTrue(actions().isEmpty())
    }
    @Test fun persistedDailyScheduleDrivesRealServiceAndDisablingItStopsBack() {
        val now = java.time.LocalTime.now()
        val minute = now.hour * 60 + now.minute
        assertTrue(store.save(true, (minute + 1435) % 1440, (minute + 5) % 1440))
        shadowOf(Looper.getMainLooper()).idle()
        menu(); service.onAccessibilityEvent(event())
        assertEquals(listOf(AccessibilityService.GLOBAL_ACTION_BACK), actions())
        assertFalse("A trial cannot overlap an enabled daily schedule", service.startTest())
        assertTrue(store.disable()); shadowOf(Looper.getMainLooper()).idle()
        service.onAccessibilityEvent(event())
        assertEquals(1, actions().size)
    }
    @Test fun pendingMetadataRetryCannotActAfterStopOrRevisionChange() {
        shadowOf(service).setWindows(emptyList()); assertTrue(service.startTest())
        service.onAccessibilityEvent(event())
        service.stop(); menu(); shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(150))
        assertTrue(actions().isEmpty()); assertFalse(store.read().enabled)
        assertSame(service, NoResetMenuService.instance); assertEquals(0, disableCalls())
        assertTrue(service.startTest()); shadowOf(service).setWindows(emptyList()); service.onAccessibilityEvent(event())
        assertTrue(store.save(false, 180, 360)); menu()
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(150)); assertTrue(actions().isEmpty())
    }
    @Test fun corruptPreferenceCannotArmTrialAndCanBeRepairedBySavingValidTimes() {
        store.preferences.edit().putString("start", "wrong type").commit()
        assertFalse(store.read().valid); assertFalse(service.startTest())
        assertTrue(store.save(false, 120, 300)); assertTrue(service.startTest())
    }
    @Test fun unknownFirmwareNeverArmsOrActs() {
        menu(); ShadowBuild.setModel("different phone")
        assertFalse(service.startTest()); service.onAccessibilityEvent(event()); assertTrue(actions().isEmpty())
        ReflectionHelpers.callInstanceMethod<Void>(service, "onServiceConnected")
        assertSame(service, NoResetMenuService.instance)
        assertFalse(service.ready()); assertEquals(0, disableCalls())
    }
    @Test fun duplicateClosingEventsSendOneBackButANewMenuWindowCanBeDismissed() {
        menu(); assertTrue(service.startTest())
        repeat(4) { service.onAccessibilityEvent(event()) }
        assertEquals(1, actions().size)
        menu(id = 52); service.onAccessibilityEvent(event(52))
        assertEquals(listOf(AccessibilityService.GLOBAL_ACTION_BACK, AccessibilityService.GLOBAL_ACTION_BACK), actions())
    }
    @Test fun onlyExplicitServiceOffControlRequestsDisable() {
        service.stopAndDisable()
        assertFalse(store.read().enabled)
        assertEquals(1, disableCalls())
    }
    @Test fun ordinaryInstallationDoesNotAttachManagedSessionOnForeground() {
        val app = RuntimeEnvironment.getApplication()
        val controller = Robolectric.buildActivity(MainActivity::class.java).create().start().resume()
        if (BuildConfig.MANAGED_TOOLS) {
            val container = app.javaClass.getMethod("getContainer").invoke(app)
            val session = container.javaClass.getMethod("getLockTaskSession").invoke(container)
            assertEquals(false, session.javaClass.getMethod("isEntryAvailable").invoke(session))
        } else assertEquals("com.example.shutdownprotection.NoResetApplication", app.javaClass.name)
        controller.pause().stop().destroy()
    }
}

/** Intercepts the actual Android disableSelf call, including calls bypassing app helper methods. */
@Implements(AccessibilityService::class)
class NoResetAccessibilityShadow : ShadowAccessibilityService() {
    var disableCalls = 0
    @Implementation fun disableSelf() { disableCalls++ }
}
