package com.example.shutdownprotection.repair

import android.app.admin.DevicePolicyIdentifiers
import com.example.shutdownprotection.admin.DevicePolicyController
import com.example.shutdownprotection.admin.DevicePolicyGateway
import com.example.shutdownprotection.admin.LockTaskMasks
import com.example.shutdownprotection.admin.LockTaskRuntimeStates
import com.example.shutdownprotection.data.BaselineLifecycleState
import com.example.shutdownprotection.data.BaselineProvenance
import com.example.shutdownprotection.data.RecoveryIncident
import com.example.shutdownprotection.scheduling.AlarmEventKind
import com.example.shutdownprotection.scheduling.CancelledAlarm
import com.example.shutdownprotection.data.DiagnosticsRepository
import com.example.shutdownprotection.data.PolicyBaseline
import com.example.shutdownprotection.data.ProtectionSettings
import com.example.shutdownprotection.data.ScheduleReceipt
import com.example.shutdownprotection.data.SessionPreparationResult
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
import com.example.shutdownprotection.protection.PocOverride
import com.example.shutdownprotection.protection.RecoveryManager
import com.example.shutdownprotection.protection.RestrictionInhibitor
import com.example.shutdownprotection.scheduling.ScheduleCalculator
import com.example.shutdownprotection.scheduling.SchedulingGateway
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.advanceUntilIdle
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
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

/**
 * High-value lifecycle regressions for recovery, baseline turnover and temporary-session fencing.
 * Each setup asserts the state that makes its branch reachable and then checks real fake-gateway
 * submissions, runtime state, or durable repository values; a status label alone is never proof.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CoreLifecycleSafetyTest {

    private class H(
        val now: Instant = INSIDE_WINDOW,
        val operationTimeoutMillis: Long = 2_300L,
        val armTimeoutMillis: Long = 3_200L,
        transformSettings: (FakeSettingsRepository) -> SettingsRepository = { it },
        transformDiagnostics: (FakeDiagnosticsRepository) -> DiagnosticsRepository = { it },
        transformGateway: (FakeDevicePolicyGateway) -> DevicePolicyGateway = { it },
        transformScheduling: (FakeSchedulingGateway) -> SchedulingGateway = { it },
        recoverySleep: suspend (Long) -> Unit = {},
    ) {
        var nowMillis: Long = 1_234L
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
            newIncidentId = { "lifecycle-incident-${++incidentSequence}" },
            sleep = recoverySleep,
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

        fun seedSettings(
            enabled: Boolean = false,
            recoveryRequired: Boolean = false,
            revision: Long = 1L,
            allowedPackages: Set<String> = setOf(APP_ID),
        ) {
            settingsStore.seed(
                ProtectionSettings(
                    enabled = enabled,
                    revision = revision,
                    allowedPackages = allowedPackages,
                    recoveryRequired = recoveryRequired,
                ),
            )
        }

        fun seedBaseline(
            features: Int,
            packages: Set<String>,
            lifecycle: BaselineLifecycleState = BaselineLifecycleState.PREPARED,
            provenance: BaselineProvenance = BaselineProvenance.CAPTURED_BEFORE_PREPARATION,
        ) {
            settingsStore.baseline = PolicyBaseline(
                capturedAtEpochMillis = 1L,
                lockTaskPackages = packages,
                lockTaskFeatures = features,
                applicationId = APP_ID,
                provenance = provenance,
                lifecycle = lifecycle,
            )
        }

        fun setRestrictedPolicy(runtimeState: Int = LockTaskRuntimeStates.LOCKED) {
            runtime.state = runtimeState
            policyGateway.features = LockTaskMasks.PROTECTED
            policyGateway.packages = mutableSetOf(APP_ID)
        }
    }

    @Test
    fun clean_owner_restore_preserves_each_nonrestrictive_mask_without_policy_mutations() = runTest {
        for (features in listOf(0, 16, LockTaskMasks.ALLOWED)) {
            val h = H()
            h.seedSettings(allowedPackages = emptySet())
            h.policyGateway.owner = true
            h.policyGateway.features = features
            h.policyGateway.packages = mutableSetOf()
            assertEquals(LockTaskRuntimeStates.NONE, h.runtime.state)
            assertNull(h.settingsStore.baseline)

            val status = h.coordinator.disableAndRestore()

            assertEquals("clean mask $features", ProtectionState.DISARMED, status.state)
            assertEquals("clean mask $features must be preserved", features, h.policyGateway.features)
            assertTrue("clean mask $features must keep its original package list", h.policyGateway.packages.isEmpty())
            assertTrue("clean mask $features must not submit a replacement mask", h.policyGateway.featureSubmissions.isEmpty())
            assertTrue("clean mask $features must not submit a package list", h.policyGateway.packageSubmissions.isEmpty())
            assertEquals("clean mask $features must not ask a session to stop", 0, h.session.stopRequests)
            assertNull(h.settingsStore.incident)
            assertFalse(h.inhibitor.isInhibited)
        }
    }

    @Test
    fun clean_no_history_preserves_external_restrictive_policy_but_not_ambiguous_app_or_runtime_state() = runTest {
        val external = H()
        external.seedSettings(allowedPackages = emptySet())
        external.settingsStore.ownerEstablished = true
        external.policyGateway.owner = true
        external.policyGateway.features = 47
        external.policyGateway.packages = mutableSetOf("external.kiosk")
        assertEquals(LockTaskRuntimeStates.NONE, external.runtime.state)
        assertNull(external.settingsStore.baseline)
        assertNull(external.settingsStore.marker)
        assertNull(external.settingsStore.incident)
        assertNull(external.settingsStore.receipt)
        assertFalse(external.settingsStore.current.enabled)
        assertFalse(external.settingsStore.current.recoveryRequired)

        val externalStatus = external.coordinator.disableAndRestore()

        assertEquals("no app history must preserve an unrelated Device Owner policy", ProtectionState.DISARMED, externalStatus.state)
        assertEquals(47, external.policyGateway.features)
        assertEquals(setOf("external.kiosk"), external.policyGateway.packages)
        assertTrue("preservation must not submit a replacement feature mask", external.policyGateway.featureSubmissions.isEmpty())
        assertTrue("preservation must not clear another owner's allowlist", external.policyGateway.packageSubmissions.isEmpty())
        assertEquals("preservation must not stop a session when runtime is NONE", 0, external.session.stopRequests)
        assertNull(external.settingsStore.incident)
        assertFalse(external.settingsStore.current.recoveryRequired)
        assertTrue(
            "a clean no-history result must not claim an original baseline was restored",
            externalStatus.detail.contains("without claiming an original baseline was restored"),
        )
        assertFalse(
            "a confirmed owner must not be reported as lost when external policy is preserved",
            externalStatus.detail.contains("Device Owner authority is lost or unavailable"),
        )
        assertFalse(external.inhibitor.isInhibited)

        // An app package in the restrictive allowlist could be residue from an interrupted app
        // session. Without a trusted original, the recovery path must remain conservative and
        // visibly unverified rather than treating it as unrelated external policy.
        for (appPackages in listOf(setOf(APP_ID), setOf(APP_ID, "external.kiosk"))) {
            val appPolicy = H()
            appPolicy.seedSettings(allowedPackages = emptySet())
            appPolicy.settingsStore.ownerEstablished = true
            appPolicy.policyGateway.owner = true
            appPolicy.policyGateway.features = 47
            appPolicy.policyGateway.packages = appPackages.toMutableSet()
            assertEquals(LockTaskRuntimeStates.NONE, appPolicy.runtime.state)
            assertNull(appPolicy.settingsStore.baseline)
            assertNull(appPolicy.settingsStore.marker)
            assertNull(appPolicy.settingsStore.receipt)
            assertNull(appPolicy.settingsStore.incident)
            assertFalse(appPolicy.settingsStore.current.enabled)
            assertFalse(appPolicy.settingsStore.current.recoveryRequired)

            val appPolicyStatus = appPolicy.coordinator.disableAndRestore()

            assertEquals("app-containing allowlist $appPackages remains ambiguous", ProtectionState.RECOVERY_FAILED, appPolicyStatus.state)
            assertEquals(LockTaskMasks.ALLOWED, appPolicy.policyGateway.features)
            assertTrue(appPolicy.policyGateway.packages.isEmpty())
            assertTrue(appPolicy.policyGateway.featureSubmissions.contains(LockTaskMasks.ALLOWED))
            assertTrue(appPolicy.policyGateway.packageSubmissions.contains(emptySet()))
            assertTrue(appPolicy.session.stopRequests > 0)
            assertNull(appPolicy.settingsStore.baseline)
            assertTrue(appPolicy.settingsStore.current.recoveryRequired)
            assertTrue(appPolicy.settingsStore.incident?.unresolved == true)
        }

        // A failed runtime read is also ambiguous even when the allowlist names no app package.
        // Keep the fake stop from inventing a NONE readback so this test proves the unknown branch.
        val unknownRuntime = H()
        unknownRuntime.seedSettings(allowedPackages = emptySet())
        unknownRuntime.settingsStore.ownerEstablished = true
        unknownRuntime.policyGateway.owner = true
        unknownRuntime.policyGateway.features = 47
        unknownRuntime.policyGateway.packages = mutableSetOf("external.kiosk")
        unknownRuntime.runtime.state = null
        unknownRuntime.session.stopLeavesStateUnchanged = true

        val unknownRuntimeStatus = unknownRuntime.coordinator.disableAndRestore()

        assertEquals(ProtectionState.RECOVERY_FAILED, unknownRuntimeStatus.state)
        assertNull(unknownRuntime.runtime.state)
        assertEquals(LockTaskMasks.ALLOWED, unknownRuntime.policyGateway.features)
        assertTrue(unknownRuntime.policyGateway.packages.isEmpty())
        assertTrue(unknownRuntime.policyGateway.featureSubmissions.contains(LockTaskMasks.ALLOWED))
        assertTrue(unknownRuntime.policyGateway.packageSubmissions.contains(emptySet()))
        assertTrue(unknownRuntime.session.stopRequests > 0)
        assertNull(unknownRuntime.settingsStore.baseline)
        assertTrue(unknownRuntime.settingsStore.current.recoveryRequired)
        assertTrue(unknownRuntime.settingsStore.incident?.unresolved == true)

        // A real but stale schedule receipt is history, even when settings and runtime look clean.
        // The external-policy preservation exception must not erase a possibly app-created policy.
        val staleReceipt = H()
        staleReceipt.seedSettings(allowedPackages = emptySet())
        staleReceipt.settingsStore.ownerEstablished = true
        staleReceipt.settingsStore.receipt = ScheduleReceipt(
            revision = 0L,
            bootGeneration = 0L,
            submittedAtEpochMillis = 1L,
            nextStartEpochMillis = 2L,
            nextEndEpochMillis = 3L,
            fallbackEpochMillis = 4L,
        )
        staleReceipt.policyGateway.owner = true
        staleReceipt.policyGateway.features = 47
        staleReceipt.policyGateway.packages = mutableSetOf("external.kiosk")
        assertEquals(LockTaskRuntimeStates.NONE, staleReceipt.runtime.state)
        assertNull(staleReceipt.settingsStore.baseline)
        assertNull(staleReceipt.settingsStore.marker)
        assertNotNull(staleReceipt.settingsStore.receipt)
        assertNull(staleReceipt.settingsStore.incident)
        assertFalse(staleReceipt.settingsStore.current.enabled)
        assertFalse(staleReceipt.settingsStore.current.recoveryRequired)

        val staleReceiptStatus = staleReceipt.coordinator.disableAndRestore()

        assertEquals(ProtectionState.RECOVERY_FAILED, staleReceiptStatus.state)
        assertEquals(LockTaskMasks.ALLOWED, staleReceipt.policyGateway.features)
        assertTrue(staleReceipt.policyGateway.packages.isEmpty())
        assertTrue(staleReceipt.policyGateway.featureSubmissions.contains(LockTaskMasks.ALLOWED))
        assertTrue(staleReceipt.policyGateway.packageSubmissions.contains(emptySet()))
        assertTrue(staleReceipt.session.stopRequests > 0)
        assertNull(staleReceipt.settingsStore.baseline)
        assertTrue(staleReceipt.settingsStore.current.recoveryRequired)
        assertTrue(staleReceipt.settingsStore.incident?.unresolved == true)
    }

    @Test
    fun external_restrictive_no_history_policy_is_preserved_across_edit_reconcile_and_entry_routes() = runTest {
        fun cleanExternalPolicy(): H = H().also { h ->
            h.seedSettings(allowedPackages = emptySet())
            h.settingsStore.ownerEstablished = true
            h.policyGateway.owner = true
            h.policyGateway.features = 47
            h.policyGateway.packages = mutableSetOf("external.kiosk")
            assertEquals(LockTaskRuntimeStates.NONE, h.runtime.state)
            assertNull(h.settingsStore.baseline)
            assertNull(h.settingsStore.marker)
            assertNull(h.settingsStore.incident)
            assertNull(h.settingsStore.receipt)
        }

        fun assertExternalPolicyPreserved(h: H, status: com.example.shutdownprotection.protection.ProtectionStatus, route: String) {
            assertEquals("$route must not change external policy", 47, h.policyGateway.features)
            assertEquals("$route must preserve the external allowlist", setOf("external.kiosk"), h.policyGateway.packages)
            assertTrue("$route must not submit a feature mask", h.policyGateway.featureSubmissions.isEmpty())
            assertTrue("$route must not submit an allowlist", h.policyGateway.packageSubmissions.isEmpty())
            assertEquals("$route must not stop a NONE runtime", 0, h.session.stopRequests)
            assertEquals("$route must not start Lock Task", 0, h.session.startRequests)
            assertNull("$route must not fabricate a baseline", h.settingsStore.baseline)
            assertNull("$route must not leave a temporary marker", h.settingsStore.marker)
            assertNull("$route must not create a recovery incident", h.settingsStore.incident)
            assertFalse("$route must keep protection disabled", h.settingsStore.current.enabled)
            assertFalse("$route must leave recovery clear", h.settingsStore.current.recoveryRequired)
            assertFalse("$route must not leave an in-process inhibition", h.inhibitor.isInhibited)
            assertFalse(
                "$route must not claim that app-imposed policy was released",
                status.detail.contains("app-imposed restriction released"),
            )
            assertFalse(
                "$route must not claim that restriction ended",
                status.detail.contains("The edit ended restriction immediately"),
            )
            assertFalse("$route must not report a newly armed session", status.state in setOf(
                ProtectionState.ARMED_POWER_MENU_ALLOWED,
                ProtectionState.ARMED_POWER_MENU_RESTRICTED,
            ))
        }

        val edit = cleanExternalPolicy()
        val editStatus = edit.coordinator.editSchedule(startMinuteOfDay = 360, endMinuteOfDay = 480)
        assertExternalPolicyPreserved(edit, editStatus, "schedule edit")
        assertFalse(
            "schedule edit must not claim it ended an unrelated restrictive policy",
            editStatus.detail.contains("The edit ended restriction immediately"),
        )
        assertEquals(360, edit.settingsStore.current.startMinuteOfDay)
        assertEquals(480, edit.settingsStore.current.endMinuteOfDay)
        assertEquals(2L, edit.settingsStore.current.revision)

        val reconcile = cleanExternalPolicy()
        val reconcileStatus = reconcile.coordinator.reconcile(ProtectionTrigger.APP_FOREGROUND)
        assertExternalPolicyPreserved(reconcile, reconcileStatus, "disabled reconcile")

        val dailyEntry = cleanExternalPolicy()
        val armStatus = dailyEntry.coordinator.arm()
        assertExternalPolicyPreserved(dailyEntry, armStatus, "daily entry refusal")
        assertEquals("daily entry must be refused before baseline capture", 0, dailyEntry.session.startRequests)

        val temporaryEntry = cleanExternalPolicy()
        val temporaryStatus = temporaryEntry.coordinator.activatePocSession()
        assertExternalPolicyPreserved(temporaryEntry, temporaryStatus, "temporary entry refusal")
        assertEquals("temporary entry must be refused before baseline capture", 0, temporaryEntry.session.startRequests)
    }

    @Test
    fun enabled_start_reconcile_with_locked_session_and_no_usable_baseline_refuses_new_restriction() = runTest {
        for (baselineKind in listOf("missing", "legacy-unverified", "retired")) {
            val h = H()
            h.seedSettings(enabled = true, recoveryRequired = false, allowedPackages = setOf(APP_ID))
            when (baselineKind) {
                "legacy-unverified" -> h.seedBaseline(
                    features = LockTaskMasks.PROTECTED,
                    packages = setOf(APP_ID),
                    lifecycle = BaselineLifecycleState.LEGACY_UNKNOWN,
                    provenance = BaselineProvenance.LEGACY_UNVERIFIED,
                )
                "retired" -> h.seedBaseline(
                    features = 16,
                    packages = setOf("external.kiosk"),
                    lifecycle = BaselineLifecycleState.RESTORED,
                )
                else -> assertNull("missing case must begin without a baseline", h.settingsStore.baseline)
            }
            h.settingsStore.marker = null
            h.settingsStore.incident = null
            h.settingsStore.receipt = null
            h.settingsStore.ownerEstablished = true
            h.policyGateway.owner = true
            h.setRestrictedPolicy()
            assertEquals("$baselineKind must begin with a real active session", LockTaskRuntimeStates.LOCKED, h.runtime.state)
            assertEquals(LockTaskMasks.PROTECTED, h.policyGateway.features)
            assertEquals(setOf(APP_ID), h.policyGateway.packages)
            assertTrue(h.settingsStore.current.enabled)
            assertFalse(h.settingsStore.current.recoveryRequired)

            val plansBefore = h.schedulingGateway.installPlanCount
            val status = h.coordinator.reconcile(ProtectionTrigger.START_ALARM)

            assertTrue(
                "$baselineKind must remain visibly unverified without a usable original",
                status.state in setOf(ProtectionState.RECOVERY_FAILED, ProtectionState.RECOVERING),
            )
            assertTrue(
                "$baselineKind must identify the missing, untrusted, or retired original",
                status.recoveryIncompleteStep in setOf("RESTORE_BASELINE_UNAVAILABLE", "RESTORE_BASELINE_INVALID"),
            )
            assertEquals("$baselineKind must not start or re-enter Lock Task", 0, h.session.startRequests)
            assertTrue("$baselineKind must attempt the permissive release mask", h.policyGateway.featureSubmissions.contains(LockTaskMasks.ALLOWED))
            assertTrue("$baselineKind must independently request session stop", h.session.stopRequests > 0)
            assertTrue("$baselineKind must independently submit the empty package exit", h.policyGateway.packageSubmissions.contains(emptySet()))
            assertEquals("$baselineKind must not install a fresh daily plan", plansBefore, h.schedulingGateway.installPlanCount)
            assertFalse("$baselineKind must not submit a restrictive schedule mask", h.policyGateway.featureSubmissions.contains(LockTaskMasks.PROTECTED))
            assertEquals(LockTaskRuntimeStates.NONE, h.runtime.state)
            assertFalse("$baselineKind must persist disabled intent", h.settingsStore.current.enabled)
            assertTrue("$baselineKind must persist unresolved recovery", h.settingsStore.current.recoveryRequired)
            assertTrue("$baselineKind must persist a durable unresolved incident", h.settingsStore.incident?.unresolved == true)
            assertTrue("$baselineKind must retain the process restriction brake", h.inhibitor.isInhibited)
            when (baselineKind) {
                "missing" -> assertNull("recovery must not fabricate an original", h.settingsStore.baseline)
                "legacy-unverified" -> assertEquals(BaselineProvenance.LEGACY_UNVERIFIED, h.settingsStore.baseline?.provenance)
                "retired" -> assertEquals(BaselineLifecycleState.RESTORED, h.settingsStore.baseline?.lifecycle)
            }
        }
    }

    @Test
    fun never_owned_and_deprovisioned_devices_preserve_policy_when_dpm_reads_are_denied() = runTest {
        for (ownerWasPreviouslyEstablished in listOf(false, true)) {
            val h = H()
            h.seedSettings(allowedPackages = emptySet())
            h.policyGateway.owner = false
            h.policyGateway.failFeatureReads = true
            h.policyGateway.failPackageReads = true
            h.policyGateway.features = LockTaskMasks.PROTECTED
            h.policyGateway.packages = mutableSetOf("external.kiosk")
            h.settingsStore.ownerEstablished = ownerWasPreviouslyEstablished
            assertEquals(LockTaskRuntimeStates.NONE, h.runtime.state)

            val status = h.coordinator.disableAndRestore()

            assertEquals("owner history=$ownerWasPreviouslyEstablished", ProtectionState.DISARMED, status.state)
            assertEquals(LockTaskMasks.PROTECTED, h.policyGateway.features)
            assertEquals(setOf("external.kiosk"), h.policyGateway.packages)
            assertTrue(h.policyGateway.featureSubmissions.isEmpty())
            assertTrue(h.policyGateway.packageSubmissions.isEmpty())
            assertEquals(0, h.session.stopRequests)
            assertNull(h.settingsStore.incident)
            assertFalse(h.inhibitor.isInhibited)
            assertEquals(ownerWasPreviouslyEstablished, h.settingsStore.ownerEstablished)
        }
    }

    @Test
    fun unknown_owner_with_restrictive_policy_attempts_release_but_never_claims_restoration() = runTest {
        var featureReadAttempts = 0
        var packageReadAttempts = 0
        val h = H(
            transformGateway = { base ->
                object : DevicePolicyGateway by base {
                    override fun readLockTaskFeatures(): Int? {
                        featureReadAttempts++
                        return base.readLockTaskFeatures()
                    }

                    override fun readLockTaskPackages(): Set<String>? {
                        packageReadAttempts++
                        return base.readLockTaskPackages()
                    }
                }
            },
        )
        h.seedSettings(allowedPackages = emptySet())
        h.policyGateway.owner = null
        h.policyGateway.features = LockTaskMasks.PROTECTED
        h.policyGateway.packages = mutableSetOf(APP_ID)
        h.runtime.state = LockTaskRuntimeStates.NONE
        val featuresBefore = h.policyGateway.features
        val packagesBefore = h.policyGateway.packages.toSet()

        val status = h.coordinator.disableAndRestore()

        assertEquals(ProtectionState.RECOVERY_FAILED, status.state)
        assertEquals("unknown authority cannot justify a baseline claim", "VERIFY_PERMISSIVE", status.recoveryIncompleteStep)
        assertEquals(1, h.session.stopRequests)
        assertTrue("feature observations/readbacks occurred during recovery", featureReadAttempts >= 3)
        assertTrue("package observations/readbacks occurred during recovery", packageReadAttempts >= 3)
        assertTrue("the DPC must refuse writes while owner authority is unknown", h.policyGateway.featureSubmissions.isEmpty())
        assertTrue(h.policyGateway.packageSubmissions.isEmpty())
        assertEquals(featuresBefore, h.policyGateway.features)
        assertEquals(packagesBefore, h.policyGateway.packages)
        assertNotNull(status.recoveryIncompleteStep)
        assertNotNull(h.settingsStore.incident)
        assertTrue(h.inhibitor.isInhibited)
    }

    @Test
    fun known_owner_mask_failure_does_not_skip_stop_or_empty_allowlist_exit() = runTest {
        val h = H()
        h.seedSettings(enabled = false, recoveryRequired = true)
        h.seedBaseline(features = 0, packages = emptySet())
        h.setRestrictedPolicy()
        h.policyGateway.ignoreFeatureWrites = true
        h.session.stopLeavesStateUnchanged = true
        assertEquals(LockTaskRuntimeStates.LOCKED, h.runtime.state)
        assertEquals(LockTaskMasks.PROTECTED, h.policyGateway.features)

        val status = h.coordinator.disableAndRestore()

        assertEquals(ProtectionState.RECOVERY_FAILED, status.state)
        assertEquals("runtime remains locked despite all release exits", "VERIFY_SESSION_RELEASED", status.recoveryIncompleteStep)
        assertTrue("permissive mask submission must be attempted", h.policyGateway.featureSubmissions.contains(LockTaskMasks.ALLOWED))
        assertEquals("the independent activity exit must be attempted", 1, h.session.stopRequests)
        assertTrue("the independent package exit must actually submit an empty allowlist", h.policyGateway.packageSubmissions.contains(emptySet()))
        assertEquals("the fake models a session that did not end", LockTaskRuntimeStates.LOCKED, h.runtime.state)
        assertEquals("ignored mask setter must leave the restrictive mask in force", LockTaskMasks.PROTECTED, h.policyGateway.features)
        assertNotNull(h.settingsStore.incident)
        assertTrue(h.inhibitor.isInhibited)
    }

    @Test
    fun temporary_activation_recovers_existing_restriction_before_refused_foreground_entry() = runTest {
        val h = H()
        h.seedSettings(enabled = false)
        h.seedBaseline(features = 0, packages = emptySet())
        h.setRestrictedPolicy()
        h.session.entryAvailable = false
        assertEquals(LockTaskRuntimeStates.LOCKED, h.runtime.state)
        assertEquals(LockTaskMasks.PROTECTED, h.policyGateway.features)
        assertFalse(h.session.entryAvailable)
        assertNull(h.settingsStore.marker)

        val status = h.coordinator.activatePocSession()

        assertEquals(ProtectionState.DISARMED, status.state)
        assertEquals("existing task must be stopped before the foreground prerequisite refusal", 1, h.session.stopRequests)
        assertEquals("no new task entry may be requested", 0, h.session.startRequests)
        assertTrue("the old restriction must go through release", h.policyGateway.featureSubmissions.contains(LockTaskMasks.ALLOWED))
        assertEquals(0, h.policyGateway.features)
        assertTrue(h.policyGateway.packages.isEmpty())
        assertNull("refused activation must not leave a temporary marker", h.settingsStore.marker)
        assertEquals(0, h.schedulingGateway.temporaryReleaseCount)
        assertNull(h.settingsStore.incident)
    }

    @Test
    fun trusted_prepared_original_47_restores_and_repeated_restore_stays_verified() = runTest {
        val h = H()
        h.seedSettings(enabled = false, recoveryRequired = true)
        h.seedBaseline(features = LockTaskMasks.PROTECTED, packages = setOf(APP_ID))
        h.setRestrictedPolicy()

        val first = h.coordinator.disableAndRestore()
        val writesAfterFirst = h.policyGateway.featureSubmissions.size
        val packageWritesAfterFirst = h.policyGateway.packageSubmissions.size

        assertEquals(ProtectionState.DISARMED, first.state)
        assertEquals(LockTaskMasks.PROTECTED, h.policyGateway.features)
        assertEquals(setOf(APP_ID), h.policyGateway.packages)
        assertEquals(LockTaskRuntimeStates.NONE, h.runtime.state)
        assertEquals(BaselineLifecycleState.RESTORED, h.settingsStore.baseline?.lifecycle)
        assertEquals(1, h.session.stopRequests)
        assertTrue(h.policyGateway.featureSubmissions.contains(LockTaskMasks.ALLOWED))

        val second = h.coordinator.disableAndRestore()

        assertEquals("retained, already-restored provenance is a verified no-op", ProtectionState.DISARMED, second.state)
        assertEquals(LockTaskMasks.PROTECTED, h.policyGateway.features)
        assertEquals(setOf(APP_ID), h.policyGateway.packages)
        assertEquals(writesAfterFirst, h.policyGateway.featureSubmissions.size)
        assertEquals(packageWritesAfterFirst, h.policyGateway.packageSubmissions.size)
        assertEquals(1, h.session.stopRequests)
        assertNull(h.settingsStore.incident)
        assertFalse(h.inhibitor.isInhibited)
    }

    @Test
    fun second_daily_session_recaptures_and_restores_external_originals() = runTest {
        val scenarios = listOf(
            16 to setOf("external.kiosk"),
            LockTaskMasks.PROTECTED to setOf(APP_ID),
        )
        for ((externalFeatures, externalPackages) in scenarios) {
            val events = mutableListOf<String>()
            val h = H(
                transformSettings = { base ->
                    object : SettingsRepository by base {
                        override suspend fun prepareSession(
                            candidateBaseline: PolicyBaseline,
                            expectedApplicationId: String,
                            temporaryMarker: TemporaryTestMarker?,
                        ): SessionPreparationResult {
                            events += "prepare:${candidateBaseline.lockTaskFeatures}:${candidateBaseline.lockTaskPackages.sorted()}"
                            return base.prepareSession(candidateBaseline, expectedApplicationId, temporaryMarker)
                        }
                    }
                },
                transformGateway = { base ->
                    object : DevicePolicyGateway by base {
                        override fun submitLockTaskFeatures(features: Int) {
                            events += "features:$features"
                            base.submitLockTaskFeatures(features)
                        }

                        override fun submitLockTaskPackages(packages: Set<String>) {
                            events += "packages:${packages.sorted()}"
                            base.submitLockTaskPackages(packages)
                        }
                    }
                },
            )
            h.seedSettings(enabled = false)
            h.policyGateway.owner = true
            h.policyGateway.features = 0
            h.policyGateway.packages = mutableSetOf()

            val firstArm = h.coordinator.arm()
            assertEquals("first clean session/$externalFeatures", ProtectionState.ARMED_POWER_MENU_RESTRICTED, firstArm.state)
            assertEquals("first session starts once", 1, h.session.startRequests)
            assertEquals(0, h.settingsStore.baseline?.lockTaskFeatures)
            val firstRestore = h.coordinator.disableAndRestore()
            assertEquals(ProtectionState.DISARMED, firstRestore.state)
            assertEquals(0, h.policyGateway.features)
            assertTrue(h.policyGateway.packages.isEmpty())
            assertEquals(BaselineLifecycleState.RESTORED, h.settingsStore.baseline?.lifecycle)
            assertEquals(LockTaskRuntimeStates.NONE, h.runtime.state)
            assertFalse(h.settingsStore.current.enabled)
            assertFalse(h.settingsStore.current.recoveryRequired)

            // The external policy change belongs to the next real session. Its new original must
            // be captured before this app submits any part of the next managed policy.
            h.policyGateway.features = externalFeatures
            h.policyGateway.packages = externalPackages.toMutableSet()
            h.policyGateway.featureSubmissions.clear()
            h.policyGateway.packageSubmissions.clear()
            events.clear()

            val secondArm = h.coordinator.arm()

            assertEquals("second session/$externalFeatures", ProtectionState.ARMED_POWER_MENU_RESTRICTED, secondArm.state)
            assertEquals("start count advances from one to two", 2, h.session.startRequests)
            assertEquals(externalFeatures, h.settingsStore.baseline?.lockTaskFeatures)
            assertEquals(externalPackages, h.settingsStore.baseline?.lockTaskPackages)
            assertEquals(BaselineProvenance.CAPTURED_BEFORE_PREPARATION, h.settingsStore.baseline?.provenance)
            assertEquals(BaselineLifecycleState.PREPARED, h.settingsStore.baseline?.lifecycle)
            assertTrue(
                "new baseline must be durably captured before any policy setter: $events",
                events.first().startsWith("prepare:$externalFeatures:${externalPackages.sorted()}"),
            )
            assertFalse("first session's old mask 0 must not be submitted during the new session", h.policyGateway.featureSubmissions.contains(0))
            assertFalse("first session's old empty package list must not be submitted during the new session", h.policyGateway.packageSubmissions.contains(emptySet()))

            val secondRestore = h.coordinator.disableAndRestore()
            assertEquals(ProtectionState.DISARMED, secondRestore.state)
            assertEquals("second session restores the actual $externalFeatures original", externalFeatures, h.policyGateway.features)
            assertEquals(externalPackages, h.policyGateway.packages)
            assertEquals(LockTaskRuntimeStates.NONE, h.runtime.state)
            assertEquals(BaselineLifecycleState.RESTORED, h.settingsStore.baseline?.lifecycle)
            assertFalse(h.settingsStore.current.enabled)
            assertFalse(h.settingsStore.current.recoveryRequired)
            assertNull(h.settingsStore.marker)
            assertNull(h.settingsStore.incident)
            assertNull(h.settingsStore.receipt)
            val featureWritesAfterSecondRestore = h.policyGateway.featureSubmissions.size
            val packageWritesAfterSecondRestore = h.policyGateway.packageSubmissions.size
            val stopsAfterSecondRestore = h.session.stopRequests

            val repeatedRestore = h.coordinator.disableAndRestore()
            assertEquals("repeat Restore remains a clean no-op/$externalFeatures", ProtectionState.DISARMED, repeatedRestore.state)
            assertEquals(externalFeatures, h.policyGateway.features)
            assertEquals(externalPackages, h.policyGateway.packages)
            assertEquals(featureWritesAfterSecondRestore, h.policyGateway.featureSubmissions.size)
            assertEquals(packageWritesAfterSecondRestore, h.policyGateway.packageSubmissions.size)
            assertEquals(stopsAfterSecondRestore, h.session.stopRequests)
            assertFalse(h.settingsStore.current.recoveryRequired)
            assertNull(h.settingsStore.incident)
        }
    }

    @Test
    fun unlocked_lock_wait_fallback_cannot_resurrect_incident_after_restore_cleanup() = runTest {
        val firstPollSleepEntered = CompletableDeferred<Unit>()
        val secondPollSleepEntered = CompletableDeferred<Unit>()
        val releaseSecondPollSleep = CompletableDeferred<Unit>()
        val fallbackReadEntered = CompletableDeferred<Unit>()
        val releaseFallbackRead = CompletableDeferred<Unit>()
        val cleanupCommitted = CompletableDeferred<Unit>()
        var recoverySleepCalls = 0
        var incidentReads = 0
        var fallbackSnapshot: RecoveryIncident? = null
        val retrySubmissions = mutableListOf<String>()
        var currentlyInstalledRetryId: String? = null
        var retryCancellations = 0

        val h = H(
            recoverySleep = {
                when (recoverySleepCalls++) {
                    0 -> {
                        firstPollSleepEntered.complete(Unit)
                        delay(380L)
                    }
                    else -> {
                        secondPollSleepEntered.complete(Unit)
                        releaseSecondPollSleep.await()
                    }
                }
            },
            transformSettings = { base ->
                object : SettingsRepository by base {
                    override suspend fun readRecoveryIncident(): RecoveryIncident? {
                        val snapshot = base.readRecoveryIncident()
                        if (++incidentReads == 2) {
                            // Snapshot the fallback's incident before cleanup on the current path.
                            // A correct shared serialization may defer this read until cleanup has
                            // completed; only the stale pre-cleanup path is parked, so cleanup can
                            // proceed when the repaired implementation shares bookkeeping locks.
                            fallbackSnapshot = snapshot
                            fallbackReadEntered.complete(Unit)
                            if (!cleanupCommitted.isCompleted) releaseFallbackRead.await()
                        }
                        return snapshot
                    }

                    override suspend fun commitCleanup(commit: com.example.shutdownprotection.data.CleanupCommit): SettingsWriteResult {
                        val result = base.commitCleanup(commit)
                        if (result is SettingsWriteResult.Success && commit.clearRecoveryIncident) {
                            cleanupCommitted.complete(Unit)
                        }
                        return result
                    }
                }
            },
            transformScheduling = { base ->
                object : SchedulingGateway by base {
                    override fun cancel(kind: AlarmEventKind): CancelledAlarm {
                        val result = base.cancel(kind)
                        if (kind == AlarmEventKind.RECOVERY_RETRY) {
                            retryCancellations++
                            currentlyInstalledRetryId = null
                        }
                        return result
                    }

                    override fun installRecoveryRetry(incidentId: String, triggerAt: Instant): Boolean {
                        retrySubmissions += incidentId
                        val installed = base.installRecoveryRetry(incidentId, triggerAt)
                        if (installed) currentlyInstalledRetryId = incidentId
                        return installed
                    }
                }
            },
        )
        h.seedSettings(enabled = false, recoveryRequired = true)
        h.seedBaseline(features = 16, packages = setOf("external.kiosk"))
        h.setRestrictedPolicy()
        h.session.stopLeavesStateUnchanged = true
        h.settingsStore.incident = RecoveryIncident(
            incidentId = "cleanup-race-incident",
            openedAtEpochMillis = 1L,
            attempts = 0,
            unresolved = true,
        )

        val restore = async { h.coordinator.disableAndRestore() }
        runCurrent()
        withTimeout(5_000L) { firstPollSleepEntered.await() }
        assertEquals("Restore is genuinely holding the coordinator lock in active-session recovery", LockTaskRuntimeStates.LOCKED, h.runtime.state)

        val blockedRelease = async { h.coordinator.reconcile(ProtectionTrigger.END_ALARM) }
        runCurrent()
        advanceTimeBy(380L)
        runCurrent()
        withTimeout(5_000L) { secondPollSleepEntered.await() }
        assertTrue("the existing unresolved incident is still durable before lock wait expires", h.settingsStore.incident?.unresolved == true)

        // The recovery remains in a cooperative polling wait while the queued operation consumes
        // the rest of its real 400 ms mutex budget. The fallback snapshots the incident and pauses
        // inside the bounded read, creating a deterministic stale-continuation window.
        advanceTimeBy(ProtectionCoordinator.LOCK_WAIT_BUDGET_MILLIS - 380L + 1L)
        runCurrent()
        assertEquals(LockTaskRuntimeStates.LOCKED, h.runtime.state)

        // Release the real session and let recovery commit cleanup while an already-entered
        // fallback read remains parked. Run current first so the unsynchronized implementation can
        // finish cleanup before the stale continuation resumes. A serialized implementation may
        // wait for that read to finish before it can commit cleanup; release is unconditional below.
        h.runtime.state = LockTaskRuntimeStates.NONE
        releaseSecondPollSleep.complete(Unit)
        runCurrent()
        releaseFallbackRead.complete(Unit)
        runCurrent()
        withTimeout(5_000L) { cleanupCommitted.await() }
        val restoreStatus = restore.await()
        assertEquals(ProtectionState.DISARMED, restoreStatus.state)
        assertEquals(16, h.policyGateway.features)
        assertEquals(setOf("external.kiosk"), h.policyGateway.packages)
        assertEquals(BaselineLifecycleState.RESTORED, h.settingsStore.baseline?.lifecycle)
        assertFalse(h.settingsStore.current.recoveryRequired)
        assertNull(h.settingsStore.incident)
        assertFalse(h.inhibitor.isInhibited)

        withTimeout(5_000L) { fallbackReadEntered.await() }
        val timeoutStatus = blockedRelease.await()
        assertEquals(ProtectionState.RECOVERY_FAILED, timeoutStatus.state)
        assertTrue("the unlocked lock-wait route must read durable incident state", incidentReads >= 2)
        assertTrue(
            "the old incident was captured before cleanup only if it was still unresolved then",
            fallbackSnapshot == null || fallbackSnapshot?.incidentId == "cleanup-race-incident",
        )

        assertNull("late fallback must not resurrect a cleaned incident", h.settingsStore.incident)
        assertFalse("late fallback must not restore recoveryRequired after successful cleanup", h.settingsStore.current.recoveryRequired)
        assertNull("cleanup must cancel any retry that was installed before it", currentlyInstalledRetryId)
        if (retrySubmissions.isNotEmpty()) {
            assertTrue("the final cleanup must cancel an intermediate stale retry", retryCancellations > 0)
        }
        assertFalse("successful Restore must leave the restriction brake cleared", h.inhibitor.isInhibited)
        assertEquals(LockTaskRuntimeStates.NONE, h.runtime.state)

        val admittedAfterCleanup = h.coordinator.arm()
        assertEquals("a later fresh session remains admissible after the stale callback is fenced", ProtectionState.ARMED_POWER_MENU_RESTRICTED, admittedAfterCleanup.state)
        assertEquals(1, h.session.startRequests)
    }

    @Test
    fun temporary_sessions_use_distinct_ids_and_ignore_the_first_session_delivery() = runTest {
        val h = H()
        h.seedSettings(enabled = false)
        h.policyGateway.owner = true
        h.policyGateway.features = 0
        h.policyGateway.packages = mutableSetOf()

        val firstStart = h.coordinator.activatePocSession()
        assertEquals(ProtectionState.ARMED_POWER_MENU_ALLOWED, firstStart.state)
        val markerA = h.settingsStore.marker ?: error("temporary start must persist marker A")
        val firstId = markerA.sessionId
        assertNotNull(firstId)
        assertEquals(LockTaskRuntimeStates.LOCKED, h.runtime.state)
        assertEquals(1, h.session.startRequests)

        val firstRelease = h.coordinator.releaseTemporaryTest(
            deliveredReleaseAtEpochMillis = markerA.releaseAtEpochMillis,
            deliveredSessionId = firstId,
        )
        assertEquals(ProtectionState.DISARMED, firstRelease.state)
        assertNull(h.settingsStore.marker)
        assertEquals(LockTaskRuntimeStates.NONE, h.runtime.state)

        // The fake wall clock is intentionally fixed, so both runs have the same releaseAt. UUID
        // identity must still distinguish the stale first delivery from the second live session.
        val secondStart = h.coordinator.activatePocSession()
        assertEquals(ProtectionState.ARMED_POWER_MENU_ALLOWED, secondStart.state)
        val markerB = h.settingsStore.marker ?: error("temporary start must persist marker B")
        assertEquals(markerA.releaseAtEpochMillis, markerB.releaseAtEpochMillis)
        assertNotEquals(firstId, markerB.sessionId)
        assertEquals(LockTaskRuntimeStates.LOCKED, h.runtime.state)
        assertEquals(2, h.session.startRequests)

        val restricted = h.coordinator.setPocOverride(PocOverride.FORCE_RESTRICTED)
        assertEquals(ProtectionState.ARMED_POWER_MENU_RESTRICTED, restricted.state)
        assertEquals(LockTaskMasks.PROTECTED, h.policyGateway.features)
        assertEquals(markerB, h.settingsStore.marker)
        val safeguardCount = h.schedulingGateway.temporaryReleaseCount
        val repeatedRestrict = h.coordinator.setPocOverride(PocOverride.FORCE_RESTRICTED)
        assertEquals(ProtectionState.ARMED_POWER_MENU_RESTRICTED, repeatedRestrict.state)
        assertEquals("repeating restrict must keep the original marker and expiry", markerB, h.settingsStore.marker)
        assertEquals(markerB.releaseAtEpochMillis, h.settingsStore.marker?.releaseAtEpochMillis)
        assertEquals("the existing safeguard must not be replaced or extended", safeguardCount, h.schedulingGateway.temporaryReleaseCount)
        val stopsBeforeStaleDelivery = h.session.stopRequests

        val staleResult = h.coordinator.releaseTemporaryTest(
            deliveredReleaseAtEpochMillis = markerA.releaseAtEpochMillis,
            deliveredSessionId = firstId,
        )

        assertEquals("stale event must leave second live test intact", markerB, h.settingsStore.marker)
        assertEquals(LockTaskRuntimeStates.LOCKED, h.runtime.state)
        assertEquals(stopsBeforeStaleDelivery, h.session.stopRequests)
        assertEquals(2, h.session.startRequests)
        assertEquals("stale test A delivery must leave restrictive test B restricted", ProtectionState.ARMED_POWER_MENU_RESTRICTED, staleResult.state)
        assertEquals(LockTaskMasks.PROTECTED, h.policyGateway.features)

        val secondRelease = h.coordinator.releaseTemporaryTest(
            deliveredReleaseAtEpochMillis = markerB.releaseAtEpochMillis,
            deliveredSessionId = markerB.sessionId,
        )
        assertEquals(ProtectionState.DISARMED, secondRelease.state)
        assertNull(h.settingsStore.marker)
        assertEquals(LockTaskRuntimeStates.NONE, h.runtime.state)
        assertEquals(2, h.session.startRequests)
    }

    @Test
    fun missing_temporary_marker_with_locked_runtime_recovers_even_when_policy_matches_baseline() = runTest {
        val h = H()
        h.seedSettings(enabled = false, recoveryRequired = false)
        h.seedBaseline(features = LockTaskMasks.PROTECTED, packages = setOf(APP_ID))
        h.policyGateway.owner = true
        h.policyGateway.features = LockTaskMasks.PROTECTED
        h.policyGateway.packages = mutableSetOf(APP_ID)
        h.runtime.state = LockTaskRuntimeStates.LOCKED
        h.settingsStore.marker = null
        assertFalse(h.inhibitor.isInhibited)
        assertNull(h.settingsStore.marker)
        assertEquals("policy values exactly match trusted prepared original", 47, h.policyGateway.features)

        val status = h.coordinator.releaseTemporaryTest()

        assertEquals(ProtectionState.DISARMED, status.state)
        assertEquals("runtime lock alone must force release", 1, h.session.stopRequests)
        assertTrue(h.policyGateway.featureSubmissions.contains(LockTaskMasks.ALLOWED))
        assertEquals(LockTaskRuntimeStates.NONE, h.runtime.state)
        assertEquals(LockTaskMasks.PROTECTED, h.policyGateway.features)
        assertEquals(setOf(APP_ID), h.policyGateway.packages)
        assertEquals(BaselineLifecycleState.RESTORED, h.settingsStore.baseline?.lifecycle)
        assertNull(h.settingsStore.marker)
        assertFalse(h.inhibitor.isInhibited)
    }

    @Test
    fun relevant_null_policy_result_recovers_live_restriction() = runTest {
        val h = H()
        h.seedSettings(enabled = false)
        h.seedBaseline(features = 0, packages = emptySet())
        h.setRestrictedPolicy()
        h.settingsStore.marker = TemporaryTestMarker(
            startedAtEpochMillis = 1L,
            releaseAtEpochMillis = 2L,
            revision = h.settingsStore.current.revision,
            sessionId = "live-callback-session",
        )
        val submissionsBefore = h.policyGateway.featureSubmissions.size

        // A missing Android result code is explicitly failure for this relevant lock-task policy.
        val status = h.coordinator.handlePolicyChanged(DevicePolicyIdentifiers.LOCK_TASK_POLICY, null)

        assertEquals(ProtectionState.DISARMED, status.state)
        assertEquals(1, h.session.stopRequests)
        assertEquals(LockTaskRuntimeStates.NONE, h.runtime.state)
        assertEquals(0, h.policyGateway.features)
        assertTrue(h.policyGateway.featureSubmissions.drop(submissionsBefore).contains(LockTaskMasks.ALLOWED))
        assertFalse(h.policyGateway.featureSubmissions.drop(submissionsBefore).contains(LockTaskMasks.PROTECTED))
        assertNull(h.settingsStore.marker)
        assertFalse(h.settingsStore.current.recoveryRequired)
        assertNull(h.settingsStore.incident)
    }

    @Test
    fun clean_restore_is_not_changed_by_a_diagnostics_write_suspended_past_its_budget() = runTest {
        var recordCalls = 0
        val h = H(
            transformDiagnostics = { base ->
                object : DiagnosticsRepository by base {
                    override suspend fun record(kind: String, revision: Long, message: String) {
                        recordCalls++
                        delay(1_000_000L)
                        base.record(kind, revision, message)
                    }
                }
            },
        )
        h.seedSettings(allowedPackages = emptySet())
        h.seedBaseline(
            features = LockTaskMasks.PROTECTED,
            packages = setOf(APP_ID),
            lifecycle = BaselineLifecycleState.RESTORED,
        )
        h.policyGateway.owner = true
        h.policyGateway.features = LockTaskMasks.PROTECTED
        h.policyGateway.packages = mutableSetOf(APP_ID)
        h.runtime.state = LockTaskRuntimeStates.NONE
        assertTrue(h.settingsStore.baseline!!.isTrustedOriginal)

        val status = h.coordinator.disableAndRestore()

        assertEquals("the no-op Restore must finish despite optional logging", ProtectionState.DISARMED, status.state)
        assertEquals(1, recordCalls)
        assertEquals("restored original feature mask must remain exact", LockTaskMasks.PROTECTED, h.policyGateway.features)
        assertEquals(setOf(APP_ID), h.policyGateway.packages)
        assertEquals(LockTaskRuntimeStates.NONE, h.runtime.state)
        assertTrue(h.policyGateway.featureSubmissions.isEmpty())
        assertTrue(h.policyGateway.packageSubmissions.isEmpty())
        assertEquals(0, h.session.stopRequests)
        assertNull(h.settingsStore.incident)
        assertFalse(h.inhibitor.isInhibited)
    }

    @Test
    fun inhibitor_engaged_while_arm_waits_at_receipt_gate_prevents_entry_after_lock_wait_timeout() = runTest {
        val receiptEntered = CompletableDeferred<Unit>()
        val releaseReceipt = CompletableDeferred<Unit>()
        val h = H(
            transformSettings = { base ->
                object : SettingsRepository by base {
                    override suspend fun writeScheduleReceipt(receipt: ScheduleReceipt?): SettingsWriteResult {
                        val result = base.writeScheduleReceipt(receipt)
                        if (receipt != null) {
                            receiptEntered.complete(Unit)
                            releaseReceipt.await()
                        }
                        return result
                    }
                }
            },
        )
        h.seedSettings(enabled = false)
        h.policyGateway.owner = true
        h.policyGateway.features = 0
        h.policyGateway.packages = mutableSetOf()

        val firstArm = async { h.coordinator.arm() }
        runCurrent()
        receiptEntered.await()
        assertEquals("first operation is parked after preparing and scheduling", 0, h.session.startRequests)

        // Queue a real coordinator operation behind the parked arm. Its lock-wait deadline must
        // engage the shared inhibitor before the first operation resumes.
        val blockedRelease = async { h.coordinator.reconcile(ProtectionTrigger.END_ALARM) }
        runCurrent()
        testScheduler.advanceTimeBy(ProtectionCoordinator.LOCK_WAIT_BUDGET_MILLIS + 1L)
        runCurrent()
        val blockedStatus = blockedRelease.await()

        assertEquals(ProtectionState.RECOVERY_FAILED, blockedStatus.state)
        assertEquals("LOCK_WAIT_TIMEOUT", blockedStatus.recoveryIncompleteStep)
        assertTrue("lock-wait timeout must engage the shared inhibitor", h.inhibitor.isInhibited)
        assertEquals("the in-flight arm still has its verified pre-entry allowlist", setOf(APP_ID), h.policyGateway.packages)
        releaseReceipt.complete(Unit)

        advanceUntilIdle()
        val armStatus = firstArm.await()

        assertEquals("the queued release must prevent a new lock-task entry", 0, h.session.startRequests)
        assertFalse("no restrictive mask may be submitted after inhibition", h.policyGateway.featureSubmissions.contains(LockTaskMasks.PROTECTED))
        assertNotEquals(ProtectionState.ARMED_POWER_MENU_RESTRICTED, armStatus.state)
        assertNotEquals(ProtectionState.ARMED_POWER_MENU_RESTRICTED, h.coordinator.status.value?.state)
        assertEquals(LockTaskRuntimeStates.NONE, h.runtime.state)
    }

    private companion object {
        const val APP_ID = "com.example.shutdownprotection"
        val INSIDE_WINDOW: Instant = Instant.parse("2026-06-15T03:00:00Z")
    }
}
