package com.example.shutdownprotection.noreset

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Looper
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowAccessibilityNodeInfo
import org.robolectric.shadows.ShadowBuild
import org.robolectric.shadow.api.Shadow
import org.robolectric.util.ReflectionHelpers
import java.time.Duration
import java.time.LocalTime

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class PocoServiceTest {
    private lateinit var service: NoResetMenuService
    private lateinit var store: NoResetStore

    @Before fun setup() {
        ShadowBuild.setModel("M2102J20SG")
        ReflectionHelpers.setStaticField(Build::class.java, "DISPLAY", "TKQ1.221013.002 test-keys")
        ReflectionHelpers.setStaticField(Build.VERSION::class.java, "INCREMENTAL", "V14.0.3.0.TJUMIXM")
        ReflectionHelpers.setStaticField(Build::class.java, "MANUFACTURER", "Xiaomi")
        service = Robolectric.buildService(NoResetMenuService::class.java).create().get()
        store = NoResetStore(service)
        store.preferences.edit().clear().commit()
        ReflectionHelpers.callInstanceMethod<Void>(service, "onServiceConnected")
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(MenuProfile.POCO, NoResetMenuService.profile())
    }

    @After fun cleanup() {
        if (NoResetMenuService.instance === service) service.onDestroy()
    }

    @Test fun capturedPocoTreeSendsBackOnlyDuringAnExplicitTrial() {
        menu()
        service.onAccessibilityEvent(event())
        assertTrue(actions().isEmpty())

        assertTrue(service.startTest())
        menu()
        service.onAccessibilityEvent(event())
        assertEquals(listOf(AccessibilityService.GLOBAL_ACTION_BACK), actions())
        assertSame(service, NoResetMenuService.instance)
    }

    @Test fun genericDialogWrongContentIdExtraNodeAndWindowManagerTitleDoNotMatch() {
        assertTrue(service.startTest())

        genericDialog(id = 70)
        service.onAccessibilityEvent(event(70))
        menu(id = 71, contentId = "android:id/other")
        service.onAccessibilityEvent(event(71))
        menu(id = 72, itemCount = 6)
        service.onAccessibilityEvent(event(72))
        menu(id = 73, lastDescription = "Notification shade")
        service.onAccessibilityEvent(event(73))
        // The app-observed AccessibilityWindowInfo title is null; the separate
        // WindowManager title is not a runtime fingerprint.
        menu(id = 74, title = "MiuiGlobalActions")
        service.onAccessibilityEvent(event(74))

        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(150))
        assertTrue(actions().isEmpty())
    }

    @Test fun duplicateEventsSendOneBackPerWindowAndASecondWindowCanBeHandled() {
        assertTrue(service.startTest())
        menu(id = 80)
        repeat(4) { service.onAccessibilityEvent(event(80)) }
        assertEquals(listOf(AccessibilityService.GLOBAL_ACTION_BACK), actions())

        menu(id = 81)
        service.onAccessibilityEvent(event(81))
        assertEquals(listOf(AccessibilityService.GLOBAL_ACTION_BACK, AccessibilityService.GLOBAL_ACTION_BACK), actions())
    }

    @Test fun pausedScheduleDoesNothingDailyScheduleActsAndStopPausesAgain() {
        menu(id = 90)
        service.onAccessibilityEvent(event(90))
        assertTrue(actions().isEmpty())

        val now = LocalTime.now()
        val minute = now.hour * 60 + now.minute
        assertTrue(store.save(true, (minute + 1410) % 1440, (minute + 30) % 1440))
        shadowOf(Looper.getMainLooper()).idle()
        menu(id = 91)
        service.onAccessibilityEvent(event(91))
        assertEquals(listOf(AccessibilityService.GLOBAL_ACTION_BACK), actions())

        assertTrue(service.stop())
        assertFalse(store.read().enabled)
        menu(id = 92)
        service.onAccessibilityEvent(event(92))
        assertEquals(1, actions().size)
        assertSame(service, NoResetMenuService.instance)
    }

    @Test fun stopInvalidatesLateWindowMetadataAndLeavesServiceBound() {
        shadowOf(service).setWindows(emptyList())
        assertTrue(service.startTest())
        service.onAccessibilityEvent(event(100))

        assertTrue(service.stop())
        menu(id = 100)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(150))

        assertTrue(actions().isEmpty())
        assertSame(service, NoResetMenuService.instance)
        assertTrue(service.ready())
        assertFalse(store.read().enabled)
    }

    @Test fun expiredTrialAndUnbindRejectLateMetadata() {
        shadowOf(service).setWindows(emptyList())
        assertTrue(service.startTest())
        service.onAccessibilityEvent(event(110))
        org.robolectric.shadows.ShadowSystemClock.advanceBy(Duration.ofSeconds(60))
        menu(id = 110)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(150))
        assertTrue(actions().isEmpty())

        shadowOf(service).setWindows(emptyList())
        assertTrue(service.startTest())
        service.onAccessibilityEvent(event(111))
        service.onUnbind(null)
        menu(id = 111)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(150))
        assertTrue(actions().isEmpty())
        assertNull(NoResetMenuService.instance)
    }

    @Test fun automaticTrialCancelsManualBackAndPreventsOverlap() {
        assertTrue(service.startCompatibilityObservation())
        assertTrue(service.startCompatibilityBackTrial())
        assertTrue(service.manualTrialSeconds() > 0)

        assertTrue(service.startTest())
        assertEquals(0, service.manualTrialSeconds())
        assertEquals(0, service.observationSeconds())
        assertFalse(service.startCompatibilityBackTrial())
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(20))
        assertTrue(actions().isEmpty())
    }

    @Test fun mergedCompatibilityServiceXmlIncludesCapturedViewIdsAndUnimportantViews() {
        val intent = Intent(AccessibilityService.SERVICE_INTERFACE).setPackage(service.packageName)
        val resolveInfo = service.packageManager
            .queryIntentServices(intent, PackageManager.GET_META_DATA)
            .firstOrNull { it.serviceInfo?.name == NoResetMenuService::class.java.name }
        assertNotNull("NoReset accessibility service must be declared in the merged manifest", resolveInfo)

        val info = ReflectionHelpers.callConstructor(
            AccessibilityServiceInfo::class.java,
            ReflectionHelpers.ClassParameter.from(ResolveInfo::class.java, requireNotNull(resolveInfo)),
            ReflectionHelpers.ClassParameter.from(Context::class.java, service),
        )
        val flags = info.flags
        val required = AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS or
            AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS or
            AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
        assertEquals(required, flags and required)
    }

    private fun actions() = shadowOf(service).globalActionsPerformed

    private fun event(id: Int = 50) = AccessibilityEvent.obtain(AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED).apply {
        packageName = "com.android.systemui"
        className = "android.app.Dialog"
        shadowOf(this).setWindowId(id)
    }

    private fun menu(
        id: Int = 50,
        title: String? = null,
        contentId: String = "android:id/content",
        itemCount: Int = 5,
        lastDescription: String = "Power off",
    ) {
        val root = node("android.widget.FrameLayout")
        val linear = node("android.widget.LinearLayout")
        val outer = node("android.widget.FrameLayout", id = contentId)
        val content = node("android.widget.FrameLayout")
        attach(root, linear)
        attach(linear, outer)
        attach(outer, content)
        val labels = listOf("Back", "Aeroplane", "Silent", "Reboot", "Power off")
        repeat(itemCount) { index ->
            val label = if (index == 4) lastDescription else labels.getOrElse(index) { "Extra" }
            attach(content, node("android.view.View", description = label))
        }

        val window = AccessibilityWindowInfo.obtain()
        shadowOf(window).apply {
            setId(id)
            setType(AccessibilityWindowInfo.TYPE_SYSTEM)
            setActive(true)
            setFocused(true)
            setTitle(title)
            setRoot(root)
        }
        shadowOf(service).setWindows(listOf(window))
    }

    private fun genericDialog(id: Int) {
        val root = node("android.widget.FrameLayout")
        val window = AccessibilityWindowInfo.obtain()
        shadowOf(window).apply {
            setId(id)
            setType(AccessibilityWindowInfo.TYPE_SYSTEM)
            setActive(true)
            setFocused(true)
            setTitle(null)
            setRoot(root)
        }
        shadowOf(service).setWindows(listOf(window))
    }

    private fun node(clazz: String, id: String? = null, description: String? = null) =
        AccessibilityNodeInfo.obtain().apply {
            packageName = "com.android.systemui"
            className = clazz
            contentDescription = description
            if (id != null) viewIdResourceName = id
        }

    private fun attach(parent: AccessibilityNodeInfo, child: AccessibilityNodeInfo) {
        Shadow.extract<ShadowAccessibilityNodeInfo>(parent).addChild(child)
    }
}
