package com.example.shutdownprotection.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.io.File

/** Raised when the settings file had to be recovered from corruption (brief section 7.10). */
class SettingsCorruptionException :
    IllegalStateException("Stored protection settings were unreadable")

/**
 * Every preference key used by the durable store, in one place. The class and the
 * file-level mappers below share this object so a key can never drift between the
 * reader and the writer.
 */
internal object SettingsKeys {
    val SCHEMA_VERSION = intPreferencesKey("schema_version")
    val ENABLED = booleanPreferencesKey("enabled")
    val START_MINUTE = intPreferencesKey("start_minute_of_day")
    val END_MINUTE = intPreferencesKey("end_minute_of_day")
    val REVISION = longPreferencesKey("revision")
    val ALLOWED_PACKAGES = stringSetPreferencesKey("allowed_packages")
    val RECOVERY_REQUIRED = booleanPreferencesKey("recovery_required")

    /** Set by the corruption handler; cleared only after recovery is verified. */
    val CORRUPT_DETECTED = booleanPreferencesKey("corrupt_detected")

    val BASELINE_PRESENT = booleanPreferencesKey("baseline_present")
    val BASELINE_CAPTURED_AT = longPreferencesKey("baseline_captured_at")
    val BASELINE_PACKAGES = stringSetPreferencesKey("baseline_packages")
    val BASELINE_FEATURES = intPreferencesKey("baseline_features")
    val BASELINE_APP_ID = stringPreferencesKey("baseline_app_id")
    val BASELINE_PROVENANCE = stringPreferencesKey("baseline_provenance")
    val BASELINE_LIFECYCLE = stringPreferencesKey("baseline_lifecycle")

    val RECEIPT_PRESENT = booleanPreferencesKey("receipt_present")
    val RECEIPT_REVISION = longPreferencesKey("receipt_revision")
    val RECEIPT_BOOT_GENERATION = longPreferencesKey("receipt_boot_generation")
    val RECEIPT_SUBMITTED_AT = longPreferencesKey("receipt_submitted_at")
    val RECEIPT_NEXT_START = longPreferencesKey("receipt_next_start")
    val RECEIPT_NEXT_END = longPreferencesKey("receipt_next_end")
    val RECEIPT_FALLBACK = longPreferencesKey("receipt_fallback")

    val TEMP_MARKER_PRESENT = booleanPreferencesKey("temp_marker_present")
    val TEMP_MARKER_STARTED_AT = longPreferencesKey("temp_marker_started_at")
    val TEMP_MARKER_RELEASE_AT = longPreferencesKey("temp_marker_release_at")
    val TEMP_MARKER_REVISION = longPreferencesKey("temp_marker_revision")
    val TEMP_MARKER_SESSION_ID = stringPreferencesKey("temp_marker_session_id")

    val INCIDENT_PRESENT = booleanPreferencesKey("incident_present")
    val INCIDENT_ID = stringPreferencesKey("incident_id")
    val INCIDENT_OPENED_AT = longPreferencesKey("incident_opened_at")
    val INCIDENT_ATTEMPTS = intPreferencesKey("incident_attempts")
    val INCIDENT_UNRESOLVED = booleanPreferencesKey("incident_unresolved")

    val BOOT_GENERATION = longPreferencesKey("boot_generation")

    val OWNER_ESTABLISHED = booleanPreferencesKey("owner_established")
}

private fun Preferences.toSettings(): ProtectionSettings = ProtectionSettings(
    schemaVersion = this[SettingsKeys.SCHEMA_VERSION] ?: ProtectionSettings.SCHEMA_VERSION,
    enabled = this[SettingsKeys.ENABLED] ?: false,
    startMinuteOfDay = this[SettingsKeys.START_MINUTE] ?: ProtectionSettings.DEFAULT_START_MINUTE,
    endMinuteOfDay = this[SettingsKeys.END_MINUTE] ?: ProtectionSettings.DEFAULT_END_MINUTE,
    revision = this[SettingsKeys.REVISION] ?: 0L,
    allowedPackages = this[SettingsKeys.ALLOWED_PACKAGES]?.toSet() ?: emptySet(),
    recoveryRequired = this[SettingsKeys.RECOVERY_REQUIRED] ?: false,
)

private fun MutablePreferences.writeSettings(settings: ProtectionSettings) {
    this[SettingsKeys.SCHEMA_VERSION] = settings.schemaVersion
    this[SettingsKeys.ENABLED] = settings.enabled
    this[SettingsKeys.START_MINUTE] = settings.startMinuteOfDay
    this[SettingsKeys.END_MINUTE] = settings.endMinuteOfDay
    this[SettingsKeys.REVISION] = settings.revision
    this[SettingsKeys.ALLOWED_PACKAGES] = settings.allowedPackages.toSet()
    this[SettingsKeys.RECOVERY_REQUIRED] = settings.recoveryRequired
}

// Key-group removals, shared by the individual writers and by the atomic cleanup commit so the
// two can never drift apart.

private fun MutablePreferences.removeBaseline() {
    remove(SettingsKeys.BASELINE_PRESENT)
    remove(SettingsKeys.BASELINE_CAPTURED_AT)
    remove(SettingsKeys.BASELINE_PACKAGES)
    remove(SettingsKeys.BASELINE_FEATURES)
    remove(SettingsKeys.BASELINE_APP_ID)
    remove(SettingsKeys.BASELINE_PROVENANCE)
    remove(SettingsKeys.BASELINE_LIFECYCLE)
}

private fun Preferences.readBaselineStrict(): PolicyBaseline? {
    val present = this[SettingsKeys.BASELINE_PRESENT]
    val hasFields = this[SettingsKeys.BASELINE_CAPTURED_AT] != null ||
        this[SettingsKeys.BASELINE_PACKAGES] != null ||
        this[SettingsKeys.BASELINE_FEATURES] != null ||
        this[SettingsKeys.BASELINE_APP_ID] != null ||
        this[SettingsKeys.BASELINE_PROVENANCE] != null ||
        this[SettingsKeys.BASELINE_LIFECYCLE] != null
    if (present != true) {
        check(!hasFields) { "Baseline fields exist without the presence marker" }
        return null
    }

    val capturedAt = this[SettingsKeys.BASELINE_CAPTURED_AT]
        ?: error("Stored baseline is missing its capture time")
    val packages = this[SettingsKeys.BASELINE_PACKAGES]?.toSet()
        ?: error("Stored baseline is missing its package set")
    val features = this[SettingsKeys.BASELINE_FEATURES]
        ?: error("Stored baseline is missing its feature mask")
    val appId = this[SettingsKeys.BASELINE_APP_ID]
        ?: error("Stored baseline is missing its application id")
    val provenance = this[SettingsKeys.BASELINE_PROVENANCE]?.let {
        runCatching { BaselineProvenance.valueOf(it) }.getOrElse { error("Unknown baseline provenance") }
    } ?: BaselineProvenance.LEGACY_UNVERIFIED
    val lifecycle = this[SettingsKeys.BASELINE_LIFECYCLE]?.let {
        runCatching { BaselineLifecycleState.valueOf(it) }.getOrElse { error("Unknown baseline lifecycle") }
    } ?: BaselineLifecycleState.LEGACY_UNKNOWN
    val baseline = PolicyBaseline(capturedAt, packages, features, appId, provenance, lifecycle)
    check(baseline.isValidFor(appId)) { baseline.invalidReasonFor(appId) ?: "Malformed baseline" }
    return baseline
}

private fun MutablePreferences.removeScheduleReceipt() {
    remove(SettingsKeys.RECEIPT_PRESENT)
    remove(SettingsKeys.RECEIPT_REVISION)
    remove(SettingsKeys.RECEIPT_BOOT_GENERATION)
    remove(SettingsKeys.RECEIPT_SUBMITTED_AT)
    remove(SettingsKeys.RECEIPT_NEXT_START)
    remove(SettingsKeys.RECEIPT_NEXT_END)
    remove(SettingsKeys.RECEIPT_FALLBACK)
}

private fun MutablePreferences.removeTemporaryTestMarker() {
    remove(SettingsKeys.TEMP_MARKER_PRESENT)
    remove(SettingsKeys.TEMP_MARKER_STARTED_AT)
    remove(SettingsKeys.TEMP_MARKER_RELEASE_AT)
    remove(SettingsKeys.TEMP_MARKER_REVISION)
    remove(SettingsKeys.TEMP_MARKER_SESSION_ID)
}

private fun MutablePreferences.removeRecoveryIncident() {
    remove(SettingsKeys.INCIDENT_PRESENT)
    remove(SettingsKeys.INCIDENT_ID)
    remove(SettingsKeys.INCIDENT_OPENED_AT)
    remove(SettingsKeys.INCIDENT_ATTEMPTS)
    remove(SettingsKeys.INCIDENT_UNRESOLVED)
}

/**
 * Device-protected DataStore holding the minimum state Direct Boot needs (brief section 7.8):
 * user intent, the captured policy baseline, the scheduling receipt, the temporary-test
 * marker, and recovery incident bookkeeping.
 *
 * Diagnostics are deliberately NOT here - they live in credential-protected storage and
 * are never required to make a correct protection decision.
 */
class DataStoreSettingsRepository(
    private val dataStore: DataStore<Preferences>,
) : SettingsRepository {

    override val settingsFlow: Flow<ProtectionSettings> = dataStore.data.map { it.toSettings() }

    override suspend fun readSettings(): SettingsReadResult {
        // Cancellation is rethrown rather than being folded into `Corrupt` (independent audit
        // finding). `runCatching` catches `Throwable`, so a cancelled scope used to be reported as
        // unreadable settings — which then drove a spurious recovery.
        val prefs = try {
            dataStore.data.first()
        } catch (cancellation: kotlinx.coroutines.CancellationException) {
            throw cancellation
        } catch (failure: Throwable) {
            return SettingsReadResult.Corrupt(failure)
        }
        // The corruption handler replaced the unreadable file with a valid store holding only this
        // flag, so the defaults are already "disabled". Surface the condition rather than pretending
        // the state is fine.
        return if (prefs[SettingsKeys.CORRUPT_DETECTED] == true) {
            SettingsReadResult.Corrupt(SettingsCorruptionException())
        } else {
            SettingsReadResult.Success(prefs.toSettings())
        }
    }

    override suspend fun writeSettings(settings: ProtectionSettings): SettingsWriteResult =
        write { it.writeSettings(settings) }

    override suspend fun editSettings(
        transform: (ProtectionSettings) -> ProtectionSettings,
    ): SettingsWriteResult = write { it.writeSettings(transform(it.toSettings())) }

    override suspend fun readBaseline(): PolicyBaseline? =
        dataStore.data.first().readBaselineStrict()

    override suspend fun writeBaseline(baseline: PolicyBaseline): SettingsWriteResult = write { prefs ->
        prefs[SettingsKeys.BASELINE_PRESENT] = true
        prefs[SettingsKeys.BASELINE_CAPTURED_AT] = baseline.capturedAtEpochMillis
        prefs[SettingsKeys.BASELINE_PACKAGES] = baseline.lockTaskPackages.toSet()
        prefs[SettingsKeys.BASELINE_FEATURES] = baseline.lockTaskFeatures
        prefs[SettingsKeys.BASELINE_APP_ID] = baseline.applicationId
        prefs[SettingsKeys.BASELINE_PROVENANCE] = baseline.provenance.name
        prefs[SettingsKeys.BASELINE_LIFECYCLE] = baseline.lifecycle.name
    }

    override suspend fun clearBaseline(): SettingsWriteResult = write { prefs ->
        prefs.removeBaseline()
    }

    /**
     * Atomically decides whether a capture is new-session provenance or must be preserved. A
     * legacy/ambiguous or unresolved baseline is never overwritten. A verified RESTORED record is
     * the only retained record eligible for replacement by a truthful current capture.
     */
    override suspend fun prepareSession(
        candidateBaseline: PolicyBaseline,
        expectedApplicationId: String,
        temporaryMarker: TemporaryTestMarker?,
    ): SessionPreparationResult {
        if (candidateBaseline.applicationId != expectedApplicationId ||
            candidateBaseline.invalidReasonFor(expectedApplicationId) != null
        ) return SessionPreparationResult.Failed(IllegalArgumentException("captured baseline is invalid"))

        var decision: SessionPreparationResult? = null
        val failure = try {
            dataStore.edit { prefs ->
                if (prefs[SettingsKeys.CORRUPT_DETECTED] == true) {
                    decision = SessionPreparationResult.Failed(SettingsCorruptionException())
                    return@edit
                }
                val existing = try {
                    prefs.readBaselineStrict()
                } catch (cancellation: kotlinx.coroutines.CancellationException) {
                    throw cancellation
                } catch (failure: Throwable) {
                    decision = SessionPreparationResult.InvalidStoredBaseline(
                        failure.message ?: "stored baseline is malformed",
                    )
                    return@edit
                }
                if (existing != null && !existing.isTrustedOriginal) {
                    decision = SessionPreparationResult.InvalidStoredBaseline(
                        existing.invalidReasonFor(expectedApplicationId)
                            ?: "stored baseline provenance is ambiguous",
                    )
                    return@edit
                }
                if (existing != null && existing.invalidReasonFor(expectedApplicationId) != null) {
                    decision = SessionPreparationResult.InvalidStoredBaseline(
                        existing.invalidReasonFor(expectedApplicationId)!!,
                    )
                    return@edit
                }

                val shouldCaptureFresh = existing == null ||
                    existing.lifecycle == BaselineLifecycleState.RESTORED
                val baseline = if (shouldCaptureFresh) {
                    candidateBaseline.copy(
                        provenance = BaselineProvenance.CAPTURED_BEFORE_PREPARATION,
                        lifecycle = BaselineLifecycleState.PREPARED,
                    ).also { stored ->
                        prefs[SettingsKeys.BASELINE_PRESENT] = true
                        prefs[SettingsKeys.BASELINE_CAPTURED_AT] = stored.capturedAtEpochMillis
                        prefs[SettingsKeys.BASELINE_PACKAGES] = stored.lockTaskPackages.toSet()
                        prefs[SettingsKeys.BASELINE_FEATURES] = stored.lockTaskFeatures
                        prefs[SettingsKeys.BASELINE_APP_ID] = stored.applicationId
                        prefs[SettingsKeys.BASELINE_PROVENANCE] = stored.provenance.name
                        prefs[SettingsKeys.BASELINE_LIFECYCLE] = stored.lifecycle.name
                    }
                } else {
                    existing!!
                }
                val revision = if (temporaryMarker == null) {
                    ((prefs[SettingsKeys.REVISION] ?: 0L) + 1L).also {
                        prefs[SettingsKeys.REVISION] = it
                    }
                } else {
                    prefs[SettingsKeys.REVISION] ?: 0L
                }
                prefs[SettingsKeys.ENABLED] = false
                if (temporaryMarker == null) {
                    prefs[SettingsKeys.RECOVERY_REQUIRED] = true
                } else {
                    prefs[SettingsKeys.TEMP_MARKER_PRESENT] = true
                    prefs[SettingsKeys.TEMP_MARKER_STARTED_AT] = temporaryMarker.startedAtEpochMillis
                    prefs[SettingsKeys.TEMP_MARKER_RELEASE_AT] = temporaryMarker.releaseAtEpochMillis
                    prefs[SettingsKeys.TEMP_MARKER_REVISION] = revision
                    if (temporaryMarker.sessionId == null) {
                        prefs.remove(SettingsKeys.TEMP_MARKER_SESSION_ID)
                    } else {
                        prefs[SettingsKeys.TEMP_MARKER_SESSION_ID] = temporaryMarker.sessionId
                    }
                }
                decision = if (shouldCaptureFresh) {
                    SessionPreparationResult.Prepared(baseline, revision)
                } else {
                    SessionPreparationResult.ReusedExisting(baseline, revision)
                }
            }
            null
        } catch (cancellation: kotlinx.coroutines.CancellationException) {
            throw cancellation
        } catch (failure: Throwable) {
            failure
        }
        if (failure != null) return SessionPreparationResult.Failed(failure)
        return decision ?: SessionPreparationResult.Failed(IllegalStateException("preparation did not complete"))
    }

    /**
     * One atomic cleanup commit (repair R03 step 7). A failure here means cleanup is incomplete
     * and the caller must keep recovery unresolved.
     */
    override suspend fun commitCleanup(commit: CleanupCommit): SettingsWriteResult = write { prefs ->
        prefs[SettingsKeys.ENABLED] = commit.enabled
        prefs[SettingsKeys.RECOVERY_REQUIRED] = commit.recoveryRequired
        if (commit.clearCorruptionFlag) prefs.remove(SettingsKeys.CORRUPT_DETECTED)
        if (commit.clearTemporaryTestMarker) prefs.removeTemporaryTestMarker()
        if (commit.clearRecoveryIncident) prefs.removeRecoveryIncident()
        if (commit.clearScheduleReceipt) prefs.removeScheduleReceipt()
        when (commit.baselineLifecycle) {
            BaselineLifecycle.CLEAR -> prefs.removeBaseline()
            BaselineLifecycle.MARK_RESTORED -> if (prefs[SettingsKeys.BASELINE_PRESENT] == true) {
                prefs[SettingsKeys.BASELINE_LIFECYCLE] = BaselineLifecycleState.RESTORED.name
            }
            BaselineLifecycle.KEEP -> Unit
        }
    }

    override suspend fun readScheduleReceipt(): ScheduleReceipt? {
        val prefs = dataStore.data.first()
        val present = prefs[SettingsKeys.RECEIPT_PRESENT]
        val hasFields = prefs[SettingsKeys.RECEIPT_REVISION] != null ||
            prefs[SettingsKeys.RECEIPT_BOOT_GENERATION] != null ||
            prefs[SettingsKeys.RECEIPT_SUBMITTED_AT] != null ||
            prefs[SettingsKeys.RECEIPT_NEXT_START] != null ||
            prefs[SettingsKeys.RECEIPT_NEXT_END] != null ||
            prefs[SettingsKeys.RECEIPT_FALLBACK] != null
        if (present != true) {
            check(!hasFields) { "Schedule receipt fields exist without the presence marker" }
            return null
        }
        return ScheduleReceipt(
            revision = prefs[SettingsKeys.RECEIPT_REVISION] ?: error("Schedule receipt is missing revision"),
            bootGeneration = prefs[SettingsKeys.RECEIPT_BOOT_GENERATION] ?: error("Schedule receipt is missing boot generation"),
            submittedAtEpochMillis = prefs[SettingsKeys.RECEIPT_SUBMITTED_AT] ?: error("Schedule receipt is missing submit time"),
            nextStartEpochMillis = prefs[SettingsKeys.RECEIPT_NEXT_START],
            nextEndEpochMillis = prefs[SettingsKeys.RECEIPT_NEXT_END],
            fallbackEpochMillis = prefs[SettingsKeys.RECEIPT_FALLBACK],
        )
    }

    override suspend fun writeScheduleReceipt(receipt: ScheduleReceipt?): SettingsWriteResult =
        write { prefs ->
            if (receipt == null) {
                prefs.removeScheduleReceipt()
            } else {
                prefs[SettingsKeys.RECEIPT_PRESENT] = true
                prefs[SettingsKeys.RECEIPT_REVISION] = receipt.revision
                prefs[SettingsKeys.RECEIPT_BOOT_GENERATION] = receipt.bootGeneration
                prefs[SettingsKeys.RECEIPT_SUBMITTED_AT] = receipt.submittedAtEpochMillis
                if (receipt.nextStartEpochMillis == null) {
                    prefs.remove(SettingsKeys.RECEIPT_NEXT_START)
                } else {
                    prefs[SettingsKeys.RECEIPT_NEXT_START] = receipt.nextStartEpochMillis
                }
                if (receipt.nextEndEpochMillis == null) {
                    prefs.remove(SettingsKeys.RECEIPT_NEXT_END)
                } else {
                    prefs[SettingsKeys.RECEIPT_NEXT_END] = receipt.nextEndEpochMillis
                }
                if (receipt.fallbackEpochMillis == null) {
                    prefs.remove(SettingsKeys.RECEIPT_FALLBACK)
                } else {
                    prefs[SettingsKeys.RECEIPT_FALLBACK] = receipt.fallbackEpochMillis
                }
            }
        }

    override suspend fun readTemporaryTestMarker(): TemporaryTestMarker? {
        val prefs = dataStore.data.first()
        val present = prefs[SettingsKeys.TEMP_MARKER_PRESENT]
        val hasFields = prefs[SettingsKeys.TEMP_MARKER_STARTED_AT] != null ||
            prefs[SettingsKeys.TEMP_MARKER_RELEASE_AT] != null ||
            prefs[SettingsKeys.TEMP_MARKER_REVISION] != null ||
            prefs[SettingsKeys.TEMP_MARKER_SESSION_ID] != null
        if (present != true) {
            check(!hasFields) { "Temporary marker fields exist without the presence marker" }
            return null
        }
        return TemporaryTestMarker(
            startedAtEpochMillis = prefs[SettingsKeys.TEMP_MARKER_STARTED_AT] ?: error("Temporary marker is missing start time"),
            releaseAtEpochMillis = prefs[SettingsKeys.TEMP_MARKER_RELEASE_AT] ?: error("Temporary marker is missing release time"),
            revision = prefs[SettingsKeys.TEMP_MARKER_REVISION] ?: error("Temporary marker is missing revision"),
            sessionId = prefs[SettingsKeys.TEMP_MARKER_SESSION_ID],
        )
    }

    override suspend fun writeTemporaryTestMarker(marker: TemporaryTestMarker?): SettingsWriteResult =
        write { prefs ->
            if (marker == null) {
                prefs.removeTemporaryTestMarker()
            } else {
                prefs[SettingsKeys.TEMP_MARKER_PRESENT] = true
                prefs[SettingsKeys.TEMP_MARKER_STARTED_AT] = marker.startedAtEpochMillis
                prefs[SettingsKeys.TEMP_MARKER_RELEASE_AT] = marker.releaseAtEpochMillis
                prefs[SettingsKeys.TEMP_MARKER_REVISION] = marker.revision
                if (marker.sessionId == null) {
                    prefs.remove(SettingsKeys.TEMP_MARKER_SESSION_ID)
                } else {
                    prefs[SettingsKeys.TEMP_MARKER_SESSION_ID] = marker.sessionId
                }
            }
        }

    override suspend fun readRecoveryIncident(): RecoveryIncident? {
        val prefs = dataStore.data.first()
        val present = prefs[SettingsKeys.INCIDENT_PRESENT]
        val hasFields = prefs[SettingsKeys.INCIDENT_ID] != null ||
            prefs[SettingsKeys.INCIDENT_OPENED_AT] != null ||
            prefs[SettingsKeys.INCIDENT_ATTEMPTS] != null ||
            prefs[SettingsKeys.INCIDENT_UNRESOLVED] != null
        if (present != true) {
            check(!hasFields) { "Recovery incident fields exist without the presence marker" }
            return null
        }
        return RecoveryIncident(
            incidentId = prefs[SettingsKeys.INCIDENT_ID]?.takeIf { it.isNotBlank() }
                ?: error("Recovery incident is missing its identity"),
            openedAtEpochMillis = prefs[SettingsKeys.INCIDENT_OPENED_AT] ?: error("Recovery incident is missing its open time"),
            attempts = prefs[SettingsKeys.INCIDENT_ATTEMPTS]?.takeIf { it >= 0 }
                ?: error("Recovery incident has an invalid attempt count"),
            unresolved = prefs[SettingsKeys.INCIDENT_UNRESOLVED] ?: error("Recovery incident is missing its resolution state"),
        )
    }

    override suspend fun writeRecoveryIncident(incident: RecoveryIncident?): SettingsWriteResult =
        write { prefs ->
            if (incident == null) {
                prefs.removeRecoveryIncident()
            } else {
                prefs[SettingsKeys.INCIDENT_PRESENT] = true
                prefs[SettingsKeys.INCIDENT_ID] = incident.incidentId
                prefs[SettingsKeys.INCIDENT_OPENED_AT] = incident.openedAtEpochMillis
                prefs[SettingsKeys.INCIDENT_ATTEMPTS] = incident.attempts
                prefs[SettingsKeys.INCIDENT_UNRESOLVED] = incident.unresolved
                if (incident.unresolved) {
                    // The incident and release-only state are one transaction. A failed separate
                    // disable write must never let a restart resume restrictive intent.
                    prefs[SettingsKeys.ENABLED] = false
                    prefs[SettingsKeys.RECOVERY_REQUIRED] = true
                }
            }
        }

    override suspend fun readBootGeneration(): Long =
        dataStore.data.first()[SettingsKeys.BOOT_GENERATION] ?: 0L

    override suspend fun bumpBootGeneration(): Long {
        var next = 0L
        try {
            dataStore.edit { prefs ->
                next = (prefs[SettingsKeys.BOOT_GENERATION] ?: 0L) + 1L
                prefs[SettingsKeys.BOOT_GENERATION] = next
            }
        } catch (cancellation: kotlinx.coroutines.CancellationException) {
            throw cancellation
        } catch (failure: Throwable) {
            throw failure
        }
        return next
    }

    override suspend fun isCorruptionFlagged(): Boolean =
        dataStore.data.first()[SettingsKeys.CORRUPT_DETECTED] == true

    override suspend fun clearCorruptionFlag(): SettingsWriteResult =
        write { it.remove(SettingsKeys.CORRUPT_DETECTED) }

    override suspend fun isOwnerEstablished(): Boolean =
        dataStore.data.first()[SettingsKeys.OWNER_ESTABLISHED] == true

    override suspend fun markOwnerEstablished(): SettingsWriteResult =
        write { it[SettingsKeys.OWNER_ESTABLISHED] = true }

    private suspend fun write(block: (MutablePreferences) -> Unit): SettingsWriteResult = try {
        dataStore.edit { prefs -> block(prefs) }
        SettingsWriteResult.Success
    } catch (cancellation: kotlinx.coroutines.CancellationException) {
        throw cancellation
    } catch (failure: Throwable) {
        SettingsWriteResult.Failure(failure)
    }
}

/**
 * Creates the two DataStore instances the app uses. Exactly one instance per file
 * (brief section 7.7); the container holds on to the returned objects rather than
 * recreating them.
 */
object SettingsDataStores {

    const val SETTINGS_FILE_NAME: String = "spm_protection_settings"
    const val DIAGNOSTICS_FILE_NAME: String = "spm_diagnostics"

    /**
     * Device-protected: readable before the user unlocks, which is what Direct Boot
     * reconciliation needs. Not encrypted at rest - it holds configuration only,
     * never personal content.
     *
     * The file path is built from `deContext.filesDir` **directly**. The obvious-looking
     * `deContext.preferencesDataStoreFile(name)` extension must NOT be used here: it resolves
     * `applicationContext.filesDir`, and `createDeviceProtectedStorageContext()` still returns
     * the credential-protected application context, so the file silently lands in
     * `/data/user/0/...` instead of `/data/user_de/0/...`. That was observed on the emulator
     * (both files appeared under `/data/user/0/`, and `/data/user_de/0/<pkg>/` did not exist),
     * which would have made every pre-unlock read fail.
     */
    fun deviceProtected(context: Context): DataStore<Preferences> {
        val deContext = context.createDeviceProtectedStorageContext()
        return PreferenceDataStoreFactory.create(
            corruptionHandler = ReplaceFileCorruptionHandler { _ ->
                // Replace the unreadable file with a valid store holding only the
                // corruption flag: every setting therefore reads as its disabled default,
                // and readSettings() still reports the condition so the coordinator takes
                // the recovery path (brief section 7.10).
                emptyPreferences().toMutablePreferences().apply {
                    this[SettingsKeys.CORRUPT_DETECTED] = true
                }
            },
            produceFile = { File(deContext.filesDir, "datastore/$SETTINGS_FILE_NAME.preferences_pb") },
        )
    }

    /**
     * Credential-protected: diagnostic events and the last runtime observation. Never
     * required to make a protection decision, so a pre-unlock read failure here is safe.
     * Losing diagnostics to corruption is acceptable and must never block release.
     */
    fun credentialProtected(context: Context): DataStore<Preferences> {
        val appContext = context.applicationContext
        return PreferenceDataStoreFactory.create(
            corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() },
            produceFile = { File(appContext.filesDir, "datastore/$DIAGNOSTICS_FILE_NAME.preferences_pb") },
        )
    }

    /** Where each store's backing file lives, for diagnostics and instrumented tests. */
    fun settingsFile(context: Context): File =
        File(context.createDeviceProtectedStorageContext().filesDir, "datastore/$SETTINGS_FILE_NAME.preferences_pb")

    fun diagnosticsFile(context: Context): File =
        File(context.applicationContext.filesDir, "datastore/$DIAGNOSTICS_FILE_NAME.preferences_pb")
}
