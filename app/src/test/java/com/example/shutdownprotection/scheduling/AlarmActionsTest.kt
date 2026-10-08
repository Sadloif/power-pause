package com.example.shutdownprotection.scheduling

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Alarm identity tests (brief section 14).
 *
 * PendingIntent equality does not include extras, so identity must come from the action and
 * the request code. These tests pin that, and pin that every string follows the actual
 * application ID so a `-Pspm.appId=` build stays self-consistent.
 */
class AlarmActionsTest {

    private val defaultAppId = "com.example.shutdownprotection"
    private val overrideAppId = "com.example.spstest"

    @Test
    fun `the scheduler owns exactly four distinct identities namespaced to the application id`() {
        val actions = AlarmActions.SCHEDULER_KINDS.map { AlarmActions.actionFor(defaultAppId, it) }
        assertEquals(4, actions.size)
        assertEquals("identities must be distinct", actions.size, actions.toSet().size)

        assertEquals("$defaultAppId.action.START_PROTECTION", AlarmActions.startProtection(defaultAppId))
        assertEquals("$defaultAppId.action.END_PROTECTION", AlarmActions.endProtection(defaultAppId))
        assertEquals("$defaultAppId.action.RELEASE_FALLBACK", AlarmActions.releaseFallback(defaultAppId))
        assertEquals("$defaultAppId.action.RECOVERY_RETRY", AlarmActions.recoveryRetry(defaultAppId))
    }

    @Test
    fun `the debug temporary test uses its own identities and never the daily ones`() {
        // The Gate B safety timer must not share the daily END/fallback identities: sharing them
        // would replace the real release path and route the timer through the reconciler, which
        // under a forced restriction would simply re-restrict.
        val schedulerActions = AlarmActions.SCHEDULER_KINDS
            .map { AlarmActions.actionFor(defaultAppId, it) }
            .toSet()
        val tempActions = AlarmActions.TEMPORARY_TEST_KINDS
            .map { AlarmActions.actionFor(defaultAppId, it) }
            .toSet()

        assertEquals(2, tempActions.size)
        assertTrue(
            "the temporary-test identities must not collide with the scheduler's",
            schedulerActions.intersect(tempActions).isEmpty(),
        )
        for (kind in AlarmActions.TEMPORARY_TEST_KINDS) {
            assertTrue("$kind must be marked as a temporary test", kind.isTemporaryTest)
        }
        for (kind in AlarmActions.SCHEDULER_KINDS) {
            assertFalse("$kind must not be marked as a temporary test", kind.isTemporaryTest)
        }
    }

    @Test
    fun `every kind maps to exactly one distinct action and request code`() {
        val actions = AlarmEventKind.entries.map { AlarmActions.actionFor(defaultAppId, it) }
        assertEquals(AlarmEventKind.entries.size, actions.size)
        assertEquals("actions must be distinct", actions.size, actions.toSet().size)

        val codes = AlarmEventKind.entries.map { AlarmActions.requestCode(it) }
        assertEquals("request codes must be distinct", codes.size, codes.toSet().size)
    }

    @Test
    fun `a non-default application id produces a complete self-consistent set`() {
        for (kind in AlarmEventKind.entries) {
            val action = AlarmActions.actionFor(overrideAppId, kind)
            assertTrue(action.startsWith("$overrideAppId."))
            assertEquals(kind, AlarmActions.kindFor(overrideAppId, action))
        }
        // An identity from the default build must not be recognised by the override build.
        assertNull(AlarmActions.kindFor(overrideAppId, AlarmActions.startProtection(defaultAppId)))
        assertNotEquals(
            AlarmActions.startProtection(defaultAppId),
            AlarmActions.startProtection(overrideAppId),
        )
    }

    @Test
    fun `request codes are distinct so identities cannot collide`() {
        assertEquals(1001, AlarmActions.REQUEST_CODE_START)
        assertEquals(1002, AlarmActions.REQUEST_CODE_END)
        assertEquals(1003, AlarmActions.REQUEST_CODE_FALLBACK)
        assertEquals(1004, AlarmActions.REQUEST_CODE_RECOVERY_RETRY)
        assertEquals(1005, AlarmActions.REQUEST_CODE_TEMPORARY_TEST_RELEASE)
        assertEquals(1006, AlarmActions.REQUEST_CODE_TEMPORARY_TEST_FALLBACK)
    }

    @Test
    fun `an unrelated action is not claimed as an app alarm identity`() {
        assertNull(AlarmActions.kindFor(defaultAppId, "android.intent.action.BOOT_COMPLETED"))
        assertNull(AlarmActions.kindFor(defaultAppId, null))
        assertNull(AlarmActions.kindFor(defaultAppId, "${defaultAppId}.action.NOT_OURS"))
    }

    @Test
    fun `extra keys are namespaced to the application id`() {
        for (key in listOf(
            AlarmActions.extraEventKind(defaultAppId),
            AlarmActions.extraSettingsRevision(defaultAppId),
            AlarmActions.extraPlannedBoundary(defaultAppId),
            AlarmActions.extraIncidentId(defaultAppId),
        )) {
            assertTrue(key.startsWith("$defaultAppId."))
        }
    }

    @Test
    fun `wire names round-trip for every kind`() {
        for (kind in AlarmEventKind.entries) {
            assertEquals(kind, AlarmEventKind.fromWireName(kind.wireName))
        }
        assertNull(AlarmEventKind.fromWireName("NOT_A_KIND"))
        assertNull(AlarmEventKind.fromWireName(null))
    }

    @Test
    fun `the fallback is a distinct delayed second attempt rather than a duplicate`() {
        assertTrue(AlarmActions.FALLBACK_DELAY_MILLIS > 0)
        assertNotEquals(AlarmEventKind.END, AlarmEventKind.RELEASE_FALLBACK)
    }

    @Test
    fun `every kind has exactly one identity mapping`() {
        val mapped = AlarmEventKind.entries.map { AlarmActions.actionFor(defaultAppId, it) }
        assertFalse(mapped.isEmpty())
        assertEquals(mapped.size, mapped.map { AlarmActions.kindFor(defaultAppId, it) }.toSet().size)
    }
}
