package com.example.shutdownprotection.protection

import com.example.shutdownprotection.BuildConfig
import com.example.shutdownprotection.admin.DevicePolicyController
import com.example.shutdownprotection.admin.LockTaskMasks
import com.example.shutdownprotection.admin.LockTaskRuntimeStates
import com.example.shutdownprotection.admin.PolicyOperationResult
import android.app.admin.PolicyUpdateResult
import com.example.shutdownprotection.data.DiagnosticsRepository
import com.example.shutdownprotection.data.ProtectionSettings
import com.example.shutdownprotection.data.RecoveryIncident
import com.example.shutdownprotection.data.RuntimeObservation
import com.example.shutdownprotection.data.SettingsReadResult
import com.example.shutdownprotection.data.SettingsRepository
import com.example.shutdownprotection.data.SettingsSchema
import com.example.shutdownprotection.data.SessionPreparationResult
import com.example.shutdownprotection.data.SettingsValidation
import com.example.shutdownprotection.data.SettingsWriteResult
import com.example.shutdownprotection.data.TemporaryTestMarker
import com.example.shutdownprotection.scheduling.ScheduleCalculator
import com.example.shutdownprotection.scheduling.SchedulingGateway
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.withContext
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.time.Instant

/**
 * The one reconciliation entry point (brief section 12).
 *
 * Every path - both alarm actions, startup, schedule edits, resume, policy callbacks, boot,
 * time changes, and time-zone changes - goes through [reconcile]. There are deliberately no
 * separate start/end policy implementations that could disagree.
 *
 * Serialization is a single process-wide [Mutex]. The critical section is kept short, and
 * nothing waits indefinitely for a policy callback that would itself need the lock.
 */
class ProtectionCoordinator(
    private val applicationId: String,
    private val settingsRepository: SettingsRepository,
    private val diagnostics: DiagnosticsRepository,
    private val policyController: DevicePolicyController,
    private val scheduleCalculator: ScheduleCalculator,
    private val scheduleManager: SchedulingGateway,
    private val recoveryManager: RecoveryManager,
    private val lockTaskSession: LockTaskSessionController,
    private val environment: EnvironmentStateProvider,
    private val inhibitor: RestrictionInhibitor,
    private val clockMillis: () -> Long = { System.currentTimeMillis() },
    private val operationTimeoutMillis: Long = DEFAULT_OPERATION_TIMEOUT_MILLIS,
    private val armTimeoutMillis: Long = DEFAULT_ARM_TIMEOUT_MILLIS,
    private val sleep: suspend (Long) -> Unit = { delay(it) },
) {

    private val mutex = Mutex()
    private val mutableStatus = MutableStateFlow<ProtectionStatus?>(null)

    /**
     * Gate B manual override. Process-local on purpose: an override must never survive an
     * app restart, update, or boot (brief section 22).
     */
    @Volatile
    private var pocOverride: PocOverride = PocOverride.NONE

    @Volatile
    private var pocActivatedInThisProcess: Boolean = false

    /**
     * The revision whose temporary safeguard has already been submitted. Prevents re-submitting
     * (and thereby extending) the timer on every "Restrict power menu" press (repair R06 step 10).
     */
    @Volatile
    private var pocSafeguardRevision: Long? = null

    /**
     * The last policy submission made for a given settings revision and mask.
     *
     * This is what breaks the submit/callback feedback loop the brief forbids: if the same
     * revision and mask have already been submitted, the coordinator reads back instead of
     * re-submitting, and a readback that still disagrees becomes a bounded recovery rather than
     * an endless retry.
     */
    private var lastSubmission: PolicySubmission? = null

    /**
     * Whether this process has successfully submitted the release plan at least once.
     *
     * A stored receipt records what a *previous* run submitted, and PendingIntent-token existence is
     * not an AlarmManager inventory query (repair R07). After a process restart the app therefore
     * cannot trust its own prior submission, so the first reconcile of each process deliberately
     * resubmits the plan once. Later reconciles in the same process do not resubmit unless
     * something actually changed, so this cannot become a foreground submission loop.
     */
    @Volatile
    private var planSubmittedInThisProcess: Boolean = false

    /** The most recent policy callback result, for the diagnostics screen (brief section 18). */
    private val lastPolicyResult = MutableStateFlow<PolicyResultObservation?>(null)

    /** Last observed status, for the UI. Never authority for a decision. */
    val status: StateFlow<ProtectionStatus?> = mutableStatus.asStateFlow()

    /** One deadline covers queueing, work, release fallback, incident persistence and final status. */
    private suspend fun serializedStatus(
        trigger: ProtectionTrigger,
        operationBudgetMillis: Long = operationTimeoutMillis,
        block: suspend () -> ProtectionStatus,
    ): ProtectionStatus {
        val totalBudget = LOCK_WAIT_BUDGET_MILLIS + operationBudgetMillis +
            TIMEOUT_CLEANUP_BUDGET_MILLIS + TIMEOUT_BOOKKEEPING_BUDGET_MILLIS +
            TIMEOUT_STATUS_BUDGET_MILLIS
        val result = withTimeoutOrNull(totalBudget) {
            val acquired = withTimeoutOrNull(LOCK_WAIT_BUDGET_MILLIS) {
                mutex.lock()
                true
            } ?: false
            if (!acquired) return@withTimeoutOrNull lockWaitTimedOut(trigger)
            try {
                try {
                    withTimeoutOrNull(operationBudgetMillis) { block() } ?: timedOutLocked(trigger)
                } catch (cancellation: CancellationException) {
                    cancellationFallback(trigger)
                    throw cancellation
                } catch (failure: Throwable) {
                    exceptionFallbackLocked(trigger, failure)
                }
            } finally {
                mutex.unlock()
            }
        }
        if (result != null) return result
        inhibitor.inhibit("Coordinator total deadline exceeded (${trigger.wireName})")
        return emergencyStatus(
            trigger = trigger,
            detail = "The bounded operation exceeded its end-to-end deadline; cleanup remains unverified.",
            step = "TOTAL_DEADLINE",
            publish = false,
        )
    }

    private suspend fun serializedUnit(
        trigger: ProtectionTrigger,
        block: suspend () -> Unit,
    ) {
        val totalBudget = LOCK_WAIT_BUDGET_MILLIS + operationTimeoutMillis +
            TIMEOUT_CLEANUP_BUDGET_MILLIS + TIMEOUT_BOOKKEEPING_BUDGET_MILLIS +
            TIMEOUT_STATUS_BUDGET_MILLIS
        withTimeoutOrNull(totalBudget) {
            val acquired = withTimeoutOrNull(LOCK_WAIT_BUDGET_MILLIS) {
                mutex.lock()
                true
            } ?: false
            if (!acquired) {
                lockWaitTimedOut(trigger)
                return@withTimeoutOrNull
            }
            try {
                try {
                    if (withTimeoutOrNull(operationTimeoutMillis) { block(); true } != true) {
                        timedOutLocked(trigger)
                    }
                } catch (cancellation: CancellationException) {
                    cancellationFallback(trigger)
                    throw cancellation
                } catch (failure: Throwable) {
                    exceptionFallbackLocked(trigger, failure)
                }
            } finally {
                mutex.unlock()
            }
        }
    }

    private suspend fun cancellationFallback(trigger: ProtectionTrigger) {
        val inhibition = inhibitor.inhibit("Coordinator operation was cancelled (${trigger.wireName})")
        lastSubmission = null
        withContext(NonCancellable) {
            withTimeoutOrNull(TIMEOUT_CLEANUP_BUDGET_MILLIS) { boundedReleaseAttempt() }
            withTimeoutOrNull(TIMEOUT_BOOKKEEPING_BUDGET_MILLIS) {
                recoveryManager.openReleaseOnlyIncident(
                    reason = "coordinator operation cancelled (${trigger.wireName})",
                    inhibitionToken = inhibition,
                )
            }
        }
    }

    private suspend fun exceptionFallbackLocked(
        trigger: ProtectionTrigger,
        failure: Throwable,
    ): ProtectionStatus {
        inhibitor.inhibit("Coordinator encountered unreadable or failed state (${trigger.wireName})")
        lastSubmission = null
        val recovery = try {
            withTimeoutOrNull(TIMEOUT_CLEANUP_BUDGET_MILLIS + TIMEOUT_BOOKKEEPING_BUDGET_MILLIS) {
                recoveryManager.recover("COORDINATOR_READ_OR_OPERATION_FAILURE")
            }
        } catch (cancellation: CancellationException) {
            cancellationFallback(trigger)
            throw cancellation
        } catch (_: Throwable) {
            null
        }
        val detail = "Coordinator state could not be read or the operation failed (${failure.javaClass.simpleName}); " +
            "release was attempted and remains unverified${recovery?.failedStep?.let { " at $it" } ?: ""}."
        return try {
            withTimeoutOrNull(TIMEOUT_STATUS_BUDGET_MILLIS) {
                statusFromObservation(
                    state = ProtectionState.RECOVERY_FAILED,
                    detail = detail,
                    recoveryIncompleteStep = "COORDINATOR_READ_OR_OPERATION_FAILURE",
                    userActionRequired = MANUAL_RECOVERY_ACTION,
                )
            } ?: emergencyStatus(trigger, detail, "COORDINATOR_READ_OR_OPERATION_FAILURE")
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Throwable) {
            emergencyStatus(trigger, detail, "COORDINATOR_READ_OR_OPERATION_FAILURE")
        }
    }

    private fun emergencyStatus(
        trigger: ProtectionTrigger,
        detail: String,
        step: String,
        publish: Boolean = true,
    ): ProtectionStatus {
        val prior = mutableStatus.value
        val result = ProtectionStatus(
            state = ProtectionState.RECOVERY_FAILED,
            detail = detail,
            settingsRevision = prior?.settingsRevision ?: -1L,
            observedAtEpochMillis = clockMillis(),
            deviceOwner = null,
            ownerEverEstablished = prior?.ownerEverEstablished ?: false,
            lockTaskState = null,
            requestedFeatures = null,
            effectiveFeatures = null,
            effectivePackages = null,
            insideProtectedInterval = null,
            exactAlarmCapability = null,
            releasePlanSubmittedForCurrentRevision = false,
            recoveryIncompleteStep = step,
            userActionRequired = MANUAL_RECOVERY_ACTION,
        )
        if (publish) mutableStatus.value = result
        return result
    }

    // ------------------------------------------------------------------
    // Public entry points
    // ------------------------------------------------------------------

    /**
     * The timeout is applied **inside** the lock, so the fallback status is published while the
     * critical section is still held. Publishing it outside would let a slow, timed-out pass
     * overwrite a newer correct status written by the next pass.
     */
    suspend fun reconcile(trigger: ProtectionTrigger): ProtectionStatus =
        serializedStatus(trigger) { reconcileLocked(trigger) }

    /**
     * The crash-safe arm transaction (brief section 9).
     *
     * Deliberately longer than a plain reconcile: it includes lock acquisition, policy
     * application, alarm installation, and the bounded wait for the runtime session to
     * report LOCKED. It never waits for user interaction.
     */
    suspend fun arm(): ProtectionStatus =
        serializedStatus(ProtectionTrigger.USER_ARM_REQUEST, armTimeoutMillis) { armLocked() }

    /** Disable and restore normal device mode. Recovery is the single idempotent path. */
    suspend fun disableAndRestore(): ProtectionStatus = serializedStatus(
        ProtectionTrigger.USER_DISABLE,
        armTimeoutMillis,
    ) {
        pocOverride = PocOverride.NONE
        pocActivatedInThisProcess = false
        pocSafeguardRevision = null
        val recovery = recoveryManager.recover("USER_DISABLE")
        if (recovery.verified) {
            statusFromObservation(
                ProtectionState.DISARMED,
                if (recovery.baselineRestored) "Normal device mode restored"
                else recovery.detail,
            )
        } else {
            statusFromObservation(
                state = ProtectionState.RECOVERY_FAILED,
                detail = "Recovery incomplete at step ${recovery.failedStep}: ${recovery.detail}",
                recoveryIncompleteStep = recovery.failedStep,
                userActionRequired = MANUAL_RECOVERY_ACTION,
            )
        }
    }

    /**
     * Refreshes observations without changing policy and without ever re-arming.
     *
     * Used by `onLockTaskModeExiting`, where recursively re-arming would be wrong
     * (brief section 9).
     */
    suspend fun refreshObservation(): ProtectionStatus =
        serializedStatus(ProtectionTrigger.LOCK_TASK_CHANGED) { refreshObservationLocked() }

    /** Handles the release-only RECOVERY_RETRY alarm. Never enables protection. */
    suspend fun handleRecoveryRetry(incidentId: String?): ProtectionStatus = serializedStatus(
        ProtectionTrigger.RECOVERY_RETRY_ALARM,
    ) {
        // A plain reconcile deadline, not the arm deadline: this path submits no alarms and
        // waits for no session, so it belongs to the same five-second budget as reconcile.
            val recovery = recoveryManager.handleRecoveryRetry(incidentId)
            if (recovery == null) {
                // Second review U03: an ignored retry is NOT evidence that nothing is restricted.
                // Publishing DISARMED here previously hid a still-locked session whose bookkeeping
                // had simply not matched.
                if (appImposedPolicyMayBePresent() || inhibitor.isInhibited) {
                    statusFromObservation(
                        state = ProtectionState.RECOVERY_FAILED,
                        detail = "The release-only retry was not applied (no matching unresolved " +
                            "incident), but an app-imposed policy or unresolved cleanup remains. " +
                            "This is not a success.",
                        recoveryIncompleteStep = "RETRY_NOT_APPLIED",
                        userActionRequired = MANUAL_RECOVERY_ACTION,
                    )
                } else {
                    statusFromObservation(
                        state = ProtectionState.DISARMED,
                        detail = "No unresolved cleanup incident and no app-imposed policy; the " +
                            "release-only retry was ignored.",
                    )
                }
            } else if (recovery.verified) {
                statusFromObservation(ProtectionState.DISARMED, "Cleanup verified by release-only retry")
            } else {
                statusFromObservation(
                    state = ProtectionState.RECOVERY_FAILED,
                    detail = "Recovery incomplete at step ${recovery.failedStep}",
                    recoveryIncompleteStep = recovery.failedStep,
                    userActionRequired = MANUAL_RECOVERY_ACTION,
                )
            }
    }

    /**
     * Schedule edit while possibly armed (brief section 9, "Schedule edits during an armed
     * session"). Validates first, requests a permissive mask before replacing a protected
     * plan, persists atomically, installs the replacement, and reconciles immediately.
     */
    suspend fun editSchedule(startMinuteOfDay: Int, endMinuteOfDay: Int): ProtectionStatus =
        serializedStatus(ProtectionTrigger.USER_EDIT, armTimeoutMillis) {
            editScheduleLocked(startMinuteOfDay, endMinuteOfDay)
        }

    /**
     * Allowlist changes fully disarm first and require explicit foreground resume
     * (brief section 9 / 11.11), so tasks are never silently changed beneath an active
     * session.
     */
    suspend fun editAllowedPackages(packages: Set<String>): ProtectionStatus =
        serializedStatus(ProtectionTrigger.USER_EDIT, armTimeoutMillis) { editAllowedPackagesLocked(packages) }

    /**
     * Debug-only Gate B release (brief section 22): ends the temporary test and releases
     * through `RecoveryManager`. It can never enable protection.
     *
     * This is deliberately **not** a reconcile: under a forced-restriction override the
     * reconciler would resolve the same instant back to the restrictive mask, which is exactly
     * why routing the temporary timer through the daily END alarm was wrong.
     *
     * **Fenced to the temporary test (repair R06 step 12).** A stale temporary release event
     * arriving after the test has ended must not silently tear down an unrelated, independently
     * started session. When no temporary test is active this is a no-op that reports the current
     * state rather than recovering.
     */
    suspend fun releaseTemporaryTest(): ProtectionStatus =
        serializedStatus(ProtectionTrigger.RELEASE_FALLBACK, armTimeoutMillis) {
            releaseTemporaryTestLocked(
                deliveredReleaseAtEpochMillis = null,
                deliveredSessionId = null,
                alarmDelivery = false,
            )
        }

    /** An alarm delivery must prove both parts of the temporary-session identity. */
    suspend fun releaseTemporaryTest(
        deliveredReleaseAtEpochMillis: Long?,
        deliveredSessionId: String?,
    ): ProtectionStatus = serializedStatus(ProtectionTrigger.RELEASE_FALLBACK, armTimeoutMillis) {
            releaseTemporaryTestLocked(deliveredReleaseAtEpochMillis, deliveredSessionId, alarmDelivery = true)
        }

    /** Legacy boundary-only deliveries remain alarm deliveries and cannot match a modern marker. */
    suspend fun releaseTemporaryTest(deliveredReleaseAtEpochMillis: Long): ProtectionStatus =
        releaseTemporaryTest(deliveredReleaseAtEpochMillis, deliveredSessionId = null)

    private suspend fun releaseTemporaryTestLocked(
        deliveredReleaseAtEpochMillis: Long?,
        deliveredSessionId: String?,
        alarmDelivery: Boolean,
    ): ProtectionStatus {
        var markerReadFailed = false
        val marker = try {
            settingsRepository.readTemporaryTestMarker()
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Throwable) {
            markerReadFailed = true
            null
        }
        if (markerReadFailed) {
            val recovery = recoveryManager.recover("TEMPORARY_MARKER_UNREADABLE")
            return statusFromObservation(
                state = if (recovery.verified) ProtectionState.DISARMED else ProtectionState.RECOVERY_FAILED,
                detail = "The temporary-test marker could not be read; release was attempted and " +
                    if (recovery.verified) "verified." else "remains unverified.",
                recoveryIncompleteStep = recovery.failedStep,
                userActionRequired = if (recovery.verified) null else MANUAL_RECOVERY_ACTION,
            )
        }
        val overrideActive = pocOverride != PocOverride.NONE

        // Incident binding (review-2 section 4.2). A wall-clock boundary can collide after a clock
        // correction, so the delivery must match both the planned boundary and durable unique
        // session ID. A delayed delivery from test A cannot tear down test B even when timestamps
        // are equal.
        val deliveryMatchesCurrent = marker?.let { current ->
            deliveredReleaseAtEpochMillis == current.releaseAtEpochMillis &&
                deliveredSessionId == current.sessionId
        } == true
        if (marker != null && alarmDelivery && !deliveryMatchesCurrent) {
            val runtimeState = readRuntimeStateOrNull()
            val effectiveFeatures = runCatching { policyController.readLockTaskFeatures() }.getOrNull()
            return statusFromObservation(
                state = observedSessionState(
                    runtimeState = runtimeState,
                    effectiveFeatures = effectiveFeatures,
                    protectedExpected = pocOverride == PocOverride.FORCE_RESTRICTED,
                ),
                detail = "A stale temporary release event was ignored: it belongs to an earlier " +
                    "test (deliveredAt=$deliveredReleaseAtEpochMillis, currentAt=" +
                    "${marker.releaseAtEpochMillis}, deliveredId=${deliveredSessionId ?: "legacy/unknown"}, " +
                    "currentId=${marker.sessionId ?: "legacy/unknown"}). The active test was left untouched.",
            )
        }

        if (marker == null && !overrideActive) {
            // A MISSING marker is not proof that nothing is restricted (verify3-e). Two routes make
            // this branch reachable over a genuinely restricted device:
            //   (a) `readTemporaryTestMarker()` swallows a read failure, so an unreadable store looks
            //       exactly like "no test";
            //   (b) `recover()` clears the marker at step 3 and cancels the alarm identities BEFORE
            //       release is verified, so a failure part-way through leaves a possibly-LOCKED
            //       device with no marker and a bare DISARMED answer for any later delivery.
            // So this only reports DISARMED when an app-imposed policy is provably absent.
            val runtimeState = readRuntimeStateOrNull()
            val runtimeNeedsRelease = runtimeState == null || runtimeState != LockTaskRuntimeStates.NONE
            val preparationMayBePending = try {
                settingsRepository.readBaseline()?.lifecycle?.let {
                    it != com.example.shutdownprotection.data.BaselineLifecycleState.RESTORED
                } == true
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: Throwable) {
                true
            }
            if (runtimeNeedsRelease || preparationMayBePending ||
                appImposedPolicyMayBePresent() || inhibitor.isInhibited
            ) {
                val recovery = recoveryManager.recover("STALE_TEMPORARY_RELEASE_WITH_POLICY")
                return statusFromObservation(
                    state = if (recovery.verified) {
                        ProtectionState.DISARMED
                    } else {
                        ProtectionState.RECOVERY_FAILED
                    },
                    detail = if (recovery.verified) {
                        if (recovery.baselineRestored) {
                            "No temporary test marker is present; the retained original policy was restored and confirmed."
                        } else {
                            recovery.detail
                        }
                    } else {
                        "No temporary test marker is present, but an app-imposed policy or unresolved " +
                            "cleanup remains and release is INCOMPLETE at step ${recovery.failedStep}: " +
                            "${recovery.detail}. The device may still be restricted."
                    },
                    recoveryIncompleteStep = recovery.failedStep,
                    userActionRequired = if (recovery.verified) null else MANUAL_RECOVERY_ACTION,
                )
            }
            // Genuinely nothing of ours is in force: a stale event for a test that is already over.
            return statusFromObservation(
                state = ProtectionState.DISARMED,
                detail = "No temporary debug test is active and no app-imposed policy is present; " +
                    "a stale temporary release event was ignored.",
            )
        }

        // Alarm deliveries were identity-checked above. The zero-argument overload is an explicit
        // foreground release action and needs no alarm identity; keep that path free of optional
        // pre-release logging so diagnostics cannot consume its recovery deadline.
        pocOverride = PocOverride.NONE
        pocActivatedInThisProcess = false
        pocSafeguardRevision = null
        val recovery = recoveryManager.recover("TEMPORARY_TEST_RELEASE")
        return if (recovery.verified) {
            statusFromObservation(
                state = ProtectionState.DISARMED,
                detail = "Temporary debug test released; normal device mode restored",
            )
        } else {
            statusFromObservation(
                state = ProtectionState.RECOVERY_FAILED,
                detail = "Temporary debug test release incomplete at step ${recovery.failedStep}",
                recoveryIncompleteStep = recovery.failedStep,
                userActionRequired = MANUAL_RECOVERY_ACTION,
            )
        }
    }

    /**
     * Records a policy callback result so diagnostics can report the actual code and the time it
     * was observed.
     *
     * **Correlation honesty (repair R09 step 5).** The platform gives no way to tie a callback to
     * the request that caused it, so the revision recorded here is the *current intent revision at
     * observation time*. It is deliberately named and reported as that, never as the originating
     * request revision.
     */
    suspend fun recordPolicyResult(identifier: String, resultCode: Int?) {
        serializedUnit(ProtectionTrigger.POLICY_CHANGED) {
            recordPolicyResultLocked(identifier, resultCode)
        }
    }

    private suspend fun recordPolicyResultLocked(identifier: String, resultCode: Int?) {
        val revision = (settingsRepository.readSettings() as? SettingsReadResult.Success)
            ?.settings?.revision ?: -1L
        lastPolicyResult.value = PolicyResultObservation(
            identifier = identifier,
            resultCode = resultCode,
            observedAtEpochMillis = clockMillis(),
            settingsRevision = revision,
        )
    }

    /**
     * Explicit failure handler for a **relevant** policy callback (repair R09 steps 3-6).
     *
     * Generic reconciliation is not a substitute for this: a failure callback must invalidate the
     * success claim and enter a serialized failure/recovery path **even when the effective policy
     * still happens to match a previous request**. Relying on readback alone let an asynchronous
     * failure be hidden behind an observation that looked fine.
     *
     * Conservative by design: if the failure cannot be correlated to a specific request, an
     * unnecessary disarm is preferred to pretending an unresolved restrictive request succeeded.
     * The latest durable intent is read first, so a callback arriving after a disable can never
     * re-enable or re-enter protection.
     */
    suspend fun handlePolicyFailure(identifier: String, resultCode: Int?): ProtectionStatus =
        serializedStatus(ProtectionTrigger.POLICY_CHANGED) {
            handlePolicyFailureLocked(identifier, resultCode)
        }

    private suspend fun handlePolicyFailureLocked(identifier: String, resultCode: Int?): ProtectionStatus {
                recordPolicyResultLocked(identifier, resultCode)
                val stored = (settingsRepository.readSettings() as? SettingsReadResult.Success)?.settings
                // A live temporary test deliberately keeps `enabled` false while holding a real
                // locked session and a restrictive mask, so `enabled` alone is not evidence that
                // nothing is restricted (independent audit finding). Without this, a relevant
                // policy failure during a live test was ignored and DISARMED was published over a
                // genuinely restricted device.
                val liveTemporaryTest = pocActivatedInThisProcess &&
                    settingsRepository.readTemporaryTestMarker() != null
                // `enabled` is NOT sufficient evidence that nothing is restricted (independent
                // audit findings, both rounds). Two real states have `enabled=false` while the
                // restrictive policy may still be applied:
                //   - a live temporary test, which keeps the daily preference false by design;
                //   - a disable whose release could not be confirmed, which leaves the recovery
                //     marker set and the mask possibly still in force.
                // In either case reporting DISARMED would be a false success claim over a
                // restricted device, so cleanup is attempted instead.
                val cleanupOwed = stored?.recoveryRequired == true
                val appPolicyPresent = appImposedPolicyMayBePresent()
                // CORRECTED AFTER ADVERSARIAL REVIEW (verify3-a P2): `stored == null` used to
                // short-circuit into this branch, so an UNREADABLE settings store published
                // "DISARMED — nothing was changed" without attempting any release, while
                // `reconcileLocked` recovers on the same Corrupt condition. An unreadable store is
                // exactly when a live restriction is most likely to be missed, so it must take the
                // recovery path, not the clean one.
                return if (stored != null &&
                    !stored.enabled && !liveTemporaryTest && !cleanupOwed && !appPolicyPresent
                ) {
                    // Nothing is restricted and no cleanup is owed: there is no success claim to
                    // invalidate, and re-enabling would be exactly the failure mode this handler
                    // exists to prevent.
                    statusFromObservation(
                        ProtectionState.DISARMED,
                        "A policy failure callback arrived while protection was disabled and no " +
                            "app-imposed policy is present; nothing was changed.",
                    )
                } else {
                    lastSubmission = null
                    val recovery = recoveryManager.recover("POLICY_CALLBACK_FAILURE")
                    statusFromObservation(
                        state = if (recovery.verified) {
                            ProtectionState.DISARMED
                        } else {
                            ProtectionState.RECOVERY_FAILED
                        },
                        detail = "A relevant policy change reported failure (code=$resultCode); the " +
                            "success claim was invalidated and recovery was attempted.",
                        recoveryIncompleteStep = recovery.failedStep,
                        userActionRequired = if (recovery.verified) null else MANUAL_RECOVERY_ACTION,
                    )
                }
    }

    /**
     * A relevant policy changed externally: reconcile current intent against effective state.
     *
     * **Both callbacks classify their result codes** (second review U06). The changed-policy
     * callback previously forwarded every relevant result to generic reconciliation, so an explicit
     * failure could be hidden behind a readback that happened to match a prior request and preserved
     * an armed success state. An explicit failure now takes the same serialized failure/recovery path
     * as the set-result callback.
     *
     * Success, cleared, unrelated identifiers and uncorrelatable results stay distinct: this method
     * only sees relevant identifiers, and only explicit failures are diverted.
     */
    suspend fun handlePolicyChanged(identifier: String, resultCode: Int?): ProtectionStatus =
        serializedStatus(ProtectionTrigger.POLICY_CHANGED) {
            if (isExplicitPolicyFailure(resultCode)) {
                handlePolicyFailureLocked(identifier, resultCode)
            } else {
                recordPolicyResultLocked(identifier, resultCode)
                reconcileLocked(ProtectionTrigger.POLICY_CHANGED)
            }
        }

    /**
     * Whether a policy result code is an explicit failure. Anything that is neither "set" nor
     * "cleared" is treated conservatively as a failure, including the documented conflict,
     * storage-limit, hardware-limitation and unknown codes.
     */
    private fun isExplicitPolicyFailure(resultCode: Int?): Boolean = when (resultCode) {
        null -> true
        PolicyUpdateResult.RESULT_POLICY_SET -> false
        PolicyUpdateResult.RESULT_POLICY_CLEARED -> false
        else -> true
    }

    /** Marks a new boot generation. Called once per boot, before any reconcile of that boot. */
    suspend fun onBootStarted(): Long {
        val result = withTimeoutOrNull(BOOT_OPERATION_BUDGET_MILLIS) {
            val acquired = withTimeoutOrNull(LOCK_WAIT_BUDGET_MILLIS) {
                mutex.lock()
                true
            } ?: false
            if (!acquired) return@withTimeoutOrNull null
            try {
                withTimeoutOrNull(BOOT_STORE_BUDGET_MILLIS) {
                    pocOverride = PocOverride.NONE
                    pocActivatedInThisProcess = false
                    pocSafeguardRevision = null
                    lastSubmission = null
                    settingsRepository.bumpBootGeneration()
                }
            } finally {
                mutex.unlock()
            }
        }
        return result ?: -1L // Unknown generation; the following boot reconciliation repairs it.
    }

    // ------------------------------------------------------------------
    // Gate B proof-of-concept session (brief section 22)
    // ------------------------------------------------------------------

    /**
     * Starts the explicit temporary debug session (repaired per guide R06).
     *
     * This is a **separate session mode**, not the daily arming path. It previously called
     * `armLocked()`, which commits the daily `enabled=true` preference — so starting a temporary
     * test silently activated the recurring daily schedule, while the POC screen claimed in its own
     * text that the preference was unchanged. It also ignored a failed marker write, so a crash
     * could lose the temporary context entirely.
     *
     * The repaired design:
     *  - debug-only, with an implementation guard as well as a hidden button;
     *  - refused unless the daily preference is disabled and recovery is resolved;
     *  - the durable marker is written **first**, checked, and read back;
     *  - the original baseline is captured and its write checked;
     *  - the exact temporary release and its distinct inexact fallback are submitted **before**
     *    entry, with a fixed expiry that repeated toggles cannot extend;
     *  - daily `enabled` stays **false** throughout — the temporary intent lives only in the marker.
     */
    suspend fun activatePocSession(): ProtectionStatus =
        serializedStatus(ProtectionTrigger.POC_START_SESSION, armTimeoutMillis) { activatePocSessionLocked() }

    private suspend fun activatePocSessionLocked(): ProtectionStatus {
        // 1. Implementation guard, not merely a hidden button (R06 step 2).
        if (!BuildConfig.DEBUG) {
            return statusFromObservation(
                ProtectionState.CONFIGURATION_ERROR,
                "Temporary testing is available only in debug builds.",
            )
        }

        // A repeated foreground press for the same live temporary session is an idempotent
        // observation, not a new entry attempt. First honor every release-only gate: a prior
        // failed recovery can leave this process-local marker alive, and a repeated Start must not
        // replace that failure with an armed-success observation.
        if (temporaryTestIsActive()) {
            val storedRead = settingsRepository.readSettings()
            val stored = (storedRead as? SettingsReadResult.Success)?.settings
            val incidentPending = settingsRepository.readRecoveryIncident()?.unresolved == true
            val corruptionPending = settingsRepository.isCorruptionFlagged()
            val recoveryPending = stored == null || stored.enabled || stored.recoveryRequired ||
                incidentPending || corruptionPending || inhibitor.isInhibited
            if (recoveryPending) {
                inhibitor.inhibit("Repeated temporary Start found unresolved recovery state")
                pocOverride = PocOverride.NONE
                pocActivatedInThisProcess = false
                pocSafeguardRevision = null
                lastSubmission = null
                val recovery = recoveryManager.recover("POC_REENTRY_RECOVERY_REQUIRED")
                return statusFromObservation(
                    state = if (recovery.verified) ProtectionState.DISARMED else ProtectionState.RECOVERY_FAILED,
                    detail = if (recovery.verified) {
                        "Repeated Start found unresolved cleanup; the active test was not extended and release was verified."
                    } else {
                        "Repeated Start found unresolved cleanup; the active test was not extended and release remains unverified at ${recovery.failedStep}."
                    },
                    recoveryIncompleteStep = recovery.failedStep,
                    userActionRequired = if (recovery.verified) null else MANUAL_RECOVERY_ACTION,
                )
            }
            val runtimeState = readRuntimeStateOrNull()
            val effectiveFeatures = try { policyController.readLockTaskFeatures() } catch (_: Throwable) { null }
            return statusFromObservation(
                state = observedSessionState(
                    runtimeState = runtimeState,
                    effectiveFeatures = effectiveFeatures,
                    protectedExpected = pocOverride == PocOverride.FORCE_RESTRICTED,
                ),
                detail = "A temporary debug test is already active; it was not restarted. Re-starting " +
                    "would reset the release timer and risk replacing the saved original policy.",
                userActionRequired = "Release the active temporary test first.",
            )
        }

        // Release/refuse an already active or uncertain session before ANY owner, unlock,
        // foreground-entry, capability, or settings prerequisite can return early. A temporary
        // activation must not leave an existing restriction stranded behind a UI refusal.
        preEntryRestrictionGuard("POC_START_WITH_EXISTING_RESTRICTION")?.let { return it }

        val owner = policyController.isDeviceOwner()
        if (owner != true) {
            return statusFromObservation(
                if (owner == false) ProtectionState.NOT_DEVICE_OWNER else ProtectionState.POLICY_PENDING,
                if (owner == false) "Device Owner provisioning is required"
                else "Device Owner authority could not be verified; the temporary test was refused.",
            )
        }
        if (environment.isKeyguardLocked() != false || environment.isUserUnlocked() != true) {
            return statusFromObservation(
                if (environment.isKeyguardLocked() == null || environment.isUserUnlocked() == null) {
                    ProtectionState.POLICY_PENDING
                } else {
                    ProtectionState.WAITING_FOR_UNLOCK
                },
                if (environment.isKeyguardLocked() == null || environment.isUserUnlocked() == null) {
                    "Unlock state could not be verified; the temporary test was refused."
                } else {
                    "The device must be unlocked before a temporary test can start."
                },
                userActionRequired = "Unlock the device, then start the test.",
            )
        }
        if (!lockTaskSession.isEntryAvailable()) {
            return statusFromObservation(
                ProtectionState.WAITING_FOR_SESSION,
                "The temporary test must be started from this app's foreground screen.",
            )
        }

        // 2. The daily preference must be disabled and recovery resolved. The test never changes
        //    the daily preference, so it refuses rather than silently altering it (R06 steps 1, 4).
        val stored = (settingsRepository.readSettings() as? SettingsReadResult.Success)?.settings
            ?: return statusFromObservation(
                ProtectionState.CONFIGURATION_ERROR,
                "Stored settings could not be read; the temporary test was not started.",
            )
        if (stored.enabled) {
            return statusFromObservation(
                ProtectionState.CONFIGURATION_ERROR,
                "The daily schedule is enabled, so the temporary test is refused. " +
                    "Disarm first with Restore Normal Device Mode; this screen never changes the " +
                    "daily preference.",
                userActionRequired = "Disarm the daily schedule, then start the temporary test.",
            )
        }
        if (stored.recoveryRequired) {
            val recovery = recoveryManager.recover("POC_REQUIRES_CLEAN_STATE")
            return statusFromObservation(
                ProtectionState.RECOVERING,
                "Recovery must complete before a temporary test can start. ${recovery.detail}",
                recoveryIncompleteStep = recovery.failedStep,
            )
        }
        if (inhibitor.isInhibited) {
            return statusFromObservation(
                ProtectionState.CONFIGURATION_ERROR,
                "New restriction is inhibited in this process: ${inhibitor.reason}",
            )
        }

        val exactCapability = scheduleManager.canScheduleExactAlarms()
        if (exactCapability != true) {
            return statusFromObservation(
                if (exactCapability == false) ProtectionState.EXACT_SCHEDULING_UNAVAILABLE
                else ProtectionState.POLICY_PENDING,
                if (exactCapability == false) {
                    "Exact alarms are not available, so the temporary test's release timer cannot be " +
                        "submitted and the test was refused."
                } else {
                    "Exact-alarm capability could not be verified, so the temporary test was refused."
                },
                userActionRequired = if (exactCapability == false) "Grant Alarms & reminders access for this app." else null,
            )
        }

        // 3. Capture the current policy only after the shared pre-entry guard. Baseline provenance
        //    and the unique temporary-session marker are then stored atomically before alarms or
        //    policy mutation.
        val startedAt = clockMillis()
        val expiry = startedAt + POC_RELEASE_MILLIS
        val marker = TemporaryTestMarker(
            startedAtEpochMillis = startedAt,
            releaseAtEpochMillis = expiry,
            revision = stored.revision,
            sessionId = java.util.UUID.randomUUID().toString(),
        )
        val captured = try { policyController.captureBaseline() } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Throwable) { null }
        if (captured == null) {
            return statusFromObservation(
                ProtectionState.CONFIGURATION_ERROR,
                "The current lock-task policy could not be read, so the temporary test was not started.",
            )
        }
        val preparation = settingsRepository.prepareSession(captured, applicationId, marker)
        val preparedRevision = when (preparation) {
            is SessionPreparationResult.Prepared -> preparation.revision
            is SessionPreparationResult.ReusedExisting -> preparation.revision
            is SessionPreparationResult.Failed -> return statusFromObservation(
                ProtectionState.CONFIGURATION_ERROR,
                "The temporary session marker and baseline could not be stored atomically; no policy was changed.",
            )
            is SessionPreparationResult.InvalidStoredBaseline -> return statusFromObservation(
                ProtectionState.CONFIGURATION_ERROR,
                "A stored policy baseline cannot be trusted (${preparation.reason}); the temporary test was refused.",
            )
        }
        val markerReadBack = try { settingsRepository.readTemporaryTestMarker() } catch (_: Throwable) { null }
        val preparedBaseline = try { settingsRepository.readBaseline() } catch (_: Throwable) { null }
        if (markerReadBack?.sessionId != marker.sessionId || markerReadBack?.releaseAtEpochMillis != expiry ||
            preparedBaseline == null || !preparedBaseline.isTrustedOriginal
        ) {
            recoveryManager.recover("POC_PREPARATION_READBACK_FAILED")
            return statusFromObservation(
                ProtectionState.CONFIGURATION_ERROR,
                "The temporary preparation could not be read back, so the test was not started and recovery was attempted.",
            )
        }

        // 5. Submit the exact temporary release AND its distinct inexact fallback BEFORE entry
        //    (R06 step 7). If either fails, refuse entry and clean up the partial preparation.
        val safeguard = scheduleManager.installTemporaryRelease(
            releaseAt = Instant.ofEpochMilli(expiry),
            revision = preparedRevision,
            sessionId = marker.sessionId,
        )
        if (!safeguard.complete) {
            recoveryManager.recover("POC_SAFEGUARD_FAILED")
            return statusFromObservation(
                ProtectionState.CONFIGURATION_ERROR,
                "The temporary release timer could not be submitted (${safeguard.failure}); " +
                    "the test was not started and cleanup was attempted.",
            )
        }
        pocSafeguardRevision = preparedRevision

        // 6. Verified permissive policy, then real entry (R06 step 9).
        currentCoroutineContext().ensureActive()
        val featuresResult = policyController.applyFeatures(LockTaskMasks.ALLOWED)
        if (!featuresResult.verified) {
            recoveryManager.recover("POC_PERMISSIVE_FAILED")
            return statusFromObservation(
                ProtectionState.CONFIGURATION_ERROR,
                "The permissive policy mask was not confirmed, so the test was not started.",
            )
        }
        currentCoroutineContext().ensureActive()
        val packagesResult = policyController.applyAllowedPackages(setOf(applicationId))
        if (!packagesResult.verified) {
            recoveryManager.recover("POC_ALLOWLIST_FAILED")
            return statusFromObservation(
                ProtectionState.CONFIGURATION_ERROR,
                "The allowlist was not confirmed, so the test was not started.",
            )
        }
        if (policyController.isLockTaskPermitted(applicationId) != true) {
            recoveryManager.recover("POC_NOT_PERMITTED")
            return statusFromObservation(
                ProtectionState.CONFIGURATION_ERROR,
                "This package is not permitted to enter Lock Task Mode; the test was not started.",
            )
        }

        pocActivatedInThisProcess = true
        pocOverride = PocOverride.FORCE_ALLOWED

        currentCoroutineContext().ensureActive()
        if (inhibitor.isInhibited) {
            val recovery = recoveryManager.recover("POC_INHIBITED_BEFORE_ENTRY")
            return statusFromObservation(
                ProtectionState.RECOVERY_FAILED,
                "A release-only inhibitor was engaged before temporary entry; the test was refused.",
                recoveryIncompleteStep = recovery.failedStep ?: "RESTRICTION_INHIBITED",
                userActionRequired = MANUAL_RECOVERY_ACTION,
            )
        }
        if (!lockTaskSession.requestStartLockTask()) {
            recoveryManager.recover("POC_ENTRY_REFUSED")
            return statusFromObservation(
                ProtectionState.CONFIGURATION_ERROR,
                "The managed session could not be started from this screen.",
            )
        }
        if (!awaitLockedSession()) {
            recoveryManager.recover("POC_NOT_LOCKED")
            return statusFromObservation(
                ProtectionState.CONFIGURATION_ERROR,
                "The temporary session did not reach LOCK_TASK_MODE_LOCKED (screen pinning is not accepted).",
            )
        }
        if (inhibitor.isInhibited) {
            val recovery = recoveryManager.recover("POC_INHIBITED_AFTER_ENTRY")
            return statusFromObservation(
                ProtectionState.RECOVERY_FAILED,
                "A release-only inhibitor was engaged while temporary entry was completing; release was attempted.",
                recoveryIncompleteStep = recovery.failedStep ?: "RESTRICTION_INHIBITED",
                userActionRequired = MANUAL_RECOVERY_ACTION,
            )
        }

        recordDiagnostic(
            ProtectionTrigger.POC_START_SESSION,
            stored.revision,
            "temporary test started; expiry=$expiry dailyEnabled=${stored.enabled}",
        )

        // Daily intent is untouched: `enabled` is still false. Reconcile only to publish status.
        return reconcileLocked(ProtectionTrigger.POC_START_SESSION)
    }

    /**
     * Manual "Allow power menu" / "Restrict power menu".
     *
     * Restricting requires the temporary release timer to be submitted first: if that fails, the
     * restrictive mask is not applied (brief section 22 step 6).
     *
     * The expiry is **fixed** at the value recorded when the test started. Repeated presses
     * therefore cannot extend the test indefinitely (R06 step 10), and the safeguard is submitted
     * once per test revision rather than on every press.
     */
    suspend fun setPocOverride(override: PocOverride): ProtectionStatus =
        serializedStatus(
            if (override == PocOverride.FORCE_RESTRICTED) ProtectionTrigger.POC_RESTRICT_MENU
            else ProtectionTrigger.POC_ALLOW_MENU,
            armTimeoutMillis,
        ) {
            val incident = settingsRepository.readRecoveryIncident()
            if (incident?.unresolved == true) {
                val recovery = recoveryManager.recover("POC_BLOCKED_BY_RECOVERY_INCIDENT")
                pocOverride = PocOverride.NONE
                return@serializedStatus statusFromObservation(
                    state = ProtectionState.RECOVERY_FAILED,
                    detail = "A durable recovery incident is unresolved; restriction was refused and release was attempted.",
                    recoveryIncompleteStep = recovery.failedStep ?: "UNRESOLVED_RECOVERY_INCIDENT",
                    userActionRequired = MANUAL_RECOVERY_ACTION,
                )
            }
            if (override == PocOverride.FORCE_RESTRICTED) {
                val marker = settingsRepository.readTemporaryTestMarker()
                    ?: return@serializedStatus statusFromObservation(
                        ProtectionState.CONFIGURATION_ERROR,
                        "No temporary test is active, so restriction was not applied.",
                    )
                val stored = settingsRepository.readSettings()
                val revision = (stored as? SettingsReadResult.Success)?.settings?.revision ?: 0L
                if (pocSafeguardRevision != revision) {
                    val install = scheduleManager.installTemporaryRelease(
                        // The marker's original expiry, never a freshly computed one.
                        releaseAt = Instant.ofEpochMilli(marker.releaseAtEpochMillis),
                        revision = revision,
                        sessionId = marker.sessionId,
                    )
                    if (!install.complete) {
                        return@serializedStatus statusFromObservation(
                            state = ProtectionState.CONFIGURATION_ERROR,
                            detail = "The temporary release timer could not be submitted " +
                                "(${install.failure}); the restrictive mask was not applied.",
                        )
                    }
                    pocSafeguardRevision = revision
                }
            }
            pocOverride = override
            reconcileLocked(
                if (override == PocOverride.FORCE_RESTRICTED) {
                    ProtectionTrigger.POC_RESTRICT_MENU
                } else {
                    ProtectionTrigger.POC_ALLOW_MENU
                },
            )
        }

    // ------------------------------------------------------------------
    // Reconciliation
    // ------------------------------------------------------------------

    private suspend fun reconcileLocked(trigger: ProtectionTrigger): ProtectionStatus {
        // 1. Latest durable settings. Never stale UI state or alarm extras.
        val stored = when (val read = settingsRepository.readSettings()) {
            is SettingsReadResult.Success -> read.settings
            is SettingsReadResult.Corrupt -> {
                // 7.10: default to disabled (already the case), release app-imposed
                // restriction, and display an error. Never silently create a fresh schedule.
                val recovery = recoveryManager.recover("CORRUPT_SETTINGS")
                return statusFromObservation(
                    state = ProtectionState.CONFIGURATION_ERROR,
                    detail = "Stored settings were unreadable. Protection was left off and release was attempted.",
                    recoveryIncompleteStep = recovery.failedStep,
                )
            }
        }

        // An older schema is upgraded in one place; a newer one is a recovery condition rather
        // than being silently reinterpreted (brief section 17).
        val settings = when (val schema = SettingsSchema.resolve(stored)) {
            is SettingsSchema.Result.Current -> stored
            is SettingsSchema.Result.Upgraded -> {
                settingsRepository.writeSettings(schema.settings)
                recordDiagnostic(
                    trigger,
                    schema.settings.revision,
                    "settings schema upgraded from ${schema.fromVersion} to ${schema.settings.schemaVersion}",
                )
                schema.settings
            }
            is SettingsSchema.Result.Unsupported -> {
                recoveryManager.recover("UNSUPPORTED_SETTINGS_SCHEMA")
                return statusFromObservation(
                    state = ProtectionState.CONFIGURATION_ERROR,
                    detail = "Stored settings use schema version ${schema.foundVersion}, which this " +
                        "build does not understand. Protection was left off and release was attempted.",
                )
            }
        }

        // A durable unresolved incident is an unconditional release-only gate, even when an
        // earlier disable write failed and settings still say enabled=true/recoveryRequired=false.
        val incident = settingsRepository.readRecoveryIncident()
        if (incident?.unresolved == true) {
            inhibitor.inhibit("A durable release-only recovery incident is unresolved")
            val recovery = recoveryManager.recover("UNRESOLVED_RECOVERY_INCIDENT")
            return statusFromObservation(
                state = ProtectionState.RECOVERY_FAILED,
                detail = "A durable recovery incident must be resolved before any new restriction; " +
                    "release was attempted and is ${if (recovery.verified) "verified" else "incomplete"}.",
                recoveryIncompleteStep = recovery.failedStep ?: "UNRESOLVED_RECOVERY_INCIDENT",
                userActionRequired = MANUAL_RECOVERY_ACTION,
            )
        }

        // An app update must not leave a temporary debug override active (brief section 22).
        if (trigger == ProtectionTrigger.APP_UPDATED) {
            pocOverride = PocOverride.NONE
            pocActivatedInThisProcess = false
        pocSafeguardRevision = null
        }

        // A durable temporary-test marker must never survive a restart, update, or boot: it
        // triggers recovery rather than a resumed test (brief section 22).
        val tempMarker = settingsRepository.readTemporaryTestMarker()
        if (tempMarker != null && !pocActivatedInThisProcess) {
            // REVIEW-2 §4.4: the result was discarded while the message claimed the test "was
            // recovered". If recovery failed, the device could still hold the restrictive mask.
            val recovery = recoveryManager.recover("TEMPORARY_TEST_INTERRUPTED")
            return statusFromObservation(
                state = if (recovery.verified) {
                    ProtectionState.DISARMED
                } else {
                    ProtectionState.RECOVERY_FAILED
                },
                detail = if (recovery.verified) {
                    "An interrupted temporary debug test was released and the original policy was " +
                        "restored and confirmed. Press Start managed session to reactivate it deliberately."
                } else {
                    "An interrupted temporary debug test was found and release was attempted, but " +
                        "recovery is INCOMPLETE at step ${recovery.failedStep}: ${recovery.detail}. " +
                        "The device may still be restricted."
                },
                recoveryIncompleteStep = recovery.failedStep,
                userActionRequired = if (recovery.verified) {
                    "Press Start managed session to reactivate the debug session."
                } else {
                    MANUAL_RECOVERY_ACTION
                },
            )
        }

        /**
         * A live temporary debug test is its **own session mode**. It deliberately keeps the daily
         * `enabled` preference false (repair R06 step 4), so it must not be mistaken for "disarmed"
         * by the branches below — doing so would cancel its release timer, clear its journal, and
         * tear the running test down. `pocActivatedInThisProcess` distinguishes a live test from a
         * marker left behind by a crash.
         */
        val temporaryTestActive = temporaryTestIsActive()

        // 2. Device Owner status and runtime Lock Task state.
        val deviceOwner = policyController.isDeviceOwner()
        if (deviceOwner == true) settingsRepository.markOwnerEstablished()
        val runtimeState = policyController.readRuntimeLockTaskState()

        // 3. Recovery pending: continue recovery and return its state.
        if (settings.recoveryRequired) {
            val recovery = recoveryManager.recover("RECOVERY_REQUIRED")
            return statusFromObservation(
                state = if (recovery.verified) ProtectionState.DISARMED else ProtectionState.RECOVERING,
                detail = if (recovery.verified) {
                    "Pending recovery completed; normal device mode restored"
                } else {
                    "Recovery in progress; step ${recovery.failedStep} is unresolved"
                },
                recoveryIncompleteStep = recovery.failedStep,
            )
        }

        if (deviceOwner == null) {
            inhibitor.inhibit("Device Owner authority could not be read")
            val recovery = recoveryManager.recover("DEVICE_OWNER_UNKNOWN")
            return statusFromObservation(
                state = ProtectionState.RECOVERY_FAILED,
                detail = "Device Owner authority is unknown; no success state can be claimed. " +
                    "A release attempt was made.",
                recoveryIncompleteStep = recovery.failedStep ?: "DEVICE_OWNER_UNKNOWN",
                userActionRequired = MANUAL_RECOVERY_ACTION,
            )
        }

        // 4. Disabled: cancel stale work and release app-imposed restriction. Never enter
        //    Lock Task Mode.
        //
        //    Skipped while a temporary debug test is live: that mode keeps the daily preference
        //    false on purpose, and tearing it down here would cancel its release timer and clear
        //    its journal (repair R06).
        if (!settings.enabled && !temporaryTestActive) {
            pocOverride = PocOverride.NONE
            pocActivatedInThisProcess = false
            pocSafeguardRevision = null
            lastSubmission = null

            // Release an existing app-imposed restriction FIRST (second review U04). Exact-alarm
            // access is a prerequisite for NEW scheduled restriction, not for releasing one that is
            // already in force. Returning the capability error first left a real locked session
            // active behind an "exact scheduling unavailable" message, so the user could not get
            // out through the path that was supposed to free them.
            val baselineLifecycle = settingsRepository.readBaseline()?.lifecycle
            val cleanupOwed = runtimeState == null || runtimeState != LockTaskRuntimeStates.NONE ||
                settings.recoveryRequired ||
                (baselineLifecycle != null && baselineLifecycle !=
                    com.example.shutdownprotection.data.BaselineLifecycleState.RESTORED) ||
                appImposedPolicyMayBePresent()
            if (cleanupOwed) {
                val recovery = recoveryManager.recover("DISABLED_LATEST_REVISION")
                // Capability loss is reported SEPARATELY from recovery success/failure, so a
                // successful release is never presented as if scheduling were healthy.
                val capability = scheduleManager.canScheduleExactAlarms()
                return statusFromObservation(
                    state = if (recovery.verified) ProtectionState.DISARMED else ProtectionState.RECOVERY_FAILED,
                    detail = buildString {
                        append(
                            if (recovery.verified) {
                                if (recovery.baselineRestored) {
                                    "Protection is disabled; the original policy was restored and confirmed."
                                } else {
                                    recovery.detail
                                }
                            } else {
                                "Recovery incomplete at step ${recovery.failedStep}"
                            },
                        )
                        if (capability == false) {
                            append(". Exact alarms remain unavailable, so scheduling requires access.")
                        } else if (capability == null) {
                            append(". Exact-alarm capability remains unknown.")
                        }
                    },
                    recoveryIncompleteStep = recovery.failedStep,
                    userActionRequired = if (recovery.verified && capability == true) {
                        null
                    } else if (capability == false) {
                        "Grant Alarms & reminders access for this app."
                    } else {
                        if (recovery.verified) null else MANUAL_RECOVERY_ACTION
                    },
                )
            }

            // No cleanup is owed. It is now safe to retire only the alarm receipt. The temporary
            // marker is never cleared here; only RecoveryManager's verified atomic cleanup may
            // remove it.
            scheduleManager.cancelAll()
            settingsRepository.writeScheduleReceipt(null)

            // Capability loss on its own: nothing of ours is in force, so this is only a
            // scheduling prerequisite notice.
            val exactCapability = scheduleManager.canScheduleExactAlarms()
            if (deviceOwner == true && exactCapability != true) {
                return statusFromObservation(
                    state = if (exactCapability == false) ProtectionState.EXACT_SCHEDULING_UNAVAILABLE else ProtectionState.POLICY_PENDING,
                    detail = if (exactCapability == false) {
                        "Exact alarms are not available, so protection cannot be scheduled. " +
                            "Grant Alarms & reminders access and then enable protection."
                    } else {
                        "Exact-alarm capability could not be verified, so no schedule was submitted."
                    },
                    userActionRequired = if (exactCapability == false) "Grant Alarms & reminders access for this app." else null,
                )
            }
            return statusFromObservation(ProtectionState.DISARMED, "Protection is disabled")
        }

        if (deviceOwner == false) {
            if (appImposedPolicyMayBePresent()) {
                inhibitor.inhibit("Device Owner authority was lost while policy may remain")
                val recovery = recoveryManager.recover("DEVICE_OWNER_LOST_WITH_POLICY")
                return statusFromObservation(
                    state = ProtectionState.RECOVERY_FAILED,
                    detail = "Device Owner authority is no longer present while app-managed policy may remain; " +
                        "release was attempted but cannot be verified.",
                    recoveryIncompleteStep = recovery.failedStep ?: "DEVICE_OWNER_LOST",
                    userActionRequired = MANUAL_RECOVERY_ACTION,
                )
            }
            return statusFromObservation(
                state = ProtectionState.NOT_DEVICE_OWNER,
                detail = "Device Owner provisioning is required",
            )
        }

        // 5. Validate the configuration read from **persistent storage** (repair R04). UI validation
        //    rejects bad edits before they are saved, but that is not proof the stored file is
        //    valid — it can be corrupt, hand-edited, or restored from an older version.
        //
        //    Previously an invalid stored schedule or a stored allowlist missing this app produced
        //    an early CONFIGURATION_ERROR return that left an existing restrictive session running.
        //    Invalid durable intent must never strand an app-imposed restriction behind an error
        //    label: inhibit new restriction and release what we imposed.
        val validation = settings.validate()
        // A temporary debug test does not use the stored daily schedule or the stored allowlist:
        // its allowlist is applied directly and its interval comes from the manual override. The
        // daily configuration is therefore not a precondition for it, and an invalid daily
        // schedule must not abort a running test (repair R06).
        val allowlistFault = !temporaryTestActive && applicationId !in settings.allowedPackages
        if (!temporaryTestActive && (validation is SettingsValidation.Invalid || allowlistFault)) {
            val fault = if (validation is SettingsValidation.Invalid) {
                validation.message
            } else {
                "This app is missing from the saved allowed-applications list."
            }
            inhibitor.inhibit("Invalid stored configuration: $fault")
            val recovery = recoveryManager.recover("INVALID_STORED_CONFIGURATION")
            return statusFromObservation(
                state = ProtectionState.CONFIGURATION_ERROR,
                detail = "$fault Protection was released as far as possible. Recovery: ${recovery.detail}",
                recoveryIncompleteStep = recovery.failedStep,
                userActionRequired = if (recovery.verified) {
                    // Deliberately not auto-repaired into an enabled schedule: a new valid setting is
                    // requested only after the device has been released.
                    "Set a valid start and end time (and make sure this app is in the allowed " +
                        "applications), then enable protection again."
                } else {
                    MANUAL_RECOVERY_ACTION
                },
            )
        }

        // 6. Exact-alarm capability. Unavailable is a detected error: take the recovery
        //    path and do not newly restrict.
        val exactCapability = scheduleManager.canScheduleExactAlarms()
        if (exactCapability != true) {
            // REVIEW-2 §4.4 (the phrase the review names): the result was discarded while the
            // message claimed "the device was disarmed". Capability loss does not prove release.
            val recovery = recoveryManager.recover("EXACT_SCHEDULING_UNAVAILABLE")
            return statusFromObservation(
                state = if (exactCapability == false) ProtectionState.EXACT_SCHEDULING_UNAVAILABLE else ProtectionState.POLICY_PENDING,
                detail = if (exactCapability == false && recovery.verified) {
                    "Exact alarms are not available, so no new restriction was applied and the " +
                        "existing policy was released and confirmed. Grant Alarms & reminders " +
                        "access and resume."
                } else if (exactCapability == false) {
                    "Exact alarms are not available, so no new restriction was applied, but release " +
                        "is INCOMPLETE at step ${recovery.failedStep}: ${recovery.detail}. The " +
                        "device may still be restricted."
                } else {
                    "Exact-alarm capability could not be verified; no new restriction was applied. " +
                        "Release is ${if (recovery.verified) "verified" else "incomplete at ${recovery.failedStep}"}."
                },
                recoveryIncompleteStep = recovery.failedStep,
                userActionRequired = if (exactCapability == false) "Grant Alarms & reminders access for this app." else MANUAL_RECOVERY_ACTION,
            )
        }

        // Every enabled schedule or live temporary session can impose policy now or at a later
        // alarm. It may do so only while the durable journal proves a valid original baseline
        // captured before this session's preparation. Missing, legacy, malformed, or retired
        // baselines must go through release/recovery; otherwise a restart could create a new
        // restriction that has no safe original policy to restore.
        if (settings.enabled || temporaryTestActive || pocOverride == PocOverride.FORCE_RESTRICTED) {
            val baselineProblem = activeSessionBaselineProblem()
            if (baselineProblem != null) {
                inhibitor.inhibit("An active session has no trusted PREPARED original-policy baseline")
                lastSubmission = null
                planSubmittedInThisProcess = false
                pocOverride = PocOverride.NONE
                pocActivatedInThisProcess = false
                pocSafeguardRevision = null
                val recovery = recoveryManager.recover("ACTIVE_SESSION_BASELINE_UNAVAILABLE")
                return statusFromObservation(
                    state = ProtectionState.RECOVERY_FAILED,
                    detail = "No trusted original-policy baseline is available for the active session " +
                        "($baselineProblem). No new schedule or restriction was applied; release was " +
                        if (recovery.verified) "attempted." else "attempted but remains unverified at ${recovery.failedStep}.",
                    recoveryIncompleteStep = recovery.failedStep ?: "ACTIVE_SESSION_BASELINE_UNAVAILABLE",
                    userActionRequired = MANUAL_RECOVERY_ACTION,
                )
            }
        }

        // 7. Recompute membership from the current instant and zone.
        val membership = scheduleCalculator.membership(settings)
        val scheduleSaysProtected = when (pocOverride) {
            PocOverride.FORCE_RESTRICTED -> true
            PocOverride.FORCE_ALLOWED -> false
            PocOverride.NONE -> membership.protectedNow
        }
        val sessionLocked = LockTaskRuntimeStates.isRealLocked(runtimeState) == true

        // 8. RELEASE FIRST (repair R01). When a real managed session is active and the desired
        //    state is permissive, request the permissive mask and confirm it by readback BEFORE
        //    advancing the end/fallback identities or waiting on any receipt storage.
        //
        //    Why the order matters: at an end boundary the "next" plan is tomorrow's, so installing
        //    it replaces today's release alarm before the current restriction has been lifted. A
        //    storage stall during that install then times out and leaves the device restricted with
        //    only a "pending" label. Restoring access takes priority over computing tomorrow's plan
        //    (guide R01 steps 1-4).
        if (sessionLocked && !scheduleSaysProtected) {
            val permissiveNow = applyMaskWithFence(LockTaskMasks.ALLOWED, settings.revision)
            if (!permissiveNow.applied.verified) {
                lastSubmission = null
                val recovery = recoveryManager.recover("RELEASE_READBACK_FAILED")
                return statusFromObservation(
                    state = if (recovery.verified) ProtectionState.DISARMED else ProtectionState.RECOVERY_FAILED,
                    detail = "Releasing the current restriction could not be confirmed by readback " +
                        "(observed features=${permissiveNow.applied.readback?.features}); the alarm plan was " +
                        "not advanced and recovery was attempted.",
                    recoveryIncompleteStep = recovery.failedStep,
                    userActionRequired = if (recovery.verified) null else MANUAL_RECOVERY_ACTION,
                )
            }
        }

        // 9. Ensure the upcoming end/start/fallback plan exists for the current revision. Only
        //    reached once any release needed above is confirmed, so a current release opportunity
        //    is never replaced by a later one before release is verified.
        //
        //    Skipped entirely for a temporary debug test: it must not install a daily start alarm
        //    as a side effect, and its own release timer is its safeguard (repair R06 step 8).
        var releasePlanSubmitted = false
        if (!temporaryTestActive) {
            val bootGeneration = settingsRepository.readBootGeneration()
            val boundaries = scheduleCalculator.boundaries(settings)
            if (!boundaries.isUsable) {
                recoveryManager.recover("NO_FUTURE_BOUNDARY")
                return statusFromObservation(
                    state = ProtectionState.CONFIGURATION_ERROR,
                    detail = "No valid future boundary could be computed (${boundaries.error}); restriction was not activated.",
                )
            }
            val nextStart = boundaries.nextStart!!
            val nextEnd = boundaries.nextEnd!!

            val existingReceipt = settingsRepository.readScheduleReceipt()
            // A receipt records what was *submitted*. The token check is a cheap negative signal
            // only: a token is not a scheduled alarm, so a positive result does not prove delivery
            // (R07).
            val planIsCurrent = existingReceipt != null &&
                existingReceipt.revision == settings.revision &&
                existingReceipt.bootGeneration == bootGeneration &&
                existingReceipt.nextStartEpochMillis == nextStart.toEpochMilli() &&
                existingReceipt.nextEndEpochMillis == nextEnd.toEpochMilli() &&
                scheduleManager.canScheduleExactAlarms() == true &&
                scheduleManager.hasPlanPendingIntentTokens() == true &&
                // Submission evidence from a previous process is not trustworthy on its own: a
                // receipt records what was submitted, and a PendingIntent token is not a scheduled
                // alarm. After a restart the plan is deliberately resubmitted once (repair R07).
                planSubmittedInThisProcess

            releasePlanSubmitted = planIsCurrent
            if (!planIsCurrent) {
                val install = scheduleManager.installPlan(nextEnd, nextStart, settings.revision, bootGeneration)
                if (!install.complete) {
                    recoveryManager.recover("SCHEDULING_FAILED")
                    return statusFromObservation(
                        state = ProtectionState.CONFIGURATION_ERROR,
                        detail = "The release schedule could not be submitted (${install.failure}); restriction was not activated.",
                    )
                }
                // The receipt is required evidence, not optional metadata: if the submitted plan
                // cannot be recorded, the plan cannot be trusted, so cleanup is required (R01 step 5).
                val receiptWrite = settingsRepository.writeScheduleReceipt(install.receipt)
                if (receiptWrite is SettingsWriteResult.Failure) {
                    recoveryManager.recover("RECEIPT_WRITE_FAILED")
                    return statusFromObservation(
                        state = ProtectionState.CONFIGURATION_ERROR,
                        detail = "The release plan was submitted but its receipt could not be saved " +
                            "(${receiptWrite.cause}); recovery was attempted.",
                    )
                }
                planSubmittedInThisProcess = true
                releasePlanSubmitted = true
            }
        }

        // 9. The required runtime session is absent.
        if (LockTaskRuntimeStates.isRealLocked(runtimeState) != true) {
            // Keep global actions permitted in the configured mask, report honestly, and do
            // NOT claim protection. A background alarm must not launch an activity.
            val permissive = policyController.applyFeatures(LockTaskMasks.ALLOWED)
            val keyguardLocked = environment.isKeyguardLocked()
            val userUnlocked = environment.isUserUnlocked()
            val unlockStateUnknown = keyguardLocked == null || userUnlocked == null
            val waitingForUnlock = keyguardLocked == true || userUnlocked == false
            val state = when {
                unlockStateUnknown -> ProtectionState.POLICY_PENDING
                waitingForUnlock -> ProtectionState.WAITING_FOR_UNLOCK
                runtimeState == null -> ProtectionState.POLICY_PENDING
                else -> ProtectionState.WAITING_FOR_SESSION
            }
            return statusFromObservation(
                state = state,
                detail = if (unlockStateUnknown || runtimeState == null) {
                    "Runtime or unlock state could not be verified. Open the app and retry after checking device state."
                } else if (waitingForUnlock) {
                    "Waiting for unlock. Open the app and resume the managed session."
                } else {
                    "Waiting for the managed session. Open the app and resume it."
                },
                requestedFeatures = LockTaskMasks.ALLOWED,
                effectiveFeatures = permissive.readback?.features,
                insideProtectedInterval = membership.protectedNow,
                releasePlanSubmitted = releasePlanSubmitted,
                userActionRequired = "Open the app and resume the managed session.",
            )
        }

        // 11. Restrict only inside the interval and only when not inhibited; otherwise keep the
        //     armed session running with global actions permitted.
        val wantRestriction = scheduleSaysProtected && !inhibitor.isInhibited
        val desiredMask = if (wantRestriction) LockTaskMasks.PROTECTED else LockTaskMasks.ALLOWED

        val outcome = applyMaskWithFence(desiredMask, settings.revision)
        val applied = outcome.applied

        if (applied is PolicyOperationResult.Rejected || applied is PolicyOperationResult.NotAuthorized) {
            // Conflict/failure: invalidate the success display and enter bounded recovery.
            lastSubmission = null
            val recovery = recoveryManager.recover("POLICY_APPLY_FAILED")
            return statusFromObservation(
                state = if (recovery.verified) ProtectionState.DISARMED else ProtectionState.RECOVERY_FAILED,
                detail = "Applying the policy failed (${applied.failure}); recovery was attempted.",
                recoveryIncompleteStep = recovery.failedStep,
            )
        }

        if (!applied.verified && outcome.alreadySubmitted) {
            // Submitted once for this revision and the readback still disagrees. That is a policy
            // conflict, not something to retry: invalidate and take the bounded recovery path
            // rather than looping through submit -> callback -> submit (brief sections 12 and 18).
            lastSubmission = null
            val recovery = recoveryManager.recover("POLICY_READBACK_MISMATCH")
            return statusFromObservation(
                state = if (recovery.verified) ProtectionState.DISARMED else ProtectionState.RECOVERY_FAILED,
                detail = "Policy readback did not confirm the requested mask " +
                    "(${LockTaskMasks.describe(desiredMask)}); recovery was attempted instead of retrying.",
                recoveryIncompleteStep = recovery.failedStep,
            )
        }

        // 12. Derive the reported state from observations. **Both** branches now require confirmed
        //     readback (repair R05). Previously the restricted branch checked verification but the
        //     allowed branch fell through unconditionally, so an unconfirmed permissive request was
        //     displayed as "Power menu allowed" — a success claim with no evidence behind it.
        val effectiveFeatures = applied.readback?.features
        val state = when {
            applied.verified && desiredMask == LockTaskMasks.PROTECTED ->
                ProtectionState.ARMED_POWER_MENU_RESTRICTED

            applied.verified -> ProtectionState.ARMED_POWER_MENU_ALLOWED

            else -> ProtectionState.POLICY_PENDING
        }
        val detail = when (state) {
            ProtectionState.ARMED_POWER_MENU_RESTRICTED ->
                if (pocOverride == PocOverride.FORCE_RESTRICTED) {
                    // Do not claim the schedule caused this: the manual debug override did.
                    "Restricting the power menu by manual debug override; global actions are restricted"
                } else {
                    "Inside the protected interval; global actions are restricted"
                }

            ProtectionState.ARMED_POWER_MENU_ALLOWED ->
                if (pocOverride == PocOverride.FORCE_ALLOWED) {
                    "Power menu allowed - managed session active (manual debug override)"
                } else {
                    "Power menu allowed - managed session active"
                }

            else ->
                // Wording deliberately avoids implying success. A submitted-but-unconfirmed
                // request is neither "allowed" nor "restricted", and a readback confirms a feature
                // mask only — it never establishes what the OEM's power-menu gesture does.
                "Power-menu policy not confirmed; requested ${LockTaskMasks.describe(desiredMask)}, " +
                    "observed features=$effectiveFeatures"
        }

        val status = statusFromObservation(
            state = state,
            detail = detail,
            requestedFeatures = desiredMask,
            effectiveFeatures = effectiveFeatures,
            insideProtectedInterval = membership.protectedNow,
            releasePlanSubmitted = releasePlanSubmitted,
        )

        // 13. Diagnostic event. Logging failure must not prevent release.
        recordDiagnostic(trigger, settings.revision, "state=${state.wireName} inside=${membership.protectedNow}")
        return status
    }

    // ------------------------------------------------------------------
    // Arm transaction (brief section 9)
    // ------------------------------------------------------------------

    private suspend fun armLocked(): ProtectionStatus {
        // A daily arm must never inherit a temporary-test override (independent audit finding):
        // the two session modes are meant to be mutually exclusive.
        pocOverride = PocOverride.NONE
        pocActivatedInThisProcess = false
        pocSafeguardRevision = null

        // 0. RELEASE AN EXISTING APP-IMPOSED RESTRICTION BEFORE ANY REFUSAL BELOW.
        //
        //    verify3-a P1, and the reason this is step 0 rather than step 2b: the previous attempt
        //    changed this guard's CONDITION but left it BELOW eight refusal returns
        //    (corrupt settings, unsupported schema, recovery-pending, inhibitor, not-owner, keyguard,
        //    validation, allowlist, entry-unavailable, exact-alarm). Those returns therefore still
        //    fired first, so on an already-restricted device — revoke Alarms & reminders, press
        //    "Resume managed session" — the app returned EXACT_SCHEDULING_UNAVAILABLE and left the
        //    locked session and mask 47 in place. That is literally U04's acceptance criterion, and
        //    the earlier claim that it was fixed was wrong.
        //
        //    It must precede the inhibitor check too: an inhibited process still has to be able to
        //    release what it already imposed.
        //
        //    Second review U07 is the missing-baseline half of the same guard: capturing the current
        //    policy when a restriction is already in force would save the app's OWN mask as the
        //    "original".
        preEntryRestrictionGuard("ARM_WITH_EXISTING_RESTRICTION")?.let { return it }

        // 1. Read settings and prerequisite state; reject when recovery is unresolved.
        val settings = when (val read = settingsRepository.readSettings()) {
            is SettingsReadResult.Success -> read.settings
            is SettingsReadResult.Corrupt -> {
                recoveryManager.recover("CORRUPT_SETTINGS_ARM")
                return statusFromObservation(
                    ProtectionState.CONFIGURATION_ERROR,
                    "Stored settings were unreadable; arming was refused and release was attempted.",
                )
            }
        }

        // Refuse to arm on a schema this build does not understand (brief section 17).
        val schema = SettingsSchema.resolve(settings)
        if (schema is SettingsSchema.Result.Unsupported) {
            recoveryManager.recover("UNSUPPORTED_SETTINGS_SCHEMA_ARM")
            return statusFromObservation(
                ProtectionState.CONFIGURATION_ERROR,
                "Stored settings use schema version ${schema.foundVersion}, which this build does " +
                    "not understand; arming was refused.",
            )
        }

        if (settings.recoveryRequired) {
            val recovery = recoveryManager.recover("RECOVERY_REQUIRED_BEFORE_ARM")
            return statusFromObservation(
                state = ProtectionState.RECOVERING,
                detail = "Recovery must complete before a new arm operation. ${recovery.detail}",
                recoveryIncompleteStep = recovery.failedStep,
            )
        }
        if (inhibitor.isInhibited) {
            return statusFromObservation(
                ProtectionState.CONFIGURATION_ERROR,
                "New restriction is inhibited in this process: ${inhibitor.reason}",
            )
        }
        if (policyController.isDeviceOwner() != true) {
            return statusFromObservation(
                if (policyController.isDeviceOwner() == false) ProtectionState.NOT_DEVICE_OWNER else ProtectionState.POLICY_PENDING,
                if (policyController.isDeviceOwner() == false) "Device Owner provisioning is required"
                else "Device Owner authority could not be verified; arming was refused.",
            )
        }
        if (environment.isKeyguardLocked() != false || environment.isUserUnlocked() != true) {
            return statusFromObservation(
                if (environment.isKeyguardLocked() == null || environment.isUserUnlocked() == null) {
                    ProtectionState.POLICY_PENDING
                } else {
                    ProtectionState.WAITING_FOR_UNLOCK
                },
                if (environment.isKeyguardLocked() == null || environment.isUserUnlocked() == null) {
                    "Unlock state could not be verified; arming was refused."
                } else {
                    "The device must be unlocked before a managed session can start."
                },
                userActionRequired = "Unlock the device, then resume.",
            )
        }
        val validation = settings.validate()
        if (validation is SettingsValidation.Invalid) {
            return statusFromObservation(ProtectionState.CONFIGURATION_ERROR, validation.message)
        }
        if (applicationId !in settings.allowedPackages) {
            return statusFromObservation(
                ProtectionState.CONFIGURATION_ERROR,
                "The protection app is missing from the allowed applications.",
                userActionRequired = "Add this app to the allowed applications, then resume.",
            )
        }
        if (!lockTaskSession.isEntryAvailable()) {
            return statusFromObservation(
                ProtectionState.WAITING_FOR_SESSION,
                "The managed session must be started from this app's foreground screen.",
                userActionRequired = "Keep this screen open and press resume.",
            )
        }
        val exactCapability = scheduleManager.canScheduleExactAlarms()
        if (exactCapability != true) {
            return statusFromObservation(
                if (exactCapability == false) ProtectionState.EXACT_SCHEDULING_UNAVAILABLE
                else ProtectionState.POLICY_PENDING,
                if (exactCapability == false) "Exact alarms are not available, so arming was refused."
                else "Exact-alarm capability could not be verified, so arming was refused.",
                userActionRequired = if (exactCapability == false) "Grant Alarms & reminders access for this app." else null,
            )
        }

        // 2b. (The existing-restriction guard that used to sit here has been MOVED to step 0 at the
        //      top of this function. Leaving it here was the defect: eight refusal returns sat above
        //      it, so they fired first and could strand a live restriction. See step 0's comment.)


        // 2 + 3. Capture the original policy baseline and write the preparation journal
        //        ATOMICALLY, and check the result (repair R02). Previously the baseline write was
        //        fire-and-forget inside `also`, so arming could enter a real managed session and
        //        apply protection even though the original policy was never saved.
        // A read failure here is a refusal, not an escaping exception (independent audit finding).
        val candidateBaseline = try { policyController.captureBaseline() } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Throwable) { null }
            ?: return statusFromObservation(
                ProtectionState.CONFIGURATION_ERROR,
                "The device's current lock-task policy could not be read, so arming was refused and " +
                    "no policy was changed.",
            )
        val armRevision = when (val preparation =
            settingsRepository.prepareSession(candidateBaseline, applicationId)) {

            is SessionPreparationResult.Failed -> return statusFromObservation(
                ProtectionState.CONFIGURATION_ERROR,
                "The session preparation could not be written (${preparation.cause}); " +
                    "no policy was changed and no session was started.",
            )

            is SessionPreparationResult.InvalidStoredBaseline -> {
                // A baseline we cannot trust is a recovery condition. It is never overwritten
                // with the app's already-modified values.
                recoveryManager.recover("INVALID_STORED_BASELINE")
                return statusFromObservation(
                    ProtectionState.CONFIGURATION_ERROR,
                    "A stored policy baseline cannot be trusted (${preparation.reason}); " +
                        "arming was refused and recovery was attempted.",
                )
            }

            is SessionPreparationResult.Prepared -> preparation.revision
            is SessionPreparationResult.ReusedExisting -> preparation.revision
        }

        // Read back enough preparation state to confirm the baseline and the journal represent the
        // same preparation, and that the baseline is valid for this installation.
        val preparedSettings = (settingsRepository.readSettings() as? SettingsReadResult.Success)?.settings
        val storedBaseline = settingsRepository.readBaseline()
        if (preparedSettings == null || !preparedSettings.recoveryRequired || storedBaseline == null) {
            return statusFromObservation(
                ProtectionState.CONFIGURATION_ERROR,
                "The session preparation could not be read back (baseline present=" +
                    "${storedBaseline != null}, journal set=${preparedSettings?.recoveryRequired}); " +
                    "arming was refused.",
            )
        }
        val baselineInvalidReason = storedBaseline.invalidReasonFor(applicationId)
        if (baselineInvalidReason != null) {
            recoveryManager.recover("INVALID_STORED_BASELINE")
            return statusFromObservation(
                ProtectionState.CONFIGURATION_ERROR,
                "The prepared baseline is not valid for this installation ($baselineInvalidReason); " +
                    "arming was refused and recovery was attempted.",
            )
        }

        // The UI is ARMING from here until the commit at step 6. Publishing it means the screen
        // can say "arming" rather than only showing a generic busy flag, and it is the state the
        // journal represents if the process dies mid-transaction (brief section 9).
        statusFromObservation(
            state = ProtectionState.ARMING,
            detail = "Arming: applying the permissive mask, the allowlist and the release schedule.",
        )

        // 4. Permissive mask + allowlist, verifying each result by readback - not merely checking
        //    that the setter returned (brief section 9 step 8). Then prepare the alarms for this
        //    revision from the candidate schedule.
        currentCoroutineContext().ensureActive()
        val featuresResult = policyController.applyFeatures(LockTaskMasks.ALLOWED)
        if (!featuresResult.verified) {
            recoveryManager.recover("ARM_FEATURES_FAILED")
            return statusFromObservation(
                ProtectionState.CONFIGURATION_ERROR,
                "The permissive policy mask was not confirmed by readback " +
                    "(submitted=${featuresResult.submitted}, readback=${featuresResult.readback?.features}, " +
                    "failure=${featuresResult.failure}).",
            )
        }
        currentCoroutineContext().ensureActive()
        val packagesResult = policyController.applyAllowedPackages(settings.allowedPackages)
        if (!packagesResult.verified) {
            recoveryManager.recover("ARM_ALLOWLIST_FAILED")
            return statusFromObservation(
                ProtectionState.CONFIGURATION_ERROR,
                "The allowed-application list was not confirmed by readback " +
                    "(submitted=${packagesResult.submitted}, " +
                    "readback=${packagesResult.readback?.packages}, failure=${packagesResult.failure}).",
            )
        }

        val boundaries = scheduleCalculator.boundaries(settings)
        if (!boundaries.isUsable) {
            recoveryManager.recover("ARM_NO_FUTURE_BOUNDARY")
            return statusFromObservation(
                ProtectionState.CONFIGURATION_ERROR,
                "No valid future boundary could be computed (${boundaries.error}).",
            )
        }
        val bootGeneration = settingsRepository.readBootGeneration()
        val install = scheduleManager.installPlan(
            nextEnd = boundaries.nextEnd!!,
            nextStart = boundaries.nextStart!!,
            revision = armRevision,
            bootGeneration = bootGeneration,
        )
        if (!install.complete) {
            recoveryManager.recover("ARM_SCHEDULING_FAILED")
            return statusFromObservation(
                ProtectionState.CONFIGURATION_ERROR,
                "The release schedule could not be submitted (${install.failure}); restriction was not activated.",
            )
        }
        // The receipt is a required write: a failure here means the submitted plan is not
        // recorded, so cleanup is required rather than proceeding into restriction (R02 step 8).
        val receiptWrite = settingsRepository.writeScheduleReceipt(install.receipt)
        if (receiptWrite is SettingsWriteResult.Failure) {
            recoveryManager.recover("ARM_RECEIPT_WRITE_FAILED")
            return statusFromObservation(
                ProtectionState.CONFIGURATION_ERROR,
                "The release-plan receipt could not be saved (${receiptWrite.cause}); " +
                    "restriction was not activated and cleanup was attempted.",
            )
        }

        // 5. Enter from the eligible foreground activity and verify real runtime LOCKED
        //    state. Global actions are still permitted at this point.
        //
        //    `startLockTask()` is only valid when this package is in the lock-task allowlist, so
        //    that is confirmed first rather than discovering it from the resulting state
        //    (brief section 9.9).
        if (policyController.isLockTaskPermitted(applicationId) != true) {
            recoveryManager.recover("ARM_NOT_PERMITTED")
            return statusFromObservation(
                ProtectionState.CONFIGURATION_ERROR,
                "This package is not permitted to enter Lock Task Mode, so the managed session " +
                    "was not started.",
            )
        }
        currentCoroutineContext().ensureActive()
        if (inhibitor.isInhibited) {
            val recovery = recoveryManager.recover("ARM_INHIBITED_BEFORE_ENTRY")
            return statusFromObservation(
                ProtectionState.RECOVERY_FAILED,
                "A release-only inhibitor was engaged before managed entry; arming was refused.",
                recoveryIncompleteStep = recovery.failedStep ?: "RESTRICTION_INHIBITED",
                userActionRequired = MANUAL_RECOVERY_ACTION,
            )
        }
        if (!lockTaskSession.requestStartLockTask()) {
            recoveryManager.recover("ARM_ENTRY_REFUSED")
            return statusFromObservation(
                ProtectionState.CONFIGURATION_ERROR,
                "The managed session could not be started from this screen.",
            )
        }
        if (!awaitLockedSession()) {
            recoveryManager.recover("ARM_NOT_LOCKED")
            return statusFromObservation(
                ProtectionState.CONFIGURATION_ERROR,
                "The managed session did not reach LOCK_TASK_MODE_LOCKED (screen pinning is not accepted).",
            )
        }
        if (inhibitor.isInhibited) {
            val recovery = recoveryManager.recover("ARM_INHIBITED_AFTER_ENTRY")
            return statusFromObservation(
                ProtectionState.RECOVERY_FAILED,
                "A release-only inhibitor was engaged while managed entry was completing; release was attempted.",
                recoveryIncompleteStep = recovery.failedStep ?: "RESTRICTION_INHIBITED",
                userActionRequired = MANUAL_RECOVERY_ACTION,
            )
        }

        // 6. Durably commit, without changing the revision the alarm plan was built from.
        val commit = settingsRepository.editSettings { current ->
            current.copy(enabled = true, recoveryRequired = false)
        }
        if (commit is SettingsWriteResult.Failure) {
            recoveryManager.recover("ARM_COMMIT_FAILED")
            return statusFromObservation(
                ProtectionState.CONFIGURATION_ERROR,
                "The arm commit could not be written (${commit.cause}); recovery was invoked.",
            )
        }
        if (inhibitor.isInhibited) {
            val recovery = recoveryManager.recover("ARM_INHIBITED_DURING_COMMIT")
            return statusFromObservation(
                ProtectionState.RECOVERY_FAILED,
                "A release-only inhibitor was engaged while the arm commit was being written; release was attempted.",
                recoveryIncompleteStep = recovery.failedStep ?: "RESTRICTION_INHIBITED",
                userActionRequired = MANUAL_RECOVERY_ACTION,
            )
        }

        recordDiagnostic(
            ProtectionTrigger.USER_ARM_REQUEST,
            armRevision,
            "armed; baseline features=${storedBaseline.lockTaskFeatures} packages=${storedBaseline.lockTaskPackages.size}",
        )

        // 7. Reconcile immediately. Restriction can start only now.
        return reconcileLocked(ProtectionTrigger.USER_ARM_REQUEST)
    }

    // ------------------------------------------------------------------
    // Schedule / allowlist edits
    // ------------------------------------------------------------------

    private suspend fun editScheduleLocked(
        startMinuteOfDay: Int,
        endMinuteOfDay: Int,
    ): ProtectionStatus {
        val read = settingsRepository.readSettings()
        if (read !is SettingsReadResult.Success) {
            val runtime = readRuntimeStateOrNull()
            val releaseOwed = runtime == null || runtime != LockTaskRuntimeStates.NONE ||
                appImposedPolicyMayBePresent() || inhibitor.isInhibited
            val recovery = if (releaseOwed) {
                inhibitor.inhibit("Stored settings are corrupt during schedule edit")
                recoveryManager.recover("CORRUPT_SETTINGS_SCHEDULE_EDIT")
            } else {
                null
            }
            return statusFromObservation(
                state = if (recovery == null || recovery.verified) {
                    ProtectionState.CONFIGURATION_ERROR
                } else {
                    ProtectionState.RECOVERY_FAILED
                },
                detail = if (recovery == null) {
                    "Stored settings could not be read; the edit was refused."
                } else if (recovery.verified) {
                    "Stored settings could not be read; the edit was refused and any app-managed " +
                        "restriction was released."
                } else {
                    "Stored settings could not be read; the edit was refused and release remains " +
                        "unverified at ${recovery.failedStep}."
                },
                recoveryIncompleteStep = recovery?.failedStep,
                userActionRequired = if (recovery?.verified == false) MANUAL_RECOVERY_ACTION else null,
            )
        }
        val current = read.settings

        // 1. Validate the candidate before touching the saved schedule.
        val candidate = current.copy(
            startMinuteOfDay = startMinuteOfDay,
            endMinuteOfDay = endMinuteOfDay,
        )
        val validation = candidate.validate()
        if (validation is SettingsValidation.Invalid) {
            return statusFromObservation(ProtectionState.CONFIGURATION_ERROR, validation.message)
        }

        val wasRestricted = policyController.readLockTaskFeatures() == LockTaskMasks.PROTECTED

        // 2. Do not change an untouched, disabled device's original policy merely because its
        // schedule was edited. Release first only when an active/pending app session can own it.
        val baselineLifecycle = settingsRepository.readBaseline()?.lifecycle
        val temporaryMarker = settingsRepository.readTemporaryTestMarker()
        val activeJournal = current.enabled || current.recoveryRequired || temporaryMarker != null ||
            baselineLifecycle != null &&
            baselineLifecycle != com.example.shutdownprotection.data.BaselineLifecycleState.RESTORED
        val policyPresence = appImposedPolicyPresence()
        val policyMayBePresent = activeJournal || policyPresence != PolicyPresence.ABSENT
        if (policyPresence == PolicyPresence.UNKNOWN) {
            // Do not replace policy whose owner is uncertain. RecoveryManager re-reads the full
            // durable lifecycle and platform facts: it can preserve a demonstrably external
            // allowlist, while app-owned, unreadable, or active-session state still takes the
            // conservative release path and blocks this edit unless that release verifies.
            val recovery = recoveryManager.recover("EDIT_WITH_UNKNOWN_POLICY_OWNERSHIP")
            if (!recovery.verified) {
                return statusFromObservation(
                    state = ProtectionState.RECOVERY_FAILED,
                    detail = "The schedule edit was refused because policy ownership is unknown and " +
                        "release remains unverified at ${recovery.failedStep}: ${recovery.detail}",
                    recoveryIncompleteStep = recovery.failedStep,
                    userActionRequired = MANUAL_RECOVERY_ACTION,
                )
            }
        } else if (policyMayBePresent && policyController.isDeviceOwner() == true) {
            val permissive = policyController.applyFeatures(LockTaskMasks.ALLOWED)
            if (!permissive.submitted) {
                recoveryManager.recover("EDIT_PERMISSIVE_FAILED")
                return statusFromObservation(
                    ProtectionState.CONFIGURATION_ERROR,
                    "The permissive mask could not be applied before the edit (${permissive.failure}).",
                )
            }
        } else if (policyMayBePresent) {
            val recovery = recoveryManager.recover("EDIT_RELEASE_WITHOUT_OWNER")
            if (!recovery.verified) {
                return statusFromObservation(
                    ProtectionState.RECOVERY_FAILED,
                    "A session or app-imposed policy may still need release; schedule edit was refused " +
                        "while recovery remains unverified at ${recovery.failedStep}.",
                    recoveryIncompleteStep = recovery.failedStep,
                    userActionRequired = MANUAL_RECOVERY_ACTION,
                )
            }
        }

        // 3. Persist the edited times and the new revision atomically.
        val write = settingsRepository.editSettings { it ->
            it.copy(
                startMinuteOfDay = startMinuteOfDay,
                endMinuteOfDay = endMinuteOfDay,
            ).withRevisionBumped()
        }
        if (write is SettingsWriteResult.Failure) {
            recoveryManager.recover("EDIT_WRITE_FAILED")
            return statusFromObservation(
                ProtectionState.CONFIGURATION_ERROR,
                "The edited schedule could not be saved (${write.cause}).",
            )
        }

        recordDiagnostic(
            ProtectionTrigger.USER_EDIT,
            current.revision + 1,
            "schedule edited to ${candidate.startLabel}-${candidate.endLabel}",
        )

        // 4. Install the new plan and reconcile immediately from the new settings.
        val reconciled = reconcileLocked(ProtectionTrigger.USER_EDIT)

        // 5. Explain in the UI when an edit changes protection immediately.
        val nowRestricted = reconciled.state == ProtectionState.ARMED_POWER_MENU_RESTRICTED
        val explanation = when {
            !activeJournal -> "The schedule was saved."
            wasRestricted && !nowRestricted -> "The edit ended restriction immediately."
            !wasRestricted && nowRestricted -> "The edit started restriction immediately."
            else -> "The edit was applied to the current session."
        }
        return reconciled.copy(detail = "$explanation ${reconciled.detail}")
    }

    private suspend fun editAllowedPackagesLocked(packages: Set<String>): ProtectionStatus {
        // Fully disarm first, then save, then require explicit foreground resume.
        val recovery = recoveryManager.recover("ALLOWLIST_EDIT")
        val write = settingsRepository.editSettings { current ->
            current.copy(allowedPackages = packages).withRevisionBumped()
        }
        if (write is SettingsWriteResult.Failure) {
            return statusFromObservation(
                ProtectionState.CONFIGURATION_ERROR,
                "The allowed-application list could not be saved (${write.cause}).",
            )
        }
        recordDiagnostic(
            ProtectionTrigger.USER_EDIT,
            -1L,
            "allowed applications changed to ${packages.size} entr(ies)",
        )
        return statusFromObservation(
            state = if (recovery.verified) ProtectionState.DISARMED else ProtectionState.RECOVERY_FAILED,
            detail = "Allowed applications saved. Press resume to start a managed session with the new list.",
            recoveryIncompleteStep = recovery.failedStep,
            userActionRequired = "Press resume to start a managed session with the new list.",
        )
    }

    // ------------------------------------------------------------------
    // Observation helpers
    // ------------------------------------------------------------------

    /**
     * Whether a temporary debug test is genuinely live in THIS process.
     *
     * Deliberately shared by the reconciler, the observation refresh, the policy-failure handler and
     * the duplicate-start refusal (second review U05 step 1). A temporary test keeps the daily
     * preference false by design, so every path that reads `enabled` must consult this first — the
     * refresh previously reported `DISARMED` over a live restricted temporary session.
     */
    private suspend fun temporaryTestIsActive(): Boolean =
        pocActivatedInThisProcess && settingsRepository.readTemporaryTestMarker() != null

    /**
     * One shared derivation of observed session status, used for BOTH daily and temporary session
     * modes (second review U05 step 1).
     *
     * Derived from observations only; it writes no policy, so an exit callback can never recursively
     * re-arm (brief section 9). An unreadable runtime state is unknown, and unknown is never treated
     * as a real locked session.
     *
     * @param protectedExpected true when a restrictive mask is the expected state (daily mode inside
     *   the interval, or a temporary test under a forced-restriction override). A confirmed
     *   permissive mask then means "pending", not "allowed".
     */
    private fun observedSessionState(
        runtimeState: Int?,
        effectiveFeatures: Int?,
        protectedExpected: Boolean,
    ): ProtectionState = when {
        runtimeState == null || LockTaskRuntimeStates.isRealLocked(runtimeState) != true ->
            if (environment.isKeyguardLocked() == true || environment.isUserUnlocked() == false) {
                ProtectionState.WAITING_FOR_UNLOCK
            } else if (environment.isKeyguardLocked() == null || environment.isUserUnlocked() == null) {
                ProtectionState.POLICY_PENDING
            } else {
                ProtectionState.WAITING_FOR_SESSION
            }

        effectiveFeatures == LockTaskMasks.PROTECTED ->
            ProtectionState.ARMED_POWER_MENU_RESTRICTED

        // The permissive mask must ACTUALLY be in force before the menu is reported as allowed
        // (repair R05). A refresh previously reported "allowed" even while the restrictive mask was
        // still applied — the same false success claim the reconciler path had.
        effectiveFeatures == LockTaskMasks.ALLOWED ->
            if (protectedExpected) ProtectionState.POLICY_PENDING
            else ProtectionState.ARMED_POWER_MENU_ALLOWED

        else -> ProtectionState.POLICY_PENDING
    }

    private suspend fun refreshObservationLocked(): ProtectionStatus {
        val read = settingsRepository.readSettings()
        if (read is SettingsReadResult.Corrupt) {
            val runtime = readRuntimeStateOrNull()
            val releaseOwed = runtime == null || runtime != LockTaskRuntimeStates.NONE ||
                appImposedPolicyMayBePresent() || inhibitor.isInhibited
            val recovery = if (releaseOwed) {
                inhibitor.inhibit("Stored settings are unreadable while refreshing policy state")
                recoveryManager.recover("CORRUPT_SETTINGS_OBSERVATION")
            } else {
                null
            }
            return statusFromObservation(
                state = if (recovery == null || recovery.verified) {
                    ProtectionState.CONFIGURATION_ERROR
                } else {
                    ProtectionState.RECOVERY_FAILED
                },
                detail = if (recovery == null) {
                    "Stored settings could not be read; the observation is unavailable."
                } else if (recovery.verified) {
                    "Stored settings could not be read; an existing app-managed restriction was " +
                        "released without re-arming."
                } else {
                    "Stored settings could not be read; release was attempted but remains " +
                        "unverified at ${recovery.failedStep}."
                },
                recoveryIncompleteStep = recovery?.failedStep,
                userActionRequired = if (recovery?.verified == false) MANUAL_RECOVERY_ACTION else null,
            )
        }
        val settings = (read as? SettingsReadResult.Success)?.settings ?: ProtectionSettings.DEFAULT
        val deviceOwner = policyController.isDeviceOwner()
        if (deviceOwner == true) settingsRepository.markOwnerEstablished()
        // Read failures degrade to unknown, never to a value that could look verified, and must not
        // escape this path.
        val runtimeState = readRuntimeStateOrNull()
        val effectiveFeatures = runCatching { policyController.readLockTaskFeatures() }.getOrNull()
        val incidentState = try {
            settingsRepository.readRecoveryIncident()?.unresolved == true
        } catch (_: Throwable) { true }
        val insideInterval = read is SettingsReadResult.Success &&
            scheduleCalculator.membership(settings).protectedNow
        val temporaryTestActive = temporaryTestIsActive()

        // Derived from observations only. No policy is written here, so an exit callback can
        // never recursively re-arm (brief section 9).
        val state = when {
            read !is SettingsReadResult.Success -> ProtectionState.CONFIGURATION_ERROR

            // An unresolved recovery must stay visible (independent audit finding). These branches
            // previously fell through to DISARMED, which erased `recoveryIncompleteStep` and hid the
            // recovery cards while cleanup was still owed — and published DISARMED over a device
            // whose restrictive policy had not been released.
            settings.recoveryRequired || incidentState -> ProtectionState.RECOVERY_FAILED
            inhibitor.isInhibited -> ProtectionState.RECOVERY_FAILED
            deviceOwner == null -> ProtectionState.POLICY_PENDING
            runtimeState == null -> ProtectionState.POLICY_PENDING

            // A live temporary test keeps the daily preference false BY DESIGN, so `!enabled` must
            // not imply DISARMED (second review U05). This is a normal callback path, not damaged
            // storage: a successful policy callback invokes this refresh. The status is derived
            // from the observed runtime and mask, exactly as for a daily session.
            !settings.enabled && temporaryTestActive -> observedSessionState(
                runtimeState = runtimeState,
                effectiveFeatures = effectiveFeatures,
                protectedExpected = pocOverride == PocOverride.FORCE_RESTRICTED,
            )

            !settings.enabled && runtimeState != LockTaskRuntimeStates.NONE -> ProtectionState.POLICY_PENDING
            !settings.enabled -> ProtectionState.DISARMED
            deviceOwner == false -> ProtectionState.NOT_DEVICE_OWNER
            else -> observedSessionState(
                runtimeState = runtimeState,
                effectiveFeatures = effectiveFeatures,
                protectedExpected = insideInterval,
            )
        }

        val detail = when (state) {
            ProtectionState.ARMED_POWER_MENU_RESTRICTED ->
                "Runtime state refreshed from observation only; global actions are restricted"
            ProtectionState.ARMED_POWER_MENU_ALLOWED ->
                "Runtime state refreshed from observation only; the permissive mask is in force"
            ProtectionState.POLICY_PENDING ->
                "Runtime state refreshed from observation only; the policy is not confirmed " +
                    "(observed features=$effectiveFeatures)"
            else -> "Runtime state refreshed from observation only"
        }

        return statusFromObservation(
            state = state,
            detail = detail,
            // Derived from the same intent the reconciler uses, so the POC screen can still
            // show requested-versus-effective after an observation-only refresh. Without
            // this, a lock-task enter/exit callback would blank the mask readback.
            requestedFeatures = when {
                read !is SettingsReadResult.Success -> null
                !settings.enabled -> null
                pocOverride == PocOverride.FORCE_RESTRICTED -> LockTaskMasks.PROTECTED
                pocOverride == PocOverride.FORCE_ALLOWED -> LockTaskMasks.ALLOWED
                insideInterval -> LockTaskMasks.PROTECTED
                else -> LockTaskMasks.ALLOWED
            },
            effectiveFeatures = effectiveFeatures,
            insideProtectedInterval = insideInterval,
            // Uses the SAME evidence standard as the reconciler (independent audit finding). It
            // previously trusted revision + tokens alone, so the field that gates the literal
            // "Restricted" label could be satisfied by weaker evidence than the coordinator accepts.
            releasePlanSubmitted = planSubmittedInThisProcess &&
                settingsRepository.readScheduleReceipt()?.let { receipt ->
                    receipt.revision == settings.revision &&
                        receipt.bootGeneration == settingsRepository.readBootGeneration() &&
                        scheduleManager.hasPlanPendingIntentTokens() == true
                } ?: false,
        )
    }

    /**
     * Shared daily/temporary entry guard. It runs before any marker, baseline, alarm or policy
     * mutation. Unknown runtime or policy evidence refuses entry and takes the release path.
     */
    private suspend fun preEntryRestrictionGuard(reason: String): ProtectionStatus? {
        val settingsRead = settingsRepository.readSettings()
        val settings = (settingsRead as? SettingsReadResult.Success)?.settings
        val baselineRead = try {
            true to settingsRepository.readBaseline()
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Throwable) {
            false to null
        }
        val markerRead = try {
            true to settingsRepository.readTemporaryTestMarker()
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Throwable) {
            false to null
        }
        val incidentRead = try {
            true to settingsRepository.readRecoveryIncident()
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Throwable) {
            false to null
        }
        val corruption = try { settingsRepository.isCorruptionFlagged() } catch (_: Throwable) { true }
        val owner = policyController.isDeviceOwner()
        val runtime = readRuntimeStateOrNull()
        val ownerHistory = try { settingsRepository.isOwnerEstablished() } catch (_: Throwable) { true }
        val cleanFirstUseWithoutOwner = owner == false && ownerHistory == false &&
            settings != null && !settings.enabled && !settings.recoveryRequired &&
            baselineRead.first && baselineRead.second == null &&
            markerRead.first && markerRead.second == null &&
            incidentRead.first && incidentRead.second == null && !corruption &&
            runtime == LockTaskRuntimeStates.NONE

        val pendingIncident = !incidentRead.first || incidentRead.second?.unresolved == true
        val pendingMarker = !markerRead.first || markerRead.second != null
        val pendingBaseline = !baselineRead.first || baselineRead.second?.lifecycle !=
            com.example.shutdownprotection.data.BaselineLifecycleState.RESTORED && baselineRead.second != null
        val runtimeUnknownOrActive = runtime == null || LockTaskRuntimeStates.isRealLocked(runtime) == true
        val policyMayBePresent = appImposedPolicyMayBePresent()
        if (cleanFirstUseWithoutOwner) return null
        if (!runtimeUnknownOrActive && !pendingIncident && !pendingMarker && !pendingBaseline && !corruption &&
            settings != null && !settings.recoveryRequired && !policyMayBePresent
        ) return null

        inhibitor.inhibit("Entry refused because a session or unresolved policy may already exist")
        val recovery = recoveryManager.recover(reason)
        val baseline = baselineRead.second
        val noSavedBaseline = baselineRead.first && baseline == null
        return statusFromObservation(
            state = if (recovery.verified) ProtectionState.DISARMED else ProtectionState.RECOVERY_FAILED,
            detail = buildString {
                append("A prior or uncertain lock-task session/policy was detected before entry. ")
                if (noSavedBaseline) {
                    append("No original baseline was saved, so original-policy restoration cannot be claimed. ")
                }
                append("Entry was refused; release is ")
                append(if (recovery.verified) "verified." else "unverified at ${recovery.failedStep}.")
            },
            recoveryIncompleteStep = recovery.failedStep,
            userActionRequired = if (recovery.verified) null else MANUAL_RECOVERY_ACTION,
        )
    }

    /** Tri-state answer to "is the app's own policy still applied?" (review-2 section 4.3). */
    private enum class PolicyPresence { PRESENT, ABSENT, UNKNOWN }

    /**
     * Whether the app's own policy is still applied, as a **tri-state** (review-2 section 4.3).
     *
     * This previously returned plain `false` whenever the baseline or a readback was unavailable,
     * so missing evidence was silently treated as proof that no cleanup was owed. Unknown is now
     * reported as unknown, and callers that decide whether to release use
     * [appImposedPolicyMayBePresent] so absence of evidence is never read as evidence of absence.
     *
     * The distinction that matters: a missing baseline is only *unknown* when the observed policy is
     * NOT the documented default. On a clean, never-armed device the observed policy is default, so
     * nothing of ours can be in force and a legitimate first activation must not be refused.
     */
    private suspend fun appImposedPolicyPresence(): PolicyPresence {
        // An unreadable policy is unknown, never "clean".
        val features = try { policyController.readLockTaskFeatures() } catch (_: Throwable) { null }
            ?: return PolicyPresence.UNKNOWN
        val packages = try { policyController.readLockTaskPackages() } catch (_: Throwable) { null }
            ?: return PolicyPresence.UNKNOWN

        val baseline = try { settingsRepository.readBaseline() } catch (_: Throwable) {
            return PolicyPresence.UNKNOWN
        }
        if (baseline == null) {
            // Without a baseline there is no trustworthy proof of who last changed policy. Only a
            // documented permissive mask with an empty allowlist is provably nonrestrictive; other
            // known combinations remain UNKNOWN so the entry guard or recovery path can preserve
            // clearly external policy or conservatively release ambiguous app-created state.
            //
            // CORRECTED AFTER ADVERSARIAL REVIEW (verify3-b P1). Treating any non-zero mask as
            // UNKNOWN here permanently bricked the app on a clean device: `editScheduleLocked`
            // legitimately applies the PERMISSIVE mask (63) whenever the app is Device Owner, before
            // any baseline exists. That made `appImposedPolicyMayBePresent()` true, so every
            // reconcile ran recovery, which could not restore a baseline that never existed, so the
            // inhibitor was never cleared and arming was refused forever — while falsely claiming
            // "a managed session is already active" over a runtime state of NONE.
            //
            // A permissive mask with no allowlist is not an app-imposed restriction, so it is
            // ABSENT. Other combinations stay UNKNOWN here so the shared entry guard refuses to
            // replace them. RecoveryManager may separately preserve a known external allowlist
            // only after proving there is no app-session or recovery history and this app is not
            // on that allowlist.
            val permissive = features in CLEAN_NONRESTRICTIVE_MASKS
            return if (permissive && packages.isEmpty()) {
                PolicyPresence.ABSENT
            } else {
                PolicyPresence.UNKNOWN
            }
        }
        if (baseline.lifecycle == com.example.shutdownprotection.data.BaselineLifecycleState.RESTORED) {
            // A completed session no longer owns subsequent external policy changes.
            return PolicyPresence.ABSENT
        }
        return if (features != baseline.lockTaskFeatures || packages != baseline.lockTaskPackages) {
            PolicyPresence.PRESENT
        } else {
            PolicyPresence.ABSENT
        }
    }

    /**
     * True unless the app's own policy is **provably** absent.
     *
     * Used by every "is cleanup owed?" decision, so an unreadable baseline or policy readback keeps
     * the release path alive rather than being mistaken for a clean state.
     */
    private suspend fun appImposedPolicyMayBePresent(): Boolean =
        appImposedPolicyPresence() != PolicyPresence.ABSENT

    /**
     * An active schedule may restrict on a later alarm, so its original policy must still be
     * trustworthy and belong to the currently prepared session. A retained RESTORED baseline is
     * historical evidence only; it cannot authorize a new session.
     */
    private suspend fun activeSessionBaselineProblem(): String? {
        val baseline = try {
            settingsRepository.readBaseline()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Throwable) {
            return "the stored baseline could not be read"
        } ?: return "the stored baseline is missing"

        baseline.invalidReasonFor(applicationId)?.let { return it }
        if (!baseline.isTrustedOriginal) return "the stored baseline has unverified provenance"
        if (baseline.lifecycle != com.example.shutdownprotection.data.BaselineLifecycleState.PREPARED) {
            return "the stored baseline is not prepared for an active session"
        }
        return null
    }

    /** Result of one mask submission, with the fence state that produced it. */
    private data class MaskOutcome(
        val applied: PolicyOperationResult,
        val alreadySubmitted: Boolean,
    )

    /**
     * Applies a mask without re-submitting an unchanged setter for the same revision
     * (brief section 12, repair R01 step 8).
     *
     * The revision is the fence. A previous submission is only reused when it belongs to exactly
     * this revision and mask, so stale work from an earlier revision cannot be mistaken for current
     * state and cannot silently undo a newer release.
     */
    private suspend fun applyMaskWithFence(mask: Int, revision: Long): MaskOutcome {
        currentCoroutineContext().ensureActive()
        if (mask == LockTaskMasks.PROTECTED && inhibitor.isInhibited) {
            return MaskOutcome(
                applied = PolicyOperationResult.Rejected(
                    IllegalStateException("A release-only inhibitor is active"),
                    policyController.readSnapshot(),
                ),
                alreadySubmitted = false,
            )
        }
        val prior = lastSubmission
        val alreadySubmitted = prior != null && prior.revision == revision && prior.mask == mask
        val applied = if (alreadySubmitted) {
            PolicyOperationResult.Applied(
                readback = policyController.readSnapshot(),
                verified = policyController.readLockTaskFeatures() == mask,
            )
        } else {
            policyController.applyFeatures(mask).also { result ->
                lastSubmission = PolicySubmission(revision = revision, mask = mask, verified = result.verified)
            }
        }
        return MaskOutcome(applied, alreadySubmitted)
    }

    /** Reads the runtime lock-task state, or null when it could not be read (unknown, not NONE). */
    private fun readRuntimeStateOrNull(): Int? =
        runCatching { policyController.readRuntimeLockTaskState() }.getOrNull()

    /**
     * Bounded, best-effort release used by the timeout handler: request the permissive mask, stop
     * the session if one is locked, and report whether the permissive mask was confirmed.
     *
     * Deliberately does not call the public `reconcile`, which would try to re-acquire the mutex
     * the caller already holds.
     */
    private suspend fun boundedReleaseAttempt(): Boolean {
        val applied = try {
            policyController.applyFeatures(LockTaskMasks.ALLOWED)
        } catch (_: Throwable) {
            null
        }
        val confirmed = applied is PolicyOperationResult.Applied && applied.verified
        // Independent exits: unknown runtime, missing authority, or a failed mask setter cannot
        // suppress the activity stop and allowlist cleanup attempts.
        try { lockTaskSession.requestStopLockTask() } catch (_: Throwable) { }
        try { policyController.applyAllowedPackages(emptySet()) } catch (_: Throwable) { }
        return confirmed
    }

    /**
     * The coordinator lock wait exceeded its budget (repair R08 step 3; corrected after independent
     * audit).
     *
     * The audit found that this path previously only recorded a diagnostic and returned a
     * `POLICY_PENDING` label with `publish = false` — no inhibitor, no durable marker, no release
     * attempt, no reschedule. For a **one-shot release alarm** (END / RELEASE_FALLBACK /
     * TEMPORARY_TEST_RELEASE) that consumed the opportunity and left the restriction in place with
     * no release attempt, which is precisely the failure mode R01's pass condition forbids.
     *
     * What it does now, without taking the lock (a policy write here would violate serialization):
     *  - engages the in-process inhibitor, which is a volatile flag and safe to set unlocked;
     *  - submits a bounded, release-only retry so a one-shot release opportunity is not lost;
     *  - reports `RECOVERY_FAILED` rather than a pending label, so the condition is visible.
     *
     * The status is still not published: a newer pass may be completing under the lock, and
     * publishing from here would overwrite a fresher, correct status.
     */
    private suspend fun lockWaitTimedOut(trigger: ProtectionTrigger): ProtectionStatus {
        val inhibition = inhibitor.inhibit("Coordinator lock wait exceeded ${LOCK_WAIT_BUDGET_MILLIS}ms (${trigger.wireName})")

        val incidentResult = try {
            withTimeoutOrNull(TIMEOUT_BOOKKEEPING_BUDGET_MILLIS) {
                // Durable release-only bookkeeping does not require a live owner-authority read.
                recoveryManager.openReleaseOnlyIncident(
                    reason = "coordinator lock wait exceeded (${trigger.wireName})",
                    inhibitionToken = inhibition,
                )
            }
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (failure: Throwable) {
            ReleaseIncidentOpenResult.Failed(
                "Release-only incident handling failed (${failure.javaClass.simpleName}).",
            )
        }
        val retryScheduled = incidentResult is ReleaseIncidentOpenResult.Scheduled
        val obsolete = incidentResult == ReleaseIncidentOpenResult.ObsoleteClean
        val detail = "The reconciliation could not start within its budget because another " +
            "operation held the coordinator lock. " +
           "This is not a success."
        val resolvedDetail = when {
            obsolete -> "Fresh evidence showed that verified cleanup had already completed. " + detail
            retryScheduled -> "A matching release-only retry was scheduled. " + detail
            else -> "New restriction remains inhibited because a matching release-only retry was not verified. " + detail
        }
        val observedStatus = try {
            withTimeoutOrNull(TIMEOUT_STATUS_BUDGET_MILLIS) {
                recordDiagnostic(
                    trigger,
                    -1L,
                    "coordinator lock wait exceeded ${LOCK_WAIT_BUDGET_MILLIS}ms; result=$incidentResult",
                )
                statusFromObservation(
                    state = ProtectionState.RECOVERY_FAILED,
                    detail = resolvedDetail,
                    recoveryIncompleteStep = "LOCK_WAIT_TIMEOUT",
                    userActionRequired = MANUAL_RECOVERY_ACTION,
                    publish = false,
                )
            }
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Throwable) {
            null
        }
        return observedStatus ?: emergencyStatus(
            trigger,
            "$resolvedDetail Fresh status observations failed; no success state is claimed.",
            "LOCK_WAIT_TIMEOUT",
            publish = false,
        )
    }

    /**
     * Runs optional work (diagnostics) without swallowing cancellation.
     *
     * `runCatching` catches `Throwable`, which **includes** `CancellationException`. Using it around
     * a suspend call would convert a cancelled scope into a silent success and break structured
     * concurrency, so cancellation is rethrown here and only genuine failures are contained
     * (repair R08 step 7).
     */
    private suspend fun optional(block: suspend () -> Unit) {
        try {
            block()
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Throwable) {
            // Optional work: a failure here must never block or determine release.
        }
    }

    private suspend fun awaitLockedSession(): Boolean {
        var poll = 0
        while (poll < SESSION_ENTRY_MAX_POLLS) {
            if (LockTaskRuntimeStates.isRealLocked(policyController.readRuntimeLockTaskState()) == true) {
                return true
            }
            poll++
            if (poll < SESSION_ENTRY_MAX_POLLS) sleep(SESSION_ENTRY_POLL_MILLIS)
        }
        return LockTaskRuntimeStates.isRealLocked(policyController.readRuntimeLockTaskState()) == true
    }

    /**
     * Builds a status from a fresh observation, writes the bounded diagnostic observation,
     * and publishes it. Every public path returns through here, so the reported state is
     * always tied to a read of the device rather than to an intention.
     */
    private suspend fun statusFromObservation(
        state: ProtectionState,
        detail: String,
        requestedFeatures: Int? = null,
        effectiveFeatures: Int? = null,
        insideProtectedInterval: Boolean? = null,
        releasePlanSubmitted: Boolean = false,
        recoveryIncompleteStep: String? = null,
        userActionRequired: String? = null,
        /**
         * False for statuses built while another pass may be completing under the lock, so a stale
         * answer cannot overwrite a fresher one (repair R08 step 3).
         */
        publish: Boolean = true,
    ): ProtectionStatus {
        val read = settingsRepository.readSettings()
        val settings = (read as? SettingsReadResult.Success)?.settings
        val deviceOwner = policyController.isDeviceOwner()
        // Unknown stays unknown (review-2 section 4.1): substituting NONE or an empty set made a
        // failed read indistinguishable from a genuinely normal state.
        val lockTaskState = try { policyController.readRuntimeLockTaskState() } catch (_: Throwable) { null }
        val effectivePackages = try { policyController.readLockTaskPackages() } catch (_: Throwable) { null }
        val receipt = settingsRepository.readScheduleReceipt()
        val ownerEverEstablished = settingsRepository.isOwnerEstablished()
        val exactCapability = scheduleManager.canScheduleExactAlarms()
        val incidentState = settingsRepository.readRecoveryIncident()?.unresolved == true
        val policyResult = lastPolicyResult.value
        val successStates = setOf(
            ProtectionState.DISARMED,
            ProtectionState.ARMED_POWER_MENU_ALLOWED,
            ProtectionState.ARMED_POWER_MENU_RESTRICTED,
        )
        val pendingRecovery = inhibitor.isInhibited || settings?.recoveryRequired == true || incidentState
        val effectiveState = when {
            settings == null && state !in setOf(ProtectionState.CONFIGURATION_ERROR, ProtectionState.RECOVERY_FAILED) ->
                ProtectionState.RECOVERY_FAILED
            pendingRecovery && state in successStates ->
                ProtectionState.RECOVERY_FAILED
            deviceOwner == null && state in setOf(
                ProtectionState.DISARMED,
                ProtectionState.ARMED_POWER_MENU_ALLOWED,
                ProtectionState.ARMED_POWER_MENU_RESTRICTED,
            ) -> ProtectionState.POLICY_PENDING
            lockTaskState == null && state in setOf(
                ProtectionState.DISARMED,
                ProtectionState.ARMED_POWER_MENU_ALLOWED,
                ProtectionState.ARMED_POWER_MENU_RESTRICTED,
            ) -> ProtectionState.POLICY_PENDING
            state in setOf(
                ProtectionState.ARMED_POWER_MENU_ALLOWED,
                ProtectionState.ARMED_POWER_MENU_RESTRICTED,
            ) && lockTaskState != LockTaskRuntimeStates.LOCKED -> ProtectionState.POLICY_PENDING
            state == ProtectionState.DISARMED && lockTaskState != LockTaskRuntimeStates.NONE ->
                ProtectionState.POLICY_PENDING
            else -> state
        }
        val effectiveDetail = when {
            settings == null && effectiveState == ProtectionState.RECOVERY_FAILED ->
                "$detail Stored settings could not be verified, so this is not a successful state."
            pendingRecovery && effectiveState == ProtectionState.RECOVERY_FAILED && state in successStates ->
                "$detail A release-only recovery remains pending, so this is not reported as success."
            effectiveState != state && deviceOwner == null ->
                "$detail Device Owner authority is unknown; no definitive state is reported."
            effectiveState != state ->
                "$detail Runtime lock-task state is unknown or no longer active; no success state is reported."
            else -> detail
        }
        val effectiveRecoveryStep = recoveryIncompleteStep ?: when {
            effectiveState == ProtectionState.RECOVERY_FAILED && pendingRecovery && state in successStates ->
                if (inhibitor.isInhibited) "RESTRICTION_INHIBITED" else "RECOVERY_PENDING"
            effectiveState != state -> "PLATFORM_STATE_UNKNOWN"
            else -> null
        }

        val status = ProtectionStatus(
            state = effectiveState,
            detail = effectiveDetail,
            settingsRevision = settings?.revision ?: -1L,
            observedAtEpochMillis = clockMillis(),
            deviceOwner = deviceOwner,
            ownerEverEstablished = ownerEverEstablished,
            lockTaskState = lockTaskState,
            requestedFeatures = requestedFeatures,
            effectiveFeatures = effectiveFeatures,
            effectivePackages = effectivePackages,
            insideProtectedInterval = insideProtectedInterval,
            exactAlarmCapability = exactCapability,
            releasePlanSubmittedForCurrentRevision = releasePlanSubmitted,
            recoveryIncompleteStep = effectiveRecoveryStep,
            userActionRequired = userActionRequired ?: if (effectiveRecoveryStep != null) MANUAL_RECOVERY_ACTION else null,
        )

        optional {
            diagnostics.writeObservation(
                RuntimeObservation(
                    observedAtEpochMillis = status.observedAtEpochMillis,
                    stateName = effectiveState.wireName,
                    settingsRevision = settings?.revision ?: -1L,
                    deviceOwner = deviceOwner,
                    lockTaskState = lockTaskState,
                    requestedFeatures = requestedFeatures,
                    effectiveFeatures = effectiveFeatures,
                    effectivePackages = effectivePackages,
                    exactAlarmCapability = exactCapability,
                    bootCompleted = null,
                    userUnlocked = environment.isUserUnlocked(),
                    nextStartEpochMillis = receipt?.nextStartEpochMillis,
                    nextEndEpochMillis = receipt?.nextEndEpochMillis,
                    fallbackEpochMillis = receipt?.fallbackEpochMillis,
                    lastPolicyResultCode = policyResult?.resultCode,
                    lastPolicyObservedAtEpochMillis = policyResult?.observedAtEpochMillis,
                    recoveryStatus = effectiveRecoveryStep ?: if (settings == null) "UNKNOWN" else if (settings.recoveryRequired || incidentState) "PENDING" else "CLEAR",
                ),
            )
        }

        // Lock-wait fallback runs without the mutex by design. Recheck its shared fence after all
        // suspending status/diagnostic reads so this older pass cannot publish restricted success.
        val finalStatus = if (inhibitor.isInhibited &&
            status.state in successStates
        ) {
            status.copy(
                state = ProtectionState.RECOVERY_FAILED,
                detail = "A release-only inhibitor was engaged during status construction; " +
                    "the current state is not reported as success.",
                recoveryIncompleteStep = status.recoveryIncompleteStep ?: "RESTRICTION_INHIBITED",
                userActionRequired = status.userActionRequired ?: MANUAL_RECOVERY_ACTION,
            )
        } else status
        if (publish) mutableStatus.value = finalStatus
        return finalStatus
    }

    /**
     * Bounded safety response to a timeout (repair R01 step 7).
     *
     * Previously this only published a `POLICY_PENDING` label: a code path could turn a timeout
     * into a harmless-looking pending state while leaving the app's unresolved restriction in place.
     * It now does what the failure preference requires (brief section 3.5) — inhibit new
     * restriction, persist disabled intent with the recovery marker set, attempt a **bounded**
     * permissive release, and report the incomplete step.
     *
     * Notes on honesty (guide R01 step 10): a coroutine deadline bounds cooperative suspension. It
     * does not forcibly interrupt every synchronous Binder call or filesystem operation, so this is
     * a best-effort release under its own explicit budget — **not** an Android release deadline.
     * The cleanup runs under [TIMEOUT_CLEANUP_BUDGET_MILLIS] and can never be unbounded.
     */
    private suspend fun timedOutLocked(trigger: ProtectionTrigger): ProtectionStatus {
        val inhibition = inhibitor.inhibit("Reconciliation timed out (${trigger.wireName})")
        lastSubmission = null

        // RELEASE FIRST (second review U02). The durable bookkeeping used to run before this, so a
        // suspended settings writer consumed the entire handler budget and NO policy or session
        // release was ever attempted — the device stayed restricted and the app reported a
        // harmless-looking timeout. Persistence must never gate the only opportunity to restore
        // controls.
        var cleanupCompleted = false
        var releaseConfirmed = false
        val cleanup = withTimeoutOrNull(TIMEOUT_CLEANUP_BUDGET_MILLIS) {
            releaseConfirmed = boundedReleaseAttempt()
            cleanupCompleted = true
        }
        if (cleanup == null) cleanupCompleted = false

        // Bookkeeping SECOND, under its own finite deadline. The recovery manager persists the
        // matching unresolved incident and release-only flags before installing an identity-bound
        // retry; an in-memory attempt count is never enough to schedule another delivery.
        val incidentResult = withTimeoutOrNull(TIMEOUT_BOOKKEEPING_BUDGET_MILLIS) {
            recoveryManager.openReleaseOnlyIncident(
                reason = "coordinator deadline (${trigger.wireName})",
                inhibitionToken = inhibition,
            )
        }
        val retryScheduled = incidentResult is ReleaseIncidentOpenResult.Scheduled
        val obsolete = incidentResult == ReleaseIncidentOpenResult.ObsoleteClean

        val detail = buildString {
            append("The reconciliation did not finish within ${operationTimeoutMillis}ms; ")
            append("the reported state is not a success. ")
            append(
                if (cleanupCompleted) "A bounded release attempt completed"
                else "The bounded release attempt did not finish within ${TIMEOUT_CLEANUP_BUDGET_MILLIS}ms",
            )
            append(
                if (releaseConfirmed) " and the permissive mask was confirmed."
                else " and the permissive mask could not be confirmed.",
            )
            append(
                if (obsolete) {
                    " A fresh durable read proved that cleanup already completed; the timeout fallback was obsolete."
                } else if (retryScheduled) {
                    " A matching durable release incident and retry were recorded."
                } else {
                    " A matching durable release incident or retry could not be verified; " +
                        "new restriction stays inhibited in this process."
                },
            )
        }
        return withTimeoutOrNull(TIMEOUT_STATUS_BUDGET_MILLIS) {
            recordDiagnostic(trigger, -1L, detail)
            statusFromObservation(
                state = if (releaseConfirmed) ProtectionState.RECOVERING else ProtectionState.RECOVERY_FAILED,
                detail = detail,
                recoveryIncompleteStep = "TIMEOUT_CLEANUP",
                userActionRequired = MANUAL_RECOVERY_ACTION,
            )
        } ?: emergencyStatus(trigger, detail, "TIMEOUT_CLEANUP")
    }

    private suspend fun recordDiagnostic(trigger: ProtectionTrigger, revision: Long, message: String) {
        // Optional diagnostics. Cancellation is rethrown, never converted into a silent success
        // (repair R08 step 7).
        optional { diagnostics.record(trigger.wireName, revision, message) }
    }

    companion object {
        /** End-to-end coordinator budget stays under the 8-second receiver envelope. */
        const val DEFAULT_OPERATION_TIMEOUT_MILLIS: Long = 2_300L

        /** Arm/temporary activation also gets a short, finite LOCKED verification window. */
        const val DEFAULT_ARM_TIMEOUT_MILLIS: Long = 3_200L

        const val SESSION_ENTRY_POLL_MILLIS: Long = 200L

        /**
         * Explicit budget for the timeout handler's cleanup. Kept separate from the operation
         * deadline so cleanup can never be unbounded, and small enough that a broadcast handler
         * still finishes inside its own budget (guide R01 step 9).
         */
        const val TIMEOUT_CLEANUP_BUDGET_MILLIS: Long = 600L

        /**
         * Budget for the timeout handler's durable bookkeeping, kept separate from the release
         * budget (second review U02). A suspended settings writer must be able to delay this write
         * but must never be able to prevent the release attempt above it.
         */
        const val TIMEOUT_BOOKKEEPING_BUDGET_MILLIS: Long = 350L

        /** One shared finalization budget for optional logging plus fresh status reads. */
        const val TIMEOUT_STATUS_BUDGET_MILLIS: Long = 200L

        /**
         * Explicit "the runtime lock-task state could not be read" sentinel for diagnostic records
         * (review-2 section 4.1). Matches the value the diagnostic read side already defaults to.
         */
        const val UNKNOWN_RUNTIME_STATE: Int = -1

        /**
         * Additional budget allowed for **waiting on the coordinator lock** before an operation's
         * own deadline starts (repair R08 step 3). `goAsync()` does not create unlimited execution
         * time, so a release event must never wait indefinitely behind queued work.
         */
        const val LOCK_WAIT_BUDGET_MILLIS: Long = 400L

        /** Bounded boot-generation edit, included in the same receiver-safe envelope. */
        const val BOOT_OPERATION_BUDGET_MILLIS: Long = 1_350L
        const val BOOT_STORE_BUDGET_MILLIS: Long = 700L

        private val CLEAN_NONRESTRICTIVE_MASKS = setOf(0, 16, LockTaskMasks.ALLOWED)

        /**
         * Five polls, not fifteen: the platform reports `LOCK_TASK_MODE_LOCKED` promptly, and this
         * wait happens with the coordinator's critical section held, so it is kept as short as the
         * verification allows (brief section 12: "Keep the critical section short").
         */
        const val SESSION_ENTRY_MAX_POLLS: Int = 5

        /** Brief section 22 step 6: a minimal temporary release broadcast five minutes later. */
        const val POC_RELEASE_MILLIS: Long = 5L * 60L * 1000L

        const val MANUAL_RECOVERY_ACTION: String =
            "Use Restore Normal Device Mode, or the documented development recovery command in docs/RECOVERY.md."
    }
}

/** One policy submission, keyed by the revision and mask it was made for. */
private data class PolicySubmission(
    val revision: Long,
    val mask: Int,
    val verified: Boolean,
)

/**
 * A recorded policy callback result (brief section 18: record the policy identifier, target
 * user, result code, observation time, and the current settings revision).
 */
data class PolicyResultObservation(
    val identifier: String,
    val resultCode: Int?,
    val observedAtEpochMillis: Long,
    val settingsRevision: Long,
)

/** Convenience for the UI gate: derive the claim inputs from an observed status. */fun ProtectionStatus.toPowerMenuClaimInputs(): PowerMenuClaimInputs = PowerMenuClaimInputs(
    enabledIntentCurrentAndValid = state != ProtectionState.DISARMED &&
        state != ProtectionState.CONFIGURATION_ERROR &&
        state != ProtectionState.NOT_DEVICE_OWNER,
    currentlyInsideProtectedInterval = insideProtectedInterval == true,
    deviceOwnerPresent = deviceOwner == true,
    // An unknown runtime state is NOT a locked session, so it cannot support a "Restricted" claim.
    runtimeStateLocked = LockTaskRuntimeStates.isRealLocked(lockTaskState) == true,
    effectivePolicyMatchesRequirement = effectiveFeatures == LockTaskMasks.PROTECTED,
    releasePlanSubmittedForCurrentRevision = releasePlanSubmittedForCurrentRevision,
    noUnresolvedPolicyOrRecoveryFailure = recoveryIncompleteStep == null &&
        state != ProtectionState.RECOVERY_FAILED &&
        state != ProtectionState.POLICY_PENDING,
)
