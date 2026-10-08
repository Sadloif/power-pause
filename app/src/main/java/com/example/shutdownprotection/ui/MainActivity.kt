package com.example.shutdownprotection.ui

import android.os.Bundle
import android.app.admin.DevicePolicyManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import com.example.shutdownprotection.ShutdownProtectionApplication
import com.example.shutdownprotection.BuildConfig

/**
 * The single activity. It exists to host Compose and to be the eligible foreground surface
 * from which a managed session is entered (brief section 9.1).
 *
 * The activity registers itself with the container's lock-task session controller while it
 * is resumed, so `startLockTask()` is only ever called from an eligible foreground activity.
 */
class MainActivity : ComponentActivity() {

    private var managedMode by mutableStateOf(false)

    private val container get() = ShutdownProtectionApplication.container(this)

    private val viewModel: MainViewModel by viewModels { MainViewModel.Factory(container) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        managedMode = BuildConfig.MANAGED_TOOLS && runCatching { getSystemService(DevicePolicyManager::class.java).isDeviceOwnerApp(packageName) }.getOrDefault(false)
        setContent {
            PowerPauseTheme {
                Surface {
                    if (managedMode) MainScreen(viewModel) else NoResetScreen {
                        if (!BuildConfig.MANAGED_TOOLS) return@NoResetScreen
                        managedMode = true
                        container.lockTaskSession.attach(this)
                        viewModel.onForeground()
                        viewModel.refreshSelectableApps()
                    }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        if (!managedMode) return
        container.lockTaskSession.attach(this)
        viewModel.onForeground()
        viewModel.refreshSelectableApps()
    }

    override fun onPause() {
        if (managedMode) container.lockTaskSession.markPaused(this)
        super.onPause()
    }

    override fun onDestroy() {
        if (managedMode) container.lockTaskSession.detach(this)
        super.onDestroy()
    }
}
