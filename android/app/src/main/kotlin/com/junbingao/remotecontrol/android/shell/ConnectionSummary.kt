package com.junbingao.remotecontrol.android.shell

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import com.junbingao.remotecontrol.android.design.Button
import com.junbingao.remotecontrol.android.design.ChipButtonStyle
import com.junbingao.remotecontrol.android.design.Text
import com.junbingao.remotecontrol.android.design.Theme
import com.junbingao.remotecontrol.android.design.barBackground
import com.junbingao.remotecontrol.android.strings.L10n
import com.junbingao.remotecontrol.android.system.ActivityIndicator
import com.junbingao.remotecontrol.android.system.IndicatorSize
import com.junbingao.remotecontrol.core.protocol.RemoteProtocol
import com.junbingao.remotecontrol.core.state.ConnectionPhase

/**
 * A one-line description of the connection, in the vocabulary the product promises: each state
 * is distinguishable and none of them is a guess. It sits in a screen's top inset
 * (`NavigationScreen(top = …)`), so it carries a bar of its own and scrolled rows pass underneath
 * it; a screen with nothing to say draws no strip at all.
 */
@Composable
fun ConnectionSummary(phase: ConnectionPhase, isDemo: Boolean, reconnect: (() -> Unit)? = null) {
    val text = text(phase, isDemo) ?: return
    Row(
        Modifier
            .fillMaxWidth()
            .barBackground()
            .padding(horizontal = Theme.Space.page, vertical = Theme.Space.tight)
            .testTag("connection.status"),
        horizontalArrangement = Arrangement.spacedBy(Theme.Space.tight),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (showsProgress(phase)) ActivityIndicator(size = IndicatorSize.mini)
        Text(text, style = Theme.Text.meta, color = Theme.inkSecondary)
        if (phase == ConnectionPhase.Superseded && reconnect != null) {
            Spacer(Modifier.weight(1f).widthIn(min = Theme.Space.tight))
            Button(onClick = reconnect, Modifier.testTag("connection.reconnect"), style = ChipButtonStyle) {
                Text(L10n.string("Reconnect"))
            }
        }
    }
}

private fun showsProgress(phase: ConnectionPhase): Boolean =
    phase == ConnectionPhase.Connecting || phase == ConnectionPhase.Syncing || phase == ConnectionPhase.Reconnecting

private fun text(phase: ConnectionPhase, isDemo: Boolean): String? {
    if (isDemo) return L10n.string("Demo · nothing leaves this device")
    return when (phase) {
        ConnectionPhase.SignedOut -> null
        ConnectionPhase.Connecting -> L10n.string("Connecting")
        ConnectionPhase.Syncing -> L10n.string("Syncing")
        ConnectionPhase.Connected -> null
        ConnectionPhase.Reconnecting -> L10n.string("Reconnecting")
        ConnectionPhase.Expired -> L10n.string("Your session expired. Sign in again.")
        ConnectionPhase.Forbidden -> L10n.string("This gateway refused the connection.")
        ConnectionPhase.Superseded -> L10n.string("Another app took over this connection.")
        is ConnectionPhase.Incompatible -> L10n.string(
            "The gateway speaks protocol %lld; this app speaks %lld. Update both.", phase.gatewayVersion, RemoteProtocol.version)
    }
}
