package com.example.shutdownprotection.scheduling

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import com.example.shutdownprotection.data.ScheduleReceipt
import java.time.Instant

/**
 * Outcome of one attempt to install the scheduling plan.
 *
 * [receipt] is populated only when every required call succeeded. It records what was
 * *submitted*; it is not a guarantee that Android will deliver it (brief section 14.5).
 */
data class AlarmInstallResult(
    val exactCapability: Boolean?,
    val endSubmitted: Boolean,
    val startSubmitted: Boolean,
    val fallbackSubmitted: Boolean,
    val receipt: ScheduleReceipt?,
    val failure: Throwable?,
) {
    /** Every primary boundary plus the fallback is in place. */
    val complete: Boolean
            get() = exactCapability == true && endSubmitted && startSubmitted && fallbackSubmitted && failure == null
}

/** One alarm identity that was cancelled, for diagnostics. */
data class CancelledAlarm(val kind: AlarmEventKind, val existed: Boolean?)

/**
 * The scheduling seam.
 *
 * Exists so coordinator and recovery logic can be exercised with a controlled scheduling
 * implementation. Android framework interactions are separately exercised under Robolectric.
 */
interface SchedulingGateway {
    fun canScheduleExactAlarms(): Boolean?
    fun cancelAll(): List<CancelledAlarm>
    fun cancel(kind: AlarmEventKind): CancelledAlarm

    /**
     * True when a matching PendingIntent **token** exists for the three primary alarm identities.
     *
     * This is a token lookup, **not** an AlarmManager inventory query (repair R07). It is used only
     * as a negative signal; a positive result does not prove an alarm is scheduled.
     */
    fun hasPlanPendingIntentTokens(): Boolean?

    fun installPlan(
        nextEnd: Instant,
        nextStart: Instant,
        revision: Long,
        bootGeneration: Long,
    ): AlarmInstallResult
    fun installTemporaryRelease(releaseAt: Instant, revision: Long, sessionId: String?): AlarmInstallResult
    fun installRecoveryRetry(incidentId: String, triggerAt: Instant): Boolean
}

/**
 * Owns every `AlarmManager` interaction (brief section 14).
 *
 * Rules enforced here:
 *  - one-shot `RTC_WAKEUP` only; never repeating alarms, never an in-process timer
 *  - `setExactAndAllowWhileIdle()` for the primary boundaries
 *  - a distinct **inexact** `setAndAllowWhileIdle()` reconciliation alarm as the
 *    best-effort release fallback
 *  - `setAlarmClock()` is deliberately never used
 *  - the end alarm is installed before the start alarm, and the fallback last, so an
 *    already-restricted session never loses its submitted release path
 *  - scheduling exceptions, including `SecurityException`, are caught and reported
 *
 * This class does not touch storage: the caller persists the receipt only after the calls
 * succeed.
 */
class ScheduleManager(
    context: Context,
    private val applicationId: String,
    private val alarmManagerProvider: () -> AlarmManager? = {
        context.applicationContext.getSystemService(AlarmManager::class.java)
    },
    private val clockMillis: () -> Long = { System.currentTimeMillis() },
) : SchedulingGateway {

    private val appContext: Context = context.applicationContext

    /**
     * Evaluated rather than inferred from installation, a permission declaration, or Device
     * Owner status (brief section 15.1).
     */
    override fun canScheduleExactAlarms(): Boolean? {
        val manager = readAlarmManager().first ?: return null
        return readCapability(manager).value
    }

    /** Cancels every app-owned alarm identity. Returns what was actually found. */
    override fun cancelAll(): List<CancelledAlarm> = AlarmEventKind.entries.map { cancel(it) }

    override fun cancel(kind: AlarmEventKind): CancelledAlarm {
        val manager = readAlarmManager().first
        val existing = try {
            existingPendingIntent(kind)
        } catch (_: Throwable) {
            return CancelledAlarm(kind, existed = null)
        } ?: return CancelledAlarm(kind, existed = manager?.let { false })
        // Always invalidate the token even when the AlarmManager service is unavailable. The
        // framework may retain an alarm record, but a cancelled PendingIntent cannot deliver it.
        val managerFailure: Throwable? = if (manager == null) {
            IllegalStateException("AlarmManager service is unavailable")
        } else {
            runCatching { manager.cancel(existing) }.exceptionOrNull()
        }
        val tokenFailure = runCatching { existing.cancel() }.exceptionOrNull()
        return if (managerFailure == null && tokenFailure == null) {
            CancelledAlarm(kind, existed = true)
        } else {
            CancelledAlarm(kind, existed = null)
        }
    }

    /**
     * The three identities [installPlan] submits. `RECOVERY_RETRY` is excluded on purpose: it is
     * only installed while an unresolved cleanup incident exists, so requiring it would force a
     * needless reinstall on every pass.
     *
     * **What this answers:** whether a matching PendingIntent *token* exists
     * (`FLAG_NO_CREATE`). It is a token lookup, **not** an AlarmManager inventory query, so a
     * positive result does not prove an alarm is scheduled. It is retained as a cheap negative
     * signal only — when the tokens are gone (a force-stop removes them) the plan certainly needs
     * resubmitting (repair R07).
     */
    override fun hasPlanPendingIntentTokens(): Boolean? {
        if (readAlarmManager().first == null) return null
        return try {
            PLAN_IDENTITIES.all { existingPendingIntent(it) != null }
        } catch (_: Throwable) {
            null
        }
    }

    /**
     * Installs the plan for the current revision: end first, then the next start, then the
     * separate inexact fallback.
     */
    override fun installPlan(
        nextEnd: Instant,
        nextStart: Instant,
        revision: Long,
        bootGeneration: Long,
    ): AlarmInstallResult {
        val (manager, managerFailure) = readAlarmManager()
        if (manager == null) {
            return AlarmInstallResult(
                exactCapability = null,
                endSubmitted = false,
                startSubmitted = false,
                fallbackSubmitted = false,
                receipt = null,
                failure = managerFailure ?: IllegalStateException("AlarmManager service is unavailable"),
            )
        }

        val capability = readCapability(manager)
        if (capability.value != true) {
            // Brief sections 14.6 and 15.8: without exact capability we must not newly restrict.
            return AlarmInstallResult(
                exactCapability = capability.value,
                endSubmitted = false,
                startSubmitted = false,
                fallbackSubmitted = false,
                receipt = null,
                failure = capability.failure,
            )
        }

        val endMillis = nextEnd.toEpochMilli()
        val startMillis = nextStart.toEpochMilli()
        val fallbackMillis = endMillis + AlarmActions.FALLBACK_DELAY_MILLIS

        val endFailure = runCatching {
            manager.setExactAndAllowWhileIdle(
                AlarmManager.RTC_WAKEUP,
                endMillis,
                buildPendingIntent(AlarmEventKind.END, revision, endMillis, PendingIntent.FLAG_UPDATE_CURRENT),
            )
        }.exceptionOrNull()
        if (endFailure != null) {
            return AlarmInstallResult(true, false, false, false, null, endFailure)
        }

        val startFailure = runCatching {
            manager.setExactAndAllowWhileIdle(
                AlarmManager.RTC_WAKEUP,
                startMillis,
                buildPendingIntent(AlarmEventKind.START, revision, startMillis, PendingIntent.FLAG_UPDATE_CURRENT),
            )
        }.exceptionOrNull()
        if (startFailure != null) {
            return AlarmInstallResult(true, true, false, false, null, startFailure)
        }

        // Inexact on purpose: this must not require exact-alarm permission.
        val fallbackFailure = runCatching {
            manager.setAndAllowWhileIdle(
                AlarmManager.RTC_WAKEUP,
                fallbackMillis,
                buildPendingIntent(
                    AlarmEventKind.RELEASE_FALLBACK,
                    revision,
                    endMillis,
                    PendingIntent.FLAG_UPDATE_CURRENT,
                ),
            )
        }.exceptionOrNull()
        if (fallbackFailure != null) {
            return AlarmInstallResult(true, true, true, false, null, fallbackFailure)
        }

        return AlarmInstallResult(
            exactCapability = true,
            endSubmitted = true,
            startSubmitted = true,
            fallbackSubmitted = true,
            receipt = ScheduleReceipt(
                revision = revision,
                bootGeneration = bootGeneration,
                submittedAtEpochMillis = clockMillis(),
                nextStartEpochMillis = startMillis,
                nextEndEpochMillis = endMillis,
                fallbackEpochMillis = fallbackMillis,
            ),
            failure = null,
        )
    }

    /**
     * The Gate B temporary safety timer (brief section 22 step 6): a minimal release
     * broadcast a few minutes ahead, plus a distinct inexact fallback.
     *
     * Two things are deliberate here:
     *
     * 1. It uses its **own** identities ([AlarmEventKind.TEMPORARY_TEST_RELEASE] /
     *    [AlarmEventKind.TEMPORARY_TEST_FALLBACK]), not the daily `END_PROTECTION` /
     *    `RELEASE_FALLBACK` ones. Sharing them would replace the real daily release path while
     *    the stored receipt still claimed that plan was installed, and the daily handler would
     *    run the normal reconciler — which, under a forced-restriction override, would simply
     *    re-restrict instead of releasing.
     * 2. The delivery handler invokes `RecoveryManager` and never enables protection.
     *
     * The returned receipt is **null on purpose**: this timer is not the daily plan and must
     * never be persisted into the scheduling-receipt slot.
     */
    override fun installTemporaryRelease(
        releaseAt: Instant,
        revision: Long,
        sessionId: String?,
    ): AlarmInstallResult {
        val (manager, managerFailure) = readAlarmManager()
        if (manager == null) {
            return AlarmInstallResult(
                exactCapability = null,
                endSubmitted = false,
                startSubmitted = false,
                fallbackSubmitted = false,
                receipt = null,
                failure = managerFailure ?: IllegalStateException("AlarmManager service is unavailable"),
            )
        }
        val capability = readCapability(manager)
        if (capability.value != true) {
            return AlarmInstallResult(
                exactCapability = capability.value,
                endSubmitted = false,
                startSubmitted = false,
                fallbackSubmitted = false,
                receipt = null,
                failure = capability.failure,
            )
        }

        val releaseMillis = releaseAt.toEpochMilli()
        val fallbackMillis = releaseMillis + AlarmActions.FALLBACK_DELAY_MILLIS

        val endFailure = runCatching {
            manager.setExactAndAllowWhileIdle(
                AlarmManager.RTC_WAKEUP,
                releaseMillis,
                buildPendingIntent(
                    AlarmEventKind.TEMPORARY_TEST_RELEASE,
                    revision,
                    releaseMillis,
                    PendingIntent.FLAG_CANCEL_CURRENT,
                    temporaryTestSessionId = sessionId,
                ),
            )
        }.exceptionOrNull()
        if (endFailure != null) {
            return AlarmInstallResult(true, false, false, false, null, endFailure)
        }

        val fallbackFailure = runCatching {
            manager.setAndAllowWhileIdle(
                AlarmManager.RTC_WAKEUP,
                fallbackMillis,
                buildPendingIntent(
                    AlarmEventKind.TEMPORARY_TEST_FALLBACK,
                    revision,
                    releaseMillis,
                    PendingIntent.FLAG_CANCEL_CURRENT,
                    temporaryTestSessionId = sessionId,
                ),
            )
        }.exceptionOrNull()
        if (fallbackFailure != null) {
            return AlarmInstallResult(true, true, false, false, null, fallbackFailure)
        }

        return AlarmInstallResult(
            exactCapability = true,
            endSubmitted = true,
            startSubmitted = true,
            fallbackSubmitted = true,
            receipt = null,
            failure = null,
        )
    }

    /**
     * One separate inexact, release-only retry for an unresolved cleanup incident
     * (brief section 10). It must never arm a session or apply a restrictive mask.
     */
    override fun installRecoveryRetry(incidentId: String, triggerAt: Instant): Boolean {
        val manager = readAlarmManager().first ?: return false
        val intent = Intent(appContext, ProtectionAlarmReceiver::class.java).apply {
            action = AlarmActions.recoveryRetry(applicationId)
            putExtra(AlarmActions.extraIncidentId(applicationId), incidentId)
        }
        val pending = PendingIntent.getBroadcast(
            appContext,
            AlarmActions.REQUEST_CODE_RECOVERY_RETRY,
            intent,
            PendingIntent.FLAG_CANCEL_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return runCatching {
            // Inexact by design: a best-effort opportunity with no delivery deadline
            // guarantee (brief section 10).
            manager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt.toEpochMilli(), pending)
            true
        }.getOrDefault(false)
    }

    private fun existingPendingIntent(kind: AlarmEventKind): PendingIntent? {
        val intent = Intent(appContext, ProtectionAlarmReceiver::class.java).apply {
            action = AlarmActions.actionFor(applicationId, kind)
        }
        return PendingIntent.getBroadcast(
            appContext,
            AlarmActions.requestCode(kind),
            intent,
            PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private data class CapabilityRead(val value: Boolean?, val failure: Throwable?)

    private fun readAlarmManager(): Pair<AlarmManager?, Throwable?> = try {
        alarmManagerProvider() to null
    } catch (failure: Throwable) {
        null to failure
    }

    private fun readCapability(manager: AlarmManager): CapabilityRead = try {
        CapabilityRead(manager.canScheduleExactAlarms(), null)
    } catch (failure: Throwable) {
        CapabilityRead(null, failure)
    }

    private fun buildPendingIntent(
        kind: AlarmEventKind,
        revision: Long,
        boundaryMillis: Long,
        flag: Int,
        temporaryTestSessionId: String? = null,
    ): PendingIntent {
        val intent = Intent(appContext, ProtectionAlarmReceiver::class.java).apply {
            action = AlarmActions.actionFor(applicationId, kind)
            // Diagnostic / staleness metadata only. The receiver always re-reads current
            // settings and the current time before acting (brief section 14).
            putExtra(AlarmActions.extraEventKind(applicationId), kind.wireName)
            putExtra(AlarmActions.extraSettingsRevision(applicationId), revision)
            putExtra(AlarmActions.extraPlannedBoundary(applicationId), boundaryMillis)
            if (kind.isTemporaryTest && temporaryTestSessionId != null) {
                putExtra(AlarmActions.extraTemporaryTestSessionId(applicationId), temporaryTestSessionId)
            }
        }
        return PendingIntent.getBroadcast(
            appContext,
            AlarmActions.requestCode(kind),
            intent,
            flag or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private companion object {
        val PLAN_IDENTITIES = listOf(
            AlarmEventKind.END,
            AlarmEventKind.START,
            AlarmEventKind.RELEASE_FALLBACK,
        )
    }
}
