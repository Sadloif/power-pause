package com.example.shutdownprotection

import android.content.pm.PackageManager
import com.example.shutdownprotection.ui.MainActivity
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29, 30, 31, 32, 33, 34, 35, 36])
class EditionIsolationTest {
    @Test fun safeManifestHasNoManagedReceiversOrAlarmPermissions() {
        val app = RuntimeEnvironment.getApplication()
        @Suppress("DEPRECATION") val info = app.packageManager.getPackageInfo(app.packageName, PackageManager.GET_RECEIVERS or PackageManager.GET_PERMISSIONS)
        assertTrue(info.receivers.orEmpty().none { it.name.startsWith("com.example.shutdownprotection.") })
        assertFalse(info.requestedPermissions.orEmpty().contains("android.permission.RECEIVE_BOOT_COMPLETED"))
        assertFalse(info.requestedPermissions.orEmpty().contains("android.permission.SCHEDULE_EXACT_ALARM"))
    }
    @Test fun managedImplementationClassesAreNotInSafeEdition() {
        listOf("AppContainer", "admin.DevicePolicyController", "protection.RecoveryManager").forEach {
            try { Class.forName("com.example.shutdownprotection.$it"); fail("Managed class present: $it") }
            catch (_: ClassNotFoundException) { }
        }
    }
    @Test fun safeApplicationAndActivityLaunchWithoutManagedWiring() {
        assertEquals(NoResetApplication::class.java, RuntimeEnvironment.getApplication().javaClass)
        val activity = Robolectric.buildActivity(MainActivity::class.java).create().start().resume()
        activity.pause().stop().destroy()
    }
}
