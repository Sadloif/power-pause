package com.example.shutdownprotection.admin

import android.app.ActivityManager
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context

/**
 * The lock-task feature masks this feature uses (brief section 8).
 *
 * Values are built from the platform constants rather than written as bare literals, and
 * [EXPECTED_PROTECTED] / [EXPECTED_ALLOWED] pin the numeric values that were verified
 * against platform 36 in `docs/API_FACTS.md`. A unit test asserts the equality, so a
 * future platform change cannot silently move the mask.
 */
object LockTaskMasks {

    /**
     * The restrictive mask. Applied only after every protection prerequisite passes.
     * Global actions (the Power Off / Restart dialog) are absent from this mask.
     */
    val PROTECTED: Int =
        DevicePolicyManager.LOCK_TASK_FEATURE_SYSTEM_INFO or
            DevicePolicyManager.LOCK_TASK_FEATURE_NOTIFICATIONS or
            DevicePolicyManager.LOCK_TASK_FEATURE_HOME or
            DevicePolicyManager.LOCK_TASK_FEATURE_OVERVIEW or
            DevicePolicyManager.LOCK_TASK_FEATURE_KEYGUARD

    /**
     * The permissive mask used while armed outside protected hours, and during arm
     * rollback. Global actions are permitted here.
     *
     * This is NOT the device's original configuration - full disarm restores the captured
     * baseline instead (brief section 8).
     */
    val ALLOWED: Int = PROTECTED or DevicePolicyManager.LOCK_TASK_FEATURE_GLOBAL_ACTIONS

    /** Verified values: SYSTEM_INFO 1 | NOTIFICATIONS 2 | HOME 4 | OVERVIEW 8 | KEYGUARD 32. */
    const val EXPECTED_PROTECTED: Int = 47

    /** Verified value: PROTECTED | GLOBAL_ACTIONS(16). */
    const val EXPECTED_ALLOWED: Int = 63

    /**
     * Never set by this app. Blocking activity starts inside a locked task changes launch
     * semantics well beyond this feature's scope.
     */
    const val BLOCK_ACTIVITY_START_IN_TASK: Int =
        DevicePolicyManager.LOCK_TASK_FEATURE_BLOCK_ACTIVITY_START_IN_TASK

    /** Human-readable mask for the diagnostics screen and log. */
    fun describe(mask: Int): String {
        if (mask == 0) return "0 (LOCK_TASK_FEATURE_NONE)"
        val parts = mutableListOf<String>()
        if (mask and DevicePolicyManager.LOCK_TASK_FEATURE_SYSTEM_INFO != 0) parts += "SYSTEM_INFO"
        if (mask and DevicePolicyManager.LOCK_TASK_FEATURE_NOTIFICATIONS != 0) parts += "NOTIFICATIONS"
        if (mask and DevicePolicyManager.LOCK_TASK_FEATURE_HOME != 0) parts += "HOME"
        if (mask and DevicePolicyManager.LOCK_TASK_FEATURE_OVERVIEW != 0) parts += "OVERVIEW"
        if (mask and DevicePolicyManager.LOCK_TASK_FEATURE_GLOBAL_ACTIONS != 0) parts += "GLOBAL_ACTIONS"
        if (mask and DevicePolicyManager.LOCK_TASK_FEATURE_KEYGUARD != 0) parts += "KEYGUARD"
        if (mask and DevicePolicyManager.LOCK_TASK_FEATURE_BLOCK_ACTIVITY_START_IN_TASK != 0) {
            parts += "BLOCK_ACTIVITY_START_IN_TASK"
        }
        return "$mask (${parts.joinToString(" | ")})"
    }
}

/** Runtime lock-task states, with the distinction the feature depends on made explicit. */
object LockTaskRuntimeStates {

    const val NONE: Int = ActivityManager.LOCK_TASK_MODE_NONE
    const val LOCKED: Int = ActivityManager.LOCK_TASK_MODE_LOCKED
    const val PINNED: Int = ActivityManager.LOCK_TASK_MODE_PINNED

    /**
     * Brief section 9.10: `LOCK_TASK_MODE_PINNED` is screen pinning, not a DPC locked
     * task, and counts as failure for this feature.
     */
    fun isRealLocked(state: Int?): Boolean? = state?.let { it == LOCKED }

    fun describe(state: Int?): String = when (state) {
        null -> "Unknown"
        NONE -> "LOCK_TASK_MODE_NONE (0)"
        LOCKED -> "LOCK_TASK_MODE_LOCKED (1)"
        PINNED -> "LOCK_TASK_MODE_PINNED (2)"
        else -> "UNKNOWN ($state)"
    }
}

/**
 * Everything read back from the platform after a policy attempt. Readback alone never
 * proves the physical menu is absent - only manual device evidence does (brief section 18).
 */
data class PolicyReadback(
    val features: Int?,
    val packages: Set<String>?,
    val runtimeLockTaskState: Int?,
)

/**
 * Outcome of one policy attempt.
 *
 * The brief (section 8) forbids reporting unconditional success because a setter returned
 * without throwing. The four distinguishable things are:
 *
 *  - **request submitted** - [submitted]
 *  - **current readback** - [readback]
 *  - **asynchronous policy result** - arrives later through `PolicyUpdateReceiver`
 *  - **runtime state** - [PolicyReadback.runtimeLockTaskState]
 */
sealed interface PolicyOperationResult {

    /** True when the platform call returned without throwing. */
    val submitted: Boolean

    /** The readback taken immediately after the attempt, when one was possible. */
    val readback: PolicyReadback?

    /** True only when the readback confirms the requested value is in effect. */
    val verified: Boolean

    val failure: Throwable?

    /** The setter returned and readback already shows the requested value. */
    data class Applied(
        override val readback: PolicyReadback,
        override val verified: Boolean,
    ) : PolicyOperationResult {
        override val submitted: Boolean get() = true
        override val failure: Throwable? get() = null
    }

    /** The setter threw, or authority was absent at call time. */
    data class Rejected(
        override val failure: Throwable?,
        override val readback: PolicyReadback? = null,
    ) : PolicyOperationResult {
        override val submitted: Boolean get() = false
        override val verified: Boolean get() = false
    }

    /** No Device Owner authority: nothing was attempted. */
    data object NotAuthorized : PolicyOperationResult {
        override val submitted: Boolean get() = false
        override val readback: PolicyReadback? get() = null
        override val verified: Boolean get() = false
        override val failure: Throwable? get() = null
    }
}

/**
 * The narrow seam over `DevicePolicyManager`. Exists so the controller's readback and
 * failure handling can be unit-tested without an Android framework, and so nothing else in
 * the app touches `DevicePolicyManager` directly (brief section 5).
 */
interface DevicePolicyGateway {
    /** Null means the service/read was unavailable; false is a confirmed negative. */
    fun isDeviceOwner(): Boolean?
    fun isLockTaskPermitted(packageName: String): Boolean?
    fun readLockTaskPackages(): Set<String>?
    fun readLockTaskFeatures(): Int?
    fun submitLockTaskPackages(packages: Set<String>)
    fun submitLockTaskFeatures(features: Int)
}

/** Runtime lock-task state seam, kept separate from policy state on purpose. */
interface RuntimeLockTaskStateProvider {
    /** Null means the runtime state could not be read. */
    fun lockTaskModeState(): Int?
}

/** Real gateway over the platform. Every method is defensive about a null service. */
class AndroidDevicePolicyGateway(context: Context) : DevicePolicyGateway {

    private val appContext: Context = context.applicationContext

    private fun devicePolicyManager(): DevicePolicyManager? =
        appContext.getSystemService(DevicePolicyManager::class.java)

    private val adminComponent: ComponentName
        get() = ComponentName(appContext, ShutdownProtectionAdminReceiver::class.java)

    override fun isDeviceOwner(): Boolean? = runCatching {
        devicePolicyManager()?.isDeviceOwnerApp(appContext.packageName)
    }.getOrNull()

    override fun isLockTaskPermitted(packageName: String): Boolean? = runCatching {
        devicePolicyManager()?.isLockTaskPermitted(packageName)
    }.getOrNull()

    override fun readLockTaskPackages(): Set<String>? = runCatching {
        devicePolicyManager()?.getLockTaskPackages(adminComponent)?.toSet()
    }.getOrNull()

    override fun readLockTaskFeatures(): Int? = runCatching {
        devicePolicyManager()?.getLockTaskFeatures(adminComponent)
    }.getOrNull()

    override fun submitLockTaskPackages(packages: Set<String>) {
        // The only one of the nine methods with a declared throws clause (docs/API_FACTS.md).
        val manager = runCatching { devicePolicyManager() }.getOrNull()
            ?: throw IllegalStateException("DevicePolicyManager service is unavailable")
        manager.setLockTaskPackages(adminComponent, packages.toTypedArray())
    }

    override fun submitLockTaskFeatures(features: Int) {
        val manager = runCatching { devicePolicyManager() }.getOrNull()
            ?: throw IllegalStateException("DevicePolicyManager service is unavailable")
        manager.setLockTaskFeatures(adminComponent, features)
    }
}

/** Real runtime-state provider over `ActivityManager`. */
class AndroidRuntimeLockTaskStateProvider(context: Context) : RuntimeLockTaskStateProvider {

    private val appContext = context.applicationContext

    override fun lockTaskModeState(): Int? = runCatching {
        appContext.getSystemService(ActivityManager::class.java)?.lockTaskModeState
    }.getOrNull()
}
