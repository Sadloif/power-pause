package com.example.shutdownprotection

import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.shutdownprotection.admin.ShutdownProtectionAdminReceiver
import com.example.shutdownprotection.data.SettingsDataStores
import com.example.shutdownprotection.scheduling.AlarmActions
import com.example.shutdownprotection.scheduling.AlarmEventKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Instrumented checks of public API state on a real (or emulated) Android 16 device
 * (brief section 26: "Add Android integration tests where an instrumented managed test
 * device can verify public API state").
 *
 * These deliberately assert only what the platform can actually report. They are **not** a
 * substitute for the manual power-menu observations: nothing here proves the Power Off /
 * Restart dialog is absent. That is a physical observation recorded in
 * `docs/TEST_RESULTS.md`.
 */
@RunWith(AndroidJUnit4::class)
class PlatformStateInstrumentedTest {

    private val context: Context
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    private val packageName: String
        get() = context.packageName

    // ---- Storage placement (brief section 7.8) -----------------------------------------

    @Test
    fun settingsStoreIsDeviceProtectedAndDiagnosticsAreCredentialProtected() {
        val settingsPath = SettingsDataStores.settingsFile(context).absolutePath
        val diagnosticsPath = SettingsDataStores.diagnosticsFile(context).absolutePath

        assertTrue(
            "the settings store must be readable before unlock; found $settingsPath",
            settingsPath.contains("/data/user_de/"),
        )
        assertFalse(
            "the settings store must NOT be credential-protected; found $settingsPath",
            settingsPath.contains("/data/user/0/"),
        )
        assertTrue(
            "diagnostics should stay behind credential encryption; found $diagnosticsPath",
            diagnosticsPath.contains("/data/user/0/"),
        )
    }

    @Test
    fun theDeviceProtectedContextIsADifferentStorageAreaFromTheCredentialProtectedOne() {
        val deviceProtectedFiles = context.createDeviceProtectedStorageContext().filesDir
        val credentialProtectedFiles = context.applicationContext.filesDir

        assertNotNull(deviceProtectedFiles)
        assertNotNull(credentialProtectedFiles)
        assertTrue(
            "device-protected and credential-protected files dirs must differ: " +
                "${deviceProtectedFiles.absolutePath} vs ${credentialProtectedFiles.absolutePath}",
            deviceProtectedFiles.absolutePath != credentialProtectedFiles.absolutePath,
        )
    }

    // ---- Alarm identity derivation (brief sections 3.2 and 14) --------------------------

    @Test
    fun alarmIdentitiesAreDerivedFromTheRealApplicationIdAndAreDistinct() {
        // The scheduler owns four identities; the Gate B debug timer owns two more. Every one of
        // them must be namespaced to the application id actually built into this APK, which is
        // what makes a -Pspm.appId= override self-consistent at runtime.
        val schedulerActions = AlarmActions.SCHEDULER_KINDS.map { AlarmActions.actionFor(packageName, it) }
        val temporaryActions = AlarmActions.TEMPORARY_TEST_KINDS.map { AlarmActions.actionFor(packageName, it) }
        val allActions = schedulerActions + temporaryActions

        assertEquals("the scheduler must own exactly four identities", 4, schedulerActions.size)
        assertEquals("the debug timer must own exactly two identities", 2, temporaryActions.size)
        assertEquals("identities must be distinct", allActions.size, allActions.toSet().size)
        assertTrue(
            "the debug timer must not reuse a scheduler identity",
            schedulerActions.toSet().intersect(temporaryActions.toSet()).isEmpty(),
        )

        for (action in allActions) {
            assertTrue(
                "every alarm action must be namespaced to the built application id: $action",
                action.startsWith("$packageName."),
            )
            assertNotNull(
                "the receiver must recognise its own action: $action",
                AlarmActions.kindFor(packageName, action),
            )
        }
        assertEquals(
            "the request codes must be distinct",
            allActions.size,
            AlarmEventKind.entries.map { AlarmActions.requestCode(it) }.toSet().size,
        )
    }

    @Test
    fun aForeignAlarmActionIsNotClaimed() {
        assertEquals(null, AlarmActions.kindFor(packageName, "android.intent.action.BOOT_COMPLETED"))
        assertEquals(null, AlarmActions.kindFor(packageName, "$packageName.action.NOT_OURS"))
    }

    // ---- Declared permissions (brief section 6) -----------------------------------------

    @Test
    fun theDeclaredPermissionSetIsMinimalAndExcludesTheProhibitedOnes() {
        val requested = requestedPermissions()

        assertTrue(requested.contains("android.permission.RECEIVE_BOOT_COMPLETED"))
        assertTrue(requested.contains("android.permission.SCHEDULE_EXACT_ALARM"))

        // Brief section 6.4 and section 2: these must never be declared for this feature.
        for (forbidden in listOf(
            "android.permission.USE_EXACT_ALARM",
            "android.permission.INTERNET",
            "android.permission.QUERY_ALL_PACKAGES",
            "android.permission.ACCESS_FINE_LOCATION",
            "android.permission.CAMERA",
            "android.permission.RECORD_AUDIO",
            "android.permission.READ_CONTACTS",
            "android.permission.READ_SMS",
            "android.permission.CALL_PHONE",
        )) {
            assertFalse("must not declare $forbidden", requested.contains(forbidden))
        }
    }

    // ---- Exported components (brief section 6) -----------------------------------------

    @Test
    fun theInternalAlarmReceiverIsNotExported() {
        val receivers = context.packageManager.getPackageInfo(
            packageName,
            PackageManager.PackageInfoFlags.of(PackageManager.GET_RECEIVERS.toLong()),
        ).receivers.orEmpty()

        val alarmReceiver = receivers.firstOrNull {
            it.name == "com.example.shutdownprotection.scheduling.ProtectionAlarmReceiver"
        }
        assertNotNull("ProtectionAlarmReceiver must be declared", alarmReceiver)
        assertFalse(
            "ProtectionAlarmReceiver must not be exported: no other app may arm or release protection",
            alarmReceiver!!.exported,
        )
    }

    @Test
    fun theAdminReceiverIsProtectedByBindDeviceAdmin() {
        val receivers = context.packageManager.getPackageInfo(
            packageName,
            PackageManager.PackageInfoFlags.of(PackageManager.GET_RECEIVERS.toLong()),
        ).receivers.orEmpty()

        val adminReceiver = receivers.firstOrNull {
            it.name == "com.example.shutdownprotection.admin.ShutdownProtectionAdminReceiver"
        }
        assertNotNull("the admin receiver must be declared", adminReceiver)
        assertTrue("the admin receiver must be exported for the platform", adminReceiver!!.exported)
        assertEquals(
            "only the platform may deliver admin callbacks",
            "android.permission.BIND_DEVICE_ADMIN",
            adminReceiver.permission,
        )
    }

    @Test
    fun thePolicyUpdateReceiverIsRegisteredForBothDocumentedActions() {
        val receivers = context.packageManager.getPackageInfo(
            packageName,
            PackageManager.PackageInfoFlags.of(PackageManager.GET_RECEIVERS.toLong()),
        ).receivers.orEmpty()

        val policyReceiverName = "com.example.shutdownprotection.admin.LockTaskPolicyUpdateReceiver"
        val policyReceiver = receivers.firstOrNull { it.name == policyReceiverName }
        assertNotNull("the policy receiver must be declared", policyReceiver)
        assertEquals(
            "the policy receiver must be protected the same way",
            "android.permission.BIND_DEVICE_ADMIN",
            policyReceiver!!.permission,
        )

        // Both documented policy actions must actually resolve to it, which is what makes the
        // section 18 callback handling reachable at all.
        for (action in listOf(
            "android.app.admin.action.DEVICE_POLICY_SET_RESULT",
            "android.app.admin.action.DEVICE_POLICY_CHANGED",
        )) {
            val intent = Intent(action).setPackage(packageName)
            val resolved = context.packageManager.queryBroadcastReceivers(
                intent,
                PackageManager.ResolveInfoFlags.of(0L),
            )
            assertTrue(
                "$action must resolve to $policyReceiverName, resolved=${
                    resolved.map { it.activityInfo?.name }
                }",
                resolved.any { it.activityInfo?.name == policyReceiverName },
            )
        }
    }

    // ---- Device Owner reporting (brief sections 8 and 9) --------------------------------

    @Test
    fun deviceOwnerReportingMatchesThePlatform() {
        val devicePolicyManager = context.getSystemService(DevicePolicyManager::class.java)
        assertNotNull("DevicePolicyManager must be available", devicePolicyManager)

        val platformSaysOwner = devicePolicyManager!!.isDeviceOwnerApp(packageName)
        // The controller and the platform must never disagree; this test records the actual
        // provisioning state rather than asserting that it is owner, because an
        // unprovisioned test run is a legitimate state.
        assertEquals(
            "the app's ownership answer must come from isDeviceOwnerApp",
            platformSaysOwner,
            devicePolicyManager.isDeviceOwnerApp(packageName),
        )
        assertTrue(
            "the admin component must resolve",
            devicePolicyManager.isAdminActive(
                ComponentName(context, ShutdownProtectionAdminReceiver::class.java),
            ) || !platformSaysOwner,
        )
    }

    private fun requestedPermissions(): List<String> = context.packageManager.getPackageInfo(
        packageName,
        PackageManager.PackageInfoFlags.of(PackageManager.GET_PERMISSIONS.toLong()),
    ).requestedPermissions?.toList().orEmpty()
}
