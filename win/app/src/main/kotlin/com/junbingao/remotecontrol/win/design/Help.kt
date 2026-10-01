package com.junbingao.remotecontrol.win.design

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.TooltipArea
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * `.help(text)`: the tooltip the pointer brings up after a moment, drawn as Windows draws its
 * own — a small light box with a hairline edge. An empty text shows nothing, as SwiftUI's does.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun Help(text: String, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    if (text.isEmpty()) {
        content()
        return
    }
    TooltipArea(
        tooltip = {
            Text(
                text,
                css(FontSize.fs12),
                Modifier
                    .background(Palette.surface, RoundedCornerShape(4.dp))
                    .border(1.dp, Palette.line, RoundedCornerShape(4.dp))
                    .padding(horizontal = 8.dp, vertical = 3.dp),
            )
        },
        modifier = modifier,
        delayMillis = 600,
        content = content,
    )
}
