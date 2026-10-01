package com.junbingao.remotecontrol.win.devices.adddevice

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.junbingao.remotecontrol.win.design.Dot
import com.junbingao.remotecontrol.win.design.DotStyle
import com.junbingao.remotecontrol.win.design.FontSize
import com.junbingao.remotecontrol.win.design.HStack
import com.junbingao.remotecontrol.win.design.LocalReduceMotion
import com.junbingao.remotecontrol.win.design.Motion
import com.junbingao.remotecontrol.win.design.Palette
import com.junbingao.remotecontrol.win.design.Space
import com.junbingao.remotecontrol.win.design.Text
import com.junbingao.remotecontrol.win.design.VStack
import com.junbingao.remotecontrol.win.design.WithForeground
import com.junbingao.remotecontrol.win.design.css
import com.junbingao.remotecontrol.win.design.icons.Icon
import com.junbingao.remotecontrol.win.design.icons.LucideIcon
import com.junbingao.remotecontrol.win.shared.Format
import com.junbingao.remotecontrol.win.strings.S
import kotlin.math.max

/**
 * `PairingProgress.tsx`: the handshake the code walks through, filled in as `pairing.progress`
 * arrives — a title with its dot and how long the modal has been listening, a hairline of progress,
 * and the three steps.
 */
@Composable
internal fun PairingSteps(
    pairing: AddDevicePairing,
    /** Milliseconds since the modal started listening. */
    elapsed: Long,
    modifier: Modifier = Modifier,
) {
    val live = pairing.live
    val marks = PairingChecklist.marks(live?.step)
    val agents = PairingChecklist.agents(live)
    val device = live?.device
    VStack(modifier.fillMaxWidth().pairingBox(Palette.surface).padding(Space.sp4), spacing = 0.dp, alignment = Alignment.Start) {
        HStack(Modifier.fillMaxWidth(), spacing = Space.sp3) {
            HStack(spacing = Space.sp2) {
                Dot(if (pairing.connected) DotStyle.Online else DotStyle.Idle, pulses = !pairing.connected)
                Text(
                    if (pairing.connected && device != null) S.pairing.connected(device.name) else S.pairing.waiting,
                    css(FontSize.fs14, weight = FontWeight.Medium),
                )
            }
            Spacer(Modifier.weight(1f))
            Text(S.pairing.listening(Format.clock(max(0, elapsed).toDouble())), css(FontSize.fs12, mono = true), color = Palette.inkSecondary)
        }
        PairingProgressLine(PairingChecklist.progress(live?.step), Modifier.padding(top = Space.sp3, bottom = Space.sp4))
        VStack(spacing = Space.sp3, alignment = Alignment.Start) {
            PairingStepRow(S.pairing.stepGateway, marks[0])
            PairingStepRow(S.pairing.stepHandshake, marks[1])
            PairingStepRow(S.pairing.stepAgents, marks[2], trailing = agents)
        }
    }
}

/** `.pair-progress`: one `--line` hairline, filled in the ink to the step reached, easing over 400 ms. */
@Composable
private fun PairingProgressLine(percent: Double, modifier: Modifier) {
    val reduceMotion = LocalReduceMotion.current
    val fraction by animateFloatAsState((percent / 100).toFloat(), Motion.ease(400, reduceMotion))
    Box(modifier.fillMaxWidth().height(1.dp).background(Palette.line)) {
        Box(Modifier.fillMaxWidth(fraction).fillMaxHeight().background(Palette.ink))
    }
}

/**
 * One step: a 16 px ring — filled with a check once done, pulsing while it is the one under way —
 * and its label in the ink the step has reached.
 */
@Composable
private fun PairingStepRow(label: String, mark: PairingChecklist.Mark, trailing: String = "") {
    val ink = when (mark) {
        PairingChecklist.Mark.done -> Palette.ink
        PairingChecklist.Mark.active -> Palette.inkSecondary
        PairingChecklist.Mark.idle -> Palette.inkTertiary
    }
    WithForeground(ink) {
        HStack(spacing = Space.sp3) {
            PairingStepMark(mark)
            Text(label, css(FontSize.fs14))
            if (trailing.isNotEmpty()) Text(trailing, css(FontSize.fs12, mono = true), color = Palette.inkSecondary)
        }
    }
}

@Composable
private fun PairingStepMark(mark: PairingChecklist.Mark) {
    // `rc-pulse` over 1.6 s while the step is under way.
    val pulsing = mark == PairingChecklist.Mark.active && !LocalReduceMotion.current
    val opacity = if (pulsing) {
        val transition = rememberInfiniteTransition(label = "step")
        val value by transition.animateFloat(
            initialValue = 1f,
            targetValue = 0.35f,
            animationSpec = infiniteRepeatable(tween(800, easing = Motion.curve), RepeatMode.Reverse),
            label = "step",
        )
        value
    } else {
        1f
    }
    Box(Modifier.size(16.dp).alpha(opacity), contentAlignment = Alignment.Center) {
        if (mark == PairingChecklist.Mark.done) {
            Canvas(Modifier.fillMaxSize()) { drawCircle(Palette.ink) }
            Icon(LucideIcon.check, size = 11.dp, strokeWidth = 3f, color = Color.White)
        } else {
            val ring = if (mark == PairingChecklist.Mark.active) Palette.inkSecondary else Palette.lineStrong
            Canvas(Modifier.fillMaxSize()) {
                val width = 1.5.dp.toPx()
                drawCircle(ring, radius = size.minDimension / 2 - width / 2, style = Stroke(width))
            }
        }
    }
}
