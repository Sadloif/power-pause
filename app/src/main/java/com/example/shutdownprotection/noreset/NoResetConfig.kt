package com.example.shutdownprotection.noreset

import com.example.shutdownprotection.data.ProtectionSettings
import com.example.shutdownprotection.scheduling.ScheduleCalculator
import java.time.Clock
import java.time.Instant
import java.time.ZoneId

/** Separate user intent: never read or modify a Device Owner policy baseline. */
data class NoResetConfig(
    val enabled: Boolean = false,
    val startMinute: Int = 120,
    val endMinute: Int = 300,
    val revision: Long = 0,
) {
    val valid: Boolean get() = startMinute in 0..1439 && endMinute in 0..1439 && startMinute != endMinute
}

class NoResetGate(private val calculator: ScheduleCalculator = ScheduleCalculator(Clock.systemUTC())) {
    fun mayDismiss(config: NoResetConfig, wallTime: Instant, elapsedMillis: Long,
                   testDeadline: Long = 0, zone: ZoneId = ZoneId.systemDefault()): Boolean {
        if (!config.valid) return false
        if (testDeadline > 0) return elapsedMillis < testDeadline
        return calculator.membership(
            ProtectionSettings(enabled = config.enabled, startMinuteOfDay = config.startMinute,
                endMinuteOfDay = config.endMinute), wallTime, zone,
        ).protectedNow
    }
}

object RenoMenuFingerprint {
    fun event(type: Int, pkg: String?, clazz: String?): Boolean = type == 32 &&
        pkg == "com.android.systemui" &&
        clazz == "com.oplus.systemui.shutdown.OplusGlobalActionsDialog\$ActionsDialog"

    fun window(id: Int, candidate: Int, type: Int, active: Boolean, focused: Boolean,
               pkg: String?, rootClass: String?, title: String?): Boolean =
        candidate >= 0 && id == candidate && type == 3 && active && focused &&
        pkg == "com.android.systemui" && rootClass == "android.widget.FrameLayout" && title == "Phone options"
}

object NoResetTimeInput {
    fun formatTyped(text: String): String {
        val candidate = text.trim().replace(';', ':')
        return if (candidate.matches(Regex("[0-9]{4}"))) {
            candidate.substring(0, 2) + ":" + candidate.substring(2)
        } else candidate
    }

    fun parse(text: String): Int? = ProtectionSettings.parseMinuteOfDay(formatTyped(text))
}
