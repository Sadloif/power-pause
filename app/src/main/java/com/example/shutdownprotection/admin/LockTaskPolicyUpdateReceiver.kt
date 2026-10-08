package com.example.shutdownprotection.admin

import android.app.admin.DevicePolicyIdentifiers
import android.app.admin.PolicyUpdateReceiver
import android.app.admin.PolicyUpdateResult
import android.app.admin.TargetUser
import android.content.Context
import android.os.Bundle
import com.example.shutdownprotection.ReceiverServices
import com.example.shutdownprotection.ShutdownProtectionApplication
import com.example.shutdownprotection.dispatchReceiverWork

/** Routes the platform's asynchronous policy outcome for Lock Task and ignores unrelated policy changes. */
class LockTaskPolicyUpdateReceiver : PolicyUpdateReceiver() {

    override fun onPolicySetResult(
        context: Context,
        policyIdentifier: String,
        additionalPolicyParams: Bundle,
        targetUser: TargetUser,
        policyUpdateResult: PolicyUpdateResult,
    ) {
        dispatch(context) { services ->
            val resultCode = readResultCode(policyUpdateResult)
            routePolicySetResult(services, policyIdentifier, resultCode, targetUser)
        }
    }

    override fun onPolicyChanged(
        context: Context,
        policyIdentifier: String,
        additionalPolicyParams: Bundle,
        targetUser: TargetUser,
        policyUpdateResult: PolicyUpdateResult,
    ) {
        dispatch(context) { services ->
            val resultCode = readResultCode(policyUpdateResult)
            routePolicyChanged(services, policyIdentifier, resultCode, targetUser)
        }
    }

    /** Shared by the Android callback and its nullable-result routing test. */
    internal suspend fun routePolicySetResult(
        services: ReceiverServices,
        policyIdentifier: String,
        resultCode: Int?,
        targetUser: TargetUser,
    ) {
        val relevant = policyIdentifier == DevicePolicyIdentifiers.LOCK_TASK_POLICY
        if (!relevant) {
            services.recordPolicyResult(policyIdentifier, resultCode)
        } else {
            when (resultCode) {
                // The same coordinator path serializes the successful result and its observation,
                // so diagnostics cannot keep an older result code after a new set confirmation.
                PolicyUpdateResult.RESULT_POLICY_SET ->
                    services.handlePolicyChanged(policyIdentifier, resultCode)
                PolicyUpdateResult.RESULT_POLICY_CLEARED ->
                    services.handlePolicyChanged(policyIdentifier, resultCode)
                // Null and every non-success result go through conservative recovery.
                else -> services.handlePolicyFailure(policyIdentifier, resultCode)
            }
        }
        services.recordDiagnostic(
            if (relevant) "POLICY_SET_RESULT" else "POLICY_SET_RESULT_UNRELATED",
            -1L,
            "identifier=$policyIdentifier result=${describeResult(resultCode)} " +
                "targetUser=$targetUser relevant=$relevant",
        )
    }

    /** Shared by the Android callback; the coordinator classifies a null result conservatively. */
    internal suspend fun routePolicyChanged(
        services: ReceiverServices,
        policyIdentifier: String,
        resultCode: Int?,
        targetUser: TargetUser,
    ) {
        val relevant = policyIdentifier == DevicePolicyIdentifiers.LOCK_TASK_POLICY
        if (relevant) {
            services.handlePolicyChanged(policyIdentifier, resultCode)
        } else {
            services.recordPolicyResult(policyIdentifier, resultCode)
        }
        services.recordDiagnostic(
            if (relevant) "POLICY_CHANGED" else "POLICY_CHANGED_UNRELATED",
            -1L,
            "identifier=$policyIdentifier result=${describeResult(resultCode)} " +
                "targetUser=$targetUser relevant=$relevant",
        )
    }

    private fun dispatch(
        context: Context,
        block: suspend (ReceiverServices) -> Unit,
    ) {
        val pending = goAsync()
        val services = try {
            ShutdownProtectionApplication.receiverServices(context)
        } catch (_: Throwable) {
            runCatching { pending.finish() }
            return
        }
        dispatchReceiverWork(services, pending, block)
    }

    private fun readResultCode(result: PolicyUpdateResult): Int? =
        runCatching { result.resultCode }.getOrNull()

    private fun describeResult(code: Int?): String = when (code) {
        PolicyUpdateResult.RESULT_POLICY_SET -> "RESULT_POLICY_SET(0)"
        PolicyUpdateResult.RESULT_FAILURE_CONFLICTING_ADMIN_POLICY ->
            "RESULT_FAILURE_CONFLICTING_ADMIN_POLICY(1)"
        PolicyUpdateResult.RESULT_POLICY_CLEARED -> "RESULT_POLICY_CLEARED(2)"
        PolicyUpdateResult.RESULT_FAILURE_STORAGE_LIMIT_REACHED ->
            "RESULT_FAILURE_STORAGE_LIMIT_REACHED(3)"
        PolicyUpdateResult.RESULT_FAILURE_HARDWARE_LIMITATION ->
            "RESULT_FAILURE_HARDWARE_LIMITATION(4)"
        PolicyUpdateResult.RESULT_FAILURE_UNKNOWN -> "RESULT_FAILURE_UNKNOWN(-1)"
        null -> "no-result"
        else -> "UNKNOWN($code)"
    }
}
