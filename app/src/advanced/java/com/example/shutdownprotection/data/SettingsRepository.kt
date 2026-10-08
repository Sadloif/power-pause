package com.example.shutdownprotection.data

import kotlinx.coroutines.flow.Flow

/**
 * Durable configuration and session bookkeeping (brief section 7).
 *
 * Design notes that the implementation must preserve:
 *  - There is exactly one store instance per backing file (brief section 7.7).
 *  - The settings store lives in device-protected storage so the minimum state needed
 *    by Direct Boot is readable before unlock (brief section 7.8). Diagnostics live in
 *    credential-protected storage and are never required for a correct decision.
 *  - Unreadable/corrupt settings are a recovery condition, not a silent reset
 *    (brief section 7.10).
 */
interface SettingsRepository {

    /** Cold flow of durable intent. Collection before unlock must use the settings store only. */
    val settingsFlow: Flow<ProtectionSettings>

    suspend fun readSettings(): SettingsReadResult

    suspend fun writeSettings(settings: ProtectionSettings): SettingsWriteResult

    /**
     * Read-modify-write of durable intent. Implementations must serialize this against
     * other writers; the coordinator also serializes at a higher level.
     */
    suspend fun editSettings(transform: (ProtectionSettings) -> ProtectionSettings): SettingsWriteResult

    // ---- Policy baseline (brief section 7.6) ----

    suspend fun readBaseline(): PolicyBaseline?

    suspend fun writeBaseline(baseline: PolicyBaseline): SettingsWriteResult

    suspend fun clearBaseline(): SettingsWriteResult

    /**
     * Atomically stores a newly captured original baseline **together with** the preparation
     * journal (`enabled=false`, `recoveryRequired=true`, revision advanced) in one durable write
     * (repair R02).
     *
     * Why one operation: arming previously wrote the baseline and the journal as two independent
     * writes and ignored the baseline result, so a failed baseline write could still be followed
     * by a real managed session. A partial pair is now impossible — either both land or neither
     * does, and the caller must check the result before touching policy.
     *
     * The baseline and journal commit together with the optional temporary-session marker; no
     * caller may mutate policy if preparation fails. A valid `PREPARED` original for this app is
     * reused so an interrupted session cannot replace its original with app-modified values. A
     * trusted `RESTORED` original belongs to a completed session, so a new preparation captures
     * the supplied candidate as the then-current original. A legacy, malformed, cross-app, or
     * otherwise ambiguous record is refused and never overwritten. Daily preparation advances
     * the settings revision and sets `recoveryRequired`; temporary preparation keeps daily intent
     * disabled, writes its temporary release marker atomically, and does not create a daily recovery
     * flag.
     */
    suspend fun prepareSession(
        candidateBaseline: PolicyBaseline,
        expectedApplicationId: String,
        temporaryMarker: TemporaryTestMarker? = null,
    ): SessionPreparationResult

    /**
     * One atomic cleanup commit for verified recovery (repair R03 step 7).
     *
     * Combines the required cleanup fields so a partially-cleaned state cannot be mistaken for a
     * finished one: disabled intent, resolved recovery marker, cleared incident / corruption /
     * temporary-test markers, and invalidated schedule receipt. The caller must check the result;
     * a failure keeps recovery incomplete.
     */
    suspend fun commitCleanup(commit: CleanupCommit): SettingsWriteResult

    // ---- Scheduling receipt (brief section 14.5) ----

    /** Records what was *submitted*; it is not a guarantee that Android will deliver it. */
    suspend fun readScheduleReceipt(): ScheduleReceipt?

    suspend fun writeScheduleReceipt(receipt: ScheduleReceipt?): SettingsWriteResult

    // ---- Temporary debug-test marker (brief section 22) ----

    /** A durable marker so an interrupted temporary test triggers recovery, not resumption. */
    suspend fun readTemporaryTestMarker(): TemporaryTestMarker?

    suspend fun writeTemporaryTestMarker(marker: TemporaryTestMarker?): SettingsWriteResult

    // ---- Recovery incident bookkeeping (brief section 10) ----

    suspend fun readRecoveryIncident(): RecoveryIncident?

    /**
     * Stores or clears durable release-only incident evidence. Persisting any unresolved incident
     * must atomically set `enabled=false` and `recoveryRequired=true` in the same transaction; if
     * that transaction fails, neither the incident nor its disabling flags may be treated as
     * durable. Clearing an incident alone does not resolve the settings journal; only verified
     * cleanup may clear both through [commitCleanup].
     */
    suspend fun writeRecoveryIncident(incident: RecoveryIncident?): SettingsWriteResult

    // ---- Boot generation ----

    /**
     * Monotonic counter advanced once per boot. Alarms installed in an earlier boot
     * generation are reinstalled even when the computed boundary is unchanged.
     */
    suspend fun readBootGeneration(): Long

    suspend fun bumpBootGeneration(): Long

    // ---- Corruption flag (brief section 7.10) ----

    /** True when the last read had to recover from an unreadable settings file. */
    suspend fun isCorruptionFlagged(): Boolean

    /** Called only after release/recovery has been verified for the corrupt state. */
    suspend fun clearCorruptionFlag(): SettingsWriteResult

    // ---- Device Owner history ----

    /**
     * True once Device Owner access has been observed for this installation. Distinguishes
     * "Lost" (access was there and went away) from "Required" (never provisioned) on the
     * setup screen (brief section 19).
     */
    suspend fun isOwnerEstablished(): Boolean

    suspend fun markOwnerEstablished(): SettingsWriteResult
}

/** What happens to the retained baseline when cleanup is committed. */
enum class BaselineLifecycle {
    /**
     * Retain the original baseline for audit and for a still-unresolved incident.
     */
    KEEP,

    /** Retain the original evidence while recording that release verified it. */
    MARK_RESTORED,

    /**
     * Clear it, because cleanup is verified and the next genuinely new session must capture the
     * then-current original policy rather than silently reusing an unrelated old one.
     */
    CLEAR,
}

/**
 * The required cleanup fields, committed together. Defaults match verified cleanup; a caller
 * should only turn a field off deliberately.
 */
data class CleanupCommit(
    val enabled: Boolean = false,
    val recoveryRequired: Boolean = false,
    val clearCorruptionFlag: Boolean = true,
    val clearTemporaryTestMarker: Boolean = true,
    val clearRecoveryIncident: Boolean = true,
    val clearScheduleReceipt: Boolean = true,
    val baselineLifecycle: BaselineLifecycle = BaselineLifecycle.CLEAR,
)

/** Result of [SettingsRepository.prepareSession]. */
sealed interface SessionPreparationResult {

    /** A fresh baseline was captured and the journal written, atomically. */
    data class Prepared(
        val baseline: PolicyBaseline,
        val revision: Long,
    ) : SessionPreparationResult

    /** A valid baseline for this installation already existed and was preserved unchanged. */
    data class ReusedExisting(
        val baseline: PolicyBaseline,
        val revision: Long,
    ) : SessionPreparationResult

    /** The atomic write failed. Nothing may be mutated on the strength of this result. */
    data class Failed(val cause: Throwable) : SessionPreparationResult

    /**
     * A stored baseline exists but cannot be trusted for this installation. This is a recovery
     * condition, not a reason to capture a fresh one over the top of it.
     */
    data class InvalidStoredBaseline(val reason: String) : SessionPreparationResult
}

/** Result of reading durable settings. */
sealed interface SettingsReadResult {
    data class Success(val settings: ProtectionSettings) : SettingsReadResult

    /**
     * The store could not be interpreted. The caller must treat this as a recovery
     * condition: default to disabled, attempt release of app-imposed restriction, and
     * surface an error. Never silently create a fresh enabled schedule.
     */
    data class Corrupt(val cause: Throwable) : SettingsReadResult
}

/** Result of a durable write. */
sealed interface SettingsWriteResult {
    data object Success : SettingsWriteResult
    data class Failure(val cause: Throwable) : SettingsWriteResult
}

/**
 * What was submitted to AlarmManager for a given settings revision (brief section 14.5).
 * Null boundary fields mean the boundary was not part of the submitted plan.
 */
data class ScheduleReceipt(
    val revision: Long,
    val bootGeneration: Long,
    val submittedAtEpochMillis: Long,
    val nextStartEpochMillis: Long?,
    val nextEndEpochMillis: Long?,
    val fallbackEpochMillis: Long?,
)

/**
 * Marker for the debug-only temporary short-interval test (brief section 22). Its only
 * purpose is to be found later and force recovery rather than resumption.
 */
data class TemporaryTestMarker(
    val startedAtEpochMillis: Long,
    val releaseAtEpochMillis: Long,
    val revision: Long,
    /** Null only for a marker written before session identities were introduced. */
    val sessionId: String? = null,
)

/**
 * Bookkeeping for one unresolved cleanup incident (brief section 10). [attempts] is
 * capped at [MAX_ATTEMPTS]; the manual recovery route always remains available after
 * that.
 */
data class RecoveryIncident(
    val incidentId: String,
    val openedAtEpochMillis: Long,
    val attempts: Int,
    val unresolved: Boolean,
) {
    fun withAttempt(): RecoveryIncident = copy(attempts = attempts + 1)

    companion object {
        const val MAX_ATTEMPTS: Int = 3

        /** Brief section 10: at least 20 minutes between release-only retries. */
        const val RETRY_DELAY_MILLIS: Long = 20L * 60L * 1000L
    }
}
