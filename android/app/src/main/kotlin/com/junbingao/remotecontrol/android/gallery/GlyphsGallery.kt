package com.junbingao.remotecontrol.android.gallery

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.junbingao.remotecontrol.android.design.AgentLogo
import com.junbingao.remotecontrol.android.design.EffortGauge
import com.junbingao.remotecontrol.android.design.FolderGlyph
import com.junbingao.remotecontrol.android.design.LaptopGlyph
import com.junbingao.remotecontrol.android.design.LaptopShape
import com.junbingao.remotecontrol.android.design.PromptShield
import com.junbingao.remotecontrol.android.design.SystemFont
import com.junbingao.remotecontrol.android.design.Text
import com.junbingao.remotecontrol.android.design.Theme
import kotlin.math.roundToInt

/** The iPhone's own glyphs, redrawn from their paths, and the four agents' marks. */
@Composable
internal fun GlyphsGallery() {
    GalleryScaffold(GalleryPages.glyphs.title) {
        Specimen("Laptop on a title's baseline, folder before a path") {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                LaptopGlyph(Modifier.alignBy { (it.measuredHeight * LaptopShape.baselineFraction).roundToInt() })
                Text("mac-studio-office", Modifier.alignByBaseline(), style = Theme.Text.title, color = Theme.ink)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                FolderGlyph()
                Text("/Users/me/dev/remote-control/web", style = Theme.Text.metaMono, color = Theme.inkSecondary)
            }
        }
        Specimen("Effort gauge: none, lowest, a third, two thirds, highest, highest and fast") {
            Row(
                Modifier.padding(end = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(22.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                EffortGauge(null, isFast = false)
                EffortGauge(0.0, isFast = false)
                EffortGauge(1.0 / 3, isFast = false)
                EffortGauge(2.0 / 3, isFast = false)
                EffortGauge(1.0, isFast = false)
                EffortGauge(1.0, isFast = true)
            }
        }
        Specimen("Prompt shield at body and at title size") {
            Row(horizontalArrangement = Arrangement.spacedBy(18.dp), verticalAlignment = Alignment.CenterVertically) {
                PromptShield(tint = Theme.ink)
                PromptShield(font = SystemFont.title, tint = Theme.ink)
            }
        }
        Specimen("Agent logos: inline beside a word, alone in a control, and an unknown agent's initial") {
            for (agent in AgentLogo.known + "aider") {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    AgentLogo(agent)
                    Text(agent, style = Theme.Text.meta, color = Theme.ink)
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                for (agent in AgentLogo.known) AgentLogo(agent, size = Theme.Mark.control)
            }
        }
    }
}
