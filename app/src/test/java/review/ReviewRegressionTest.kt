package review

import com.example.shutdownprotection.admin.*
import com.example.shutdownprotection.data.*
import com.example.shutdownprotection.fakes.*
import com.example.shutdownprotection.protection.*
import com.example.shutdownprotection.scheduling.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import java.time.*

class ReviewRegressionTest {
    private class H(
        val now: Instant = Instant.parse("2026-06-15T03:00:00Z"),
        transform: (FakeSettingsRepository) -> SettingsRepository = { it },
        /**
         * Added during the repair so a test can inject a *specific* policy-write failure rather
         * than a global one. The global `ignoreFeatureWrites` knob aborts recovery earlier than
         * several tests assume, which made their assertions pass for the wrong reason.
         */
        transformGateway: (FakeDevicePolicyGateway) -> DevicePolicyGateway = { it },
        timeout: Long = 5000,
    ) {
        val underlying = FakeSettingsRepository()
        val settings = transform(underlying)
        val diagnostics = FakeDiagnosticsRepository()
        val rawGateway = FakeDevicePolicyGateway()
        val gateway: DevicePolicyGateway = transformGateway(rawGateway)
        val runtime = FakeRuntimeLockTaskStateProvider()
        val session = FakeLockTaskSessionController(runtime)
        val scheduling = FakeSchedulingGateway()
        val inhibitor = RestrictionInhibitor()
        val policy = DevicePolicyController(gateway, runtime, ID)
        val recovery = RecoveryManager(settings, diagnostics, policy, scheduling, session, inhibitor, sleep = {})
        val coordinator = ProtectionCoordinator(
            ID, settings, diagnostics, policy,
            ScheduleCalculator(Clock.fixed(now, ZoneOffset.UTC), { ZoneOffset.UTC }),
            scheduling, recovery, session, FakeEnvironmentStateProvider(), inhibitor,
            operationTimeoutMillis = timeout, sleep = {},
        )
        init { underlying.seed(ProtectionSettings(enabled = true, allowedPackages = setOf(ID))) }
        fun restricted() {
            runtime.state = LockTaskRuntimeStates.LOCKED
            rawGateway.features = LockTaskMasks.PROTECTED
            rawGateway.packages = mutableSetOf(ID)
        }
    }

    @Test fun recovery_must_not_claim_verified_when_baseline_readback_disagrees() = runTest {
        // The second setter is the original-policy restore. Its immediate controller snapshot is
        // deliberately allowed to report the requested value once, then every later feature read
        // returns the actual stale permissive mask. This reaches RecoveryManager's independent
        // post-set readback loop instead of failing earlier in DevicePolicyController.applyFeatures.
        var featureReadsAfterBaselineWrite = 0
        var baselineWriteSeen = false
        var cleanupCommitCalls = 0
        val h = H(
            transform = { base ->
                object : SettingsRepository by base {
                    override suspend fun commitCleanup(commit: CleanupCommit): SettingsWriteResult {
                        cleanupCommitCalls++
                        return base.commitCleanup(commit)
                    }
                }
            },
            transformGateway = { base ->
                object : DevicePolicyGateway by base {
                    private var featureWrites = 0
                    override fun submitLockTaskFeatures(features: Int) {
                        featureWrites++
                        if (featureWrites == 1) {
                            base.submitLockTaskFeatures(features)
                        } else {
                            baselineWriteSeen = true // ignore the original-mask write
                        }
                    }

                    override fun readLockTaskFeatures(): Int? {
                        if (baselineWriteSeen) {
                            featureReadsAfterBaselineWrite++
                            if (featureReadsAfterBaselineWrite == 1) return 16
                        }
                        return base.readLockTaskFeatures()
                    }
                }
            },
        )
        h.restricted()
        h.underlying.baseline = PolicyBaseline(
            0, setOf("original.app"), 16, ID,
            BaselineProvenance.CAPTURED_BEFORE_PREPARATION,
            BaselineLifecycleState.PREPARED,
        )
        val r = h.recovery.recover("REVIEW")
        println("baseline_readback: verified=${r.verified} step=${r.failedStep} actualFeatures=${h.rawGateway.features} expectedBaseline=16")
        assertFalse("Restore was not confirmed, but recovery claims verification", r.verified)
        assertEquals(
            "the independent post-set baseline readback must actually have been reached",
            "RESTORE_BASELINE_READBACK",
            r.failedStep,
        )
        assertTrue("the baseline setter snapshot and retry loop must perform multiple reads", featureReadsAfterBaselineWrite >= 3)
        assertFalse("a mismatching original baseline must not reach the final cleanup commit", cleanupCommitCalls > 0)
        assertFalse(r.baselineRestored)
    }

    @Test fun recovery_must_not_claim_verified_when_final_cleanup_write_fails() = runTest {
        val h = H(transform = { base ->
            object : SettingsRepository by base {
                // ADAPTED FOR REPAIR R03 (guide section 4.3 step 7). The required cleanup fields are
                // now committed through ONE atomic `commitCleanup` call instead of a bare
                // `editSettings`, so that is where the failure is injected.
                //
                // CORRECTED AFTER INDEPENDENT AUDIT: a valid baseline must be seeded, otherwise
                // recovery returns RESTORE_BASELINE_UNAVAILABLE and never calls `commitCleanup` at
                // all — the injected failure was dead code and the assertion below passed for an
                // unrelated reason.
                override suspend fun commitCleanup(commit: CleanupCommit) =
                    SettingsWriteResult.Failure(IllegalStateException("final commit failed"))
            }
        })
        h.restricted()
        h.underlying.baseline = PolicyBaseline(
            0, emptySet(), 0, ID,
            BaselineProvenance.CAPTURED_BEFORE_PREPARATION,
            BaselineLifecycleState.PREPARED,
        )
        val r = h.recovery.recover("REVIEW")
        println("cleanup_write: verified=${r.verified} step=${r.failedStep} durableRecoveryRequired=${h.underlying.current.recoveryRequired}")
        assertFalse("Final durable cleanup failed, but recovery claims verification", r.verified)
        assertEquals(
            "the final cleanup commit must actually have been reached",
            "PERSIST_FINAL_CLEANUP",
            r.failedStep,
        )
        assertTrue("durable cleanup is unverified", h.underlying.current.recoveryRequired)
    }

    @Test fun observation_refresh_must_not_claim_allowed_while_the_mask_is_still_restricted() = runTest {
        // ADDED AFTER INDEPENDENT AUDIT: this is the path on which the R05 defect actually
        // survived. The refresh reported ARMED_POWER_MENU_ALLOWED outside the interval regardless
        // of the effective mask, so a delayed or missed release displayed "Allowed" while the
        // restrictive mask was still applied.
        val h = H(now = Instant.parse("2026-06-15T06:00:00Z")) // outside 02:00-05:00
        h.restricted() // LOCKED + PROTECTED
        val s = h.coordinator.refreshObservation()
        println("refresh_outside: state=${s.state} features=${h.rawGateway.features}")
        assertNotEquals(
            "A live restrictive mask must never be reported as allowed",
            ProtectionState.ARMED_POWER_MENU_ALLOWED,
            s.state,
        )
    }

    @Test fun arming_must_abort_when_baseline_cannot_be_saved() = runTest {
        val h = H(transform = { base ->
            object : SettingsRepository by base {
                // ADAPTED FOR REPAIR R02 (guide section 4.3 step 7).
                // Arming now writes the original baseline and the preparation journal through one
                // atomic `prepareSession` call, so the failure is injected there rather than at
                // `writeBaseline`. The safety assertion below is unchanged: no policy mutation and
                // no session entry may occur without a durably saved baseline.
                override suspend fun prepareSession(
                    candidateBaseline: PolicyBaseline,
                    expectedApplicationId: String,
                    temporaryMarker: TemporaryTestMarker?,
                ): SessionPreparationResult =
                    SessionPreparationResult.Failed(IllegalStateException("baseline write failed"))
            }
        })
        h.underlying.seed(h.underlying.current.copy(enabled = false))
        val s = h.coordinator.arm()
        println("baseline_save: state=${s.state} features=${h.rawGateway.features} runtime=${h.runtime.state} savedBaseline=${h.underlying.baseline}")
        assertEquals("No policy mutation/session should occur without durable baseline", LockTaskRuntimeStates.NONE, h.runtime.state)
    }

    @Test fun end_alarm_timeout_must_not_leave_restriction_with_release_moved_to_tomorrow() = runTest {
        val h = H(now = Instant.parse("2026-06-15T05:00:00Z"), timeout = 50, transform = { base ->
            object : SettingsRepository by base {
                override suspend fun writeScheduleReceipt(receipt: ScheduleReceipt?): SettingsWriteResult {
                    delay(1000)
                    return base.writeScheduleReceipt(receipt)
                }
            }
        })
        h.restricted()
        val s = h.coordinator.reconcile(ProtectionTrigger.END_ALARM)
        println("end_timeout: state=${s.state} features=${h.rawGateway.features} runtime=${h.runtime.state} replacementEnd=${h.scheduling.lastInstalledEnd}")
        assertEquals("The current restriction must be released before installing tomorrow's plan", LockTaskMasks.ALLOWED, h.rawGateway.features)
    }

    @Test fun invalid_enabled_schedule_must_trigger_release_not_just_an_error_label() = runTest {
        val h = H()
        h.restricted()
        h.underlying.seed(h.underlying.current.copy(startMinuteOfDay = 120, endMinuteOfDay = 120))
        val s = h.coordinator.reconcile(ProtectionTrigger.END_ALARM)
        println("invalid_settings: state=${s.state} features=${h.rawGateway.features} runtime=${h.runtime.state}")
        assertEquals("Invalid settings must not leave an existing restriction active", LockTaskRuntimeStates.NONE, h.runtime.state)
    }

    @Test fun outside_window_must_not_claim_menu_allowed_when_readback_is_still_restricted() = runTest {
        val h = H(now = Instant.parse("2026-06-15T06:00:00Z"))
        h.restricted()
        h.rawGateway.ignoreFeatureWrites = true
        val s = h.coordinator.reconcile(ProtectionTrigger.END_ALARM)
        println("false_allowed: state=${s.state} actualFeatures=${h.rawGateway.features}")
        assertNotEquals("An unconfirmed permissive request must not be shown as allowed", ProtectionState.ARMED_POWER_MENU_ALLOWED, s.state)
    }

    @Test fun temporary_test_must_not_start_if_its_atomic_marker_preparation_fails() = runTest {
        var preparationCalls = 0
        val h = H(transform = { base ->
            object : SettingsRepository by base {
                override suspend fun prepareSession(
                    candidateBaseline: PolicyBaseline,
                    expectedApplicationId: String,
                    temporaryMarker: TemporaryTestMarker?,
                ): SessionPreparationResult {
                    preparationCalls++
                    return SessionPreparationResult.Failed(IllegalStateException("test marker preparation failed"))
                }
            }
        })
        h.underlying.seed(h.underlying.current.copy(enabled = false))
        val s = h.coordinator.activatePocSession()
        println("temporary_marker: state=${s.state} runtime=${h.runtime.state} marker=${h.underlying.marker} enabled=${h.underlying.current.enabled}")
        assertEquals("the atomic baseline-plus-marker preparation injection must be reached", 1, preparationCalls)
        assertEquals("A temporary test requires its crash-recovery marker", LockTaskRuntimeStates.NONE, h.runtime.state)
    }

    companion object { const val ID = "com.example.shutdownprotection" }
}
