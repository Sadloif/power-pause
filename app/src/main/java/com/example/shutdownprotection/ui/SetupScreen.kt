package com.example.shutdownprotection.ui

import android.os.Build
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Divider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.shutdownprotection.BuildConfig
import com.example.shutdownprotection.R
import com.example.shutdownprotection.admin.LockTaskRuntimeStates
import com.example.shutdownprotection.admin.ShutdownProtectionAdminReceiver
import com.example.shutdownprotection.protection.DeviceOwnerUiState

/**
 * Setup and prerequisites (brief section 19).
 *
 * Every row here is either a fact read from the platform, a fact carried by [UiState], or an
 * explicit statement that the fact is *not observable*. Nothing on this screen may imply that
 * physical power-menu behavior has been validated: that requires manual device evidence, so
 * the supported-device test row always reports "physical device behavior NOT validated".
 */
@Composable
fun SetupScreen(state: UiState, onRestore: () -> Unit, onBack: () -> Unit) {
    val status = state.status

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            text = "Setup and prerequisites",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
        )

        // Required on every management screen (brief section 10).
        RestoreControl(onRestore = onRestore, enabled = !state.busy)

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                InfoRow(
                    label = "Android API level",
                    value = "${Build.VERSION.SDK_INT} (${Build.VERSION.RELEASE})",
                )
                InfoRow(
                    label = "Device Owner state",
                    value = setupOwnerLabel(status?.deviceOwnerUiState),
                )
                InfoRow(
                    label = "Admin receiver",
                    value = ShutdownProtectionAdminReceiver::class.java.name,
                )
                InfoRow(
                    label = "Default launcher package",
                    value = state.defaultLauncherPackage ?: "Unknown",
                )
                InfoRow(
                    label = "Package visibility",
                    value = "narrow HOME/LAUNCHER queries only; QUERY_ALL_PACKAGES is not requested",
                )
                InfoRow(
                    label = "Runtime Lock Task state",
                    value = status?.lockTaskState?.let { LockTaskRuntimeStates.describe(it) }
                        ?: "Unknown (read failed)",
                )
                InfoRow(
                    label = "Exact scheduling capability",
                    value = setupExactAlarmLabel(status?.exactAlarmCapability),
                )
                InfoRow(
                    label = "Relevant exemptions",
                    value = "not observable through public API",
                )
                InfoRow(
                    label = "Recovery readiness",
                    value = setupRecoveryLabel(status?.recoveryIncompleteStep, status == null),
                )
                InfoRow(
                    label = "Supported-device test status",
                    value = "physical device behavior NOT validated",
                )
                InfoRow(
                    label = "Secure screen lock present",
                    value = when (state.deviceSecure) {
                        true -> "Yes"
                        false -> "No"
                        null -> "Unknown"
                    },
                )
            }
        }

        if (status?.deviceOwnerUiState != DeviceOwnerUiState.READY) {
            ErrorCard(
                title = "Device Owner required",
                body = stringResource(R.string.device_owner_required),
            )
            Text(
                text = "Current Device Owner presentation: " +
                    setupOwnerLabel(status?.deviceOwnerUiState) +
                    ". Nothing on this device can restrict the power menu until Device Owner " +
                    "provisioning is complete.",
                style = MaterialTheme.typography.bodySmall,
            )
        }

        // Separate the four actions explicitly (repair R10 step 3). Users otherwise reasonably
        // assume the recovery button also removes management, which it does not.
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Four separate actions", fontWeight = FontWeight.Bold)
                Text(
                    text = "1. Installing this app does not grant any policy capability.\n" +
                        "2. Enrolling as Device Owner grants management authority but does not " +
                        "restrict anything by itself.\n" +
                        "3. Restore Normal Device Mode ends the managed session and restores the " +
                        "original policy — but it does NOT remove Device Owner management.\n" +
                        "4. Removing Device Owner management is a separate step, and in this " +
                        "build it is a debug/test-only escape route that needs authorized ADB.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }

        Divider()

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Build identity", fontWeight = FontWeight.Bold)
                InfoRow(label = "Application ID", value = BuildConfig.APPLICATION_ID)
                InfoRow(label = "Version name", value = BuildConfig.VERSION_NAME)
                InfoRow(label = "Debug build", value = if (BuildConfig.DEBUG) "Yes" else "No")
            }
        }

        Text(
            text = "The admin receiver class name above is compiled from the fixed Kotlin " +
                "namespace com.example.shutdownprotection, while the Application ID is the " +
                "installed identity and can be overridden at build time. The full device-admin " +
                "component for this installation is therefore " +
                "${BuildConfig.APPLICATION_ID}/${ShutdownProtectionAdminReceiver::class.java.name}.",
            style = MaterialTheme.typography.bodySmall,
        )

        Text(
            text = "Scheduling capability is reported from the last observed readback only. It " +
                "does not prove that an alarm will actually fire at the planned time.",
            style = MaterialTheme.typography.bodySmall,
        )

        Button(onClick = onBack, modifier = Modifier.fillMaxWidth()) {
            Text("Back")
        }
    }
}

internal fun setupOwnerLabel(state: DeviceOwnerUiState?): String = when (state) {
    DeviceOwnerUiState.READY -> "Ready"
    DeviceOwnerUiState.REQUIRED -> "Required"
    DeviceOwnerUiState.LOST -> "Lost"
    DeviceOwnerUiState.UNKNOWN -> "Unknown"
    null -> "Unknown"
}

internal fun setupExactAlarmLabel(capability: Boolean?): String = when (capability) {
    true -> "Available (as last observed)"
    false -> "Unavailable (as last observed)"
    null -> "Unknown"
}

private fun setupRecoveryLabel(incompleteStep: String?, statusMissing: Boolean): String = when {
    statusMissing -> "Not observed yet"
    incompleteStep == null -> "Ready — no incomplete recovery step reported"
    else -> "Not ready — failing step: $incompleteStep"
}
