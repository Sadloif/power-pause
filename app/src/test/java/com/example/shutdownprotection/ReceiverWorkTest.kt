package com.example.shutdownprotection

import com.example.shutdownprotection.protection.ProtectionTrigger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReceiverWorkTest {

    @Test
    fun submissionFailureAndDuplicateLauncherCompletionCannotFinishTwice() {
        var finishCalls = 0
        val services = object : ReceiverServices {
            override val applicationId: String = "test.app"

            override fun launchReceiver(finishPendingResult: () -> Unit, block: suspend () -> Unit) {
                finishPendingResult()
                finishPendingResult()
                throw IllegalStateException("submission failed after completion")
            }

            override suspend fun reconcile(trigger: ProtectionTrigger) = Unit
            override suspend fun onBootStarted() = Unit
            override suspend fun refreshObservation() = Unit
            override suspend fun recordPolicyResult(policyIdentifier: String, resultCode: Int?) = Unit
            override suspend fun handlePolicyChanged(policyIdentifier: String, resultCode: Int?) = Unit
            override suspend fun handlePolicyFailure(policyIdentifier: String, resultCode: Int?) = Unit
            override suspend fun handleRecoveryRetry(incidentId: String?) = Unit
            override suspend fun releaseTemporaryTest(
                deliveredReleaseAtEpochMillis: Long?,
                deliveredSessionId: String?,
            ) = Unit
            override suspend fun recordDiagnostic(kind: String, revision: Long, message: String) = Unit
        }

        dispatchReceiverWork(services, { finishCalls++ }) { }

        assertEquals("even a partially submitted launcher plus synchronous failure is once-only", 1, finishCalls)
    }

    @Test
    fun successFailureAndThrowingDiagnosticsFinishOnce() = runTest {
        assertEquals("receiver work stays inside the ordinary broadcast budget", 8_000L, AppContainer.RECEIVER_WORK_BUDGET_MILLIS)
        var successes = 0
        launchBoundedReceiverWork(
            scope = this,
            boundedMillis = 8_000,
            finish = { successes++ },
            reportFailure = { error("must not report success") },
        ) { }
        runCurrent()
        assertEquals(1, successes)

        var failures = 0
        var reports = 0
        launchBoundedReceiverWork(
            scope = this,
            boundedMillis = 8_000,
            finish = { failures++ },
            reportFailure = { reports++; error("diagnostics failed too") },
        ) {
            error("repository failed")
        }
        runCurrent()
        assertEquals(1, reports)
        assertEquals("failure and failed reporting still finish exactly once", 1, failures)
    }

    @Test
    fun canceledWorkAndAlreadyCanceledScopeFinishExactlyOnce() = runTest {
        var cancelledFinishes = 0
        val running = launchBoundedReceiverWork(
            scope = this,
            boundedMillis = 8_000,
            finish = { cancelledFinishes++ },
            reportFailure = {},
        ) {
            kotlinx.coroutines.awaitCancellation()
        }
        runCurrent()
        running.cancel()
        runCurrent()
        assertEquals(1, cancelledFinishes)

        val stoppedScope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
        stoppedScope.cancel()
        var stoppedFinishes = 0
        launchBoundedReceiverWork(
            scope = stoppedScope,
            boundedMillis = 8_000,
            finish = { stoppedFinishes++ },
            reportFailure = {},
        ) { error("a canceled scope must never enter work") }
        runCurrent()
        assertEquals("work never starts after scope cancellation, but PendingResult is finished", 1, stoppedFinishes)
    }

    @Test
    fun deadlineFinishesWhenRepositoryErrorLoggingOrFinallyDoesNotCooperate() = runTest {
        suspend fun assertDeadlineFor(
            block: suspend () -> Unit,
            reportFailure: suspend (Throwable) -> Unit = {},
        ) {
            var finishes = 0
            var entered = false
            val job = launchBoundedReceiverWork(
                scope = this,
                boundedMillis = 8_000,
                finish = { finishes++ },
                reportFailure = { error -> reportFailure(error) },
                block = {
                    entered = true
                    block()
                },
            )
            runCurrent()
            assertTrue("the requested work path should have started", entered)
            advanceTimeBy(8_000)
            runCurrent()
            assertEquals("the independent deadline completes PendingResult", 1, finishes)
            assertFalse("the watchdog must cancel the tracked work job", job.isActive)
            // Let deliberately non-cancellable test work leave its bounded cleanup section.
            advanceTimeBy(2_000)
            runCurrent()
            assertEquals("late coroutine cleanup cannot finish it a second time", 1, finishes)
        }

        assertDeadlineFor(
            block = {
                withContext(NonCancellable) { delay(9_000) }
            },
        )

        assertDeadlineFor(
            block = { error("repository failed") },
            reportFailure = {
                withContext(NonCancellable) { delay(9_000) }
            },
        )

        assertDeadlineFor(
            block = {
                try {
                    // Return immediately; deliberately stall in finally instead.
                } finally {
                    withContext(NonCancellable) { delay(9_000) }
                }
            },
        )
    }
}
