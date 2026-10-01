package com.junbingao.remotecontrol.win.design

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/**
 * `.badge`, `.badge.warn` and `.badge.error`: a 20 px tinted pill of 12 px text. Nothing is
 * outlined (`docs/DESIGN.md` § "Chips and badges").
 */
@Composable
fun Badge(text: String, tone: Badge.Tone = Badge.Tone.neutral, modifier: Modifier = Modifier) {
    Box(modifier.height(20.dp).background(tone.background, CircleShape).padding(horizontal = 8.dp)) {
        Text(text, css(FontSize.fs12), Modifier.fillMaxHeight(), color = tone.foreground, softWrap = false, lineLimit = 1)
    }
}

object Badge {
    enum class Tone(val foreground: Color, val background: Color) {
        neutral(Palette.inkSecondary, Palette.surfaceMuted),
        warn(Color.hex(0x775707), Palette.attentionSoft),
        error(Color.hex(0x9C2C21), Palette.dangerSoft),
    }
}
