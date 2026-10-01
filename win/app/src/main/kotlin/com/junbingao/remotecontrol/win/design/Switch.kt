package com.junbingao.remotecontrol.win.design

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp

/**
 * `web/src/components/Switch.tsx` / `.switch`: a 40 × 24 pill in `--line-strong`, ink when on, with
 * an 18 px white knob that slides 16 px over `--dur`. Disabled, it looks exactly as it does
 * enabled and keeps the pointer: `ui.css` has no rule for `.switch:disabled`, so only the press
 * stops working.
 */
@Composable
fun Switch(isOn: Boolean, label: String, modifier: Modifier = Modifier, onChange: (Boolean) -> Unit) {
    val reduceMotion = LocalReduceMotion.current
    val track by animateColorAsState(if (isOn) Palette.ink else Palette.lineStrong, Motion.ease(Motion.dur, reduceMotion))
    val knob by animateDpAsState(if (isOn) 19.dp else 3.dp, Motion.ease(Motion.dur, reduceMotion))
    Button(
        action = { onChange(!isOn) },
        modifier = modifier.pointerHoverIcon(PointerIcon.Hand, overrideDescendants = true).semantics {
            contentDescription = label
            stateDescription = if (isOn) "1" else "0"
            role = Role.Switch
        },
    ) {
        Box(Modifier.size(40.dp, 24.dp).background(track, CircleShape), contentAlignment = Alignment.CenterStart) {
            Box(
                Modifier
                    .offset(x = knob)
                    .size(18.dp)
                    .boxShadow(Shadow.one, CircleShape)
                    .background(Palette.surface, CircleShape),
            )
        }
    }
}
