package com.example.shutdownprotection.ui

import android.content.Intent
import android.provider.Settings
import android.widget.Toast
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.example.shutdownprotection.R
import com.example.shutdownprotection.BuildConfig
import com.example.shutdownprotection.data.ProtectionSettings
import com.example.shutdownprotection.noreset.*
import kotlinx.coroutines.delay
import java.time.Instant
import java.time.ZoneId

@Composable
fun NoResetScreen(onManagedTools: () -> Unit = {}) {
    val context = LocalContext.current
    val appName = stringResource(R.string.app_name)
    val edition = when {
        BuildConfig.MANAGED_TOOLS -> "Advanced edition"
        BuildConfig.COMPATIBILITY_EDITION -> "Android 10+ compatibility preview"
        else -> "Simple · tested Reno edition"
    }
    val store = remember { NoResetStore(context) }
    var config by remember { mutableStateOf(store.read()) }
    var start by rememberSaveable { mutableStateOf(ProtectionSettings.formatMinuteOfDay(config.startMinute)) }
    var end by rememberSaveable { mutableStateOf(ProtectionSettings.formatMinuteOfDay(config.endMinute)) }
    var page by rememberSaveable { mutableIntStateOf(0) }
    var tick by remember { mutableLongStateOf(0) }
    var error by rememberSaveable { mutableStateOf<String?>(null) }
    var showDisable by rememberSaveable { mutableStateOf(false) }
    var showManaged by rememberSaveable { mutableStateOf(false) }
    var diagnostics by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        while (true) { delay(1000); config = store.read(); tick++ }
    }
    // Refresh connection, trial and local-calendar status after returning from Settings.
    @Suppress("UNUSED_VARIABLE") val refresh = tick
    val service = NoResetMenuService.instance
    val supported = NoResetMenuService.supported()
    val remaining = service?.secondsRemaining() ?: 0
    val scheduledNow = remember(config, tick) { NoResetGate().mayDismiss(config, Instant.now(), 0) }
    val ready = supported && service?.ready() == true && config.valid
    val active = ready && (remaining > 0 || scheduledNow)
    val actionColors = ButtonDefaults.buttonColors(
        disabledContainerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
        disabledContentColor = MaterialTheme.colorScheme.onSurfaceVariant)
    val title = when {
        !supported -> "Phone not supported"
        service == null -> "Setup needed"
        service.problem() != null || !service.ready() -> "Needs attention"
        !config.valid -> "Check your times"
        remaining > 0 -> "Test running"
        !config.enabled -> "Paused"
        scheduledNow -> "Active now"
        else -> "Scheduled"
    }
    val detail = when {
        !supported -> "This firmware has not been verified. Menu dismissal is unavailable."
        service == null -> "Turn on Power Pause in Accessibility settings to get started."
        service.problem() != null -> service.problem()!!
        !service.ready() -> "The service was interrupted. Save your schedule or start a new test to resume."
        !config.valid -> "Save different, valid start and end times."
        remaining > 0 -> "$remaining seconds left. Menu dismissal will stop automatically."
        !config.enabled -> "Your normal power menu is available."
        scheduledNow -> "The ordinary power menu closes when it appears."
        else -> "Waiting for your daily hours. Your power menu is available now."
    }
    fun message(text: String) { Toast.makeText(context, text, Toast.LENGTH_LONG).show() }
    fun stop() {
        val saved = service?.stop() ?: store.disable()
        if (!saved) message("Stop could not be saved. Disable Power Pause in Accessibility settings.") else error = null
        config = store.read(); tick++
    }
    fun save(enable: Boolean) {
        val s = NoResetTimeInput.parse(start)
        val e = NoResetTimeInput.parse(end)
        when {
            s == null || e == null -> error = "Use 0000–2359 or HH:mm. For example, 1430 becomes 14:30."
            s == e -> error = "Start and end must be different."
            enable && (!supported || service == null) -> error = "Enable Power Pause in Accessibility settings on the supported phone first."
            !store.save(enable, s, e) -> error = "The schedule could not be saved. Protection has not been confirmed enabled."
            else -> {
                service?.resumeAfterSave(); config = store.read()
                start = ProtectionSettings.formatMinuteOfDay(s); end = ProtectionSettings.formatMinuteOfDay(e)
                error = null; message(if (enable) "Daily schedule enabled." else "Times saved. Power Pause is paused.")
            }
        }
    }
    Scaffold(contentWindowInsets = WindowInsets.safeDrawing, bottomBar = {
        NavigationBar(containerColor = MaterialTheme.colorScheme.surfaceContainerLow, tonalElevation = 0.dp) {
            listOf("Schedule", "Setup", "Tools").forEachIndexed { index, label ->
                NavigationBarItem(selected = page == index, onClick = { page = index },
                    icon = { Icon(painterResource(listOf(R.drawable.ic_schedule, R.drawable.ic_setup, R.drawable.ic_tools)[index]), contentDescription = null) },
                    label = { Text(label) })
            }
        }
    }) { insets ->
        // Separate scroll state prevents opening a tab at another tab's scroll offset.
        key(page) {
        Column(Modifier.fillMaxSize().padding(insets).imePadding().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Surface(shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.primary) {
                    Icon(painterResource(R.drawable.ic_power_pause), contentDescription = null,
                        tint = MaterialTheme.colorScheme.onPrimary, modifier = Modifier.padding(12.dp).size(28.dp))
                }
                Column {
                    Text(appName, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                    Text("Your power menu, on your schedule", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(edition, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                }
            }
            Surface(modifier = Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.medium,
                color = if (service != null) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.errorContainer) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(if (service != null) "Accessibility connected" else "Accessibility not connected",
                        style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Text(if (service == null) "Open Setup to connect the service."
                        else if (!supported || service.problem() != null || !service.ready()) "Connected does not mean protection is available. Review Setup."
                        else if (!config.enabled && remaining == 0L) "Service ready · schedule paused"
                        else "See the schedule status below for protection activity.", style = MaterialTheme.typography.bodySmall)
                }
            }
            when (page) {
                0 -> {
                    val statusColor = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.secondaryContainer
                    val statusText = if (active) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSecondaryContainer
                    Card(colors = CardDefaults.cardColors(containerColor = statusColor), modifier = Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(22.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            Text(if (remaining > 0) "QUICK TEST" else "DAILY POWER MENU", style = MaterialTheme.typography.labelMedium, color = statusText.copy(alpha = .8f))
                            Text(title, style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold, color = statusText)
                            Text(detail, style = MaterialTheme.typography.bodyMedium, color = statusText)
                            if (config.valid && config.enabled && remaining == 0L) Text("${ProtectionSettings.formatMinuteOfDay(config.startMinute)}  —  ${ProtectionSettings.formatMinuteOfDay(config.endMinute)} · every day", style = MaterialTheme.typography.titleMedium, color = statusText)
                        }
                    }
                    OutlinedButton(onClick = { stop() }, modifier = Modifier.fillMaxWidth()) { Text("Stop protection now") }
                    if (service == null || !supported || service.problem() != null) TextButton(onClick = { page = 1 }, modifier = Modifier.fillMaxWidth()) { Text("Review setup") }
                    SectionCard("Daily hours", "Repeats each day in your phone’s local time.") {
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            OutlinedTextField(value = start, onValueChange = { start = NoResetTimeInput.formatTyped(it) }, label = { Text("Start") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Next), singleLine = true, modifier = Modifier.weight(1f))
                            OutlinedTextField(value = end, onValueChange = { end = NoResetTimeInput.formatTyped(it) }, label = { Text("End") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Done), singleLine = true, modifier = Modifier.weight(1f))
                        }
                        Text("24-hour clock · type 1430 → 14:30\n0000 = midnight · 1200 = noon", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        if (NoResetTimeInput.parse(start)?.let { s -> NoResetTimeInput.parse(end)?.let { e -> s > e } } == true) Text("Ends the next day", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                        Button(onClick = { save(true) }, enabled = supported && service != null, colors = actionColors, modifier = Modifier.fillMaxWidth()) { Text("Save and enable schedule") }
                        TextButton(onClick = { save(false) }, modifier = Modifier.fillMaxWidth()) { Text("Save times and keep paused") }
                    }
                    Text("At the end time, dismissal stops. Accessibility stays enabled for the next day.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("Local time zone: ${ZoneId.systemDefault().id.replace('_', ' ')}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                1 -> {
                    Text("One-time setup", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
                    Text("No factory reset or running Shizuku needed.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (BuildConfig.COMPATIBILITY_EDITION) Text("Android 10+ installation preview. Automatic schedules are available only on the verified Reno firmware. On another phone, use the optional checks in Tools; a successful single Back does not enable automatic protection.", color = MaterialTheme.colorScheme.error)
                    Text("Keep only one Power Pause edition’s Accessibility service enabled at a time. Each edition has separate settings and approval.", style = MaterialTheme.typography.bodySmall)
                    SectionCard("1. Allow $appName", "Prevent ColorOS from blocking the service.") {
                        Text("In Phone Manager, go to:\nViruses & risks → Block suspicious app activities → More options → Allowlist.")
                        Text("Add $appName. Keep the main blocking switch on. If you already allowed this app under its old name, the same app approval is retained.")
                        OutlinedButton(onClick = {
                            val intent = context.packageManager.getLaunchIntentForPackage("com.coloros.phonemanager")
                            if (intent == null) message("Open Phone Manager manually.") else runCatching { context.startActivity(intent) }.onFailure { message("Open Phone Manager manually.") }
                        }, modifier = Modifier.fillMaxWidth()) { Text("Open Phone Manager") }
                        Text("Power Pause cannot check your Allowlist entry. Confirm it in Phone Manager.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    SectionCard("2. Enable Accessibility", if (service?.ready() == true) "Service connected" else "Service unavailable or interrupted") {
                        Text("Open downloaded apps/services and enable $appName. Then return here to set your hours. Enabling the service alone does not turn on a new schedule.")
                        OutlinedButton(onClick = {
                            runCatching { context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }.onFailure { message("Open Accessibility settings manually.") }
                        }, modifier = Modifier.fillMaxWidth()) { Text("Open Accessibility settings") }
                    }
                    SectionCard("How it works", "Closes the menu after it appears.") {
                        Text("The ordinary Power off / Restart menu may be visible briefly. Hardware forced restart and emergency functions remain available.")
                        Text("If ColorOS shows “Abnormal device control” or turns the service off, check this app’s Allowlist entry. Power Pause will not re-enable itself.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("Verified: OPPO Reno 15 (CPH2825), Android 16, build CPH2825_16.0.10.501(EX01), English menu. Other phones, firmware and languages need testing.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                2 -> {
                    Text("Tools & help", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
                    if (BuildConfig.COMPATIBILITY_EDITION) SectionCard("Older-phone checks", "Optional checks · no automatic profile is created") {
                        Text("Keep your schedule paused. Observe records only System UI window class, model, Android version and window number in memory. It does not read screen text or send data anywhere. Open the menu briefly, release power and never select Power off or Restart.")
                        OutlinedButton(onClick = { if (service?.startCompatibilityObservation() != true) message("Connect the service and keep the schedule paused first."); tick++ }, enabled = service != null && !config.enabled, modifier = Modifier.fillMaxWidth()) { Text("Observe only for 45 seconds") }
                        Text(service?.let { "Observation: ${it.observationSeconds()} seconds left\n${it.observationReport()}" } ?: "Connect Accessibility first.", style = MaterialTheme.typography.bodySmall)
                        Text("The next check sends one Back after 20 seconds. Start it, then open the power menu while at least 10 seconds remain. If the menu is not open, Back may exit the current screen. Stop cancels it.")
                        Button(onClick = { if (service?.startCompatibilityBackTrial() != true) message("Connect the service and keep the schedule paused first."); tick++ }, enabled = service != null && !config.enabled && service.manualTrialSeconds() == 0L, colors = actionColors, modifier = Modifier.fillMaxWidth()) { Text("One Back after 20 seconds") }
                        Text("Back countdown: ${service?.manualTrialSeconds() ?: 0} seconds", style = MaterialTheme.typography.bodySmall)
                        OutlinedButton(onClick = { stop() }, modifier = Modifier.fillMaxWidth()) { Text("Cancel checks and stop") }
                    }
                    SectionCard("Try it for one minute", "A temporary test, without changing your daily hours.") {
                        Text(if (remaining > 0) "$remaining seconds remaining" else "Pause the schedule first. Start the test, briefly open the power menu and release the button. Do not select Power off or Restart.")
                        Button(onClick = {
                            if (service?.startTest() != true) message("Pause the schedule and enable the service before testing.")
                            tick++
                        }, enabled = supported && service != null && !config.enabled && remaining == 0L, colors = actionColors, modifier = Modifier.fillMaxWidth()) { Text("Test for 60 seconds") }
                        OutlinedButton(onClick = { stop() }, modifier = Modifier.fillMaxWidth()) { Text("Stop protection now") }
                        Text("After the minute ends, the menu stays open again. Accessibility stays enabled.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    SectionCard("Accessibility service", if (service?.ready() == true) "Connected" else "Unavailable or interrupted") {
                        Text("Ordinary Stop pauses dismissal and keeps your approval. Use the button below only if you also want to turn off the service.")
                        OutlinedButton(onClick = { showDisable = true }, modifier = Modifier.fillMaxWidth()) { Text("Turn off Accessibility service") }
                    }
                    TextButton(onClick = { diagnostics = !diagnostics }) { Text(if (diagnostics) "Hide diagnostics" else "Show diagnostics") }
                    if (diagnostics) SectionCard("Last service result", "For troubleshooting") {
                        Text(NoResetMenuService.lastResult, style = MaterialTheme.typography.bodySmall)
                        Text("An accepted Back request is an Android result. Confirm actual menu closure on the phone.", style = MaterialTheme.typography.bodySmall)
                    }
                    if (BuildConfig.MANAGED_TOOLS) {
                        HorizontalDivider()
                        SectionCard("Caution: managed device tools", "Experimental · dedicated managed devices only") {
                            Text("These are the original Device Owner tools. They can limit which apps and system controls are available. They are not needed for your Accessibility schedule.")
                            Text("Do not experiment with them on your everyday phone. Enrollment normally requires initial setup or a factory reset. The managed power-menu behaviour has not been verified on a physical device.", color = MaterialTheme.colorScheme.error)
                            Text("Opening the tools stops your Accessibility schedule. It does not enroll or reset your phone. “Restore Normal Device Mode” restores app-applied managed rules; it does not erase the phone.", style = MaterialTheme.typography.bodySmall)
                            OutlinedButton(onClick = { showManaged = true }, modifier = Modifier.fillMaxWidth()) { Text("Open managed device tools") }
                        }
                    }
                    Text("$edition · ${BuildConfig.VERSION_NAME}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        }
    }
    if (showDisable) AlertDialog(onDismissRequest = { showDisable = false }, title = { Text("Turn off the service?") },
        text = { Text("Dismissal will stop. You will need to enable Power Pause again in Accessibility settings before using it.") },
        confirmButton = { TextButton(onClick = {
            showDisable = false
            if (service != null) service.stopAndDisable() else if (!store.disable()) message("Stop could not be saved. Open Accessibility settings to turn off the service.")
            config = store.read(); tick++
        }) { Text("Turn off service") } }, dismissButton = { TextButton(onClick = { showDisable = false }) { Text("Cancel") } })
    if (showManaged && BuildConfig.MANAGED_TOOLS) AlertDialog(onDismissRequest = { showManaged = false }, title = { Text("Caution: advanced tools") },
        text = { Text("Your Accessibility schedule will stop. These experimental tools can restrict apps and system controls on an enrolled device. Use only for deliberate testing or recovery on a dedicated managed phone. Do not enroll your everyday phone. Opening this page does not enroll, reset or erase anything.") },
        confirmButton = { TextButton(onClick = {
            showManaged = false; service?.stop()
            if (store.disable()) onManagedTools() else message("Could not save Stop. Turn off this Accessibility service before opening managed tools.")
        }) { Text("Stop and open") } }, dismissButton = { TextButton(onClick = { showManaged = false }) { Text("Cancel") } })
}

@Composable
private fun SectionCard(title: String, subtitle: String, content: @Composable ColumnScope.() -> Unit) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            content()
        }
    }
}
