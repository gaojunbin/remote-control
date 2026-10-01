package com.junbingao.remotecontrol.android.screens.alerts

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.junbingao.remotecontrol.android.push.PushAuthorization
import com.junbingao.remotecontrol.android.strings.L10n
import com.junbingao.remotecontrol.core.state.GatewayAPI
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.launch

/**
 * The system side of notifications, so the controller can be driven without Android's
 * notification manager in a picture or a check.
 *
 * The iPhone's platform also carries an APNs token, its environment and the registration with
 * Apple. Android has no push channel yet (`docs/DESIGN.md` § "The Android app"), so there is
 * nothing for those to stand for, and asking is the screen's: Android shows its prompt from the
 * activity that asks ([PushController.requestAuthorizationIfNeeded]).
 */
interface NotificationPlatform {
    val supported: Boolean

    suspend fun authorization(): PushAuthorization

    /** Takes every notification the app posted down: the switch went off, or the account left. */
    fun unregister()

    fun openSettings()
}

/**
 * Where the reconciliation below got to, in one value.
 *
 * The words are built from it at read time rather than stored: a string `L10n.string` produced and
 * a store kept would go on saying what it said in the language it was first built in, long after
 * the reader changed it. The iPhone's three registration states (registering with Apple, on, and
 * refused by the gateway) have no counterpart until Android has a push channel.
 */
enum class PushStatus {
    off, unsupported, denied, waitingForPermission, appOnly;

    val text: String
        get() = when (this) {
            off -> L10n.string("Off")
            unsupported -> L10n.string("Not available on this device")
            denied -> L10n.string("Blocked in iOS Settings")
            waitingForPermission -> L10n.string("Waiting for permission")
            appOnly -> L10n.string("On, in this app only")
        }
}

/**
 * Reconciles one preference and one system authorization.
 *
 * Work is serialized through a single chained job, so a slow answer from the system can never land
 * after the user turned notifications back off. What the switch turns on is the app's own
 * notifications, posted from the live connection (`docs/DESIGN.md` § "The Android app"), so there
 * is no token to register and nothing to hand a gateway.
 */
class PushController(private val platform: NotificationPlatform, private val tasks: CoroutineScope) {
    /** The app's own, on the main thread for the life of the process, as the iPhone's lives on the main actor. */
    constructor(context: Context) : this(SystemNotifications(context.applicationContext), MainScope())

    var authorization: PushAuthorization by mutableStateOf(PushAuthorization.notDetermined)
        private set
    var status: PushStatus by mutableStateOf(PushStatus.off)
        private set
    val statusText: String get() = status.text
    var errorMessage: String? by mutableStateOf(null)
        private set

    private var enabled = false
    private var operation: Job? = null

    val isSupported: Boolean get() = platform.supported

    /** Notifications can only be re-enabled in Android Settings once denied. */
    fun openSystemSettings() = platform.openSettings()

    /**
     * The account's gateway and the switch, on every sign-in; the demo passes no gateway. Android
     * registers nothing with a gateway yet, so the gateway is not kept: the call is the moment to
     * read the system's answer again.
     */
    fun attach(api: GatewayAPI?, enabled: Boolean) {
        this.enabled = enabled
        reconcile()
    }

    fun setEnabled(value: Boolean) {
        enabled = value
        reconcile()
    }

    /**
     * Ask the system, then reconcile. Called from the settings switch, with [ask] showing Android's
     * own prompt: the question is put from the activity on screen, so the screen supplies it.
     */
    fun requestAuthorizationIfNeeded(ask: suspend () -> Unit) {
        chain {
            try {
                if (platform.authorization() == PushAuthorization.notDetermined) ask()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                errorMessage = error.localizedMessage
            }
            apply()
        }
    }

    /** Signing out: an account that has left keeps nothing on the lock screen. */
    fun detach() {
        chain {
            platform.unregister()
            status = PushStatus.off
        }
    }

    private fun reconcile() {
        chain { apply() }
    }

    private suspend fun apply() {
        if (!platform.supported) {
            authorization = PushAuthorization.unsupported
            status = PushStatus.unsupported
            return
        }
        authorization = platform.authorization()
        if (!enabled) {
            platform.unregister()
            status = PushStatus.off
            return
        }
        status = when (authorization) {
            PushAuthorization.denied -> PushStatus.denied
            PushAuthorization.notDetermined -> PushStatus.waitingForPermission
            // With no push channel there is nobody to hand a device token to. The switch still
            // holds: the app's own notifications need no gateway.
            else -> PushStatus.appOnly
        }
    }

    /** Serialize every step: a reconciliation must never overtake a later one. */
    private fun chain(work: suspend () -> Unit) {
        val previous = operation
        operation = tasks.launch {
            previous?.join()
            work()
        }
    }
}
