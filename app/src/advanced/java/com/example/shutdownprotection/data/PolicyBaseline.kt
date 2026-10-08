package com.example.shutdownprotection.data

/**
 * The device's original lock-task policy, captured *before* this app mutates it
 * (brief section 7.6 / 8).
 *
 * The distinction that matters: this is the configuration the device had before the
 * app touched it. It must never be overwritten with the app's own already-modified
 * values on a later resume, or full disarm would "restore" the app's restriction.
 */
data class PolicyBaseline(
    val capturedAtEpochMillis: Long,
    val lockTaskPackages: Set<String>,
    val lockTaskFeatures: Int,
    val applicationId: String,
    /** Missing persisted provenance is always treated as legacy and ambiguous. */
    val provenance: BaselineProvenance = BaselineProvenance.LEGACY_UNVERIFIED,
    /** Missing persisted lifecycle is never interpreted as a fresh session. */
    val lifecycle: BaselineLifecycleState = BaselineLifecycleState.LEGACY_UNKNOWN,
) {
    /**
     * Whether this baseline can be trusted to describe *this* installation's original policy
     * (repair R02 step 6). A baseline belonging to a different application ID, or one with an
     * impossible feature mask, must never be restored as if it were ours.
     */
    fun isValidFor(expectedApplicationId: String): Boolean =
        applicationId.isNotBlank() && applicationId == expectedApplicationId &&
            capturedAtEpochMillis >= 0L &&
            lockTaskFeatures >= 0 &&
            lockTaskPackages.none { it.isBlank() }

    /** Only explicit capture/confirmation plus a known lifecycle can authorize restoration. */
    val isTrustedOriginal: Boolean
        get() = provenance in setOf(
            BaselineProvenance.CAPTURED_BEFORE_PREPARATION,
            BaselineProvenance.USER_CONFIRMED,
        ) && lifecycle in setOf(BaselineLifecycleState.PREPARED, BaselineLifecycleState.RESTORED)

    /** Reason a stored baseline was rejected, for the recovery result and diagnostics. */
    fun invalidReasonFor(expectedApplicationId: String): String? = when {
        applicationId.isBlank() -> "baseline application id is blank"
        applicationId != expectedApplicationId ->
            "baseline belongs to application '$applicationId', not '$expectedApplicationId'"
        lockTaskFeatures < 0 -> "baseline feature mask is negative (${lockTaskFeatures})"
        capturedAtEpochMillis < 0L -> "baseline capture time is invalid"
        lockTaskPackages.any { it.isBlank() } -> "baseline package list contains a blank entry"
        else -> null
    }

    companion object {
        const val SCHEMA_VERSION: Int = 1
    }
}

/** Evidence for why a baseline may be applied during release. */
enum class BaselineProvenance {
    /** Records created before the provenance journal existed remain ambiguous forever. */
    LEGACY_UNVERIFIED,
    /** Captured and atomically journaled before this installation prepared policy mutation. */
    CAPTURED_BEFORE_PREPARATION,
    /** Explicitly resolved by a future safe user-confirmation flow. */
    USER_CONFIRMED,
}

/** Durable state of the session for which the baseline was captured. */
enum class BaselineLifecycleState {
    LEGACY_UNKNOWN,
    PREPARED,
    RESTORED,
}
