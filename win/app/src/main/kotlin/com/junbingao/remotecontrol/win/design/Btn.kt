package com.junbingao.remotecontrol.win.design

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.junbingao.remotecontrol.win.design.icons.Icon
import com.junbingao.remotecontrol.win.design.icons.LucideIcon

/**
 * `.btn`'s variants: the default tinted button, the one filled primary per surface, the danger
 * tint and the ghost (`docs/DESIGN.md` § "Buttons").
 */
enum class ButtonVariant { standard, primary, danger, ghost }

/** `.btn`, `.btn.small` and `.btn.block`. */
enum class ButtonSize(val height: Dp, val padding: Dp, val fontSize: Float) {
    regular(36.dp, Space.sp4, FontSize.fs14),
    small(28.dp, Space.sp3, FontSize.fs13),

    /** The full width of its container, one step taller: the sign-in button. */
    block(44.dp, Space.sp4, FontSize.fs15),
}

/**
 * `web/src/components/ui.css` `.btn`: a pill with no border, 500-weight type and a gap of 8
 * between an icon and its label. The pointer and a press tint it one step darker; disabled fades
 * it, except the primary, which turns grey.
 */
class BtnStyle(val variant: ButtonVariant = ButtonVariant.standard, val size: ButtonSize = ButtonSize.regular) : ButtonStyle {
    @Composable
    override fun Body(configuration: ButtonConfiguration, modifier: Modifier) {
        val reduceMotion = LocalReduceMotion.current
        val enabled = configuration.isEnabled
        // `:hover` and `:active` tint alike: a press is always under the pointer.
        val lit = enabled && (configuration.isHovered || configuration.isPressed)
        val fill by animateColorAsState(background(lit, enabled), Motion.ease(Motion.durFast, reduceMotion))
        val opacity by animateFloatAsState(
            if (enabled || variant == ButtonVariant.primary) 1f else 0.45f,
            Motion.ease(Motion.durFast, reduceMotion),
        )
        // `:focus-visible` gives every focused control a 4 px radius, the pill included, and an
        // ink outline that follows it.
        val shape = RoundedCornerShape(if (configuration.isFocused) 4.dp else size.height / 2)
        Row(
            modifier
                .then(if (size == ButtonSize.block) Modifier.fillMaxWidth() else Modifier)
                .height(size.height)
                .focusOutline(configuration.isFocused)
                .alpha(opacity)
                .background(fill, shape)
                .padding(horizontal = size.padding),
            horizontalArrangement = Arrangement.spacedBy(Space.sp2, Alignment.CenterHorizontally),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            CompositionLocalProvider(
                LocalContentColor provides foreground,
                LocalFont provides FontSpec(size.fontSize, FontWeight.Medium),
            ) { configuration.label() }
        }
    }

    private fun background(lit: Boolean, enabled: Boolean): Color = when (variant) {
        ButtonVariant.standard -> if (lit) Palette.surfaceActive else Palette.surfaceMuted
        ButtonVariant.primary -> if (!enabled) Color.hex(0xB9B9B4) else if (lit) Color.hex(0x262626) else Palette.ink
        ButtonVariant.danger -> if (lit) Color.hex(0xFBDEDB) else Palette.dangerSoft
        ButtonVariant.ghost -> if (lit) Palette.surfaceMuted else Color.Transparent
    }

    private val foreground: Color
        get() = when (variant) {
            ButtonVariant.standard, ButtonVariant.ghost -> Palette.ink
            ButtonVariant.primary -> Palette.inkInverse
            ButtonVariant.danger -> Palette.danger
        }
}

/** `.btn(…)`: `Button(…, style = btn(ButtonVariant.primary))`. */
fun btn(variant: ButtonVariant = ButtonVariant.standard, size: ButtonSize = ButtonSize.regular): ButtonStyle = BtnStyle(variant, size)

/**
 * `web/src/components/Button.tsx`: a `.btn` with an optional leading icon and the busy state — a
 * spinner before the label, and no second press.
 */
@Composable
fun Btn(
    title: String,
    icon: LucideIcon? = null,
    variant: ButtonVariant = ButtonVariant.standard,
    size: ButtonSize = ButtonSize.regular,
    busy: Boolean = false,
    modifier: Modifier = Modifier,
    action: () -> Unit,
) {
    Disabled(busy) {
        Button(action, modifier, style = btn(variant, size)) {
            if (busy) Spinner()
            if (icon != null) Icon(icon, size = if (size == ButtonSize.small) 14.dp else 15.dp)
            Text(title, css(size.fontSize, weight = FontWeight.Medium), Modifier.fillMaxHeight(), softWrap = false)
        }
    }
}
