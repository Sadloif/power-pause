package com.example.shutdownprotection.ui

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
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/**
 * The daily interval editor (brief sections 7.1-7.2 and 19).
 *
 * The two fields are 24-hour `HH:mm` only. A localized 12-hour form is deliberately not
 * offered as the input format: `ProtectionSettings.parseMinuteOfDay` accepts only an
 * unambiguous 24-hour string, so a 12-hour entry would be rejected as malformed.
 */
@Composable
fun ScheduleScreen(
    state: UiState,
    onApply: (String, String) -> Unit,
    onRestore: () -> Unit,
    onBack: () -> Unit,
) {
    // Seeded once from the stored settings, and re-seeded whenever the stored minutes change
    // (for example after a successful apply, or when another component rewrites the schedule).
    var startText by remember(state.settings.startMinuteOfDay, state.settings.endMinuteOfDay) {
        mutableStateOf(state.settings.startLabel)
    }
    var endText by remember(state.settings.startMinuteOfDay, state.settings.endMinuteOfDay) {
        mutableStateOf(state.settings.endLabel)
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            text = "Schedule",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
        )

        // Required on every management screen (brief section 10).
        RestoreControl(onRestore = onRestore, enabled = !state.busy)

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                InfoRow(
                    label = "Stored start",
                    value = if (state.settingsLoaded) state.settings.startLabel else "Unknown",
                )
                InfoRow(
                    label = "Stored end",
                    value = if (state.settingsLoaded) state.settings.endLabel else "Unknown",
                )
                InfoRow(
                    label = "Interval shape",
                    value = if (!state.settingsLoaded) "Unknown" else if (state.settings.crossesMidnight) "Crosses midnight" else "Same day",
                )
                InfoRow(
                    label = "Persistent preference",
                    value = if (!state.settingsLoaded) "Unknown" else if (state.settings.enabled) "Enabled" else "Disabled",
                )
                InfoRow(
                    label = "Settings revision",
                    value = if (state.settingsLoaded) state.settings.revision.toString() else "Unknown",
                )
                InfoRow(
                    label = "Last reported state",
                    value = state.status?.state?.wireName ?: "not observed yet",
                )
            }
        }

        OutlinedTextField(
            value = startText,
            onValueChange = { startText = it },
            label = { Text("Restriction starts (HH:mm)") },
            singleLine = true,
            enabled = !state.busy && state.settingsLoaded,
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = endText,
            onValueChange = { endText = it },
            label = { Text("Restriction ends (HH:mm)") },
            singleLine = true,
            enabled = !state.busy && state.settingsLoaded,
            modifier = Modifier.fillMaxWidth(),
        )

        Text(
            text = "Enter both times in 24-hour HH:mm, e.g. 02:00. A 12-hour-only entry is not " +
                "accepted, because it is ambiguous.",
            style = MaterialTheme.typography.bodySmall,
        )

        Button(
            onClick = { onApply(startText, endText) },
            enabled = !state.busy && state.settingsLoaded,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("Apply")
        }

        state.validationError?.let { message ->
            ErrorCard(title = "Check the times", body = message)
        }

        Divider()

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("What this interval means", fontWeight = FontWeight.Bold)
                Text(
                    text = "• Same day: the start is earlier than the end, for example " +
                        "02:00 to 05:00.",
                    style = MaterialTheme.typography.bodySmall,
                )
                Text(
                    text = "• Crosses midnight: the start is later than the end, for example " +
                        "23:00 to 06:00. Restriction runs from the start until midnight and " +
                        "then from midnight until the end.",
                    style = MaterialTheme.typography.bodySmall,
                )
                Text(
                    text = "• An identical start and end is rejected. It is never guessed as a " +
                        "24-hour window or as a zero-length window.",
                    style = MaterialTheme.typography.bodySmall,
                )
                Text(
                    text = "• The interval repeats every calendar day in the device's current " +
                        "time zone. It is not pinned to the zone it was created in, so changing " +
                        "the device time zone moves the window with the device clock.",
                    style = MaterialTheme.typography.bodySmall,
                )
                Text(
                    text = "• Applying an edit can start or end restriction immediately, because " +
                        "the new window is evaluated against the current time rather than " +
                        "deferred to the next boundary. The last reported status may say exactly " +
                        "that: " + (state.status?.detail ?: "no status detail recorded yet."),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }

        Text(
            text = "If a protection operation is in progress, wait for it to finish before " +
                "restoring. Restore Normal Device Mode is available from the main screen and " +
                "this schedule screen afterward.",
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.Medium,
        )

        Button(onClick = onBack, modifier = Modifier.fillMaxWidth()) {
            Text("Back")
        }
    }
}
