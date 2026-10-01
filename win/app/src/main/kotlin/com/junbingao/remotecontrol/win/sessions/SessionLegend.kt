package com.junbingao.remotecontrol.win.sessions

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import com.junbingao.remotecontrol.core.state.DotTone
import com.junbingao.remotecontrol.win.design.Dot
import com.junbingao.remotecontrol.win.design.DotStyle
import com.junbingao.remotecontrol.win.design.FontSize
import com.junbingao.remotecontrol.win.design.HStack
import com.junbingao.remotecontrol.win.design.Palette
import com.junbingao.remotecontrol.win.design.Space
import com.junbingao.remotecontrol.win.design.Text
import com.junbingao.remotecontrol.win.design.css
import com.junbingao.remotecontrol.win.devices.WrapRow
import com.junbingao.remotecontrol.win.strings.S

/**
 * `SessionLegend.tsx` with `legend.ts`: what the dots mean, said once above the list and nowhere
 * else — not on the chat sidebar, not on the Devices screen, and not when the list is empty, where
 * the empty state speaks instead. A caption line with no box, no border and no title
 * (`docs/DESIGN.md` § "A legend, once, and quiet").
 */
@Composable
internal fun SessionLegend(modifier: Modifier = Modifier) {
    WrapRow(spacing = Space.sp4, lineSpacing = Space.sp1, modifier = modifier.semantics { contentDescription = S.sessions.legend }) {
        for (entry in SessionLegend.entries) {
            HStack(spacing = Space.sp2) {
                Dot(DotStyle.Tone(entry.tone))
                Text(entry.label, css(FontSize.fs12, lineHeight = 1.45f), color = Palette.inkSecondary, lineLimit = 1)
            }
        }
    }
}

internal object SessionLegend {
    /**
     * The four colours a dot takes, in the order the legend reads them. Four, not five: the
     * pulsing amber of a waiting session and the solid amber of a finished turn are one colour to
     * the eye, so the legend draws the still one and "For you" covers both. Built on every read, so
     * the words follow the interface language.
     */
    val entries: List<Entry>
        get() = listOf(
            Entry(DotTone.working, S.sessions.legendWorking),
            Entry(DotTone.live, S.sessions.legendAttention),
            Entry(DotTone.off, S.sessions.legendOff),
            Entry(DotTone.failed, S.sessions.legendFailed),
        )

    data class Entry(val tone: DotTone, val label: String) {
        val id: DotTone get() = tone
    }
}
