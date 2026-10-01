package com.junbingao.remotecontrol.android.system

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.toggleable
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.junbingao.remotecontrol.android.design.CapsuleShape
import com.junbingao.remotecontrol.android.design.LocalAppearance
import com.junbingao.remotecontrol.android.design.Theme

/**
 * `Toggle`'s switch as iOS 26 draws it: a 63 by 28 capsule, off in grey and on in the app's tint
 * (the accent, `.tint(Theme.accent)` at the root), with a white thumb that is itself a capsule,
 * 37 by 24, inset two points. A disabled switch keeps its shape at half strength.
 */
@Composable
fun Switch(
    isOn: Boolean,
    onChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    tag: String? = null,
) {
    val still = LocalAppearance.current.reduceMotion
    val track by animateColorAsState(if (isOn) Theme.accent else SwitchMetrics.offTrack, tween(if (still) 0 else 200), label = "track")
    val thumbX by animateDpAsState(
        if (isOn) SwitchMetrics.width - SwitchMetrics.thumbWidth - SwitchMetrics.inset else SwitchMetrics.inset,
        tween(if (still) 0 else 250, easing = IosEasing),
        label = "thumb",
    )
    Box(
        modifier
            .size(SwitchMetrics.width, SwitchMetrics.height)
            .alpha(if (enabled) 1f else 0.5f)
            .background(track, CapsuleShape)
            .toggleable(
                value = isOn,
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                enabled = enabled,
                role = Role.Switch,
                onValueChange = onChange,
            )
            .then(if (tag != null) Modifier.testTag(tag) else Modifier),
        contentAlignment = Alignment.CenterStart,
    ) {
        Box(
            Modifier
                .offset { IntOffset(thumbX.roundToPx(), 0) }
                .size(SwitchMetrics.thumbWidth, SwitchMetrics.thumbHeight)
                .shadow(1.5.dp, CapsuleShape, ambientColor = Color(0x14000000), spotColor = Color(0x29000000))
                .background(Color.White, CapsuleShape),
        )
    }
}

/**
 * `Toggle(_:isOn:)` in a row: the words at the leading edge in the font set above them, the
 * switch at the trailing edge, the whole row one control for assistive technology.
 */
@Composable
fun Toggle(
    title: String,
    isOn: Boolean,
    onChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    tag: String? = null,
    label: (@Composable () -> Unit)? = null,
) {
    androidx.compose.foundation.layout.Row(
        modifier
            .toggleable(
                value = isOn,
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                enabled = enabled,
                role = Role.Switch,
                onValueChange = onChange,
            )
            .then(if (tag != null) Modifier.testTag(tag) else Modifier),
        verticalAlignment = Alignment.CenterVertically,
        // SwiftUI's own gap between a toggle's label and its switch.
        horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp),
    ) {
        Box(Modifier.weight(1f)) {
            if (label != null) label() else com.junbingao.remotecontrol.android.design.Text(title, color = Theme.ink)
        }
        Switch(isOn, onChange, enabled = enabled)
    }
}

/** The switch's measurements, from the iPhone 17 reference screenshots. */
object SwitchMetrics {
    val width = 63.dp
    val height = 28.dp
    val thumbWidth = 37.dp
    val thumbHeight = 24.dp
    val inset = 2.dp

    val offTrack: Color @Composable @ReadOnlyComposable
        get() = if (LocalAppearance.current.isDark) Color(0xFF39393D) else Color(0xFFC5C5C7)
}
