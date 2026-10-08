package com.example.shutdownprotection.repair

import com.example.shutdownprotection.admin.DevicePolicyController
import com.example.shutdownprotection.admin.DevicePolicyGateway
import com.example.shutdownprotection.admin.LockTaskMasks
import com.example.shutdownprotection.admin.LockTaskRuntimeStates
import com.example.shutdownprotection.data.BaselineLifecycleState
import com.example.shutdownprotection.data.BaselineProvenance
import com.example.shutdownprotection.data.CleanupCommit
import com.example.shutdownprotection.data.DiagnosticsRepository
import com.example.shutdownprotection.data.PolicyBaseline
import com.example.shutdownprotection.data.ProtectionSettings
import com.example.shutdownprotection.data.RecoveryIncident
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
import com.example.shutdownprotection.protection.ProtectionCoordinator
import com.example.shutdownprotection.protection.ProtectionState
import com.example.shutdownprotection.protection.ProtectionTrigger
import com.example.shutdownprotection.protection.ReleaseIncidentOpenResult
import com.example.shutdownprotection.protection.RecoveryManager
import com.example.shutdownprotection.protection.RestrictionInhibitor
import com.example.shutdownprotection.scheduling.ScheduleCalculator
import com.example.shutdownprotection.scheduling.SchedulingGateway
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

/** Deadline and cancellation regressions for every safety-critical auxiliary store read. */
@OptIn(ExperimentalCoroutinesApi::class)
class CoreDeadlineSafetyTest {

    private enum class ReadHook {
        SETTINGS,
        BASELINE,
        TEMP_MARKER,
        RECOVERY_INCIDENT,
        SCHEDULE_RECEIPT,
        BOOT_GENERATION,
        CORRUPTION_FLAG,
        OWNER_HISTORY,
    }

    private enum class FaultMode { THROW_IO, SUSPEND_UNTIL_CANCELLED }

    /** One-shot injection with a per-hook invocation count, including an entry into a suspended read. */
    private class ReadFault(
        private val selected: ReadHook,
        private val mode: FaultMode,
    ) {
        private val invocations = mutableMapOf<ReadHook, Int>()
        private var injected = false
        private var injectionEnabled = true

        fun calls(hook: ReadHook): Int = invocations[hook] ?: 0
        fun wasInjected(): Boolean = injected

        fun disableInjection() {
            injectionEnabled = false
        }

        fun rearm() {
            injectionEnabled = true
            injected = false
        }

        suspend fun before(hook: ReadHook) {
            invocations[hook] = calls(hook) + 1
            if (!injectionEnabled || hook != selected || injected) return
            injected = true
            when (mode) {
                FaultMode.THROW_IO -> throw IOException("injected $hook read failure")
                FaultMode.SUSPEND_UNTIL_CANCELLED -> awaitCancellation()
            }
        }
    }

    private class H(
        val now: Instant = INSIDE_WINDOW,
        val operationTimeoutMillis: Long = 2_300L,
        val armTimeoutMillis: Long = 3_200L,
        transformSettings: (FakeSettingsRepository) -> SettingsRepository = { it },
        transformDiagnostics: (FakeDiagnosticsRepository) -> DiagnosticsRepository = { it },
        transformGateway: (FakeDevicePolicyGateway) -> DevicePolicyGateway = { it },
        transformScheduling: (FakeSchedulingGateway) -> SchedulingGateway = { it },
    ) {
        var nowMillis: Long = 10_000L
        private var incidentSequence = 0

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

        val policy = DevicePolicyController(gateway, runtime, APP_ID, clockMillis = { nowMillis })
        val recovery = RecoveryManager(
            settingsRepository = settings,
            diagnostics = diagnostics,
            policyController = policy,
            scheduleManager = scheduling,
            lockTaskSession = session,
            inhibitor = inhibitor,
            clockMillis = { nowMillis },
            newIncidentId = { "deadline-incident-${++incidentSequence}" },
            sleep = {},
        )
        val coordinator = ProtectionCoordinator(
            applicationId = APP_ID,
            settingsRepository = settings,
            diagnostics = diagnostics,
            policyController = policy,
            scheduleCalculator = ScheduleCalculator(
                Clock.fixed(now, ZoneOffset.UTC),
                { ZoneOffset.UTC },
            ),
            scheduleManager = scheduling,
            recoveryManager = recovery,
            lockTaskSession = session,
            environment = environment,
            inhibitor = inhibitor,
            clockMillis = { nowMillis },
            operationTimeoutMillis = operationTimeoutMillis,
            armTimeoutMillis = armTimeoutMillis,
            sleep = {},
        )

        fun seedSettings(enabled: Boolean, recoveryRequired: Boolean, revision: Long = 4L) {
            settingsStore.seed(
                ProtectionSettings(
                    enabled = enabled,
                    revision = revision,
                    allowedPackages = setOf(APP_ID),
                    recoveryRequired = recoveryRequired,
                ),
            )
        }

        fun seedTrustedBaseline(features: Int = 16, packages: Set<String> = setOf(EXTERNAL_PACKAGE)) {
            settingsStore.baseline = PolicyBaseline(
                capturedAtEpochMillis = 100L,
                lockTaskPackages = packages,
                lockTaskFeatures = features,
                applicationId = APP_ID,
                provenance = BaselineProvenance.CAPTURED_BEFORE_PREPARATION,
                lifecycle = BaselineLifecycleState.PREPARED,
            )
        }

        fun seedLiveRestriction(enabled: Boolean = false, recoveryRequired: Boolean = true) {
            seedSettings(enabled = enabled, recoveryRequired = recoveryRequired)
            seedTrustedBaseline()
            policyGateway.owner = true
            policyGateway.features = LockTaskMasks.PROTECTED
            policyGateway.packages = mutableSetOf(APP_ID)
            runtime.state = LockTaskRuntimeStates.LOCKED
        }
    }

    @Test
    fun every_recovery_evidence_read_failure_and_cooperative_stall_is_bounded_and_releases() = runTest {
        val hooks = listOf(
            ReadHook.SETTINGS,
            ReadHook.BASELINE,
            ReadHook.TEMP_MARKER,
            ReadHook.RECOVERY_INCIDENT,
            ReadHook.CORRUPTION_FLAG,
            ReadHook.OWNER_HISTORY,
        )
        for (hook in hooks) {
            for (mode in FaultMode.entries) {
                val fault = ReadFault(hook, mode)
                val h = H(
                    transformSettings = { base -> faultingRepository(base, fault) },
                )
                h.seedLiveRestriction()
                val elapsedBefore = testScheduler.currentTime

                // Recovery captures all evidence through bounded reads before it acts. A thrown IO
                // failure or a cooperative operation that never resumes must therefore remain an
                // unknown input while release and restoration continue from the other evidence.
                val status = withTimeout(7_999L) { h.coordinator.disableAndRestore() }
                val elapsed = testScheduler.currentTime - elapsedBefore

                assertTrue("$hook/$mode must reach its injected read", fault.calls(hook) > 0)
                assertTrue("$hook/$mode exceeded the receiver-safe operation envelope: $elapsed ms", elapsed < 8_000L)
                assertTrue("$hook/$mode must request task stop", h.session.stopRequests > 0)
                assertTrue("$hook/$mode must submit the independent empty-allowlist exit", h.policyGateway.packageSubmissions.contains(emptySet()))
                assertEquals("$hook/$mode must leave runtime Lock Task", LockTaskRuntimeStates.NONE, h.runtime.state)
                assertEquals("$hook/$mode cannot start a new session", 0, h.session.startRequests)
                assertFalse("$hook/$mode must not submit a new restrictive mask", h.policyGateway.featureSubmissions.contains(LockTaskMasks.PROTECTED))

                if (hook == ReadHook.BASELINE) {
                    assertEquals("unknown original evidence must remain an honest failure", ProtectionState.RECOVERY_FAILED, status.state)
                    assertEquals("READ_BASELINE", status.recoveryIncompleteStep)
                    assertTrue("baseline read failure must retain the recovery journal", h.settingsStore.current.recoveryRequired)
                    assertNotNull("baseline read failure must remain durable as an incident", h.settingsStore.incident)
                    assertNotEquals("unknown baseline must not be claimed restored", 16, h.policyGateway.features)
                } else {
                    assertEquals("$hook/$mode with known baseline should complete recovery", ProtectionState.DISARMED, status.state)
                    assertEquals(16, h.policyGateway.features)
                    assertEquals(setOf(EXTERNAL_PACKAGE), h.policyGateway.packages)
                    assertFalse(h.settingsStore.current.recoveryRequired)
                    assertNull(h.settingsStore.incident)
                }
            }
        }
    }

    @Test
    fun coordinator_read_exceptions_and_cooperative_stalls_reach_release_fallback_before_any_new_plan() = runTest {
        // These reads are reached directly by public reconciliation before recovery starts. Their
        // failures must enter the coordinator's bounded exception/timeout fallback rather than
        // leaking out or allowing schedule work to continue. BASELINE is reached by the disabled
        // branch's lifecycle check; the remaining reads use an enabled, outside-window session.
        val hooks = listOf(
            ReadHook.SETTINGS,
            ReadHook.BASELINE,
            ReadHook.TEMP_MARKER,
            ReadHook.RECOVERY_INCIDENT,
            ReadHook.SCHEDULE_RECEIPT,
            ReadHook.BOOT_GENERATION,
        )
        for (hook in hooks) {
            for (mode in FaultMode.entries) {
                val fault = ReadFault(hook, mode)
                val h = H(
                    now = OUTSIDE_WINDOW,
                    transformSettings = { base -> faultingRepository(base, fault) },
                )
                h.seedLiveRestriction(
                    enabled = hook != ReadHook.BASELINE,
                    recoveryRequired = false,
                )
                val plansBefore = h.schedulingGateway.installPlanCount
                val before = testScheduler.currentTime

                val status = withTimeout(7_999L) {
                    h.coordinator.reconcile(ProtectionTrigger.END_ALARM)
                }
                val elapsed = testScheduler.currentTime - before

                assertTrue("reconcile $hook/$mode must enter the selected read", fault.calls(hook) > 0)
                assertTrue("reconcile $hook/$mode exceeded 8 s: $elapsed ms", elapsed < 8_000L)
                assertTrue("reconcile $hook/$mode must attempt stop", h.session.stopRequests > 0)
                assertTrue(
                    "reconcile $hook/$mode must attempt the independent package exit",
                    h.policyGateway.packageSubmissions.contains(emptySet()),
                )
                assertEquals(LockTaskRuntimeStates.NONE, h.runtime.state)
                assertEquals("no failure path may enter Lock Task", 0, h.session.startRequests)
                assertFalse(
                    "read failure/stall cannot apply or report a fresh restrictive state",
                    status.state in setOf(
                        ProtectionState.ARMED_POWER_MENU_RESTRICTED,
                        ProtectionState.ARMED_POWER_MENU_ALLOWED,
                    ),
                )
                assertFalse(
                    "the failing reconciliation must not submit a restrictive mask",
                    h.policyGateway.featureSubmissions.contains(LockTaskMasks.PROTECTED),
                )
                assertEquals(
                    "the exception/timeout path must not install a new alarm plan",
                    plansBefore,
                    h.schedulingGateway.installPlanCount,
                )
            }
        }
    }

    @Test
    fun owner_history_reconcile_status_read_failure_and_stall_fall_back_without_replacing_plan() = runTest {
        for (mode in FaultMode.entries) {
            val fault = ReadFault(ReadHook.OWNER_HISTORY, mode)
            val h = H(
                now = OUTSIDE_WINDOW,
                transformSettings = { base -> faultingRepository(base, fault) },
            )
            h.seedSettings(enabled = true, recoveryRequired = false)
            h.seedTrustedBaseline()
            h.policyGateway.owner = true
            h.policyGateway.features = 16
            h.policyGateway.packages = mutableSetOf(EXTERNAL_PACKAGE)
            h.runtime.state = LockTaskRuntimeStates.NONE

            // A prior successful pass makes the plan current, so the faulted observation below
            // can reach statusFromObservation without installing a replacement plan first.
            fault.disableInjection()
            h.coordinator.reconcile(ProtectionTrigger.BOOT_UNLOCKED)
            assertNotNull("the warm pass must create the current receipt", h.settingsStore.receipt)
            assertTrue("the warm pass must establish process-local submission evidence", h.schedulingGateway.installPlanCount > 0)
            val plansBefore = h.schedulingGateway.installPlanCount
            val ownerHistoryReadsBefore = fault.calls(ReadHook.OWNER_HISTORY)

            h.seedLiveRestriction(enabled = true, recoveryRequired = false)
            fault.rearm()
            val before = testScheduler.currentTime
            val status = withTimeout(7_999L) {
                h.coordinator.reconcile(ProtectionTrigger.END_ALARM)
            }
            val elapsed = testScheduler.currentTime - before

            assertTrue("owner-history/$mode must fail in the coordinator's final observation", fault.calls(ReadHook.OWNER_HISTORY) > ownerHistoryReadsBefore)
            assertTrue("owner-history/$mode exceeded 8 s: $elapsed ms", elapsed < 8_000L)
            assertTrue(h.session.stopRequests > 0)
            assertTrue(h.policyGateway.packageSubmissions.contains(emptySet()))
            assertEquals(LockTaskRuntimeStates.NONE, h.runtime.state)
            assertEquals(0, h.session.startRequests)
            assertFalse(status.state in setOf(ProtectionState.ARMED_POWER_MENU_RESTRICTED, ProtectionState.ARMED_POWER_MENU_ALLOWED))
            assertFalse(h.policyGateway.featureSubmissions.contains(LockTaskMasks.PROTECTED))
            assertEquals("a failing status observation cannot install another plan", plansBefore, h.schedulingGateway.installPlanCount)
        }
    }

    @Test
    fun corruption_flag_read_failure_and_stall_are_conservative_in_the_pre_entry_guard() = runTest {
        // reconcile does not read this flag directly. The real entry route is arm's shared guard,
        // which classifies an unreadable/cancelled flag as corruption and invokes recovery before
        // any new session or restrictive policy can be started.
        for (mode in FaultMode.entries) {
            val fault = ReadFault(ReadHook.CORRUPTION_FLAG, mode)
            val h = H(transformSettings = { base -> faultingRepository(base, fault) })
            h.seedLiveRestriction(enabled = true, recoveryRequired = false)
            val before = testScheduler.currentTime

            val status = withTimeout(7_999L) { h.coordinator.arm() }
            val elapsed = testScheduler.currentTime - before

            assertTrue("corruption flag/$mode must be read by the pre-entry guard", fault.calls(ReadHook.CORRUPTION_FLAG) > 0)
            assertTrue("corruption flag/$mode exceeded 8 s: $elapsed ms", elapsed < 8_000L)
            assertTrue("an existing session must receive a stop request", h.session.stopRequests > 0)
            assertTrue("the independent empty-allowlist exit must be attempted", h.policyGateway.packageSubmissions.contains(emptySet()))
            assertEquals(LockTaskRuntimeStates.NONE, h.runtime.state)
            assertEquals("entry must be refused", 0, h.session.startRequests)
            assertFalse("the guard must not start a restrictive session", h.policyGateway.featureSubmissions.contains(LockTaskMasks.PROTECTED))
            assertFalse(status.state in setOf(ProtectionState.ARMED_POWER_MENU_RESTRICTED, ProtectionState.ARMED_POWER_MENU_ALLOWED))
        }
    }

    @Test
    fun status_receipt_read_failure_or_stall_cannot_escape_or_claim_success() = runTest {
        for (mode in FaultMode.entries) {
            val fault = ReadFault(ReadHook.SCHEDULE_RECEIPT, mode)
            fault.disableInjection()
            var successfulCleanupCommits = 0
            var postCleanupReceiptReadCalls = 0
            val h = H(
                transformSettings = { base ->
                    val delegate = faultingRepository(base, fault)
                    object : SettingsRepository by delegate {
                        override suspend fun commitCleanup(commit: CleanupCommit): SettingsWriteResult {
                            val result = base.commitCleanup(commit)
                            if (result is SettingsWriteResult.Success) {
                                successfulCleanupCommits++
                                fault.rearm()
                            }
                            return result
                        }

                        override suspend fun readScheduleReceipt(): ScheduleReceipt? {
                            if (successfulCleanupCommits > 0) postCleanupReceiptReadCalls++
                            return delegate.readScheduleReceipt()
                        }
                    }
                },
            )
            h.seedLiveRestriction()
            val before = testScheduler.currentTime

            val status = withTimeout(7_999L) { h.coordinator.disableAndRestore() }
            val elapsed = testScheduler.currentTime - before

            assertEquals("$mode must pass one successful atomic cleanup before final status observation", 1, successfulCleanupCommits)
            assertTrue("$mode must reach schedule-receipt observation after cleanup", postCleanupReceiptReadCalls > 0)
            assertTrue("$mode must fire its one-shot fault after cleanup", fault.wasInjected())
            assertTrue("status receipt/$mode must reach its injected read", fault.calls(ReadHook.SCHEDULE_RECEIPT) > 0)
            assertTrue("status receipt/$mode exceeded 8 s: $elapsed ms", elapsed < 8_000L)
            assertNotEquals("an unreadable or stalled status input cannot become a successful state", ProtectionState.DISARMED, status.state)
            assertNotNull("status receipt/$mode must identify incomplete observation", status.recoveryIncompleteStep)
            assertTrue("initial recovery must have stopped the existing task", h.session.stopRequests >= 1)
            assertTrue("initial recovery must have submitted the empty package exit", h.policyGateway.packageSubmissions.contains(emptySet()))
            assertEquals(LockTaskRuntimeStates.NONE, h.runtime.state)
            assertFalse(h.session.startRequests > 0)
            assertFalse(h.policyGateway.featureSubmissions.contains(LockTaskMasks.PROTECTED))
            assertFalse("status failure must not claim the old restriction remains successful", status.state == ProtectionState.ARMED_POWER_MENU_RESTRICTED)
        }
    }

    @Test
    fun outside_window_mask_failure_releases_without_advancing_or_claiming_allowed() = runTest {
        val h = H(now = OUTSIDE_WINDOW)
        h.seedLiveRestriction(enabled = true, recoveryRequired = false)
        h.policyGateway.ignoreFeatureWrites = true
        val plansBefore = h.schedulingGateway.installPlanCount
        val started = testScheduler.currentTime

        val status = withTimeout(7_999L) { h.coordinator.reconcile(ProtectionTrigger.END_ALARM) }

        assertTrue(testScheduler.currentTime - started < 8_000L)
        assertEquals("a refused permissive write cannot be reported as allowed", ProtectionState.RECOVERY_FAILED, status.state)
        assertNotEquals(ProtectionState.ARMED_POWER_MENU_ALLOWED, status.state)
        assertTrue("the permissive mask request must actually be attempted", h.policyGateway.featureSubmissions.contains(LockTaskMasks.ALLOWED))
        assertTrue("the session stop remains independent of feature mask failure", h.session.stopRequests > 0)
        assertTrue("the package-removal exit remains independent of feature mask failure", h.policyGateway.packageSubmissions.contains(emptySet()))
        assertEquals(LockTaskRuntimeStates.NONE, h.runtime.state)
        assertEquals("a failed release must not replace its current plan", plansBefore, h.schedulingGateway.installPlanCount)
        assertFalse("the coordinator must not newly apply a restrictive mask", h.policyGateway.featureSubmissions.contains(LockTaskMasks.PROTECTED))
        assertNotNull(h.settingsStore.incident)
        assertTrue(h.inhibitor.isInhibited)
    }

    @Test
    fun boot_generation_read_failure_or_stall_after_release_is_bounded_and_never_restricts() = runTest {
        for (mode in FaultMode.entries) {
            val fault = ReadFault(ReadHook.BOOT_GENERATION, mode)
            val h = H(
                now = OUTSIDE_WINDOW,
                transformSettings = { base -> faultingRepository(base, fault) },
            )
            h.seedLiveRestriction(enabled = true, recoveryRequired = false)
            val plansBefore = h.schedulingGateway.installPlanCount
            val before = testScheduler.currentTime

            val status = withTimeout(7_999L) { h.coordinator.reconcile(ProtectionTrigger.END_ALARM) }
            val elapsed = testScheduler.currentTime - before

            assertTrue("boot generation/$mode must reach its injected read", fault.calls(ReadHook.BOOT_GENERATION) > 0)
            assertTrue("boot generation/$mode exceeded 8 s: $elapsed ms", elapsed < 8_000L)
            assertNotEquals(ProtectionState.ARMED_POWER_MENU_RESTRICTED, status.state)
            assertNotEquals(ProtectionState.ARMED_POWER_MENU_ALLOWED, status.state)
            assertNotNull(status.recoveryIncompleteStep)
            assertTrue("existing locked session must receive stop request", h.session.stopRequests > 0)
            assertTrue("release must submit empty allowlist", h.policyGateway.packageSubmissions.contains(emptySet()))
            assertEquals(LockTaskRuntimeStates.NONE, h.runtime.state)
            assertEquals(0, h.session.startRequests)
            assertEquals("failed plan read must not install a fresh alarm plan", plansBefore, h.schedulingGateway.installPlanCount)
            assertFalse(h.policyGateway.featureSubmissions.contains(LockTaskMasks.PROTECTED))
        }
    }

    @Test
    fun lock_wait_timeout_bounds_stalled_incident_write_and_final_status_read_without_stale_publish() = runTest {
        val receiptWriteEntered = CompletableDeferred<Unit>()
        val releaseReceiptWrite = CompletableDeferred<Unit>()
        val incidentWriteEntered = CompletableDeferred<Unit>()
        val finalReceiptReadEntered = CompletableDeferred<Unit>()
        var stallFinalReceiptRead = false
        var firstIncidentWrite = true
        var incidentWriteCalls = 0
        var finalReceiptReadCalls = 0
        val h = H(
            transformSettings = { base ->
                object : SettingsRepository by base {
                    override suspend fun writeScheduleReceipt(receipt: ScheduleReceipt?): SettingsWriteResult {
                        val result = base.writeScheduleReceipt(receipt)
                        if (receipt != null) {
                            receiptWriteEntered.complete(Unit)
                            releaseReceiptWrite.await()
                        }
                        return result
                    }

                    override suspend fun writeRecoveryIncident(incident: RecoveryIncident?): SettingsWriteResult {
                        incidentWriteCalls++
                        if (firstIncidentWrite) {
                            firstIncidentWrite = false
                            incidentWriteEntered.complete(Unit)
                            // RecoveryManager's evidence snapshot is complete before the incident
                            // write; arm the final-status receipt stall only after that snapshot.
                            stallFinalReceiptRead = true
                            awaitCancellation()
                        }
                        return base.writeRecoveryIncident(incident)
                    }

                    override suspend fun readScheduleReceipt(): ScheduleReceipt? {
                        if (stallFinalReceiptRead) {
                            stallFinalReceiptRead = false
                            finalReceiptReadCalls++
                            finalReceiptReadEntered.complete(Unit)
                            awaitCancellation()
                        }
                        return base.readScheduleReceipt()
                    }
                }
            },
        )
        h.seedSettings(enabled = false, recoveryRequired = false)
        h.policyGateway.owner = true
        h.policyGateway.features = 0
        h.policyGateway.packages = mutableSetOf()

        val holder = async { h.coordinator.arm() }
        runCurrent()
        receiptWriteEntered.await()
        // Arm is now known to hold the coordinator mutex. The injected incident write enables the
        // status-read stall only after RecoveryManager's new receipt evidence read has completed.
        val lockWaitOperation = async { h.coordinator.reconcile(ProtectionTrigger.END_ALARM) }
        runCurrent()

        val beforeTimeout = testScheduler.currentTime
        advanceTimeBy(ProtectionCoordinator.LOCK_WAIT_BUDGET_MILLIS + 1L)
        runCurrent()
        assertTrue("the lock-wait path must attempt durable incident creation", incidentWriteCalls > 0)
        incidentWriteEntered.await()
        // Its own 140 ms write bound cancels the first infinite write, then it reaches the bounded
        // final observation where readScheduleReceipt is parked. Release the mutex holder before
        // that status budget expires so a newer status can publish first.
        advanceTimeBy(RecoveryManager.STORE_WRITE_BUDGET_MILLIS + 1L)
        runCurrent()
        assertTrue("the lock-wait path must reach its final status receipt read", finalReceiptReadCalls > 0)
        finalReceiptReadEntered.await()

        releaseReceiptWrite.complete(Unit)
        runCurrent()
        val newerHolderStatus = holder.await()
        assertEquals("holder must refuse entry after shared inhibitor is engaged", 0, h.session.startRequests)
        assertNotEquals(ProtectionState.ARMED_POWER_MENU_RESTRICTED, newerHolderStatus.state)
        assertEquals("holder's completion must publish a newer status", newerHolderStatus, h.coordinator.status.value)

        // The lock-wait caller's separate final-status budget expires only after the new holder
        // result has published; its stale fallback is returned to that caller without overwriting
        // the shared status flow.
        advanceTimeBy(ProtectionCoordinator.TIMEOUT_STATUS_BUDGET_MILLIS + 1L)
        runCurrent()
        val lockWaitStatus = lockWaitOperation.await()
        val elapsed = testScheduler.currentTime - beforeTimeout

        assertEquals(ProtectionState.RECOVERY_FAILED, lockWaitStatus.state)
        assertEquals("LOCK_WAIT_TIMEOUT", lockWaitStatus.recoveryIncompleteStep)
        assertTrue("stalled incident and status writes must remain bounded", elapsed < 8_000L)
        assertEquals("stale lock-wait status must not overwrite the newer holder publication", newerHolderStatus, h.coordinator.status.value)
        assertNotEquals("LOCK_WAIT_TIMEOUT", h.coordinator.status.value?.recoveryIncompleteStep)
        assertFalse(h.policyGateway.featureSubmissions.contains(LockTaskMasks.PROTECTED))
        assertEquals(LockTaskRuntimeStates.NONE, h.runtime.state)
        assertNull("the timed-out incident write did not persist a false retry identity", h.settingsStore.incident)
    }

    @Test
    fun lock_wait_final_status_read_io_failure_returns_bounded_failure_without_escape_or_stale_success() = runTest {
        val receiptWriteEntered = CompletableDeferred<Unit>()
        val releaseReceiptWrite = CompletableDeferred<Unit>()
        var injectReceiptFailure = false
        var receiptFailureCalls = 0
        var incidentWriteCalls = 0
        var retryInstallCalls = 0
        val h = H(
            transformSettings = { base ->
                object : SettingsRepository by base {
                    override suspend fun writeScheduleReceipt(receipt: ScheduleReceipt?): SettingsWriteResult {
                        val result = base.writeScheduleReceipt(receipt)
                        if (receipt != null) {
                            receiptWriteEntered.complete(Unit)
                            releaseReceiptWrite.await()
                        }
                        return result
                    }

                    override suspend fun writeRecoveryIncident(incident: RecoveryIncident?): SettingsWriteResult {
                        incidentWriteCalls++
                        return base.writeRecoveryIncident(incident)
                    }

                    override suspend fun readScheduleReceipt(): ScheduleReceipt? {
                        if (injectReceiptFailure) {
                            injectReceiptFailure = false
                            receiptFailureCalls++
                            throw IOException("injected lock-wait final status receipt failure")
                        }
                        return base.readScheduleReceipt()
                    }
                }
            },
            transformScheduling = { base ->
                object : SchedulingGateway by base {
                    override fun installRecoveryRetry(incidentId: String, triggerAt: Instant): Boolean {
                        retryInstallCalls++
                        val installed = base.installRecoveryRetry(incidentId, triggerAt)
                        // RecoveryManager has completed all of the incident evidence reads by this
                        // point. The next receipt read belongs to lockWaitTimedOut's final status.
                        injectReceiptFailure = true
                        return installed
                    }
                }
            },
        )
        h.seedSettings(enabled = false, recoveryRequired = false)
        h.policyGateway.owner = true
        h.policyGateway.features = 0
        h.policyGateway.packages = mutableSetOf()

        val holder = async { h.coordinator.arm() }
        runCurrent()
        receiptWriteEntered.await()
        val statusBeforeFallback = h.coordinator.status.value
        val lockWaitOperation = async {
            try {
                h.coordinator.reconcile(ProtectionTrigger.END_ALARM)
            } catch (failure: IOException) {
                if (failure.message != "injected lock-wait final status receipt failure") throw failure
                throw AssertionError("lock-wait must return bounded failure rather than escape").also {
                    it.initCause(failure)
                }
            }
        }
        runCurrent()

        // Arm is parked while holding the real coordinator mutex. The retry-install hook arms the
        // one-shot fault after the fallback's evidence snapshot, so it reaches final status only.
        val before = testScheduler.currentTime
        advanceTimeBy(ProtectionCoordinator.LOCK_WAIT_BUDGET_MILLIS + 1L)
        runCurrent()
        val lockWaitStatus = lockWaitOperation.await()
        val elapsed = testScheduler.currentTime - before

        assertTrue("the lock-wait fallback must attempt incident persistence", incidentWriteCalls > 0)
        assertTrue("the fallback must install its retry before final status construction", retryInstallCalls > 0)
        assertEquals("the throwing read must be reached only in fallback status construction", 1, receiptFailureCalls)
        assertEquals(ProtectionState.RECOVERY_FAILED, lockWaitStatus.state)
        assertEquals("LOCK_WAIT_TIMEOUT", lockWaitStatus.recoveryIncompleteStep)
        assertTrue("fallback status must remain within its receiver-safe deadline: $elapsed ms", elapsed < 8_000L)
        assertTrue("the release brake must remain engaged for this unresolved preparation", h.inhibitor.isInhibited)
        assertTrue("the fallback's durable incident must remain visible", h.settingsStore.incident?.unresolved == true)
        assertTrue(h.settingsStore.current.recoveryRequired)
        assertEquals("unlocked fallback status must not publish over its holder", statusBeforeFallback, h.coordinator.status.value)

        releaseReceiptWrite.complete(Unit)
        runCurrent()
        val holderStatus = holder.await()
        assertEquals("the holder must not enter after the fallback engaged the brake", 0, h.session.startRequests)
        assertFalse(h.policyGateway.featureSubmissions.contains(LockTaskMasks.PROTECTED))
        assertEquals(LockTaskRuntimeStates.NONE, h.runtime.state)
        assertNotEquals(ProtectionState.ARMED_POWER_MENU_RESTRICTED, holderStatus.state)
        assertEquals("the holder's later result remains the shared status", holderStatus, h.coordinator.status.value)
    }

    @Test
    fun serialized_unit_lock_wait_final_status_cancellation_remains_cancellation() = runTest {
        val receiptWriteEntered = CompletableDeferred<Unit>()
        val releaseReceiptWrite = CompletableDeferred<Unit>()
        val finalReceiptReadEntered = CompletableDeferred<Unit>()
        var stallNextReceiptRead = false
        var retryInstallCalls = 0
        val cancellationObserved = CompletableDeferred<String>()
        val h = H(
            transformSettings = { base ->
                object : SettingsRepository by base {
                    override suspend fun writeScheduleReceipt(receipt: ScheduleReceipt?): SettingsWriteResult {
                        val result = base.writeScheduleReceipt(receipt)
                        if (receipt != null) {
                            receiptWriteEntered.complete(Unit)
                            releaseReceiptWrite.await()
                        }
                        return result
                    }

                    override suspend fun readScheduleReceipt(): ScheduleReceipt? {
                        if (stallNextReceiptRead) {
                            stallNextReceiptRead = false
                            finalReceiptReadEntered.complete(Unit)
                            awaitCancellation()
                        }
                        return base.readScheduleReceipt()
                    }
                }
            },
            transformScheduling = { base ->
                object : SchedulingGateway by base {
                    override fun installRecoveryRetry(incidentId: String, triggerAt: Instant): Boolean {
                        retryInstallCalls++
                        val installed = base.installRecoveryRetry(incidentId, triggerAt)
                        // The read side of the incident snapshot has finished. Park only the
                        // subsequent lock-wait status observation until the caller cancels it.
                        stallNextReceiptRead = true
                        return installed
                    }
                }
            },
        )
        h.seedSettings(enabled = false, recoveryRequired = false)
        h.policyGateway.owner = true
        h.policyGateway.features = 0
        h.policyGateway.packages = mutableSetOf()

        val holder = async { h.coordinator.arm() }
        runCurrent()
        receiptWriteEntered.await()
        val unitCallback = async {
            try {
                h.coordinator.recordPolicyResult("unrelated.callback", resultCode = null)
                false
            } catch (cancellation: CancellationException) {
                cancellationObserved.complete(cancellation.message.orEmpty())
                throw cancellation
            }
        }
        runCurrent()
        advanceTimeBy(ProtectionCoordinator.LOCK_WAIT_BUDGET_MILLIS + 1L)
        runCurrent()
        finalReceiptReadEntered.await()

        unitCallback.cancel(CancellationException("external lock-wait status cancellation"))
        unitCallback.join()
        assertTrue("the fallback must finish incident evidence and install its retry first", retryInstallCalls > 0)
        assertEquals("the unlocked callback fallback must propagate caller cancellation", "external lock-wait status cancellation", cancellationObserved.await())
        assertTrue(unitCallback.isCancelled)
        assertTrue("incident bookkeeping must finish before the final status read", h.settingsStore.incident?.unresolved == true)
        assertTrue(h.inhibitor.isInhibited)

        releaseReceiptWrite.complete(Unit)
        runCurrent()
        val holderStatus = holder.await()
        assertEquals(0, h.session.startRequests)
        assertFalse(h.policyGateway.featureSubmissions.contains(LockTaskMasks.PROTECTED))
        assertEquals(LockTaskRuntimeStates.NONE, h.runtime.state)
        assertNotEquals(ProtectionState.ARMED_POWER_MENU_RESTRICTED, holderStatus.state)
        assertEquals(holderStatus, h.coordinator.status.value)
    }

    @Test
    fun concurrent_release_only_fallbacks_must_not_leave_a_retry_for_a_different_incident() = runTest {
        val firstReadSnapshotCaptured = CompletableDeferred<Unit>()
        val releaseReadGate = CompletableDeferred<Unit>()
        val firstIncidentWriteCommitted = CompletableDeferred<Unit>()
        val allowFirstWriteToReturn = CompletableDeferred<Unit>()
        var incidentReads = 0
        val retrySubmissions = mutableListOf<String>()
        var currentlyInstalledRetryId: String? = null

        val h = H(
            transformSettings = { base ->
                object : SettingsRepository by base {
                    override suspend fun readRecoveryIncident(): RecoveryIncident? {
                        val snapshot = base.readRecoveryIncident()
                        if (++incidentReads == 1) firstReadSnapshotCaptured.complete(Unit)
                        // Capture the durable value before suspending. If a repaired implementation
                        // serializes the whole operation, B can reach this gate only after A ends;
                        // the already-open gate then lets it read A's committed incident.
                        releaseReadGate.await()
                        return snapshot
                    }

                    override suspend fun writeRecoveryIncident(incident: RecoveryIncident?): SettingsWriteResult {
                        val result = base.writeRecoveryIncident(incident)
                        if (incident?.incidentId == "deadline-incident-1") {
                            firstIncidentWriteCommitted.complete(Unit)
                            // Suspend after A commits but before its caller can submit the retry.
                            allowFirstWriteToReturn.await()
                        }
                        return result
                    }
                }
            },
            transformScheduling = { base ->
                object : SchedulingGateway by base {
                    override fun installRecoveryRetry(incidentId: String, triggerAt: Instant): Boolean {
                        retrySubmissions += incidentId
                        val installed = base.installRecoveryRetry(incidentId, triggerAt)
                        if (installed) currentlyInstalledRetryId = incidentId
                        return installed
                    }
                }
            },
        )
        h.seedLiveRestriction(enabled = false, recoveryRequired = true)
        assertNull("the race begins before any incident exists", h.settingsStore.incident)
        assertEquals("both release-only opens refer to a real pending managed session", LockTaskRuntimeStates.LOCKED, h.runtime.state)

        // These are the same durable-open operation called by the coordinator's unlocked timeout
        // fallbacks. Gates control actual reads, durable writes, and retry submissions.
        val firstOpen = async { h.recovery.openReleaseOnlyIncident("LOCK_WAIT_A") }
        runCurrent()
        firstReadSnapshotCaptured.await()
        val secondOpen = async { h.recovery.openReleaseOnlyIncident("LOCK_WAIT_B") }
        runCurrent()
        // Open the common pre-read gate regardless of whether B has started its read. On current
        // code both null snapshots can race; after a serialized fix B runs behind A and reads A.
        releaseReadGate.complete(Unit)
        runCurrent()
        firstIncidentWriteCommitted.await()

        // A is held after its real durable write while B either commits/schedules concurrently or
        // waits behind the same bookkeeping lock. Releasing unconditionally handles both cases.
        allowFirstWriteToReturn.complete(Unit)
        runCurrent()
        val firstResult = firstOpen.await()
        val secondResult = secondOpen.await()
        assertTrue("live restricted state must produce a scheduled incident", firstResult is ReleaseIncidentOpenResult.Scheduled)
        assertTrue("concurrent live fallback must reuse/schedule the same incident", secondResult is ReleaseIncidentOpenResult.Scheduled)
        val firstId = (firstResult as ReleaseIncidentOpenResult.Scheduled).incidentId
        val secondId = (secondResult as ReleaseIncidentOpenResult.Scheduled).incidentId
        assertTrue("both actual fallback operations must eventually read incident state", incidentReads >= 2)
        assertTrue("A's durable-write gate must have been reached", firstIncidentWriteCommitted.isCompleted)
        assertNotNull("at least one retry must be submitted", currentlyInstalledRetryId)

        val durableIncidentId = h.settingsStore.incident?.incidentId
        val installedRetryId = currentlyInstalledRetryId
        assertEquals(
            "concurrent incident writes and retry submissions must preserve one matching identity " +
                "(submissions=$retrySubmissions, callers=$firstId/$secondId)",
            durableIncidentId,
            installedRetryId,
        )
        assertTrue(
            "every installed or replaced retry must name the durable incident",
            retrySubmissions.isNotEmpty() && retrySubmissions.all { it == durableIncidentId },
        )
    }

    @Test
    fun delayed_fallback_token_cannot_reopen_verified_clean_recovery_or_clear_newer_brake() = runTest {
        for (engageNewerInhibition in listOf(false, true)) {
            val h = H()
            h.seedLiveRestriction(enabled = false, recoveryRequired = true)
            val delayedFallbackToken = h.inhibitor.inhibit("lock-wait fallback captured before recovery")
            assertTrue(h.inhibitor.isInhibited)
            assertTrue(h.settingsStore.baseline?.isTrustedOriginal == true)
            assertNull(h.settingsStore.incident)

            val restoreStatus = h.coordinator.disableAndRestore()

            assertEquals("real active-session Restore must complete", ProtectionState.DISARMED, restoreStatus.state)
            assertEquals(LockTaskRuntimeStates.NONE, h.runtime.state)
            assertEquals(16, h.policyGateway.features)
            assertEquals(setOf(EXTERNAL_PACKAGE), h.policyGateway.packages)
            assertEquals(BaselineLifecycleState.RESTORED, h.settingsStore.baseline?.lifecycle)
            assertFalse(h.settingsStore.current.enabled)
            assertFalse(h.settingsStore.current.recoveryRequired)
            assertNull(h.settingsStore.incident)
            assertFalse(h.inhibitor.isInhibited)

            val featureSubmissionsBefore = h.policyGateway.featureSubmissions.toList()
            val packageSubmissionsBefore = h.policyGateway.packageSubmissions.toList()
            val retriesBefore = h.schedulingGateway.recoveryRetryCount
            val newerToken = if (engageNewerInhibition) {
                h.inhibitor.inhibit("new independent failure after recovery")
            } else {
                null
            }

            val obsolete = h.recovery.openReleaseOnlyIncident(
                reason = "delayed lock-wait fallback from older recovery generation",
                inhibitionToken = delayedFallbackToken,
            )

            assertEquals(
                "a fresh read must classify the old callback as obsolete clean state",
                ReleaseIncidentOpenResult.ObsoleteClean,
                obsolete,
            )
            assertNull(h.settingsStore.incident)
            assertFalse(h.settingsStore.current.recoveryRequired)
            assertEquals(BaselineLifecycleState.RESTORED, h.settingsStore.baseline?.lifecycle)
            assertEquals(featureSubmissionsBefore, h.policyGateway.featureSubmissions)
            assertEquals(packageSubmissionsBefore, h.policyGateway.packageSubmissions)
            assertEquals(retriesBefore, h.schedulingGateway.recoveryRetryCount)

            if (newerToken == null) {
                assertFalse("obsolete completion must keep the cleared inhibitor clear", h.inhibitor.isInhibited)
            } else {
                assertTrue("the delayed older token must not clear a newer failure brake", h.inhibitor.isInhibited)
                assertEquals("new independent failure after recovery", h.inhibitor.reason)
                assertFalse("the old token no longer owns the active lease", h.inhibitor.clearIfCurrent(delayedFallbackToken))
                assertTrue("the newer failure still owns its lease", h.inhibitor.clearIfCurrent(newerToken))
                assertFalse(h.inhibitor.isInhibited)
            }

            val freshArm = h.coordinator.arm()
            assertEquals("fresh admission remains possible after stale callback fencing", ProtectionState.ARMED_POWER_MENU_RESTRICTED, freshArm.state)
            assertEquals(1, h.session.startRequests)
        }
    }

    @Test
    fun external_cancellation_propagates_after_bounded_release_and_never_enters_task() = runTest {
        val receiptWriteEntered = CompletableDeferred<Unit>()
        val cancellationObserved = CompletableDeferred<Boolean>()
        val h = H(
            transformSettings = { base ->
                object : SettingsRepository by base {
                    override suspend fun writeScheduleReceipt(receipt: ScheduleReceipt?): SettingsWriteResult {
                        val result = base.writeScheduleReceipt(receipt)
                        if (receipt != null) {
                            receiptWriteEntered.complete(Unit)
                            awaitCancellation()
                        }
                        return result
                    }
                }
            },
        )
        h.seedSettings(enabled = false, recoveryRequired = false)
        h.policyGateway.owner = true
        h.policyGateway.features = 0
        h.policyGateway.packages = mutableSetOf()

        val operation = async {
            try {
                h.coordinator.arm()
                false
            } catch (cancellation: CancellationException) {
                cancellationObserved.complete(true)
                throw cancellation
            }
        }
        val before = testScheduler.currentTime
        runCurrent()
        receiptWriteEntered.await()
        operation.cancel(CancellationException("external test cancellation"))
        operation.join()
        val elapsed = testScheduler.currentTime - before

        assertTrue("the public operation must propagate caller cancellation", operation.isCancelled)
        assertTrue("the test must observe the cancellation cause in the coordinator caller", cancellationObserved.await())
        assertTrue("cancellation cleanup must remain within the receiver-safe envelope: $elapsed ms", elapsed < 8_000L)
        assertTrue("cancellation fallback must attempt session stop", h.session.stopRequests > 0)
        assertTrue("cancellation fallback must submit the empty-allowlist exit", h.policyGateway.packageSubmissions.contains(emptySet()))
        assertTrue("cancellation fallback must persist a release-only incident", h.settingsStore.incident?.unresolved == true)
        assertEquals(LockTaskRuntimeStates.NONE, h.runtime.state)
        assertEquals(0, h.session.startRequests)
        assertFalse("cancelled arm must not submit restrictive policy", h.policyGateway.featureSubmissions.contains(LockTaskMasks.PROTECTED))
    }

    private fun faultingRepository(base: FakeSettingsRepository, fault: ReadFault): SettingsRepository =
        object : SettingsRepository by base {
            override suspend fun readSettings(): SettingsReadResult {
                fault.before(ReadHook.SETTINGS)
                return base.readSettings()
            }

            override suspend fun readBaseline(): PolicyBaseline? {
                fault.before(ReadHook.BASELINE)
                return base.readBaseline()
            }

            override suspend fun readTemporaryTestMarker(): TemporaryTestMarker? {
                fault.before(ReadHook.TEMP_MARKER)
                return base.readTemporaryTestMarker()
            }

            override suspend fun readRecoveryIncident(): RecoveryIncident? {
                fault.before(ReadHook.RECOVERY_INCIDENT)
                return base.readRecoveryIncident()
            }

            override suspend fun readScheduleReceipt(): ScheduleReceipt? {
                fault.before(ReadHook.SCHEDULE_RECEIPT)
                return base.readScheduleReceipt()
            }

            override suspend fun readBootGeneration(): Long {
                fault.before(ReadHook.BOOT_GENERATION)
                return base.readBootGeneration()
            }

            override suspend fun isCorruptionFlagged(): Boolean {
                fault.before(ReadHook.CORRUPTION_FLAG)
                return base.isCorruptionFlagged()
            }

            override suspend fun isOwnerEstablished(): Boolean {
                fault.before(ReadHook.OWNER_HISTORY)
                return base.isOwnerEstablished()
            }
        }

    private companion object {
        const val APP_ID = "com.example.shutdownprotection"
        const val EXTERNAL_PACKAGE = "external.kiosk"
        val INSIDE_WINDOW: Instant = Instant.parse("2026-06-15T03:00:00Z")
        val OUTSIDE_WINDOW: Instant = Instant.parse("2026-06-15T06:00:00Z")
    }
}
