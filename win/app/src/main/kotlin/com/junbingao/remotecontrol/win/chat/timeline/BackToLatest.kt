package com.junbingao.remotecontrol.win.chat.timeline

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.junbingao.remotecontrol.win.design.Button
import com.junbingao.remotecontrol.win.design.ButtonStyle
import com.junbingao.remotecontrol.win.design.FontSize
import com.junbingao.remotecontrol.win.design.HStack
import com.junbingao.remotecontrol.win.design.Help
import com.junbingao.remotecontrol.win.design.LocalReduceMotion
import com.junbingao.remotecontrol.win.design.Motion
import com.junbingao.remotecontrol.win.design.Palette
import com.junbingao.remotecontrol.win.design.Shadow
import com.junbingao.remotecontrol.win.design.Space
import com.junbingao.remotecontrol.win.design.Text
import com.junbingao.remotecontrol.win.design.WithForeground
import com.junbingao.remotecontrol.win.design.boxShadow
import com.junbingao.remotecontrol.win.design.css
import com.junbingao.remotecontrol.win.design.icons.Icon
import com.junbingao.remotecontrol.win.design.icons.LucideIcon
import com.junbingao.remotecontrol.win.strings.S

/**
 * `BackToLatest` in `Timeline.tsx`: the way back down, a round button centred at the foot of the
 * transcript, lifted on the shadow alone. It widens into a capsule around that centre when it has
 * a count to carry, and its words are in its accessible name.
 */
@Composable
fun BackToLatest(missed: Int, modifier: Modifier = Modifier, action: () -> Unit) {
    val count = if (missed > 0) S.chat.newUpdates(missed) else null
    val reduceMotion = LocalReduceMotion.current
    // `back-to-latest-in`: from 92 % and transparent over 150 ms.
    val shown = remember { Animatable(0f) }
    LaunchedEffect(Unit) { shown.animateTo(1f, Motion.ease(150, reduceMotion)) }
    Help(S.chat.backToLatest, modifier.graphicsLayer {
        val scale = 0.92f + 0.08f * shown.value
        scaleX = scale
        scaleY = scale
        alpha = shown.value
    }) {
        Button(
            action,
            style = BackToLatestStyle,
            accessibilityLabel = count?.let { "${S.chat.backToLatest}, $it" } ?: S.chat.backToLatest,
        ) {
            HStack(Modifier.padding(horizontal = if (count == null) 0.dp else Space.sp3), spacing = Space.sp1) {
                Icon(LucideIcon.arrowDown, size = 16.dp)
                if (count != null) {
                    // `font-variant-numeric: tabular-nums`.
                    Text(
                        AnnotatedString(count, listOf(AnnotatedString.Range(SpanStyle(fontFeatureSettings = "tnum"), 0, count.length))),
                        css(FontSize.fs13, weight = FontWeight.Medium),
                        softWrap = false,
                    )
                }
            }
        }
    }
}

/** A capsule at least 36 points either way, on the surface — the hover tint under the pointer — lifted by `--shadow-pop`. */
private val BackToLatestStyle = ButtonStyle { configuration, modifier ->
    WithForeground(Palette.ink) {
        Box(
            modifier
                .boxShadow(Shadow.pop, CircleShape)
                .background(if (configuration.isHovered) Palette.surfaceHover else Palette.surface, CircleShape)
                .defaultMinSize(minWidth = 36.dp, minHeight = 36.dp),
            contentAlignment = Alignment.Center,
        ) { configuration.label() }
    }
}
