package com.junbingao.remotecontrol.win.settings

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.junbingao.remotecontrol.win.app.LocalLayoutClass
import com.junbingao.remotecontrol.win.design.Button
import com.junbingao.remotecontrol.win.design.ButtonConfiguration
import com.junbingao.remotecontrol.win.design.ButtonStyle
import com.junbingao.remotecontrol.win.design.FontSize
import com.junbingao.remotecontrol.win.design.HStack
import com.junbingao.remotecontrol.win.design.LocalReduceMotion
import com.junbingao.remotecontrol.win.design.Motion
import com.junbingao.remotecontrol.win.design.Palette
import com.junbingao.remotecontrol.win.design.RowHeight
import com.junbingao.remotecontrol.win.design.Space
import com.junbingao.remotecontrol.win.design.Text
import com.junbingao.remotecontrol.win.design.VStack
import com.junbingao.remotecontrol.win.design.css
import com.junbingao.remotecontrol.win.design.focusOutline
import com.junbingao.remotecontrol.win.design.icons.Icon
import com.junbingao.remotecontrol.win.design.icons.LucideIcon

/**
 * `SettingsRow.tsx`: one settings row — a title, one sentence under it, and the control at the
 * trailing edge (`docs/DESIGN.md` § "The Settings screen"). No rule parts two rows and nothing is
 * drawn beside a row for a state: a row whose state has something to say says it in place of its
 * sentence.
 *
 * `target` is a row a click anywhere on reaches: the ruling gives this to a menu and to a switch,
 * never to a segmented control, where there is no one control to reach. The control keeps its own
 * click. `reach` is what a click on the row does for its control, or null while the control has
 * nothing to do, which leaves the row inert.
 */
@Composable
fun SettingsRow(
    title: String,
    sentence: String,
    target: Boolean = false,
    reach: (() -> Unit)? = null,
    control: @Composable () -> Unit,
) {
    val layout = LocalLayoutClass.current
    val reduceMotion = LocalReduceMotion.current
    val source = remember { MutableInteractionSource() }
    val hovered by source.collectIsHoveredAsState()
    val fill by animateColorAsState(if (target && hovered) Palette.hover else Color.Transparent, Motion.ease(Motion.durFast, reduceMotion))
    val currentReach by rememberUpdatedState(reach)
    HStack(
        Modifier
            .settingsRowFrame()
            .background(fill)
            .hoverable(source)
            .then(
                if (target) {
                    // A press the control took is the control's; the row answers the rest.
                    Modifier.pointerHoverIcon(PointerIcon.Hand).pointerInput(Unit) { detectTapGestures { currentReach?.invoke() } }
                } else {
                    Modifier
                },
            )
            .settingsRowPadding(compact = layout.maxWidth640),
        spacing = Space.sp4,
    ) {
        SettingsRowText(title, sentence, modifier = Modifier.weight(1f))
        control()
    }
}

/**
 * `SettingsActionRow`: a row that is the action itself — the whole row is the button, with a
 * chevron where it leads somewhere and none for Sign out, which takes something away rather than
 * leading anywhere.
 */
@Composable
fun SettingsActionRow(title: String, sentence: String, danger: Boolean = false, onPress: () -> Unit) {
    Button(onPress, Modifier.fillMaxWidth(), style = SettingsActionRowStyle, accessibilityLabel = title) {
        HStack(Modifier.fillMaxWidth(), spacing = Space.sp4) {
            SettingsRowText(title, sentence, danger, Modifier.weight(1f))
            if (!danger) Icon(LucideIcon.chevronRight, size = 16.dp, color = Palette.inkTertiary)
        }
    }
}

private object SettingsActionRowStyle : ButtonStyle {
    @Composable
    override fun Body(configuration: ButtonConfiguration, modifier: Modifier) {
        val layout = LocalLayoutClass.current
        val reduceMotion = LocalReduceMotion.current
        val fill by animateColorAsState(if (configuration.isHovered) Palette.hover else Color.Transparent, Motion.ease(Motion.durFast, reduceMotion))
        HStack(
            modifier
                .settingsRowFrame()
                .focusOutline(configuration.isFocused)
                .background(fill)
                .settingsRowPadding(compact = layout.maxWidth640),
            spacing = 0.dp,
        ) { configuration.label() }
    }
}

/** `.settings-row-text`: the title in the label weight over its sentence. */
@Composable
fun SettingsRowText(title: String, sentence: String, danger: Boolean = false, modifier: Modifier = Modifier) {
    VStack(modifier, spacing = 1.dp, alignment = Alignment.Start) {
        Text(title, css(FontSize.fs15, weight = FontWeight.SemiBold, lineHeight = 1.35f), color = if (danger) Palette.danger else Palette.ink)
        Text(sentence, css(FontSize.fs13, lineHeight = 1.45f), color = Palette.inkSecondary)
    }
}

/**
 * `.settings-row`'s box: as wide as its surface and 56 high at least — a floor, never a clip. The
 * row is as tall as it needs whatever height it is offered, and what it holds is centred in it.
 */
private fun Modifier.settingsRowFrame(): Modifier = fillMaxWidth().heightIn(min = RowHeight.rowHSetting)

/** 12 above and below, 20 at the sides (16 at 640 and narrower). */
private fun Modifier.settingsRowPadding(compact: Boolean): Modifier =
    padding(vertical = Space.sp3, horizontal = if (compact) Space.sp4 else Space.sp5)
