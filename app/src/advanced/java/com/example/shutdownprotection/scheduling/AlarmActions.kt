package com.example.shutdownprotection.scheduling

/**
 * The four stable, app-owned alarm identities (brief section 14).
 *
 * All strings are derived from the *actual* application ID rather than hardcoded, so a
 * non-default `-Pspm.appId=...` build produces a consistent, self-consistent set
 * (brief section 3.2).
 */
object AlarmActions {

    fun startProtection(applicationId: String): String = "$applicationId.action.START_PROTECTION"

    fun endProtection(applicationId: String): String = "$applicationId.action.END_PROTECTION"

    fun releaseFallback(applicationId: String): String = "$applicationId.action.RELEASE_FALLBACK"

    fun recoveryRetry(applicationId: String): String = "$applicationId.action.RECOVERY_RETRY"

    /**
     * Gate B debug-only temporary-test release (brief section 22 step 6).
     *
     * These two are **not** part of the scheduler's four identities. They exist because the
     * brief requires the temporary test's release handler to invoke `RecoveryManager` and to
     * never enable protection. Routing that timer through the daily `END_PROTECTION` identity
     * would be wrong twice over: the handler would run the normal reconciler (which, under a
     * forced-restriction override, would simply re-restrict), and it would replace the real
     * daily END alarm while the stored receipt still claimed that plan was installed.
     */
    fun temporaryTestRelease(applicationId: String): String =
        "$applicationId.action.TEMPORARY_TEST_RELEASE"

    fun temporaryTestFallback(applicationId: String): String =
        "$applicationId.action.TEMPORARY_TEST_FALLBACK"

    fun actionFor(applicationId: String, kind: AlarmEventKind): String = when (kind) {
        AlarmEventKind.START -> startProtection(applicationId)
        AlarmEventKind.END -> endProtection(applicationId)
        AlarmEventKind.RELEASE_FALLBACK -> releaseFallback(applicationId)
        AlarmEventKind.RECOVERY_RETRY -> recoveryRetry(applicationId)
        AlarmEventKind.TEMPORARY_TEST_RELEASE -> temporaryTestRelease(applicationId)
        AlarmEventKind.TEMPORARY_TEST_FALLBACK -> temporaryTestFallback(applicationId)
    }

    fun kindFor(applicationId: String, action: String?): AlarmEventKind? = when (action) {
        startProtection(applicationId) -> AlarmEventKind.START
        endProtection(applicationId) -> AlarmEventKind.END
        releaseFallback(applicationId) -> AlarmEventKind.RELEASE_FALLBACK
        recoveryRetry(applicationId) -> AlarmEventKind.RECOVERY_RETRY
        temporaryTestRelease(applicationId) -> AlarmEventKind.TEMPORARY_TEST_RELEASE
        temporaryTestFallback(applicationId) -> AlarmEventKind.TEMPORARY_TEST_FALLBACK
        else -> null
    }

    /** The four identities the scheduler owns. */
    val SCHEDULER_KINDS: List<AlarmEventKind> = listOf(
        AlarmEventKind.START,
        AlarmEventKind.END,
        AlarmEventKind.RELEASE_FALLBACK,
        AlarmEventKind.RECOVERY_RETRY,
    )

    /** The debug-only temporary-test identities. */
    val TEMPORARY_TEST_KINDS: List<AlarmEventKind> = listOf(
        AlarmEventKind.TEMPORARY_TEST_RELEASE,
        AlarmEventKind.TEMPORARY_TEST_FALLBACK,
    )

    /**
     * Distinct request codes. PendingIntent equality ignores extras, so identity must come
     * from the request code plus the action, never from a revision extra (brief section 14).
     */
    const val REQUEST_CODE_START: Int = 1001
    const val REQUEST_CODE_END: Int = 1002
    const val REQUEST_CODE_FALLBACK: Int = 1003
    const val REQUEST_CODE_RECOVERY_RETRY: Int = 1004
    const val REQUEST_CODE_TEMPORARY_TEST_RELEASE: Int = 1005
    const val REQUEST_CODE_TEMPORARY_TEST_FALLBACK: Int = 1006

    fun requestCode(kind: AlarmEventKind): Int = when (kind) {
        AlarmEventKind.START -> REQUEST_CODE_START
        AlarmEventKind.END -> REQUEST_CODE_END
        AlarmEventKind.RELEASE_FALLBACK -> REQUEST_CODE_FALLBACK
        AlarmEventKind.RECOVERY_RETRY -> REQUEST_CODE_RECOVERY_RETRY
        AlarmEventKind.TEMPORARY_TEST_RELEASE -> REQUEST_CODE_TEMPORARY_TEST_RELEASE
        AlarmEventKind.TEMPORARY_TEST_FALLBACK -> REQUEST_CODE_TEMPORARY_TEST_FALLBACK
    }

    // ---- Diagnostic / staleness metadata. Never the alarm's identity. ----

    fun extraEventKind(applicationId: String): String = "$applicationId.extra.EVENT_KIND"

    fun extraSettingsRevision(applicationId: String): String = "$applicationId.extra.SETTINGS_REVISION"

    fun extraPlannedBoundary(applicationId: String): String = "$applicationId.extra.PLANNED_BOUNDARY"

    fun extraIncidentId(applicationId: String): String = "$applicationId.extra.INCIDENT_ID"

    /** Durable temporary-test identity; timestamps alone can collide after clock rollback. */
    fun extraTemporaryTestSessionId(applicationId: String): String =
        "$applicationId.extra.TEMPORARY_TEST_SESSION_ID"

    /**
     * The best-effort release fallback is submitted this long after the scheduled end. It
     * is a *secondary attempt*, not a guarantee: it can be delayed or prevented by
     * platform/OEM restrictions or force-stop (brief section 14).
     */
    const val FALLBACK_DELAY_MILLIS: Long = 60_000L
}

/** Which boundary an alarm delivery represents. */
enum class AlarmEventKind(val wireName: String) {
    START("START"),
    END("END"),
    RELEASE_FALLBACK("RELEASE_FALLBACK"),
    RECOVERY_RETRY("RECOVERY_RETRY"),

    /** Debug-only: the Gate B temporary test's exact release timer. Invokes RecoveryManager. */
    TEMPORARY_TEST_RELEASE("TEMPORARY_TEST_RELEASE"),

    /** Debug-only: the Gate B temporary test's distinct inexact fallback. */
    TEMPORARY_TEST_FALLBACK("TEMPORARY_TEST_FALLBACK");

    /** True for the two debug-only identities, which are not part of the scheduler's four. */
    val isTemporaryTest: Boolean
        get() = this == TEMPORARY_TEST_RELEASE || this == TEMPORARY_TEST_FALLBACK

    companion object {
        fun fromWireName(value: String?): AlarmEventKind? =
            entries.firstOrNull { it.wireName == value }
    }
}
