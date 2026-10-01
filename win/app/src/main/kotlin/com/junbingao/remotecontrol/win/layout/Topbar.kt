package com.junbingao.remotecontrol.win.layout

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.junbingao.remotecontrol.win.app.LocalLayoutClass
import com.junbingao.remotecontrol.win.app.LocalShellState
import com.junbingao.remotecontrol.win.design.FontSize
import com.junbingao.remotecontrol.win.design.HStack
import com.junbingao.remotecontrol.win.design.Help
import com.junbingao.remotecontrol.win.design.LayoutSize
import com.junbingao.remotecontrol.win.design.Mark
import com.junbingao.remotecontrol.win.design.Palette
import com.junbingao.remotecontrol.win.design.Space
import com.junbingao.remotecontrol.win.design.Spinner
import com.junbingao.remotecontrol.win.design.Text
import com.junbingao.remotecontrol.win.design.css
import com.junbingao.remotecontrol.win.shared.Identity
import com.junbingao.remotecontrol.win.strings.S

/**
 * `.topbar`: the brand, the three tabs, the reconnecting chip, the gateway's host in mono and the
 * initials, on a 60 px strip of 86 % white over a `--line` rule, across the top of the page below
 * the system title bar.
 */
@Composable
fun Topbar() {
    val shell = LocalShellState.current
    val layout = LocalLayoutClass.current
    val compact = layout.maxWidth760
    val padding = if (layout.maxWidth420) Space.sp3 else if (compact) Space.sp4 else Space.sp6
    // The page scrolls under 86 % white; the web also blurs what passes beneath
    // (`backdrop-filter`), which the Mac leaves out and so does this app.
    Column(Modifier.fillMaxWidth().background(Color.White.copy(alpha = 0.86f))) {
        Box(Modifier.fillMaxWidth().height(LayoutSize.headerH), contentAlignment = Alignment.Center) {
            Row(
                Modifier
                    .widthIn(max = LayoutSize.contentMax)
                    .fillMaxWidth()
                    .padding(start = leadingPadding(padding), end = padding),
                horizontalArrangement = Arrangement.spacedBy(if (compact) Space.sp2 else Space.sp5),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Brand(compact)
                TopbarTabs(compact, tight = layout.maxWidth420, modifier = if (compact) Modifier.weight(1f) else Modifier)
                if (!compact) Spacer(Modifier.weight(1f))
                Trailing(compact, shell.connectionIsOpen, shell.origin, shell.username)
            }
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(Palette.line))
    }
}

/** `--header-h` and the rule under it. */
val TopbarHeight: Dp = LayoutSize.headerH + 1.dp

/** The Mac starts the brand after its traffic lights; there are none here, so the padding is the web's. */
@Composable
private fun leadingPadding(padding: Dp): Dp = maxOf(padding, LocalTrafficLightInset.current)

@Composable
private fun Brand(compact: Boolean) {
    HStack(spacing = Space.sp2) {
        Mark()
        // At 760 and narrower the mark stays and the wordmark goes.
        if (!compact) {
            Text(S.productName, css(FontSize.fs14, weight = FontWeight.SemiBold, tracking = -0.01f), softWrap = false, lineLimit = 1)
        }
    }
}

@Composable
private fun Trailing(compact: Boolean, connectionIsOpen: Boolean, origin: String, username: String) {
    HStack(spacing = if (compact) Space.sp2 else Space.sp3) {
        if (!connectionIsOpen) {
            Help(S.connection.reconnecting) { Spinner() }
        }
        if (!compact) {
            Help(origin) {
                Text(
                    Identity.gatewayHost(origin),
                    css(FontSize.fs12, mono = true),
                    Modifier.widthIn(max = 220.dp),
                    color = Palette.inkSecondary,
                    lineLimit = 1,
                )
            }
        }
        Avatar(username)
    }
}

/** `.avatar`: the initials on a 28 px ink circle. */
@Composable
internal fun Avatar(username: String) {
    Box(Modifier.size(28.dp).background(Palette.ink, CircleShape), contentAlignment = Alignment.Center) {
        Text(
            Identity.initials(username.ifEmpty { "?" }),
            css(FontSize.fs11, weight = FontWeight.SemiBold, tracking = 0.02f),
            color = Palette.inkInverse,
            softWrap = false,
        )
    }
}
