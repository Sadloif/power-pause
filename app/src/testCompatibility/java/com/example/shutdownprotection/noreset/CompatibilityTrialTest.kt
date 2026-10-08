package com.example.shutdownprotection.noreset

import android.accessibilityservice.AccessibilityService
import android.os.Build
import android.os.Looper
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowBuild
import org.robolectric.util.ReflectionHelpers
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29, 30, 31, 32, 33, 34, 35, 36])
class CompatibilityTrialTest {
    private lateinit var service: NoResetMenuService
    private lateinit var store: NoResetStore
    @Before fun setup() {
        ShadowBuild.setModel("UNTESTED")
        ReflectionHelpers.setStaticField(Build::class.java, "DISPLAY", "unverified")
        service = Robolectric.buildService(NoResetMenuService::class.java).create().get()
        store = NoResetStore(service)
        store.preferences.edit().clear().commit()
        ReflectionHelpers.callInstanceMethod<Void>(service, "onServiceConnected")
    }
    @After fun cleanup() { service.onDestroy() }
    private fun advance(seconds: Long) = shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(seconds))
    @Test fun unknownPhoneCanRequestExactlyOneBackButNeverAutomaticTrial() {
        assertFalse(service.startTest())
        assertTrue(service.startCompatibilityBackTrial())
        assertFalse(service.startCompatibilityBackTrial())
        advance(19); assertTrue(shadowOf(service).globalActionsPerformed.isEmpty())
        advance(1); assertEquals(listOf(AccessibilityService.GLOBAL_ACTION_BACK), shadowOf(service).globalActionsPerformed)
        advance(30); assertEquals(1, shadowOf(service).globalActionsPerformed.size)
        assertFalse(store.read().enabled)
    }
    @Test fun stopAndDisconnectCancelPendingBack() {
        assertTrue(service.startCompatibilityBackTrial()); assertTrue(service.stop())
        advance(20); assertTrue(shadowOf(service).globalActionsPerformed.isEmpty())
        assertTrue(service.startCompatibilityBackTrial()); service.onDestroy()
        advance(20); assertTrue(shadowOf(service).globalActionsPerformed.isEmpty())
    }
    @Test fun editInvalidatesOldCallbackWithoutCancellingNewTrial() {
        assertTrue(service.startCompatibilityBackTrial()); advance(10)
        assertTrue(store.save(false, 120, 300)); shadowOf(Looper.getMainLooper()).idle()
        assertTrue(service.startCompatibilityBackTrial()); advance(10)
        assertTrue(shadowOf(service).globalActionsPerformed.isEmpty())
        assertEquals(10, service.manualTrialSeconds())
        advance(10); assertEquals(1, shadowOf(service).globalActionsPerformed.size)
    }
}
