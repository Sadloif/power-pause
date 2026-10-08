package com.example.shutdownprotection

import android.content.BroadcastReceiver
import com.example.shutdownprotection.data.DiagnosticsRepository
import com.example.shutdownprotection.protection.ProtectionCoordinator
import com.example.shutdownprotection.protection.ProtectionTrigger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The small surface used by asynchronous BroadcastReceivers. Keeping the framework callbacks
 * wired to this interface lets JVM tests execute the real receivers with controlled coordinator,
 * diagnostics, and receiver-lifetime behavior.
 */
interface ReceiverServices {
    val applicationId: String

    fun launchReceiver(
        finishPendingResult: () -> Unit,
        block: suspend () -> Unit,
    )

    suspend fun reconcile(trigger: ProtectionTrigger)
    suspend fun onBootStarted()
    suspend fun refreshObservation()
    suspend fun recordPolicyResult(policyIdentifier: String, resultCode: Int?)
    suspend fun handlePolicyChanged(policyIdentifier: String, resultCode: Int?)
    suspend fun handlePolicyFailure(policyIdentifier: String, resultCode: Int?)
    suspend fun handleRecoveryRetry(incidentId: String?)
    suspend fun releaseTemporaryTest(
        deliveredReleaseAtEpochMillis: Long?,
        deliveredSessionId: String?,
    )
    suspend fun recordDiagnostic(kind: String, revision: Long, message: String)
}

/** Routes production receiver calls to the process container and its coordinator. */
internal class AppContainerReceiverServices(
    private val container: AppContainer,
    private val coordinatorOverride: ProtectionCoordinator? = null,
    private val diagnosticsOverride: DiagnosticsRepository? = null,
) : ReceiverServices {
    private val coordinator: ProtectionCoordinator get() = coordinatorOverride ?: container.coordinator
    private val diagnostics: DiagnosticsRepository get() = diagnosticsOverride ?: container.diagnostics

    override val applicationId: String get() = container.applicationId

    override fun launchReceiver(
        finishPendingResult: () -> Unit,
        block: suspend () -> Unit,
    ) {
        container.launchReceiverWork(finishPendingResult, block)
    }

    override suspend fun reconcile(trigger: ProtectionTrigger) {
        coordinator.reconcile(trigger)
    }

    override suspend fun onBootStarted() {
        coordinator.onBootStarted()
    }

    override suspend fun refreshObservation() {
        coordinator.refreshObservation()
    }

    override suspend fun recordPolicyResult(policyIdentifier: String, resultCode: Int?) =
        coordinator.recordPolicyResult(policyIdentifier, resultCode)

    override suspend fun handlePolicyChanged(policyIdentifier: String, resultCode: Int?) {
        coordinator.handlePolicyChanged(policyIdentifier, resultCode)
    }

    override suspend fun handlePolicyFailure(policyIdentifier: String, resultCode: Int?) {
        coordinator.handlePolicyFailure(policyIdentifier, resultCode)
    }

    override suspend fun handleRecoveryRetry(incidentId: String?) {
        coordinator.handleRecoveryRetry(incidentId)
    }

    override suspend fun releaseTemporaryTest(
        deliveredReleaseAtEpochMillis: Long?,
        deliveredSessionId: String?,
    ) {
        coordinator.releaseTemporaryTest(deliveredReleaseAtEpochMillis, deliveredSessionId)
    }

    override suspend fun recordDiagnostic(kind: String, revision: Long, message: String) =
        diagnostics.record(kind, revision, message)
}

/**
 * Starts work after `goAsync()` and owns a shared finish gate for both synchronous submission
 * errors and asynchronous completion. A service that throws after partially scheduling work can
 * therefore never make the framework PendingResult finish twice.
 */
internal fun dispatchReceiverWork(
    services: ReceiverServices,
    pendingResult: BroadcastReceiver.PendingResult,
    block: suspend (ReceiverServices) -> Unit,
) {
    dispatchReceiverWork(services, { pendingResult.finish() }, block)
}

/** Testable half of the PendingResult adapter; also owns submission-failure deduplication. */
internal fun dispatchReceiverWork(
    services: ReceiverServices,
    finishPendingResult: () -> Unit,
    block: suspend (ReceiverServices) -> Unit,
) {
    val finished = AtomicBoolean(false)
    val finishOnce = {
        if (finished.compareAndSet(false, true)) runCatching { finishPendingResult() }
        Unit
    }
    try {
        services.launchReceiver(finishOnce) { block(services) }
    } catch (_: Throwable) {
        finishOnce()
    }
}

/**
 * Launches tracked broadcast work. A separate watchdog finishes the pending result at the
 * deadline even if a repository is stuck in a non-cancellable suspend/finally block. Exception
 * reporting runs inside the worker's same timeout; there is no unbounded logging tail.
 */
internal fun launchBoundedReceiverWork(
    scope: CoroutineScope,
    boundedMillis: Long,
    finish: () -> Unit,
    reportFailure: suspend (Throwable) -> Unit,
    block: suspend () -> Unit,
): Job {
    require(boundedMillis > 0L)
    val finishOnce = AtomicBoolean(false)
    fun finishSafely() {
        if (finishOnce.compareAndSet(false, true)) {
            runCatching { finish() }
        }
    }

    val watchdogParent = SupervisorJob()
    val watchdogScope = CoroutineScope(
        scope.coroutineContext.minusKey(Job) + watchdogParent,
    )
    val workJob = try {
        scope.launch(start = CoroutineStart.LAZY) {
            try {
                withTimeoutOrNull(boundedMillis) {
                    try {
                        block()
                    } catch (cancellation: CancellationException) {
                        throw cancellation
                    } catch (failure: Throwable) {
                        try {
                            reportFailure(failure)
                        } catch (cancellation: CancellationException) {
                            throw cancellation
                        } catch (_: Throwable) {
                            // Diagnostics are optional and stay within the receiver deadline.
                        }
                    }
                }
            } finally {
                finishSafely()
            }
        }
    } catch (_: Throwable) {
        finishSafely()
        watchdogScope.cancel()
        return Job().also { it.complete() }
    }

    val watchdog = try {
        watchdogScope.launch(start = CoroutineStart.LAZY) {
            delay(boundedMillis)
            finishSafely()
            workJob.cancel(CancellationException("broadcast work deadline exceeded"))
            watchdogScope.cancel()
        }
    } catch (_: Throwable) {
        finishSafely()
        workJob.cancel(CancellationException("broadcast watchdog could not be submitted"))
        watchdogScope.cancel()
        return workJob
    }
    workJob.invokeOnCompletion {
        watchdog.cancel()
        watchdogScope.cancel()
        finishSafely()
    }

    try {
        watchdog.start()
        workJob.start()
    } catch (failure: Throwable) {
        finishSafely()
        workJob.cancel(
            CancellationException("broadcast work could not be submitted").also {
                it.initCause(failure)
            },
        )
        watchdog.cancel()
        watchdogScope.cancel()
    }
    return workJob
}
