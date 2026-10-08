package com.example.shutdownprotection.ui

import androidx.compose.foundation.clickable
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
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Divider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.shutdownprotection.BuildConfig

/**
 * The Lock Task allowlist editor (brief sections 8 and 19).
 *
 * Two honesty rules drive this screen:
 *
 *  1. A package that is saved but no longer resolvable on the device is *listed as unresolved*
 *     rather than silently dropped, so the screen never implies an allowlist is in force when
 *     it is not.
 *  2. This application's own package is always part of the allowlist and cannot be removed
 *     from here, because removing it would make the session unrecoverable from the UI.
 */
@Composable
fun AllowedAppsScreen(
    state: UiState,
    onSave: (Set<String>) -> Unit,
    onRestore: () -> Unit,
    onBack: () -> Unit,
) {
    val ownPackage = BuildConfig.APPLICATION_ID

    val labelsByPackage = remember(state.selectableApps) {
        state.selectableApps.orEmpty().associate { entry -> entry.packageName to entry.label }
    }

    // Seeded once from the stored allowlist and re-seeded whenever the stored set changes.
    var selected by remember(state.settings.allowedPackages) {
        mutableStateOf(state.settings.allowedPackages)
    }

    val selectable = state.selectableApps.orEmpty().filter { it.packageName != ownPackage }

    val unresolved = state.settings.allowedPackages
        .filter { pkg -> pkg != ownPackage && labelsByPackage[pkg] == null }
        .sorted()

    val savedSorted = state.settings.allowedPackages.sorted()

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item {
            Text(
                text = "Allowed applications",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
            )
        }

        // Required on every management screen (brief section 10).
        item {
            RestoreControl(onRestore = onRestore, enabled = !state.busy)
        }

        item {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("Currently saved allowlist", fontWeight = FontWeight.Bold)
                    if (!state.settingsLoaded) {
                        Text("Unknown (saved settings could not be read)", style = MaterialTheme.typography.bodySmall)
                    } else if (savedSorted.isEmpty()) {
                        Text(
                            text = "No applications are saved. An empty allowlist leaves a " +
                                "locked session with no permitted application.",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    } else {
                        savedSorted.forEach { pkg ->
                            InfoRow(
                                label = labelsByPackage[pkg] ?: pkg,
                                value = pkg,
                            )
                        }
                    }
                    InfoRow(label = "Saved entries", value = if (state.settingsLoaded) savedSorted.size.toString() else "Unknown")
                }
            }
        }

        item {
            Text(
                text = "The default launcher package is " +
                    (state.defaultLauncherPackage ?: "Unknown") +
                    ". Include it: without a launcher the device is difficult to use while a " +
                    "session is locked, and the user may not be able to leave the session from " +
                    "the screen.",
                style = MaterialTheme.typography.bodySmall,
            )
        }

        item {
            Text(
                text = "This application — required",
                fontWeight = FontWeight.Bold,
            )
        }

        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Checkbox(checked = true, onCheckedChange = null)
                Column(Modifier.padding(start = 8.dp)) {
                    Text(ownPackage, style = MaterialTheme.typography.bodyLarge)
                    Text(
                        text = "Must stay allowed. Removing this application would leave no " +
                            "reliable way to restore the device from the screen, so it cannot " +
                            "be deselected here.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }

        item {
            Divider()
        }

        item {
            Text(
                text = "Selectable applications on this device",
                fontWeight = FontWeight.Bold,
            )
        }

        if (state.selectableApps == null) {
            item {
                Text(
                    text = "Selectable applications are unknown because the package query is unavailable or has not completed.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        } else if (selectable.isEmpty()) {
            item {
                Text(
                    text = "No selectable applications were resolved. Launchable and home " +
                        "applications are enumerated on the foreground; if this stays empty, " +
                        "the package query returned nothing on this device.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }

        items(selectable, key = { entry -> entry.packageName }) { entry ->
            val isChecked = selected.contains(entry.packageName)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(enabled = state.settingsLoaded && !state.busy) {
                        selected = togglePackage(selected, entry.packageName)
                    },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Checkbox(
                    checked = isChecked,
                    onCheckedChange = if (state.settingsLoaded && !state.busy) {
                        { selected = togglePackage(selected, entry.packageName) }
                    } else null,
                )
                Column(Modifier.padding(start = 8.dp)) {
                    Text(entry.label, style = MaterialTheme.typography.bodyLarge)
                    Text(entry.packageName, style = MaterialTheme.typography.bodySmall)
                }
            }
        }

        if (unresolved.isNotEmpty()) {
            item {
                Divider()
            }
            item {
                Text(
                    text = "Unresolved packages",
                    fontWeight = FontWeight.Bold,
                )
            }
            item {
                Text(
                    text = "These packages are in the saved allowlist but are not currently " +
                        "resolvable on this device — they may be disabled, uninstalled, or " +
                        "hidden from the launcher query. They are kept rather than silently " +
                        "dropped, and the allowlist must not be assumed active for them.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            items(unresolved) { pkg ->
                Column(Modifier.fillMaxWidth()) {
                    Text(pkg, style = MaterialTheme.typography.bodyLarge)
                    Text(
                        text = "not currently resolvable on this device",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }

        item {
            Divider()
        }

        item {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        text = "Saving disarms the managed session first",
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.error,
                    )
                    Text(
                        text = "Saving this allowlist fully disarms the current managed session " +
                            "before the new list is written. The allowlist is never rewritten " +
                            "under a locked session. An explicit resume is required afterwards: " +
                            "the persistent preference is left as it is, but protection is not " +
                            "operating again until the session is resumed from the main screen.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }

        item {
            Text(
                text = "Selected: ${(selected + ownPackage).size} package(s), including this " +
                    "application.",
                style = MaterialTheme.typography.bodySmall,
            )
        }

        item {
            Button(
                onClick = { onSave(selected + ownPackage) },
                enabled = !state.busy && state.settingsLoaded,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Save")
            }
        }

        item {
            Button(onClick = onBack, modifier = Modifier.fillMaxWidth()) {
                Text("Back")
            }
        }
    }
}

private fun togglePackage(selected: Set<String>, packageName: String): Set<String> =
    if (selected.contains(packageName)) selected - packageName else selected + packageName
