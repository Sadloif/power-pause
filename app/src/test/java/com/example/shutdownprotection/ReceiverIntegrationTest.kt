package com.example.shutdownprotection

import android.app.admin.DevicePolicyIdentifiers
import android.app.admin.PolicyUpdateResult
import android.app.admin.TargetUser
import android.content.BroadcastReceiver
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import com.example.shutdownprotection.admin.LockTaskPolicyUpdateReceiver
import com.example.shutdownprotection.admin.ShutdownProtectionAdminReceiver
import com.example.shutdownprotection.admin.DevicePolicyController
import com.example.shutdownprotection.admin.LockTaskMasks
import com.example.shutdownprotection.admin.LockTaskRuntimeStates
import com.example.shutdownprotection.data.BaselineLifecycleState
import com.example.shutdownprotection.data.BaselineProvenance
import com.example.shutdownprotection.data.PolicyBaseline
import com.example.shutdownprotection.data.TemporaryTestMarker
import com.example.shutdownprotection.fakes.FakeDevicePolicyGateway
import com.example.shutdownprotection.fakes.FakeDiagnosticsRepository
import com.example.shutdownprotection.fakes.FakeEnvironmentStateProvider
import com.example.shutdownprotection.fakes.FakeLockTaskSessionController
import com.example.shutdownprotection.fakes.FakeRuntimeLockTaskStateProvider
import com.example.shutdownprotection.fakes.FakeSchedulingGateway
import com.example.shutdownprotection.fakes.FakeSettingsRepository
import com.example.shutdownprotection.protection.ProtectionCoordinator
import com.example.shutdownprotection.protection.ProtectionState
import com.example.shutdownprotection.protection.RecoveryManager
import com.example.shutdownprotection.protection.RestrictionInhibitor
import com.example.shutdownprotection.protection.ProtectionTrigger
import com.example.shutdownprotection.scheduling.AlarmActions
import com.example.shutdownprotection.scheduling.AlarmEventKind
import com.example.shutdownprotection.scheduling.ProtectionAlarmReceiver
import com.example.shutdownprotection.scheduling.SystemEventReceiver
import com.example.shutdownprotection.scheduling.ScheduleCalculator
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowBroadcastPendingResult
import org.robolectric.util.ReflectionHelpers
import org.robolectric.util.ReflectionHelpers.ClassParameter
import java.util.concurrent.TimeUnit
import java.time.Clock
import java.time.Instant
import java.time.ZoneId

/** Executes real BroadcastReceiver callbacks and reads the PendingResult Robolectric captured. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = ShutdownProtectionApplication::class)
class ReceiverIntegrationTest {

    private lateinit var application: ShutdownProtectionApplication
    private lateinit var services: RecordingReceiverServices

    @Before
    fun setUp() {
        application = RuntimeEnvironment.getApplication() as ShutdownProtectionApplication
        services = RecordingReceiverServices(application.packageName)
        application.receiverServicesOverrideForTests = services
    }

    @After
    fun tearDown() {
        application.receiverServicesOverrideForTests = null
        services.close()
        application.container.scope.cancel()
    }

    @Test
    fun deviceAdminCallbacksRouteAndCompleteTheirAsyncResults() {
        val receiver = readyReceiver(ShutdownProtectionAdminReceiver())

        receiver.onLockTaskModeEntering(application, Intent(), "example.kiosk")
        assertEquals(
            listOf("reconcile:LOCK_TASK_CHANGED", "diagnostic:LOCK_TASK_ENTERING"),
            services.calls.toList(),
        )
        assertFinished(receiver)

        services.calls.clear()
        receiver.onLockTaskModeExiting(application, Intent())
        assertEquals(
            listOf("refresh", "diagnostic:LOCK_TASK_EXITING"),
            services.calls.toList(),
        )
        assertFalse("exit observation must not recursively reconcile", services.calls.any { it.startsWith("reconcile:") })
        assertFinished(receiver)

        services.calls.clear()
        receiver.onEnabled(application, Intent())
        assertEquals(
            listOf("reconcile:APP_FOREGROUND", "diagnostic:ADMIN_ENABLED"),
            services.calls.toList(),
        )
        assertFinished(receiver)

        services.calls.clear()
        receiver.onDisabled(application, Intent())
        assertEquals(listOf("refresh", "diagnostic:ADMIN_DISABLED"), services.calls.toList())
        assertFinished(receiver)
    }

    @Test
    fun receiversFinishAsyncResultsWhenApplicationContainerResolutionFails() {
        val unavailableApplication = object : ContextWrapper(application) {
            override fun getApplicationContext(): Context = this
        }

        val admin = readyReceiver(ShutdownProtectionAdminReceiver())
        admin.onEnabled(unavailableApplication, Intent())
        assertFinished(admin)

        val policy = readyReceiver(LockTaskPolicyUpdateReceiver())
        policy.onPolicySetResult(
            unavailableApplication,
            DevicePolicyIdentifiers.LOCK_TASK_POLICY,
            Bundle(),
            TargetUser.LOCAL_USER,
            PolicyUpdateResult(PolicyUpdateResult.RESULT_POLICY_SET),
        )
        assertFinished(policy)

        val alarm = readyReceiver(ProtectionAlarmReceiver())
        alarm.onReceive(unavailableApplication, Intent(AlarmActions.startProtection(application.packageName)))
        assertFinished(alarm)

        val system = readyReceiver(SystemEventReceiver())
        system.onReceive(unavailableApplication, Intent(Intent.ACTION_LOCKED_BOOT_COMPLETED))
        assertFinished(system)
    }

    @Test
    fun policyCallbacksUseThePublicLockTaskIdentifierAndKeepUnrelatedChangesOutOfTheFeature() {
        val receiver = readyReceiver(LockTaskPolicyUpdateReceiver())
        val parameters = Bundle()
        val localUser = TargetUser.LOCAL_USER

        receiver.onPolicySetResult(
            application,
            DevicePolicyIdentifiers.LOCK_TASK_POLICY,
            parameters,
            localUser,
            PolicyUpdateResult(PolicyUpdateResult.RESULT_POLICY_SET),
        )
        assertEquals(
            listOf(
                "changed:${DevicePolicyIdentifiers.LOCK_TASK_POLICY}:${PolicyUpdateResult.RESULT_POLICY_SET}",
                "diagnostic:POLICY_SET_RESULT",
            ),
            services.calls.toList(),
        )
        assertFinished(receiver)

        services.calls.clear()
        receiver.onPolicySetResult(
            application,
            DevicePolicyIdentifiers.LOCK_TASK_POLICY,
            parameters,
            localUser,
            PolicyUpdateResult(PolicyUpdateResult.RESULT_FAILURE_UNKNOWN),
        )
        assertEquals(
            listOf("failure:${DevicePolicyIdentifiers.LOCK_TASK_POLICY}:-1", "diagnostic:POLICY_SET_RESULT"),
            services.calls.toList(),
        )
        assertFinished(receiver)

        services.calls.clear()
        receiver.onPolicyChanged(
            application,
            DevicePolicyIdentifiers.LOCK_TASK_POLICY,
            parameters,
            localUser,
            PolicyUpdateResult(PolicyUpdateResult.RESULT_FAILURE_CONFLICTING_ADMIN_POLICY),
        )
        assertEquals(
            listOf("changed:${DevicePolicyIdentifiers.LOCK_TASK_POLICY}:1", "diagnostic:POLICY_CHANGED"),
            services.calls.toList(),
        )
        assertFinished(receiver)

        services.calls.clear()
        receiver.onPolicySetResult(
            application,
            "android.app.admin.policy.UNRELATED_TEST_POLICY",
            parameters,
            localUser,
            PolicyUpdateResult(PolicyUpdateResult.RESULT_POLICY_SET),
        )
        assertEquals(
            listOf("record:android.app.admin.policy.UNRELATED_TEST_POLICY:0", "diagnostic:POLICY_SET_RESULT_UNRELATED"),
            services.calls.toList(),
        )
        assertFalse("unrelated identifiers must not change or fail Lock Task policy", services.calls.any {
            it.startsWith("changed:") || it.startsWith("failure:") || it == "refresh"
        })
        assertFinished(receiver)

        services.calls.clear()
        receiver.onPolicyChanged(
            application,
            "android.app.admin.policy.UNRELATED_TEST_POLICY",
            parameters,
            localUser,
            PolicyUpdateResult(PolicyUpdateResult.RESULT_POLICY_CLEARED),
        )
        assertEquals(
            listOf("record:android.app.admin.policy.UNRELATED_TEST_POLICY:2", "diagnostic:POLICY_CHANGED_UNRELATED"),
            services.calls.toList(),
        )
        assertFinished(receiver)

        services.calls.clear()
        runBlocking {
            receiver.routePolicySetResult(
                services,
                DevicePolicyIdentifiers.LOCK_TASK_POLICY,
                null,
                localUser,
            )
        }
        assertEquals(
            "a missing policy result must route into conservative failure handling",
            listOf("failure:${DevicePolicyIdentifiers.LOCK_TASK_POLICY}:null", "diagnostic:POLICY_SET_RESULT"),
            services.calls.toList(),
        )

        services.calls.clear()
        runBlocking {
            receiver.routePolicyChanged(
                services,
                DevicePolicyIdentifiers.LOCK_TASK_POLICY,
                null,
                localUser,
            )
        }
        assertEquals(
            "a null policy-changed result must reach conservative core classification",
            listOf("changed:${DevicePolicyIdentifiers.LOCK_TASK_POLICY}:null", "diagnostic:POLICY_CHANGED"),
            services.calls.toList(),
        )
    }

    @Test
    fun alarmRoutesPreserveIncidentAndTemporarySessionIdentityAndLogAfterRelease() {
        val receiver = readyReceiver(ProtectionAlarmReceiver())
        val appId = application.packageName

        receiver.onReceive(application, Intent(AlarmActions.actionFor(appId, AlarmEventKind.START)))
        assertEquals(listOf("reconcile:START_ALARM"), services.calls.toList())
        assertFinished(receiver)

        services.calls.clear()
        receiver.onReceive(application, Intent(AlarmActions.actionFor(appId, AlarmEventKind.END)))
        assertEquals(listOf("reconcile:END_ALARM"), services.calls.toList())
        assertFinished(receiver)

        services.calls.clear()
        receiver.onReceive(application, Intent(AlarmActions.actionFor(appId, AlarmEventKind.RELEASE_FALLBACK)))
        assertEquals(listOf("reconcile:RELEASE_FALLBACK"), services.calls.toList())
        assertFinished(receiver)

        services.calls.clear()
        receiver.onReceive(
            application,
            Intent(AlarmActions.actionFor(appId, AlarmEventKind.RECOVERY_RETRY))
                .putExtra(AlarmActions.extraIncidentId(appId), "incident-42"),
        )
        assertEquals(listOf("retry:incident-42"), services.calls.toList())
        assertFinished(receiver)

        services.calls.clear()
        receiver.onReceive(
            application,
            Intent(AlarmActions.temporaryTestRelease(appId))
                .putExtra(AlarmActions.extraPlannedBoundary(appId), 1_700_000_123_456L)
                .putExtra(AlarmActions.extraTemporaryTestSessionId(appId), "old-session-after-clock-roll-back"),
        )
        assertEquals(
            listOf(
                "release:1700000123456:old-session-after-clock-roll-back",
                "diagnostic:ALARM_DELIVERY",
            ),
            services.calls.toList(),
        )
        assertTrue("the stale token and exact planned time are forwarded unchanged", services.calls.first().contains("old-session-after-clock-roll-back"))
        assertFinished(receiver)

        services.calls.clear()
        val blockedLog = CompletableDeferred<Unit>()
        services.beforeDiagnostic = { blockedLog.await() }
        receiver.onReceive(
            application,
            Intent(AlarmActions.temporaryTestFallback(appId))
                .putExtra(AlarmActions.extraPlannedBoundary(appId), 1_700_000_123_456L)
                .putExtra(AlarmActions.extraTemporaryTestSessionId(appId), "session-B"),
        )
        assertEquals(
            "the release action must have run before a suspended ALARM_DELIVERY write",
            listOf("release:1700000123456:session-B", "diagnostic:ALARM_DELIVERY"),
            services.calls.toList(),
        )
        assertFalse(Shadows.shadowOf(receiver).getOriginalPendingResult().let {
            Shadows.shadowOf(it).getFuture().isDone
        })
        blockedLog.complete(Unit)
        assertFinished(receiver)
    }

    @Test
    fun systemEventsOrderLockedBootAndIgnoreForeignActions() {
        val receiver = readyReceiver(SystemEventReceiver())

        receiver.onReceive(application, Intent(Intent.ACTION_LOCKED_BOOT_COMPLETED))
        assertEquals(listOf("boot-started", "reconcile:BOOT_LOCKED"), services.calls.toList())
        assertFinished(receiver)

        services.calls.clear()
        receiver.onReceive(application, Intent(Intent.ACTION_BOOT_COMPLETED))
        assertEquals(listOf("reconcile:BOOT_UNLOCKED"), services.calls.toList())
        assertFinished(receiver)

        services.calls.clear()
        receiver.onReceive(application, Intent(Intent.ACTION_MY_PACKAGE_REPLACED))
        assertEquals(listOf("boot-started", "reconcile:APP_UPDATED"), services.calls.toList())
        assertFinished(receiver)

        services.calls.clear()
        receiver.onReceive(application, Intent(Intent.ACTION_TIME_CHANGED))
        assertEquals(listOf("reconcile:TIME_CHANGED"), services.calls.toList())
        assertFinished(receiver)

        services.calls.clear()
        receiver.onReceive(application, Intent(Intent.ACTION_TIMEZONE_CHANGED))
        assertEquals(listOf("reconcile:TIMEZONE_CHANGED"), services.calls.toList())
        assertFinished(receiver)

        services.calls.clear()
        receiver.onReceive(
            application,
            Intent(android.app.AlarmManager.ACTION_SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED),
        )
        assertEquals(listOf("reconcile:EXACT_ACCESS_GRANTED"), services.calls.toList())
        assertFinished(receiver)

        services.calls.clear()
        receiver.onReceive(application, Intent("android.intent.action.UNRELATED"))
        assertEquals(listOf("diagnostic:UNKNOWN_SYSTEM_EVENT"), services.calls.toList())
        assertFinished(receiver)
    }

    @Test
    fun manifestWiresDirectBootAndProtectedReceiverActionsWithoutExpandingPermissions() {
        val info = application.packageManager.getPackageInfo(
            application.packageName,
            PackageManager.PackageInfoFlags.of(
                (PackageManager.GET_RECEIVERS or PackageManager.GET_PERMISSIONS).toLong(),
            ),
        )
        val receivers = info.receivers.orEmpty().associateBy { it.name }
        val alarm = receivers[ProtectionAlarmReceiver::class.java.name]
        val system = receivers[SystemEventReceiver::class.java.name]
        val admin = receivers[ShutdownProtectionAdminReceiver::class.java.name]
        val policy = receivers[LockTaskPolicyUpdateReceiver::class.java.name]

        assertNotNull(alarm)
        assertFalse(alarm!!.exported)
        assertTrue(alarm.directBootAware)
        assertNotNull(system)
        assertFalse("system broadcasts do not require application-level export", system!!.exported)
        assertTrue(system.directBootAware)
        assertEquals("android.permission.BIND_DEVICE_ADMIN", admin!!.permission)
        assertEquals("android.permission.BIND_DEVICE_ADMIN", policy!!.permission)
        assertTrue(info.requestedPermissions.orEmpty().contains("android.permission.RECEIVE_BOOT_COMPLETED"))
        assertFalse(info.requestedPermissions.orEmpty().contains("android.permission.USE_EXACT_ALARM"))
        assertFalse(info.requestedPermissions.orEmpty().contains("android.permission.INTERNET"))

        for (action in listOf(
                Intent.ACTION_LOCKED_BOOT_COMPLETED,
                Intent.ACTION_BOOT_COMPLETED,
                Intent.ACTION_MY_PACKAGE_REPLACED,
                Intent.ACTION_TIME_CHANGED,
                Intent.ACTION_TIMEZONE_CHANGED,
                android.app.AlarmManager.ACTION_SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED,
            )
        ) {
            assertActionResolvesTo(action, SystemEventReceiver::class.java.name)
        }
        assertActionResolvesTo(
            "android.app.admin.action.DEVICE_POLICY_SET_RESULT",
            LockTaskPolicyUpdateReceiver::class.java.name,
        )
        assertActionResolvesTo(
            "android.app.admin.action.DEVICE_POLICY_CHANGED",
            LockTaskPolicyUpdateReceiver::class.java.name,
        )
        assertActionResolvesTo(
            "android.app.action.DEVICE_ADMIN_ENABLED",
            ShutdownProtectionAdminReceiver::class.java.name,
        )
    }

    @Test
    fun successfulPolicySetUpdatesRealCoordinatorAndStoredObservation() {
        val fixture = recoveryFixture(application.packageName)
        fixture.runtime.state = LockTaskRuntimeStates.NONE
        application.receiverServicesOverrideForTests = AppContainerReceiverServices(
            container = application.container,
            coordinatorOverride = fixture.coordinator,
            diagnosticsOverride = fixture.diagnostics,
        )

        val receiver = readyReceiver(LockTaskPolicyUpdateReceiver())
        receiver.onPolicySetResult(
            application,
            DevicePolicyIdentifiers.LOCK_TASK_POLICY,
            Bundle(),
            TargetUser.LOCAL_USER,
            PolicyUpdateResult(PolicyUpdateResult.RESULT_POLICY_SET),
        )

        assertFinished(receiver)
        val observed = fixture.diagnostics.observation
        assertNotNull("successful callback refreshes the real coordinator observation", observed)
        assertEquals(PolicyUpdateResult.RESULT_POLICY_SET, observed!!.lastPolicyResultCode)
        assertEquals(fixture.settings.current.revision, observed.settingsRevision)
        assertEquals("callback result must be persisted at the observation time", 1_700_000_000_000L, observed.lastPolicyObservedAtEpochMillis)
        assertEquals(ProtectionState.DISARMED, fixture.coordinator.status.value?.state)
    }

    @Test
    fun productionReceiverAdapterForwardsStaleAndMatchingIdentityIntoRealRecovery() {
        val fixture = recoveryFixture(application.packageName)
        application.receiverServicesOverrideForTests = AppContainerReceiverServices(
            container = application.container,
            coordinatorOverride = fixture.coordinator,
            diagnosticsOverride = fixture.diagnostics,
        )
        val appId = application.packageName
        fixture.settings.marker = TemporaryTestMarker(
            startedAtEpochMillis = 1_700_000_000_000L,
            releaseAtEpochMillis = 1_700_000_300_000L,
            revision = fixture.settings.current.revision,
            sessionId = "session-B",
        )
        fixture.settings.baseline = PolicyBaseline(
            capturedAtEpochMillis = 1_699_999_000_000L,
            lockTaskPackages = emptySet(),
            lockTaskFeatures = 0,
            applicationId = appId,
            provenance = BaselineProvenance.CAPTURED_BEFORE_PREPARATION,
            lifecycle = BaselineLifecycleState.PREPARED,
        )
        fixture.gateway.packages = mutableSetOf(appId)
        fixture.gateway.features = LockTaskMasks.PROTECTED
        fixture.runtime.state = LockTaskRuntimeStates.LOCKED

        val receiver = readyReceiver(ProtectionAlarmReceiver())
        val action = AlarmActions.temporaryTestRelease(appId)
        receiver.onReceive(
            application,
            Intent(action)
                .putExtra(AlarmActions.extraPlannedBoundary(appId), 1_700_000_300_000L)
                .putExtra(AlarmActions.extraTemporaryTestSessionId(appId), "session-A"),
        )
        assertFinished(receiver)
        assertNotNull("same-time stale session must leave current marker intact", fixture.settings.marker)
        assertEquals("stale ID must not release current runtime session", LockTaskRuntimeStates.LOCKED, fixture.runtime.state)
        assertEquals("stale ID must not change the restrictive mask", LockTaskMasks.PROTECTED, fixture.gateway.features)

        receiver.onReceive(
            application,
            Intent(action)
                .putExtra(AlarmActions.extraPlannedBoundary(appId), 1_700_000_300_000L)
                .putExtra(AlarmActions.extraTemporaryTestSessionId(appId), "session-B"),
        )
        assertFinished(receiver)
        assertNull("matching episode is routed through recovery and clears its marker", fixture.settings.marker)
        assertEquals(LockTaskRuntimeStates.NONE, fixture.runtime.state)
        assertEquals(0, fixture.gateway.features)
        assertEquals(ProtectionState.DISARMED, fixture.coordinator.status.value?.state)
    }

    private fun assertFinished(receiver: BroadcastReceiver) {
        val shadow = Shadows.shadowOf(receiver)
        assertTrue("the callback must call goAsync()", shadow.wentAsync())
        val pending = shadow.getOriginalPendingResult()
        assertNotNull("goAsync must retain the pending result", pending)
        assertTrue(
            "the receiver's pending result must complete",
            Shadows.shadowOf(pending).getFuture().get(2, TimeUnit.SECONDS) != null,
        )
        // A second framework callback on this instance receives a fresh platform result.
        seedPendingResult(receiver)
    }

    private fun <T : BroadcastReceiver> readyReceiver(receiver: T): T {
        seedPendingResult(receiver)
        return receiver
    }

    /**
     * Robolectric's direct callback calls do not install the PendingResult the Android framework
     * supplies before invocation. Seed that framework state so the production goAsync/finish path
     * itself runs for device-admin and policy callbacks as well as ordinary broadcasts.
     */
    private fun seedPendingResult(receiver: BroadcastReceiver) {
        val pending = ReflectionHelpers.callStaticMethod<BroadcastReceiver.PendingResult>(
            ShadowBroadcastPendingResult::class.java,
            "create",
            ClassParameter.from(Int::class.javaPrimitiveType!!, 0),
            ClassParameter.from(String::class.java, null),
            ClassParameter.from(Bundle::class.java, null),
            ClassParameter.from(Boolean::class.javaPrimitiveType!!, false),
        )
        ReflectionHelpers.callInstanceMethod<Any?>(
            receiver,
            "setPendingResult",
            ClassParameter.from(BroadcastReceiver.PendingResult::class.java, pending),
        )
    }

    private fun assertActionResolvesTo(action: String, receiverClassName: String) {
        val resolved = application.packageManager.queryBroadcastReceivers(
            Intent(action).setPackage(application.packageName),
            PackageManager.ResolveInfoFlags.of(0),
        )
        assertTrue(
            "$action must remain manifest-deliverable to $receiverClassName",
            resolved.any { it.activityInfo?.name == receiverClassName },
        )
    }

    private fun recoveryFixture(applicationId: String): RecoveryFixture {
        val settings = FakeSettingsRepository()
        val diagnostics = FakeDiagnosticsRepository()
        val gateway = FakeDevicePolicyGateway()
        val runtime = FakeRuntimeLockTaskStateProvider(LockTaskRuntimeStates.LOCKED)
        val lockTaskSession = FakeLockTaskSessionController(runtime)
        val schedule = FakeSchedulingGateway()
        val environment = FakeEnvironmentStateProvider()
        val inhibitor = RestrictionInhibitor()
        val controller = DevicePolicyController(
            gateway = gateway,
            runtimeState = runtime,
            applicationId = applicationId,
            clockMillis = { 1_700_000_000_000L },
        )
        val calculator = ScheduleCalculator(
            clock = Clock.fixed(Instant.parse("2026-10-06T03:00:00Z"), ZoneId.of("UTC")),
            zoneProvider = { ZoneId.of("UTC") },
        )
        val recovery = RecoveryManager(
            settingsRepository = settings,
            diagnostics = diagnostics,
            policyController = controller,
            scheduleManager = schedule,
            lockTaskSession = lockTaskSession,
            inhibitor = inhibitor,
            clockMillis = { 1_700_000_000_000L },
            newIncidentId = { "receiver-test-incident" },
            sleep = {},
        )
        val coordinator = ProtectionCoordinator(
            applicationId = applicationId,
            settingsRepository = settings,
            diagnostics = diagnostics,
            policyController = controller,
            scheduleCalculator = calculator,
            scheduleManager = schedule,
            recoveryManager = recovery,
            lockTaskSession = lockTaskSession,
            environment = environment,
            inhibitor = inhibitor,
            clockMillis = { 1_700_000_000_000L },
            sleep = {},
        )
        return RecoveryFixture(settings, diagnostics, gateway, runtime, coordinator)
    }

    private data class RecoveryFixture(
        val settings: FakeSettingsRepository,
        val diagnostics: FakeDiagnosticsRepository,
        val gateway: FakeDevicePolicyGateway,
        val runtime: FakeRuntimeLockTaskStateProvider,
        val coordinator: ProtectionCoordinator,
    )

    private class RecordingReceiverServices(
        override val applicationId: String,
    ) : ReceiverServices {
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val calls = mutableListOf<String>()
        var beforeDiagnostic: suspend () -> Unit = {}

        override fun launchReceiver(finishPendingResult: () -> Unit, block: suspend () -> Unit) {
            launchBoundedReceiverWork(
                scope = scope,
                boundedMillis = 30_000L,
                finish = finishPendingResult,
                reportFailure = { calls += "reported:${it::class.simpleName}" },
                block = block,
            )
        }

        override suspend fun reconcile(trigger: ProtectionTrigger) {
            calls += "reconcile:${trigger.wireName}"
        }

        override suspend fun onBootStarted() {
            calls += "boot-started"
        }

        override suspend fun refreshObservation() {
            calls += "refresh"
        }

        override suspend fun recordPolicyResult(policyIdentifier: String, resultCode: Int?) {
            calls += "record:$policyIdentifier:$resultCode"
        }

        override suspend fun handlePolicyChanged(policyIdentifier: String, resultCode: Int?) {
            calls += "changed:$policyIdentifier:$resultCode"
        }

        override suspend fun handlePolicyFailure(policyIdentifier: String, resultCode: Int?) {
            calls += "failure:$policyIdentifier:$resultCode"
        }

        override suspend fun handleRecoveryRetry(incidentId: String?) {
            calls += "retry:$incidentId"
        }

        override suspend fun releaseTemporaryTest(
            deliveredReleaseAtEpochMillis: Long?,
            deliveredSessionId: String?,
        ) {
            calls += "release:${deliveredReleaseAtEpochMillis?.toString()}:$deliveredSessionId"
        }

        override suspend fun recordDiagnostic(kind: String, revision: Long, message: String) {
            calls += "diagnostic:$kind"
            beforeDiagnostic()
        }

        fun close() = scope.cancel()
    }
}
