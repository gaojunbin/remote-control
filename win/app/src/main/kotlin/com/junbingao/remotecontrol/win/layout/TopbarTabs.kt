package com.junbingao.remotecontrol.win.layout

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.junbingao.remotecontrol.win.app.LocalShellState
import com.junbingao.remotecontrol.win.app.Route
import com.junbingao.remotecontrol.win.design.Button
import com.junbingao.remotecontrol.win.design.ButtonConfiguration
import com.junbingao.remotecontrol.win.design.ButtonStyle
import com.junbingao.remotecontrol.win.design.FontSize
import com.junbingao.remotecontrol.win.design.HStack
import com.junbingao.remotecontrol.win.design.LocalReduceMotion
import com.junbingao.remotecontrol.win.design.Motion
import com.junbingao.remotecontrol.win.design.Palette
import com.junbingao.remotecontrol.win.design.Space
import com.junbingao.remotecontrol.win.design.Text
import com.junbingao.remotecontrol.win.design.css
import com.junbingao.remotecontrol.win.strings.S

/** `.tabs`: Devices, Sessions and Settings, 2 px apart. Built on every draw, so the tabs follow the interface language. */
@Composable
fun TopbarTabs(compact: Boolean, tight: Boolean, modifier: Modifier = Modifier) {
    val router = LocalShellState.current.router
    HStack(modifier.semantics { contentDescription = S.nav.primary }, spacing = 2.dp) {
        for ((route, title) in tabs()) {
            TopbarTab(title, active = router.route.tab == route, compact = compact, tight = tight) { router.go(route) }
        }
    }
}

fun tabs(): List<Pair<Route, String>> =
    listOf(Route.Devices to S.nav.devices, Route.Sessions to S.nav.sessions, Route.Settings to S.nav.settings)

/**
 * `.tab`: a 30 px pill in the secondary ink; the pointer darkens it onto `--surface-hover`, and the
 * current one is ink on `--surface-muted` at 500. 13 px with less padding at 760 and narrower, less
 * again at 420.
 */
@Composable
internal fun TopbarTab(title: String, active: Boolean, compact: Boolean, tight: Boolean, action: () -> Unit) {
    Button(action, Modifier.semantics { selected = active }, style = TabStyle(active, compact, tight)) {
        Text(
            title,
            css(if (compact) FontSize.fs13 else FontSize.fs14, weight = if (active) FontWeight.Medium else FontWeight.Normal),
            Modifier.fillMaxHeight(),
            softWrap = false,
            lineLimit = 1,
        )
    }
}

private class TabStyle(val active: Boolean, val compact: Boolean, val tight: Boolean) : ButtonStyle {
    @Composable
    override fun Body(configuration: ButtonConfiguration, modifier: Modifier) {
        val reduceMotion = LocalReduceMotion.current
        val hovered = configuration.isHovered
        val ink by animateColorAsState(if (active || hovered) Palette.ink else Palette.inkSecondary, Motion.ease(Motion.durFast, reduceMotion))
        val fill by animateColorAsState(
            if (active) Palette.surfaceMuted else if (hovered) Palette.surfaceHover else Color.Transparent,
            Motion.ease(Motion.durFast, reduceMotion),
        )
        Box(
            modifier
                .height(30.dp)
                .background(fill, CircleShape)
                .padding(horizontal = if (tight) 8.dp else if (compact) 10.dp else Space.sp3),
        ) {
            com.junbingao.remotecontrol.win.design.WithForeground(ink) { configuration.label() }
        }
    }
}
