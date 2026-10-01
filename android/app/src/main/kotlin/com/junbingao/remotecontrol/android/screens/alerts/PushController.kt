package com.junbingao.remotecontrol.android.screens.alerts

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.junbingao.remotecontrol.android.push.NotificationAuthorization
import com.junbingao.remotecontrol.android.push.PushAuthorization
import com.junbingao.remotecontrol.core.state.GatewayAPI

/**
 * Placeholder for `android-settings`: the iPhone's `PushController`, which reconciles the
 * Notifications switch with the system's permission. On Android it reconciles local
 * notifications only (`docs/DESIGN.md` § "The Android app": no push channel yet), so there is no
 * token and no gateway registration.
 *
 * The model calls [attach], [detach] and reads [authorization]; the port replaces the bodies and
 * adds what Settings reads (the status words, enabling, asking), keeping these four.
 */
class PushController(private val context: Context) {
    var authorization: PushAuthorization by mutableStateOf(PushAuthorization.notDetermined)
        private set

    /** The account's gateway and the switch, whenever there is an account to reconcile for; the demo passes no gateway. */
    fun attach(api: GatewayAPI?, enabled: Boolean) {
        authorization = NotificationAuthorization.status(context)
    }

    /** Signing out: nothing is reconciled for an account that has left. */
    fun detach() {
        authorization = NotificationAuthorization.status(context)
    }
}
