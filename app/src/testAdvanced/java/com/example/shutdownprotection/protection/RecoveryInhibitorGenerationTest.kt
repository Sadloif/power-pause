package com.example.shutdownprotection.protection

import com.example.shutdownprotection.admin.DevicePolicyController
import com.example.shutdownprotection.admin.LockTaskMasks
import com.example.shutdownprotection.admin.LockTaskRuntimeStates
import com.example.shutdownprotection.data.BaselineLifecycleState
import com.example.shutdownprotection.data.BaselineProvenance
import com.example.shutdownprotection.data.CleanupCommit
import com.example.shutdownprotection.data.PolicyBaseline
import com.example.shutdownprotection.data.ProtectionSettings
import com.example.shutdownprotection.data.SettingsRepository
import com.example.shutdownprotection.data.SettingsWriteResult
import com.example.shutdownprotection.fakes.FakeDevicePolicyGateway
import com.example.shutdownprotection.fakes.FakeDiagnosticsRepository
import com.example.shutdownprotection.fakes.FakeLockTaskSessionController
import com.example.shutdownprotection.fakes.FakeRuntimeLockTaskStateProvider
import com.example.shutdownprotection.fakes.FakeSchedulingGateway
import com.example.shutdownprotection.fakes.FakeSettingsRepository
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RecoveryInhibitorGenerationTest {

    @Test
    fun verified_recovery_cannot_clear_a_newer_independent_inhibition() = runTest {
        val appId = "com.example.shutdownprotection"
        val settings = FakeSettingsRepository().apply {
            seed(
                ProtectionSettings.DEFAULT.copy(
                    enabled = false,
                    recoveryRequired = true,
                    allowedPackages = setOf(appId),
                ),
            )
            baseline = PolicyBaseline(
                capturedAtEpochMillis = 1L,
                lockTaskPackages = setOf("external.kiosk"),
                lockTaskFeatures = 16,
                applicationId = appId,
                provenance = BaselineProvenance.CAPTURED_BEFORE_PREPARATION,
                lifecycle = BaselineLifecycleState.PREPARED,
            )
        }
        val runtime = FakeRuntimeLockTaskStateProvider(LockTaskRuntimeStates.LOCKED)
        val gateway = FakeDevicePolicyGateway().apply {
            owner = true
            features = LockTaskMasks.PROTECTED
            packages = mutableSetOf(appId)
        }
        val inhibitor = RestrictionInhibitor()
        val earlierToken = inhibitor.inhibit("an earlier operation requested release")
        var newerIndependentToken: RestrictionInhibitor.Token? = null
        val settingsWithIndependentFailure = object : SettingsRepository by settings {
            override suspend fun commitCleanup(commit: CleanupCommit): SettingsWriteResult {
                val result = settings.commitCleanup(commit)
                if (result is SettingsWriteResult.Success) {
                    // This represents another safety failure arriving after recovery began but
                    // before its final conditional clear.
                    newerIndependentToken = inhibitor.inhibit("new independent failure during cleanup")
                }
                return result
            }
        }
        val recovery = RecoveryManager(
            settingsRepository = settingsWithIndependentFailure,
            diagnostics = FakeDiagnosticsRepository(),
            policyController = DevicePolicyController(
                gateway = gateway,
                runtimeState = runtime,
                applicationId = appId,
                clockMillis = { 1L },
            ),
            scheduleManager = FakeSchedulingGateway(),
            lockTaskSession = FakeLockTaskSessionController(runtime),
            inhibitor = inhibitor,
            clockMillis = { 1L },
            newIncidentId = { "generation-test-incident" },
            sleep = {},
        )

        val result = recovery.recover("INDEPENDENT_INHIBITION_TEST")

        assertTrue("the original recovery completed its durable cleanup", result.verified)
        assertTrue("the newer independent failure must remain inhibited", inhibitor.isInhibited)
        assertFalse("an older token cannot clear newer state", inhibitor.clearIfCurrent(earlierToken))
        val newer = requireNotNull(newerIndependentToken)
        assertTrue("only the current failure owner can release its own token", inhibitor.clearIfCurrent(newer))
        assertFalse(inhibitor.isInhibited)
    }
}
