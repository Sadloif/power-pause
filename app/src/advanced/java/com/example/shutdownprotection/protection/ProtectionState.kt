package com.example.shutdownprotection.protection

import com.example.shutdownprotection.admin.LockTaskRuntimeStates

/**
 * Internal runtime states (brief section 7). The screen may use simpler wording, but these
 * distinctions must exist internally - in particular "armed but not actually protecting"
 * is never collapsed into "protected".
 */
enum class ProtectionState(val wireName: String) {
    NOT_DEVICE_OWNER("NOT_DEVICE_OWNER"),
    DISARMED("DISARMED"),
    ARMING("ARMING"),
    ARMED_POWER_MENU_ALLOWED("ARMED_POWER_MENU_ALLOWED"),
    ARMED_POWER_MENU_RESTRICTED("ARMED_POWER_MENU_RESTRICTED"),
    WAITING_FOR_UNLOCK("WAITING_FOR_UNLOCK"),
    WAITING_FOR_SESSION("WAITING_FOR_SESSION"),
    EXACT_SCHEDULING_UNAVAILABLE("EXACT_SCHEDULING_UNAVAILABLE"),
    POLICY_PENDING("POLICY_PENDING"),
    CONFIGURATION_ERROR("CONFIGURATION_ERROR"),
    RECOVERING("RECOVERING"),
    RECOVERY_FAILED("RECOVERY_FAILED"),
}

/** Every entry point that may drive reconciliation (brief section 12). */
enum class ProtectionTrigger(val wireName: String) {
    USER_ENABLE("USER_ENABLE"),
    USER_DISABLE("USER_DISABLE"),
    USER_EDIT("USER_EDIT"),
    APP_FOREGROUND("APP_FOREGROUND"),
    START_ALARM("START_ALARM"),
    END_ALARM("END_ALARM"),
    RELEASE_FALLBACK("RELEASE_FALLBACK"),
    BOOT_LOCKED("BOOT_LOCKED"),
    BOOT_UNLOCKED("BOOT_UNLOCKED"),
    USER_UNLOCKED("USER_UNLOCKED"),
    TIME_CHANGED("TIME_CHANGED"),
    TIMEZONE_CHANGED("TIMEZONE_CHANGED"),
    APP_UPDATED("APP_UPDATED"),
    EXACT_ACCESS_GRANTED("EXACT_ACCESS_GRANTED"),
    POLICY_CHANGED("POLICY_CHANGED"),
    LOCK_TASK_CHANGED("LOCK_TASK_CHANGED"),
    USER_ARM_REQUEST("USER_ARM_REQUEST"),
    USER_RESUME_REQUEST("USER_RESUME_REQUEST"),
    POC_START_SESSION("POC_START_SESSION"),
    POC_ALLOW_MENU("POC_ALLOW_MENU"),
    POC_RESTRICT_MENU("POC_RESTRICT_MENU"),
    RECOVERY_RETRY_ALARM("RECOVERY_RETRY_ALARM"),
}

/** Device Owner presentation, per brief section 19. */
enum class DeviceOwnerUiState { READY, REQUIRED, LOST, UNKNOWN }

/**
 * Manual override used only by the Gate B proof-of-concept screen (brief section 22).
 *
 * The POC is an explicit temporary debug session, distinct from the persistent daily
 * enabled preference, so it must be able to drive the mask without a schedule.
 */
enum class PocOverride {
    /** No override: the daily schedule decides. */
    NONE,

    /** Manual "Allow power menu" from the POC screen. */
    FORCE_ALLOWED,

    /** Manual "Restrict power menu" from the POC screen. */
    FORCE_RESTRICTED,
}/** Managed-session presentation, per brief section 19. */
enum class ManagedSessionUiState { ACTIVE, NOT_ACTIVE, WAITING_FOR_UNLOCK, UNKNOWN }

/** Power-menu presentation, per brief section 19. */
enum class PowerMenuUiState { RESTRICTED, ALLOWED, NOT_VERIFIED }

/**
 * The seven conditions that must all hold before the UI may display "Power menu
 * restricted" (brief section 19). Modelled as data so the rule is testable rather than
 * being re-derived inline in a composable.
 */
data class PowerMenuClaimInputs(
    val enabledIntentCurrentAndValid: Boolean,
    val currentlyInsideProtectedInterval: Boolean,
    val deviceOwnerPresent: Boolean,
    val runtimeStateLocked: Boolean,
    val effectivePolicyMatchesRequirement: Boolean,
    val releasePlanSubmittedForCurrentRevision: Boolean,
    val noUnresolvedPolicyOrRecoveryFailure: Boolean,
) {
    fun canClaimRestricted(): Boolean =
        enabledIntentCurrentAndValid &&
            currentlyInsideProtectedInterval &&
            deviceOwnerPresent &&
            runtimeStateLocked &&
            effectivePolicyMatchesRequirement &&
            releasePlanSubmittedForCurrentRevision &&
            noUnresolvedPolicyOrRecoveryFailure
}

/** Everything the UI and diagnostics need from one reconciliation pass. */
data class ProtectionStatus(
    val state: ProtectionState,
    val detail: String,
    val settingsRevision: Long,
    val observedAtEpochMillis: Long,
    /** Null when the DevicePolicyManager service or authority read was unavailable. */
    val deviceOwner: Boolean?,
    /**
     * True once ownership has been observed for this installation. Distinguishes "Lost"
     * (ownership was there and went away) from "Required" (never provisioned).
     */
    val ownerEverEstablished: Boolean,
    /**
     * The observed runtime lock-task state, or **null when the read failed**.
     *
     * Nullable on purpose (review-2 section 4.1): this previously substituted `NONE` when the read
     * threw, which made an unknown state indistinguishable from a genuinely normal one. Unknown is
     * now reported as unknown.
     */
    val lockTaskState: Int?,
    val requestedFeatures: Int?,
    val effectiveFeatures: Int?,
    /** The observed effective package allowlist, or **null when the read failed**. */
    val effectivePackages: Set<String>?,
    /** Null until settings and schedule inputs are readable. */
    val insideProtectedInterval: Boolean?,
    /** Null means the AlarmManager capability query was unavailable or failed. */
    val exactAlarmCapability: Boolean?,
    val releasePlanSubmittedForCurrentRevision: Boolean,
    val recoveryIncompleteStep: String? = null,
    val userActionRequired: String? = null,
) {
    val deviceOwnerUiState: DeviceOwnerUiState
        get() = when {
            deviceOwner == null -> DeviceOwnerUiState.UNKNOWN
            deviceOwner -> DeviceOwnerUiState.READY
            ownerEverEstablished -> DeviceOwnerUiState.LOST
            else -> DeviceOwnerUiState.REQUIRED
        }

    val managedSessionUiState: ManagedSessionUiState
        get() = when {
            state == ProtectionState.WAITING_FOR_UNLOCK -> ManagedSessionUiState.WAITING_FOR_UNLOCK
            lockTaskState == null -> ManagedSessionUiState.UNKNOWN
            // PINNED is screen pinning, not a DPC locked session (brief section 9.10). An unknown
            // runtime state is NOT reported as active.
            LockTaskRuntimeStates.isRealLocked(lockTaskState) == true ->
                ManagedSessionUiState.ACTIVE
            else -> ManagedSessionUiState.NOT_ACTIVE
        }

    val powerMenuUiState: PowerMenuUiState
        get() = when {
            state == ProtectionState.ARMED_POWER_MENU_RESTRICTED -> PowerMenuUiState.RESTRICTED
            state == ProtectionState.ARMED_POWER_MENU_ALLOWED && effectiveFeatures != null ->
                PowerMenuUiState.ALLOWED
            else -> PowerMenuUiState.NOT_VERIFIED
        }

    companion object {
        const val OWNER_LOST_PREFIX: String = "Device Owner access lost"
    }
}
