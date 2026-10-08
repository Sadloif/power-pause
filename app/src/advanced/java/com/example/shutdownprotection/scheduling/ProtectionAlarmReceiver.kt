package com.example.shutdownprotection.scheduling

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.example.shutdownprotection.ReceiverServices
import com.example.shutdownprotection.ShutdownProtectionApplication
import com.example.shutdownprotection.dispatchReceiverWork
import com.example.shutdownprotection.protection.ProtectionTrigger

/** Handles only the app-owned explicit alarm identities. */
class ProtectionAlarmReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val pending = goAsync()
        val services = try {
            ShutdownProtectionApplication.receiverServices(context)
        } catch (_: Throwable) {
            runCatching { pending.finish() }
            return
        }
        dispatchReceiverWork(services, pending) { receiverServices ->
            route(intent, receiverServices)
        }
    }

    /** Kept inside the bounded receiver job, including intent parsing, safety action, and log. */
    private suspend fun route(intent: Intent, services: ReceiverServices) {
        val action = intent.action
        val kind = AlarmActions.kindFor(services.applicationId, action)
        val plannedBoundary = runCatching {
            intent.getLongExtra(AlarmActions.extraPlannedBoundary(services.applicationId), -1L)
        }.getOrDefault(-1L)

        when (kind) {
            null -> Unit
            AlarmEventKind.START -> services.reconcile(ProtectionTrigger.START_ALARM)
            AlarmEventKind.END -> services.reconcile(ProtectionTrigger.END_ALARM)
            AlarmEventKind.RELEASE_FALLBACK -> services.reconcile(ProtectionTrigger.RELEASE_FALLBACK)
            AlarmEventKind.RECOVERY_RETRY -> {
                val incidentId = runCatching {
                    intent.getStringExtra(AlarmActions.extraIncidentId(services.applicationId))
                }.getOrNull()
                services.handleRecoveryRetry(incidentId)
            }
            AlarmEventKind.TEMPORARY_TEST_RELEASE,
            AlarmEventKind.TEMPORARY_TEST_FALLBACK -> {
                val sessionId = runCatching {
                    intent.getStringExtra(AlarmActions.extraTemporaryTestSessionId(services.applicationId))
                }.getOrNull()
                services.releaseTemporaryTest(
                    deliveredReleaseAtEpochMillis = plannedBoundary.takeIf { it > 0L },
                    deliveredSessionId = sessionId,
                )
            }
        }

        // Logging is last so a blocked diagnostics write cannot postpone release. The receiver
        // watchdog also bounds this log and PendingResult.finish().
        val actual = System.currentTimeMillis()
        if (kind == null) {
            services.recordDiagnostic(
                "UNKNOWN_ALARM_ACTION",
                -1L,
                "action=${action ?: "null"}",
            )
        } else if (plannedBoundary > 0L) {
            services.recordDiagnostic(
                "ALARM_DELIVERY",
                -1L,
                "kind=${kind.wireName} planned=$plannedBoundary actual=$actual " +
                    "deltaMs=${actual - plannedBoundary}",
            )
        }
    }
}
