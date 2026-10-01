package com.junbingao.remotecontrol.android.screens.chat.voice

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.junbingao.remotecontrol.android.design.LocalAppearance
import com.junbingao.remotecontrol.android.system.safeArea

/**
 * What the listening glow shows, written by the composer that is listening and drawn by the
 * conversation over everything it holds.
 *
 * The iPhone puts the light in a window of its own, because the composer's bounds are nowhere near
 * the edge of the screen and a sheet would cover an overlay drawn as a sibling. Here the
 * conversation is the screen, edge to edge, so it draws the light last of all its layers
 * ([VoiceGlowLayer]) and the composer only says what to draw ([VoiceGlowPresenter]).
 */
class VoiceGlowState {
    var active: Boolean by mutableStateOf(false)
    var level: Double by mutableDoubleStateOf(0.0)
}

val LocalVoiceGlow = staticCompositionLocalOf<VoiceGlowState?> { null }

/** Puts the listening glow around the whole display rather than around the control that started it. */
@Composable
fun VoiceGlowPresenter(active: Boolean, level: Double) {
    val glow = LocalVoiceGlow.current ?: return
    SideEffect {
        glow.active = active
        glow.level = level
    }
    DisposableEffect(glow) { onDispose { glow.active = false } }
}

/** The light over the conversation, taking no touches and saying nothing to a screen reader. */
@Composable
fun VoiceGlowLayer(state: VoiceGlowState) {
    val safe = safeArea()
    val corner = if (safe.displayCorner > 0.dp) safe.displayCorner else DisplayCorner.radius(safe.bottom)
    VoiceGlowRoot(state.active, state.level, LocalAppearance.current.reduceMotion, corner)
}

/** The drawn root, edge to edge and over the system bars. */
@Composable
fun VoiceGlowRoot(active: Boolean, level: Double, reduceMotion: Boolean, cornerRadius: Dp) {
    Box(Modifier.fillMaxSize().clearAndSetSemantics { }) {
        VoiceEdgeGlow(active = active, level = level, reduceMotion = reduceMotion, cornerRadius = cornerRadius)
    }
}
