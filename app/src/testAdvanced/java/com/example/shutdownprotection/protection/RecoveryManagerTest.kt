package com.example.shutdownprotection.protection

import com.example.shutdownprotection.admin.DevicePolicyController
import com.example.shutdownprotection.admin.LockTaskMasks
import com.example.shutdownprotection.admin.LockTaskRuntimeStates
import com.example.shutdownprotection.data.ProtectionSettings
import com.example.shutdownprotection.data.RecoveryIncident
import com.example.shutdownprotection.data.BaselineLifecycleState
import com.example.shutdownprotection.data.BaselineProvenance
import com.example.shutdownprotection.fakes.FakeDevicePolicyGateway
import com.example.shutdownprotection.fakes.FakeDiagnosticsRepository
import com.example.shutdownprotection.fakes.FakeLockTaskSessionController
import com.example.shutdownprotection.fakes.FakeRuntimeLockTaskStateProvider
import com.example.shutdownprotection.fakes.FakeSchedulingGateway
import com.example.shutdownprotection.fakes.FakeSettingsRepository
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Recovery tests (brief sections 10 and 26).
 *
 * Recovery must exist and be verified before the first restrictive test, so these cases run
 * against the same idempotent sequence the app uses.
 */
class RecoveryManagerTest {

    private class Harness {
        val settings = FakeSettingsRepository()
        val diagnostics = FakeDiagnosticsRepository()
        val gateway = FakeDevicePolicyGateway()
        val runtime = FakeRuntimeLockTaskStateProvider()
        val session = FakeLockTaskSessionController(runtime)
        val scheduling = FakeSchedulingGateway()
        val inhibitor = RestrictionInhibitor()

        val controller = DevicePolicyController(
            gateway = gateway,
            runtimeState = runtime,
            applicationId = APP_ID,
            clockMillis = { 0L },
        )

        val recovery = RecoveryManager(
            settingsRepository = settings,
            diagnostics = diagnostics,
            policyController = controller,
            scheduleManager = scheduling,
            lockTaskSession = session,
            inhibitor = inhibitor,
            clockMillis = { 0L },
            newIncidentId = { "incident-1" },
            sleep = {},
        )

        fun armedSession() = ProtectionSettings(
            enabled = true,
            startMinuteOfDay = 120,
            endMinuteOfDay = 300,
            revision = 3L,
            allowedPackages = setOf(APP_ID),
        )

        /**
         * Seeds the original policy baseline a real arm transaction would have captured
         * (repair R02). Recovery can only *confirm* restoration against a valid baseline; with none
         * stored the honest outcome is `RESTORE_BASELINE_UNAVAILABLE`, not a verified recovery.
         */
        fun seedBaseline(features: Int = 0, packages: Set<String> = emptySet()) {
            settings.baseline = com.example.shutdownprotection.data.PolicyBaseline(
                capturedAtEpochMillis = 0L,
                lockTaskPackages = packages,
                lockTaskFeatures = features,
                applicationId = APP_ID,
                provenance = BaselineProvenance.CAPTURED_BEFORE_PREPARATION,
                lifecycle = BaselineLifecycleState.PREPARED,
            )
        }
    }

    @Test
    fun `recovery is idempotent and reaches the same verified end state twice`() = runTest {
        val harness = Harness()
        harness.settings.seed(harness.armedSession())
        harness.seedBaseline()
        harness.runtime.state = LockTaskRuntimeStates.LOCKED

        val first = harness.recovery.recover("TEST")
        val second = harness.recovery.recover("TEST")

        assertTrue(first.verified)
        assertTrue(second.verified)
        assertFalse(harness.settings.current.enabled)
        assertFalse(harness.settings.current.recoveryRequired)
        assertEquals(LockTaskRuntimeStates.NONE, harness.runtime.state)
        assertNull(harness.settings.incident)
        assertNull(harness.settings.marker)
        assertTrue(harness.gateway.featureSubmissions.contains(LockTaskMasks.ALLOWED))
        assertTrue(harness.gateway.packageSubmissions.contains(emptySet()))
    }

    @Test
    fun `durable disable is written before release so a crash leaves a durable journal`() = runTest {
        val harness = Harness()
        harness.settings.seed(harness.armedSession())
        harness.seedBaseline()
        harness.runtime.state = LockTaskRuntimeStates.LOCKED
        // The session refuses to end, so recovery cannot complete.
        harness.session.stopLeavesStateUnchanged = true

        val result = harness.recovery.recover("TEST")

        assertFalse(result.verified)
        // Step renamed by repair R03: the release check now reports the observed state rather than
        // the narrower "stop" step, and treats unknown/PINNED as not released.
        assertEquals("VERIFY_SESSION_RELEASED", result.failedStep)
        assertTrue(result.durableDisableWritten)
        assertFalse("the session must not be reported as released", result.sessionReleased)
        // The journal survives the failure, so the next event will retry recovery.
        assertFalse(harness.settings.current.enabled)
        assertTrue(harness.settings.current.recoveryRequired)
        assertNotNull(harness.settings.incident)
        assertTrue(harness.settings.incident!!.unresolved)
    }

    @Test
    fun `storage failure does not suppress the best-effort policy release`() = runTest {
        val harness = Harness()
        harness.settings.seed(harness.armedSession())
        harness.seedBaseline()
        harness.runtime.state = LockTaskRuntimeStates.LOCKED
        harness.settings.failWrites = true
        harness.diagnostics.failWrites = true

        val result = harness.recovery.recover("TEST")

        // Release was still attempted even though both durable writes failed.
        assertTrue(harness.gateway.featureSubmissions.contains(LockTaskMasks.ALLOWED))
        assertTrue(harness.gateway.packageSubmissions.contains(emptySet()))
        assertEquals(LockTaskRuntimeStates.NONE, harness.runtime.state)
        assertTrue("the session itself was released", result.sessionReleased)

        // And the failure is reported honestly rather than being called repaired. The step is now
        // the atomic cleanup commit (repair R03), which is where the required writes converge.
        assertFalse(result.verified)
        assertEquals("PERSIST_FINAL_CLEANUP", result.failedStep)
        assertFalse(result.durableDisableWritten)
        assertFalse(result.durableCleanupCommitted)
        assertTrue(harness.inhibitor.isInhibited)
    }

    @Test
    fun `a failed release schedules at most three bounded release-only retries`() = runTest {
        val harness = Harness()
        harness.settings.seed(harness.armedSession())
        harness.seedBaseline()
        harness.runtime.state = LockTaskRuntimeStates.LOCKED
        harness.session.stopLeavesStateUnchanged = true

        val first = harness.recovery.recover("TEST")
        assertFalse(first.verified)
        assertEquals(1, first.attemptsRecorded)
        assertTrue("a release-only retry should be submitted", first.retryScheduled)
        assertEquals(1, harness.scheduling.recoveryRetryCount)

        harness.recovery.recover("TEST")
        val third = harness.recovery.recover("TEST")
        assertEquals(RecoveryIncident.MAX_ATTEMPTS, third.attemptsRecorded)
        assertFalse("automatic attempts are capped", third.retryScheduled)
        assertEquals(2, harness.scheduling.recoveryRetryCount)

        // The manual route is unaffected by the cap.
        harness.session.stopLeavesStateUnchanged = false
        val manual = harness.recovery.recover("MANUAL")
        assertTrue(manual.verified)
        assertNull(harness.settings.incident)
    }

    @Test
    fun `a retry alarm with a mismatched incident identity is a no-op`() = runTest {
        val harness = Harness()
        harness.settings.incident = RecoveryIncident("incident-1", 0L, 0, unresolved = true)

        val result = harness.recovery.handleRecoveryRetry("some-other-incident")

        assertNull(result)
        assertEquals("incident-1", harness.settings.incident!!.incidentId)
        assertTrue(harness.gateway.featureSubmissions.isEmpty())
    }

    @Test
    fun `a retry alarm with no unresolved cleanup is a no-op`() = runTest {
        val harness = Harness()
        harness.settings.incident = null

        val result = harness.recovery.handleRecoveryRetry("incident-1")

        assertNull(result)
        assertTrue(harness.gateway.featureSubmissions.isEmpty())
    }

    @Test
    fun `the retry alarm never enables protection and only attempts cleanup`() = runTest {
        val harness = Harness()
        harness.settings.seed(harness.armedSession().copy(enabled = false, recoveryRequired = true))
        harness.seedBaseline()
        harness.settings.incident = RecoveryIncident("incident-1", 0L, 1, unresolved = true)
        harness.runtime.state = LockTaskRuntimeStates.LOCKED

        val result = harness.recovery.handleRecoveryRetry("incident-1")

        assertNotNull(result)
        assertTrue(result!!.verified)
        assertFalse(harness.settings.current.enabled)
        assertFalse(
            "a retry must never apply a restrictive mask",
            harness.gateway.featureSubmissions.contains(LockTaskMasks.PROTECTED),
        )
        assertEquals(0, harness.session.startRequests)
    }

    @Test
    fun `recovery restores the captured baseline rather than the app's own mask`() = runTest {
        val harness = Harness()
        harness.settings.seed(harness.armedSession())
        harness.settings.baseline = com.example.shutdownprotection.data.PolicyBaseline(
            capturedAtEpochMillis = 0L,
            lockTaskPackages = setOf("com.example.original"),
            lockTaskFeatures = 0,
            applicationId = APP_ID,
            provenance = BaselineProvenance.CAPTURED_BEFORE_PREPARATION,
            lifecycle = BaselineLifecycleState.PREPARED,
        )
        harness.runtime.state = LockTaskRuntimeStates.LOCKED
        harness.gateway.features = LockTaskMasks.PROTECTED
        harness.gateway.packages = mutableSetOf(APP_ID)

        val result = harness.recovery.recover("TEST")

        assertTrue(result.verified)
        assertEquals(0, harness.gateway.features)
        assertEquals(setOf("com.example.original"), harness.gateway.packages)
    }

    @Test
    fun `malformed or foreign retired baseline is never accepted as clean restoration evidence`() = runTest {
        val invalidRetiredBaselines = listOf(
            com.example.shutdownprotection.data.PolicyBaseline(
                capturedAtEpochMillis = 0L,
                lockTaskPackages = emptySet(),
                lockTaskFeatures = 16,
                applicationId = "com.example.other",
                provenance = BaselineProvenance.CAPTURED_BEFORE_PREPARATION,
                lifecycle = BaselineLifecycleState.RESTORED,
            ),
            com.example.shutdownprotection.data.PolicyBaseline(
                capturedAtEpochMillis = -1L,
                lockTaskPackages = emptySet(),
                lockTaskFeatures = 16,
                applicationId = APP_ID,
                provenance = BaselineProvenance.CAPTURED_BEFORE_PREPARATION,
                lifecycle = BaselineLifecycleState.RESTORED,
            ),
        )

        invalidRetiredBaselines.forEach { invalid ->
            val harness = Harness()
            harness.settings.baseline = invalid
            harness.gateway.features = 16

            val result = harness.recovery.recover("INVALID_RETIRED_BASELINE")

            assertFalse("foreign or malformed retired data must not verify recovery", result.verified)
            assertFalse("it must never be called baseline restoration", result.baselineRestored)
            assertEquals("RESTORE_BASELINE_INVALID", result.failedStep)
            assertEquals("the ambiguous record must remain untouched", invalid, harness.settings.baseline)
            assertTrue("release exits must still be attempted", harness.session.stopRequests > 0)
            assertTrue(harness.gateway.featureSubmissions.contains(LockTaskMasks.ALLOWED))
            assertTrue(harness.inhibitor.isInhibited)
        }
    }

    @Test
    fun `recovery cancels every app-owned alarm including a prior retry`() = runTest {
        val harness = Harness()
        harness.settings.seed(harness.armedSession())
        harness.runtime.state = LockTaskRuntimeStates.LOCKED

        harness.recovery.recover("TEST")

        assertTrue(harness.scheduling.cancelAllCount >= 1)
        assertNull(harness.settings.receipt)
    }

    @Test
    fun `recovery works without a valid schedule or a device owner`() = runTest {
        val harness = Harness()
        // An invalid schedule and no ownership must not prevent release attempts.
        harness.settings.seed(ProtectionSettings(enabled = true, startMinuteOfDay = 120, endMinuteOfDay = 120))
        harness.gateway.owner = false
        harness.runtime.state = LockTaskRuntimeStates.LOCKED

        val result = harness.recovery.recover("TEST")

        // With no authority there is nothing to submit, but the durable disable still lands
        // and the session is still asked to stop.
        assertFalse(harness.settings.current.enabled)
        assertTrue(harness.session.stopRequests >= 1)
        assertTrue(harness.gateway.featureSubmissions.isEmpty())
        assertTrue(result.durableDisableWritten)
    }

    private companion object {
        const val APP_ID = "com.example.shutdownprotection"
    }
}
