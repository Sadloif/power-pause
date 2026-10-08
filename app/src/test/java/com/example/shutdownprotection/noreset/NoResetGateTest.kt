package com.example.shutdownprotection.noreset

import org.junit.Assert.*
import org.junit.Test
import java.time.Instant
import java.time.ZoneId

class NoResetGateTest {
    private val gate = NoResetGate()
    private val zone = ZoneId.of("Asia/Karachi")
    private fun at(local: String) = java.time.LocalDateTime.parse(local).atZone(zone).toInstant()
    @Test fun dailyScheduleIncludesStartAndExcludesEnd() {
        val config = NoResetConfig(true, 120, 300)
        assertFalse(gate.mayDismiss(config, at("2026-10-08T01:59:59"), 0, zone = zone))
        assertTrue(gate.mayDismiss(config, at("2026-10-08T02:00:00"), 0, zone = zone))
        assertTrue(gate.mayDismiss(config, at("2026-10-08T04:59:59"), 0, zone = zone))
        assertFalse(gate.mayDismiss(config, at("2026-10-08T05:00:00"), 0, zone = zone))
    }
    @Test fun overnightScheduleRecognizesYesterday() {
        val config = NoResetConfig(true, 1380, 60)
        assertTrue(gate.mayDismiss(config, at("2026-10-09T00:30:00"), 0, zone = zone))
        assertFalse(gate.mayDismiss(config, at("2026-10-09T01:00:00"), 0, zone = zone))
    }
    @Test fun disabledAndInvalidIntentNeverDismiss() {
        val time = at("2026-10-08T03:00:00")
        assertFalse(gate.mayDismiss(NoResetConfig(), time, 0, zone = zone))
        for (config in listOf(NoResetConfig(true, -1, 300), NoResetConfig(true, 120, 1440), NoResetConfig(true, 120, 120))) {
            assertFalse(gate.mayDismiss(config, time, 0, zone = zone))
            assertFalse(gate.mayDismiss(config, time, 10, 100, zone))
        }
    }
    @Test fun elapsedTestExpiryCannotBeExtendedByWallClockChanges() {
        val off = NoResetConfig()
        assertTrue(gate.mayDismiss(off, Instant.EPOCH, 59_999, 60_000, zone))
        assertFalse(gate.mayDismiss(off, Instant.EPOCH, 60_000, 60_000, zone))
        assertFalse(gate.mayDismiss(off, Instant.parse("2099-01-01T00:00:00Z"), 60_001, 60_000, zone))
    }
    @Test fun exactEventAndWindowAreRequired() {
        val clazz = "com.oplus.systemui.shutdown.OplusGlobalActionsDialog\$ActionsDialog"
        assertTrue(RenoMenuFingerprint.event(32, "com.android.systemui", clazz))
        assertFalse(RenoMenuFingerprint.event(2048, "com.android.systemui", clazz))
        assertFalse(RenoMenuFingerprint.event(32, "android", clazz))
        assertFalse(RenoMenuFingerprint.event(32, "com.android.systemui", clazz + "Other"))
        assertFalse(RenoMenuFingerprint.event(32, "com.android.permissioncontroller", "android.app.Dialog"))
        fun window(id: Int = 51, candidate: Int = 51, type: Int = 3, active: Boolean = true,
                   focused: Boolean = true, pkg: String? = "com.android.systemui", root: String? = "android.widget.FrameLayout",
                   title: String? = "Phone options") = RenoMenuFingerprint.window(id, candidate, type, active, focused, pkg, root, title)
        assertTrue(window())
        assertFalse(window(candidate = 52)); assertFalse(window(id = -1, candidate = -1))
        assertFalse(window(type = 1)); assertFalse(window(active = false)); assertFalse(window(focused = false))
        assertFalse(window(pkg = "com.other")); assertFalse(window(root = "android.app.Dialog"))
        assertFalse(window(title = "Navigation bar")); assertFalse(window(title = "Notification shade")); assertFalse(window(title = null))
    }
    @Test fun compact24HourEntryPreservesTheIntendedScheduleMinutes() {
        assertEquals("02:30", NoResetTimeInput.formatTyped("0230"))
        assertEquals(150, NoResetTimeInput.parse("0230"))
        assertEquals(870, NoResetTimeInput.parse("1430"))
        assertEquals(0, NoResetTimeInput.parse("0000"))
        assertEquals(1439, NoResetTimeInput.parse("2359"))
        assertEquals(150, NoResetTimeInput.parse("02;30"))
    }
    @Test fun invalidOrIncompleteCompactTimesCannotActivateASchedule() {
        for (text in listOf("2400", "1260", "9999", "023", "02300", "2pm", "-100")) {
            assertNull("Refuse rather than silently changing $text", NoResetTimeInput.parse(text))
        }
    }
}
