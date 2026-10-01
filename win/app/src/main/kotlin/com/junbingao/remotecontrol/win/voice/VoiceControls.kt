package com.junbingao.remotecontrol.win.voice

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.junbingao.remotecontrol.win.design.Button
import com.junbingao.remotecontrol.win.design.ButtonSize
import com.junbingao.remotecontrol.win.design.ButtonVariant
import com.junbingao.remotecontrol.win.design.Disabled
import com.junbingao.remotecontrol.win.design.FontSize
import com.junbingao.remotecontrol.win.design.HStack
import com.junbingao.remotecontrol.win.design.Palette
import com.junbingao.remotecontrol.win.design.Space
import com.junbingao.remotecontrol.win.design.Text
import com.junbingao.remotecontrol.win.design.btn
import com.junbingao.remotecontrol.win.design.css
import com.junbingao.remotecontrol.win.shared.Format
import com.junbingao.remotecontrol.win.strings.S
import kotlin.math.floor

/**
 * `web/src/features/voice/VoiceControls.tsx`: what the composer's box holds while dictation runs —
 * how loud it is, how long it has been listening, and the one way out. Done stands where Send
 * stands, at Send's size, because it is the one primary action while listening; there is no Cancel
 * and no "stop and send". The click on Done is answered in that same slot: the capsule gives way to
 * the spinner until the backend's final transcript lands.
 */
@Composable
fun VoiceControls(voice: VoiceController, slot: PrimarySlot, modifier: Modifier = Modifier, onDone: () -> Unit) {
    val elapsed = Format.clock(voice.elapsedMs)
    HStack(modifier.fillMaxWidth().padding(top = Space.sp2, bottom = 2.dp), spacing = Space.sp2) {
        Waveform(voice.level)
        Text(
            elapsed,
            css(FontSize.fs13, mono = true),
            Modifier.semantics { contentDescription = S.voice.listeningFor(elapsed) },
            color = Palette.inkSecondary,
        )
        // Everything after the timer is pushed to the trailing edge.
        Spacer(Modifier.weight(1f))
        if (slot == PrimarySlot.done) {
            Disabled(voice.state != VoiceState.listening) {
                Button(onDone, style = btn(ButtonVariant.primary, ButtonSize.small)) {
                    Text(S.voice.done, css(FontSize.fs13, weight = FontWeight.Medium), Modifier.fillMaxHeight(), softWrap = false)
                }
            }
        } else {
            WorkingPill(S.voice.finishing)
        }
    }
}

/** Fixed per-bar weights: a level of 1 lights the middle bars tallest. */
private val weights = listOf(0.35, 0.55, 0.8, 1.0, 0.7, 0.95, 0.6, 0.85, 0.5, 0.7, 0.4)

/** Eleven bars, each as tall as the level and its weight say, following the level in 90 ms. */
@Composable
private fun Waveform(level: Double) {
    HStack(Modifier.height(24.dp).clearAndSetSemantics {}, spacing = 2.dp) {
        for (weight in weights) {
            val height by animateDpAsState(floor(4 + weight * level * 20 + 0.5).dp, tween(90, easing = LinearEasing), label = "bar")
            Box(Modifier.width(2.dp).height(height).background(Palette.ink, RoundedCornerShape(1.dp)))
        }
    }
}
