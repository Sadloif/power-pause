package com.example.shutdownprotection.protection

import com.example.shutdownprotection.admin.DevicePolicyController
import com.example.shutdownprotection.admin.LockTaskMasks
import com.example.shutdownprotection.admin.LockTaskRuntimeStates
import com.example.shutdownprotection.admin.PolicyOperationResult
import com.example.shutdownprotection.data.BaselineLifecycle
import com.example.shutdownprotection.data.BaselineLifecycleState
import com.example.shutdownprotection.data.CleanupCommit
import com.example.shutdownprotection.data.DiagnosticsRepository
import com.example.shutdownprotection.data.PolicyBaseline
import com.example.shutdownprotection.data.RecoveryIncident
import com.example.shutdownprotection.data.ScheduleReceipt
import com.example.shutdownprotection.data.SettingsRepository
import com.example.shutdownprotection.data.SettingsWriteResult
import com.example.shutdownprotection.scheduling.AlarmEventKind
import com.example.shutdownprotection.scheduling.SchedulingGateway
import java.time.Instant
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Outcome of one recovery attempt (repair R03).
 *
 * The separate flags exist because the guide requires these outcomes to stay distinct: ending the
 * session does **not** prove baseline restoration, and restoring normal controls does **not**
 * remove Device Owner management. `verified` means every documented recovery condition actually
 * holds — never merely that the session ended.
 */
data class RecoveryResult(
    val verified: Boolean,
    val failedStep: String?,
    val detail: String,
    /** True only when recover()'s separate early settings write persisted disabled/recovery-required intent. */
    val durableDisableWritten: Boolean,
    val attemptsRecorded: Int,
    val retryScheduled: Boolean,
    /** The app-controlled managed session was observed to end (`NONE`). Kept separate. */
    val sessionReleased: Boolean,
    /**
     * A trusted original was restored and read back, or the clean no-op path proved a retained
     * trusted original still matched. Preserving a default or otherwise current policy is not an
     * original-baseline restoration.
     */
    val baselineRestored: Boolean,
    /**
     * Required cleanup was durably committed. A clean no-history no-op also reports true because
     * there was no pending cleanup state to commit.
     */
    val durableCleanupCommitted: Boolean,
)

/** Result of opening a release-only incident from a timeout or lock-wait fallback. */
sealed interface ReleaseIncidentOpenResult {
    data class Scheduled(val incidentId: String) : ReleaseIncidentOpenResult
    /** A fresh durable read proved that verified cleanup had already completed. */
    data object ObsoleteClean : ReleaseIncidentOpenResult
    /** The incident or its matching retry could not be durably arranged. */
    data class Failed(val detail: String) : ReleaseIncidentOpenResult
}

/**
 * The idempotent recovery sequence (brief section 10, repaired per guide R03).
 *
 * Ordering that matters, and why:
 *
 * 1. **Inhibit first.** New restriction is blocked before any lengthy cleanup begins, so a slow
 *    or failing cleanup cannot be raced by a fresh arm attempt.
 * 2. **Release before bookkeeping.** The permissive mask request and the session exit happen
 *    before any diagnostic write or final commit. Optional logging can never consume the only
 *    opportunity to restore controls.
 * 3. **Verify, do not assume.** Baseline restoration checks setter results *and* reads back both
 *    the feature mask and the package list, comparing them to the stored baseline.
 * 4. **Commit cleanup atomically.** The required cleanup fields land in one transaction, and the
 *    result is checked. A failure keeps recovery incomplete and the inhibitor engaged.
 * 5. **Clear the brake only at the end**, and only when everything above verified.
 *
 * **Serialization:** `recover()` is called from inside the coordinator's serialized critical
 * section. Deadline and lock-wait fallbacks can call `openReleaseOnlyIncident()` outside that
 * section, so incident read/modify/write/retry submission and final cleanup/cancellation share a
 * separate bounded bookkeeping mutex.
 */
class RecoveryManager(
    private val settingsRepository: SettingsRepository,
    private val diagnostics: DiagnosticsRepository,
    private val policyController: DevicePolicyController,
    private val scheduleManager: SchedulingGateway,
    private val lockTaskSession: LockTaskSessionController,
    private val inhibitor: RestrictionInhibitor,
    private val clockMillis: () -> Long = { System.currentTimeMillis() },
    private val newIncidentId: () -> String = { UUID.randomUUID().toString() },
    private val sessionReleasePollIntervalMillis: Long = 200L,
    private val sessionReleaseMaxPolls: Int = 5,
    private val policyReadbackMaxAttempts: Int = 3,
    private val sleep: suspend (Long) -> Unit = { kotlinx.coroutines.delay(it) },
) {

    private val incidentBookkeepingMutex = Mutex()

    /** Only the newest fallback token can be current, so older generations need not accumulate. */
    private val pendingFallbackInhibition = AtomicReference<RestrictionInhibitor.Token?>(null)

    private data class ReadEvidence<T>(val known: Boolean, val value: T? = null)

    private data class RecoveryEvidence(
        val settings: ReadEvidence<com.example.shutdownprotection.data.ProtectionSettings>,
        val scheduleReceipt: ReadEvidence<ScheduleReceipt?>,
        val baselineRead: ReadEvidence<PolicyBaseline?>,
        val marker: ReadEvidence<com.example.shutdownprotection.data.TemporaryTestMarker?>,
        val incident: ReadEvidence<RecoveryIncident?>,
        val corruption: ReadEvidence<Boolean>,
        val ownerEstablished: ReadEvidence<Boolean>,
        val owner: Boolean?,
        val runtime: Int?,
        val features: Int?,
        val packages: Set<String>?,
    ) {
        fun isCleanNoHistory(applicationId: String): Boolean {
            val current = settings.value ?: return false
            val original = baselineRead.value
            val baselineIsAbsent = baselineRead.known && original == null
            val baselineIsRetired = baselineRead.known && original?.isTrustedOriginal == true &&
                original.lifecycle == BaselineLifecycleState.RESTORED &&
                original.invalidReasonFor(applicationId) == null
            val lifecycleClean = settings.known && !current.enabled && !current.recoveryRequired &&
                (baselineIsAbsent || baselineIsRetired) &&
                scheduleReceipt.known && scheduleReceipt.value == null &&
                marker.known && marker.value == null &&
                incident.known && incident.value == null &&
                corruption.known && corruption.value == false &&
                ownerEstablished.known &&
                runtime == LockTaskRuntimeStates.NONE
            val observedCleanPolicy = owner != null && features in CLEAN_NONRESTRICTIVE_MASKS &&
                packages?.isEmpty() == true
            // With no preparation or session history, an owner-confirmed nonempty allowlist that
            // excludes this app is evidence of externally managed policy. Preserve it when every
            // durable read and the runtime/policy observations are known; never infer this from an
            // unknown owner, an unreadable policy, a package set containing this app, or a locked
            // runtime.
            val validExternalPackages = packages?.takeIf { values ->
                values.isNotEmpty() && values.all { it.isNotBlank() }
            }
            val observedExternalPolicy = baselineIsAbsent && owner == true && features != null &&
                features >= 0 && validExternalPackages != null && applicationId !in validExternalPackages
            // `ownerEstablished` describes authority observed in the past; it is not evidence
            // that this app ever changed policy. With no preparation/baseline/temporary/incident
            // history and a confirmed NONE runtime, a deprovisioned or not-yet-provisioned device
            // must preserve its policy even when DPM no longer lets us read it.
            val noOwnerAndNoManagedHistory = owner == false && ownerEstablished.known && baselineIsAbsent
            val retiredSessionIsComplete = baselineIsRetired
            return lifecycleClean && (
                observedCleanPolicy || observedExternalPolicy || noOwnerAndNoManagedHistory || retiredSessionIsComplete
            )
        }
    }

    /** A slow/corrupt store must not make release wait indefinitely or masquerade as absence. */
    private suspend fun <T> boundedRead(block: suspend () -> T): ReadEvidence<T> {
        val result = withTimeoutOrNull(STORE_READ_BUDGET_MILLIS) {
            try {
                ReadEvidence(known = true, value = block())
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: Throwable) {
                ReadEvidence<T>(known = false)
            }
        }
        return result ?: ReadEvidence(known = false)
    }

    private suspend fun <T> withBookkeepingLock(block: suspend () -> T): T {
        incidentBookkeepingMutex.lock()
        try {
            return block()
        } finally {
            incidentBookkeepingMutex.unlock()
        }
    }

    /**
     * A verified no-op or cleanup resolves outstanding release-fallback leases. A newer ordinary
     * failure lease is not registered as a release fallback and remains active; conditional
     * clears also protect a lease that was superseded while this operation was suspended.
     */
    private fun clearResolvedFallbackInhibitions() {
        pendingFallbackInhibition.getAndSet(null)?.let(inhibitor::clearIfCurrent)
    }

    private fun registerFallbackInhibition(token: RestrictionInhibitor.Token) {
        while (true) {
            val current = pendingFallbackInhibition.get()
            if (current != null && current.generation >= token.generation) return
            if (pendingFallbackInhibition.compareAndSet(current, token)) return
        }
    }

    private fun clearFallbackInhibition(token: RestrictionInhibitor.Token) {
        pendingFallbackInhibition.compareAndSet(token, null)
        inhibitor.clearIfCurrent(token)
    }

    private suspend fun boundedWrite(block: suspend () -> Boolean): Boolean? =
        withTimeoutOrNull(STORE_WRITE_BUDGET_MILLIS) { block() }

    private suspend fun captureEvidence(): RecoveryEvidence {
        val settingsRead = boundedRead {
            when (val result = settingsRepository.readSettings()) {
                is com.example.shutdownprotection.data.SettingsReadResult.Success -> result.settings
                is com.example.shutdownprotection.data.SettingsReadResult.Corrupt ->
                    throw result.cause
            }
        }
        val scheduleReceipt = boundedRead { settingsRepository.readScheduleReceipt() }
        val baseline = boundedRead { settingsRepository.readBaseline() }
        val marker = boundedRead { settingsRepository.readTemporaryTestMarker() }
        val incident = boundedRead { settingsRepository.readRecoveryIncident() }
        val corruption = boundedRead { settingsRepository.isCorruptionFlagged() }
        val ownerEstablished = boundedRead { settingsRepository.isOwnerEstablished() }
        val owner = try { policyController.isDeviceOwner() } catch (_: Throwable) { null }
        val runtime = try { policyController.readRuntimeLockTaskState() } catch (_: Throwable) { null }
        val features = try { policyController.readLockTaskFeatures() } catch (_: Throwable) { null }
        val packages = try { policyController.readLockTaskPackages() } catch (_: Throwable) { null }
        return RecoveryEvidence(
            settings = settingsRead,
            scheduleReceipt = scheduleReceipt,
            baselineRead = baseline,
            marker = marker,
            incident = incident,
            corruption = corruption,
            ownerEstablished = ownerEstablished,
            owner = owner,
            runtime = runtime,
            features = features,
            packages = packages,
        )
    }

    /**
     * Runs the full sequence. Idempotent: running it twice reaches the same verified end state
     * without enabling intent, entering a session, duplicating uncontrolled retries, or
     * overwriting the original baseline.
     */
    suspend fun recover(reason: String): RecoveryResult {
        val recoveryInhibition = inhibitor.inhibit("Recovery in progress ($reason); new restriction is inhibited")

        // Snapshot all evidence that could be destroyed by cleanup. Each store read is finite;
        // unavailable evidence remains UNKNOWN and can never become a clean-no-history no-op.
        var evidence = captureEvidence()
        if (evidence.isCleanNoHistory(policyController.applicationId)) {
            // Fallbacks may have opened an incident after this first snapshot. Recheck while
            // serialized with incident creation and cleanup before preserving the current policy.
            // This also makes an older recovery observe a newer completed cleanup instead of
            // reopening an incident from its stale snapshot.
            val confirmed = withTimeoutOrNull(BOOKKEEPING_LOCK_BUDGET_MILLIS) {
                withBookkeepingLock {
                    captureEvidence().also { fresh ->
                        if (fresh.isCleanNoHistory(policyController.applicationId)) {
                            clearResolvedFallbackInhibitions()
                            inhibitor.clearIfCurrent(recoveryInhibition)
                        }
                    }
                }
            }
            val cleanConfirmation = confirmed?.takeIf {
                it.isCleanNoHistory(policyController.applicationId)
            }
            if (cleanConfirmation == null) {
                // If confirmation did not complete or found a new journal, the first snapshot is
                // no longer safe proof of absence. Continue through release using the fresh state
                // when available, or UNKNOWN original history when the bounded check timed out.
                evidence = confirmed ?: evidence.copy(
                    settings = ReadEvidence(known = false),
                    scheduleReceipt = ReadEvidence(known = false),
                    baselineRead = ReadEvidence(known = false),
                    marker = ReadEvidence(known = false),
                    incident = ReadEvidence(known = false),
                    corruption = ReadEvidence(known = false),
                    ownerEstablished = ReadEvidence(known = false),
                    owner = null,
                    runtime = null,
                    features = null,
                    packages = null,
                )
            } else {
                evidence = cleanConfirmation
                val baseline = evidence.baselineRead.value
                val baselineStillMatches = baseline?.lifecycle == BaselineLifecycleState.RESTORED &&
                    evidence.features == baseline.lockTaskFeatures && evidence.packages == baseline.lockTaskPackages
                val policyObservedNonrestrictive = baseline == null && evidence.owner != null &&
                    evidence.features in CLEAN_NONRESTRICTIVE_MASKS && evidence.packages?.isEmpty() == true
                val observedPackages = evidence.packages
                val validExternalPackages = observedPackages?.takeIf { values ->
                    values.isNotEmpty() && values.all { it.isNotBlank() }
                }
                val policyObservedExternal = baseline == null && evidence.owner == true &&
                    evidence.features != null && evidence.features >= 0 && validExternalPackages != null &&
                    policyController.applicationId !in validExternalPackages
                record(
                    "RECOVERY_NOOP",
                    "reason=$reason; no app session or pending journal exists; " +
                        "policyObservedNonrestrictive=$policyObservedNonrestrictive " +
                        "policyObservedExternal=$policyObservedExternal baselineStillMatches=$baselineStillMatches",
                )
                return RecoveryResult(
                    verified = true,
                    failedStep = null,
                    detail = when {
                        baselineStillMatches -> "No pending managed session exists; current policy still matches the retained original and was preserved."
                        baseline?.lifecycle == BaselineLifecycleState.RESTORED ->
                            "No pending managed session exists; current policy was preserved without reapplying a retired baseline."
                        policyObservedNonrestrictive ->
                            "No managed session or recovery history exists; current policy was preserved without claiming an original baseline was restored."
                        policyObservedExternal ->
                            "No managed session or preparation history exists; the confirmed allowlist excludes this app, " +
                                "so the externally managed policy was preserved without claiming an original baseline was restored."
                        else ->
                            "No managed session or preparation history exists. Device Owner authority is not " +
                                "currently held by this app; current policy was preserved without claiming that an " +
                                "original baseline was restored."
                    },
                    durableDisableWritten = false,
                    attemptsRecorded = 0,
                    retryScheduled = false,
                    sessionReleased = true,
                    baselineRestored = baselineStillMatches,
                    durableCleanupCommitted = true,
                )
            }
        }

        // Never wait for storage before trying to restore controls. Mask release, task stop and
        // allowlist removal are independent exits and are attempted even if the runtime read or
        // Device Owner read is unknown. The controller itself refuses unauthorized writes.
        val permissiveVerified = requestAndVerifyPermissive()
        try { lockTaskSession.requestStopLockTask() } catch (cancellation: CancellationException) { throw cancellation } catch (_: Throwable) { }
        try { policyController.applyAllowedPackages(emptySet()) } catch (cancellation: CancellationException) { throw cancellation } catch (_: Throwable) { }

        val releasedState = awaitSessionEnd()
        val sessionReleased = releasedState == LockTaskRuntimeStates.NONE
        val durableDisableWritten = boundedWrite {
            settingsRepository.editSettings { current ->
                current.copy(enabled = false, recoveryRequired = true).withRevisionBumped()
            } is SettingsWriteResult.Success
        } ?: false
        try { scheduleManager.cancelAll() } catch (_: Throwable) { }

        if (!sessionReleased) {
            val described = releasedState?.let { LockTaskRuntimeStates.describe(it) } ?: "unknown"
            return fail(
                step = "VERIFY_SESSION_RELEASED",
                detail = "Runtime lock-task state is $described, not LOCK_TASK_MODE_NONE.",
                reason = reason,
                durableDisableWritten = durableDisableWritten,
                releaseAttempted = true,
                sessionReleased = false,
                baselineRestored = false,
                durableCleanupCommitted = false,
                recoveryInhibition = recoveryInhibition,
            )
        }
        if (!permissiveVerified) {
            return fail(
                step = "VERIFY_PERMISSIVE",
                detail = "The managed session ended, but the permissive policy could not be confirmed.",
                reason = reason,
                durableDisableWritten = durableDisableWritten,
                releaseAttempted = true,
                sessionReleased = true,
                baselineRestored = false,
                durableCleanupCommitted = false,
                recoveryInhibition = recoveryInhibition,
            )
        }

        // A missing/corrupt baseline is safe only in the clean-no-history case above. Any actual
        // session, marker, incident or policy evidence requires a trusted original; never claim the
        // permissive fallback is the user's original policy.
        if (!evidence.baselineRead.known) {
            return fail("READ_BASELINE", "Stored baseline could not be read; original-policy restoration is unknown.", reason,
                durableDisableWritten, true, true, false, false, recoveryInhibition)
        }
        val baseline = evidence.baselineRead.value
        if (baseline == null) {
            return fail("RESTORE_BASELINE_UNAVAILABLE",
                "No original baseline was stored for this session; controls were released, but the original policy cannot be claimed restored.",
                reason, durableDisableWritten, true, true, false, false, recoveryInhibition)
        }
        val baselineOutcome = restoreBaseline(baseline)
        if (!baselineOutcome.restored) {
            return fail(baselineOutcome.step, baselineOutcome.detail, reason,
                durableDisableWritten, true, true, false, false, recoveryInhibition)
        }

        val cleanup = withTimeoutOrNull(BOOKKEEPING_LOCK_BUDGET_MILLIS) {
            withBookkeepingLock {
                // Do not atomically clear an incident group whose current contents were not
                // readable. The transaction and its retry cancellation are serialized with open
                // and fail, so no stale writer can resurrect that just-cleared incident afterward.
                val incidentRead = boundedRead { settingsRepository.readRecoveryIncident() }
                if (!incidentRead.known) {
                    false
                } else {
                    val persisted = boundedWrite {
                        settingsRepository.commitCleanup(
                            CleanupCommit(
                                enabled = false,
                                recoveryRequired = false,
                                clearCorruptionFlag = true,
                                clearTemporaryTestMarker = true,
                                clearRecoveryIncident = true,
                                clearScheduleReceipt = true,
                                baselineLifecycle = BaselineLifecycle.MARK_RESTORED,
                            ),
                        ) is SettingsWriteResult.Success
                    } ?: false
                    if (persisted) {
                        try { scheduleManager.cancel(AlarmEventKind.RECOVERY_RETRY) } catch (_: Throwable) { }
                        clearResolvedFallbackInhibitions()
                        inhibitor.clearIfCurrent(recoveryInhibition)
                    }
                    persisted
                }
            }
        } ?: false
        if (!cleanup) {
            return fail("PERSIST_FINAL_CLEANUP",
                "Policy release succeeded but durable cleanup did not complete; recovery remains unverified.",
                reason, durableDisableWritten, true, true, true, false, recoveryInhibition)
        }

        record("RECOVERY_VERIFIED", "reason=$reason sessionReleased=true baselineRestored=true")
        return RecoveryResult(
            verified = true,
            failedStep = null,
            detail = "Managed session ended, original policy restored and confirmed, durable cleanup committed",
            durableDisableWritten = durableDisableWritten,
            attemptsRecorded = 0,
            retryScheduled = false,
            sessionReleased = true,
            baselineRestored = true,
            durableCleanupCommitted = true,
        )
    }

    /**
     * Opens a durable unresolved cleanup incident and submits the release-only retry **for that
     * incident** (second review U03). A fresh clean lifecycle returns [ReleaseIncidentOpenResult.ObsoleteClean];
     * persistence, identity, scheduling or deadline failures return [ReleaseIncidentOpenResult.Failed].
     *
     * The lock-wait handler previously submitted a retry with a synthetic identifier such as
     * `lock-wait-END_ALARM`, while `handleRecoveryRetry` verifies the identifier against a durable
     * incident. No incident existed, so the retry was silently discarded by its own identity check
     * and the device stayed restricted while `DISARMED` was published.
     *
     * Retry submission and handler semantics must agree, so this arranges a **real, serialized,
     * durable incident** first and only then submits the retry carrying its identity. It refuses to
     * submit anything it could not persist, rather than emitting a delivery that cannot be honoured.
     *
     * Returns an explicit result so callers can distinguish an obsolete fallback from a failed
     * attempt. Never enables protection and never applies a restrictive mask.
     */
    suspend fun openReleaseOnlyIncident(
        reason: String,
        inhibitionToken: RestrictionInhibitor.Token? = null,
    ): ReleaseIncidentOpenResult {
        // Register before the first suspension. If a verified cleanup wins the mutex while this
        // fallback waits, that cleanup can resolve this exact release-only lease. A later ordinary
        // failure is never registered as a release fallback and cannot be cleared by this recovery.
        inhibitionToken?.let(::registerFallbackInhibition)

        val result = withTimeoutOrNull(BOOKKEEPING_LOCK_BUDGET_MILLIS) {
            withBookkeepingLock {
                // Always decide from a fresh snapshot while holding the same mutex as successful
                // cleanup. This fences stale timeout continuations both before and after cleanup.
                val evidence = captureEvidence()
                if (evidence.isCleanNoHistory(policyController.applicationId)) {
                    inhibitionToken?.let(::clearFallbackInhibition)
                    return@withBookkeepingLock ReleaseIncidentOpenResult.ObsoleteClean
                }
                if (!evidence.incident.known) {
                    return@withBookkeepingLock ReleaseIncidentOpenResult.Failed(
                        "The durable incident state could not be read; no retry was installed.",
                    )
                }

                val existing = evidence.incident.value?.takeIf { it.unresolved }
                val incident = existing ?: RecoveryIncident(
                    incidentId = newIncidentId(),
                    openedAtEpochMillis = clockMillis(),
                    attempts = 0,
                    unresolved = true,
                )
                if (incident.attempts >= RecoveryIncident.MAX_ATTEMPTS) {
                    return@withBookkeepingLock ReleaseIncidentOpenResult.Failed(
                        "The automatic release retry limit was reached; manual recovery remains available.",
                    )
                }

                // Persist BEFORE submitting: a retry for an incident that does not exist is not a retry.
                val persisted = boundedWrite {
                    settingsRepository.writeRecoveryIncident(incident) is SettingsWriteResult.Success
                } ?: false
                if (!persisted) {
                    return@withBookkeepingLock ReleaseIncidentOpenResult.Failed(
                        "The incident could not be durably persisted; no retry was installed.",
                    )
                }
                val scheduled = try {
                    scheduleManager.installRecoveryRetry(
                        incidentId = incident.incidentId,
                        triggerAt = Instant.ofEpochMilli(clockMillis() + RecoveryIncident.RETRY_DELAY_MILLIS),
                    )
                } catch (_: Throwable) { false }
                if (scheduled) {
                    ReleaseIncidentOpenResult.Scheduled(incident.incidentId)
                } else {
                    ReleaseIncidentOpenResult.Failed(
                        "Incident ${incident.incidentId} was persisted, but its matching retry was not installed.",
                    )
                }
            }
        } ?: ReleaseIncidentOpenResult.Failed(
            "Incident bookkeeping did not finish within ${BOOKKEEPING_LOCK_BUDGET_MILLIS}ms.",
        )

        when (result) {
            is ReleaseIncidentOpenResult.Scheduled -> record(
                "RELEASE_INCIDENT_OPEN",
                "reason=$reason incident=${result.incidentId} retryScheduled=true",
            )
            ReleaseIncidentOpenResult.ObsoleteClean -> record(
                "RELEASE_INCIDENT_OPEN",
                "reason=$reason; a fresh read proved the fallback obsolete after clean recovery",
            )
            is ReleaseIncidentOpenResult.Failed -> record(
                "RELEASE_INCIDENT_OPEN",
                "reason=$reason; ${result.detail}",
            )
        }
        return result
    }

    /**
     * Handles the release-only `RECOVERY_RETRY` alarm (brief sections 10 and 14).
     *
     * Verifies the incident identity against unresolved cleanup state. In ordinary disabled state
     * with no cleanup pending this is a no-op, and it is *never* interpreted as a request to
     * enable protection.
     */
    suspend fun handleRecoveryRetry(incidentId: String?): RecoveryResult? {
        val read = boundedRead { settingsRepository.readRecoveryIncident() }
        if (!read.known) return null
        val incident = read.value
        if (incident == null || !incident.unresolved) {
            record("RECOVERY_RETRY_IGNORED", "no unresolved cleanup incident")
            return null
        }
        if (incidentId != null && incidentId != incident.incidentId) {
            record("RECOVERY_RETRY_IGNORED", "incident identity mismatch")
            return null
        }
        if (incident.attempts >= RecoveryIncident.MAX_ATTEMPTS) {
            record("RECOVERY_RETRY_IGNORED", "attempt limit reached; manual recovery route remains")
            return null
        }
        return recover("RECOVERY_RETRY")
    }

    // ------------------------------------------------------------------
    // Internals
    // ------------------------------------------------------------------

    /**
     * Requests the permissive mask and confirms it by readback, with a finite retry budget for a
     * delayed policy application. Never an infinite setter/callback loop.
     */
    private suspend fun requestAndVerifyPermissive(): Boolean {
        var attempt = 0
        while (attempt < policyReadbackMaxAttempts) {
            attempt++
            val result = policyController.applyFeatures(LockTaskMasks.ALLOWED)
            if (result is PolicyOperationResult.Applied && result.verified) return true
            if (result is PolicyOperationResult.Rejected || result is PolicyOperationResult.NotAuthorized) {
                return false
            }
            if (attempt < policyReadbackMaxAttempts) sleep(sessionReleasePollIntervalMillis)
        }
        // Final readback after the last attempt, so a slow-but-successful apply is still honoured.
        // A readback that *throws* means unknown, not success — and it must never escape recovery,
        // because recovery is the path that restores the user's controls.
        return runCatching { policyController.readLockTaskFeatures() }.getOrNull() == LockTaskMasks.ALLOWED
    }

    /** Returns the observed runtime state, or null when it could not be read (unknown). */
    private suspend fun readRuntimeStateOrNull(): Int? =
        runCatching { policyController.readRuntimeLockTaskState() }.getOrNull()

    /**
     * Polls until the runtime state is `NONE`, or the budget runs out. Returns the last observed
     * state, or null when it could not be read at all.
     */
    private suspend fun awaitSessionEnd(): Int? {
        var poll = 0
        while (poll < sessionReleaseMaxPolls) {
            val state = readRuntimeStateOrNull()
            if (state == LockTaskRuntimeStates.NONE) return state
            poll++
            if (poll < sessionReleaseMaxPolls) sleep(sessionReleasePollIntervalMillis)
        }
        return readRuntimeStateOrNull()
    }

    private data class BaselineOutcome(
        val restored: Boolean,
        val step: String,
        val detail: String,
    )

    /**
     * Restores the stored baseline and confirms it by readback (repair R03 step 4-6).
     *
     * The clean-no-history path is handled before reaching this method. Here, any unresolved
     * release needs a valid, trusted, still-PREPARED original. Invalid or retired records are
     * reported as failures and never restored or overwritten. A missing baseline is handled by
     * `recover()` as an unverified release after independent exits; it cannot become a claimed
     * original.
     */
    private suspend fun restoreBaseline(baseline: PolicyBaseline): BaselineOutcome {
        if (baseline.lifecycle == BaselineLifecycleState.RESTORED) {
            // A retired original belongs to a completed session. Reapplying it could overwrite an
            // external policy change made since cleanup; it is only a no-op proof on the clean path.
            return BaselineOutcome(false, "RESTORE_BASELINE_INVALID",
                "the retained baseline belongs to an already restored session and cannot be reused for this unresolved release")
        }
        val invalidReason = baseline.invalidReasonFor(policyController.applicationId)
        if (invalidReason != null || !baseline.isTrustedOriginal ||
            baseline.lifecycle != BaselineLifecycleState.PREPARED
        ) {
            return BaselineOutcome(
                false,
                "RESTORE_BASELINE_INVALID",
                "the stored baseline is not proven original: ${invalidReason ?: "provenance/lifecycle is ambiguous"}",
            )
        }

        val featuresResult = policyController.applyFeatures(baseline.lockTaskFeatures)
        val packagesResult = policyController.applyAllowedPackages(baseline.lockTaskPackages)
        if (!featuresResult.verified || !packagesResult.verified) {
            return BaselineOutcome(
                false,
                "RESTORE_BASELINE",
                "baseline restore was not confirmed (features=${featuresResult.failure}, packages=${packagesResult.failure})",
            )
        }

        var attempt = 0
        while (attempt < policyReadbackMaxAttempts) {
            attempt++
            val features = try { policyController.readLockTaskFeatures() } catch (_: Throwable) { null }
            val packages = try { policyController.readLockTaskPackages() } catch (_: Throwable) { null }
            if (features == baseline.lockTaskFeatures && packages == baseline.lockTaskPackages) {
                return BaselineOutcome(true, "RESTORE_BASELINE_READBACK", "baseline features and packages confirmed by readback")
            }
            if (attempt < policyReadbackMaxAttempts) sleep(sessionReleasePollIntervalMillis)
        }
        return BaselineOutcome(
            false,
            "RESTORE_BASELINE_READBACK",
            "baseline readback did not match: expected features=${baseline.lockTaskFeatures} packages=${baseline.lockTaskPackages}; " +
                "observed features=${try { policyController.readLockTaskFeatures() } catch (_: Throwable) { null }} " +
                "packages=${try { policyController.readLockTaskPackages() } catch (_: Throwable) { null }}",
        )
    }

    private suspend fun fail(
        step: String,
        detail: String,
        reason: String,
        durableDisableWritten: Boolean,
        releaseAttempted: Boolean,
        sessionReleased: Boolean,
        baselineRestored: Boolean,
        durableCleanupCommitted: Boolean,
        recoveryInhibition: RestrictionInhibitor.Token,
    ): RecoveryResult {
        val result = withTimeoutOrNull(BOOKKEEPING_LOCK_BUDGET_MILLIS) {
            withBookkeepingLock {
                val evidence = captureEvidence()
                val retiredBaseline = evidence.baselineRead.value
                val supersededByVerifiedCleanup = retiredBaseline?.lifecycle == BaselineLifecycleState.RESTORED &&
                    retiredBaseline.isTrustedOriginal &&
                    retiredBaseline.invalidReasonFor(policyController.applicationId) == null &&
                    evidence.isCleanNoHistory(policyController.applicationId)
                if (supersededByVerifiedCleanup) {
                    // A newer successful recovery can finish while this older attempt is suspended.
                    // Its durable RESTORED lifecycle is authoritative; do not resurrect an incident
                    // from this attempt's stale failure snapshot.
                    clearResolvedFallbackInhibitions()
                    inhibitor.clearIfCurrent(recoveryInhibition)
                    val baselineStillMatches = retiredBaseline != null &&
                        evidence.features == retiredBaseline.lockTaskFeatures &&
                        evidence.packages == retiredBaseline.lockTaskPackages
                    return@withBookkeepingLock RecoveryResult(
                        verified = true,
                        failedStep = null,
                        detail = "A newer verified cleanup completed while this release attempt was pending; " +
                            "the current policy was preserved.",
                        durableDisableWritten = durableDisableWritten,
                        attemptsRecorded = 0,
                        retryScheduled = false,
                        sessionReleased = evidence.runtime == LockTaskRuntimeStates.NONE,
                        baselineRestored = baselineStillMatches,
                        durableCleanupCommitted = true,
                    )
                }

                val existingRead = evidence.incident
                val existing = existingRead.value
                val incident = existing?.takeIf { it.unresolved }
                    ?: RecoveryIncident(
                        incidentId = newIncidentId(),
                        openedAtEpochMillis = clockMillis(),
                        attempts = 0,
                        unresolved = true,
                    )
                val updated = incident.withAttempt()
                // The attempt count must be durable before installing its retry. Using an in-memory
                // increment when this write failed caused every redelivery to retry as attempt one.
                val persisted = if (existingRead.known) {
                    boundedWrite {
                        settingsRepository.writeRecoveryIncident(updated) is SettingsWriteResult.Success
                    } ?: false
                } else {
                    false
                }

                // One separate inexact, release-only retry, at least 20 minutes later, limited to
                // three automatic attempts (brief section 10). It can never arm or restrict.
                var retryScheduled = false
                if (persisted && releaseAttempted && updated.attempts < RecoveryIncident.MAX_ATTEMPTS) {
                    retryScheduled = try {
                        scheduleManager.installRecoveryRetry(
                            incidentId = updated.incidentId,
                            triggerAt = Instant.ofEpochMilli(clockMillis() + RecoveryIncident.RETRY_DELAY_MILLIS),
                        )
                    } catch (_: Throwable) { false }
                }

                RecoveryResult(
                    verified = false,
                    failedStep = step,
                    detail = detail,
                    durableDisableWritten = durableDisableWritten,
                    attemptsRecorded = if (persisted) updated.attempts else existing?.attempts ?: 0,
                    retryScheduled = retryScheduled,
                    sessionReleased = sessionReleased,
                    baselineRestored = baselineRestored,
                    durableCleanupCommitted = durableCleanupCommitted,
                )
            }
        }

        val finalResult = result ?: RecoveryResult(
            verified = false,
            failedStep = step,
            detail = "$detail Recovery incident bookkeeping did not finish within ${BOOKKEEPING_LOCK_BUDGET_MILLIS}ms.",
            durableDisableWritten = durableDisableWritten,
            attemptsRecorded = 0,
            retryScheduled = false,
            sessionReleased = sessionReleased,
            baselineRestored = baselineRestored,
            durableCleanupCommitted = durableCleanupCommitted,
        )

        // Optional diagnostics LAST: a failed log must never delay or determine the release above.
        if (finalResult.verified) {
            record("RECOVERY_SUPERSEDED", "reason=$reason current lifecycle proves a newer cleanup completed")
        } else {
            record(
                "RECOVERY_INCOMPLETE",
                "step=$step attempts=${finalResult.attemptsRecorded} retryScheduled=${finalResult.retryScheduled} reason=$reason detail=${finalResult.detail}",
            )
        }
        return finalResult
    }

    private suspend fun record(kind: String, message: String) {
        // Optional diagnostics. Cancellation is rethrown rather than swallowed by a broad catch, so
        // a cancelled scope stays cancelled instead of being converted into a silent success
        // (repair R08 step 7).
        withTimeoutOrNull(DIAGNOSTIC_BUDGET_MILLIS) {
            try {
                diagnostics.record(kind, revision = -1L, message = message)
            } catch (cancellation: kotlinx.coroutines.CancellationException) {
                throw cancellation
            } catch (_: Throwable) {
                // Logging failure must never block release (brief sections 12.13 and 20).
            }
        }
    }

    companion object {
        /** A pre-release evidence read must not consume the control-release opportunity. */
        const val STORE_READ_BUDGET_MILLIS: Long = 45L
        /** Each post-release bookkeeping call has a finite independent cap. */
        const val STORE_WRITE_BUDGET_MILLIS: Long = 140L
        /** Optional logging must not turn a clean no-op into a policy mutation timeout. */
        const val DIAGNOSTIC_BUDGET_MILLIS: Long = 25L
        /** Incident lock wait plus its bounded read/write/install bookkeeping. */
        const val BOOKKEEPING_LOCK_BUDGET_MILLIS: Long = 450L
        private val CLEAN_NONRESTRICTIVE_MASKS = setOf(0, 16, LockTaskMasks.ALLOWED)
    }
}
