package com.example.shutdownprotection.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Settings model and validation tests (brief sections 7.1-7.3). */
class ProtectionSettingsTest {

    @Test
    fun `defaults match the brief's initial interval`() {
        val defaults = ProtectionSettings.DEFAULT
        assertFalse(defaults.enabled)
        assertEquals(120, defaults.startMinuteOfDay)
        assertEquals(300, defaults.endMinuteOfDay)
        assertEquals(0L, defaults.revision)
        assertTrue(defaults.allowedPackages.isEmpty())
        assertFalse(defaults.recoveryRequired)
        assertEquals(1, defaults.schemaVersion)
    }

    @Test
    fun `minute values outside 0 to 1439 are rejected`() {
        assertEquals(
            SettingsValidation.START_OUT_OF_RANGE,
            (ProtectionSettings(startMinuteOfDay = -1).validate() as SettingsValidation.Invalid).message,
        )
        assertEquals(
            SettingsValidation.START_OUT_OF_RANGE,
            (ProtectionSettings(startMinuteOfDay = 1440).validate() as SettingsValidation.Invalid).message,
        )
        assertEquals(
            SettingsValidation.END_OUT_OF_RANGE,
            (ProtectionSettings(endMinuteOfDay = 1440).validate() as SettingsValidation.Invalid).message,
        )
        assertTrue(ProtectionSettings(startMinuteOfDay = 0, endMinuteOfDay = 1439).isValid)
    }

    @Test
    fun `identical start and end is rejected with the exact required message`() {
        val validation = ProtectionSettings(startMinuteOfDay = 300, endMinuteOfDay = 300).validate()
        assertTrue(validation is SettingsValidation.Invalid)
        assertEquals("Start and end must be different.", (validation as SettingsValidation.Invalid).message)
    }

    @Test
    fun `an identical-time schedule is never inferred as zero or twenty-four hours`() {
        val settings = ProtectionSettings(startMinuteOfDay = 300, endMinuteOfDay = 300)
        assertFalse(settings.isValid)
        // Nothing in the model turns the equal case into a duration.
        assertFalse(settings.crossesMidnight)
    }

    @Test
    fun `crossing midnight is detected by start after end`() {
        assertTrue(ProtectionSettings(startMinuteOfDay = 1380, endMinuteOfDay = 360).crossesMidnight)
        assertFalse(ProtectionSettings(startMinuteOfDay = 120, endMinuteOfDay = 300).crossesMidnight)
    }

    @Test
    fun `revision advances by exactly one`() {
        assertEquals(1L, ProtectionSettings(revision = 0L).withRevisionBumped().revision)
        assertEquals(8L, ProtectionSettings(revision = 7L).withRevisionBumped().revision)
    }

    @Test
    fun `minute formatting is an unambiguous zero-padded 24-hour form`() {
        assertEquals("00:00", ProtectionSettings.formatMinuteOfDay(0))
        assertEquals("02:00", ProtectionSettings.formatMinuteOfDay(120))
        assertEquals("05:00", ProtectionSettings.formatMinuteOfDay(300))
        assertEquals("23:59", ProtectionSettings.formatMinuteOfDay(1439))
    }

    @Test
    fun `parsing accepts valid 24-hour input and rejects the rest`() {
        assertEquals(0, ProtectionSettings.parseMinuteOfDay("00:00"))
        assertEquals(120, ProtectionSettings.parseMinuteOfDay("02:00"))
        assertEquals(1439, ProtectionSettings.parseMinuteOfDay("23:59"))
        assertEquals(120, ProtectionSettings.parseMinuteOfDay("  02:00  "))

        assertNull(ProtectionSettings.parseMinuteOfDay("24:00"))
        assertNull(ProtectionSettings.parseMinuteOfDay("02:60"))
        assertNull(ProtectionSettings.parseMinuteOfDay("2:00:00"))
        assertNull(ProtectionSettings.parseMinuteOfDay("2"))
        assertNull(ProtectionSettings.parseMinuteOfDay(""))
        assertNull(ProtectionSettings.parseMinuteOfDay("ab:cd"))
        assertNull(ProtectionSettings.parseMinuteOfDay("-1:00"))
    }

    @Test
    fun `format and parse round-trip across the whole day`() {
        for (minute in 0..1439) {
            val text = ProtectionSettings.formatMinuteOfDay(minute)
            assertEquals(minute, ProtectionSettings.parseMinuteOfDay(text))
        }
    }
}
