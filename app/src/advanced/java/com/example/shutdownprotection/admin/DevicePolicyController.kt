package com.example.shutdownprotection.admin

import com.example.shutdownprotection.data.PolicyBaseline

/**
 * All documented device-policy operations and reads for this app (brief section 8).
 *
 * Nothing else in the app touches `DevicePolicyManager`. Every mutation returns a
 * [PolicyOperationResult] that distinguishes *submitted* from *verified*, and no method
 * ever reports success merely because a setter returned without throwing.
 */
class DevicePolicyController(
    private val gateway: DevicePolicyGateway,
    private val runtimeState: RuntimeLockTaskStateProvider,
    /** The application ID this installation actually runs as. Exposed so baseline validation and
     * recovery use the real identity rather than a second copy that could drift. */
    val applicationId: String,
    private val clockMillis: () -> Long = { System.currentTimeMillis() },
) {

    /** Null means the authority read failed; callers must not treat it as confirmed loss. */
    fun isDeviceOwner(): Boolean? = runCatching { gateway.isDeviceOwner() }.getOrNull()

    fun isLockTaskPermitted(packageName: String): Boolean? =
        runCatching { gateway.isLockTaskPermitted(packageName) }.getOrNull()

    fun readLockTaskFeatures(): Int? = runCatching { gateway.readLockTaskFeatures() }.getOrNull()

    fun readLockTaskPackages(): Set<String>? = runCatching { gateway.readLockTaskPackages() }.getOrNull()

    fun readRuntimeLockTaskState(): Int? = runCatching { runtimeState.lockTaskModeState() }.getOrNull()

    /**
     * Brief section 9.10: only `LOCK_TASK_MODE_LOCKED` counts. `LOCK_TASK_MODE_PINNED` is
     * screen pinning and is failure for this feature.
     */
    fun isRealLockedSession(): Boolean? =
        LockTaskRuntimeStates.isRealLocked(readRuntimeLockTaskState())

    /** Snapshot of everything currently readable. Safe to call at any time. */
    fun readSnapshot(): PolicyReadback = PolicyReadback(
        features = readLockTaskFeaturesOrNull(),
        packages = readLockTaskPackagesOrNull(),
        runtimeLockTaskState = readRuntimeLockTaskStateOrNull(),
    )

    /**
     * Applies the lock-task package allowlist and verifies it by readback.
     *
     * An empty set is a meaningful value here: for the dedicated-device MVP, temporarily
     * removing every app-configured lock-task package is an acceptable exit procedure
     * (brief section 10.5).
     */
    fun applyAllowedPackages(packages: Set<String>): PolicyOperationResult {
        when (isDeviceOwner()) {
            false -> return PolicyOperationResult.NotAuthorized
            null -> return PolicyOperationResult.Rejected(
                IllegalStateException("Device Owner authority could not be verified"),
                readSnapshot(),
            )
            true -> Unit
        }
        val failure = runCatching { gateway.submitLockTaskPackages(packages) }.exceptionOrNull()
        val readback = readSnapshot()
        if (failure != null) return PolicyOperationResult.Rejected(failure, readback)
        return PolicyOperationResult.Applied(
            readback = readback,
            verified = readback.packages == packages,
        )
    }

    /** Applies a feature mask and verifies it by readback. */
    fun applyFeatures(features: Int): PolicyOperationResult {
        when (isDeviceOwner()) {
            false -> return PolicyOperationResult.NotAuthorized
            null -> return PolicyOperationResult.Rejected(
                IllegalStateException("Device Owner authority could not be verified"),
                readSnapshot(),
            )
            true -> Unit
        }
        val failure = runCatching { gateway.submitLockTaskFeatures(features) }.exceptionOrNull()
        val readback = readSnapshot()
        if (failure != null) return PolicyOperationResult.Rejected(failure, readback)
        return PolicyOperationResult.Applied(
            readback = readback,
            verified = readback.features == features,
        )
    }

    /**
     * Reads the device's current lock-task configuration as the pre-session baseline.
     *
     * Callers must capture this BEFORE the first policy mutation of a session. It is never
     * overwritten with the app's own already-modified values on a later resume (brief
     * section 7.6).
     */
    fun captureBaseline(): PolicyBaseline {
        // Both reads are wrapped and a failure is raised as a refusal rather than allowed to escape
        // (independent audit finding): a throwing capture previously propagated out of `arm()` to
        // the ViewModel instead of being handled as a declined activation.
        val packages = readLockTaskPackagesOrNull()
            ?: throw IllegalStateException("lock-task package readback was unavailable")
        val features = readLockTaskFeaturesOrNull()
            ?: throw IllegalStateException("lock-task feature readback was unavailable")
        return PolicyBaseline(
            capturedAtEpochMillis = clockMillis(),
            lockTaskPackages = packages,
            lockTaskFeatures = features,
            applicationId = applicationId,
        )
    }

    // Readback must never crash a caller: a failure here simply means "unknown", which is
    // reported as null rather than as a value that could be mistaken for a verification.
    private fun readLockTaskFeaturesOrNull(): Int? =
        runCatching { gateway.readLockTaskFeatures() }.getOrNull()

    private fun readLockTaskPackagesOrNull(): Set<String>? =
        runCatching { gateway.readLockTaskPackages() }.getOrNull()

    private fun readRuntimeLockTaskStateOrNull(): Int? =
        runCatching { runtimeState.lockTaskModeState() }.getOrNull()
}
