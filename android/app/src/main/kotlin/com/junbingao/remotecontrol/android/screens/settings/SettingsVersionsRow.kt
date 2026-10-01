package com.junbingao.remotecontrol.android.screens.settings

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Constraints
import com.junbingao.remotecontrol.android.design.BorderlessButtonStyle
import com.junbingao.remotecontrol.android.design.Button
import com.junbingao.remotecontrol.android.design.Text
import com.junbingao.remotecontrol.android.design.Theme
import com.junbingao.remotecontrol.android.strings.L10n
import com.junbingao.remotecontrol.core.protocol.RemoteProtocol
import com.junbingao.remotecontrol.core.state.AppBuild
import com.junbingao.remotecontrol.core.state.InterfaceLanguage
import com.junbingao.remotecontrol.core.state.VersionsLine

/**
 * The line that closes the screen: this build, the gateway's, and the protocol both speak, with
 * Diagnostics beside it.
 *
 * `docs/DESIGN.md` § "The Settings screen": one caption in the tertiary ink, centred, on the canvas
 * rather than in a group. There is no About group; a version is something to read once, not a
 * setting.
 */
@Composable
fun SettingsVersionsRow(
    gatewayVersion: String,
    /**
     * Held so a change of interface language rebuilds the line where it stands: `Gateway` and
     * `Protocol` are words, and the numbers are not.
     */
    language: InterfaceLanguage,
    diagnostics: () -> Unit,
) {
    // Side by side where they fit, stacked where the phone is too narrow for the line and the
    // button on one row.
    SideBySideOrStacked(Modifier.fillMaxWidth().padding(vertical = Theme.Space.small)) {
        Text(
            VersionsLine.text(app = AppBuild.version, gateway = gatewayVersion, protocolVersion = RemoteProtocol.version),
            Modifier.testTag("settings.versions"),
            style = Theme.Text.caption,
            color = Theme.inkTertiary,
            alignment = TextAlign.Center,
        )
        Button(onClick = diagnostics, Modifier.testTag("settings.diagnostics"), style = BorderlessButtonStyle) {
            Text(L10n.string("Diagnostics"), style = Theme.Text.caption)
        }
    }
}

/**
 * SwiftUI's `ViewThatFits(in: .horizontal)` over the two arrangements the row offers: the line and
 * the button in a row, ten points apart, when both fit at their own widths; otherwise one over the
 * other, six apart, the line wrapping where it must. Either way the group is centred.
 */
@Composable
private fun SideBySideOrStacked(modifier: Modifier, content: @Composable () -> Unit) {
    Layout(content, modifier) { measurables, constraints ->
        val (line, button) = measurables
        val across = Theme.Space.small.roundToPx()
        val down = Theme.Space.tight.roundToPx()
        val loose = constraints.copy(minWidth = 0, minHeight = 0)
        val wide = line.maxIntrinsicWidth(Constraints.Infinity) + across + button.maxIntrinsicWidth(Constraints.Infinity)
        if (wide <= constraints.maxWidth) {
            val first = line.measure(loose)
            val second = button.measure(loose)
            val height = maxOf(first.height, second.height)
            layout(constraints.maxWidth, height) {
                val start = (constraints.maxWidth - first.width - across - second.width) / 2
                first.place(start, (height - first.height) / 2)
                second.place(start + first.width + across, (height - second.height) / 2)
            }
        } else {
            val first = line.measure(loose)
            val second = button.measure(loose)
            layout(constraints.maxWidth, first.height + down + second.height) {
                first.place((constraints.maxWidth - first.width) / 2, 0)
                second.place((constraints.maxWidth - second.width) / 2, first.height + down)
            }
        }
    }
}
