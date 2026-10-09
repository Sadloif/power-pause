package com.example.shutdownprotection.noreset

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RenoAuthGuardTest {
    @Test fun authEventRequiresExactSystemUiClassAndWindowStateChange() {
        assertTrue(RenoAuthFingerprint.event(32, "com.android.systemui", "android.widget.LinearLayout"))
        assertFalse(RenoAuthFingerprint.event(16, "com.android.systemui", "android.widget.LinearLayout"))
        assertFalse(RenoAuthFingerprint.event(32, "com.example", "android.widget.LinearLayout"))
        assertFalse(RenoAuthFingerprint.event(32, "com.android.systemui", "android.app.Dialog"))
    }

    @Test fun authWindowRequiresEveryCapturedWindowField() {
        assertTrue(authWindow())
        val mismatches = listOf(
            Window(id = 8, candidate = 7),
            Window(candidate = -1),
            Window(type = 2),
            Window(active = false),
            Window(focused = false),
            Window(pkg = "com.example"),
            Window(rootClass = "android.app.Dialog"),
            Window(title = ""),
            Window(title = "BiometricPrompt"),
            Window(title = null),
        )
        mismatches.forEach { assertFalse(it.matches()) }
    }

    @Test fun authHierarchyRequiresAllFieldsAtEveryCapturedNode() {
        val expected = expectedHierarchy()
        val heading = "Enter Lock screen password"
        assertTrue(RenoAuthFingerprint.hierarchy(expected, heading))
        assertFalse(RenoAuthFingerprint.hierarchy(expected, null))
        assertFalse(RenoAuthFingerprint.hierarchy(expected, "Enter lock screen password"))
        assertFalse(RenoAuthFingerprint.hierarchy(expected, "$heading "))

        expected.indices.forEach { index ->
            val original = expected[index]
            val mismatches = listOf(
                original.copy(clazz = "${original.clazz}.wrong"),
                original.copy(id = if (original.id == null) "com.android.systemui:id/wrong" else null),
                original.copy(children = original.children + 1),
            )
            mismatches.forEach { mismatch ->
                val changed = expected.toMutableList().apply { this[index] = mismatch }
                assertFalse("Unexpected match at node $index: $mismatch", RenoAuthFingerprint.hierarchy(changed, heading))
            }
        }
        assertFalse(RenoAuthFingerprint.hierarchy(expected.dropLast(1), heading))
        assertFalse(RenoAuthFingerprint.hierarchy(expected + expected.last(), heading))
    }

    @Test fun contextRequiresFreshMenuEventAndRejectsFutureOrStaleEvents() {
        val context = RenoShutdownContext()
        assertFalse(context.begin(21, revision = 4, elapsed = 100, eventUptime = 1_000, nowUptime = 1_751))
        assertFalse(context.begin(21, revision = 4, elapsed = 100, eventUptime = 1_001, nowUptime = 1_000))
        assertFalse(context.permits(revision = 4, elapsed = 100))

        assertTrue(context.begin(21, revision = 4, elapsed = 100, eventUptime = 1_000, nowUptime = 1_750))
        assertTrue(context.permits(revision = 4, elapsed = 100))
    }

    @Test fun contextExpiresAtEndExclusiveLifetimeAndRevisionMismatchClearsIt() {
        val expired = RenoShutdownContext()
        assertTrue(expired.begin(21, revision = 4, elapsed = 100, eventUptime = 1_000, nowUptime = 1_000))
        assertTrue(expired.permits(revision = 4, elapsed = 1_599))
        assertFalse(expired.permits(revision = 4, elapsed = 1_600))
        assertNull(expired.consume(revision = 4, elapsed = 1_600))

        val revised = RenoShutdownContext()
        assertTrue(revised.begin(21, revision = 4, elapsed = 100, eventUptime = 1_000, nowUptime = 1_000))
        assertFalse(revised.permits(revision = 5, elapsed = 101))
        assertFalse(revised.permits(revision = 4, elapsed = 101))
    }

    @Test fun contextConsumesOnceAndClearRemovesIt() {
        val consumed = RenoShutdownContext()
        assertTrue(consumed.begin(21, revision = 4, elapsed = 100, eventUptime = 1_000, nowUptime = 1_000))
        assertEquals(21, consumed.consume(revision = 4, elapsed = 101))
        assertNull(consumed.consume(revision = 4, elapsed = 102))

        val cleared = RenoShutdownContext()
        assertTrue(cleared.begin(22, revision = 4, elapsed = 100, eventUptime = 1_000, nowUptime = 1_000))
        cleared.clear()
        assertFalse(cleared.permits(revision = 4, elapsed = 101))
    }

    @Test fun duplicateMenuEventDoesNotExtendAnActiveContext() {
        val context = RenoShutdownContext()
        assertTrue(context.begin(21, revision = 4, elapsed = 100, eventUptime = 1_000, nowUptime = 1_000))
        assertFalse(context.begin(21, revision = 4, elapsed = 1_000, eventUptime = 1_200, nowUptime = 1_200))
        assertTrue(context.permits(revision = 4, elapsed = 1_599))
        assertFalse(context.permits(revision = 4, elapsed = 1_600))
    }

    @Test fun invalidNewMenuEventClearsExistingContext() {
        listOf(
            1_000L to 1_751L,
            1_001L to 1_000L,
        ).forEachIndexed { index, (eventUptime, nowUptime) ->
            val context = RenoShutdownContext()
            assertTrue(context.begin(21, revision = 4, elapsed = 100, eventUptime = 1_000, nowUptime = 1_000))
            assertFalse(context.begin(22, revision = 4, elapsed = 101L + index, eventUptime = eventUptime, nowUptime = nowUptime))
            assertFalse(context.permits(revision = 4, elapsed = 102L + index))
        }
    }

    private fun authWindow() = Window().matches()

    private fun expectedHierarchy() = listOf(
        RenoAuthNode("android.widget.LinearLayout", null, 1),
        RenoAuthNode("android.widget.FrameLayout", "com.android.systemui:id/layout", 4),
        RenoAuthNode("android.widget.ImageView", "com.android.systemui:id/background", 0),
        RenoAuthNode("android.view.View", "com.android.systemui:id/panel", 0),
        RenoAuthNode("android.widget.LinearLayout", null, 3),
        RenoAuthNode("android.widget.RelativeLayout", null, 2),
        RenoAuthNode("android.widget.ImageView", "com.android.systemui:id/cancel", 0),
        RenoAuthNode("android.widget.ImageView", "com.android.systemui:id/save", 0),
        RenoAuthNode("android.widget.ScrollView", "com.android.systemui:id/scrollView", 1),
        RenoAuthNode("android.widget.FrameLayout", null, 1),
        RenoAuthNode("android.widget.LinearLayout", null, 1),
        RenoAuthNode("android.widget.TextView", "com.android.systemui:id/title", 0),
        RenoAuthNode("android.widget.FrameLayout", null, 1),
        RenoAuthNode("android.view.ViewGroup", "com.android.systemui:id/input_layout", 1),
        RenoAuthNode("android.widget.ScrollView", "com.android.systemui:id/biometric_scrollview", 0),
    )

    private data class Window(
        val id: Int = 7,
        val candidate: Int = 7,
        val type: Int = 3,
        val active: Boolean = true,
        val focused: Boolean = true,
        val pkg: String = "com.android.systemui",
        val rootClass: String = "android.widget.LinearLayout",
        val title: String? = " ",
    ) {
        fun matches() = RenoAuthFingerprint.window(id, candidate, type, active, focused, pkg, rootClass, title)
    }
}
