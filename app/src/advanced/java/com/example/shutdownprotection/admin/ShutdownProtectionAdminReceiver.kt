package com.example.shutdownprotection.admin

import android.app.admin.DeviceAdminReceiver
import android.content.Context
import android.content.Intent
import com.example.shutdownprotection.ShutdownProtectionApplication
import com.example.shutdownprotection.dispatchReceiverWork
import com.example.shutdownprotection.protection.ProtectionTrigger

/** Receives device-admin and Lock Task observations without re-arming from an exit callback. */
class ShutdownProtectionAdminReceiver : DeviceAdminReceiver() {

    override fun onLockTaskModeEntering(context: Context, intent: Intent, pkg: String) {
        super.onLockTaskModeEntering(context, intent, pkg)
        dispatch(context) { services ->
            services.reconcile(ProtectionTrigger.LOCK_TASK_CHANGED)
            services.recordDiagnostic("LOCK_TASK_ENTERING", -1L, "package=$pkg")
        }
    }

    override fun onLockTaskModeExiting(context: Context, intent: Intent) {
        super.onLockTaskModeExiting(context, intent)
        dispatch(context) { services ->
            // Observation refresh only. No policy is written, so this cannot re-arm.
            services.refreshObservation()
            services.recordDiagnostic("LOCK_TASK_EXITING", -1L, "session exit observed")
        }
    }

    override fun onEnabled(context: Context, intent: Intent) {
        super.onEnabled(context, intent)
        dispatch(context) { services ->
            services.reconcile(ProtectionTrigger.APP_FOREGROUND)
            services.recordDiagnostic("ADMIN_ENABLED", -1L, "device admin enabled")
        }
    }

    override fun onDisabled(context: Context, intent: Intent) {
        super.onDisabled(context, intent)
        dispatch(context) { services ->
            // Ownership is gone, so only refresh observations; do not reconcile into a policy
            // mutation that can no longer be made.
            services.refreshObservation()
            services.recordDiagnostic("ADMIN_DISABLED", -1L, "device admin disabled")
        }
    }

    private fun dispatch(context: Context, block: suspend (com.example.shutdownprotection.ReceiverServices) -> Unit) {
        val pending = goAsync()
        val services = try {
            ShutdownProtectionApplication.receiverServices(context)
        } catch (_: Throwable) {
            runCatching { pending.finish() }
            return
        }
        dispatchReceiverWork(services, pending, block)
    }
}
