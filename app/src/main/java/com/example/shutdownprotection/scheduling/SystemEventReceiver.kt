package com.example.shutdownprotection.scheduling

import android.app.AlarmManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.example.shutdownprotection.ReceiverServices
import com.example.shutdownprotection.ShutdownProtectionApplication
import com.example.shutdownprotection.dispatchReceiverWork
import com.example.shutdownprotection.protection.ProtectionTrigger

/** Handles the narrowly allowlisted boot, clock, replacement, and exact-alarm events. */
class SystemEventReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val pending = goAsync()
        val services = try {
            ShutdownProtectionApplication.receiverServices(context)
        } catch (_: Throwable) {
            runCatching { pending.finish() }
            return
        }
        dispatchReceiverWork(services, pending) { receiverServices ->
            route(intent.action, receiverServices)
        }
    }

    private suspend fun route(action: String?, services: ReceiverServices) {
        when (action) {
            Intent.ACTION_LOCKED_BOOT_COMPLETED -> {
                // Invalidate previous-boot alarms before reading/reconciling device-protected state.
                services.onBootStarted()
                services.reconcile(ProtectionTrigger.BOOT_LOCKED)
            }
            Intent.ACTION_BOOT_COMPLETED -> services.reconcile(ProtectionTrigger.BOOT_UNLOCKED)
            Intent.ACTION_MY_PACKAGE_REPLACED -> {
                services.onBootStarted()
                services.reconcile(ProtectionTrigger.APP_UPDATED)
            }
            Intent.ACTION_TIME_CHANGED -> services.reconcile(ProtectionTrigger.TIME_CHANGED)
            Intent.ACTION_TIMEZONE_CHANGED -> services.reconcile(ProtectionTrigger.TIMEZONE_CHANGED)
            AlarmManager.ACTION_SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED ->
                services.reconcile(ProtectionTrigger.EXACT_ACCESS_GRANTED)
            else -> services.recordDiagnostic(
                "UNKNOWN_SYSTEM_EVENT",
                -1L,
                "action=${action ?: "null"}",
            )
        }
    }
}
