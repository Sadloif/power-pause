package com.example.shutdownprotection.admin

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Feature-mask and runtime-state tests.
 *
 * The numeric values are pinned against `docs/API_FACTS.md`, which was extracted directly
 * from platform 36's `android.jar`. Pinning them here means a future platform change cannot
 * silently move the mask this feature depends on.
 */
class LockTaskMasksTest {

    @Test
    fun `protected mask matches the verified platform 36 value`() {
        assertEquals(47, LockTaskMasks.EXPECTED_PROTECTED)
        assertEquals(LockTaskMasks.EXPECTED_PROTECTED, LockTaskMasks.PROTECTED)
    }

    @Test
    fun `allowed mask matches the verified platform 36 value`() {
        assertEquals(63, LockTaskMasks.EXPECTED_ALLOWED)
        assertEquals(LockTaskMasks.EXPECTED_ALLOWED, LockTaskMasks.ALLOWED)
    }

    @Test
    fun `the only difference between the masks is global actions`() {
        val difference = LockTaskMasks.ALLOWED xor LockTaskMasks.PROTECTED
        assertEquals(16, difference) // LOCK_TASK_FEATURE_GLOBAL_ACTIONS
    }

    @Test
    fun `the protected mask excludes global actions and the allowed mask includes it`() {
        assertFalse(LockTaskMasks.PROTECTED and 16 != 0)
        assertTrue(LockTaskMasks.ALLOWED and 16 != 0)
    }

    @Test
    fun `neither mask ever sets block activity start in task`() {
        assertEquals(64, LockTaskMasks.BLOCK_ACTIVITY_START_IN_TASK)
        assertEquals(0, LockTaskMasks.PROTECTED and LockTaskMasks.BLOCK_ACTIVITY_START_IN_TASK)
        assertEquals(0, LockTaskMasks.ALLOWED and LockTaskMasks.BLOCK_ACTIVITY_START_IN_TASK)
    }

    @Test
    fun `describe names the features actually set`() {
        val protectedText = LockTaskMasks.describe(LockTaskMasks.PROTECTED)
        assertTrue(protectedText.contains("SYSTEM_INFO"))
        assertTrue(protectedText.contains("NOTIFICATIONS"))
        assertTrue(protectedText.contains("HOME"))
        assertTrue(protectedText.contains("OVERVIEW"))
        assertTrue(protectedText.contains("KEYGUARD"))
        assertFalse(protectedText.contains("GLOBAL_ACTIONS"))

        val allowedText = LockTaskMasks.describe(LockTaskMasks.ALLOWED)
        assertTrue(allowedText.contains("GLOBAL_ACTIONS"))
    }

    @Test
    fun `describe handles the empty mask`() {
        assertEquals("0 (LOCK_TASK_FEATURE_NONE)", LockTaskMasks.describe(0))
    }

    @Test
    fun `only LOCK_TASK_MODE_LOCKED counts as a real locked session`() {
        assertEquals(true, LockTaskRuntimeStates.isRealLocked(LockTaskRuntimeStates.LOCKED))
        assertEquals("screen pinning is failure for this feature", false, LockTaskRuntimeStates.isRealLocked(LockTaskRuntimeStates.PINNED))
        assertEquals(false, LockTaskRuntimeStates.isRealLocked(LockTaskRuntimeStates.NONE))
    }

    @Test
    fun `runtime state values match the verified platform 36 constants`() {
        assertEquals(0, LockTaskRuntimeStates.NONE)
        assertEquals(1, LockTaskRuntimeStates.LOCKED)
        assertEquals(2, LockTaskRuntimeStates.PINNED)
    }

    @Test
    fun `describe distinguishes locked from pinned`() {
        assertTrue(LockTaskRuntimeStates.describe(LockTaskRuntimeStates.LOCKED).contains("LOCKED"))
        assertTrue(LockTaskRuntimeStates.describe(LockTaskRuntimeStates.PINNED).contains("PINNED"))
        assertTrue(LockTaskRuntimeStates.describe(99).contains("UNKNOWN"))
    }
}
