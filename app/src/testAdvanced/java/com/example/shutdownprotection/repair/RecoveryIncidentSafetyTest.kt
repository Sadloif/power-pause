package com.example.shutdownprotection.repair

import com.example.shutdownprotection.admin.DevicePolicyController
import com.example.shutdownprotection.admin.DevicePolicyGateway
import com.example.shutdownprotection.admin.LockTaskMasks
import com.example.shutdownprotection.admin.LockTaskRuntimeStates
import com.example.shutdownprotection.data.BaselineLifecycleState
import com.example.shutdownprotection.data.BaselineProvenance
import com.example.shutdownprotection.data.DiagnosticsRepository
import com.example.shutdownprotection.data.PolicyBaseline
import com.example.shutdownprotection.data.ProtectionSettings
import com.example.shutdownprotection.data.RecoveryIncident
import com.example.shutdownprotection.data.SettingsRepository
import com.example.shutdownprotection.data.SettingsWriteResult
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
import com.example.shutdownprotection.protection.RecoveryManager
import com.example.shutdownprotection.protection.RestrictionInhibitor
import com.example.shutdownprotection.scheduling.ScheduleCalculator
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

/** Regression tests for the durable retry bound and restart admission after incomplete recovery. */
class RecoveryIncidentSafetyTest {

    @Test
    fun `failed durable attempt update must not schedule another automatic retry`() = runTest {
        val h = Harness()
        h.seedRestrictedSession()
        h.settingsStore.incident = RecoveryIncident(
            incidentId = "incident-persisted",
            openedAtEpochMillis = 10L,
            attempts = 0,
            unresolved = true,
        )
        // Exercise the cleanup-commit failure path after release and restoration both verified.
        // The attempt-count update itself is then independently rejected by the repository wrapper.
        h.settingsStore.failCommitCleanup = true
        h.settings.failAttemptCountWriteFor = 1

        val recovery = h.newRecovery(RestrictionInhibitor())
        val result = recovery.recover("RETRY_COUNTER_PERSISTENCE_TEST")

        assertEquals("PERSIST_FINAL_CLEANUP", result.failedStep)
        assertTrue("release must have been observed", result.sessionReleased)
        assertTrue("the explicitly trusted original must have been restored", result.baselineRestored)
        assertFalse(result.durableCleanupCommitted)
        assertEquals("the fail path must attempt to persist the increment", 1, h.settings.incidentWriteRequests)
        assertEquals(1, h.settings.failedAttemptCountWrites)
        assertEquals(1, h.settings.lastRequestedIncidentAttempts)
        assertEquals(
            "failed persistence must not advance durable attempts",
            0,
            h.settingsStore.incident?.attempts,
        )
        assertFalse("a retry cannot be scheduled from an unpersisted attempt count", result.retryScheduled)
        assertEquals("no automatic alarm should be installed", 0, h.scheduling.recoveryRetryCount)

        // A duplicate/already-delivered retry still sees durable attempt 0. It must not create a
        // new alarm from another unpersisted local increment either.
        val repeated = recovery.handleRecoveryRetry("incident-persisted")
        assertNotNull(repeated)
        assertEquals("PERSIST_FINAL_CLEANUP", repeated?.failedStep)
        assertEquals(0, h.settingsStore.incident?.attempts)
        assertFalse(repeated?.retryScheduled == true)
        assertEquals(2, h.settings.failedAttemptCountWrites)
        assertEquals(0, h.scheduling.recoveryRetryCount)
    }

    @Test
    fun `durable retry attempts stop at three successful increments`() = runTest {
        val h = Harness()
        h.seedRestrictedSession()
        h.settingsStore.incident = RecoveryIncident(
            incidentId = "incident-bounded",
            openedAtEpochMillis = 10L,
            attempts = 0,
            unresolved = true,
        )
        h.settingsStore.failCommitCleanup = true
        val recovery = h.newRecovery(RestrictionInhibitor())

        // Initial failed cleanup plus two delivered retries are the three automatic attempts.
        val first = recovery.recover("INITIAL_FAILURE")
        assertEquals("PERSIST_FINAL_CLEANUP", first.failedStep)
        assertEquals(1, h.settingsStore.incident?.attempts)
        assertTrue(first.retryScheduled)

        val second = recovery.handleRecoveryRetry("incident-bounded")
        assertNotNull(second)
        assertEquals("PERSIST_FINAL_CLEANUP", second?.failedStep)
        assertEquals(2, h.settingsStore.incident?.attempts)
        assertTrue(second?.retryScheduled == true)

        val third = recovery.handleRecoveryRetry("incident-bounded")
        assertNotNull(third)
        assertEquals("PERSIST_FINAL_CLEANUP", third?.failedStep)
        assertEquals(RecoveryIncident.MAX_ATTEMPTS, h.settingsStore.incident?.attempts)
        assertFalse("the third persisted attempt is terminal for automatic retries", third?.retryScheduled == true)

        val capped = recovery.handleRecoveryRetry("incident-bounded")
        assertEquals("a capped delivery is ignored", null, capped)
        assertEquals(RecoveryIncident.MAX_ATTEMPTS, h.settingsStore.incident?.attempts)
        assertEquals("only the first two failures may schedule follow-up alarms", 2, h.scheduling.recoveryRetryCount)
        assertEquals("all three attempt increments must be durable", 3, h.settings.incidentWriteRequests)
        assertEquals(0, h.settings.failedAttemptCountWrites)
    }

    @Test
    fun `restart reconciliation prioritizes unresolved incident over enabled schedule`() = runTest {
        val h = Harness()
        h.seedRestrictedSession()
        h.settingsStore.seed(h.enabledSettings(recoveryRequired = false))
        h.settingsStore.failCommitCleanup = true
        h.settings.failNextEditSettings = true
        h.session.stopLeavesStateUnchanged = true
        // A successful empty lock-task allowlist exits allowlisted tasks on Android. Keep the task
        // locked only by making that independent policy exit fail as well; this models a real
        // blocked-release state rather than an impossible fake runtime.
        h.gateway.ignorePackageWrites = true

        val firstProcessInhibitor = RestrictionInhibitor()
        val firstRecovery = h.newRecovery(firstProcessInhibitor)
        val firstResult = firstRecovery.recover("DURABLE_DISABLE_FAILURE_TEST")

        assertFalse(firstResult.verified)
        assertEquals("VERIFY_SESSION_RELEASED", firstResult.failedStep)
        assertFalse("the injected disable write must really have failed", firstResult.durableDisableWritten)
        assertEquals(1, h.settings.failedDisableWriteCount)
        assertTrue("an unresolved durable incident must be created", h.settingsStore.incident?.unresolved == true)
        assertEquals(1, h.settingsStore.incident?.attempts)
        assertFalse("the modern incident transaction must fail closed even after a separate disable write fails", h.settingsStore.current.enabled)
        assertTrue("the modern incident transaction must atomically set the recovery marker", h.settingsStore.current.recoveryRequired)
        assertEquals(LockTaskRuntimeStates.LOCKED, h.runtime.state)
        assertTrue("the first recovery must request the independent empty-allowlist exit", h.gateway.packageSubmissions.contains(emptySet()))
        assertEquals("the injected setter failure must leave the task allowlisted", setOf(APP_ID, ORIGINAL_LAUNCHER_ID), h.gateway.packages)

        // Recreate the persisted state produced by older releases, where writing the unresolved
        // incident did not atomically clear enabled/recoveryRequired. This is deliberately an
        // explicit historical row; the current writer correctly cannot produce this combination.
        val legacyIncident = RecoveryIncident(
            incidentId = "legacy-enabled-with-unresolved-incident",
            openedAtEpochMillis = NOW_MILLIS,
            attempts = 1,
            unresolved = true,
        )
        h.settingsStore.seed(h.enabledSettings(recoveryRequired = false))
        h.settingsStore.incident = legacyIncident
        assertTrue("the test must seed the historical enabled intent", h.settingsStore.current.enabled)
        assertFalse("the test must seed the historical missing recovery marker", h.settingsStore.current.recoveryRequired)
        assertEquals("the unresolved incident must coexist with the historical row", legacyIncident, h.settingsStore.incident)

        // Process restart: retain only durable/platform state, while losing the process-local brake,
        // coordinator mutex, submission cache, and manual override.
        val restartedInhibitor = RestrictionInhibitor()
        val restartedRecovery = h.newRecovery(restartedInhibitor)
        val restartedCoordinator = h.newCoordinator(restartedRecovery, restartedInhibitor)
        val incidentReadsBefore = h.settings.incidentReads
        val stopRequestsBefore = h.session.stopRequests
        val startRequestsBefore = h.session.startRequests
        val planInstallsBefore = h.scheduling.installPlanCount
        h.gateway.featureSubmissions.clear()
        h.gateway.packageSubmissions.clear()

        val restartedStatus = restartedCoordinator.reconcile(ProtectionTrigger.BOOT_UNLOCKED)

        assertTrue("the recreated process must inspect the durable incident", h.settings.incidentReads > incidentReadsBefore)
        assertTrue("incident-first handling must attempt release", h.session.stopRequests > stopRequestsBefore)
        assertEquals("reconciliation must not enter a new session", startRequestsBefore, h.session.startRequests)
        assertEquals("incident handling must bypass ordinary plan installation", planInstallsBefore, h.scheduling.installPlanCount)
        assertTrue(
            "release is attempted before normal schedule work",
            h.gateway.featureSubmissions.contains(LockTaskMasks.ALLOWED),
        )
        assertTrue("the recovery path must retry the independent allowlist exit", h.gateway.packageSubmissions.contains(emptySet()))
        assertEquals("the failed allowlist write still leaves the task allowlisted", setOf(APP_ID, ORIGINAL_LAUNCHER_ID), h.gateway.packages)
        assertFalse(
            "an unresolved cleanup incident must never reapply the restrictive schedule mask",
            h.gateway.featureSubmissions.contains(LockTaskMasks.PROTECTED),
        )
        assertTrue(
            "an unverified release must remain visibly unresolved",
            restartedStatus.state == ProtectionState.RECOVERY_FAILED ||
                restartedStatus.state == ProtectionState.RECOVERING,
        )
        assertEquals("the recovery retry updates its own durable incident", 2, h.settingsStore.incident?.attempts)
    }

    private class Harness {
        val settingsStore = FakeSettingsRepository()
        val settings = IncidentTrackingSettingsRepository(settingsStore)
        val diagnostics = FakeDiagnosticsRepository()
        val gateway = FakeDevicePolicyGateway()
        val runtime = FakeRuntimeLockTaskStateProvider()
        val session = FakeLockTaskSessionController(runtime)
        val scheduling = FakeSchedulingGateway()
        val environment = FakeEnvironmentStateProvider()

        fun seedRestrictedSession() {
            settingsStore.seed(enabledSettings(recoveryRequired = true))
            settingsStore.baseline = trustedOriginalBaseline()
            settingsStore.incident = null
            runtime.state = LockTaskRuntimeStates.LOCKED
            gateway.features = LockTaskMasks.PROTECTED
            gateway.packages = mutableSetOf(APP_ID, ORIGINAL_LAUNCHER_ID)
        }

        fun enabledSettings(recoveryRequired: Boolean) = ProtectionSettings(
            enabled = true,
            startMinuteOfDay = 120,
            endMinuteOfDay = 300,
            revision = 7L,
            allowedPackages = setOf(APP_ID),
            recoveryRequired = recoveryRequired,
        )

        private fun trustedOriginalBaseline() = PolicyBaseline(
            capturedAtEpochMillis = 1L,
            // This deliberately matches the app's usual restricted mask shape. The provenance and
            // lifecycle fields make it an explicitly captured original, rather than a legacy value
            // inferred from its contents.
            lockTaskPackages = setOf(APP_ID, ORIGINAL_LAUNCHER_ID),
            lockTaskFeatures = LockTaskMasks.PROTECTED,
            applicationId = APP_ID,
            provenance = BaselineProvenance.CAPTURED_BEFORE_PREPARATION,
            lifecycle = BaselineLifecycleState.PREPARED,
        )

        fun newRecovery(inhibitor: RestrictionInhibitor) = RecoveryManager(
            settingsRepository = settings,
            diagnostics = diagnostics,
            policyController = DevicePolicyController(gateway, runtime, APP_ID, clockMillis = { NOW_MILLIS }),
            scheduleManager = scheduling,
            lockTaskSession = session,
            inhibitor = inhibitor,
            clockMillis = { NOW_MILLIS },
            newIncidentId = { "incident-created-by-recovery" },
            sleep = {},
        )

        fun newCoordinator(
            recovery: RecoveryManager,
            inhibitor: RestrictionInhibitor,
        ) = ProtectionCoordinator(
            applicationId = APP_ID,
            settingsRepository = settings,
            diagnostics = diagnostics,
            policyController = DevicePolicyController(gateway, runtime, APP_ID, clockMillis = { NOW_MILLIS }),
            scheduleCalculator = ScheduleCalculator(
                Clock.fixed(INSIDE_PROTECTED_INTERVAL, ZoneOffset.UTC),
                { ZoneOffset.UTC },
            ),
            scheduleManager = scheduling,
            recoveryManager = recovery,
            lockTaskSession = session,
            environment = environment,
            inhibitor = inhibitor,
            clockMillis = { NOW_MILLIS },
            sleep = {},
        )
    }

    /** Fault-injection wrapper for the two independent required-write boundaries in this review. */
    private class IncidentTrackingSettingsRepository(
        private val delegate: FakeSettingsRepository,
    ) : SettingsRepository by delegate {
        var failAttemptCountWriteFor: Int? = null
        var failNextEditSettings: Boolean = false
        var incidentReads: Int = 0
            private set
        var incidentWriteRequests: Int = 0
            private set
        var failedAttemptCountWrites: Int = 0
            private set
        var lastRequestedIncidentAttempts: Int? = null
            private set
        var failedDisableWriteCount: Int = 0
            private set

        override suspend fun readRecoveryIncident(): RecoveryIncident? {
            incidentReads++
            return delegate.readRecoveryIncident()
        }

        override suspend fun writeRecoveryIncident(incident: RecoveryIncident?): SettingsWriteResult {
            if (incident != null && incident.unresolved) {
                incidentWriteRequests++
                lastRequestedIncidentAttempts = incident.attempts
                if (incident.attempts == failAttemptCountWriteFor) {
                    failedAttemptCountWrites++
                    return SettingsWriteResult.Failure(IllegalStateException("simulated incident attempt write failure"))
                }
            }
            return delegate.writeRecoveryIncident(incident)
        }

        override suspend fun editSettings(
            transform: (ProtectionSettings) -> ProtectionSettings,
        ): SettingsWriteResult {
            if (failNextEditSettings) {
                failNextEditSettings = false
                failedDisableWriteCount++
                return SettingsWriteResult.Failure(IllegalStateException("simulated durable disable failure"))
            }
            return delegate.editSettings(transform)
        }
    }

    private companion object {
        const val APP_ID = "com.example.shutdownprotection"
        const val ORIGINAL_LAUNCHER_ID = "com.example.launcher"
        const val NOW_MILLIS = 1_782_102_000_000L
        val INSIDE_PROTECTED_INTERVAL: Instant = Instant.parse("2026-06-15T03:00:00Z")
    }
}
