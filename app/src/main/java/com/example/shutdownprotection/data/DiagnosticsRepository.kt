package com.example.shutdownprotection.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.CancellationException

/**
 * One bounded, structured diagnostic record (brief section 20).
 *
 * Nothing here is personal content: no contacts, notifications' contents, messages,
 * browsing content, location, photos, credentials, or activity contents. Application
 * labels and package selections are configuration, and they are never turned into a
 * usage history.
 */
data class DiagnosticEvent(
    val timestampMillis: Long,
    val kind: String,
    val revision: Long,
    val message: String,
)

/**
 * The last observed Android state. Stored separately from durable user intent, because a
 * stored "Protected" value from yesterday is not today's runtime state (brief 7.11).
 */
data class RuntimeObservation(
    val observedAtEpochMillis: Long,
    val stateName: String,
    val settingsRevision: Long,
    val deviceOwner: Boolean?,
    val lockTaskState: Int?,
    val requestedFeatures: Int?,
    val effectiveFeatures: Int?,
    val effectivePackages: Set<String>?,
    val exactAlarmCapability: Boolean?,
    val bootCompleted: Boolean?,
    val userUnlocked: Boolean?,
    val nextStartEpochMillis: Long?,
    val nextEndEpochMillis: Long?,
    val fallbackEpochMillis: Long?,
    val lastPolicyResultCode: Int?,
    val lastPolicyObservedAtEpochMillis: Long?,
    val recoveryStatus: String,
)

/**
 * Pure encoding for the bounded event log. Kept separate from storage so the trimming and
 * sanitising rules can be unit-tested without a DataStore.
 */
object DiagnosticCodec {

    const val FIELD_SEPARATOR: Char = '\u001F'
    const val LINE_SEPARATOR: Char = '\n'
    const val MAX_MESSAGE_LENGTH: Int = 512

    /** Brief section 20: initially 1,000 records or 1 MiB. */
    const val MAX_EVENTS: Int = 1000
    const val MAX_BYTES: Int = 1024 * 1024

    /** Long digit runs are most likely phone numbers or account identifiers. */
    private val LONG_DIGIT_RUN = Regex("\\d{7,}")

    const val REDACTED: String = "<redacted-digits>"

    /**
     * Removes record separators and control characters, redacts long digit runs, and caps
     * length. Callers must still never pass personal content - this is a structural guard,
     * not a content classifier.
     */
    fun sanitize(message: String): String = message
        .replace('\n', ' ')
        .replace('\r', ' ')
        .replace(FIELD_SEPARATOR, ' ')
        .replace(LONG_DIGIT_RUN, REDACTED)
        .take(MAX_MESSAGE_LENGTH)

    fun encode(events: List<DiagnosticEvent>): String = events.joinToString(LINE_SEPARATOR.toString()) { event ->
        listOf(
            event.timestampMillis.toString(),
            event.kind,
            event.revision.toString(),
            event.message,
        ).joinToString(FIELD_SEPARATOR.toString())
    }

    fun decode(raw: String): List<DiagnosticEvent> {
        if (raw.isEmpty()) return emptyList()
        return raw.split(LINE_SEPARATOR).mapNotNull { line ->
            if (line.isEmpty()) return@mapNotNull null
            val fields = line.split(FIELD_SEPARATOR)
            if (fields.size < 4) return@mapNotNull null
            val timestamp = fields[0].toLongOrNull() ?: return@mapNotNull null
            val revision = fields[2].toLongOrNull() ?: return@mapNotNull null
            DiagnosticEvent(
                timestampMillis = timestamp,
                kind = fields[1],
                revision = revision,
                message = fields.drop(3).joinToString(FIELD_SEPARATOR.toString()),
            )
        }
    }

    /**
     * Keeps the newest events within both bounds. Returns the trimmed list; the caller
     * writes it back.
     *
     * [maxEvents] and [maxBytes] are parameters so the byte bound is reachable in a test.
     * With the shipped constants the byte bound is *not* the binding one: 1,000 events of at
     * most [MAX_MESSAGE_LENGTH] characters cannot exceed roughly 535 KB, so the record bound
     * always trips first. The byte check remains as a guard for any future change to the
     * message cap.
     */
    fun trim(
        events: List<DiagnosticEvent>,
        maxEvents: Int = MAX_EVENTS,
        maxBytes: Int = MAX_BYTES,
    ): List<DiagnosticEvent> {
        var kept = if (events.size <= maxEvents) events else events.takeLast(maxEvents)
        while (kept.size > 1 && encode(kept).toByteArray(Charsets.UTF_8).size > maxBytes) {
            kept = kept.drop(1)
        }
        return kept
    }
}

/**
 * Bounded local diagnostics. Deliberately credential-protected: never required for a
 * protection decision, so a pre-unlock failure here is safe. Every caller must treat a
 * failure as non-fatal - brief section 12.13 and section 20 both require that logging
 * failure must not block power-menu release.
 */
interface DiagnosticsRepository {
    suspend fun record(kind: String, revision: Long, message: String)
    suspend fun readAll(): List<DiagnosticEvent>
    suspend fun clear()
    suspend fun exportText(): String
    suspend fun readObservation(): RuntimeObservation?
    suspend fun writeObservation(observation: RuntimeObservation)
}

/** DataStore-backed implementation with the brief's retention bounds. */
class DataStoreDiagnosticsRepository(
    private val dataStore: DataStore<Preferences>,
    private val clockMillis: () -> Long = { System.currentTimeMillis() },
) : DiagnosticsRepository {

    override suspend fun record(kind: String, revision: Long, message: String) {
        val event = DiagnosticEvent(
            timestampMillis = clockMillis(),
            kind = kind,
            revision = revision,
            message = DiagnosticCodec.sanitize(message),
        )
        try {
            dataStore.edit { prefs ->
                val existing = DiagnosticCodec.decode(prefs[KEY_EVENTS].orEmpty())
                val updated = DiagnosticCodec.trim(existing + event)
                prefs[KEY_EVENTS] = DiagnosticCodec.encode(updated)
            }
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Throwable) {
            // Diagnostics are optional; a failure must not affect release decisions.
        }
    }

    override suspend fun readAll(): List<DiagnosticEvent> =
        DiagnosticCodec.decode(dataStore.data.first()[KEY_EVENTS].orEmpty())

    override suspend fun clear() {
        try {
            dataStore.edit { it.remove(KEY_EVENTS) }
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Throwable) {
            // Optional diagnostics.
        }
    }

    override suspend fun exportText(): String {
        val events = readAll()
        val observation = readObservation()
        return buildString {
            appendLine("Scheduled Power Menu Restriction - diagnostic export")
            appendLine("Exported (epoch millis): ${clockMillis()}")
            appendLine("Events retained: ${events.size} (bounds: ${DiagnosticCodec.MAX_EVENTS} records / ${DiagnosticCodec.MAX_BYTES} bytes)")
            appendLine()
            appendLine("== Last runtime observation ==")
            appendLine(observation?.toString() ?: "(none recorded)")
            appendLine()
            appendLine("== Events (oldest first) ==")
            events.forEach { event ->
                appendLine("${event.timestampMillis}\t${event.kind}\trev=${event.revision}\t${event.message}")
            }
        }
    }

    override suspend fun readObservation(): RuntimeObservation? {
        val prefs = dataStore.data.first()
        if (prefs[KEY_OBS_PRESENT] != true) return null
        return RuntimeObservation(
            observedAtEpochMillis = prefs[KEY_OBS_AT] ?: error("Runtime observation is missing timestamp"),
            stateName = prefs[KEY_OBS_STATE] ?: error("Runtime observation is missing state"),
            settingsRevision = prefs[KEY_OBS_REVISION] ?: error("Runtime observation is missing revision"),
            deviceOwner = prefs[KEY_OBS_OWNER],
            lockTaskState = prefs[KEY_OBS_LOCK_STATE],
            requestedFeatures = prefs[KEY_OBS_REQUESTED_FEATURES],
            effectiveFeatures = prefs[KEY_OBS_EFFECTIVE_FEATURES],
            effectivePackages = prefs[KEY_OBS_EFFECTIVE_PACKAGES]?.toSet(),
            exactAlarmCapability = prefs[KEY_OBS_EXACT],
            bootCompleted = prefs[KEY_OBS_BOOT_COMPLETED],
            userUnlocked = prefs[KEY_OBS_USER_UNLOCKED],
            nextStartEpochMillis = prefs[KEY_OBS_NEXT_START],
            nextEndEpochMillis = prefs[KEY_OBS_NEXT_END],
            fallbackEpochMillis = prefs[KEY_OBS_FALLBACK],
            lastPolicyResultCode = prefs[KEY_OBS_POLICY_RESULT],
            lastPolicyObservedAtEpochMillis = prefs[KEY_OBS_POLICY_AT],
            recoveryStatus = prefs[KEY_OBS_RECOVERY] ?: error("Runtime observation is missing recovery state"),
        )
    }

    override suspend fun writeObservation(observation: RuntimeObservation) {
        try {
            dataStore.edit { prefs ->
                prefs[KEY_OBS_PRESENT] = true
                prefs[KEY_OBS_AT] = observation.observedAtEpochMillis
                prefs[KEY_OBS_STATE] = observation.stateName
                prefs[KEY_OBS_REVISION] = observation.settingsRevision
                if (observation.deviceOwner == null) prefs.remove(KEY_OBS_OWNER)
                else prefs[KEY_OBS_OWNER] = observation.deviceOwner
                if (observation.lockTaskState == null) prefs.remove(KEY_OBS_LOCK_STATE)
                else prefs[KEY_OBS_LOCK_STATE] = observation.lockTaskState
                if (observation.requestedFeatures == null) {
                    prefs.remove(KEY_OBS_REQUESTED_FEATURES)
                } else {
                    prefs[KEY_OBS_REQUESTED_FEATURES] = observation.requestedFeatures
                }
                if (observation.effectiveFeatures == null) {
                    prefs.remove(KEY_OBS_EFFECTIVE_FEATURES)
                } else {
                    prefs[KEY_OBS_EFFECTIVE_FEATURES] = observation.effectiveFeatures
                }
                if (observation.effectivePackages == null) prefs.remove(KEY_OBS_EFFECTIVE_PACKAGES)
                else prefs[KEY_OBS_EFFECTIVE_PACKAGES] = observation.effectivePackages.toSet()
                if (observation.exactAlarmCapability == null) prefs.remove(KEY_OBS_EXACT)
                else prefs[KEY_OBS_EXACT] = observation.exactAlarmCapability
                if (observation.bootCompleted == null) prefs.remove(KEY_OBS_BOOT_COMPLETED)
                else prefs[KEY_OBS_BOOT_COMPLETED] = observation.bootCompleted
                if (observation.userUnlocked == null) prefs.remove(KEY_OBS_USER_UNLOCKED)
                else prefs[KEY_OBS_USER_UNLOCKED] = observation.userUnlocked
                if (observation.nextStartEpochMillis == null) {
                    prefs.remove(KEY_OBS_NEXT_START)
                } else {
                    prefs[KEY_OBS_NEXT_START] = observation.nextStartEpochMillis
                }
                if (observation.nextEndEpochMillis == null) {
                    prefs.remove(KEY_OBS_NEXT_END)
                } else {
                    prefs[KEY_OBS_NEXT_END] = observation.nextEndEpochMillis
                }
                if (observation.fallbackEpochMillis == null) {
                    prefs.remove(KEY_OBS_FALLBACK)
                } else {
                    prefs[KEY_OBS_FALLBACK] = observation.fallbackEpochMillis
                }
                if (observation.lastPolicyResultCode == null) {
                    prefs.remove(KEY_OBS_POLICY_RESULT)
                } else {
                    prefs[KEY_OBS_POLICY_RESULT] = observation.lastPolicyResultCode
                }
                if (observation.lastPolicyObservedAtEpochMillis == null) {
                    prefs.remove(KEY_OBS_POLICY_AT)
                } else {
                    prefs[KEY_OBS_POLICY_AT] = observation.lastPolicyObservedAtEpochMillis
                }
                prefs[KEY_OBS_RECOVERY] = observation.recoveryStatus
            }
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Throwable) {
            // Observation diagnostics are optional.
        }
    }

    private companion object {
        val KEY_EVENTS = stringPreferencesKey("events")

        val KEY_OBS_PRESENT = booleanPreferencesKey("obs_present")
        val KEY_OBS_AT = longPreferencesKey("obs_at")
        val KEY_OBS_STATE = stringPreferencesKey("obs_state")
        val KEY_OBS_REVISION = longPreferencesKey("obs_revision")
        val KEY_OBS_OWNER = booleanPreferencesKey("obs_owner")
        val KEY_OBS_LOCK_STATE = intPreferencesKey("obs_lock_state")
        val KEY_OBS_REQUESTED_FEATURES = intPreferencesKey("obs_requested_features")
        val KEY_OBS_EFFECTIVE_FEATURES = intPreferencesKey("obs_effective_features")
        val KEY_OBS_EFFECTIVE_PACKAGES = stringSetPreferencesKey("obs_effective_packages")
        val KEY_OBS_EXACT = booleanPreferencesKey("obs_exact")
        val KEY_OBS_BOOT_COMPLETED = booleanPreferencesKey("obs_boot_completed")
        val KEY_OBS_USER_UNLOCKED = booleanPreferencesKey("obs_user_unlocked")
        val KEY_OBS_NEXT_START = longPreferencesKey("obs_next_start")
        val KEY_OBS_NEXT_END = longPreferencesKey("obs_next_end")
        val KEY_OBS_FALLBACK = longPreferencesKey("obs_fallback")
        val KEY_OBS_POLICY_RESULT = intPreferencesKey("obs_policy_result")
        val KEY_OBS_POLICY_AT = longPreferencesKey("obs_policy_at")
        val KEY_OBS_RECOVERY = stringPreferencesKey("obs_recovery")
    }
}
