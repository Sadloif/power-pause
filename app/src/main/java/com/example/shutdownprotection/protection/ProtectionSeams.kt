package com.example.shutdownprotection.protection

/**
 * Enters and leaves the real Lock Task session.
 *
 * `Activity.startLockTask()` must be called from the activity that owns the task, and a
 * newly created activity cannot be assumed to be able to call `stopLockTask()` for the
 * original task (brief section 9). This seam therefore models the activity-owned entry
 * flow, while the primary *exit* route remains DPC allowlist removal in
 * [RecoveryManager].
 */
interface LockTaskSessionController {

    /** True when an eligible foreground activity is currently attached. */
    fun isEntryAvailable(): Boolean

    /**
     * Calls `startLockTask()` on the attached foreground activity.
     * Returns false when no eligible activity is attached.
     */
    fun requestStartLockTask(): Boolean

    /**
     * Best-effort `stopLockTask()` from the task-owning activity. Documented task ownership
     * only; never the sole exit route.
     */
    fun requestStopLockTask(): Boolean
}

/** Unlock / keyguard facts the coordinator needs before attempting entry (brief section 9). */
interface EnvironmentStateProvider {
    /** Null means the corresponding Android service was absent or the read failed. */
    fun isUserUnlocked(): Boolean?
    fun isKeyguardLocked(): Boolean?
    fun isDeviceSecure(): Boolean?
}

/**
 * Process-level inhibition of new restriction.
 *
 * Set when durable disable could not be written during recovery (brief section 10): the
 * brief forbids claiming that a persistence failure was repaired by flipping only an
 * in-memory boolean, so this flag is a *safety brake*, not a substitute for persistence -
 * durable cleanup stays unverified and is reported as such.
 */
class RestrictionInhibitor {

    /** A lease prevents an older recovery completion from clearing a newer failure. */
    data class Token internal constructor(val generation: Long)

    private var generation: Long = 0L

    @Volatile
    var isInhibited: Boolean = false
        private set

    @Volatile
    var reason: String? = null
        private set

    @Synchronized
    fun inhibit(reason: String): Token {
        generation += 1L
        this.reason = reason
        isInhibited = true
        return Token(generation)
    }

    /**
     * Clears only the exact inhibition lease that verified cleanup owns. If a later failure
     * engaged the brake while cleanup was suspended, that newer lease remains in force.
     */
    @Synchronized
    fun clearIfCurrent(token: Token): Boolean {
        if (!isInhibited || token.generation != generation) return false
        reason = null
        isInhibited = false
        return true
    }
}
