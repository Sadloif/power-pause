package com.example.shutdownprotection

import android.app.Activity
import android.app.KeyguardManager
import android.content.Context
import android.os.Looper
import android.os.UserManager
import com.example.shutdownprotection.admin.AndroidDevicePolicyGateway
import com.example.shutdownprotection.admin.AndroidRuntimeLockTaskStateProvider
import com.example.shutdownprotection.admin.DevicePolicyController
import com.example.shutdownprotection.data.DataStoreDiagnosticsRepository
import com.example.shutdownprotection.data.DataStoreSettingsRepository
import com.example.shutdownprotection.data.DiagnosticsRepository
import com.example.shutdownprotection.data.SettingsDataStores
import com.example.shutdownprotection.data.SettingsRepository
import com.example.shutdownprotection.data.UnlockGatedDiagnosticsRepository
import com.example.shutdownprotection.protection.EnvironmentStateProvider
import com.example.shutdownprotection.protection.LockTaskSessionController
import com.example.shutdownprotection.protection.ProtectionCoordinator
import com.example.shutdownprotection.protection.RecoveryManager
import com.example.shutdownprotection.protection.RestrictionInhibitor
import com.example.shutdownprotection.scheduling.ScheduleCalculator
import com.example.shutdownprotection.scheduling.ScheduleManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import java.lang.ref.WeakReference
import java.time.Clock

/**
 * Process-wide wiring for the MVP.
 *
 * Every component stays in the same process, and exactly one DataStore instance exists per
 * file (brief sections 7.7 and 12).
 *
 * **Direct Boot:** construction must not read credential-protected storage. Creating a
 * DataStore is lazy - the backing file is not touched until the first read or write - so
 * building this container before unlock is safe.
 */
class AppContainer(context: Context) {

    val appContext: Context = context.applicationContext

    val applicationId: String = appContext.packageName

    val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val settingsRepository: SettingsRepository = DataStoreSettingsRepository(
        SettingsDataStores.deviceProtected(appContext),
    )

    val environment: EnvironmentStateProvider = AndroidEnvironmentStateProvider(appContext)

    /**
     * Diagnostics live in credential-protected storage, so they are gated on the user being
     * unlocked: the `LOCKED_BOOT_COMPLETED` path must not touch credential-protected storage
     * (brief section 7.8). See [UnlockGatedDiagnosticsRepository].
     */
    val diagnostics: DiagnosticsRepository = UnlockGatedDiagnosticsRepository(
        delegate = DataStoreDiagnosticsRepository(
            SettingsDataStores.credentialProtected(appContext),
        ),
        isUserUnlocked = { environment.isUserUnlocked() },
    )

    val policyController: DevicePolicyController = DevicePolicyController(
        gateway = AndroidDevicePolicyGateway(appContext),
        runtimeState = AndroidRuntimeLockTaskStateProvider(appContext),
        applicationId = applicationId,
    )

    val scheduleManager: ScheduleManager = ScheduleManager(
        context = appContext,
        applicationId = applicationId,
    )

    val scheduleCalculator: ScheduleCalculator = ScheduleCalculator(
        clock = Clock.systemUTC(),
        // The schedule follows the device's *current* zone. Reading systemDefault() on every
        // call is deliberate: caching it would pin the schedule to the zone in force when
        // the container was built (brief section 13).
        zoneProvider = { java.time.ZoneId.systemDefault() },
    )

    val inhibitor: RestrictionInhibitor = RestrictionInhibitor()

    val lockTaskSession: ActivityLockTaskSessionController = ActivityLockTaskSessionController()

    val recoveryManager: RecoveryManager = RecoveryManager(
        settingsRepository = settingsRepository,
        diagnostics = diagnostics,
        policyController = policyController,
        scheduleManager = scheduleManager,
        lockTaskSession = lockTaskSession,
        inhibitor = inhibitor,
    )

    val coordinator: ProtectionCoordinator = ProtectionCoordinator(
        applicationId = applicationId,
        settingsRepository = settingsRepository,
        diagnostics = diagnostics,
        policyController = policyController,
        scheduleCalculator = scheduleCalculator,
        scheduleManager = scheduleManager,
        recoveryManager = recoveryManager,
        lockTaskSession = lockTaskSession,
        environment = environment,
        inhibitor = inhibitor,
    )

    /**
     * Starts a receiver job with a single deadline covering its work and error diagnostics.
     * The independent watchdog finishes the pending result even if work gets stuck in a
     * non-cancellable suspension or finally block.
     */
    fun launchReceiverWork(
        finishPendingResult: () -> Unit,
        block: suspend () -> Unit,
    ) {
        launchBoundedReceiverWork(
            scope = scope,
            boundedMillis = RECEIVER_WORK_BUDGET_MILLIS,
            finish = finishPendingResult,
            reportFailure = { failure ->
                diagnostics.record(
                    "RECEIVER_WORK_FAILED",
                    -1L,
                    "receiver work failed: ${failure::class.java.simpleName}: ${failure.message}",
                )
            },
            block = block,
        )
    }

    companion object {
        /**
         * Eight seconds leaves a two-second margin inside the ordinary ten-second broadcast ANR
         * window for receiver dispatch and PendingResult.finish(). Coordinator operations must
         * have a shorter internal budget and release conservatively on cancellation.
         */
        const val RECEIVER_WORK_BUDGET_MILLIS: Long = 8_000L
    }

    /**
     * False until physical power-menu behavior has been observed on *this* device and build.
     *
     * Deliberately not derived from a successful build, a passing unit test, or an emulator
     * result: the brief forbids presenting API observations as a physical test result.
     * Flipping this requires recorded manual evidence in docs/TEST_RESULTS.md.
     */
    @Volatile
    var deviceBehaviorValidated: Boolean = false
}

/** Real unlock / keyguard facts. */
class AndroidEnvironmentStateProvider(context: Context) : EnvironmentStateProvider {

    private val appContext = context.applicationContext

    override fun isUserUnlocked(): Boolean? = runCatching {
        appContext.getSystemService(UserManager::class.java)?.isUserUnlocked
    }.getOrNull()

    override fun isKeyguardLocked(): Boolean? = runCatching {
        appContext.getSystemService(KeyguardManager::class.java)?.isKeyguardLocked
    }.getOrNull()

    override fun isDeviceSecure(): Boolean? = runCatching {
        appContext.getSystemService(KeyguardManager::class.java)?.isDeviceSecure
    }.getOrNull()
}

/**
 * The activity-owned Lock Task entry seam.
 *
 * `startLockTask()` is an `Activity` API and must run on the main thread. Rather than
 * silently marshalling across threads - which would make the "did it actually start?"
 * verification meaningless - this controller refuses the request when it is not on the main
 * thread. [ProtectionCoordinator.arm] is therefore only ever invoked from the ViewModel,
 * whose scope is the main dispatcher.
 */
class ActivityLockTaskSessionController : LockTaskSessionController {

    private var activityRef: WeakReference<Activity>? = null
    private var activityResumed: Boolean = false

    fun attach(activity: Activity) {
        activityRef = WeakReference(activity)
        activityResumed = true
    }

    /** Stops new entry as soon as the owning activity begins to leave the foreground. */
    fun markPaused(activity: Activity) {
        if (activityRef?.get() === activity) activityResumed = false
    }

    fun detach(activity: Activity) {
        if (activityRef?.get() === activity) {
            activityResumed = false
            activityRef = null
        }
    }

    override fun isEntryAvailable(): Boolean {
        val activity = activityRef?.get() ?: return false
        return activityResumed && isLive(activity) && isOnMainThread()
    }

    override fun requestStartLockTask(): Boolean {
        val activity = activityRef?.get() ?: return false
        // Recheck at dispatch time: a queued request may run after onPause.
        if (!activityResumed || !isLive(activity) || !isOnMainThread()) return false
        return runCatching {
            activity.startLockTask()
            true
        }.getOrDefault(false)
    }

    override fun requestStopLockTask(): Boolean {
        val activity = activityRef?.get() ?: return false
        // Paused activities remain attached until destruction so the task-owning activity can
        // make a best-effort stop request during release.
        if (activity.isDestroyed || !isOnMainThread()) return false
        return runCatching {
            activity.stopLockTask()
            true
        }.getOrDefault(false)
    }

    private fun isLive(activity: Activity): Boolean =
        !activity.isFinishing && !activity.isDestroyed

    private fun isOnMainThread(): Boolean = Looper.myLooper() == Looper.getMainLooper()
}
