package com.example.shutdownprotection.admin

import com.example.shutdownprotection.fakes.FakeDevicePolicyGateway
import com.example.shutdownprotection.fakes.FakeRuntimeLockTaskStateProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Device-policy controller tests (brief sections 8 and 26).
 *
 * The point of these cases is that the controller never reports success merely because a
 * setter returned without throwing.
 */
class DevicePolicyControllerTest {

    private fun controller(
        gateway: DevicePolicyGateway = FakeDevicePolicyGateway(),
        runtime: RuntimeLockTaskStateProvider = FakeRuntimeLockTaskStateProvider(),
    ) = DevicePolicyController(
        gateway = gateway,
        runtimeState = runtime,
        applicationId = APP_ID,
        clockMillis = { 1_700_000_000_000L },
    )

    @Test
    fun `PINNED is not accepted as a real locked session`() {
        val pinned = controller(runtime = FakeRuntimeLockTaskStateProvider(LockTaskRuntimeStates.PINNED))
        val locked = controller(runtime = FakeRuntimeLockTaskStateProvider(LockTaskRuntimeStates.LOCKED))
        val none = controller(runtime = FakeRuntimeLockTaskStateProvider(LockTaskRuntimeStates.NONE))

        assertEquals(false, pinned.isRealLockedSession())
        assertEquals(true, locked.isRealLockedSession())
        assertEquals(false, none.isRealLockedSession())
    }

    @Test
    fun `a setter that returns without taking effect is not reported as verified`() {
        val gateway = FakeDevicePolicyGateway().apply { ignoreFeatureWrites = true }
        val result = controller(gateway).applyFeatures(LockTaskMasks.PROTECTED)

        assertTrue(result is PolicyOperationResult.Applied)
        assertTrue("the request was submitted", result.submitted)
        assertFalse("but readback does not confirm it", result.verified)
        assertEquals(0, result.readback?.features)
    }

    @Test
    fun `a setter that is honoured is reported as verified`() {
        val gateway = FakeDevicePolicyGateway()
        val result = controller(gateway).applyFeatures(LockTaskMasks.ALLOWED)

        assertTrue(result.submitted)
        assertTrue(result.verified)
        assertEquals(LockTaskMasks.ALLOWED, result.readback?.features)
        assertEquals(LockTaskMasks.ALLOWED, gateway.features)
    }

    @Test
    fun `a thrown SecurityException is reported as rejected and not as success`() {
        val gateway = FakeDevicePolicyGateway().apply { throwOnSetFeatures = true }
        val result = controller(gateway).applyFeatures(LockTaskMasks.PROTECTED)

        assertTrue(result is PolicyOperationResult.Rejected)
        assertFalse(result.submitted)
        assertFalse(result.verified)
        assertTrue(result.failure is SecurityException)
    }

    @Test
    fun `a thrown SecurityException on packages is reported as rejected`() {
        val gateway = FakeDevicePolicyGateway().apply { throwOnSetPackages = true }
        val result = controller(gateway).applyAllowedPackages(setOf(APP_ID))

        assertTrue(result is PolicyOperationResult.Rejected)
        assertTrue(result.failure is SecurityException)
    }

    @Test
    fun `no policy is attempted without Device Owner authority`() {
        val gateway = FakeDevicePolicyGateway().apply { owner = false }
        val subject = controller(gateway)

        assertEquals(PolicyOperationResult.NotAuthorized, subject.applyFeatures(LockTaskMasks.PROTECTED))
        assertEquals(PolicyOperationResult.NotAuthorized, subject.applyAllowedPackages(setOf(APP_ID)))
        assertTrue(gateway.featureSubmissions.isEmpty())
        assertTrue(gateway.packageSubmissions.isEmpty())
    }

    @Test
    fun `an empty package set is a meaningful value and round-trips as verified`() {
        val gateway = FakeDevicePolicyGateway().apply { packages = mutableSetOf(APP_ID) }
        val result = controller(gateway).applyAllowedPackages(emptySet())

        assertTrue(result.verified)
        assertTrue(gateway.packages.isEmpty())
    }

    @Test
    fun `the package allowlist readback is compared as a set`() {
        val gateway = FakeDevicePolicyGateway()
        val result = controller(gateway).applyAllowedPackages(setOf("a", "b"))
        assertTrue(result.verified)
        assertEquals(setOf("a", "b"), result.readback?.packages)
    }

    @Test
    fun `captureBaseline records the pre-mutation configuration`() {
        val gateway = FakeDevicePolicyGateway().apply {
            packages = mutableSetOf("com.example.original")
            features = 0
        }
        val baseline = controller(gateway).captureBaseline()

        assertEquals(setOf("com.example.original"), baseline.lockTaskPackages)
        assertEquals(0, baseline.lockTaskFeatures)
        assertEquals(APP_ID, baseline.applicationId)
        assertEquals(1_700_000_000_000L, baseline.capturedAtEpochMillis)
    }

    @Test
    fun `captureBaseline taken after a mutation is the caller's mistake to avoid, not the controller's to hide`() {
        val gateway = FakeDevicePolicyGateway()
        val subject = controller(gateway)
        subject.applyFeatures(LockTaskMasks.ALLOWED)

        // The controller reports what is actually there; the brief's rule is that callers
        // capture before mutating, which the coordinator does.
        assertEquals(LockTaskMasks.ALLOWED, subject.captureBaseline().lockTaskFeatures)
    }

    @Test
    fun `a gateway that throws on reads yields unknown rather than a wrong value`() {
        val subject = controller(gateway = ThrowingReadsGateway(), runtime = ThrowingRuntime())
        val snapshot = subject.readSnapshot()

        assertNull(snapshot.features)
        assertNull(snapshot.packages)
        assertNull(snapshot.runtimeLockTaskState)
    }

    @Test
    fun `isLockTaskPermitted reflects the gateway answer`() {
        val gateway = FakeDevicePolicyGateway().apply { packages = mutableSetOf(APP_ID) }
        val subject = controller(gateway)

        assertEquals(true, subject.isLockTaskPermitted(APP_ID))
        assertEquals(false, subject.isLockTaskPermitted("com.example.other"))
    }

    /** Read failures must degrade to "unknown", never to a value that looks verified. */
    private class ThrowingReadsGateway : DevicePolicyGateway {
        override fun isDeviceOwner(): Boolean = true
        override fun isLockTaskPermitted(packageName: String): Boolean = false
        override fun readLockTaskPackages(): Set<String> = throw IllegalStateException("read failed")
        override fun readLockTaskFeatures(): Int = throw IllegalStateException("read failed")
        override fun submitLockTaskPackages(packages: Set<String>) = Unit
        override fun submitLockTaskFeatures(features: Int) = Unit
    }

    private class ThrowingRuntime : RuntimeLockTaskStateProvider {
        override fun lockTaskModeState(): Int = throw IllegalStateException("read failed")
    }

    private companion object {
        const val APP_ID = "com.example.shutdownprotection"
    }
}
