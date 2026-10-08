package com.example.shutdownprotection

import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.pm.PackageManager
import com.example.shutdownprotection.ui.MainActivity
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class EditionIsolationTest {
    @Test fun simpleManifestHasNoManagedReceiverOrManagedAlarmPermissions() {
        val app = RuntimeEnvironment.getApplication() as ShutdownProtectionApplication
        @Suppress("DEPRECATION")
        val info = app.packageManager.getPackageInfo(app.packageName, PackageManager.GET_RECEIVERS or PackageManager.GET_PERMISSIONS)
        val names = info.receivers.orEmpty().map { it.name }.toSet()
        listOf("admin.ShutdownProtectionAdminReceiver", "admin.LockTaskPolicyUpdateReceiver",
            "scheduling.ProtectionAlarmReceiver", "scheduling.SystemEventReceiver").forEach { suffix ->
            assertEquals(suffix, BuildConfig.MANAGED_TOOLS, names.contains("com.example.shutdownprotection.$suffix"))
        }
        val permissions = info.requestedPermissions.orEmpty().toSet()
        listOf("android.permission.RECEIVE_BOOT_COMPLETED", "android.permission.SCHEDULE_EXACT_ALARM").forEach {
            assertEquals(it, BuildConfig.MANAGED_TOOLS, permissions.contains(it))
        }
    }

    @Test fun simpleStartupAndOrdinaryActivityDoNotConstructManagedWiring() {
        val app = RuntimeEnvironment.getApplication() as ShutdownProtectionApplication
        assertEquals(BuildConfig.MANAGED_TOOLS, app.managedContainerInitialized)
        val activity = Robolectric.buildActivity(MainActivity::class.java).create().start().resume()
        assertEquals(BuildConfig.MANAGED_TOOLS, app.managedContainerInitialized)
        activity.pause().stop().destroy()
    }

    @Test fun simpleDoesNotAutomaticallyOpenManagedModeEvenIfAnOwnerIsReported() {
        val app = RuntimeEnvironment.getApplication() as ShutdownProtectionApplication
        // Test shadow only. This never provisions a physical device.
        shadowOf(app.getSystemService(DevicePolicyManager::class.java)).setDeviceOwner(
            ComponentName(app.packageName, "com.example.shutdownprotection.admin.ShutdownProtectionAdminReceiver"))
        val activity = Robolectric.buildActivity(MainActivity::class.java).create().start().resume()
        if (!BuildConfig.MANAGED_TOOLS) assertFalse(app.managedContainerInitialized)
        else assertTrue(app.managedContainerInitialized)
        activity.pause().stop().destroy()
    }
}
