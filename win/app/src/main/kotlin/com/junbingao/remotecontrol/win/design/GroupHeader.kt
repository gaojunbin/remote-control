package com.junbingao.remotecontrol.win.design

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.junbingao.remotecontrol.win.design.icons.Icon
import com.junbingao.remotecontrol.win.design.icons.LucideIcon
import com.junbingao.remotecontrol.win.strings.S

/**
 * `.group-head`: a group caption that doubles as its disclosure control. The chevron takes the
 * gutter, so the label keeps the indent a plain caption has; the pointer turns it the secondary
 * ink.
 */
internal class GroupHeadStyle(val leading: Dp, val spacing: Dp) : ButtonStyle {
    @Composable
    override fun Body(configuration: ButtonConfiguration, modifier: Modifier) {
        val reduceMotion = LocalReduceMotion.current
        val base = LocalContentColor.current
        val ink by animateColorAsState(if (configuration.isHovered) Palette.inkSecondary else base, Motion.ease(Motion.durFast, reduceMotion))
        Row(
            modifier.padding(start = leading, end = Space.sp2, top = Space.sp1, bottom = Space.sp1),
            horizontalArrangement = Arrangement.spacedBy(spacing),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            CompositionLocalProvider(LocalContentColor provides ink) { configuration.label() }
        }
    }
}

/** `.group-chevron`: a right chevron that turns a quarter when the group opens. */
@Composable
internal fun GroupChevron(size: Dp, expanded: Boolean) {
    val reduceMotion = LocalReduceMotion.current
    val angle by animateFloatAsState(if (expanded) 90f else 0f, Motion.ease(Motion.durFast, reduceMotion))
    Icon(LucideIcon.chevronRight, size = size, modifier = Modifier.rotate(angle))
}

/**
 * `web/src/components/GroupHeader.tsx` `DeviceGroupHeader`: the header of one device's group,
 * which the Sessions page and the chat sidebar share so the two lists read the same way. The name
 * is printed exactly as the device reports it — never re-cased — at 14 px, 600, in ink.
 *
 * `leading` is the header's left padding: none in `.group-head`, which the chat sidebar raises to 8.
 */
@Composable
fun DeviceGroupHeader(
    name: String,
    online: Boolean,
    expanded: Boolean,
    leading: Dp = 0.dp,
    modifier: Modifier = Modifier,
    onToggle: () -> Unit,
) {
    WithForeground(Palette.ink) {
        Button(onToggle, modifier, style = GroupHeadStyle(leading, Space.sp2)) {
            GroupChevron(13.dp, expanded)
            Text(name, css(FontSize.fs14, weight = FontWeight.SemiBold, tracking = -0.01f), lineLimit = 1)
            OnlineDot(online)
        }
    }
}

/**
 * `ArchiveGroupHeader`: one device's Archive, folded shut under its active rows. It takes the ink
 * of wherever it is placed, as the web's button inherits it; `leading` is 12 in `.archive-head`.
 * `fontSize` is the size of the type around it, which the web's button inherits: 14 on the page body.
 */
@Composable
fun ArchiveGroupHeader(
    count: Int,
    expanded: Boolean,
    leading: Dp = Space.sp3,
    fontSize: Float = FontSize.fs14,
    modifier: Modifier = Modifier,
    onToggle: () -> Unit,
) {
    Button(onToggle, modifier, style = GroupHeadStyle(leading, Space.sp1)) {
        GroupChevron(12.dp, expanded)
        Text(S.sessions.archiveGroup(count), css(fontSize))
    }
}
