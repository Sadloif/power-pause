package com.example.shutdownprotection.fakes

import com.example.shutdownprotection.admin.DevicePolicyGateway
import com.example.shutdownprotection.admin.LockTaskRuntimeStates
import com.example.shutdownprotection.admin.RuntimeLockTaskStateProvider
import com.example.shutdownprotection.data.CleanupCommit
import com.example.shutdownprotection.data.DiagnosticEvent
import com.example.shutdownprotection.data.DiagnosticsRepository
import com.example.shutdownprotection.data.BaselineLifecycle
import com.example.shutdownprotection.data.BaselineLifecycleState
import com.example.shutdownprotection.data.BaselineProvenance
import com.example.shutdownprotection.data.PolicyBaseline
import com.example.shutdownprotection.data.ProtectionSettings
import com.example.shutdownprotection.data.RecoveryIncident
import com.example.shutdownprotection.data.RuntimeObservation
import com.example.shutdownprotection.data.ScheduleReceipt
import com.example.shutdownprotection.data.SessionPreparationResult
import com.example.shutdownprotection.data.SettingsReadResult
import com.example.shutdownprotection.data.SettingsRepository
import com.example.shutdownprotection.data.SettingsWriteResult
import com.example.shutdownprotection.data.TemporaryTestMarker
import com.example.shutdownprotection.protection.EnvironmentStateProvider
import com.example.shutdownprotection.protection.LockTaskSessionController
import com.example.shutdownprotection.scheduling.AlarmEventKind
import com.example.shutdownprotection.scheduling.AlarmInstallResult
import com.example.shutdownprotection.scheduling.CancelledAlarm
import com.example.shutdownprotection.scheduling.SchedulingGateway
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import java.time.Instant

/**
 * In-memory durable store. Every write is observable so tests can assert on persistence
 * attempts, and [failWrites] models the storage failure the brief requires to be
 * non-fatal for release.
 */
class FakeSettingsRepository : SettingsRepository {

    private val flow = MutableStateFlow(ProtectionSettings.DEFAULT)

    var failWrites: Boolean = false
    var corruptReads: Boolean = false

    var baseline: PolicyBaseline? = null
    var receipt: ScheduleReceipt? = null
    var marker: TemporaryTestMarker? = null
    var incident: RecoveryIncident? = null
    var bootGeneration: Long = 0L
    var corruptionFlagged: Boolean = false
    var ownerEstablished: Boolean = false

    var writeCount: Int = 0
        private set

    override val settingsFlow: Flow<ProtectionSettings> get() = flow

    val current: ProtectionSettings get() = flow.value

    fun seed(settings: ProtectionSettings) {
        flow.value = settings
    }

    override suspend fun readSettings(): SettingsReadResult =
        if (corruptReads) {
            SettingsReadResult.Corrupt(IllegalStateException("simulated corruption"))
        } else {
            SettingsReadResult.Success(flow.value)
        }

    override suspend fun writeSettings(settings: ProtectionSettings): SettingsWriteResult {
        writeCount++
        if (failWrites) return SettingsWriteResult.Failure(IllegalStateException("simulated write failure"))
        flow.value = settings
        return SettingsWriteResult.Success
    }

    override suspend fun editSettings(
        transform: (ProtectionSettings) -> ProtectionSettings,
    ): SettingsWriteResult = writeSettings(transform(flow.value))

    override suspend fun readBaseline(): PolicyBaseline? = baseline

    override suspend fun writeBaseline(baseline: PolicyBaseline): SettingsWriteResult {
        if (failWrites) return SettingsWriteResult.Failure(IllegalStateException("simulated write failure"))
        this.baseline = baseline
        return SettingsWriteResult.Success
    }

    override suspend fun clearBaseline(): SettingsWriteResult {
        baseline = null
        return SettingsWriteResult.Success
    }

    /** Independently injectable so a test can fail preparation without failing every write. */
    var failPrepareSession: Boolean = false

    /** Independently injectable so a test can fail only the final cleanup commit. */
    var failCommitCleanup: Boolean = false

    override suspend fun prepareSession(
        candidateBaseline: PolicyBaseline,
        expectedApplicationId: String,
        temporaryMarker: TemporaryTestMarker?,
    ): SessionPreparationResult {
        // The failure path returns BEFORE either durable field is touched (independent audit
        // finding): the baseline and the journal are one transaction, so a failed preparation
        // must leave *both* unchanged. Previously the failure check sat below the stored-baseline
        // inspection, which made the "no partial durable pair" property impossible to assert
        // against this fake.
        if (failWrites || failPrepareSession) {
            return SessionPreparationResult.Failed(IllegalStateException("simulated prepareSession failure"))
        }
        val existing = baseline
        if (existing != null && !existing.isTrustedOriginal) {
            return SessionPreparationResult.InvalidStoredBaseline("baseline provenance or lifecycle is ambiguous")
        }
        if (existing != null) {
            val reason = existing.invalidReasonFor(expectedApplicationId)
            if (reason != null) return SessionPreparationResult.InvalidStoredBaseline(reason)
        }
        val reuseExisting = existing != null && existing.lifecycle != BaselineLifecycleState.RESTORED
        val prepared = if (reuseExisting) existing!! else candidateBaseline.copy(
            provenance = BaselineProvenance.CAPTURED_BEFORE_PREPARATION,
            lifecycle = BaselineLifecycleState.PREPARED,
        )
        if (!reuseExisting) baseline = prepared
        val nextRevision = if (temporaryMarker == null) flow.value.revision + 1L else flow.value.revision
        flow.value = flow.value.copy(
            enabled = false,
            recoveryRequired = temporaryMarker == null,
            revision = nextRevision,
        )
        if (temporaryMarker != null) marker = temporaryMarker.copy(revision = nextRevision)
        return if (reuseExisting) {
            SessionPreparationResult.ReusedExisting(prepared, nextRevision)
        } else {
            SessionPreparationResult.Prepared(prepared, nextRevision)
        }
    }

    override suspend fun commitCleanup(commit: CleanupCommit): SettingsWriteResult {
        if (failWrites || failCommitCleanup) {
            return SettingsWriteResult.Failure(IllegalStateException("simulated commitCleanup failure"))
        }
        flow.value = flow.value.copy(enabled = commit.enabled, recoveryRequired = commit.recoveryRequired)
        if (commit.clearCorruptionFlag) corruptionFlagged = false
        if (commit.clearTemporaryTestMarker) marker = null
        if (commit.clearRecoveryIncident) incident = null
        if (commit.clearScheduleReceipt) receipt = null
        if (commit.baselineLifecycle == BaselineLifecycle.CLEAR) baseline = null
        if (commit.baselineLifecycle == BaselineLifecycle.MARK_RESTORED) {
            baseline = baseline?.copy(lifecycle = BaselineLifecycleState.RESTORED)
        }
        return SettingsWriteResult.Success
    }

    override suspend fun readScheduleReceipt(): ScheduleReceipt? = receipt

    override suspend fun writeScheduleReceipt(receipt: ScheduleReceipt?): SettingsWriteResult {
        if (failWrites) return SettingsWriteResult.Failure(IllegalStateException("simulated write failure"))
        this.receipt = receipt
        return SettingsWriteResult.Success
    }

    override suspend fun readTemporaryTestMarker(): TemporaryTestMarker? = marker

    override suspend fun writeTemporaryTestMarker(marker: TemporaryTestMarker?): SettingsWriteResult {
        if (failWrites) return SettingsWriteResult.Failure(IllegalStateException("simulated write failure"))
        this.marker = marker
        return SettingsWriteResult.Success
    }

    override suspend fun readRecoveryIncident(): RecoveryIncident? = incident

    override suspend fun writeRecoveryIncident(incident: RecoveryIncident?): SettingsWriteResult {
        if (failWrites) return SettingsWriteResult.Failure(IllegalStateException("simulated write failure"))
        this.incident = incident
        if (incident?.unresolved == true) {
            flow.value = flow.value.copy(enabled = false, recoveryRequired = true)
        }
        return SettingsWriteResult.Success
    }

    override suspend fun readBootGeneration(): Long = bootGeneration

    override suspend fun bumpBootGeneration(): Long {
        bootGeneration += 1
        return bootGeneration
    }

    override suspend fun isCorruptionFlagged(): Boolean = corruptionFlagged

    override suspend fun clearCorruptionFlag(): SettingsWriteResult {
        corruptionFlagged = false
        return SettingsWriteResult.Success
    }

    override suspend fun isOwnerEstablished(): Boolean = ownerEstablished

    override suspend fun markOwnerEstablished(): SettingsWriteResult {
        ownerEstablished = true
        return SettingsWriteResult.Success
    }
}

/** Diagnostics that can be made to fail, to prove logging failure never blocks release. */
class FakeDiagnosticsRepository : DiagnosticsRepository {

    val events = mutableListOf<DiagnosticEvent>()
    var failWrites: Boolean = false
    var observation: RuntimeObservation? = null

    override suspend fun record(kind: String, revision: Long, message: String) {
        if (failWrites) throw IllegalStateException("simulated diagnostics failure")
        events += DiagnosticEvent(System.currentTimeMillis(), kind, revision, message)
    }

    override suspend fun readAll(): List<DiagnosticEvent> = events.toList()

    override suspend fun clear() {
        events.clear()
    }

    override suspend fun exportText(): String = events.joinToString("\n") { "${it.kind}:${it.message}" }

    override suspend fun readObservation(): RuntimeObservation? = observation

    override suspend fun writeObservation(observation: RuntimeObservation) {
        if (failWrites) throw IllegalStateException("simulated diagnostics failure")
        this.observation = observation
    }
}

/**
 * Policy gateway with observable submissions. [ignoreFeatureWrites] models the case the brief
 * cares about most: the setter returns without throwing but readback does not confirm it.
 */
class FakeDevicePolicyGateway : DevicePolicyGateway {

    var owner: Boolean? = true
    var failFeatureReads: Boolean = false
    var failPackageReads: Boolean = false
    var failPermittedReads: Boolean = false
    var throwOnSetPackages: Boolean = false
    var throwOnSetFeatures: Boolean = false
    var ignoreFeatureWrites: Boolean = false

    /**
     * Models a package-list setter that returns without throwing but never takes effect, so the
     * readback still disagrees with the requested value. Used to prove recovery does not claim a
     * baseline was restored when the package restoration was silently ignored (repair R03).
     */
    var ignorePackageWrites: Boolean = false

    var packages: MutableSet<String> = mutableSetOf()
    var features: Int = 0

    val featureSubmissions = mutableListOf<Int>()
    val packageSubmissions = mutableListOf<Set<String>>()

    override fun isDeviceOwner(): Boolean? = owner

    override fun isLockTaskPermitted(packageName: String): Boolean? =
        if (failPermittedReads) null else packageName in packages

    override fun readLockTaskPackages(): Set<String>? = if (failPackageReads) null else packages.toSet()

    override fun readLockTaskFeatures(): Int? = if (failFeatureReads) null else features

    override fun submitLockTaskPackages(packages: Set<String>) {
        packageSubmissions += packages
        if (throwOnSetPackages) throw SecurityException("simulated SecurityException")
        if (!ignorePackageWrites) this.packages = packages.toMutableSet()
    }

    override fun submitLockTaskFeatures(features: Int) {
        featureSubmissions += features
        if (throwOnSetFeatures) throw SecurityException("simulated SecurityException")
        if (!ignoreFeatureWrites) this.features = features
    }
}

/** Runtime lock-task state the session controller mutates. */
class FakeRuntimeLockTaskStateProvider(
    var state: Int? = LockTaskRuntimeStates.NONE,
) : RuntimeLockTaskStateProvider {
    override fun lockTaskModeState(): Int? = state
}

/**
 * Session controller that really does change the runtime state, so the coordinator's
 * LOCKED-versus-PINNED verification is exercised rather than bypassed.
 */
class FakeLockTaskSessionController(
    private val runtime: FakeRuntimeLockTaskStateProvider,
    var entryAvailable: Boolean = true,
    var resultingState: Int = LockTaskRuntimeStates.LOCKED,
    var stopLeavesStateUnchanged: Boolean = false,
) : LockTaskSessionController {

    var startRequests: Int = 0
        private set
    var stopRequests: Int = 0
        private set

    override fun isEntryAvailable(): Boolean = entryAvailable

    override fun requestStartLockTask(): Boolean {
        startRequests++
        if (!entryAvailable) return false
        runtime.state = resultingState
        return true
    }

    override fun requestStopLockTask(): Boolean {
        stopRequests++
        if (!stopLeavesStateUnchanged) runtime.state = LockTaskRuntimeStates.NONE
        return true
    }
}

/** Scheduling gateway whose submissions and failures are all controllable. */
class FakeSchedulingGateway : SchedulingGateway {

    var exactCapability: Boolean? = true
    var planInstallSucceeds: Boolean = true
    var temporaryReleaseSucceeds: Boolean = true
    var recoveryRetrySucceeds: Boolean = true

    /**
     * Models PendingIntent-token existence. Set false to simulate a force-stop, where the platform
     * has cancelled the app's alarms and removed its PendingIntents even though a receipt is stored.
     *
     * Note this models the *token*, not a scheduled alarm — the distinction repair R07 is about.
     */
    var planPendingIntentTokens: Boolean? = true

    var installPlanCount: Int = 0
        private set
    var cancelAllCount: Int = 0
        private set
    var temporaryReleaseCount: Int = 0
        private set
    var recoveryRetryCount: Int = 0
        private set

    var lastInstalledStart: Instant? = null
        private set
    var lastInstalledEnd: Instant? = null
        private set
    var lastTemporarySessionId: String? = null
        private set

    override fun canScheduleExactAlarms(): Boolean? = exactCapability

    override fun cancelAll(): List<CancelledAlarm> {
        cancelAllCount++
        return AlarmEventKind.entries.map { CancelledAlarm(it, existed = true) }
    }

    override fun cancel(kind: AlarmEventKind): CancelledAlarm = CancelledAlarm(kind, existed = true)

    override fun hasPlanPendingIntentTokens(): Boolean? = planPendingIntentTokens

    override fun installPlan(
        nextEnd: Instant,
        nextStart: Instant,
        revision: Long,
        bootGeneration: Long,
    ): AlarmInstallResult {
        installPlanCount++
        lastInstalledStart = nextStart
        lastInstalledEnd = nextEnd
        if (!planInstallSucceeds) {
            return AlarmInstallResult(true, false, false, false, null, IllegalStateException("simulated"))
        }
        return AlarmInstallResult(
            exactCapability = true,
            endSubmitted = true,
            startSubmitted = true,
            fallbackSubmitted = true,
            receipt = ScheduleReceipt(
                revision = revision,
                bootGeneration = bootGeneration,
                submittedAtEpochMillis = 0L,
                nextStartEpochMillis = nextStart.toEpochMilli(),
                nextEndEpochMillis = nextEnd.toEpochMilli(),
                fallbackEpochMillis = nextEnd.toEpochMilli() + 60_000L,
            ),
            failure = null,
        )
    }

    override fun installTemporaryRelease(
        releaseAt: Instant,
        revision: Long,
        sessionId: String?,
    ): AlarmInstallResult {
        temporaryReleaseCount++
        lastTemporarySessionId = sessionId
        if (!temporaryReleaseSucceeds) {
            return AlarmInstallResult(true, false, false, false, null, IllegalStateException("simulated"))
        }
        return AlarmInstallResult(
            exactCapability = true,
            endSubmitted = true,
            startSubmitted = true,
            fallbackSubmitted = true,
            receipt = ScheduleReceipt(
                revision = revision,
                bootGeneration = 0L,
                submittedAtEpochMillis = 0L,
                nextStartEpochMillis = null,
                nextEndEpochMillis = releaseAt.toEpochMilli(),
                fallbackEpochMillis = releaseAt.toEpochMilli() + 60_000L,
            ),
            failure = null,
        )
    }

    override fun installRecoveryRetry(incidentId: String, triggerAt: Instant): Boolean {
        recoveryRetryCount++
        return recoveryRetrySucceeds
    }
}

/** Unlock / keyguard facts. */
class FakeEnvironmentStateProvider(
    var unlocked: Boolean? = true,
    var keyguardLocked: Boolean? = false,
    var secure: Boolean? = true,
) : EnvironmentStateProvider {
    override fun isUserUnlocked(): Boolean? = unlocked
    override fun isKeyguardLocked(): Boolean? = keyguardLocked
    override fun isDeviceSecure(): Boolean? = secure
}
