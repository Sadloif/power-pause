package com.example.shutdownprotection.protection

import com.example.shutdownprotection.admin.DevicePolicyController
import com.example.shutdownprotection.admin.LockTaskMasks
import com.example.shutdownprotection.admin.LockTaskRuntimeStates
import com.example.shutdownprotection.data.PolicyBaseline
import com.example.shutdownprotection.data.BaselineLifecycleState
import com.example.shutdownprotection.data.BaselineProvenance
import com.example.shutdownprotection.data.ProtectionSettings
import com.example.shutdownprotection.data.TemporaryTestMarker
import com.example.shutdownprotection.fakes.FakeDevicePolicyGateway
import com.example.shutdownprotection.fakes.FakeDiagnosticsRepository
import com.example.shutdownprotection.fakes.FakeEnvironmentStateProvider
import com.example.shutdownprotection.fakes.FakeLockTaskSessionController
import com.example.shutdownprotection.fakes.FakeRuntimeLockTaskStateProvider
import com.example.shutdownprotection.fakes.FakeSchedulingGateway
import com.example.shutdownprotection.fakes.FakeSettingsRepository
import com.example.shutdownprotection.scheduling.ScheduleCalculator
import kotlinx.coroutines.async
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
import java.time.ZoneId

/**
 * Coordinator behaviour tests (brief section 26).
 *
 * Every case is driven through the single `reconcile` / `arm` entry point with injected time,
 * so no test waits and none depends on the machine's real clock or zone.
 */
class ProtectionCoordinatorTest {

    /** Instant inside a 02:00-05:00 UTC interval. */
    private val insideInterval = Instant.parse("2026-06-15T02:30:00Z")

    /** Instant outside a 02:00-05:00 UTC interval. */
    private val outsideInterval = Instant.parse("2026-06-15T12:00:00Z")

    private class Harness(initialInstant: Instant, initialZone: ZoneId) {
        var zone: ZoneId = initialZone
        val settings = FakeSettingsRepository()
        val diagnostics = FakeDiagnosticsRepository()
        val gateway = FakeDevicePolicyGateway()
        val runtime = FakeRuntimeLockTaskStateProvider()
        val session = FakeLockTaskSessionController(runtime)
        val scheduling = FakeSchedulingGateway()
        val environment = FakeEnvironmentStateProvider()
        val inhibitor = RestrictionInhibitor()

        val controller = DevicePolicyController(
            gateway = gateway,
            runtimeState = runtime,
            applicationId = APP_ID,
            clockMillis = { 0L },
        )

        val calculator = ScheduleCalculator(Clock.fixed(initialInstant, initialZone), zoneProvider = { zone })

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

        val coordinator = ProtectionCoordinator(
            applicationId = APP_ID,
            settingsRepository = settings,
            diagnostics = diagnostics,
            policyController = controller,
            scheduleCalculator = calculator,
            scheduleManager = scheduling,
            recoveryManager = recovery,
            lockTaskSession = session,
            environment = environment,
            inhibitor = inhibitor,
            clockMillis = { 0L },
            sleep = {},
        )

        fun armedSettings(
            start: Int = 120,
            end: Int = 300,
            enabled: Boolean = true,
        ) = ProtectionSettings(
            enabled = enabled,
            startMinuteOfDay = start,
            endMinuteOfDay = end,
            revision = 1L,
            allowedPackages = setOf(APP_ID),
        )

        /**
         * Seeds the original policy baseline that a real arm transaction would have captured
         * (repair R02). Recovery can only confirm restoration against a valid baseline, so any test
         * modelling an existing managed session must have one — otherwise the honest outcome is
         * `RESTORE_BASELINE_UNAVAILABLE` rather than a verified recovery.
         */
        fun seedBaseline(features: Int = 0, packages: Set<String> = emptySet()) {
            settings.baseline = PolicyBaseline(
                capturedAtEpochMillis = 0L,
                lockTaskPackages = packages,
                lockTaskFeatures = features,
                applicationId = APP_ID,
                provenance = BaselineProvenance.CAPTURED_BEFORE_PREPARATION,
                lifecycle = BaselineLifecycleState.PREPARED,
            )
        }
    }

    // ---- Ownership ---------------------------------------------------------------------

    @Test
    fun `missing ownership never arms and never applies a restrictive mask`() = runTest {
        val harness = Harness(insideInterval, ZoneId.of("UTC"))
        harness.gateway.owner = false
        harness.settings.seed(harness.armedSettings())

        val status = harness.coordinator.arm()

        assertEquals(ProtectionState.NOT_DEVICE_OWNER, status.state)
        assertTrue("no policy may be submitted without ownership", harness.gateway.featureSubmissions.isEmpty())
        assertTrue(harness.gateway.packageSubmissions.isEmpty())
    }

    @Test
    fun `missing ownership during reconcile never restricts`() = runTest {
        val harness = Harness(insideInterval, ZoneId.of("UTC"))
        harness.gateway.owner = false
        harness.runtime.state = LockTaskRuntimeStates.LOCKED
        harness.settings.seed(harness.armedSettings())

        val status = harness.coordinator.reconcile(ProtectionTrigger.START_ALARM)

        assertEquals(ProtectionState.NOT_DEVICE_OWNER, status.state)
        assertFalse(harness.gateway.featureSubmissions.contains(LockTaskMasks.PROTECTED))
    }

    // ---- Pinned is not locked ----------------------------------------------------------

    @Test
    fun `PINNED is not treated as LOCKED and never reports protection`() = runTest {
        val harness = Harness(insideInterval, ZoneId.of("UTC"))
        harness.session.resultingState = LockTaskRuntimeStates.PINNED
        harness.settings.seed(harness.armedSettings(enabled = false))

        val status = harness.coordinator.arm()

        assertFalse(LockTaskRuntimeStates.isRealLocked(LockTaskRuntimeStates.PINNED) == true)
        assertNotEquals(ProtectionState.ARMED_POWER_MENU_RESTRICTED, status.state)
        assertEquals(ProtectionState.CONFIGURATION_ERROR, status.state)
        assertFalse(harness.gateway.featureSubmissions.contains(LockTaskMasks.PROTECTED))
    }

    // ---- Exact capability --------------------------------------------------------------

    @Test
    fun `missing exact capability prevents a new restriction`() = runTest {
        val harness = Harness(insideInterval, ZoneId.of("UTC"))
        harness.scheduling.exactCapability = false
        harness.runtime.state = LockTaskRuntimeStates.LOCKED
        harness.seedBaseline()
        harness.settings.seed(harness.armedSettings())

        val status = harness.coordinator.reconcile(ProtectionTrigger.START_ALARM)

        assertEquals(ProtectionState.EXACT_SCHEDULING_UNAVAILABLE, status.state)
        assertFalse(
            "restriction must not be activated without exact alarms",
            harness.gateway.featureSubmissions.contains(LockTaskMasks.PROTECTED),
        )
    }

    @Test
    fun `missing exact capability prevents arming`() = runTest {
        val harness = Harness(insideInterval, ZoneId.of("UTC"))
        harness.scheduling.exactCapability = false
        harness.settings.seed(harness.armedSettings(enabled = false))

        val status = harness.coordinator.arm()

        assertEquals(ProtectionState.EXACT_SCHEDULING_UNAVAILABLE, status.state)
        assertEquals(0, harness.session.startRequests)
    }

    // ---- Scheduling failure ------------------------------------------------------------

    @Test
    fun `end alarm scheduling failure prevents restriction`() = runTest {
        val harness = Harness(insideInterval, ZoneId.of("UTC"))
        harness.runtime.state = LockTaskRuntimeStates.LOCKED
        harness.scheduling.planInstallSucceeds = false
        harness.seedBaseline()
        harness.settings.seed(harness.armedSettings())

        val status = harness.coordinator.reconcile(ProtectionTrigger.START_ALARM)

        assertEquals(ProtectionState.CONFIGURATION_ERROR, status.state)
        assertFalse(
            "a failed release plan must never be followed by restriction",
            harness.gateway.featureSubmissions.contains(LockTaskMasks.PROTECTED),
        )
    }

    // ---- Stale events ------------------------------------------------------------------

    @Test
    fun `an old start event uses current settings and never imposes an obsolete state`() = runTest {
        val harness = Harness(insideInterval, ZoneId.of("UTC"))
        harness.seedBaseline()
        harness.runtime.state = LockTaskRuntimeStates.LOCKED
        harness.gateway.features = LockTaskMasks.PROTECTED
        harness.gateway.packages = mutableSetOf(APP_ID)
        harness.settings.seed(harness.armedSettings(enabled = false))

        val status = harness.coordinator.reconcile(ProtectionTrigger.START_ALARM)

        assertEquals(ProtectionState.DISARMED, status.state)
        assertEquals("the stale start must release the real session", LockTaskRuntimeStates.NONE, harness.runtime.state)
        assertEquals("the trusted original is restored", 0, harness.gateway.features)
        assertFalse("the stale start cannot reimpose restriction", harness.gateway.featureSubmissions.contains(LockTaskMasks.PROTECTED))
    }

    @Test
    fun `a newer disabled revision wins over a queued end alarm`() = runTest {
        val harness = Harness(insideInterval, ZoneId.of("UTC"))
        harness.seedBaseline()
        harness.settings.seed(harness.armedSettings(enabled = true))
        // A newer revision arrives before the queued alarm is delivered.
        harness.settings.seed(harness.armedSettings(enabled = false).copy(revision = 2L))
        harness.runtime.state = LockTaskRuntimeStates.LOCKED
        harness.gateway.features = LockTaskMasks.PROTECTED
        harness.gateway.packages = mutableSetOf(APP_ID)

        val status = harness.coordinator.reconcile(ProtectionTrigger.END_ALARM)

        assertEquals(ProtectionState.DISARMED, status.state)
        assertEquals(LockTaskRuntimeStates.NONE, harness.runtime.state)
        assertEquals("the old restriction must return to its trusted original", 0, harness.gateway.features)
        assertFalse(harness.gateway.featureSubmissions.contains(LockTaskMasks.PROTECTED))
        assertTrue(harness.scheduling.cancelAllCount >= 1)
    }

    @Test
    fun `a stale fallback event does not blindly release when the current schedule still protects`() = runTest {
        val harness = Harness(insideInterval, ZoneId.of("UTC"))
        harness.runtime.state = LockTaskRuntimeStates.LOCKED
        harness.seedBaseline()
        harness.settings.seed(harness.armedSettings())

        val status = harness.coordinator.reconcile(ProtectionTrigger.RELEASE_FALLBACK)

        // The current configuration still protects this instant, so the fallback handler must
        // not release merely because its own event name says "release" (brief section 14).
        assertEquals(ProtectionState.ARMED_POWER_MENU_RESTRICTED, status.state)
        assertEquals(LockTaskMasks.PROTECTED, harness.gateway.features)
    }

    @Test
    fun `a fallback event after a newer disabled revision releases and never restricts`() = runTest {
        val harness = Harness(insideInterval, ZoneId.of("UTC"))
        harness.runtime.state = LockTaskRuntimeStates.LOCKED
        // A session that was armed at some point has a saved original policy. Seeding it makes this
        // the realistic "release a session we can restore" case; the no-baseline case is pinned
        // separately below, because review-2 section 4.3 requires unknown to stay unknown rather
        // than being read as a clean state.
        harness.seedBaseline()
        harness.gateway.features = LockTaskMasks.PROTECTED
        harness.settings.seed(harness.armedSettings(enabled = false).copy(revision = 5L))

        val status = harness.coordinator.reconcile(ProtectionTrigger.RELEASE_FALLBACK)

        assertEquals(ProtectionState.DISARMED, status.state)
        assertFalse(harness.gateway.featureSubmissions.contains(LockTaskMasks.PROTECTED))
        assertEquals("the existing restriction must actually be released", LockTaskRuntimeStates.NONE, harness.runtime.state)
    }

    @Test
    fun `an existing restriction with no saved baseline is never reported as a clean disarmed state`() = runTest {
        // REVIEW-2 section 4.3: missing evidence is not proof that no cleanup is owed. With a live
        // restricted session and no saved original policy, the honest answer is that restoration is
        // UNKNOWN — not DISARMED, which would claim the device is back to normal.
        val harness = Harness(insideInterval, ZoneId.of("UTC"))
        harness.runtime.state = LockTaskRuntimeStates.LOCKED
        harness.gateway.features = LockTaskMasks.PROTECTED
        harness.settings.seed(harness.armedSettings(enabled = false).copy(revision = 5L))

        val status = harness.coordinator.reconcile(ProtectionTrigger.RELEASE_FALLBACK)

        assertNotEquals(
            "an unconfirmed restoration must not be reported as a clean disarmed state",
            ProtectionState.DISARMED,
            status.state,
        )
        assertEquals("original-policy restoration must be reported as unresolved", "RESTORE_BASELINE_UNAVAILABLE", status.recoveryIncompleteStep)
        assertFalse("it must still never re-restrict", harness.gateway.featureSubmissions.contains(LockTaskMasks.PROTECTED))
    }

    @Test
    fun `an alarm delivered during recovery does not undo the cleanup`() = runTest {
        val harness = Harness(insideInterval, ZoneId.of("UTC"))
        harness.runtime.state = LockTaskRuntimeStates.LOCKED
        harness.seedBaseline()
        harness.gateway.features = LockTaskMasks.PROTECTED
        harness.settings.seed(harness.armedSettings().copy(recoveryRequired = true))

        // Both operations contend for the same serialization primitive; whichever order they
        // land in, cleanup must win and no restriction may survive.
        val alarm = async { harness.coordinator.reconcile(ProtectionTrigger.START_ALARM) }
        val recovery = async { harness.coordinator.disableAndRestore() }
        alarm.await()
        recovery.await()

        assertFalse("cleanup must leave the durable intent disabled", harness.settings.current.enabled)
        assertFalse(harness.settings.current.recoveryRequired)
        assertEquals(LockTaskRuntimeStates.NONE, harness.runtime.state)
        assertNotEquals(
            "an alarm must never re-impose a restrictive mask after cleanup",
            LockTaskMasks.PROTECTED,
            harness.gateway.features,
        )
    }

    // ---- Absent session after boot -----------------------------------------------------

    @Test
    fun `no protection is claimed when the runtime session is absent after boot`() = runTest {
        val harness = Harness(insideInterval, ZoneId.of("UTC"))
        harness.runtime.state = LockTaskRuntimeStates.NONE
        harness.seedBaseline()
        harness.settings.seed(harness.armedSettings())

        val status = harness.coordinator.reconcile(ProtectionTrigger.BOOT_UNLOCKED)

        assertEquals(ProtectionState.WAITING_FOR_SESSION, status.state)
        assertEquals(LockTaskMasks.ALLOWED, status.requestedFeatures)
        assertFalse(harness.gateway.featureSubmissions.contains(LockTaskMasks.PROTECTED))
        assertEquals(ManagedSessionUiState.NOT_ACTIVE, status.managedSessionUiState)
        assertEquals(PowerMenuUiState.NOT_VERIFIED, status.powerMenuUiState)
    }

    @Test
    fun `a locked keyguard reports waiting for unlock rather than protection`() = runTest {
        val harness = Harness(insideInterval, ZoneId.of("UTC"))
        harness.runtime.state = LockTaskRuntimeStates.NONE
        harness.environment.unlocked = false
        harness.environment.keyguardLocked = true
        harness.seedBaseline()
        harness.settings.seed(harness.armedSettings())

        val status = harness.coordinator.reconcile(ProtectionTrigger.BOOT_LOCKED)

        assertEquals(ProtectionState.WAITING_FOR_UNLOCK, status.state)
        assertEquals(ManagedSessionUiState.WAITING_FOR_UNLOCK, status.managedSessionUiState)
    }

    // ---- Interrupted arm ---------------------------------------------------------------

    @Test
    fun `an interrupted arm journal has a safe recovery route and never completes activation`() = runTest {
        val harness = Harness(insideInterval, ZoneId.of("UTC"))
        harness.seedBaseline()
        // Exactly the state the arm journal leaves behind if the process dies mid-transaction.
        harness.settings.seed(
            harness.armedSettings(enabled = false).copy(revision = 7L, recoveryRequired = true),
        )
        harness.runtime.state = LockTaskRuntimeStates.LOCKED

        val status = harness.coordinator.reconcile(ProtectionTrigger.APP_FOREGROUND)

        assertEquals(ProtectionState.DISARMED, status.state)
        assertFalse(harness.settings.current.recoveryRequired)
        assertFalse(
            "an abandoned activation must never be completed automatically",
            harness.gateway.featureSubmissions.contains(LockTaskMasks.PROTECTED),
        )
    }

    @Test
    fun `arming is refused while recovery is unresolved`() = runTest {
        val harness = Harness(insideInterval, ZoneId.of("UTC"))
        // A pending preparation journal requires a real captured original for verified cleanup.
        harness.seedBaseline()
        harness.settings.seed(harness.armedSettings(enabled = false).copy(recoveryRequired = true))

        val status = harness.coordinator.arm()

        assertEquals("the interrupted preparation is resolved before any new arm", ProtectionState.DISARMED, status.state)
        assertFalse(harness.settings.current.recoveryRequired)
        assertEquals(0, harness.session.startRequests)
        assertFalse(harness.gateway.featureSubmissions.contains(LockTaskMasks.PROTECTED))
    }

    // ---- Success path ------------------------------------------------------------------

    @Test
    fun `arm succeeds end to end and restricts only inside the interval`() = runTest {
        val harness = Harness(insideInterval, ZoneId.of("UTC"))
        harness.settings.seed(harness.armedSettings(enabled = false))

        val status = harness.coordinator.arm()

        assertEquals(ProtectionState.ARMED_POWER_MENU_RESTRICTED, status.state)
        assertEquals(LockTaskMasks.PROTECTED, harness.gateway.features)
        assertEquals(LockTaskRuntimeStates.LOCKED, harness.runtime.state)
        assertTrue(harness.settings.current.enabled)
        assertFalse(harness.settings.current.recoveryRequired)
        assertEquals(1, harness.session.startRequests)
        assertEquals(PowerMenuUiState.RESTRICTED, status.powerMenuUiState)
        assertTrue(status.toPowerMenuClaimInputs().canClaimRestricted())
    }

    @Test
    fun `arm outside the interval leaves the power menu allowed`() = runTest {
        val harness = Harness(outsideInterval, ZoneId.of("UTC"))
        harness.settings.seed(harness.armedSettings(enabled = false))

        val status = harness.coordinator.arm()

        assertEquals(ProtectionState.ARMED_POWER_MENU_ALLOWED, status.state)
        assertEquals(LockTaskMasks.ALLOWED, harness.gateway.features)
        assertEquals(PowerMenuUiState.ALLOWED, status.powerMenuUiState)
        assertFalse(status.toPowerMenuClaimInputs().canClaimRestricted())
    }

    @Test
    fun `a success path refreshes observations without resubmitting unchanged setters`() = runTest {
        val harness = Harness(insideInterval, ZoneId.of("UTC"))
        harness.runtime.state = LockTaskRuntimeStates.LOCKED
        harness.gateway.features = LockTaskMasks.PROTECTED
        harness.seedBaseline()
        harness.settings.seed(harness.armedSettings())

        val submissionsBefore = harness.gateway.featureSubmissions.size
        repeat(3) { harness.coordinator.refreshObservation() }

        assertEquals(
            "a success callback must not create an endless setter loop",
            submissionsBefore,
            harness.gateway.featureSubmissions.size,
        )
    }

    // ---- Edits and concurrency ---------------------------------------------------------

    @Test
    fun `a schedule edit during an armed session reinstalls the plan and reconciles immediately`() = runTest {
        val harness = Harness(insideInterval, ZoneId.of("UTC"))
        harness.runtime.state = LockTaskRuntimeStates.LOCKED
        harness.seedBaseline()
        harness.settings.seed(harness.armedSettings())

        // Establish the restricted state first, so the edit genuinely ends restriction.
        val before = harness.coordinator.reconcile(ProtectionTrigger.START_ALARM)
        assertEquals(ProtectionState.ARMED_POWER_MENU_RESTRICTED, before.state)

        val status = harness.coordinator.editSchedule(6 * 60, 7 * 60)

        assertEquals(2L, harness.settings.current.revision)
        assertEquals(6 * 60, harness.settings.current.startMinuteOfDay)
        // 02:30 is no longer inside 06:00-07:00, so the edit ends restriction immediately.
        assertEquals(ProtectionState.ARMED_POWER_MENU_ALLOWED, status.state)
        assertEquals(LockTaskMasks.ALLOWED, harness.gateway.features)
        assertTrue(status.detail.contains("ended restriction"))
        assertTrue(harness.scheduling.installPlanCount >= 1)
    }

    @Test
    fun `an invalid schedule edit is refused before the saved schedule changes`() = runTest {
        val harness = Harness(insideInterval, ZoneId.of("UTC"))
        harness.settings.seed(harness.armedSettings())

        val status = harness.coordinator.editSchedule(300, 300)

        assertEquals(ProtectionState.CONFIGURATION_ERROR, status.state)
        assertEquals("Start and end must be different.", status.detail)
        assertEquals(120, harness.settings.current.startMinuteOfDay)
        assertEquals(1L, harness.settings.current.revision)
    }

    @Test
    fun `allowlist edits disarm first and require an explicit resume`() = runTest {
        val harness = Harness(insideInterval, ZoneId.of("UTC"))
        harness.runtime.state = LockTaskRuntimeStates.LOCKED
        harness.seedBaseline()
        harness.settings.seed(harness.armedSettings())

        val status = harness.coordinator.editAllowedPackages(setOf(APP_ID, "com.example.launcher"))

        assertEquals(ProtectionState.DISARMED, status.state)
        assertFalse(harness.settings.current.enabled)
        assertTrue(status.userActionRequired!!.contains("resume"))
        assertTrue(harness.gateway.packageSubmissions.contains(emptySet()))
    }

    @Test
    fun `concurrent edit and alarm delivery serialize and reach a consistent state`() = runTest {
        val harness = Harness(insideInterval, ZoneId.of("UTC"))
        harness.runtime.state = LockTaskRuntimeStates.LOCKED
        harness.seedBaseline()
        harness.settings.seed(harness.armedSettings())

        val alarm = async { harness.coordinator.reconcile(ProtectionTrigger.START_ALARM) }
        val edit = async { harness.coordinator.editSchedule(6 * 60, 7 * 60) }
        alarm.await()
        edit.await()

        // Whatever order they landed in, the final durable state must match the final
        // observed state: the edit is the last write, so restriction must not be active.
        assertEquals(6 * 60, harness.settings.current.startMinuteOfDay)
        assertEquals(ProtectionState.ARMED_POWER_MENU_ALLOWED, harness.coordinator.status.value!!.state)
    }

    @Test
    fun `recovery is not starved by repeated status refreshes`() = runTest {
        val harness = Harness(insideInterval, ZoneId.of("UTC"))
        harness.seedBaseline()
        harness.settings.seed(
            harness.armedSettings(enabled = false).copy(revision = 9L, recoveryRequired = true),
        )
        harness.runtime.state = LockTaskRuntimeStates.LOCKED

        val refreshes = List(20) { async { harness.coordinator.refreshObservation() } }
        refreshes.forEach { it.await() }
        val status = harness.coordinator.reconcile(ProtectionTrigger.APP_FOREGROUND)

        assertEquals(ProtectionState.DISARMED, status.state)
        assertFalse(harness.settings.current.recoveryRequired)
    }

    // ---- Time-zone change --------------------------------------------------------------

    @Test
    fun `a time-zone change re-evaluates membership with the new zone`() = runTest {
        val harness = Harness(insideInterval, ZoneId.of("UTC"))
        harness.runtime.state = LockTaskRuntimeStates.LOCKED
        harness.seedBaseline()
        harness.settings.seed(harness.armedSettings())

        val inUtc = harness.coordinator.reconcile(ProtectionTrigger.START_ALARM)
        assertEquals(ProtectionState.ARMED_POWER_MENU_RESTRICTED, inUtc.state)

        // Same instant, now evaluated in Tokyo where it is 11:30 - outside 02:00-05:00.
        harness.zone = ZoneId.of("Asia/Tokyo")
        val inTokyo = harness.coordinator.reconcile(ProtectionTrigger.TIMEZONE_CHANGED)

        assertEquals(ProtectionState.ARMED_POWER_MENU_ALLOWED, inTokyo.state)
        assertEquals(LockTaskMasks.ALLOWED, harness.gateway.features)
    }

    // ---- Temporary debug test ----------------------------------------------------------

    @Test
    fun `restricting in the temporary test requires the release timer to be submitted first`() = runTest {
        val harness = Harness(insideInterval, ZoneId.of("UTC"))
        harness.settings.seed(harness.armedSettings(enabled = false))
        // ADAPTED FOR REPAIR R06: the exact release timer plus its inexact fallback are now
        // submitted BEFORE the managed session starts, not when restriction is requested. The
        // failure is therefore injected here. The safety intent is unchanged: with no submitted
        // release timer, no managed session and no restriction may begin.
        harness.scheduling.temporaryReleaseSucceeds = false

        val started = harness.coordinator.activatePocSession()

        assertEquals(ProtectionState.CONFIGURATION_ERROR, started.state)
        assertEquals("no managed session may start", LockTaskRuntimeStates.NONE, harness.runtime.state)
        assertNull("the temporary marker must be cleaned up", harness.settings.marker)
        assertFalse(harness.gateway.featureSubmissions.contains(LockTaskMasks.PROTECTED))
    }

    @Test
    fun `a live temporary test does not commit the daily enabled preference`() = runTest {
        val harness = Harness(insideInterval, ZoneId.of("UTC"))
        harness.settings.seed(harness.armedSettings(enabled = false))

        harness.coordinator.activatePocSession()

        // Repair R06: the temporary test is its own session mode and must leave the daily
        // preference disabled. It previously called the daily arming path, which committed
        // enabled=true while the POC screen claimed the preference was unchanged.
        assertFalse(
            "the temporary test must not activate the recurring daily schedule",
            harness.settings.current.enabled,
        )
        assertEquals(LockTaskRuntimeStates.LOCKED, harness.runtime.state)
        assertNotNull("the temporary marker is this mode's journal", harness.settings.marker)
        // And it must not install a daily start alarm as a side effect.
        assertEquals(0, harness.scheduling.installPlanCount)
    }

    @Test
    fun `the temporary test can restrict and then allow the menu again`() = runTest {
        val harness = Harness(insideInterval, ZoneId.of("UTC"))
        harness.settings.seed(harness.armedSettings(enabled = false))
        harness.coordinator.activatePocSession()

        val restricted = harness.coordinator.setPocOverride(PocOverride.FORCE_RESTRICTED)
        assertEquals(ProtectionState.ARMED_POWER_MENU_RESTRICTED, restricted.state)
        assertEquals(LockTaskMasks.PROTECTED, harness.gateway.features)
        assertTrue(harness.scheduling.temporaryReleaseCount >= 1)

        val allowed = harness.coordinator.setPocOverride(PocOverride.FORCE_ALLOWED)
        assertEquals(ProtectionState.ARMED_POWER_MENU_ALLOWED, allowed.state)
        assertEquals(LockTaskMasks.ALLOWED, harness.gateway.features)
    }

    @Test
    fun `an interrupted temporary test is recovered rather than resumed`() = runTest {
        val harness = Harness(insideInterval, ZoneId.of("UTC"))
        harness.runtime.state = LockTaskRuntimeStates.LOCKED
        harness.gateway.features = LockTaskMasks.PROTECTED
        harness.settings.seed(harness.armedSettings())
        harness.settings.marker = TemporaryTestMarker(
            startedAtEpochMillis = 0L,
            releaseAtEpochMillis = 1L,
            revision = 1L,
        )

        val status = harness.coordinator.reconcile(ProtectionTrigger.APP_FOREGROUND)

        // UPDATED FOR REVIEW-2 §4.4. This used to assert CONFIGURATION_ERROR, which was the state
        // that paired with the false wording "was recovered instead of resumed" — the result was
        // discarded, so a FAILED recovery looked identical to a successful one. No baseline is
        // seeded here, so restoration cannot be confirmed and the honest state is RECOVERY_FAILED
        // with the unresolved step reported.
        assertEquals(ProtectionState.RECOVERY_FAILED, status.state)
        assertEquals("RESTORE_BASELINE_UNAVAILABLE", status.recoveryIncompleteStep)
        assertFalse(
            "the detail must not claim the test was recovered when recovery did not verify " +
                "(detail=${status.detail})",
            status.detail!!.contains("was recovered"),
        )
        assertNotNull("failed baseline recovery preserves interrupted-session evidence", harness.settings.marker)
        assertTrue("the recovery journal stays unresolved until cleanup verifies", harness.settings.current.recoveryRequired)
        assertFalse(harness.settings.current.enabled)
    }

    // ---- Policy conflict handling ------------------------------------------------------

    @Test
    fun `a readback mismatch enters bounded recovery instead of resubmitting the setter`() = runTest {
        val harness = Harness(insideInterval, ZoneId.of("UTC"))
        harness.runtime.state = LockTaskRuntimeStates.LOCKED
        harness.seedBaseline()
        // The setter returns without throwing but never takes effect.
        harness.gateway.ignoreFeatureWrites = true
        harness.settings.seed(harness.armedSettings())

        val first = harness.coordinator.reconcile(ProtectionTrigger.START_ALARM)
        assertEquals(ProtectionState.POLICY_PENDING, first.state)

        val second = harness.coordinator.reconcile(ProtectionTrigger.POLICY_CHANGED)

        assertNotEquals(
            "the second pass must not remain pending forever",
            ProtectionState.POLICY_PENDING,
            second.state,
        )
        assertEquals(
            "an unchanged setter must never be re-submitted (no submit/callback loop)",
            1,
            harness.gateway.featureSubmissions.count { it == LockTaskMasks.PROTECTED },
        )
    }

    @Test
    fun `a verified submission is not written again on the next pass`() = runTest {
        val harness = Harness(insideInterval, ZoneId.of("UTC"))
        harness.runtime.state = LockTaskRuntimeStates.LOCKED
        harness.settings.seed(harness.armedSettings())

        harness.coordinator.reconcile(ProtectionTrigger.START_ALARM)
        val afterFirst = harness.gateway.featureSubmissions.count { it == LockTaskMasks.PROTECTED }
        repeat(3) { harness.coordinator.reconcile(ProtectionTrigger.POLICY_CHANGED) }

        assertEquals(
            "re-submitting an unchanged setter repeatedly is the feedback loop the brief forbids",
            afterFirst,
            harness.gateway.featureSubmissions.count { it == LockTaskMasks.PROTECTED },
        )
    }

    // ---- Schema handling (brief section 17) --------------------------------------------

    @Test
    fun `an older schema is upgraded without changing user intent`() = runTest {
        val harness = Harness(insideInterval, ZoneId.of("UTC"))
        harness.runtime.state = LockTaskRuntimeStates.LOCKED
        harness.seedBaseline()
        harness.settings.seed(harness.armedSettings().copy(schemaVersion = 0))

        val status = harness.coordinator.reconcile(ProtectionTrigger.APP_UPDATED)

        assertEquals(ProtectionSettings.SCHEMA_VERSION, harness.settings.current.schemaVersion)
        assertTrue("enabled intent must survive a schema upgrade", harness.settings.current.enabled)
        assertEquals("the revision must not move for a schema upgrade", 1L, harness.settings.current.revision)
        assertEquals(120, harness.settings.current.startMinuteOfDay)
        assertEquals(ProtectionState.ARMED_POWER_MENU_RESTRICTED, status.state)
    }

    @Test
    fun `a newer schema is treated as a recovery condition rather than silently accepted`() = runTest {
        val harness = Harness(insideInterval, ZoneId.of("UTC"))
        harness.runtime.state = LockTaskRuntimeStates.LOCKED
        harness.settings.seed(harness.armedSettings().copy(schemaVersion = 99))

        val status = harness.coordinator.reconcile(ProtectionTrigger.APP_FOREGROUND)

        assertEquals(ProtectionState.CONFIGURATION_ERROR, status.state)
        assertFalse(harness.settings.current.enabled)
        assertFalse(harness.gateway.featureSubmissions.contains(LockTaskMasks.PROTECTED))
    }

    @Test
    fun `an app update preserves the active state and reinstalls the plan`() = runTest {
        val harness = Harness(insideInterval, ZoneId.of("UTC"))
        harness.runtime.state = LockTaskRuntimeStates.LOCKED
        harness.seedBaseline()
        harness.settings.seed(harness.armedSettings())
        harness.coordinator.reconcile(ProtectionTrigger.START_ALARM)

        val revisionBefore = harness.settings.current.revision
        val installsBefore = harness.scheduling.installPlanCount

        // What SystemEventReceiver does on MY_PACKAGE_REPLACED: a new boot generation first, so
        // alarms installed by the previous build are treated as stale.
        harness.coordinator.onBootStarted()
        val status = harness.coordinator.reconcile(ProtectionTrigger.APP_UPDATED)

        assertTrue("an update must not disable protection silently", harness.settings.current.enabled)
        assertEquals("an update must not move the revision", revisionBefore, harness.settings.current.revision)
        assertEquals(ProtectionState.ARMED_POWER_MENU_RESTRICTED, status.state)
        assertTrue(
            "the scheduling plan must be reinstalled after an update",
            harness.scheduling.installPlanCount > installsBefore,
        )
    }

    // ---- Temporary debug test release --------------------------------------------------

    @Test
    fun `the temporary release timer releases through recovery and never re-restricts`() = runTest {
        val harness = Harness(insideInterval, ZoneId.of("UTC"))
        harness.settings.seed(harness.armedSettings(enabled = false))
        harness.coordinator.activatePocSession()
        harness.coordinator.setPocOverride(PocOverride.FORCE_RESTRICTED)
        assertEquals(LockTaskMasks.PROTECTED, harness.gateway.features)

        val released = harness.coordinator.releaseTemporaryTest()

        assertEquals(ProtectionState.DISARMED, released.state)
        assertFalse(harness.settings.current.enabled)
        assertNull("the temporary-test marker must be cleared", harness.settings.marker)
        assertEquals(LockTaskRuntimeStates.NONE, harness.runtime.state)
        assertNotEquals(
            "the temporary release must never re-impose the restrictive mask",
            LockTaskMasks.PROTECTED,
            harness.gateway.features,
        )
    }

    private companion object {
        const val APP_ID = "com.example.shutdownprotection"
    }
}
