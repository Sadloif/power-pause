package review2

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

class SecondReviewRegressionTest {
    private class H(
        transform: (FakeSettingsRepository) -> SettingsRepository = { it },
        transformScheduling: (FakeSchedulingGateway) -> SchedulingGateway = { it },
        operationTimeout: Long = 5000,
        armTimeout: Long = 12000,
    ) {
        val store = FakeSettingsRepository()
        val settings = transform(store)
        val diagnostics = FakeDiagnosticsRepository()
        val gateway = FakeDevicePolicyGateway()
        val runtime = FakeRuntimeLockTaskStateProvider()
        val session = FakeLockTaskSessionController(runtime)
        val rawScheduling = FakeSchedulingGateway()
        val scheduling = transformScheduling(rawScheduling)
        val inhibitor = RestrictionInhibitor()
        val policy = DevicePolicyController(gateway, runtime, ID)
        val recovery = RecoveryManager(settings, diagnostics, policy, scheduling, session, inhibitor, sleep = {})
        val coordinator = ProtectionCoordinator(
            ID, settings, diagnostics, policy,
            ScheduleCalculator(Clock.fixed(Instant.parse("2026-06-15T03:00:00Z"), ZoneOffset.UTC), { ZoneOffset.UTC }),
            scheduling, recovery, session, FakeEnvironmentStateProvider(), inhibitor,
            operationTimeoutMillis = operationTimeout, armTimeoutMillis = armTimeout, sleep = {},
        )
        init { store.seed(ProtectionSettings(enabled = true, allowedPackages = setOf(ID))) }
        fun seedBaseline() { store.baseline = PolicyBaseline(0, emptySet(), 0, ID) }
        fun restricted() {
            runtime.state = LockTaskRuntimeStates.LOCKED
            gateway.features = LockTaskMasks.PROTECTED
            gateway.packages = mutableSetOf(ID)
        }
    }

    @Test fun failed_permissive_setter_must_still_attempt_independent_session_exit() = runTest {
        val h = H()
        h.seedBaseline()
        h.restricted()
        h.gateway.throwOnSetFeatures = true
        val result = h.coordinator.disableAndRestore()
        println("setter_failed: state=${result.state} mask=${h.gateway.features} runtime=${h.runtime.state} stopRequests=${h.session.stopRequests} packageRequests=${h.gateway.packageSubmissions.size} cancelAll=${h.rawScheduling.cancelAllCount}")
        assertTrue("Failure of the feature setter must not skip the independent session-exit path", h.session.stopRequests > 0)
        assertEquals("The available activity-owned exit should release this simulated session", LockTaskRuntimeStates.NONE, h.runtime.state)
    }

    @Test fun suspended_disable_storage_must_not_block_both_recovery_and_timeout_release() = runTest {
        val h = H(armTimeout = 50, transform = { base ->
            object : SettingsRepository by base {
                override suspend fun editSettings(transform: (ProtectionSettings) -> ProtectionSettings): SettingsWriteResult {
                    delay(100000)
                    return base.editSettings(transform)
                }
            }
        })
        h.seedBaseline()
        h.restricted()
        // Exceeds the 50 ms operation plus the 2,000 ms cleanup budget by a generous margin.
        val result = withTimeoutOrNull(5000) { h.coordinator.disableAndRestore() }
        println("storage_stall: returned=${result?.state} mask=${h.gateway.features} runtime=${h.runtime.state} featureRequests=${h.gateway.featureSubmissions.size} stopRequests=${h.session.stopRequests}")
        assertTrue("A suspended durable write cannot prevent all policy/session release attempts", h.gateway.featureSubmissions.isNotEmpty() || h.session.stopRequests > 0)
        assertEquals(LockTaskRuntimeStates.NONE, h.runtime.state)
    }

    @Test fun lock_wait_release_retry_must_reference_a_real_recovery_incident() = runTest {
        var submittedIncident: String? = null
        val h = H(operationTimeout = 50, transform = { base ->
            object : SettingsRepository by base {
                var reads = 0
                override suspend fun readSettings(): SettingsReadResult {
                    reads++
                    if (reads == 1) delay(100000)
                    return base.readSettings()
                }
            }
        }, transformScheduling = { base ->
            object : SchedulingGateway by base {
                override fun installRecoveryRetry(incidentId: String, triggerAt: Instant): Boolean {
                    submittedIncident = incidentId
                    return base.installRecoveryRetry(incidentId, triggerAt)
                }
            }
        })
        h.seedBaseline()
        h.restricted()
        val holder = launch(start = CoroutineStart.UNDISPATCHED) { h.coordinator.arm() }
        val result = h.coordinator.reconcile(ProtectionTrigger.END_ALARM)
        holder.cancelAndJoin()
        assertEquals("Ensure this drives the intended timeout path", "LOCK_WAIT_TIMEOUT", result.recoveryIncompleteStep)
        assertNotNull("Ensure a retry was actually submitted", submittedIncident)
        val retry = h.coordinator.handleRecoveryRetry(submittedIncident)
        println("lock_wait_retry: submitted=$submittedIncident durableIncident=${h.store.incident} returned=${retry.state} detail=${retry.detail} mask=${h.gateway.features} runtime=${h.runtime.state}")
        assertEquals("A submitted release retry must actually invoke release rather than be ignored", LockTaskRuntimeStates.NONE, h.runtime.state)
    }

    @Test fun disabled_intent_and_missing_exact_permission_must_still_release_existing_policy() = runTest {
        val h = H()
        h.seedBaseline()
        h.restricted()
        h.store.seed(h.store.current.copy(enabled = false, recoveryRequired = false))
        h.rawScheduling.exactCapability = false
        val result = h.coordinator.reconcile(ProtectionTrigger.EXACT_ACCESS_GRANTED)
        println("disabled_no_exact: state=${result.state} mask=${h.gateway.features} runtime=${h.runtime.state} stopRequests=${h.session.stopRequests}")
        assertEquals("Alarms permission is not required for releasing an existing app restriction", LockTaskRuntimeStates.NONE, h.runtime.state)
    }

    @Test fun policy_success_refresh_must_not_report_a_live_restricted_temporary_test_as_disarmed() = runTest {
        val h = H()
        h.store.seed(h.store.current.copy(enabled = false))
        h.coordinator.activatePocSession()
        val restricted = h.coordinator.setPocOverride(PocOverride.FORCE_RESTRICTED)
        assertEquals("Ensure the temporary session really was restricted", ProtectionState.ARMED_POWER_MENU_RESTRICTED, restricted.state)
        assertEquals(LockTaskMasks.PROTECTED, h.gateway.features)
        val refreshed = h.coordinator.refreshObservation()
        println("temporary_refresh: state=${refreshed.state} dailyEnabled=${h.store.current.enabled} mask=${h.gateway.features} runtime=${h.runtime.state} marker=${h.store.marker != null}")
        assertNotEquals("Policy-success callbacks must preserve truthful temporary-session status", ProtectionState.DISARMED, refreshed.state)
    }

    @Test fun relevant_policy_changed_failure_must_use_failure_recovery_not_matching_readback() = runTest {
        val h = H()
        h.seedBaseline()
        h.restricted()
        val result = h.coordinator.handlePolicyChanged("lockTask", 1)
        println("changed_failure: state=${result.state} enabled=${h.store.current.enabled} mask=${h.gateway.features} runtime=${h.runtime.state} stopRequests=${h.session.stopRequests}")
        assertFalse("An explicit relevant policy failure cannot be silently treated as an armed success", h.store.current.enabled)
        assertEquals(LockTaskRuntimeStates.NONE, h.runtime.state)
    }

    @Test fun missing_original_baseline_during_an_existing_restriction_must_not_recapture_the_restriction() = runTest {
        val h = H()
        h.restricted()
        assertNull(h.store.baseline)
        val result = h.coordinator.arm()
        println("missing_original: state=${result.state} savedMask=${h.store.baseline?.lockTaskFeatures} savedPackages=${h.store.baseline?.lockTaskPackages} runtime=${h.runtime.state} startRequests=${h.session.startRequests}")
        assertEquals("An existing restricted session with no original baseline must be recovered, not armed as a new session", 0, h.session.startRequests)
        assertTrue("The app's own restriction must not be saved as the original policy", h.store.baseline?.lockTaskFeatures != LockTaskMasks.PROTECTED)
    }

    companion object { const val ID = "com.example.shutdownprotection" }
}
