package com.example.shutdownprotection.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Divider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.shutdownprotection.R
import com.example.shutdownprotection.admin.LockTaskMasks
import com.example.shutdownprotection.admin.LockTaskRuntimeStates
import com.example.shutdownprotection.data.RuntimeObservation
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Diagnostics (brief section 20).
 *
 * The screen separates three different kinds of statement, and never merges them:
 *
 *  - **current status** read during the last reconciliation ([com.example.shutdownprotection.protection.ProtectionStatus]),
 *  - **last stored observation** read back from local storage ([com.example.shutdownprotection.data.RuntimeObservation]),
 *  - **not recorded / not observable** — said plainly rather than filled in with a plausible value.
 *
 * In particular there is no row claiming that a scheduled alarm actually fired: this build
 * does not record deliveries and delays separately, so it says so.
 */
@Composable
fun DiagnosticsScreen(
    state: UiState,
    onClear: () -> Unit,
    onExport: (android.net.Uri) -> Unit,
    onRefresh: () -> Unit,
    onRestore: () -> Unit,
    onBack: () -> Unit,
) {
    val status = state.status
    val observation = state.observation

    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("text/plain"),
    ) { uri -> uri?.let(onExport) }

    val newestFirst = state.events?.asReversed().orEmpty()

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item {
            Text(
                text = "Diagnostics",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
            )
        }

        // Required on every management screen (brief section 10).
        item {
            RestoreControl(onRestore = onRestore, enabled = !state.busy)
        }

        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onRefresh) { Text("Refresh") }
                // Clearing while an operation is in flight could race the operation's own
                // diagnostic write, so it is held until the operation settles.
                OutlinedButton(onClick = onClear, enabled = !state.busy) { Text("Clear") }
                Button(
                    onClick = { launcher.launch("shutdown-protection-diagnostics.txt") },
                ) {
                    Text("Export")
                }
            }
        }

        item {
            Text(
                text = "Export is deliberate and local: it writes the diagnostic text to the " +
                    "destination you pick in the system document picker. Nothing is uploaded " +
                    "automatically, and no diagnostic data leaves the device unless you choose " +
                    "a destination yourself.",
                style = MaterialTheme.typography.bodySmall,
            )
        }

        item {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("Settings (durable intent)", fontWeight = FontWeight.Bold)
                    InfoRow("Settings revision", if (state.settingsLoaded) state.settings.revision.toString() else "Unknown")
                    InfoRow(
                        "Persistent preference",
                        if (!state.settingsLoaded) "Unknown" else if (state.settings.enabled) "Enabled" else "Disabled",
                    )
                    InfoRow(
                        "Schedule (24-hour)",
                        if (state.settingsLoaded) "${state.settings.startLabel} – ${state.settings.endLabel}" else "Unknown",
                    )
                    InfoRow(
                        "Crosses midnight",
                        if (!state.settingsLoaded) "Unknown" else if (state.settings.crossesMidnight) "Yes" else "No",
                    )
                    InfoRow(
                        "Saved allowlist entries",
                        if (state.settingsLoaded) state.settings.allowedPackages.size.toString() else "Unknown",
                    )
                    InfoRow(
                        "Stored recovery flag",
                        if (!state.settingsLoaded) "Unknown" else if (state.settings.recoveryRequired) "Set" else "Not set",
                    )
                }
            }
        }

        item {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("Lock task masks", fontWeight = FontWeight.Bold)
                    InfoRow(
                        "Requested mask (current status)",
                        diagnosticMaskLabel(status?.requestedFeatures),
                    )
                    InfoRow(
                        "Effective mask (current status)",
                        diagnosticMaskLabel(status?.effectiveFeatures),
                    )
                    InfoRow(
                        "Requested mask (last observation)",
                        diagnosticMaskLabel(observation?.requestedFeatures),
                    )
                    InfoRow(
                        "Effective mask (last observation)",
                        diagnosticMaskLabel(observation?.effectiveFeatures),
                    )
                    InfoRow(
                        "Runtime lock task state",
                        LockTaskRuntimeStates.describe(status?.lockTaskState),
                    )
                    InfoRow(
                        "Runtime lock task state (last observation)",
                        LockTaskRuntimeStates.describe(observation?.lockTaskState),
                    )
                }
            }
        }

        item {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("Effective package list", fontWeight = FontWeight.Bold)
                    InfoRow("From current status", diagnosticPackageList(status?.effectivePackages))
                    InfoRow(
                        "From last observation",
                        diagnosticPackageList(observation?.effectivePackages),
                    )
                    InfoRow(
                        "Inside protected interval",
                        diagnosticYesNo(status?.insideProtectedInterval),
                    )
                    InfoRow(
                        "Current release submission confirmed",
                        diagnosticYesNo(status?.releasePlanSubmittedForCurrentRevision),
                    )
                    Text(
                        "“No” means current submission evidence is missing or unavailable; it " +
                            "does not prove that no alarm exists.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }

        item {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("Boot and unlock state (last observation)", fontWeight = FontWeight.Bold)
                    InfoRow("Boot completed", diagnosticBoolean(observation?.bootCompleted))
                    InfoRow("User unlocked", diagnosticBoolean(observation?.userUnlocked))
                    InfoRow("Exact scheduling capability", diagnosticBoolean(observation?.exactAlarmCapability))
                    InfoRow(
                        "Device Owner (last observation)",
                        diagnosticBoolean(observation?.deviceOwner),
                    )
                    InfoRow(
                        "Observation recorded at",
                        diagnosticEpochLabel(observation?.observedAtEpochMillis),
                    )
                }
            }
        }

        item {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("Next submitted alarms (last observation)", fontWeight = FontWeight.Bold)
                    InfoRow(
                        "Next start",
                        diagnosticEpochLabel(observation?.nextStartEpochMillis),
                    )
                    InfoRow(
                        "Next end",
                        diagnosticEpochLabel(observation?.nextEndEpochMillis),
                    )
                    InfoRow(
                        "Release fallback",
                        diagnosticEpochLabel(observation?.fallbackEpochMillis),
                    )
                    Text(
                        text = "These are the alarm times that were submitted, rendered in the " +
                            "device's current time zone. They are not evidence that an alarm " +
                            "fired.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }

        item {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("Deliveries and delays", fontWeight = FontWeight.Bold)
                    InfoRow("Last actual deliveries", "not recorded separately by this build")
                    InfoRow("Last actual delays", "not recorded separately by this build")
                }
            }
        }

        item {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("Policy results", fontWeight = FontWeight.Bold)
                    InfoRow(
                        "Last policy result code",
                        diagnosticPolicyResultLabel(observation),
                    )
                    InfoRow(
                        "Last policy result observed at",
                        if (observation == null) "Unknown"
                        else diagnosticEpochLabel(observation.lastPolicyObservedAtEpochMillis),
                    )
                    Text(
                        text = "An asynchronous policy result is not the same as a physical " +
                            "observation of the power menu.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }

        item {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("Recovery status", fontWeight = FontWeight.Bold)
                    InfoRow(
                        "Last observation",
                        diagnosticRecoveryStatusLabel(observation),
                    )
                    InfoRow(
                        "Incomplete recovery step (current status)",
                        status?.recoveryIncompleteStep
                            ?: if (status == null) "Unknown" else "none reported",
                    )
                    InfoRow(
                        "Stored recovery flag",
                        storedRecoveryFlagLabel(state.settingsLoaded, state.settings.recoveryRequired),
                    )
                }
            }
        }

        item {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("Device behavior validation", fontWeight = FontWeight.Bold)
                    Text(
                        text = stringResource(R.string.behavior_not_validated),
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        text = "Policy readback and runtime lock-task state are API observations. " +
                            "They do not prove that the physical Power Off and Restart menu is " +
                            "absent, which requires a manual check on the device.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }

        item {
            Divider()
        }

        item {
            Text("Event log (newest first)", fontWeight = FontWeight.Bold)
        }

        item {
            Text(
                text = "Retention is bounded to 1000 records or 1 MiB; the oldest records are " +
                    "discarded first. Long digit runs are redacted on write.",
                style = MaterialTheme.typography.bodySmall,
            )
        }

        if (state.events == null) {
            item {
                Text("Diagnostic events are unavailable (read failed or not yet loaded).", style = MaterialTheme.typography.bodySmall)
            }
        } else if (newestFirst.isEmpty()) {
            item {
                Text("No diagnostic events recorded.", style = MaterialTheme.typography.bodySmall)
            }
        }

        items(newestFirst) { event ->
            Text(
                text = "${diagnosticTimestamp(event.timestampMillis)} | ${event.kind} | " +
                    "rev=${event.revision} | ${event.message}",
                style = MaterialTheme.typography.bodySmall,
            )
        }

        item {
            Button(onClick = onBack, modifier = Modifier.fillMaxWidth()) {
                Text("Back")
            }
        }
    }
}

private val DIAGNOSTIC_TIME_FORMATTER: DateTimeFormatter =
    DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss", Locale.ROOT)

private fun diagnosticTimestamp(epochMillis: Long): String =
    Instant.ofEpochMilli(epochMillis)
        .atZone(ZoneId.systemDefault())
        .format(DIAGNOSTIC_TIME_FORMATTER)

internal fun diagnosticEpochLabel(epochMillis: Long?): String =
    if (epochMillis == null) "Unknown / not recorded" else diagnosticTimestamp(epochMillis)

internal fun diagnosticMaskLabel(mask: Int?): String =
    if (mask == null) "unknown" else LockTaskMasks.describe(mask)

internal fun diagnosticBoolean(value: Boolean?): String = when (value) {
    true -> "true"
    false -> "false"
    null -> "Unknown"
}

internal fun diagnosticYesNo(value: Boolean?): String = when (value) {
    true -> "Yes"
    false -> "No"
    null -> "Unknown"
}

internal fun storedRecoveryFlagLabel(settingsLoaded: Boolean, recoveryRequired: Boolean): String =
    if (!settingsLoaded) "Unknown" else if (recoveryRequired) "Set" else "Not set"

internal fun diagnosticPolicyResultLabel(observation: RuntimeObservation?): String =
    if (observation == null) "Unknown"
    else observation.lastPolicyResultCode?.toString() ?: "not recorded"

internal fun diagnosticRecoveryStatusLabel(observation: RuntimeObservation?): String =
    if (observation == null) "Unknown"
    else observation.recoveryStatus.takeIf { it.isNotEmpty() } ?: "not recorded"

internal fun diagnosticPackageList(packages: Set<String>?): String = when {
    packages == null -> "Unknown"
    packages.isEmpty() -> "(none)"
    else -> packages.sorted().joinToString(", ")
}
