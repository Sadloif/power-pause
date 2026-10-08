package com.example.shutdownprotection.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.shutdownprotection.AppContainer
import com.example.shutdownprotection.data.DiagnosticEvent
import com.example.shutdownprotection.data.ProtectionSettings
import com.example.shutdownprotection.data.RuntimeObservation
import com.example.shutdownprotection.data.SettingsValidation
import com.example.shutdownprotection.protection.PocOverride
import com.example.shutdownprotection.protection.ProtectionStatus
import com.example.shutdownprotection.protection.ProtectionTrigger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.isActive
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.OutputStream

/** One selectable application for the allowlist. */
data class AppEntry(val packageName: String, val label: String)

/** Everything the Compose screens render. */
data class UiState(
    val status: ProtectionStatus? = null,
    val settings: ProtectionSettings = ProtectionSettings.DEFAULT,
    val settingsLoaded: Boolean = false,
    val observation: RuntimeObservation? = null,
    /** Null means the diagnostic log read failed or has not completed; empty means read and empty. */
    val events: List<DiagnosticEvent>? = null,
    val busy: Boolean = false,
    val message: String? = null,
    val validationError: String? = null,
    /** Null means the package query is unavailable or has not completed; empty means it succeeded. */
    val selectableApps: List<AppEntry>? = null,
    val defaultLauncherPackage: String? = null,
    val deviceSecure: Boolean? = null,
    /**
     * The fixed expiry of an active temporary debug test, or null when none is running.
     * Shown to the operator as a submitted release opportunity, never as a guaranteed delivery
     * time (repair R06 step 10).
     */
    val temporaryTestExpiryEpochMillis: Long? = null,
    /** False means the marker read failed or has not completed; null expiry alone means nothing. */
    val temporaryTestMarkerRead: Boolean = false,
)

/**
 * The only bridge between Compose and the policy layer.
 *
 * Compose screens never touch `DevicePolicyManager`; they call this ViewModel, which calls
 * the coordinator (brief section 5).
 */
class MainViewModel(private val container: AppContainer) : ViewModel() {

    private val mutableState = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = mutableState.asStateFlow()

    val applicationId: String get() = container.applicationId

    init {
        viewModelScope.launch {
            container.settingsRepository.settingsFlow
                .catch { failure ->
                    if (failure is CancellationException) throw failure
                    mutableState.value = mutableState.value.copy(
                        settingsLoaded = false,
                        message = "Saved settings could not be read. Current user preference is unknown.",
                    )
                }
                .collect { settings ->
                    mutableState.value = mutableState.value.copy(
                        settings = settings,
                        settingsLoaded = true,
                    )
                }
        }
        viewModelScope.launch {
            container.coordinator.status.collect { status ->
                if (status != null) mutableState.value = mutableState.value.copy(status = status)
            }
        }
    }

    // ------------------------------------------------------------------
    // Lifecycle and refresh
    // ------------------------------------------------------------------

    fun onForeground() {
        viewModelScope.launch {
            // No announcement: merely returning to the app must not pop a modal. Status is
            // always on the main screen; a dialog is reserved for a user-initiated action or
            // a condition that needs a decision.
            runOperation(announce = false) { container.coordinator.reconcile(ProtectionTrigger.APP_FOREGROUND) }
            refreshDiagnostics()
            refreshDeviceFacts()
        }
    }

    fun refreshDeviceFacts() {
        viewModelScope.launch {
            val secure = try {
                withContext(Dispatchers.Default) { container.environment.isDeviceSecure() }
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: Throwable) {
                null
            }
            currentCoroutineContext().ensureActive()
            mutableState.value = mutableState.value.copy(deviceSecure = secure)
        }
    }

    fun refreshDiagnostics() {
        viewModelScope.launch {
            val events = try {
                container.diagnostics.readAll()
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: Throwable) {
                null
            }
            currentCoroutineContext().ensureActive()
            val observation = try {
                container.diagnostics.readObservation()
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: Throwable) {
                null
            }
            currentCoroutineContext().ensureActive()
            var markerRead = false
            val marker = try {
                container.settingsRepository.readTemporaryTestMarker().also { markerRead = true }
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: Throwable) {
                null
            }
            currentCoroutineContext().ensureActive()
            mutableState.value = mutableState.value.copy(
                events = events,
                observation = observation,
                temporaryTestExpiryEpochMillis = marker?.releaseAtEpochMillis,
                temporaryTestMarkerRead = markerRead,
                message = if (events == null || observation == null) {
                    "Some diagnostics could not be read. Unavailable facts are shown as unknown."
                } else {
                    mutableState.value.message
                },
            )
        }
    }

    fun refreshSelectableApps() {
        viewModelScope.launch {
            val apps = withContext(Dispatchers.IO) { querySelectableApps() }
            val launcher = withContext(Dispatchers.IO) { resolveDefaultLauncher() }
            currentCoroutineContext().ensureActive()
            mutableState.value = mutableState.value.copy(
                selectableApps = apps,
                defaultLauncherPackage = launcher,
            )
        }
    }

    // ------------------------------------------------------------------
    // Primary controls
    // ------------------------------------------------------------------

    fun arm() {
        viewModelScope.launch {
            runOperation { container.coordinator.arm() }
        }
    }

    fun disableAndRestore() {
        viewModelScope.launch {
            runOperation { container.coordinator.disableAndRestore() }
        }
    }

    /** Validates the candidate times before touching the saved schedule. */
    fun applySchedule(startText: String, endText: String) {
        val start = ProtectionSettings.parseMinuteOfDay(startText)
        val end = ProtectionSettings.parseMinuteOfDay(endText)
        if (start == null || end == null) {
            mutableState.value = mutableState.value.copy(
                validationError = "Enter times as 24-hour HH:mm, for example 02:00.",
            )
            return
        }
        val candidate = mutableState.value.settings.copy(
            startMinuteOfDay = start,
            endMinuteOfDay = end,
        )
        val validation = candidate.validate()
        if (validation is SettingsValidation.Invalid) {
            mutableState.value = mutableState.value.copy(validationError = validation.message)
            return
        }
        mutableState.value = mutableState.value.copy(validationError = null)
        viewModelScope.launch {
            runOperation { container.coordinator.editSchedule(start, end) }
        }
    }

    fun saveAllowedPackages(packages: Set<String>) {
        viewModelScope.launch {
            runOperation { container.coordinator.editAllowedPackages(packages) }
        }
    }

    // ------------------------------------------------------------------
    // Debug-only proof-of-concept controls (brief section 22)
    // ------------------------------------------------------------------

    fun startPocSession() {
        viewModelScope.launch { runOperation { container.coordinator.activatePocSession() } }
    }

    fun pocAllowMenu() {
        viewModelScope.launch { runOperation { container.coordinator.setPocOverride(PocOverride.FORCE_ALLOWED) } }
    }

    fun pocRestrictMenu() {
        viewModelScope.launch { runOperation { container.coordinator.setPocOverride(PocOverride.FORCE_RESTRICTED) } }
    }

    // ------------------------------------------------------------------
    // Diagnostics
    // ------------------------------------------------------------------

    fun clearDiagnostics() {
        viewModelScope.launch {
            try {
                container.diagnostics.clear()
                currentCoroutineContext().ensureActive()
                refreshDiagnostics()
                mutableState.value = mutableState.value.copy(message = "Diagnostics cleared")
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: Throwable) {
                currentCoroutineContext().ensureActive()
                mutableState.value = mutableState.value.copy(message = "Diagnostics could not be cleared")
            }
        }
    }

    /** Deliberate, local export. Never an automatic upload. */
    suspend fun buildExportText(): String = container.diagnostics.exportText()

    /** Writes the export to a user-selected destination. */
    fun exportTo(uri: Uri) {
        viewModelScope.launch {
            val text = try {
                container.diagnostics.exportText()
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: Throwable) {
                mutableState.value = mutableState.value.copy(message = "Diagnostics export failed")
                return@launch
            }
            val written = try {
                withContext(Dispatchers.IO) {
                    writeDiagnosticsExport(
                        text,
                        container.appContext.contentResolver.openOutputStream(uri),
                    )
                }
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: Throwable) {
                false
            }
            if (!viewModelScope.isActive) return@launch
            mutableState.value = mutableState.value.copy(
                message = if (written) "Diagnostics exported" else "Diagnostics export failed",
            )
        }
    }

    fun dismissMessage() {
        mutableState.value = mutableState.value.copy(message = null, validationError = null)
    }

    /** The documented Alarms & reminders screen for this application (brief section 15.3). */
    fun exactAlarmSettingsIntent(): Intent =
        Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM).setData(
            Uri.fromParts("package", container.applicationId, null),
        )

    // ------------------------------------------------------------------
    // Display helpers (main screen, brief section 19)
    // ------------------------------------------------------------------

    /** Device time zone, read fresh: the schedule follows the *current* zone. */
    fun currentZoneId(): String = container.scheduleCalculator.currentZone().id

    /** The next planned transition, with its zone, as the main screen requires. */
    fun describeNextTransition(): String {
        val settings = mutableState.value.settings
        if (!settings.isValid) return "Not available while the schedule is invalid"
        val next = container.scheduleCalculator.nextTransition(settings)
            ?: return "No valid future boundary found"
        val local = java.time.ZonedDateTime.ofInstant(next.instant, next.zoneId)
        val label = if (next.isStart) "Restriction starts" else "Restriction ends"
        return "$label ${local.format(TRANSITION_FORMATTER)} (${next.zoneId.id})"
    }

    /** Time of the stored platform observation; it may contain unknown capability fields. */
    fun lastObservationText(): String {
        val observed = mutableState.value.observation?.observedAtEpochMillis ?: return "Unknown"
        return java.time.Instant.ofEpochMilli(observed)
            .atZone(java.time.ZoneId.systemDefault())
            .format(TRANSITION_FORMATTER)
    }

    /**
     * False until physical power-menu behavior has been observed on this device/build. The
     * UI shows "Device behavior not yet validated" rather than disguising API observations
     * as a physical test result (brief section 19).
     */
    fun deviceBehaviorValidated(): Boolean = container.deviceBehaviorValidated

    // ------------------------------------------------------------------
    // Internals
    // ------------------------------------------------------------------

    private suspend fun runOperation(announce: Boolean = true, block: suspend () -> ProtectionStatus) {
        mutableState.value = mutableState.value.copy(busy = true, validationError = null)
        try {
            val status = block()
            currentCoroutineContext().ensureActive()
            mutableState.value = mutableState.value.copy(
                busy = false,
                // A routine foreground refresh is quiet; user-requested actions are announced.
                message = if (announce) describe(status) else null,
            )
            refreshDiagnostics()
        } catch (cancellation: CancellationException) {
            // A cleared viewModelScope is allowed to stop work. Do not turn cancellation into a
            // user-visible policy failure or continue into diagnostics after the screen is gone.
            if (viewModelScope.isActive) {
                mutableState.value = mutableState.value.copy(busy = false)
            }
            throw cancellation
        } catch (failure: Throwable) {
            currentCoroutineContext().ensureActive()
            mutableState.value = mutableState.value.copy(
                busy = false,
                message = "Operation failed: ${failure.message ?: failure::class.java.simpleName}",
            )
            refreshDiagnostics()
        }
    }

    private fun describe(status: ProtectionStatus): String =
        "${status.state.wireName}: ${status.detail}"

    private fun querySelectableApps(): List<AppEntry>? {
        val context = container.appContext
        return try {
            val packageManager = context.packageManager
            val seen = mutableMapOf<String, AppEntry>()

            val launcherIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
            val homeIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
            for (intent in listOf(launcherIntent, homeIntent)) {
                val resolved = packageManager.queryIntentActivities(intent, 0)
                for (info in resolved) {
                    val packageName = info.activityInfo?.packageName ?: continue
                    if (seen.containsKey(packageName)) continue
                    val label = runCatching {
                        info.loadLabel(packageManager).toString()
                    }.getOrDefault(packageName)
                    seen[packageName] = AppEntry(packageName, label)
                }
            }
            seen.values.sortedBy { it.label.lowercase() }
        } catch (_: Throwable) {
            null
        }
    }

    private fun resolveDefaultLauncher(): String? {
        val context = container.appContext
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        // Resolved on the actual device: manufacturer launcher package names are never assumed.
        return runCatching {
            context.packageManager.resolveActivity(intent, android.content.pm.PackageManager.MATCH_DEFAULT_ONLY)
                ?.activityInfo?.packageName
        }.getOrNull()
    }

    class Factory(private val container: AppContainer) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            MainViewModel(container) as T
    }

    private companion object {
        val TRANSITION_FORMATTER: java.time.format.DateTimeFormatter =
            java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
    }
}

/** Returns false for a missing stream or a failed write; it never reports a null stream as success. */
internal fun writeDiagnosticsExport(text: String, output: OutputStream?): Boolean {
    if (output == null) return false
    return try {
        output.use { it.write(text.toByteArray(Charsets.UTF_8)) }
        true
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (_: Throwable) {
        false
    }
}
