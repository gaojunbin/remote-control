package com.junbingao.remotecontrol.android.design

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * The agent's own logo, monochrome, in the square the words beside it stand in.
 *
 * `docs/DESIGN.md` § "Agents": the shape tells the agents apart, never a colour per vendor, so
 * every mark is drawn in the ink on the same quiet tint the chips share. An agent this build
 * carries no vector for is marked by the first letter of its id in the same box — the core's
 * `AgentLabel.initial` rule, which the logo applies itself.
 *
 * [tint] is the colour to draw in, or null to take whatever the caller has set above it — a
 * selected segment, a menu row, a chip. The box follows the phone's font size exactly as the
 * footnote beside it does.
 */
@Composable
fun AgentLogo(
    agent: String,
    modifier: Modifier = Modifier,
    size: Dp = Theme.Mark.inline,
    tint: Color? = Theme.ink,
) {
    val side = scaledMetric(size, SystemFont.footnote)
    val ink = tint ?: LocalForeground.current.takeIf { it != Color.Unspecified } ?: SystemColor.label
    val vector = AgentLogo.vector(agent)
    Box(modifier.size(side).clearAndSetSemantics {}, contentAlignment = Alignment.Center) {
        if (vector != null) {
            Image(vector, contentDescription = null, colorFilter = ColorFilter.tint(ink))
        } else {
            Text(
                agent.take(1).uppercase(),
                style = SystemFont.system(side.value, FontWeight.SemiBold),
                color = ink,
                lineLimit = 1,
            )
        }
    }
}

object AgentLogo {
    private val built = HashMap<String, ImageVector>()

    /**
     * The vector alone, for the controls that take an image rather than a view — a segmented
     * control's segment, a menu row. Null for an agent with no vector.
     */
    fun vector(agent: String): ImageVector? {
        val mark = AgentLogoPaths.marks[agent] ?: return null
        return built.getOrPut(agent) {
            ImageVector.Builder(
                name = "agent-$agent",
                defaultWidth = 24.dp,
                defaultHeight = 24.dp,
                viewportWidth = mark.viewport,
                viewportHeight = mark.viewport,
            ).apply {
                for ((data, evenOdd) in mark.paths) {
                    addPath(
                        pathData = addPathNodes(data),
                        pathFillType = if (evenOdd) PathFillType.EvenOdd else PathFillType.NonZero,
                        fill = SolidColor(Color.Black),
                    )
                }
            }.build()
        }
    }

    /** The agents this build draws a mark for, in the device row's order. */
    val known: List<String> = listOf("claude", "codex", "grok", "pi")
}
