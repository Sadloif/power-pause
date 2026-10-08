package com.example.shutdownprotection

import android.app.ActivityManager
import android.app.AlarmManager
import android.app.PendingIntent
import android.app.KeyguardManager
import android.app.admin.DevicePolicyManager
import android.content.Context
import android.content.ContextWrapper
import android.os.UserManager
import com.example.shutdownprotection.admin.AndroidDevicePolicyGateway
import com.example.shutdownprotection.admin.AndroidRuntimeLockTaskStateProvider
import com.example.shutdownprotection.protection.DeviceOwnerUiState
import com.example.shutdownprotection.protection.ManagedSessionUiState
import com.example.shutdownprotection.scheduling.AlarmEventKind
import com.example.shutdownprotection.scheduling.AlarmActions
import com.example.shutdownprotection.scheduling.ProtectionAlarmReceiver
import com.example.shutdownprotection.scheduling.ScheduleManager
import com.example.shutdownprotection.ui.MainActivity
import com.example.shutdownprotection.ui.diagnosticBoolean
import com.example.shutdownprotection.ui.diagnosticEpochLabel
import com.example.shutdownprotection.ui.diagnosticPackageList
import com.example.shutdownprotection.ui.diagnosticPolicyResultLabel
import com.example.shutdownprotection.ui.diagnosticRecoveryStatusLabel
import com.example.shutdownprotection.ui.diagnosticYesNo
import com.example.shutdownprotection.ui.exactAlarmLabel
import com.example.shutdownprotection.ui.ownerLabel
import com.example.shutdownprotection.ui.sessionLabel
import com.example.shutdownprotection.ui.setupExactAlarmLabel
import com.example.shutdownprotection.ui.setupOwnerLabel
import com.example.shutdownprotection.ui.storedRecoveryFlagLabel
import com.example.shutdownprotection.ui.writeDiagnosticsExport
import kotlinx.coroutines.CancellationException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Robolectric
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowAlarmManager
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.OutputStream
import java.time.Instant

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class PlatformUnknownStateTest {

    private val context: Context get() = RuntimeEnvironment.getApplication()

    @Test
    fun absentOrThrowingPlatformServicesRemainUnknown() {
        val absent = contextWithServiceOverrides(emptyMap())
        val gateway = AndroidDevicePolicyGateway(absent)
        assertNull(gateway.isDeviceOwner())
        assertNull(gateway.isLockTaskPermitted(context.packageName))
        assertNull(gateway.readLockTaskPackages())
        assertNull(gateway.readLockTaskFeatures())
        assertThrows(IllegalStateException::class.java) {
            gateway.submitLockTaskPackages(setOf(context.packageName))
        }

        val environment = AndroidEnvironmentStateProvider(absent)
        assertNull(environment.isUserUnlocked())
        assertNull(environment.isKeyguardLocked())
        assertNull(environment.isDeviceSecure())
        assertNull(AndroidRuntimeLockTaskStateProvider(absent).lockTaskModeState())

        val throwing = contextWithServiceOverrides(emptyMap(), throwOnLookup = true)
        val throwingGateway = AndroidDevicePolicyGateway(throwing)
        assertNull(throwingGateway.isDeviceOwner())
        assertNull(throwingGateway.isLockTaskPermitted(context.packageName))
        assertNull(throwingGateway.readLockTaskPackages())
        assertNull(throwingGateway.readLockTaskFeatures())
        assertThrows(IllegalStateException::class.java) {
            throwingGateway.submitLockTaskPackages(setOf(context.packageName))
        }
        assertThrows(IllegalStateException::class.java) { throwingGateway.submitLockTaskFeatures(0) }
        assertNull(AndroidRuntimeLockTaskStateProvider(throwing).lockTaskModeState())
        val throwingEnvironment = AndroidEnvironmentStateProvider(throwing)
        assertNull(throwingEnvironment.isUserUnlocked())
        assertNull(throwingEnvironment.isKeyguardLocked())
        assertNull(throwingEnvironment.isDeviceSecure())

        val throwingAlarmManager = ScheduleManager(throwing, context.packageName)
        assertNull(throwingAlarmManager.canScheduleExactAlarms())
        assertNull(throwingAlarmManager.hasPlanPendingIntentTokens())
        assertTrue(throwingAlarmManager.cancelAll().all { it.existed == null })
    }

    @Test
    fun missingAlarmServiceAndReadFailureDoNotBecomeFalseOrNonexistent() {
        val absent = ScheduleManager(context, context.packageName, alarmManagerProvider = { null })
        assertNull(absent.canScheduleExactAlarms())
        assertNull(absent.hasPlanPendingIntentTokens())
        assertTrue(absent.cancelAll().all { it.existed == null })

        val instant = Instant.parse("2026-10-06T02:00:00Z")
        val absentInstall = absent.installPlan(instant.plusSeconds(3600), instant.plusSeconds(7200), 1, 1)
        assertNull(absentInstall.exactCapability)
        assertFalse(absentInstall.complete)
        assertTrue("missing AlarmManager must be diagnosable", absentInstall.failure is IllegalStateException)

        val throwing = ScheduleManager(
            context,
            context.packageName,
            alarmManagerProvider = { throw SecurityException("service lookup failed") },
        )
        assertNull(throwing.canScheduleExactAlarms())
        assertNull(throwing.cancel(AlarmEventKind.END).existed)
        assertNull(throwing.hasPlanPendingIntentTokens())
        val failedInstall = throwing.installPlan(instant.plusSeconds(3600), instant.plusSeconds(7200), 1, 1)
        assertNull(failedInstall.exactCapability)
        assertTrue(failedInstall.failure is SecurityException)
        val failedTemporary = throwing.installTemporaryRelease(instant.plusSeconds(60), 2, "session-id")
        assertNull(failedTemporary.exactCapability)
        assertTrue(failedTemporary.failure is SecurityException)
    }

    @Test
    fun temporaryReleaseAndFallbackCarryTheSameUniqueSessionIdAndBoundary() {
        ShadowAlarmManager.setCanScheduleExactAlarms(true)
        val manager = ScheduleManager(context, context.packageName)
        val releaseAt = Instant.parse("2026-10-06T02:30:00Z")
        val result = manager.installTemporaryRelease(releaseAt, 7L, "session-B")
        assertTrue(result.complete)

        val release = pendingIntent(AlarmEventKind.TEMPORARY_TEST_RELEASE)
        val fallback = pendingIntent(AlarmEventKind.TEMPORARY_TEST_FALLBACK)
        assertNotNull(release)
        assertNotNull(fallback)
        for (pending in listOf(release!!, fallback!!)) {
            val saved = org.robolectric.Shadows.shadowOf(pending).savedIntent
            assertEquals(releaseAt.toEpochMilli(), saved.getLongExtra(
                AlarmActions.extraPlannedBoundary(context.packageName), -1L,
            ))
            assertEquals(
                "session-B",
                saved.getStringExtra(AlarmActions.extraTemporaryTestSessionId(context.packageName)),
            )
        }
    }

    @Test
    fun reinstallingSameTimeTemporarySessionCancelsOldTokensBeforeReplacingIdentity() {
        ShadowAlarmManager.setCanScheduleExactAlarms(true)
        val manager = ScheduleManager(context, context.packageName)
        val releaseAt = Instant.parse("2026-10-06T02:30:00Z")

        assertTrue(manager.installTemporaryRelease(releaseAt, 7L, "session-A").complete)
        val oldRelease = pendingIntent(AlarmEventKind.TEMPORARY_TEST_RELEASE)!!
        val oldFallback = pendingIntent(AlarmEventKind.TEMPORARY_TEST_FALLBACK)!!
        assertEquals(
            "session-A",
            org.robolectric.Shadows.shadowOf(oldRelease).savedIntent.getStringExtra(
                AlarmActions.extraTemporaryTestSessionId(context.packageName),
            ),
        )

        // Equal timestamps deliberately exercise the clock-rollback collision. Extras do not
        // participate in PendingIntent identity, so new installs must cancel the old token.
        assertTrue(manager.installTemporaryRelease(releaseAt, 8L, "session-B").complete)
        val newRelease = pendingIntent(AlarmEventKind.TEMPORARY_TEST_RELEASE)!!
        val newFallback = pendingIntent(AlarmEventKind.TEMPORARY_TEST_FALLBACK)!!

        assertNotSame("the replacement must be a fresh token", oldRelease, newRelease)
        assertNotSame("the replacement fallback must also be a fresh token", oldFallback, newFallback)
        assertThrows(PendingIntent.CanceledException::class.java) { oldRelease.send() }
        assertThrows(PendingIntent.CanceledException::class.java) { oldFallback.send() }
        for (pending in listOf(newRelease, newFallback)) {
            val saved = org.robolectric.Shadows.shadowOf(pending).savedIntent
            assertEquals(
                releaseAt.toEpochMilli(),
                saved.getLongExtra(AlarmActions.extraPlannedBoundary(context.packageName), -1L),
            )
            assertEquals(
                "session-B",
                saved.getStringExtra(AlarmActions.extraTemporaryTestSessionId(context.packageName)),
            )
        }
    }

    @Test
    fun tokenCancellationIsAttemptedEvenWhenAlarmManagerIsUnavailable() {
        val kind = AlarmEventKind.TEMPORARY_TEST_RELEASE
        val token = PendingIntent.getBroadcast(
            context,
            AlarmActions.requestCode(kind),
            android.content.Intent(context, ProtectionAlarmReceiver::class.java).apply {
                action = AlarmActions.actionFor(context.packageName, kind)
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val absentManager = ScheduleManager(
            contextWithServiceOverrides(emptyMap()),
            context.packageName,
            alarmManagerProvider = { null },
        )

        assertNull("AlarmManager absence keeps cancellation result unknown", absentManager.cancel(kind).existed)
        assertThrows(PendingIntent.CanceledException::class.java) { token.send() }
    }

    @Test
    fun successfulAlarmAndTokenCancellationIsReportedAsConfirmed() {
        val kind = AlarmEventKind.TEMPORARY_TEST_RELEASE
        val token = PendingIntent.getBroadcast(
            context,
            AlarmActions.requestCode(kind),
            android.content.Intent(context, ProtectionAlarmReceiver::class.java).apply {
                action = AlarmActions.actionFor(context.packageName, kind)
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        context.getSystemService(AlarmManager::class.java)!!.set(
            AlarmManager.RTC_WAKEUP,
            System.currentTimeMillis() + 60_000L,
            token,
        )

        val result = ScheduleManager(context, context.packageName).cancel(kind)

        assertEquals("both AlarmManager and token cancellation succeeded", true, result.existed)
        assertThrows(PendingIntent.CanceledException::class.java) { token.send() }
    }

    @Test
    fun nullableFactsRenderAsUnknownAndAnEmptyValueRemainsDistinct() {
        assertEquals("Unknown", ownerLabel(DeviceOwnerUiState.UNKNOWN))
        assertEquals("Unknown", setupOwnerLabel(DeviceOwnerUiState.UNKNOWN))
        assertEquals("Unknown", sessionLabel(ManagedSessionUiState.UNKNOWN))
        assertEquals("Unknown", exactAlarmLabel(null))
        assertEquals("Unknown", setupExactAlarmLabel(null))
        assertEquals("Unknown", diagnosticBoolean(null))
        assertEquals("Unknown", diagnosticYesNo(null))
        assertEquals("Yes", diagnosticYesNo(true))
        assertEquals("No", diagnosticYesNo(false))
        assertEquals("Unknown", diagnosticPackageList(null))
        assertEquals("(none)", diagnosticPackageList(emptySet()))
        assertEquals("Unknown / not recorded", diagnosticEpochLabel(null))
        assertEquals("Unknown", diagnosticPolicyResultLabel(null))
        assertEquals("Unknown", diagnosticRecoveryStatusLabel(null))
        assertEquals("Unknown", storedRecoveryFlagLabel(settingsLoaded = false, recoveryRequired = false))
        assertEquals("Not set", storedRecoveryFlagLabel(settingsLoaded = true, recoveryRequired = false))
    }

    @Test
    fun diagnosticsExportRequiresAStreamReportsRealWritesAndPropagatesCancellation() {
        assertFalse(writeDiagnosticsExport("diagnostics", null))

        val bytes = ByteArrayOutputStream()
        assertTrue(writeDiagnosticsExport("diagnostics", bytes))
        assertEquals("diagnostics", bytes.toString(Charsets.UTF_8.name()))

        val failingStream = object : OutputStream() {
            override fun write(b: Int) = throw IOException("destination failed")
        }
        assertFalse(writeDiagnosticsExport("diagnostics", failingStream))

        val canceledStream = object : OutputStream() {
            override fun write(b: Int) = throw CancellationException("export canceled")
        }
        assertThrows(CancellationException::class.java) {
            writeDiagnosticsExport("diagnostics", canceledStream)
        }
    }

    @Test
    fun aPausedActivityCannotEnterLockTaskButKeepsTheBestEffortStopRoute() {
        val app = RuntimeEnvironment.getApplication() as ShutdownProtectionApplication
        // This is the managed Activity path; ordinary installs now use no-reset mode.
        Shadows.shadowOf(app.getSystemService(DevicePolicyManager::class.java)).setDeviceOwner(
            android.content.ComponentName(app.packageName, "com.example.shutdownprotection.admin.ShutdownProtectionAdminReceiver"),
        )
        val controller = Robolectric.buildActivity(MainActivity::class.java)
        val activity = controller.create().start().resume().get()
        val session = app.container.lockTaskSession

        assertTrue(session.isEntryAvailable())
        controller.pause()
        assertFalse("onPause must stop admission immediately", session.isEntryAvailable())
        assertFalse("a queued start request is rechecked after pause", session.requestStartLockTask())
        assertTrue("the paused task owner remains available for best-effort stop", session.requestStopLockTask())
        controller.destroy()
        assertFalse(session.isEntryAvailable())
        assertTrue(activity.isDestroyed)
    }

    private fun contextWithServiceOverrides(
        services: Map<Class<*>, Any>,
        throwOnLookup: Boolean = false,
    ): Context = object : ContextWrapper(context) {
        override fun getApplicationContext(): Context = this

        override fun getSystemService(name: String): Any? {
            val trackedService = setOf(
                DevicePolicyManager::class.java,
                ActivityManager::class.java,
                KeyguardManager::class.java,
                AlarmManager::class.java,
                UserManager::class.java,
            ).firstOrNull { getSystemServiceName(it) == name }
            if (trackedService != null) {
                if (throwOnLookup) throw SecurityException("service lookup failed: ${trackedService.name}")
                return services[trackedService]
            }
            return super.getSystemService(name)
        }
    }

    private fun pendingIntent(kind: AlarmEventKind): PendingIntent? = PendingIntent.getBroadcast(
        context,
        AlarmActions.requestCode(kind),
        android.content.Intent(context, ProtectionAlarmReceiver::class.java).apply {
            action = AlarmActions.actionFor(context.packageName, kind)
        },
        PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE,
    )
}
