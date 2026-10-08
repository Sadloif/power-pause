package com.example.shutdownprotection.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Divider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.shutdownprotection.BuildConfig
import com.example.shutdownprotection.R
import com.example.shutdownprotection.protection.DeviceOwnerUiState
import com.example.shutdownprotection.protection.ManagedSessionUiState
import com.example.shutdownprotection.protection.PowerMenuUiState
import com.example.shutdownprotection.protection.ProtectionState
import com.example.shutdownprotection.protection.toPowerMenuClaimInputs

/** Which screen is showing. Plain state rather than a navigation library: six screens. */
private enum class Screen { MAIN, SETUP, SCHEDULE, ALLOWED_APPS, DIAGNOSTICS, POC }

@Composable
fun MainScreen(viewModel: MainViewModel) {
    val state by viewModel.state.collectAsState()
    var screen by remember { mutableStateOf(Screen.MAIN) }
    var showEnableDisclosure by remember { mutableStateOf(false) }

    BackHandler(enabled = screen != Screen.MAIN) { screen = Screen.MAIN }

    when (screen) {
        Screen.SETUP -> SetupScreen(
            state = state,
            onRestore = { viewModel.disableAndRestore() },
            onBack = { screen = Screen.MAIN },
        )
        Screen.SCHEDULE -> ScheduleScreen(
            state = state,
            onApply = { start, end -> viewModel.applySchedule(start, end) },
            onRestore = { viewModel.disableAndRestore() },
            onBack = { screen = Screen.MAIN },
        )
        Screen.ALLOWED_APPS -> AllowedAppsScreen(
            state = state,
            onSave = { packages -> viewModel.saveAllowedPackages(packages) },
            onRestore = { viewModel.disableAndRestore() },
            onBack = { screen = Screen.MAIN },
        )
        Screen.DIAGNOSTICS -> DiagnosticsScreen(
            state = state,
            onClear = { viewModel.clearDiagnostics() },
            onExport = { uri -> viewModel.exportTo(uri) },
            onRefresh = { viewModel.refreshDiagnostics() },
            onRestore = { viewModel.disableAndRestore() },
            onBack = { screen = Screen.MAIN },
        )
        Screen.POC -> PocScreen(
            state = state,
            onStartSession = { viewModel.startPocSession() },
            onAllowMenu = { viewModel.pocAllowMenu() },
            onRestrictMenu = { viewModel.pocRestrictMenu() },
            onRestore = { viewModel.disableAndRestore() },
            onBack = { screen = Screen.MAIN },
        )
        Screen.MAIN -> MainContent(
            state = state,
            viewModel = viewModel,
            onOpen = { screen = it },
            onEnableClicked = { showEnableDisclosure = true },
        )
    }

    if (showEnableDisclosure) {
        EnableDisclosureDialog(
            onDismiss = { showEnableDisclosure = false },
            onConfirm = {
                showEnableDisclosure = false
                viewModel.arm()
            },
        )
    }

    state.message?.let { message ->
        AlertDialog(
            onDismissRequest = { viewModel.dismissMessage() },
            confirmButton = { TextButton(onClick = { viewModel.dismissMessage() }) { Text("OK") } },
            title = { Text("Status") },
            text = { Text(message) },
        )
    }
}

@Composable
private fun MainContent(
    state: UiState,
    viewModel: MainViewModel,
    onOpen: (Screen) -> Unit,
    onEnableClicked: () -> Unit,
) {
    val status = state.status
    val claimRestricted = status?.toPowerMenuClaimInputs()?.canClaimRestricted() == true
    val context = LocalContext.current

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            text = stringResource(R.string.app_name),
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
        )

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Caution: experimental managed mode", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.error)
                Text("For dedicated devices with existing Device Owner enrollment. This mode can restrict apps and system controls. Physical power-menu behaviour is not yet verified. Do not experiment on an everyday phone.")
                Text("These settings are separate from the Accessibility schedule. Restore Normal Device Mode reverses app-applied managed rules; it does not factory-reset the phone. Enrollment normally requires initial setup or a factory reset.", style = MaterialTheme.typography.bodySmall)
            }
        }

        if (state.busy) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(modifier = Modifier.height(20.dp))
                Spacer(Modifier.fillMaxWidth(0.05f))
                Text("Working…")
            }
        }

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                InfoRow("Device Owner", ownerLabel(status?.deviceOwnerUiState))
                InfoRow(
                    "User preference",
                    if (!state.settingsLoaded) "Unknown" else if (state.settings.enabled) "Enabled" else "Disabled",
                )
                InfoRow("Managed session", sessionLabel(status?.managedSessionUiState))
                InfoRow(
                    "Power menu",
                    when (status?.powerMenuUiState) {
                        // Never print "Restricted" unless all seven conditions hold
                        // (brief section 19). A pending or unverified claim is "Not verified".
                        PowerMenuUiState.RESTRICTED -> if (claimRestricted) "Restricted" else "Not verified"
                        PowerMenuUiState.ALLOWED -> "Allowed"
                        else -> "Not verified"
                    },
                )
                InfoRow(
                    "Schedule (24-hour)",
                    if (!state.settingsLoaded) "Unknown" else {
                        "${state.settings.startLabel} – ${state.settings.endLabel}" +
                            if (state.settings.crossesMidnight) " (crosses midnight)" else ""
                    },
                )
                InfoRow("Device time zone", viewModel.currentZoneId())
                InfoRow(
                    "Next planned transition",
                    if (state.settingsLoaded) viewModel.describeNextTransition() else "Unknown",
                )
                InfoRow(
                    "Exact scheduling",
                    exactAlarmLabel(status?.exactAlarmCapability) +
                        " — last observed ${viewModel.lastObservationText()}",
                )
            }
        }

        // The second situation has a required label (brief section 3.3): "Power menu allowed -
        // managed session active". It is shown as its own line so it is never confused with
        // "normal device mode", which this app never claims.
        if (status?.state == ProtectionState.ARMED_POWER_MENU_ALLOWED) {
            Text(
                text = stringResource(R.string.second_situation_label),
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Bold,
            )
        }

        // A concrete corrective action, when the coordinator has one (brief section 19 item 8).
        status?.userActionRequired?.let { action ->
            ErrorCard(
                title = "Action needed",
                body = action,
                action = if (status.state == ProtectionState.RECOVERY_FAILED) {
                    stringResource(R.string.restore_normal_device_mode)
                } else {
                    null
                },
                onAction = if (status.state == ProtectionState.RECOVERY_FAILED) {
                    { viewModel.disableAndRestore() }
                } else {
                    null
                },
            )
        }

        val validation = state.settings.validate()
        if (validation is com.example.shutdownprotection.data.SettingsValidation.Invalid) {
            ErrorCard(
                title = "Configuration error",
                body = validation.message,
                action = "Change start/end",
                onAction = { onOpen(Screen.SCHEDULE) },
            )
        }
        state.validationError?.let { ErrorCard(title = "Check the times", body = it) }
        if (status != null && status.state == ProtectionState.CONFIGURATION_ERROR) {
            ErrorCard(title = "Configuration error", body = status.detail)
        }
        if (status != null && status.state == ProtectionState.RECOVERY_FAILED) {
            ErrorCard(
                title = stringResource(R.string.recovery_incomplete),
                body = "Failing step: ${status.recoveryIncompleteStep ?: "unknown"}. ${status.detail}",
            )
        }
        if (status?.state == ProtectionState.EXACT_SCHEDULING_UNAVAILABLE) {
            // Brief section 15.3: when unavailable and the app is foreground, explain the
            // requirement and offer the documented Alarms & reminders settings intent.
            ErrorCard(
                title = "Exact alarms unavailable",
                body = status.detail,
                action = "Open Alarms & reminders settings",
                onAction = {
                    runCatching { context.startActivity(viewModel.exactAlarmSettingsIntent()) }
                },
            )
        }
        if (!viewModel.deviceBehaviorValidated()) {
            Text(
                text = stringResource(R.string.behavior_not_validated),
                style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.Bold,
            )
        }

        Divider()

        Button(
            onClick = onEnableClicked,
            enabled = !state.busy && state.settingsLoaded,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(if (state.settings.enabled) "Resume managed session" else "Enable / resume managed session")
        }
        Button(
            onClick = { viewModel.disableAndRestore() },
            enabled = !state.busy,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(stringResource(R.string.restore_normal_device_mode))
        }
        OutlinedButton(onClick = { onOpen(Screen.SCHEDULE) }, modifier = Modifier.fillMaxWidth()) {
            Text("Change start / end")
        }
        OutlinedButton(onClick = { onOpen(Screen.ALLOWED_APPS) }, modifier = Modifier.fillMaxWidth()) {
            Text("Manage allowed applications")
        }
        OutlinedButton(onClick = { onOpen(Screen.DIAGNOSTICS) }, modifier = Modifier.fillMaxWidth()) {
            Text("Diagnostics")
        }
        OutlinedButton(onClick = { onOpen(Screen.SETUP) }, modifier = Modifier.fillMaxWidth()) {
            Text("Setup and prerequisites")
        }
        if (BuildConfig.DEBUG) {
            OutlinedButton(onClick = { onOpen(Screen.POC) }, modifier = Modifier.fillMaxWidth()) {
                Text("Development test controls (debug only)")
            }
        }

        Text(
            text = stringResource(R.string.feature_explanation),
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

@Composable
private fun EnableDisclosureDialog(onDismiss: () -> Unit, onConfirm: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Before you enable") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.feature_explanation))
                Text(
                    "This runs a managed Lock Task session for the whole armed period, so the " +
                        "device behaves like a dedicated device, not an ordinary phone:",
                )
                Text("• Quick Settings is expected to remain unavailable. This is a known limitation of the documented notifications feature and is not restored by adding unrelated flags.")
                Text("• Some application workflows may be restricted, including permission dialogs, authentication handoffs, and share or document pickers.")
                Text("• Hardware forced restart, recovery mode, battery loss, and every OEM shutdown path remain outside this feature's guarantee.")
                Text(
                    "• Restore Normal Device Mode is available from the main screen after any " +
                        "active protection operation finishes.",
                )
            }
        },
        confirmButton = { Button(onClick = onConfirm) { Text("Enable protection") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
internal fun InfoRow(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
        Text(value, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
internal fun ErrorCard(title: String, body: String, action: String? = null, onAction: (() -> Unit)? = null) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(title, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.error)
            Text(body, style = MaterialTheme.typography.bodySmall)
            if (action != null && onAction != null) {
                TextButton(onClick = onAction) { Text(action) }
            }
        }
    }
}

/**
 * The recovery control, required on every screen where protection can be managed
 * (brief section 10). It is deliberately a plain prominent button with no precondition: it must
 * work outside the protected interval as well as inside it, and must not require network access,
 * a subscription, or a valid schedule.
 *
 * Back navigation always reaches the main screen's copy too, but relying on that would mean the
 * user has to leave the screen they are on to find the way out.
 */
@Composable
internal fun RestoreControl(onRestore: () -> Unit, enabled: Boolean = true) {
    Button(
        onClick = onRestore,
        enabled = enabled,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(stringResource(R.string.restore_normal_device_mode))
    }
}

internal fun ownerLabel(state: DeviceOwnerUiState?): String = when (state) {
    DeviceOwnerUiState.READY -> "Ready"
    DeviceOwnerUiState.LOST -> "Lost"
    DeviceOwnerUiState.REQUIRED -> "Required"
    DeviceOwnerUiState.UNKNOWN -> "Unknown"
    null -> "Unknown"
}

internal fun sessionLabel(state: ManagedSessionUiState?): String = when (state) {
    ManagedSessionUiState.ACTIVE -> "Active"
    ManagedSessionUiState.WAITING_FOR_UNLOCK -> "Waiting for unlock"
    ManagedSessionUiState.NOT_ACTIVE -> "Not active"
    ManagedSessionUiState.UNKNOWN -> "Unknown"
    null -> "Unknown"
}

internal fun exactAlarmLabel(capability: Boolean?): String = when (capability) {
    true -> "Available"
    false -> "Unavailable"
    null -> "Unknown"
}
