package com.junbingao.remotecontrol.win.settings

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import com.junbingao.remotecontrol.core.protocol.RemoteProtocol
import com.junbingao.remotecontrol.core.state.AppBuild
import com.junbingao.remotecontrol.core.state.ConnectionStore
import com.junbingao.remotecontrol.win.app.LocalAppModel
import com.junbingao.remotecontrol.win.design.FontSize
import com.junbingao.remotecontrol.win.design.Palette
import com.junbingao.remotecontrol.win.design.Space
import com.junbingao.remotecontrol.win.design.Text
import com.junbingao.remotecontrol.win.design.css
import com.junbingao.remotecontrol.win.strings.S

/**
 * `VersionsLine.tsx`: the caption that closes the screen, and not a group. The Windows app is
 * installed apart from the gateway that serves the web, so the line starts with its own version,
 * as the Mac app's does (`docs/DESIGN.md` § "The Windows app" → **Windows' own words**). Every
 * number is read from the running code: this build, the gateway's `hello` — or
 * `GET /api/config` before one has landed — and the protocol this build speaks, which a gateway
 * speaking another would have refused.
 */
@Composable
fun VersionsLine() {
    val connection = LocalAppModel.current.connection
    Text(
        S.win.versions(AppBuild.version, gatewayVersion(connection), "v${RemoteProtocol.version}"),
        css(FontSize.fs12, lineHeight = 1.5f),
        Modifier.padding(top = Space.sp8).fillMaxWidth(),
        color = Palette.inkTertiary,
        textAlign = TextAlign.Center,
    )
}

private fun gatewayVersion(connection: ConnectionStore): String {
    if (connection.gatewayVersion.isNotEmpty()) return connection.gatewayVersion
    return connection.config.version.ifEmpty { "—" }
}
