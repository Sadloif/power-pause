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
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.shutdownprotection.R
import com.example.shutdownprotection.admin.LockTaskMasks
import com.example.shutdownprotection.admin.LockTaskRuntimeStates
import com.example.shutdownprotection.protection.toPowerMenuClaimInputs

/**
 * Gate B proof-of-concept controls (brief section 22). Debug builds only.
 *
 * This screen drives a *temporary* debug session and deliberately does not reuse any
 * production success wording: the daily "enabled" preference is a separate, persistent piece
 * of user intent, and a value here never means the daily schedule is protecting the device.
 */
@Composable
fun PocScreen(
    state: UiState,
    onStartSession: () -> Unit,
    onAllowMenu: () -> Unit,
    onRestrictMenu: () -> Unit,
    onRestore: () -> Unit,
    onBack: () -> Unit,
) {
    val status = state.status
    // Null means the runtime read FAILED. It is carried through as unknown rather than being
    // substituted with NONE, which would make an unreadable state look like a normal one
    // (review-2 section 4.1).
    val runtimeLockState = status?.lockTaskState

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            text = "Development test controls",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
        )

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    text = "Temporary debug session — not the daily schedule",
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.error,
                )
                Text(
                    text = "This screen starts a temporary managed session for a proof-of-concept " +
                        "check. It is a separate mechanism from the persistent daily enabled " +
                        "preference, and it is not evidence that the daily schedule works.",
                    style = MaterialTheme.typography.bodySmall,
                )
                Text(
                    text = if (state.settings.enabled) {
                        "The daily preference is currently ENABLED, so the temporary test will be " +
                            "refused. Disarm first with Restore Normal Device Mode — this screen " +
                            "never changes the daily preference."
                    } else {
                        "The daily preference is currently disabled and stays disabled throughout " +
                            "the temporary test; the test's intent is held only in its own " +
                            "temporary marker."
                    },
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.Bold,
                )
                val temporaryTestExpiry = state.temporaryTestExpiryEpochMillis
                if (!state.temporaryTestMarkerRead) {
                    Text(
                        text = "Temporary-test marker is unknown because saved state could not be read.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                } else if (temporaryTestExpiry == null) {
                    Text(
                        text = "No temporary-test release timer is recorded.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                } else {
                    val local = java.time.Instant.ofEpochMilli(temporaryTestExpiry)
                        .atZone(java.time.ZoneId.systemDefault())
                        .format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))
                    Text(
                        text = "Release timer submitted for $local (fixed; repeated presses do not " +
                            "extend it). This is a submitted release opportunity, not a guaranteed " +
                            "delivery time — a force-stop or OEM restriction can prevent it.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Readback", fontWeight = FontWeight.Bold)
                InfoRow(
                    label = "Requested mask",
                    value = pocMaskLabel(status?.requestedFeatures),
                )
                InfoRow(
                    label = "Effective mask",
                    value = pocMaskLabel(status?.effectiveFeatures),
                )
                InfoRow(
                    label = "Runtime lock task state",
                    value = runtimeLockState?.let { LockTaskRuntimeStates.describe(it) }
                        ?: "Unknown (read failed)",
                )
                InfoRow(
                    label = "Locked vs pinned",
                    value = pocLockedVerdict(runtimeLockState),
                )
                InfoRow(
                    label = "Reported state",
                    value = status?.state?.wireName ?: "not observed yet",
                )
                InfoRow(
                    label = "Restricted claim satisfied",
                    value = status?.toPowerMenuClaimInputs()?.let {
                        if (it.canClaimRestricted()) "Yes" else "No"
                    } ?: "not observed yet",
                )
            }
        }

        Text(
            text = "Detail: " + (status?.detail ?: "no status detail recorded yet."),
            style = MaterialTheme.typography.bodySmall,
        )

        Text(
            text = "Everything above is API readback. It does not prove the physical Power Off " +
                "and Restart menu is absent — LOCK_TASK_MODE_PINNED in particular is screen " +
                "pinning, not a Device Owner locked task, and counts as a failure here.",
            style = MaterialTheme.typography.bodySmall,
        )

        Divider()

        Button(
            onClick = onStartSession,
            enabled = !state.busy,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("Start managed session")
        }
        Button(
            onClick = onAllowMenu,
            enabled = !state.busy,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("Allow power menu")
        }
        Button(
            onClick = onRestrictMenu,
            enabled = !state.busy,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("Restrict power menu")
        }
        Button(
            onClick = onRestore,
            enabled = !state.busy,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(stringResource(R.string.restore_normal_device_mode))
        }

        Divider()

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("How the temporary session behaves", fontWeight = FontWeight.Bold)
                Text(
                    text = "• A temporary release timer is submitted before restriction is " +
                        "applied.",
                    style = MaterialTheme.typography.bodySmall,
                )
                Text(
                    text = "• Restricting is refused if that release submission fails, so a " +
                        "restricted state is never entered without a planned way out.",
                    style = MaterialTheme.typography.bodySmall,
                )
                Text(
                    text = "• On reboot, application restart, or an application update, an " +
                        "interrupted temporary test is recovered rather than resumed. It is not " +
                        "quietly continued as if it had never been interrupted.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    text = "Do not select Power Off while testing the toggle",
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.error,
                )
                Text(
                    text = "During ordinary allow/restrict toggle testing, do not choose Power " +
                        "Off from the menu. Powering the device off ends the session and the " +
                        "observation you were taking. Hardware forced-restart behavior is a " +
                        "separate, controlled test with its own recovery plan, and it is outside " +
                        "this feature's guarantee.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }

        Button(onClick = onBack, modifier = Modifier.fillMaxWidth()) {
            Text("Back")
        }
    }
}

private fun pocMaskLabel(mask: Int?): String =
    if (mask == null) "unknown" else LockTaskMasks.describe(mask)

private fun pocLockedVerdict(lockTaskState: Int?): String = when (lockTaskState) {
    null ->
        "UNKNOWN — the runtime lock-task state could not be read (not a pass, not a normal state)"
    LockTaskRuntimeStates.LOCKED ->
        "LOCK_TASK_MODE_LOCKED — a Device Owner locked task is in effect (pass)"
    LockTaskRuntimeStates.PINNED ->
        "LOCK_TASK_MODE_PINNED — screen pinning, not a Device Owner locked task (failure)"
    LockTaskRuntimeStates.NONE ->
        "LOCK_TASK_MODE_NONE — no locked task is in effect"
    else ->
        "UNKNOWN ($lockTaskState) — not a recognised runtime state"
}
