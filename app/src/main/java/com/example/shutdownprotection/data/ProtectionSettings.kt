package com.example.shutdownprotection.data

import java.util.Locale

/**
 * Durable user intent plus the minimum session bookkeeping the brief requires to
 * survive a crash.
 *
 * This type is *intent*, never proof that protection is operating. Observed Android
 * state lives in [com.example.shutdownprotection.protection.ProtectionState] and is
 * written to diagnostics, not here (brief section 7.5 / 7.11).
 *
 * Field set and defaults are fixed by brief section 7.
 */
data class ProtectionSettings(
    val schemaVersion: Int = SCHEMA_VERSION,
    val enabled: Boolean = false,
    val startMinuteOfDay: Int = DEFAULT_START_MINUTE,
    val endMinuteOfDay: Int = DEFAULT_END_MINUTE,
    val revision: Long = 0L,
    val allowedPackages: Set<String> = emptySet(),
    val recoveryRequired: Boolean = false,
) {

    /** Validation per brief section 7.1-7.2. Equal start and end is never inferred. */
    fun validate(): SettingsValidation {
        if (startMinuteOfDay !in MINUTE_MIN..MINUTE_MAX) {
            return SettingsValidation.Invalid(SettingsValidation.START_OUT_OF_RANGE)
        }
        if (endMinuteOfDay !in MINUTE_MIN..MINUTE_MAX) {
            return SettingsValidation.Invalid(SettingsValidation.END_OUT_OF_RANGE)
        }
        if (startMinuteOfDay == endMinuteOfDay) {
            return SettingsValidation.Invalid(SettingsValidation.EQUAL_TIMES)
        }
        return SettingsValidation.Valid
    }

    val isValid: Boolean get() = validate() is SettingsValidation.Valid

    /** True when the interval crosses local midnight (start after end). */
    val crossesMidnight: Boolean get() = startMinuteOfDay > endMinuteOfDay

    val startLabel: String get() = formatMinuteOfDay(startMinuteOfDay)
    val endLabel: String get() = formatMinuteOfDay(endMinuteOfDay)

    /**
     * Every change to enablement, times, allowlist, or invalidated scheduled work
     * advances the revision (brief section 7.3). A newer revision always wins.
     */
    fun withRevisionBumped(): ProtectionSettings = copy(revision = revision + 1)

    companion object {
        const val SCHEMA_VERSION: Int = 1
        const val MINUTE_MIN: Int = 0
        const val MINUTE_MAX: Int = 1439
        const val MINUTES_PER_DAY: Int = 1440

        /** 02:00 local, the brief's stated initial interval. */
        const val DEFAULT_START_MINUTE: Int = 120

        /** 05:00 local. */
        const val DEFAULT_END_MINUTE: Int = 300

        val DEFAULT: ProtectionSettings = ProtectionSettings()

        /**
         * Unambiguous 24-hour label. Locale.ROOT is deliberate: the brief requires an
         * unambiguous 24-hour form, and a localized default locale can emit non-ASCII
         * digits or a 12-hour pattern. A localized 12-hour label may be shown
         * *additionally* by the UI, never instead of this one.
         */
        fun formatMinuteOfDay(minuteOfDay: Int): String {
            val clamped = minuteOfDay.coerceIn(MINUTE_MIN, MINUTE_MAX)
            return String.format(Locale.ROOT, "%02d:%02d", clamped / 60, clamped % 60)
        }

        /** Parses "HH:mm" (24-hour) into a minute-of-day, or null when out of range/malformed. */
        fun parseMinuteOfDay(text: String): Int? {
            val parts = text.trim().split(":")
            if (parts.size != 2) return null
            val hour = parts[0].toIntOrNull() ?: return null
            val minute = parts[1].toIntOrNull() ?: return null
            if (hour !in 0..23 || minute !in 0..59) return null
            return hour * 60 + minute
        }
    }
}

/** Result of validating a [ProtectionSettings] candidate. */
sealed interface SettingsValidation {
    data object Valid : SettingsValidation

    /** [message] is the exact user-facing text required by brief section 7.2. */
    data class Invalid(val message: String) : SettingsValidation

    companion object {
        const val EQUAL_TIMES: String = "Start and end must be different."
        const val START_OUT_OF_RANGE: String = "Start time must be between 00:00 and 23:59."
        const val END_OUT_OF_RANGE: String = "End time must be between 00:00 and 23:59."
    }
}
