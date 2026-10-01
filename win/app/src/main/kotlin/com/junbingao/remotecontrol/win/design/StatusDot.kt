package com.junbingao.remotecontrol.win.design

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.junbingao.remotecontrol.win.standin.DotTone
import com.junbingao.remotecontrol.win.strings.S

/**
 * `.dot` and its tones: a 7 px circle, one colour per line of the table in `docs/DESIGN.md`
 * § "The status dot". Green means working — leave it; amber means there is something for you, a
 * finished turn to read or a question to answer. Only `waiting` moves, so the one state that
 * needs the person is the one that asks for a glance.
 */
sealed interface DotStyle {
    /** A session's tone, as `DotTone` picks it (`web/src/components/dotTone.ts`). */
    data class Tone(val tone: DotTone) : DotStyle

    /** `.dot.running`: a device that is online. */
    data object Online : DotStyle

    /** `.dot.offline`: an empty ring of the idle grey. */
    data object Offline : DotStyle

    /** `.dot` alone: the idle grey. */
    data object Idle : DotStyle

    /** `.dot.attention` and `.dot.error`. */
    data object Attention : DotStyle
    data object Error : DotStyle

    val fill: Color?
        get() = when (this) {
            Online -> Palette.running
            Attention -> Palette.attention
            Error -> Palette.danger
            Offline -> null
            Idle -> Palette.idle
            is Tone -> when (tone) {
                DotTone.working -> Palette.running
                DotTone.waiting, DotTone.live -> Palette.attention
                DotTone.failed -> Palette.danger
                DotTone.off -> Palette.idle
            }
        }
}

/** One dot. `pulses` is `.dot.pulse`; a waiting session pulses of its own. */
@Composable
fun Dot(style: DotStyle, pulses: Boolean = false, modifier: Modifier = Modifier) {
    val pulsing = !LocalReduceMotion.current && (pulses || style == DotStyle.Tone(DotTone.waiting))
    val opacity = if (pulsing) {
        // `rc-pulse`: full, a third, full again over 1.8 s on the one easing curve.
        val transition = rememberInfiniteTransition(label = "pulse")
        val value by transition.animateFloat(
            initialValue = 1f,
            targetValue = 0.35f,
            animationSpec = infiniteRepeatable(tween(900, easing = Motion.curve), RepeatMode.Reverse),
            label = "pulse",
        )
        value
    } else {
        1f
    }
    val fill = style.fill
    Canvas(modifier.size(7.dp).alpha(opacity)) {
        if (fill != null) {
            drawCircle(fill)
        } else {
            val width = 1.5.dp.toPx()
            drawCircle(Palette.idle, radius = size.minDimension / 2 - width / 2, style = Stroke(width))
        }
    }
}

/**
 * `web/src/components/StatusDot.tsx`: a session's dot, in the tone `DotTone` picked from the
 * state, the control owner and the device together. The accessible name stays the raw state and
 * the tooltip names the tone. Stage 2 adds the form that takes the core's session types.
 */
@Composable
fun StatusDot(tone: DotTone, state: String, modifier: Modifier = Modifier) {
    Help(S.dotToneLabel(tone.rawValue)) {
        Dot(DotStyle.Tone(tone), modifier = modifier.semantics { contentDescription = S.stateLabel(state) })
    }
}

/** A device's own dot. Online or not, and pulsing while it updates itself (A22). */
@Composable
fun OnlineDot(online: Boolean, pulses: Boolean = false, modifier: Modifier = Modifier) {
    Dot(
        if (online) DotStyle.Online else DotStyle.Offline,
        pulses = pulses,
        modifier = modifier.semantics { contentDescription = if (online) "online" else "offline" },
    )
}
