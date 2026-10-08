package review3

import com.example.shutdownprotection.admin.*
import com.example.shutdownprotection.data.*
import com.example.shutdownprotection.fakes.*
import com.example.shutdownprotection.protection.*
import com.example.shutdownprotection.scheduling.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import java.time.*

class ThirdReviewRegressionTest {
    private class H(transform: (FakeSettingsRepository) -> SettingsRepository = { it }, timeout: Long = 5000) {
        val store = FakeSettingsRepository()
        val settings = transform(store)
        val diagnostics = FakeDiagnosticsRepository()
        val gateway = FakeDevicePolicyGateway()
        val runtime = FakeRuntimeLockTaskStateProvider()
        val session = FakeLockTaskSessionController(runtime)
        val scheduling = FakeSchedulingGateway()
        val inhibitor = RestrictionInhibitor()
        val policy = DevicePolicyController(gateway, runtime, ID)
        val recovery = RecoveryManager(settings, diagnostics, policy, scheduling, session, inhibitor, sleep = {})
        val coordinator = ProtectionCoordinator(
            ID, settings, diagnostics, policy,
            ScheduleCalculator(Clock.fixed(Instant.parse("2026-06-15T03:00:00Z"), ZoneOffset.UTC), { ZoneOffset.UTC }),
            scheduling, recovery, session, FakeEnvironmentStateProvider(), inhibitor,
            operationTimeoutMillis = timeout, sleep = {},
        )
        init { store.seed(ProtectionSettings(enabled = false, allowedPackages = setOf(ID))) }
        fun restricted() {
            runtime.state = LockTaskRuntimeStates.LOCKED
            gateway.features = LockTaskMasks.PROTECTED
            gateway.packages = mutableSetOf(ID)
        }
    }

    @Test fun clean_never_armed_restore_must_not_create_an_unrecoverable_incident() = runTest {
        val h = H()
        h.gateway.features = 16 // documented GLOBAL_ACTIONS-only initial feature mask
        assertNull(h.store.baseline)
        assertEquals(LockTaskRuntimeStates.NONE, h.runtime.state)
        val result = h.coordinator.disableAndRestore()
        println("clean_restore: state=${result.state} step=${result.recoveryIncompleteStep} mask=${h.gateway.features} runtime=${h.runtime.state} inhibited=${h.inhibitor.isInhibited} recoveryRequired=${h.store.current.recoveryRequired}")
        assertEquals("An already clean, never-armed device must not be turned into unresolved recovery by Restore", ProtectionState.DISARMED, result.state)
        assertFalse(h.inhibitor.isInhibited)
        assertFalse(h.store.current.recoveryRequired)
    }

    @Test fun temporary_activation_must_not_capture_an_existing_restriction_as_original() = runTest {
        val h = H()
        h.restricted()
        assertNull(h.store.baseline)
        val result = h.coordinator.activatePocSession()
        println("temporary_capture: state=${result.state} baselineMask=${h.store.baseline?.lockTaskFeatures} starts=${h.session.startRequests} runtime=${h.runtime.state}")
        assertEquals("A pre-existing restricted session with no baseline requires recovery, not a new temporary entry", 0, h.session.startRequests)
        assertTrue(h.store.baseline?.lockTaskFeatures != LockTaskMasks.PROTECTED)
    }

    @Test fun unreadable_policy_result_must_not_be_treated_as_an_armed_success() = runTest {
        val h = H()
        h.store.seed(h.store.current.copy(enabled = true))
        h.store.baseline = trustedBaseline(0, emptySet(), 16)
        h.restricted()
        val result = h.coordinator.handlePolicyChanged("lockTask", null)
        println("null_callback: state=${result.state} enabled=${h.store.current.enabled} mask=${h.gateway.features} runtime=${h.runtime.state} stops=${h.session.stopRequests}")
        assertNotEquals("Unknown callback result is not evidence of successful restrictive policy", ProtectionState.ARMED_POWER_MENU_RESTRICTED, result.state)
        assertFalse(h.store.current.enabled)
    }

    @Test fun lock_wait_fallback_bookkeeping_must_have_its_own_finite_budget() = runTest {
        val h = H(timeout = 50, transform = { base ->
            object : SettingsRepository by base {
                var baselineReads = 0
                override suspend fun readBaseline(): PolicyBaseline? {
                    baselineReads++
                    if (baselineReads == 1) delay(100000)
                    return base.readBaseline()
                }
                override suspend fun readRecoveryIncident(): RecoveryIncident? {
                    delay(100000)
                    return base.readRecoveryIncident()
                }
            }
        })
        h.store.baseline = trustedBaseline(0, emptySet(), 16)
        h.restricted()
        val holder = launch(start = CoroutineStart.UNDISPATCHED) { h.coordinator.arm() }
        // Reconcile's advertised total is 3,000 + 50 + 2,000 = 5,050 ms; allow 8,000.
        val result = withTimeoutOrNull(8000) { h.coordinator.reconcile(ProtectionTrigger.END_ALARM) }
        holder.cancelAndJoin()
        println("fallback_budget: returned=${result?.state} mask=${h.gateway.features} runtime=${h.runtime.state} inhibited=${h.inhibitor.isInhibited} retryCount=${h.scheduling.recoveryRetryCount}")
        assertNotNull("Fallback incident persistence is outside the coordinator deadline and must not wait indefinitely", result)
    }

    @Test fun valid_original_baseline_must_not_be_rejected_only_because_it_matches_the_protected_mask() = runTest {
        val h = H()
        // Represents a trusted snapshot captured BEFORE this app's session, not a reconstructed one.
        val original = trustedBaseline(0, setOf(ID), LockTaskMasks.PROTECTED)
        assertTrue(original.isValidFor(ID))
        h.store.baseline = original
        h.gateway.features = LockTaskMasks.PROTECTED
        h.gateway.packages = mutableSetOf(ID)
        assertEquals(LockTaskRuntimeStates.NONE, h.runtime.state)
        val result = h.recovery.recover("VALID_ORIGINAL")
        println("valid_original: verified=${result.verified} step=${result.failedStep} runtime=${h.runtime.state} inhibited=${h.inhibitor.isInhibited}")
        assertTrue("Provenance/lifecycle must distinguish valid original and reconstructed snapshot; value equality alone cannot", result.verified)
    }

    @Test fun resume_with_saved_baseline_and_revoked_alarm_access_must_release_existing_session() = runTest {
        val h = H()
        h.store.seed(h.store.current.copy(enabled = true))
        h.store.baseline = trustedBaseline(0, emptySet(), 16)
        h.restricted()
        h.scheduling.exactCapability = false
        val result = h.coordinator.arm()
        println("resume_guard: state=${result.state} mask=${h.gateway.features} runtime=${h.runtime.state} starts=${h.session.startRequests} stops=${h.session.stopRequests}")
        assertEquals("Resume must release the existing session before returning an alarm-capability refusal", LockTaskRuntimeStates.NONE, h.runtime.state)
        assertEquals(16, h.gateway.features)
        assertEquals(0, h.session.startRequests)
        assertFalse(h.store.current.enabled)
    }

    companion object {
        const val ID = "com.example.shutdownprotection"

        private fun trustedBaseline(capturedAt: Long, packages: Set<String>, features: Int) =
            PolicyBaseline(
                capturedAt,
                packages,
                features,
                ID,
                BaselineProvenance.CAPTURED_BEFORE_PREPARATION,
                BaselineLifecycleState.PREPARED,
            )
    }
}
