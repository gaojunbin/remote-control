package com.junbingao.remotecontrol.android.gallery

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.junbingao.remotecontrol.android.design.ContinuousShape
import com.junbingao.remotecontrol.android.design.SystemFont
import com.junbingao.remotecontrol.android.design.Text
import com.junbingao.remotecontrol.android.design.Theme
import com.junbingao.remotecontrol.android.design.weight

/** The text styles at the iPhone's sizes, and every colour token of the palette. */
@Composable
internal fun TypeGallery() {
    GalleryScaffold(GalleryPages.type.title) {
        Specimen("Text styles") {
            TypeLine("Large title 34", SystemFont.largeTitle.weight(FontWeight.Bold))
            TypeLine("Title 2 22", SystemFont.title2)
            TypeLine("Headline 17", SystemFont.headline)
            TypeLine("Body 17 — 设备与会话", SystemFont.body)
            TypeLine("Callout 16 semibold (row title)", Theme.Text.title)
            TypeLine("Subheadline 15", SystemFont.subheadline)
            TypeLine("Footnote 13 (meta)", Theme.Text.meta)
            TypeLine("Caption 12", Theme.Text.caption)
            TypeLine("/Users/me/dev/remote-control", Theme.Text.metaMono)
        }
        Specimen("Palette") {
            Swatches(
                "canvas" to Theme.canvas, "surface" to Theme.surface, "sunken" to Theme.surfaceSunken,
                "border" to Theme.border, "ink" to Theme.ink, "secondary" to Theme.inkSecondary,
            )
            Swatches(
                "tertiary" to Theme.inkTertiary, "hairline" to Theme.hairline, "quiet" to Theme.quietFill,
                "running" to Theme.running, "attention" to Theme.attention, "resting" to Theme.resting,
            )
            Swatches("danger" to Theme.danger, "added" to Theme.added, "removed" to Theme.removed, "accent" to Theme.accent)
        }
    }
}

@Composable
private fun TypeLine(text: String, style: TextStyle) {
    Text(text, style = style, color = Theme.ink, lineLimit = 1)
}

@Composable
private fun Swatches(vararg swatches: Pair<String, Color>) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        for ((name, color) in swatches) {
            androidx.compose.foundation.layout.Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Box(
                    Modifier
                        .size(44.dp)
                        .background(color, ContinuousShape(10.dp))
                        .border(0.5.dp, Theme.border, ContinuousShape(10.dp)),
                )
                Text(name, style = SystemFont.caption2, color = Theme.inkSecondary, lineLimit = 1)
            }
        }
    }
}
