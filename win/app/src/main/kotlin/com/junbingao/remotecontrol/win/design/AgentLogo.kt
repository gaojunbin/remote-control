package com.junbingao.remotecontrol.win.design

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlin.math.min

/**
 * `web/src/components/AgentLogo.tsx`: the mark of an agent, its own logo drawn monochrome in the
 * ink of the text beside it. The shape is what tells the agents apart, never a colour per vendor
 * (`docs/DESIGN.md` § "Agents").
 *
 * The box is one em square — `size` is the font size of the line it stands in — so a logo reads
 * as the same size as the name it stands beside or replaces. An agent nobody knows has no vector:
 * it is marked by the first letter of its id, in the same box.
 */
@Composable
fun AgentLogo(agent: String, size: Float, modifier: Modifier = Modifier) {
    val logo = AgentLogoArt.logos[agent]
    if (logo == null) {
        Box(modifier.size(size.dp), contentAlignment = Alignment.Center) {
            WithFont(size, FontWeight.SemiBold) {
                // Its line is taller than the box, and overflows it evenly as SwiftUI's does.
                Text(agent.take(1).uppercase(), modifier = Modifier.wrapContentSize(unbounded = true), softWrap = false)
            }
        }
        return
    }
    val ink = LocalContentColor.current
    Canvas(modifier.size(size.dp)) {
        val box = logo.viewBox
        val scale = min(this.size.width / box.width, this.size.height / box.height)
        val dx = (this.size.width - box.width * scale) / 2 - box.left * scale
        val dy = (this.size.height - box.height * scale) / 2 - box.top * scale
        translate(dx, dy) {
            scale(scale, scale, pivot = androidx.compose.ui.geometry.Offset.Zero) {
                for (path in logo.paths) drawPath(path.path, ink)
            }
        }
    }
}
