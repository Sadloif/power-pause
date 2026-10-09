package com.example.shutdownprotection.noreset

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.os.Build
import android.os.Looper
import android.os.SystemClock
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import org.junit.After
import org.junit.Assert.assertEquals
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

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], shadows = [NoResetAccessibilityShadow::class])
class RenoAuthServiceTest {
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
        assertEquals(MenuProfile.RENO, NoResetMenuService.profile())
        assertTrue("The exact Reno password path is enabled in both safe editions",
            NoResetMenuService.supportsRenoPasswordTrial())
    }

    @After fun cleanup() { service.onDestroy() }

    @Test fun menuThenAuthGetsTwoBacksDuplicateAuthIsOneAndMenuWindowCanCloseAgain() {
        startTrial()
        windows(menuWindow(51))
        send(menuEvent(51))
        assertEquals(1, actions().size)

        windows(menuWindow(51, active = false, focused = false), authWindow(52))
        send(authEvent(52))
        assertEquals(listOf(AccessibilityService.GLOBAL_ACTION_BACK, AccessibilityService.GLOBAL_ACTION_BACK), actions())
        send(authEvent(52))
        assertEquals(2, actions().size)

        windows(menuWindow(51))
        send(menuEvent(51))
        assertEquals(3, actions().size)
        assertSame(service, NoResetMenuService.instance)
    }

    @Test fun authCanUseTheSameWindowIdAsTheOriginalMenu() {
        startTrial()
        windows(menuWindow(61))
        send(menuEvent(61))
        assertEquals(1, actions().size)

        windows(authWindow(61))
        send(authEvent(61))
        assertEquals(2, actions().size)
        send(authEvent(61))
        assertEquals(2, actions().size)
    }

    @Test fun authWithoutFreshPowerMenuContextDoesNothing() {
        startTrial()
        windows(authWindow(70))
        send(authEvent(70))
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(150))
        assertTrue(actions().isEmpty())
    }

    @Test fun authIdCanBeReusedByANewPowerMenuEpisodeButNotByADuplicate() {
        startTrial()
        windows(menuWindow(63)); send(menuEvent(63))
        windows(authWindow(65)); send(authEvent(65)); send(authEvent(65))
        assertEquals(2, actions().size)
        windows(menuWindow(64)); send(menuEvent(64))
        windows(authWindow(65)); send(authEvent(65)); send(authEvent(65))
        assertEquals(4, actions().size)
    }

    @Test fun authAtContextExpiryDoesNotUseTheOldPowerMenu() {
        startTrial()
        windows(menuWindow(66)); send(menuEvent(66))
        org.robolectric.shadows.ShadowSystemClock.advanceBy(Duration.ofMillis(1_500))
        windows(authWindow(67)); send(authEvent(67))
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(150))
        assertEquals(1, actions().size)
    }

    @Test fun explicitPasswordTrialLeavesMenuOpenThenBacksOnlyTheExactAuthPrompt() {
        assertTrue(service.startRenoPasswordTrial())
        assertTrue(service.renoPasswordTrialActive())

        windows(menuWindow(68))
        send(menuEvent(68))
        assertTrue("The original power menu must stay open during this trial", actions().isEmpty())

        windows(menuWindow(68, active = false, focused = false), authWindow(69))
        send(authEvent(69))
        assertEquals(listOf(AccessibilityService.GLOBAL_ACTION_BACK), actions())
        assertTrue(service.renoPasswordTrialActive())
        send(authEvent(69))
        assertEquals("Duplicate auth events cannot send another Back", 1, actions().size)
    }

    @Test fun verifiedMenuFocusKeepsLateAuthAuthorizedBeyondTheInitialBridge() {
        assertTrue(service.startRenoPasswordTrial())
        windows(menuWindow(180))
        send(menuEvent(180))
        assertTrue(actions().isEmpty())

        // The menu stays exact and focused while the owner reaches the prompt.
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(3))
        windows(menuWindow(180, active = false, focused = false), authWindow(181))
        send(authEvent(181))
        assertEquals("Recent verified focus should bridge to the exact auth window", 1, actions().size)
    }

    @Test fun absentOrWrongMenuIdentityCannotRefreshLateAuthContext() {
        assertTrue(service.startRenoPasswordTrial())
        windows(menuWindow(182)); send(menuEvent(182))
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(100))
        windows()
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(2))
        windows(authWindow(183)); send(authEvent(183))
        assertTrue("An absent menu cannot keep the auth context fresh", actions().isEmpty())

        windows(menuWindow(184)); send(menuEvent(184))
        windows(menuWindow(184, title = "Other dialog"))
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(2))
        windows(authWindow(185)); send(authEvent(185))
        assertTrue("A reused ID with the wrong title cannot refresh the context", actions().isEmpty())
    }

    @Test fun preferenceRevisionAndStopCancelAStillVisibleVerifiedMenuEpisode() {
        assertTrue(service.startRenoPasswordTrial())
        windows(menuWindow(186)); send(menuEvent(186))
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(2))
        assertTrue(store.save(false, 181, 361))
        shadowOf(Looper.getMainLooper()).idle()
        windows(menuWindow(186, active = false, focused = false), authWindow(187))
        send(authEvent(187))
        assertTrue("A settings revision cancels the context even with the menu behind auth", actions().isEmpty())

        assertTrue(service.startRenoPasswordTrial())
        windows(menuWindow(188)); send(menuEvent(188))
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(2))
        assertTrue(service.stop())
        windows(menuWindow(188, active = false, focused = false), authWindow(189))
        send(authEvent(189))
        assertTrue("Stop cancels the context even with the menu behind auth", actions().isEmpty())
    }

    @Test fun normalTestStopSaveAndExpiryRestoreNormalMode() {
        assertTrue(service.startRenoPasswordTrial())
        assertTrue(service.startTest())
        assertTrue(!service.renoPasswordTrialActive())
        windows(menuWindow(73))
        send(menuEvent(73))
        assertEquals("Normal test resumes the usual power-menu Back", 1, actions().size)

        assertTrue(service.stop())
        assertTrue(service.startRenoPasswordTrial())
        // Window IDs can be reused from an earlier ordinary test; trial still needs its fresh context.
        windows(menuWindow(73))
        send(menuEvent(73))
        assertEquals("Password-only trial must skip Back even for a previously handled menu ID", 1, actions().size)
        windows(menuWindow(73, active = false, focused = false), authWindow(76))
        send(authEvent(76))
        assertEquals(2, actions().size)

        assertTrue(service.stop())
        assertTrue(!service.renoPasswordTrialActive())

        assertTrue(service.startRenoPasswordTrial())
        assertTrue(store.save(false, 180, 360))
        shadowOf(Looper.getMainLooper()).idle()
        service.resumeAfterSave()
        assertTrue(!service.renoPasswordTrialActive())

        assertTrue(service.startRenoPasswordTrial())
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(60))
        assertTrue(!service.renoPasswordTrialActive())
    }

    @Test fun passwordTrialRequiresPausedEligibleRenoAndDoesNotSurviveReconnect() {
        assertTrue(store.save(true, 180, 360))
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue(!service.startRenoPasswordTrial())

        assertTrue(store.save(false, 180, 360))
        shadowOf(Looper.getMainLooper()).idle()
        ShadowBuild.setModel("unsupported-model")
        assertTrue(!service.startRenoPasswordTrial())
        ShadowBuild.setModel("CPH2825")
        assertTrue(service.startRenoPasswordTrial())
        service.onInterrupt()
        assertTrue(!service.renoPasswordTrialActive())
        ReflectionHelpers.callInstanceMethod<Void>(service, "onServiceConnected")
        assertTrue(!service.renoPasswordTrialActive())
        assertTrue(service.startRenoPasswordTrial())

        windows(menuWindow(74))
        send(menuEvent(74))
        assertTrue(actions().isEmpty())
        service.onUnbind(null)
        assertTrue(!service.renoPasswordTrialActive())
        ReflectionHelpers.callInstanceMethod<Void>(service, "onServiceConnected")
        assertTrue(!service.renoPasswordTrialActive())
        windows(authWindow(75))
        send(authEvent(75))
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(150))
        assertTrue("A new service connection cannot reuse the old menu context", actions().isEmpty())
    }

    @Test fun exactAuthWindowCanPreemptTheOriginalMenuWindowLookup() {
        startTrial()
        windows(authWindow(72))
        send(menuEvent(71))
        assertEquals(listOf(AccessibilityService.GLOBAL_ACTION_BACK), actions())
    }

    @Test fun genericTitleWrongHeadingAndWrongSchemaNeverDismissAuth() {
        startTrial()
        val variants = listOf(
            Triple(80, 90, AuthShape(title = "")),
            Triple(81, 91, AuthShape(heading = "Enter lock screen password")),
            Triple(82, 92, AuthShape(backgroundClass = "android.widget.FrameLayout")),
        )
        variants.forEachIndexed { index, (menuId, authId, shape) ->
            windows(menuWindow(menuId))
            send(menuEvent(menuId))
            val menuBacks = index + 1
            assertEquals(menuBacks, actions().size)

            windows(menuWindow(menuId, active = false, focused = false), authWindow(authId, shape))
            send(authEvent(authId))
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(150))
            assertEquals("Rejected auth variant $index", menuBacks, actions().size)
        }
    }

    @Test fun stopInvalidatesAuthRetryAndNewTestCannotReuseTheOldContext() {
        startTrial()
        windows(menuWindow(100))
        send(menuEvent(100))
        assertEquals(1, actions().size)

        shadowOf(service).setWindows(emptyList())
        send(authEvent(101))
        assertTrue(service.stop())
        assertTrue(service.startTest())
        windows(authWindow(101))
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(150))
        send(authEvent(101))
        assertEquals(1, actions().size)
    }

    @Test fun preferenceRevisionInvalidatesPendingAuthMetadataRetry() {
        startTrial()
        windows(menuWindow(110))
        send(menuEvent(110))
        assertEquals(1, actions().size)

        shadowOf(service).setWindows(emptyList())
        send(authEvent(111))
        assertTrue(store.save(false, 180, 360))
        shadowOf(Looper.getMainLooper()).idle()
        windows(authWindow(111))
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(150))
        assertEquals(1, actions().size)
    }

    @Test fun sixtySecondExpiryAndUnbindCancelPendingAuthRetries() {
        startTrial()
        windows(menuWindow(120))
        send(menuEvent(120))
        shadowOf(service).setWindows(emptyList())
        send(authEvent(121))
        org.robolectric.shadows.ShadowSystemClock.advanceBy(Duration.ofSeconds(60))
        windows(authWindow(121))
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(150))
        assertEquals(1, actions().size)

        assertTrue(service.startTest())
        windows(menuWindow(122))
        send(menuEvent(122))
        shadowOf(service).setWindows(emptyList())
        send(authEvent(123))
        service.onUnbind(null)
        windows(authWindow(123))
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(150))
        assertEquals(2, actions().size)
        assertNull(NoResetMenuService.instance)
    }

    @Test fun accessibilityXmlIncludesWindowViewIdsAndUnimportantViews() {
        val intent = Intent(AccessibilityService.SERVICE_INTERFACE).setPackage(service.packageName)
        val resolveInfo = service.packageManager
            .queryIntentServices(intent, PackageManager.GET_META_DATA)
            .firstOrNull { it.serviceInfo?.name == NoResetMenuService::class.java.name }
        assertNotNull("NoReset service must be declared in the merged manifest", resolveInfo)
        val info = ReflectionHelpers.callConstructor(
            AccessibilityServiceInfo::class.java,
            ReflectionHelpers.ClassParameter.from(ResolveInfo::class.java, requireNotNull(resolveInfo)),
            ReflectionHelpers.ClassParameter.from(Context::class.java, service),
        )
        val requiredFlags = AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS or
            AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS or
            AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS
        assertEquals(requiredFlags, info.flags and requiredFlags)
    }

    private fun startTrial() { assertTrue(service.startTest()) }
    private fun actions() = shadowOf(service).globalActionsPerformed
    private fun send(event: AccessibilityEvent) = service.onAccessibilityEvent(event)
    private fun windows(vararg items: AccessibilityWindowInfo) = shadowOf(service).setWindows(items.toList())

    private fun menuEvent(id: Int) = event(
        id,
        "com.oplus.systemui.shutdown.OplusGlobalActionsDialog\$ActionsDialog",
    )

    private fun authEvent(id: Int) = event(id, "android.widget.LinearLayout")

    private fun event(id: Int, clazz: String) = AccessibilityEvent.obtain(
        AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED,
    ).apply {
        packageName = "com.android.systemui"
        className = clazz
        eventTime = SystemClock.uptimeMillis()
        shadowOf(this).setWindowId(id)
    }

    private fun menuWindow(
        id: Int,
        active: Boolean = true,
        focused: Boolean = true,
        title: String? = "Phone options",
        pkg: String = "com.android.systemui",
        rootClass: String = "android.widget.FrameLayout",
    ): AccessibilityWindowInfo =
        window(id, AccessibilityWindowInfo.TYPE_SYSTEM, active, focused, title, node(rootClass, pkg = pkg))

    private fun authWindow(id: Int, shape: AuthShape = AuthShape()): AccessibilityWindowInfo =
        window(id, AccessibilityWindowInfo.TYPE_SYSTEM, true, true, shape.title, authTree(shape))

    private fun window(
        id: Int,
        type: Int,
        active: Boolean,
        focused: Boolean,
        title: String?,
        root: AccessibilityNodeInfo,
    ) = AccessibilityWindowInfo.obtain().apply {
        shadowOf(this).apply {
            setId(id)
            setType(type)
            setActive(active)
            setFocused(focused)
            setTitle(title)
            setRoot(root)
        }
    }

    private fun authTree(shape: AuthShape): AccessibilityNodeInfo {
        val root = node("android.widget.LinearLayout")
        val layout = node("android.widget.FrameLayout", "com.android.systemui:id/layout")
        attach(root, layout)

        attach(layout, node(shape.backgroundClass, "com.android.systemui:id/background"))
        attach(layout, node("android.view.View", "com.android.systemui:id/panel"))

        val content = node("android.widget.LinearLayout")
        attach(layout, content)
        val relative = node("android.widget.RelativeLayout")
        attach(content, relative)
        attach(relative, node("android.widget.ImageView", "com.android.systemui:id/cancel"))
        attach(relative, node("android.widget.ImageView", "com.android.systemui:id/save"))

        val scroll = node("android.widget.ScrollView", "com.android.systemui:id/scrollView")
        attach(content, scroll)
        val frame = node("android.widget.FrameLayout")
        attach(scroll, frame)
        val column = node("android.widget.LinearLayout")
        attach(frame, column)
        attach(column, node("android.widget.TextView", "com.android.systemui:id/title", text = shape.heading))

        val inputFrame = node("android.widget.FrameLayout")
        attach(content, inputFrame)
        val inputLayout = node("android.view.ViewGroup", "com.android.systemui:id/input_layout")
        attach(inputFrame, inputLayout)
        // Poison credential subtree: a valid match proves the walker stops at input_layout.
        val poison = node("android.widget.EditText", text = "DO_NOT_READ_CREDENTIAL")
        attach(inputLayout, poison)
        attach(poison, node("android.view.View", text = "DO_NOT_READ_CHILD"))

        // The captured biometric scroll container is layout's fourth child, separate from input_layout.
        attach(layout, node("android.widget.ScrollView", "com.android.systemui:id/biometric_scrollview"))
        return root
    }

    private fun node(clazz: String, id: String? = null, text: String? = null, pkg: String = "com.android.systemui"): AccessibilityNodeInfo =
        AccessibilityNodeInfo.obtain().apply {
            packageName = pkg
            className = clazz
            if (id != null) viewIdResourceName = id
            if (text != null) this.text = text
        }

    private fun attach(parent: AccessibilityNodeInfo, child: AccessibilityNodeInfo) {
        Shadow.extract<ShadowAccessibilityNodeInfo>(parent).addChild(child)
    }

    private data class AuthShape(
        val title: String? = " ",
        val heading: String? = "Enter Lock screen password",
        val backgroundClass: String = "android.widget.ImageView",
    )
}
