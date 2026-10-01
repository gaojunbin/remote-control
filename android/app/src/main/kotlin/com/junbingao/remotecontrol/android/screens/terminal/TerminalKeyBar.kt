package com.junbingao.remotecontrol.android.screens.terminal

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.junbingao.remotecontrol.android.design.Bar
import com.junbingao.remotecontrol.android.design.Button
import com.junbingao.remotecontrol.android.design.ContinuousShape
import com.junbingao.remotecontrol.android.design.SystemColor
import com.junbingao.remotecontrol.android.design.SystemFont
import com.junbingao.remotecontrol.android.design.Text
import com.junbingao.remotecontrol.android.design.Theme
import com.junbingao.remotecontrol.android.design.monospaced
import com.junbingao.remotecontrol.core.state.TerminalKey

/**
 * The one thing that decides whether a shell is usable on a phone (`docs/DESIGN.md` § "The
 * terminal" → **The phone's key bar**).
 *
 * One row above the keyboard, scrolling sideways when it must, in the order the design lists: Esc ·
 * Tab · Ctrl · ↑ · ↓ · ← · → · Ctrl-C · Ctrl-D · Ctrl-Z · Ctrl-R · Ctrl-L · | · / · - · ~ · Paste.
 * Every cap sends the bytes `TerminalKey` names and nothing app-specific; Ctrl is sticky and shows
 * it. The bar's material runs [reach] further down, to the foot of the screen under it.
 *
 * [isControlArmed]: whether the next key is sent as a control character.
 */
@Composable
fun TerminalKeyBar(isControlArmed: Boolean, onKey: (TerminalKey) -> Unit, modifier: Modifier = Modifier, reach: Dp = 0.dp) {
    val material = Bar.material
    val line = SystemColor.separator
    Box(
        modifier
            .fillMaxWidth()
            .drawBehind { drawRect(material, size = Size(size.width, size.height + reach.toPx())) }
            .testTag("terminal.keyBar"),
    ) {
        Row(
            Modifier
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = Theme.Space.small, vertical = Theme.Space.tight),
            horizontalArrangement = Arrangement.spacedBy(Theme.Space.tight),
        ) {
            for (key in TerminalKey.bar) Cap(key, armed = key == TerminalKey.control && isControlArmed) { onKey(key) }
        }
        Box(Modifier.align(Alignment.TopCenter).fillMaxWidth().height(KeyBarMetrics.hairline).background(line))
    }
}

@Composable
private fun Cap(key: TerminalKey, armed: Boolean, press: () -> Unit) {
    Button(
        onClick = press,
        Modifier
            .semantics {
                contentDescription = key.spokenName
                if (armed) selected = true
            }
            .testTag("terminal.key.${key.rawValue}"),
    ) {
        Box(
            Modifier
                .background(if (armed) Theme.accent else Theme.quietFill, ContinuousShape(Theme.Radius.control))
                .widthIn(min = KeyBarMetrics.capWidth)
                .heightIn(min = KeyBarMetrics.capHeight)
                .padding(horizontal = Theme.Space.small),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                key.cap,
                style = if (key == TerminalKey.paste) SystemFont.footnote else SystemFont.footnote.monospaced(),
                color = if (armed) Theme.onAccent else Theme.ink,
                lineLimit = 1,
            )
        }
    }
}

private object KeyBarMetrics {
    val capWidth = 40.dp
    val capHeight = 34.dp

    /** `Divider()` along the bar's top edge. */
    val hairline = 0.33.dp
}
