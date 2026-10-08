package com.example.shutdownprotection.repair

import android.app.admin.PolicyUpdateResult
import com.example.shutdownprotection.admin.DevicePolicyController
import com.example.shutdownprotection.admin.DevicePolicyGateway
import com.example.shutdownprotection.admin.LockTaskMasks
import com.example.shutdownprotection.admin.LockTaskRuntimeStates
import com.example.shutdownprotection.data.DiagnosticsRepository
import com.example.shutdownprotection.data.BaselineLifecycleState
import com.example.shutdownprotection.data.BaselineProvenance
import com.example.shutdownprotection.data.PolicyBaseline
import com.example.shutdownprotection.data.ProtectionSettings
import com.example.shutdownprotection.data.ScheduleReceipt
import com.example.shutdownprotection.data.SettingsReadResult
import com.example.shutdownprotection.data.SettingsRepository
import com.example.shutdownprotection.data.SettingsWriteResult
import com.example.shutdownprotection.data.TemporaryTestMarker
import com.example.shutdownprotection.fakes.FakeDevicePolicyGateway
import com.example.shutdownprotection.fakes.FakeDiagnosticsRepository
import com.example.shutdownprotection.fakes.FakeEnvironmentStateProvider
import com.example.shutdownprotection.fakes.FakeLockTaskSessionController
import com.example.shutdownprotection.fakes.FakeRuntimeLockTaskStateProvider
import com.example.shutdownprotection.fakes.FakeSchedulingGateway
import com.example.shutdownprotection.fakes.FakeSettingsRepository
import com.example.shutdownprotection.protection.PocOverride
import com.example.shutdownprotection.protection.ProtectionCoordinator
import com.example.shutdownprotection.protection.ProtectionState
import com.example.shutdownprotection.protection.ProtectionTrigger
import com.example.shutdownprotection.protection.RecoveryManager
import com.example.shutdownprotection.protection.RestrictionInhibitor
import com.example.shutdownprotection.scheduling.AlarmInstallResult
import com.example.shutdownprotection.scheduling.ScheduleCalculator
import com.example.shutdownprotection.scheduling.SchedulingGateway
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

/**
 * Repair-safety regressions (guide sections R01-R09).
 *
 * Every case drives the real coordinator / recovery implementation through its injected seams
 * and asserts *observable* behaviour: the mask that actually reached the policy gateway, the
 * runtime lock-task state, the durable settings that were written, the injected safety brake, and
 * the order and number of submissions. Status strings are never the only evidence.
 *
 * Three harness facts worth remembering while reading these tests:
 *
 *  - **A real armed session always has a stored baseline** (repair R02). Any test that models an
 *    existing managed session seeds one via [H.seedBaseline]; without it the honest recovery
 *    outcome would be `RESTORE_BASELINE_UNAVAILABLE`.
 *  - Time is fully injected (`Clock.fixed` + a fixed zone) and `sleep = {}` is passed to both the
 *    coordinator and the recovery manager, so nothing waits. The only real suspension points are
 *    the deliberate `delay(...)` injections that model a stalled write or a suspended logger.
 *  - **Interleaving is created explicitly.** The coordinator serializes every operation on one
 *    mutex, so two `async` bodies with no suspension between them run strictly one after the
 *    other and prove nothing about concurrency. The interleaving tests therefore park a real
 *    operation *inside* its critical section with a gated `SettingsRepository`, then drive the
 *    virtual clock with `runCurrent()` / `advanceUntilIdle()` so a second operation is genuinely
 *    queued or in flight at the same time. Each of those tests also asserts the interleaving
 *    itself happened (a deadline that expired mid-flight), so the test cannot silently degrade
 *    back into a sequential one.
 */
@OptIn(ExperimentalCoroutinesApi::class) // runCurrent / advanceUntilIdle
class RepairSafetyTest {

    // ------------------------------------------------------------------
    // Harness
    // ------------------------------------------------------------------

    private class H(
        val now: Instant = INSIDE_WINDOW,
        val timeout: Long = 5_000L,
        val armTimeout: Long = 12_000L,
        val transformSettings: (FakeSettingsRepository) -> SettingsRepository = { it },
        val transformDiagnostics: (FakeDiagnosticsRepository) -> DiagnosticsRepository = { it },
        val transformGateway: (FakeDevicePolicyGateway) -> DevicePolicyGateway = { it },
        val transformScheduling: (FakeSchedulingGateway) -> SchedulingGateway = { it },
    ) {
        /**
         * The coordinator/recovery wall clock. Deliberately mutable: a test that proves a refused
         * operation left a fixed expiry untouched has to advance the clock between the two calls,
         * otherwise a re-computed expiry would coincidentally equal the original. 0 by default,
         * which is what every other test relies on.
         */
        var nowMillis: Long = 0L

        /** The raw fakes are kept public so a test can seed and inspect them directly. */
        val settingsStore = FakeSettingsRepository()
        val settings: SettingsRepository = transformSettings(settingsStore)

        val diagnosticsStore = FakeDiagnosticsRepository()
        val diagnostics: DiagnosticsRepository = transformDiagnostics(diagnosticsStore)

        val policyGateway = FakeDevicePolicyGateway()
        val gateway: DevicePolicyGateway = transformGateway(policyGateway)

        val runtime = FakeRuntimeLockTaskStateProvider()
        val session = FakeLockTaskSessionController(runtime)

        val schedulingGateway = FakeSchedulingGateway()
        val scheduling: SchedulingGateway = transformScheduling(schedulingGateway)

        val environment = FakeEnvironmentStateProvider()
        val inhibitor = RestrictionInhibitor()

        val policy = DevicePolicyController(gateway, runtime, ID, clockMillis = { 0L })

        val recovery = RecoveryManager(
            settings, diagnostics, policy, scheduling, session, inhibitor,
            clockMillis = { nowMillis },
            newIncidentId = { "incident-1" },
            sleep = {},
        )

        val coordinator = ProtectionCoordinator(
            ID, settings, diagnostics, policy,
            ScheduleCalculator(Clock.fixed(now, ZoneOffset.UTC), { ZoneOffset.UTC }),
            scheduling, recovery, session, environment, inhibitor,
            clockMillis = { nowMillis },
            operationTimeoutMillis = timeout,
            armTimeoutMillis = armTimeout,
            sleep = {},
        )

        fun seedSettings(
            enabled: Boolean = true,
            start: Int = ProtectionSettings.DEFAULT_START_MINUTE,
            end: Int = ProtectionSettings.DEFAULT_END_MINUTE,
            revision: Long = 1L,
            allowed: Set<String> = setOf(ID),
            recoveryRequired: Boolean = false,
        ) {
            settingsStore.seed(
                ProtectionSettings(
                    enabled = enabled,
                    startMinuteOfDay = start,
                    endMinuteOfDay = end,
                    revision = revision,
                    allowedPackages = allowed,
                    recoveryRequired = recoveryRequired,
                ),
            )
        }

        /**
         * The stored original policy a real arm transaction captures (repair R02). Recovery can
         * only confirm restoration against a valid baseline, so every test that models an existing
         * managed session must have one.
         */
        fun seedBaseline(
            features: Int = 0,
            packages: Set<String> = emptySet(),
            applicationId: String = ID,
            provenance: BaselineProvenance = BaselineProvenance.CAPTURED_BEFORE_PREPARATION,
            lifecycle: BaselineLifecycleState = BaselineLifecycleState.PREPARED,
        ) {
            settingsStore.baseline = PolicyBaseline(
                capturedAtEpochMillis = 0L,
                lockTaskPackages = packages,
                lockTaskFeatures = features,
                applicationId = applicationId,
                provenance = provenance,
                lifecycle = lifecycle,
            )
        }

        /** An existing managed session: real LOCKED runtime plus an app-imposed PROTECTED mask. */
        fun restricted() {
            runtime.state = LockTaskRuntimeStates.LOCKED
            policyGateway.features = LockTaskMasks.PROTECTED
            policyGateway.packages = mutableSetOf(ID)
        }
    }

    // ------------------------------------------------------------------
    // R01 - release ordering and timeout safety
    // ------------------------------------------------------------------

    @Test
    fun permissive_release_is_confirmed_before_the_next_daily_plan_is_installed() = runTest {
        val log = mutableListOf<String>()
        val h = H(
            now = Instant.parse("2026-06-15T05:00:00Z"),
            transformGateway = { base -> loggingGateway(base, log) },
            transformScheduling = { base -> loggingScheduling(base, log) },
        )
        h.seedSettings(enabled = true)
        h.seedBaseline()
        h.restricted()

        val status = h.coordinator.reconcile(ProtectionTrigger.END_ALARM)

        assertTrue(
            "the permissive mask must actually be submitted, not merely intended",
            h.policyGateway.featureSubmissions.contains(LockTaskMasks.ALLOWED),
        )
        assertEquals(
            "the permissive mask must be confirmed by readback",
            LockTaskMasks.ALLOWED,
            h.policyGateway.features,
        )
        assertEquals(
            "the replacement plan installed at the end boundary is tomorrow's interval end",
            Instant.parse("2026-06-16T05:00:00Z"),
            h.schedulingGateway.lastInstalledEnd,
        )

        val releaseIndex = log.indexOfFirst { it == "applyFeatures:${LockTaskMasks.ALLOWED}" }
        val installIndex = log.indexOfFirst { it == "installPlan" }
        assertTrue(
            "both the release and the plan install must have been observed",
            releaseIndex >= 0 && installIndex >= 0,
        )
        assertTrue(
            "the confirmed release must happen BEFORE tomorrow's plan is installed " +
                "(release=$releaseIndex install=$installIndex)",
            releaseIndex < installIndex,
        )
        assertEquals(ProtectionState.ARMED_POWER_MENU_ALLOWED, status.state)
    }

    @Test
    fun overnight_interval_end_releases_before_advancing_the_plan() = runTest {
        val log = mutableListOf<String>()
        val h = H(
            now = Instant.parse("2026-06-15T06:00:00Z"),
            transformGateway = { base -> loggingGateway(base, log) },
            transformScheduling = { base -> loggingScheduling(base, log) },
        )
        h.seedSettings(enabled = true, start = 23 * 60, end = 6 * 60)
        h.seedBaseline()
        h.restricted()

        val status = h.coordinator.reconcile(ProtectionTrigger.END_ALARM)

        assertTrue(
            "the permissive mask must actually be submitted",
            h.policyGateway.featureSubmissions.contains(LockTaskMasks.ALLOWED),
        )
        assertEquals(LockTaskMasks.ALLOWED, h.policyGateway.features)
        assertEquals(
            "an overnight interval's next end is tomorrow's 06:00",
            Instant.parse("2026-06-16T06:00:00Z"),
            h.schedulingGateway.lastInstalledEnd,
        )

        val releaseIndex = log.indexOfFirst { it == "applyFeatures:${LockTaskMasks.ALLOWED}" }
        val installIndex = log.indexOfFirst { it == "installPlan" }
        assertTrue(
            "both the release and the plan install must have been observed",
            releaseIndex >= 0 && installIndex >= 0,
        )
        assertTrue(
            "an overnight end must release before the plan advances " +
                "(release=$releaseIndex install=$installIndex)",
            releaseIndex < installIndex,
        )
        assertEquals(ProtectionState.ARMED_POWER_MENU_ALLOWED, status.state)
    }

    @Test
    fun a_stalled_receipt_write_after_release_does_not_re_restrict() = runTest {
        val h = H(
            now = Instant.parse("2026-06-15T05:00:00Z"),
            timeout = 50L,
            transformSettings = { base ->
                object : SettingsRepository by base {
                    override suspend fun writeScheduleReceipt(receipt: ScheduleReceipt?): SettingsWriteResult {
                        delay(1_000)
                        return base.writeScheduleReceipt(receipt)
                    }
                }
            },
        )
        h.seedSettings(enabled = true)
        h.seedBaseline()
        h.restricted()

        h.coordinator.reconcile(ProtectionTrigger.END_ALARM)

        assertNotEquals(
            "a receipt write that stalls past the deadline must never leave the device restricted",
            LockTaskMasks.PROTECTED,
            h.policyGateway.features,
        )
        assertEquals(
            "the timeout handler must release the managed session",
            LockTaskRuntimeStates.NONE,
            h.runtime.state,
        )
        assertTrue(
            "the bounded release attempt must have submitted the permissive mask",
            h.policyGateway.featureSubmissions.contains(LockTaskMasks.ALLOWED),
        )
    }

    @Test
    fun a_queued_stale_start_cannot_undo_disable_while_recovery_storage_write_stalls() = runTest {
        // The first recovery settings write is deliberately parked while the START_ALARM call is
        // queued behind the disable. RecoveryManager's own 140ms write cap must release that
        // writer and let verified cleanup finish; this test is about the actual queued stale event
        // observing the resulting disabled state, not about the coordinator's separate timeout
        // path (covered below by concurrent_disable_and_start_converge_to_released).
        var recoveryWriteEntered = false
        val h = H(
            armTimeout = 1_000L,
            transformSettings = { base ->
                object : SettingsRepository by base {
                    private var edits = 0
                    override suspend fun editSettings(
                        transform: (ProtectionSettings) -> ProtectionSettings,
                    ): SettingsWriteResult {
                        edits++
                        if (edits == 1) {
                            recoveryWriteEntered = true
                            delay(5_000)
                        }
                        return base.editSettings(transform)
                    }
                }
            },
        )
        h.seedSettings(enabled = true)
        h.seedBaseline()
        h.restricted()

        val disable = async { h.coordinator.disableAndRestore() }
        runCurrent() // the disable takes the lock and parks inside its critical section
        assertTrue("the bounded store-write injection must actually be reached", recoveryWriteEntered)
        val staleStart = async { h.coordinator.reconcile(ProtectionTrigger.START_ALARM) }
        runCurrent() // the stale start is now genuinely queued behind the in-flight disable

        advanceUntilIdle()
        val disableStatus = disable.await()
        val status = staleStart.await()

        assertEquals("the bounded write must not prevent verified disable cleanup", ProtectionState.DISARMED, disableStatus.state)
        assertEquals("the queued stale request must use the newer disabled intent", ProtectionState.DISARMED, status.state)

        assertFalse("the durable intent must stay disabled", h.settingsStore.current.enabled)
        assertEquals(LockTaskRuntimeStates.NONE, h.runtime.state)
        assertNotEquals(LockTaskMasks.PROTECTED, h.policyGateway.features)
        assertFalse(
            "neither the disable nor the stale start may submit a restrictive mask " +
                "(submissions=${h.policyGateway.featureSubmissions})",
            h.policyGateway.featureSubmissions.contains(LockTaskMasks.PROTECTED),
        )
        assertFalse(h.settingsStore.current.recoveryRequired)
        assertNull(h.settingsStore.incident)
    }

    // ------------------------------------------------------------------
    // R02 - baseline and journal persistence
    // ------------------------------------------------------------------

    @Test
    fun alarm_receipt_write_failure_after_preparation_blocks_new_restriction() = runTest {
        val h = H(
            transformSettings = { base ->
                object : SettingsRepository by base {
                    override suspend fun writeScheduleReceipt(receipt: ScheduleReceipt?): SettingsWriteResult =
                        if (receipt == null) {
                            base.writeScheduleReceipt(null)
                        } else {
                            SettingsWriteResult.Failure(
                                IllegalStateException("simulated receipt write failure"),
                            )
                        }
                }
            },
        )
        h.seedSettings(enabled = false)

        val status = h.coordinator.arm()

        assertNotNull(
            "the failure must happen after the baseline/preparation was written",
            h.settingsStore.baseline,
        )
        assertNotEquals(
            "a plan whose receipt cannot be saved must never be followed by restriction",
            ProtectionState.ARMED_POWER_MENU_RESTRICTED,
            status.state,
        )
        assertEquals(LockTaskRuntimeStates.NONE, h.runtime.state)
        assertEquals("the managed session must never be entered", 0, h.session.startRequests)
        assertFalse(
            "no restrictive mask may be applied when the submitted plan is not recorded",
            h.policyGateway.featureSubmissions.contains(LockTaskMasks.PROTECTED),
        )
    }

    @Test
    fun a_failed_session_preparation_leaves_both_the_baseline_and_the_settings_unchanged() = runTest {
        // R02: the baseline and the preparation journal are ONE durable transaction, so a failed
        // preparation must leave *both* untouched. This is asserted through the fake's dedicated
        // failure knob (`failPrepareSession`), which the independent audit found dead: without it
        // the "no partial durable pair" requirement could not be expressed at all.
        val h = H()
        h.seedSettings(enabled = false, revision = 4L)
        val settingsBefore = h.settingsStore.current
        h.settingsStore.failPrepareSession = true

        val status = h.coordinator.arm()

        assertEquals(ProtectionState.CONFIGURATION_ERROR, status.state)
        assertNull("no partial durable baseline may be written", h.settingsStore.baseline)
        assertEquals(
            "the preparation journal must not be written either",
            settingsBefore,
            h.settingsStore.current,
        )
        assertTrue(
            "no policy may be submitted without a durably saved baseline",
            h.policyGateway.featureSubmissions.isEmpty(),
        )
        assertEquals("no managed session may be entered", 0, h.session.startRequests)
    }

    @Test
    fun arming_refuses_an_ambiguous_legacy_baseline_without_overwriting_it() = runTest {
        // This fixture models a persisted baseline from before provenance existed. It cannot
        // demonstrate a second successful session; the separate lifecycle regression does that.
        // The contract here is that ambiguous data remains preserved and cannot authorize entry.
        val h = H()
        h.seedSettings(enabled = false)
        h.seedBaseline(
            features = 7,
            packages = setOf("old.app"),
            provenance = BaselineProvenance.LEGACY_UNVERIFIED,
            lifecycle = BaselineLifecycleState.LEGACY_UNKNOWN,
        )

        val status = h.coordinator.arm()

        assertEquals(ProtectionState.RECOVERY_FAILED, status.state)
        assertEquals("ambiguous data must never authorize Lock Task entry", 0, h.session.startRequests)
        val stored = h.settingsStore.baseline
        assertNotNull("arming must retain a baseline", stored)
        val baseline = stored!!
        assertEquals(
            "the ambiguous feature value must remain unchanged",
            7,
            baseline.lockTaskFeatures,
        )
        assertEquals(
            "the ambiguous package list must remain unchanged",
            setOf("old.app"),
            baseline.lockTaskPackages,
        )
        assertEquals(ID, baseline.applicationId)
        assertEquals(BaselineProvenance.LEGACY_UNVERIFIED, baseline.provenance)
    }

    @Test
    fun a_wrong_installation_baseline_triggers_release_and_reports_restoration_unknown() = runTest {
        val h = H()
        h.seedSettings(enabled = true, recoveryRequired = true)
        h.seedBaseline(applicationId = "com.other.app")
        h.restricted()

        val status = h.coordinator.reconcile(ProtectionTrigger.START_ALARM)

        assertNotEquals(
            "a baseline belonging to another installation must never be reported as restored",
            ProtectionState.DISARMED,
            status.state,
        )
        assertNotNull(
            "the unresolved restoration step must be reported",
            status.recoveryIncompleteStep,
        )
        assertNotEquals(
            "no restrictive mask may survive the release attempt",
            LockTaskMasks.PROTECTED,
            h.policyGateway.features,
        )
        assertEquals(LockTaskRuntimeStates.NONE, h.runtime.state)
    }

    // ------------------------------------------------------------------
    // R03 - recovery verification
    // ------------------------------------------------------------------

    @Test
    fun recovery_reports_incomplete_when_baseline_package_restoration_is_ignored() = runTest {
        val h = H()
        h.seedSettings(enabled = true)
        h.seedBaseline(features = 0, packages = setOf("com.example.original"))
        h.restricted()
        h.policyGateway.ignorePackageWrites = true

        val result = h.recovery.recover("TEST")

        assertFalse(
            "a package setter that never takes effect is not a restored baseline",
            result.verified,
        )
        assertFalse(result.baselineRestored)
    }

    @Test
    fun recovery_reports_incomplete_when_a_readback_is_unknown() = runTest {
        val h = H(transformGateway = { base -> unknownBaselineReadbackGateway(base) })
        h.seedSettings(enabled = true)
        h.seedBaseline(features = 0, packages = emptySet())
        h.restricted()

        val result = h.recovery.recover("TEST")

        assertFalse(
            "an unknown readback must never be reported as verified restoration",
            result.verified,
        )
        assertFalse(result.baselineRestored)
    }

    @Test
    fun recovery_reports_incomplete_when_the_final_cleanup_commit_fails() = runTest {
        // DELETED AFTER INDEPENDENT AUDIT: `a_second_recovery_after_a_successful_one_is_safe_and_
        // idempotent` duplicated `RecoveryManagerTest.recovery is idempotent and reaches the same
        // verified end state twice` and passed on the pre-repair code, so it proved nothing.
        //
        // Replaced with the R03 case the audit found uncovered: the final atomic cleanup commit is
        // the step that makes recovery verified, and its failure must leave the durable journal,
        // the inhibitor and the retry evidence in the honest unverified state. This uses the
        // `failCommitCleanup` knob, which no test previously exercised.
        val h = H()
        h.seedSettings(enabled = true)
        h.seedBaseline()
        h.restricted()
        h.settingsStore.failCommitCleanup = true

        val result = h.recovery.recover("TEST")

        assertFalse("a failed final cleanup commit is not verified recovery", result.verified)
        assertEquals("PERSIST_FINAL_CLEANUP", result.failedStep)
        assertTrue("the release itself was confirmed", result.sessionReleased)
        assertTrue("the baseline restoration was confirmed", result.baselineRestored)
        assertFalse("the durable cleanup is unverified", result.durableCleanupCommitted)
        assertEquals(LockTaskRuntimeStates.NONE, h.runtime.state)
        assertNotEquals(LockTaskMasks.PROTECTED, h.policyGateway.features)
        assertTrue(
            "the durable recovery journal must survive a failed cleanup commit",
            h.settingsStore.current.recoveryRequired,
        )
        assertFalse(h.settingsStore.current.enabled)
        assertTrue("the safety brake must stay engaged", h.inhibitor.isInhibited)
        assertNotNull("the unresolved incident must be recorded", h.settingsStore.incident)
        assertEquals(
            "a release-only retry must be scheduled for the unresolved cleanup",
            1,
            h.schedulingGateway.recoveryRetryCount,
        )
    }

    // ------------------------------------------------------------------
    // R04 - invalid stored configuration
    // ------------------------------------------------------------------

    @Test
    fun out_of_range_stored_times_release_an_existing_session() = runTest {
        val h = H()
        h.seedSettings(enabled = true)
        h.seedBaseline()
        h.restricted()
        h.settingsStore.seed(h.settingsStore.current.copy(startMinuteOfDay = 1500))

        val status = h.coordinator.reconcile(ProtectionTrigger.END_ALARM)

        assertEquals(ProtectionState.CONFIGURATION_ERROR, status.state)
        assertEquals(
            "invalid stored times must release the existing session",
            LockTaskRuntimeStates.NONE,
            h.runtime.state,
        )
        assertNotEquals(LockTaskMasks.PROTECTED, h.policyGateway.features)
        // The previous trailing `assertTrue(step != null || features != PROTECTED)` was
        // tautological: the assertNotEquals directly above already guaranteed its second disjunct,
        // so it could never fail and it silently excused a release that never completed. These
        // hard assertions replace it.
        assertNull(
            "the release must be verified, not merely attempted",
            status.recoveryIncompleteStep,
        )
        assertFalse("the durable intent must not stay enabled", h.settingsStore.current.enabled)
        assertFalse(
            "the recovery journal must be resolved by the verified cleanup",
            h.settingsStore.current.recoveryRequired,
        )
    }

    @Test
    fun own_package_missing_from_stored_allowlist_releases_an_existing_session() = runTest {
        val h = H()
        h.seedSettings(enabled = true, allowed = setOf("com.example.other"))
        h.seedBaseline()
        h.restricted()

        val status = h.coordinator.reconcile(ProtectionTrigger.START_ALARM)

        assertEquals(ProtectionState.CONFIGURATION_ERROR, status.state)
        assertEquals(
            "an allowlist missing this app must release the existing session",
            LockTaskRuntimeStates.NONE,
            h.runtime.state,
        )
        assertNotEquals(LockTaskMasks.PROTECTED, h.policyGateway.features)
        // Same tautology removed as in the out-of-range case above.
        assertNull(
            "the release must be verified, not merely attempted",
            status.recoveryIncompleteStep,
        )
        assertFalse("the durable intent must not stay enabled", h.settingsStore.current.enabled)
        assertFalse(
            "the recovery journal must be resolved by the verified cleanup",
            h.settingsStore.current.recoveryRequired,
        )
    }

    // ------------------------------------------------------------------
    // R05 - status claims
    // ------------------------------------------------------------------

    @Test
    fun observation_refresh_outside_the_interval_never_claims_allowed_when_the_readback_is_unreadable() =
        runTest {
            // REPLACED AFTER INDEPENDENT AUDIT. The previous version picked an instant INSIDE the
            // interval, where the derivation takes the restricted branch; it therefore never
            // reached the branch that used to fall through to ARMED_POWER_MENU_ALLOWED, and it
            // passed on the pre-repair code. `review.ReviewRegressionTest` now covers the known
            // PROTECTED mask outside the interval; this covers the other half of the same R05
            // property — an unreadable (unknown) readback, which is never evidence of "allowed".
            val h = H(
                now = Instant.parse("2026-06-15T06:00:00Z"), // outside 02:00-05:00
                transformGateway = { base -> unreadableFeatureReadbackGateway(base) },
            )
            h.seedSettings(enabled = true)
            h.seedBaseline()
            h.restricted()

            val status = h.coordinator.refreshObservation()

            assertNotEquals(
                "an unreadable mask readback is unknown, and unknown is never 'allowed'",
                ProtectionState.ARMED_POWER_MENU_ALLOWED,
                status.state,
            )
            assertNotEquals(
                "an unreadable mask readback must not be reported as restricted either",
                ProtectionState.ARMED_POWER_MENU_RESTRICTED,
                status.state,
            )
            assertNull("the refresh must report the readback as unknown", status.effectiveFeatures)
            assertEquals(LockTaskRuntimeStates.LOCKED, status.lockTaskState)
            assertEquals(false, status.insideProtectedInterval)
        }

    // ------------------------------------------------------------------
    // R06 - temporary test separation
    // ------------------------------------------------------------------

    @Test
    fun temporary_test_is_refused_while_the_daily_preference_is_enabled() = runTest {
        val h = H()
        h.seedSettings(enabled = true)
        // A retained original from a completed session is not a pending preparation journal.
        h.seedBaseline(lifecycle = BaselineLifecycleState.RESTORED)
        val settingsBefore = h.settingsStore.current

        val status = h.coordinator.activatePocSession()

        assertEquals(ProtectionState.CONFIGURATION_ERROR, status.state)
        assertEquals(LockTaskRuntimeStates.NONE, h.runtime.state)
        // The old assertion here was `assertTrue(current.enabled)`, which simply echoed the value
        // the test itself seeded, so the pre-repair bug (a temporary test silently committing the
        // daily preference from a disabled start) could not be observed through it. Asserting the
        // whole durable record is unchanged is the observable form of "the preference was left
        // exactly as it was".
        assertEquals(
            "the daily preference record must be left exactly as it was",
            settingsBefore,
            h.settingsStore.current,
        )
        assertNull("a refused temporary test must leave no marker", h.settingsStore.marker)
        assertEquals("no managed session may be entered", 0, h.session.startRequests)
        assertFalse(
            "a refused temporary test must not apply a restrictive mask",
            h.policyGateway.featureSubmissions.contains(LockTaskMasks.PROTECTED),
        )
        assertEquals(
            "a refused temporary test must not submit a release timer",
            0,
            h.schedulingGateway.temporaryReleaseCount,
        )
        assertNotEquals(ProtectionState.ARMED_POWER_MENU_RESTRICTED, status.state)
    }

    @Test
    fun corrupt_settings_during_observation_releases_a_live_restriction_without_rearming() = runTest {
        val h = H()
        h.seedSettings(enabled = true)
        h.seedBaseline()
        h.restricted()
        h.settingsStore.corruptReads = true

        val status = h.coordinator.refreshObservation()

        assertTrue("the callback path must actually request task exit", h.session.stopRequests > 0)
        assertTrue("the permissive mask must be attempted", h.policyGateway.featureSubmissions.contains(LockTaskMasks.ALLOWED))
        assertEquals(LockTaskRuntimeStates.NONE, h.runtime.state)
        assertNotEquals(LockTaskMasks.PROTECTED, h.policyGateway.features)
        assertFalse("corrupt intent cannot remain enabled after recovery", h.settingsStore.current.enabled)
        assertFalse(h.settingsStore.current.recoveryRequired)
        assertEquals(ProtectionState.CONFIGURATION_ERROR, status.state)
        assertNotEquals(ProtectionState.ARMED_POWER_MENU_RESTRICTED, status.state)
        assertNull("corrupt schedule data cannot establish interval membership", status.insideProtectedInterval)
    }

    @Test
    fun corrupt_settings_schedule_edit_preserves_clean_original_policy_but_releases_if_active() = runTest {
        val clean = H()
        clean.settingsStore.corruptReads = true
        clean.policyGateway.features = 16

        val cleanStatus = clean.coordinator.editSchedule(180, 300)

        assertEquals(ProtectionState.CONFIGURATION_ERROR, cleanStatus.state)
        assertEquals(16, clean.policyGateway.features)
        assertTrue("clean no-history edits must not mutate device policy", clean.policyGateway.featureSubmissions.isEmpty())
        assertEquals(0, clean.session.stopRequests)

        val active = H()
        active.seedSettings(enabled = true)
        active.seedBaseline()
        active.restricted()
        active.settingsStore.corruptReads = true

        val activeStatus = active.coordinator.editSchedule(180, 300)

        assertTrue("a corrupt settings read over a live session must request release", active.session.stopRequests > 0)
        assertEquals(LockTaskRuntimeStates.NONE, active.runtime.state)
        assertNotEquals(LockTaskMasks.PROTECTED, active.policyGateway.features)
        assertEquals(ProtectionState.CONFIGURATION_ERROR, activeStatus.state)
        assertFalse(active.settingsStore.current.enabled)
    }

    @Test
    fun corrupt_settings_during_policy_failure_still_releases_without_claiming_original_restored() = runTest {
        val h = H(
            transformSettings = { base ->
                object : SettingsRepository by base {
                    override suspend fun readSettings(): SettingsReadResult =
                        SettingsReadResult.Corrupt(IllegalStateException("injected unreadable settings"))

                    override suspend fun readBaseline(): PolicyBaseline? =
                        throw IllegalStateException("injected unreadable baseline")
                }
            },
        )
        h.seedSettings(enabled = true)
        h.seedBaseline(features = 0, packages = emptySet())
        h.restricted()
        assertEquals("setup must be a real locked session with the documented restrictive mask", 47, h.policyGateway.features)
        assertEquals(LockTaskRuntimeStates.LOCKED, h.runtime.state)
        val startsBefore = h.session.startRequests
        val protectedSubmissionsBefore = h.policyGateway.featureSubmissions.count { it == LockTaskMasks.PROTECTED }

        val status = h.coordinator.handlePolicyFailure(
            "lockTask",
            PolicyUpdateResult.RESULT_FAILURE_CONFLICTING_ADMIN_POLICY,
        )

        assertEquals("unreadable settings/baseline cannot be reported as a clean disabled state", ProtectionState.RECOVERY_FAILED, status.state)
        assertEquals("the real locked session must be asked to stop", 1, h.session.stopRequests)
        assertEquals(LockTaskRuntimeStates.NONE, h.runtime.state)
        assertTrue("the permissive mask must actually be submitted", h.policyGateway.featureSubmissions.contains(LockTaskMasks.ALLOWED))
        assertEquals("independent allowlist removal must be attempted", emptySet<String>(), h.policyGateway.packages)
        assertEquals(startsBefore, h.session.startRequests)
        assertEquals(
            "failure recovery must never submit a new restrictive mask",
            protectedSubmissionsBefore,
            h.policyGateway.featureSubmissions.count { it == LockTaskMasks.PROTECTED },
        )
        assertTrue("an unresolved durable incident must remain visible", h.settingsStore.incident?.unresolved == true)
        assertTrue("the durable settings must keep recovery pending", h.settingsStore.current.recoveryRequired)
    }

    @Test
    fun first_arm_preserves_the_original_permissive_mask_across_a_disabled_schedule_edit() = runTest {
        for (originalFeatures in listOf(16, LockTaskMasks.ALLOWED)) {
            val h = H()
            h.seedSettings(enabled = false, start = 120, end = 300)
            h.policyGateway.features = originalFeatures
            h.policyGateway.packages.clear()
            assertEquals(LockTaskRuntimeStates.NONE, h.runtime.state)

            val edited = h.coordinator.editSchedule(90, 330)
            assertEquals("a disabled schedule edit must save intent without arming", ProtectionState.DISARMED, edited.state)
            assertEquals("the original mask must survive the edit", originalFeatures, h.policyGateway.features)
            assertTrue("a clean disabled edit must not touch the original policy", h.policyGateway.featureSubmissions.isEmpty())
            assertEquals(0, h.session.startRequests)

            val armed = h.coordinator.arm()
            assertEquals("a documented permissive original must not poison first-use arming", ProtectionState.ARMED_POWER_MENU_RESTRICTED, armed.state)
            assertEquals(1, h.session.startRequests)
            val captured = requireNotNull(h.settingsStore.baseline)
            assertEquals("preparation must record the real pre-mutation mask", originalFeatures, captured.lockTaskFeatures)
            assertEquals(emptySet<String>(), captured.lockTaskPackages)
            assertEquals(BaselineProvenance.CAPTURED_BEFORE_PREPARATION, captured.provenance)
            assertEquals(BaselineLifecycleState.PREPARED, captured.lifecycle)
            assertEquals(LockTaskMasks.PROTECTED, h.policyGateway.features)

            val restored = h.coordinator.disableAndRestore()
            assertEquals(ProtectionState.DISARMED, restored.state)
            assertEquals("full recovery must restore the exact original mask", originalFeatures, h.policyGateway.features)
            assertEquals("full recovery must restore the exact original allowlist", emptySet<String>(), h.policyGateway.packages)
            assertEquals(LockTaskRuntimeStates.NONE, h.runtime.state)
            assertFalse(h.settingsStore.current.recoveryRequired)
        }
    }

    @Test
    fun an_interrupted_temporary_marker_is_recovered_never_resumed_inside_the_window() = runTest {
        val h = H(now = INSIDE_WINDOW)
        h.seedSettings(enabled = true)
        h.seedBaseline()
        h.settingsStore.marker = TemporaryTestMarker(
            startedAtEpochMillis = 0L,
            releaseAtEpochMillis = 60_000L,
            revision = 1L,
        )

        val status = h.coordinator.reconcile(ProtectionTrigger.APP_FOREGROUND)

        // UPDATED FOR REVIEW-2 §4.4: the state now reflects whether recovery actually verified. A
        // baseline IS seeded here, so restoration is confirmed and the honest state is DISARMED
        // rather than the old blanket CONFIGURATION_ERROR that accompanied the false "was
        // recovered" wording.
        assertEquals(ProtectionState.DISARMED, status.state)
        assertNull("a verified recovery reports no unresolved step", status.recoveryIncompleteStep)
        assertNull(
            "an interrupted marker must be recovered and cleared, never resumed",
            h.settingsStore.marker,
        )
        assertFalse(
            "an interrupted temporary test must not newly restrict the power menu",
            h.policyGateway.featureSubmissions.contains(LockTaskMasks.PROTECTED),
        )
        assertEquals(LockTaskRuntimeStates.NONE, h.runtime.state)
    }

    @Test
    fun an_interrupted_temporary_marker_with_unverifiable_recovery_is_not_reported_as_recovered() = runTest {
        // REVIEW-2 §4.4: the mirror case. With no baseline, restoration cannot be confirmed, so the
        // app must say so rather than claiming the interrupted test "was recovered".
        val h = H(now = INSIDE_WINDOW)
        h.seedSettings(enabled = true)
        h.settingsStore.marker = TemporaryTestMarker(
            startedAtEpochMillis = 0L,
            releaseAtEpochMillis = 60_000L,
            revision = 1L,
        )

        val status = h.coordinator.reconcile(ProtectionTrigger.APP_FOREGROUND)

        assertEquals(ProtectionState.RECOVERY_FAILED, status.state)
        assertEquals("RESTORE_BASELINE_UNAVAILABLE", status.recoveryIncompleteStep)
        assertNotNull("the unresolved step must be surfaced", status.userActionRequired)
        assertFalse(
            "the detail must not claim success when recovery did not verify (detail=${status.detail})",
            status.detail!!.contains("was recovered"),
        )
    }

    @Test
    fun repeated_restrict_presses_do_not_extend_the_temporary_expiry() = runTest {
        val h = H()
        h.seedSettings(enabled = false)

        val started = h.coordinator.activatePocSession()
        assertEquals(ProtectionState.ARMED_POWER_MENU_ALLOWED, started.state)

        val marker = h.settingsStore.marker
        assertNotNull("the temporary test must have a durable marker", marker)
        val releasesAfterStart = h.schedulingGateway.temporaryReleaseCount
        assertTrue(
            "the release timer must be submitted before entry",
            releasesAfterStart >= 1,
        )

        h.coordinator.setPocOverride(PocOverride.FORCE_RESTRICTED)
        h.coordinator.setPocOverride(PocOverride.FORCE_RESTRICTED)

        // The timer count is the assertion that carries this property: the expiry can only move if
        // the release timer is re-submitted, and re-submitting it is what made the test extendable
        // indefinitely. The former `assertEquals(expiryAfterStart, marker.releaseAtEpochMillis)`
        // was decorative — no production path rewrites the marker after start, so it could not
        // fail — and `temporaryReleaseCount <= 1` was implied by the equality below.
        assertEquals(
            "the temporary release timer must not be re-submitted for the same revision",
            releasesAfterStart,
            h.schedulingGateway.temporaryReleaseCount,
        )
        assertEquals(
            "the presses must still have restricted the menu",
            LockTaskMasks.PROTECTED,
            h.policyGateway.features,
        )
    }

    @Test
    fun a_second_temporary_test_start_is_refused_and_preserves_the_marker_and_baseline() = runTest {
        // The audit's highest-severity finding. A live temporary test keeps the daily preference
        // false and `recoveryRequired` false, so every precondition of `activatePocSession` still
        // holds on a second press. Without the re-entry guard a second start replaces the marker
        // with a later expiry (making the test extendable indefinitely) and overwrites the saved
        // original baseline with the app's own current policy — so a later "restore" restores the
        // restriction and reports success. This pins the refusal, the unchanged expiry, and the
        // unchanged baseline (R06 step 10 / R02 baseline lifecycle).
        val h = H()
        h.seedSettings(enabled = false)
        // Model an already-restored original policy, then let this start capture it as a fresh
        // session baseline.
        h.seedBaseline(
            features = 7,
            packages = setOf("com.example.original"),
            lifecycle = BaselineLifecycleState.RESTORED,
        )
        h.policyGateway.features = 7
        h.policyGateway.packages = mutableSetOf("com.example.original")

        val first = h.coordinator.activatePocSession()
        assertEquals(ProtectionState.ARMED_POWER_MENU_ALLOWED, first.state)
        val markerAfterFirst = h.settingsStore.marker
        assertNotNull("the temporary test must have a durable marker", markerAfterFirst)
        val baselineAfterFirst = h.settingsStore.baseline
        assertNotNull("the temporary test must retain the original baseline", baselineAfterFirst)
        val releasesAfterFirst = h.schedulingGateway.temporaryReleaseCount
        val startsAfterFirst = h.session.startRequests

        // Advance the clock so a re-computed expiry could not coincidentally equal the original.
        h.nowMillis = 60_000L
        val second = h.coordinator.activatePocSession()

        assertNotEquals(
            "a second start must not claim a new restricted session",
            ProtectionState.ARMED_POWER_MENU_RESTRICTED,
            second.state,
        )
        assertNotNull(
            "the refusal must tell the user a test is already active",
            second.userActionRequired,
        )
        assertEquals(
            "a second start must not reset the release timer (the expiry must not move)",
            markerAfterFirst,
            h.settingsStore.marker,
        )
        assertEquals(
            "a second start must not overwrite the saved original policy",
            baselineAfterFirst,
            h.settingsStore.baseline,
        )
        assertEquals(
            "the stored original mask must still be the device's original policy",
            7,
            h.settingsStore.baseline!!.lockTaskFeatures,
        )
        assertEquals(
            "the stored original allowlist must still be the device's original policy",
            setOf("com.example.original"),
            h.settingsStore.baseline!!.lockTaskPackages,
        )
        assertEquals(
            "a second start must not re-submit the release timer",
            releasesAfterFirst,
            h.schedulingGateway.temporaryReleaseCount,
        )
        assertEquals(
            "a second start must not re-enter the managed session",
            startsAfterFirst,
            h.session.startRequests,
        )
    }

    @Test
    fun a_repeat_start_and_stale_alarm_cannot_hide_a_failed_temporary_recovery() = runTest {
        val h = restrictedLiveTemporaryTest()
        val marker = requireNotNull(h.settingsStore.marker)
        val allowed = h.coordinator.setPocOverride(PocOverride.FORCE_ALLOWED)
        assertEquals("the setup must reach a live but permissive Lock Task session", ProtectionState.ARMED_POWER_MENU_ALLOWED, allowed.state)
        val startsBeforeFailure = h.session.startRequests
        val protectedSubmissionsBeforeFailure = h.policyGateway.featureSubmissions.count { it == LockTaskMasks.PROTECTED }
        h.session.stopLeavesStateUnchanged = true
        h.policyGateway.ignorePackageWrites = true

        val failure = h.coordinator.handlePolicyFailure("lock_task_policy", null)

        assertEquals("the injected stop/policy failure must be visible", ProtectionState.RECOVERY_FAILED, failure.state)
        assertTrue("the callback must actually attempt session exit", h.session.stopRequests > 0)
        assertTrue("the unresolved recovery must be durably represented", h.settingsStore.current.recoveryRequired)
        assertTrue("the temporary marker remains evidence while cleanup is incomplete", h.settingsStore.marker != null)
        assertTrue("recovery must actually attempt the independent empty-allowlist exit", h.policyGateway.packageSubmissions.contains(emptySet()))
        assertEquals("the injected package-exit failure keeps the app allowlisted", setOf(ID), h.policyGateway.packages)

        val repeatedStart = h.coordinator.activatePocSession()
        assertEquals("a repeat Start cannot replace pending recovery with active success", ProtectionState.RECOVERY_FAILED, repeatedStart.state)
        assertEquals("repeat Start must preserve the original release marker", marker, h.settingsStore.marker)
        assertEquals("repeat Start must not enter a second session", startsBeforeFailure, h.session.startRequests)
        assertEquals(
            "repeat Start must not submit another restrictive policy",
            protectedSubmissionsBeforeFailure,
            h.policyGateway.featureSubmissions.count { it == LockTaskMasks.PROTECTED },
        )

        val staleDelivery = h.coordinator.releaseTemporaryTest(marker.releaseAtEpochMillis, deliveredSessionId = null)
        assertEquals("a legacy/missing identity cannot clear the modern session or its failure", ProtectionState.RECOVERY_FAILED, staleDelivery.state)
        assertNotNull("the downgrade must retain a recovery step", staleDelivery.recoveryIncompleteStep)
        assertEquals(marker, h.settingsStore.marker)
        assertEquals(startsBeforeFailure, h.session.startRequests)
    }

    // ------------------------------------------------------------------
    // R07 - alarm evidence
    // ------------------------------------------------------------------

    @Test
    fun a_plan_submitted_by_a_previous_process_is_resubmitted_once_after_a_restart() = runTest {
        // ADDED AFTER INDEPENDENT AUDIT. Three earlier R07 tests were deleted here:
        //  * `a_new_boot_generation_causes_the_plan_to_be_resubmitted` — `ScheduleReceipt
        //    .bootGeneration` predates the repair, so it passed on the pre-repair code and could
        //    not see the new mechanism;
        //  * `missing_pending_intent_tokens_cause_resubmission` — missing tokens were already a
        //    negative signal pre-repair (the R07 defect is the *positive* direction);
        //  * `repeated_stable_reconciles_do_not_resubmit_forever` — a receipt/token guard already
        //    existed, so it passed pre-repair; its same-process loop guard is folded in below.
        //
        // This is R07's actual mechanism. Every *stored* condition for "the plan is current" holds
        // here — the receipt matches this revision, this boot generation and the exact boundaries
        // this instant computes, and the PendingIntent tokens exist — yet this process has not
        // submitted the plan, so the first pass must resubmit it once. A receipt records what a
        // previous run submitted and a PendingIntent token is not an AlarmManager inventory query,
        // so neither is evidence of delivery after a restart.
        val h = H()
        h.seedSettings(enabled = true)
        h.seedBaseline()
        h.restricted()

        val settings = h.settingsStore.current
        val boundaries = ScheduleCalculator(Clock.fixed(h.now, ZoneOffset.UTC), { ZoneOffset.UTC })
            .boundaries(settings)
        h.settingsStore.receipt = ScheduleReceipt(
            revision = settings.revision,
            bootGeneration = h.settingsStore.bootGeneration,
            submittedAtEpochMillis = 0L,
            nextStartEpochMillis = boundaries.nextStart!!.toEpochMilli(),
            nextEndEpochMillis = boundaries.nextEnd!!.toEpochMilli(),
            fallbackEpochMillis = boundaries.nextEnd!!.toEpochMilli() + 60_000L,
        )

        h.coordinator.reconcile(ProtectionTrigger.START_ALARM)

        assertEquals(
            "a receipt written by a previous process is not proof that this process submitted it",
            1,
            h.schedulingGateway.installPlanCount,
        )

        repeat(3) { h.coordinator.reconcile(ProtectionTrigger.POLICY_CHANGED) }

        assertEquals(
            "once this process has submitted, a stable plan must not be resubmitted on every pass",
            1,
            h.schedulingGateway.installPlanCount,
        )
        assertEquals(
            "the submitted plan must be recorded for this revision and boot generation",
            settings.revision,
            h.settingsStore.receipt!!.revision,
        )
    }

    // ------------------------------------------------------------------
    // R08 / R09 - receiver and callback safety
    // ------------------------------------------------------------------

    @Test
    fun a_suspended_diagnostics_writer_does_not_prevent_the_timeout_release() = runTest {
        // RENAMED AFTER INDEPENDENT AUDIT. The old name (`...does_not_prevent_release`) read as if
        // it covered the R08 receiver-logging order, which it does not: the release here comes
        // from the coordinator's own timeout handler, because the suspended `record(...)` call
        // exhausts the operation deadline. That is still a real property — optional logging must
        // never be able to hold a release hostage. The R08 ordering inside `ProtectionAlarmReceiver`
        // is exercised by the Robolectric `ReceiverIntegrationTest`, which supplies a real Context
        // and pending result on the desktop JVM.
        val h = H(
            now = Instant.parse("2026-06-15T05:00:00Z"),
            timeout = 200L,
            transformDiagnostics = { base ->
                object : DiagnosticsRepository by base {
                    override suspend fun record(kind: String, revision: Long, message: String) {
                        delay(10_000)
                        base.record(kind, revision, message)
                    }
                }
            },
        )
        h.seedSettings(enabled = true)
        h.seedBaseline()
        h.restricted()

        val status = h.coordinator.reconcile(ProtectionTrigger.END_ALARM)

        assertTrue(
            "the permissive mask must still be submitted when the logger is suspended",
            h.policyGateway.featureSubmissions.contains(LockTaskMasks.ALLOWED),
        )
        assertNotEquals(
            "a suspended diagnostics writer must never leave the device restricted",
            LockTaskMasks.PROTECTED,
            h.policyGateway.features,
        )
        assertEquals(LockTaskRuntimeStates.NONE, h.runtime.state)
        assertNotEquals(ProtectionState.ARMED_POWER_MENU_RESTRICTED, status.state)
    }

    @Test
    fun manual_temporary_release_restores_original_policy_when_optional_logging_stalls() = runTest {
        var stallLogger = false
        val h = H(
            transformDiagnostics = { base ->
                object : DiagnosticsRepository by base {
                    override suspend fun record(kind: String, revision: Long, message: String) {
                        if (stallLogger) delay(10_000)
                        base.record(kind, revision, message)
                    }
                }
            },
        )
        h.seedSettings(enabled = false)
        h.seedBaseline(
            features = 7,
            packages = setOf("com.example.original"),
            lifecycle = BaselineLifecycleState.RESTORED,
        )
        h.policyGateway.features = 7
        h.policyGateway.packages = mutableSetOf("com.example.original")
        val start = h.coordinator.activatePocSession()
        assertEquals("setup must start an actual temporary session", ProtectionState.ARMED_POWER_MENU_ALLOWED, start.state)
        val restricted = h.coordinator.setPocOverride(PocOverride.FORCE_RESTRICTED)
        assertEquals("setup must actually apply the temporary restriction", ProtectionState.ARMED_POWER_MENU_RESTRICTED, restricted.state)
        stallLogger = true

        val released = h.coordinator.releaseTemporaryTest()

        assertEquals("manual release must finish despite optional diagnostics", ProtectionState.DISARMED, released.state)
        assertTrue("release must actually request task exit", h.session.stopRequests > 0)
        assertEquals(LockTaskRuntimeStates.NONE, h.runtime.state)
        assertEquals("the captured original feature mask must be restored", 7, h.policyGateway.features)
        assertEquals(setOf("com.example.original"), h.policyGateway.packages)
        assertNull("verified manual release clears the marker", h.settingsStore.marker)
        assertFalse(h.settingsStore.current.recoveryRequired)
    }

    @Test
    fun concurrent_disable_and_start_converge_to_released() = runTest {
        // REWRITTEN AFTER INDEPENDENT AUDIT. The previous version launched both bodies with
        // `async` but nothing inside them suspended, so they ran strictly one after the other and
        // the test proved nothing about concurrency (the pre-repair code passed it too).
        //
        // A real interleaving is created here: the START_ALARM pass takes the coordinator lock and
        // parks inside its critical section on a stalled receipt write until its own operation
        // deadline expires, with the disable already queued behind it on the lock. The start's
        // timeout handler must confirm the release (RECOVERING, with the timeout step recorded),
        // while the disable independently reports that its lock-wait fallback could not perform
        // policy work. A later manual Restore verifies and resolves the retained journal.
        //
        // An earlier version of this rewrite asserted the mid-flight inhibitor/durable-disable state
        // after `advanceTimeBy`; mutation testing showed those assertions were satisfied by the
        // *disable's own* recovery rather than by the start's timeout handler, so they proved
        // nothing. They are replaced by the start's own timeout outcome, which only the repaired
        // handler produces, plus a separate assertion that a later serialized Restore clears the
        // pending incident.
        val h = H(
            timeout = 500L,
            transformSettings = { base ->
                object : SettingsRepository by base {
                    override suspend fun writeScheduleReceipt(
                        receipt: ScheduleReceipt?,
                    ): SettingsWriteResult {
                        // The start's own plan submission stalls past its deadline.
                        if (receipt != null) delay(1_000)
                        return base.writeScheduleReceipt(receipt)
                    }
                }
            },
        )
        h.seedSettings(enabled = true)
        h.seedBaseline()
        h.restricted()

        val start = async { h.coordinator.reconcile(ProtectionTrigger.START_ALARM) }
        runCurrent() // the start takes the lock and parks inside its critical section
        val disable = async { h.coordinator.disableAndRestore() }
        runCurrent() // the disable is genuinely queued behind the in-flight start

        advanceUntilIdle()
        val startStatus = start.await()
        val disableStatus = disable.await()

        // Proof the interleaving was real: the start's deadline expired while it was parked with
        // the disable waiting, and its bounded release was confirmed.
        assertEquals("TIMEOUT_CLEANUP", startStatus.recoveryIncompleteStep)
        assertEquals(
            "the timed-out start must report the confirmed bounded release, not a pending label",
            ProtectionState.RECOVERING,
            startStatus.state,
        )

        assertFalse("the durable intent must end disabled", h.settingsStore.current.enabled)
        assertTrue("the unverified timeout boundary must remain durably pending", h.settingsStore.current.recoveryRequired)
        assertTrue("the release-only incident must remain visible", h.settingsStore.incident?.unresolved == true)
        assertEquals(LockTaskRuntimeStates.NONE, h.runtime.state)
        assertNotEquals(
            "no restrictive mask may survive a concurrent disable",
            LockTaskMasks.PROTECTED,
            h.policyGateway.features,
        )
        assertNotEquals(
            "the timed-out start must not be reported as a restriction claim",
            ProtectionState.ARMED_POWER_MENU_RESTRICTED,
            startStatus.state,
        )
        assertEquals(
            "the queued disable cannot enter the held lock and must report its release-only fallback",
            "LOCK_WAIT_TIMEOUT",
            disableStatus.recoveryIncompleteStep,
        )

        // The lock-wait request could not perform policy work while the start held the mutex. A
        // subsequent foreground Restore runs after the holder is gone and completes the durable
        // release transaction, so this final resolved assertion belongs to a real recovery pass.
        val manualRelease = h.coordinator.disableAndRestore()
        assertEquals(ProtectionState.DISARMED, manualRelease.state)
        assertFalse("the later verified Restore resolves the journal", h.settingsStore.current.recoveryRequired)
        assertNull(h.settingsStore.incident)
        assertEquals(LockTaskRuntimeStates.NONE, h.runtime.state)
    }

    @Test
    fun a_stale_temporary_release_event_cannot_tear_down_a_later_test() = runTest {
        // REVIEW-2 section 4.2: the fence used to be purely presence-based, so a delayed delivery
        // from test A could tear down a live test B whenever a marker existed. The delivery is now
        // bound to the test run it belongs to via the alarm's own planned boundary.
        val h = H()
        h.seedSettings(enabled = false)
        h.coordinator.activatePocSession()
        h.coordinator.setPocOverride(PocOverride.FORCE_RESTRICTED)

        val marker = h.settingsStore.marker
        assertNotNull("the temporary test must be live", marker)
        assertEquals(
            "the temporary session must actually be restricted",
            LockTaskMasks.PROTECTED,
            h.policyGateway.features,
        )

        // A delivery from an EARLIER test: its planned boundary is not this marker's releaseAt.
        val stale = h.coordinator.releaseTemporaryTest(
            deliveredReleaseAtEpochMillis = marker!!.releaseAtEpochMillis - 60_000L,
        )

        assertNotNull("a stale event must not clear the marker", h.settingsStore.marker)
        assertEquals(
            "a stale event must not release the live test",
            LockTaskRuntimeStates.LOCKED,
            h.runtime.state,
        )
        assertEquals(
            "a stale event must not change the mask",
            LockTaskMasks.PROTECTED,
            h.policyGateway.features,
        )
        assertEquals(
            "a stale event must report the observed restricted state, not a disarmed one",
            ProtectionState.ARMED_POWER_MENU_RESTRICTED,
            stale.state,
        )

        // The genuine delivery for THIS test must still release it — the fence must not be so strict
        // that a real release is ignored.
        h.coordinator.releaseTemporaryTest(
            deliveredReleaseAtEpochMillis = marker.releaseAtEpochMillis,
            deliveredSessionId = marker.sessionId,
        )
        assertEquals("the genuine release must still work", LockTaskRuntimeStates.NONE, h.runtime.state)
        assertNull("the genuine release must clear the marker", h.settingsStore.marker)
    }

    @Test
    fun exact_alarm_unavailability_must_not_claim_the_device_was_disarmed() = runTest {
        // CLOSES THE M02 MUTATION GAP. Mutation testing showed that reverting the review-2 §4.4 fix
        // at the reconciler's exact-alarm-unavailable branch broke NO test: the supplied U04 case
        // exercises the DISABLED branch, not this one. So the wording on this path was unverified.
        //
        // Here the app is ENABLED with a live restriction and no exact-alarm capability, so the
        // reconciler reaches that branch. Recovery cannot verify (no baseline exists), so the honest
        // answer is a capability notice PLUS an unresolved-release report — never "the device was
        // disarmed".
        val h = H()
        h.seedSettings(enabled = true)
        h.restricted()
        h.schedulingGateway.exactCapability = false

        val status = h.coordinator.reconcile(ProtectionTrigger.EXACT_ACCESS_GRANTED)

        assertEquals(
            "the capability loss is still the headline condition",
            ProtectionState.EXACT_SCHEDULING_UNAVAILABLE,
            status.state,
        )
        assertFalse(
            "the detail must not claim the device was disarmed when recovery did not verify " +
                "(detail=${status.detail})",
            status.detail!!.contains("was disarmed"),
        )
        assertNotNull(
            "an incomplete release must be surfaced, not hidden behind a capability message",
            status.recoveryIncompleteStep,
        )
    }

    @Test
    fun a_submitted_lock_wait_retry_must_actually_apply_rather_than_be_ignored() = runTest {
        // RESTORES COVERAGE LOST BY A PRODUCTION FIX (verify3-c final report).
        //
        // The supplied review2 test `lock_wait_release_retry_must_reference_a_real_recovery_incident`
        // used to catch a synthetic retry id. Moving the armLocked guard to step 0 made the
        // fixture's `arm()` run a full recovery BEFORE the stalled settings read, so that test's only
        // behavioural assertion (`runtime == NONE`) is now satisfied without the retry working — it
        // is a false green, and U03 can be reintroduced with the whole suite still passing.
        //
        // The supplied file is byte-identical to the reviewer's and must not be weakened, so the
        // property is re-established here by asserting the RETRY'S OWN result rather than the final
        // device state:
        //   pristine: retry.recoveryIncompleteStep == null  (the retry applied)
        //   U03     : retry.recoveryIncompleteStep == "RETRY_NOT_APPLIED"  (id mismatch, ignored)
        var submittedIncident: String? = null
        val h = H(
            timeout = 50L,
            transformScheduling = { base ->
                object : SchedulingGateway by base {
                    override fun installRecoveryRetry(incidentId: String, triggerAt: Instant): Boolean {
                        submittedIncident = incidentId
                        return base.installRecoveryRetry(incidentId, triggerAt)
                    }
                }
            },
            transformSettings = { base ->
                object : SettingsRepository by base {
                    var reads = 0
                    override suspend fun readSettings(): SettingsReadResult {
                        reads++
                        if (reads == 1) delay(100_000) // parks the lock holder
                        return base.readSettings()
                    }
                }
            },
        )
        h.seedSettings(enabled = true)
        h.seedBaseline()
        h.restricted()

        val holder = async { h.coordinator.arm() }
        runCurrent()
        val blocked = async { h.coordinator.reconcile(ProtectionTrigger.END_ALARM) }
        runCurrent()
        testScheduler.advanceTimeBy(6_000)
        runCurrent()
        blocked.await()

        val incident = submittedIncident
        assertNotNull("the lock-wait path must submit a retry", incident)

        // A retry delivery is a separate serialized operation. Let the original arm holder finish
        // its own bounded fallback before delivering the incident, otherwise this call merely
        // exercises another lock-wait timeout rather than the retry handler.
        advanceUntilIdle()
        holder.await()

        val retry = h.coordinator.handleRecoveryRetry(incident)
        assertNull(
            "the submitted retry must reference a REAL durable incident and actually apply; an " +
                "ignored retry reports RETRY_NOT_APPLIED (got ${retry.recoveryIncompleteStep})",
            retry.recoveryIncompleteStep,
        )

    }

    @Test
    fun a_lock_wait_that_exceeds_its_budget_reports_recovery_failed_and_schedules_a_release_retry() =
        runTest {
            // ADDED AFTER INDEPENDENT AUDIT: no test reached `lockWaitTimedOut`, the R08 path that
            // previously turned an expired lock wait into a harmless-looking POLICY_PENDING label
            // with no inhibitor, no durable marker, no release attempt and no reschedule. For a
            // one-shot release alarm that consumed the only release opportunity and left the
            // restriction in place — the failure mode R01's pass condition forbids.
            //
            // The lock is genuinely held past the blocked pass's whole budget: the holder is an
            // `arm()` (12 s arm deadline) parked inside `readSettings`, while the blocked pass is a
            // `reconcile` whose total ceiling is lock-wait (3 s) + operation (50 ms) + cleanup (2 s)
            // = 5.05 s. 5.05 s < 12 s, so the lock wait expires while the holder is still inside its
            // critical section.
            //
            // REWRITTEN FOR REVIEW-2 U02: the previous version relied on the timeout handler's
            // durable write running OUTSIDE any deadline to hold the lock for 20 s. That was the
            // very defect U02 fixed — the handler's bookkeeping is now bounded — so the old setup no
            // longer produces a lock-wait timeout at all.
            val h = H(
                timeout = 50L,
                transformSettings = { base ->
                    object : SettingsRepository by base {
                        override suspend fun readSettings(): SettingsReadResult {
                            delay(100_000) // parks the holder inside its critical section
                            return base.readSettings()
                        }
                    }
                },
            )
            h.seedSettings(enabled = true)
            h.seedBaseline()
            h.restricted()

            val holder = async { h.coordinator.arm() }
            runCurrent() // holder takes the lock and parks inside readSettings
            val blocked = async { h.coordinator.reconcile(ProtectionTrigger.END_ALARM) }
            runCurrent() // blocked is now waiting on the coordinator lock

            // Advance past the blocked pass's 5.05 s ceiling but NOT as far as the holder's 12 s arm
            // deadline. The inhibitor is a single-reason brake and the holder's own later timeout
            // would overwrite the reason, so the lock-wait reason must be observed in this window.
            testScheduler.advanceTimeBy(6_000)
            runCurrent()
            val status = blocked.await()

            assertNotEquals(
                "an expired lock wait must not be reported as a harmless pending label",
                ProtectionState.POLICY_PENDING,
                status.state,
            )
            assertEquals(ProtectionState.RECOVERY_FAILED, status.state)
            assertEquals("LOCK_WAIT_TIMEOUT", status.recoveryIncompleteStep)
            assertTrue("the safety brake must be engaged", h.inhibitor.isInhibited)
            // The inhibitor is a single-reason brake and the holder's own later timeout legitimately
            // overwrites the reason, so the lock-wait path is identified from ITS OWN answer, which
            // nothing overwrites.
            assertTrue(
                "the lock-wait answer must name the lock contention it actually hit " +
                    "(detail=${status.detail})",
                status.detail!!.contains("held the coordinator lock"),
            )
            assertTrue(
                "a release-only retry must be scheduled so the one-shot release is not lost " +
                    "(count=${h.schedulingGateway.recoveryRetryCount})",
                h.schedulingGateway.recoveryRetryCount >= 1,
            )
            // REVIEW-2 U03: the retry must reference a REAL durable incident, otherwise the retry
            // receiver discards it on its identity check and the restriction survives.
            assertNotNull(
                "the scheduled retry must reference a durable recovery incident",
                h.settingsStore.incident,
            )
            assertNotEquals(
                "the lock-wait answer is deliberately not published, so it cannot overwrite the " +
                    "status written by the pass that held the lock",
                "LOCK_WAIT_TIMEOUT",
                h.coordinator.status.value?.recoveryIncompleteStep,
            )

            // Let the holder finish so the test leaves no dangling coroutine behind.
            advanceUntilIdle()
            holder.await()
        }

    @Test
    fun a_relevant_policy_failure_while_protected_invalidates_and_recovers() = runTest {
        val h = H()
        h.seedSettings(enabled = true)
        h.seedBaseline()
        h.restricted()

        val status = h.coordinator.handlePolicyFailure(
            "lockTask",
            PolicyUpdateResult.RESULT_FAILURE_CONFLICTING_ADMIN_POLICY,
        )

        assertTrue(
            "a relevant policy failure must not be reported as a success claim (state=${status.state})",
            status.state == ProtectionState.DISARMED ||
                status.state == ProtectionState.RECOVERY_FAILED,
        )
        assertNotEquals(
            "the device must be released after an invalidated success claim",
            LockTaskMasks.PROTECTED,
            h.policyGateway.features,
        )
        assertEquals(LockTaskRuntimeStates.NONE, h.runtime.state)
    }

    @Test
    fun a_policy_failure_after_disable_never_reenables_protection() = runTest {
        // STRENGTHENED AFTER INDEPENDENT AUDIT. The previous version asserted only that the durable
        // intent stayed disabled and that no PROTECTED mask was submitted — all of which an empty
        // `handlePolicyFailure` satisfies — and it released the device first, so there was nothing
        // left that a wrong handler could restore. The device is now genuinely LOCKED + PROTECTED
        // while the daily preference is disabled, which is exactly the state the R09 repair exists
        // for: keying off `enabled` alone is not evidence that nothing is restricted.
        val h = restrictedLiveTemporaryTest()
        val submissionsBefore = h.policyGateway.featureSubmissions.size

        val status = h.coordinator.handlePolicyFailure(
            "lockTask",
            PolicyUpdateResult.RESULT_FAILURE_CONFLICTING_ADMIN_POLICY,
        )

        assertFalse(
            "a failure callback arriving after a disable must not re-enable protection",
            h.settingsStore.current.enabled,
        )
        assertFalse(
            "no restrictive mask may be submitted after a disable",
            h.policyGateway.featureSubmissions
                .drop(submissionsBefore)
                .contains(LockTaskMasks.PROTECTED),
        )
        assertEquals(
            "the restricted session must be released, not left running",
            LockTaskRuntimeStates.NONE,
            h.runtime.state,
        )
        assertNotEquals(
            "the restrictive mask must not survive the failure callback",
            LockTaskMasks.PROTECTED,
            h.policyGateway.features,
        )
        assertNotEquals(
            "the returned status must not claim the power menu is restricted",
            ProtectionState.ARMED_POWER_MENU_RESTRICTED,
            status.state,
        )
    }

    @Test
    fun a_policy_failure_during_a_live_temporary_test_releases_before_claiming_disarmed() = runTest {
        // ADDED AFTER INDEPENDENT AUDIT: the live temporary test is the one state in which the
        // daily preference is false while a real locked session and a restrictive mask are in
        // force. A handler that reads `enabled` alone publishes DISARMED over that restricted
        // device without releasing it. This pins the release and the durable cleanup that must
        // precede the claim — the same handler as the test above, asserted through the recovery
        // evidence (call counts and durable state) rather than through the reported state alone.
        val h = restrictedLiveTemporaryTest()
        val submissionsBefore = h.policyGateway.featureSubmissions.size

        val status = h.coordinator.handlePolicyFailure(
            "lockTask",
            PolicyUpdateResult.RESULT_FAILURE_CONFLICTING_ADMIN_POLICY,
        )

        assertEquals(
            "recovery must actually run: the managed session must be asked to stop",
            1,
            h.session.stopRequests,
        )
        assertTrue(
            "recovery must cancel the app-owned alarms, including the temporary release timer",
            h.schedulingGateway.cancelAllCount >= 1,
        )
        assertTrue(
            "the permissive mask must actually have been applied during the release",
            h.policyGateway.featureSubmissions.contains(LockTaskMasks.ALLOWED),
        )
        // Full recovery restores the device's ORIGINAL policy (the stored baseline, 0 here), not
        // the app's permissive working mask, so that is what must be in force before DISARMED is
        // claimed. What matters for this test is that the restrictive mask is gone.
        assertEquals(
            "the device's original policy must be in force before DISARMED is claimed",
            h.settingsStore.baseline!!.lockTaskFeatures,
            h.policyGateway.features,
        )
        assertEquals(LockTaskRuntimeStates.NONE, h.runtime.state)
        assertFalse(
            "no restrictive mask may be submitted by the failure handler",
            h.policyGateway.featureSubmissions
                .drop(submissionsBefore)
                .contains(LockTaskMasks.PROTECTED),
        )
        assertNull("the temporary-test marker must be cleared", h.settingsStore.marker)
        assertFalse(
            "the durable recovery journal must be resolved by the verified cleanup",
            h.settingsStore.current.recoveryRequired,
        )
        assertFalse(h.settingsStore.current.enabled)
        assertEquals(ProtectionState.DISARMED, status.state)
    }

    // DELETED AFTER INDEPENDENT AUDIT: `an_unrelated_policy_identifier_does_not_mutate_this_feature`
    // asserted the property at the wrong layer. `handlePolicyChanged` ignores its identifier
    // entirely — the R09 classification (`policyIdentifier ==
    // DevicePolicyIdentifiers.LOCK_TASK_POLICY`) lives in `LockTaskPolicyUpdateReceiver`, and every
    // assertion the deleted test made was also made by the repeated-callback guard. The receiver
    // receiver behavior is covered by the Robolectric ReceiverIntegrationTest, which supplies a
    // real Context and exercises goAsync plus both callback routes on the desktop JVM.

    /**
     * The state the R09 repair is about: the daily preference is disabled, yet the device is
     * genuinely LOCKED with this app's restrictive mask applied. Only a live temporary debug test
     * produces that combination (`enabled` deliberately stays false while the session is real), so
     * it is the state in which a handler keying off `enabled` alone publishes DISARMED over a
     * restricted device. The assertions here also prove the setup itself, so a test that silently
     * stopped reaching this state would fail rather than pass vacuously.
     */
    private suspend fun restrictedLiveTemporaryTest(): H {
        val h = H()
        h.seedSettings(enabled = false)
        h.seedBaseline(lifecycle = BaselineLifecycleState.RESTORED)

        val started = h.coordinator.activatePocSession()
        assertEquals(ProtectionState.ARMED_POWER_MENU_ALLOWED, started.state)
        val restricted = h.coordinator.setPocOverride(PocOverride.FORCE_RESTRICTED)
        assertEquals(ProtectionState.ARMED_POWER_MENU_RESTRICTED, restricted.state)

        assertFalse(
            "temporary-test mode keeps the daily preference disabled on purpose",
            h.settingsStore.current.enabled,
        )
        assertEquals(LockTaskRuntimeStates.LOCKED, h.runtime.state)
        assertEquals(LockTaskMasks.PROTECTED, h.policyGateway.features)
        assertNotNull("a live temporary test must have its durable marker", h.settingsStore.marker)
        return h
    }

    private companion object {
        const val ID = "com.example.shutdownprotection"

        /** Inside the default 02:00-05:00 UTC interval used by every default-schedule test. */
        val INSIDE_WINDOW: Instant = Instant.parse("2026-06-15T03:00:00Z")
    }
}

/** Gateway wrapper that records every mask submission, so call ordering can be asserted. */
private fun loggingGateway(
    base: FakeDevicePolicyGateway,
    log: MutableList<String>,
): DevicePolicyGateway = object : DevicePolicyGateway by base {
    override fun submitLockTaskFeatures(features: Int) {
        log += "applyFeatures:$features"
        base.submitLockTaskFeatures(features)
    }
}

/** Scheduling wrapper that records plan installs, so call ordering can be asserted. */
private fun loggingScheduling(
    base: FakeSchedulingGateway,
    log: MutableList<String>,
): SchedulingGateway = object : SchedulingGateway by base {
    override fun installPlan(
        nextEnd: Instant,
        nextStart: Instant,
        revision: Long,
        bootGeneration: Long,
    ): AlarmInstallResult {
        log += "installPlan"
        return base.installPlan(nextEnd, nextStart, revision, bootGeneration)
    }
}

/**
 * Feature readback that stops answering once the release confirmation has been taken.
 *
 * Throwing on *every* call would abort the recovery sequence before it could produce a result at
 * all - the release confirmation itself could never be verified and the raw final readback would
 * propagate an exception instead of a [com.example.shutdownprotection.protection.RecoveryResult].
 * The readback therefore becomes unknown from the baseline comparison onward, which is exactly the
 * case under test: the baseline cannot be confirmed because the readback is unknown.
 */
private fun unknownBaselineReadbackGateway(base: FakeDevicePolicyGateway): DevicePolicyGateway =
    object : DevicePolicyGateway by base {
        private var reads = 0

        override fun readLockTaskFeatures(): Int? {
            reads++
            if (reads > 1) throw IllegalStateException("simulated unknown feature readback")
            return base.readLockTaskFeatures()
        }
    }

/**
 * Feature readback that never answers, so every observation of the mask is *unknown* rather than a
 * value. Used to prove the observation-only refresh never converts an unreadable readback into a
 * success claim (repair R05). This is the `null` input; the known-`PROTECTED` input is covered by
 * `review.ReviewRegressionTest`.
 */
private fun unreadableFeatureReadbackGateway(base: FakeDevicePolicyGateway): DevicePolicyGateway =
    object : DevicePolicyGateway by base {
        override fun readLockTaskFeatures(): Int =
            throw IllegalStateException("simulated unreadable feature readback")
    }
