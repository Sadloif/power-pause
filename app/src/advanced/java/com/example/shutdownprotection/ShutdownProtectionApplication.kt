package com.example.shutdownprotection

import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.core.content.ContextCompat
import com.example.shutdownprotection.protection.ProtectionTrigger

/**
 * Application entry point.
 *
 * Deliberately thin: it builds the [AppContainer] and registers the one system event that
 * cannot be manifest-delivered. It reads no credential-protected storage, so it is safe to
 * create before the user unlocks.
 */
class ShutdownProtectionApplication : Application() {

    private val containerDelegate = lazy { AppContainer(this) }
    val container: AppContainer by containerDelegate
    internal val managedContainerInitialized: Boolean get() = containerDelegate.isInitialized()

    private var userUnlockedReceiver: BroadcastReceiver? = null

    /** Replaced only by JVM tests so real receiver callbacks can be executed without a DPC. */
    internal var receiverServicesOverrideForTests: ReceiverServices? = null

    override fun onCreate() {
        super.onCreate()
        if (BuildConfig.MANAGED_TOOLS) {
            container
            registerUserUnlockedReceiver()
        }
    }

    /**
     * `ACTION_USER_UNLOCKED` is not deliverable to manifest-declared receivers, so it is
     * registered dynamically here (brief section 17).
     */
    private fun registerUserUnlockedReceiver() {
        if (userUnlockedReceiver != null) return
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                if (intent.action != Intent.ACTION_USER_UNLOCKED) return
                val pending = goAsync()
                val services = try {
                    receiverServices(this@ShutdownProtectionApplication)
                } catch (_: Throwable) {
                    runCatching { pending.finish() }
                    return
                }
                dispatchReceiverWork(services, pending) { receiverServices ->
                    receiverServices.reconcile(ProtectionTrigger.USER_UNLOCKED)
                }
            }
        }
        runCatching {
            ContextCompat.registerReceiver(
                this,
                receiver,
                IntentFilter(Intent.ACTION_USER_UNLOCKED),
                ContextCompat.RECEIVER_NOT_EXPORTED,
            )
            userUnlockedReceiver = receiver
        }
    }

    companion object {
        /**
         * Resolves the container from any context, including the device-protected context
         * used before unlock. A directBootAware component's `applicationContext` is still
         * this Application instance.
         */
        fun container(context: Context): AppContainer =
            (context.applicationContext as ShutdownProtectionApplication).container

        internal fun receiverServices(context: Context): ReceiverServices {
            val application = context.applicationContext as ShutdownProtectionApplication
            return application.receiverServicesOverrideForTests
                ?: AppContainerReceiverServices(application.container)
        }
    }
}
