package com.junbingao.remotecontrol.win.design

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.junbingao.remotecontrol.win.design.overlay.LocalPopoverIsOpen

/**
 * `.menu`: the list inside a popover panel, every row of it. The web scrolls it past 320 px; the
 * Mac's scrolls only when its rows do not fit the height it is offered, and a popover offers the
 * whole height its content asks for, so the Mac's menus never scroll and neither do these.
 */
@Composable
fun MenuList(modifier: Modifier = Modifier, content: @Composable VStackScope.() -> Unit) {
    VStack(modifier, spacing = 0.dp, alignment = Alignment.Start, content = content)
}

/**
 * `.menu-item`: a row of a menu — a button, or a link when it goes somewhere, both drawn alike —
 * with a 14 px label and an optional 12 px line under it. The pointer tints it `--surface-hover`,
 * the selected one holds `--surface-muted`, and a disabled one fades. `.danger-text` turns the
 * label the danger ink.
 */
@Composable
fun MenuItemRow(
    label: String,
    description: String? = null,
    selected: Boolean = false,
    danger: Boolean = false,
    help: String? = null,
    modifier: Modifier = Modifier,
    action: () -> Unit,
) {
    Help(help ?: "") {
        Button(action, modifier, style = MenuItemStyle(selected)) {
            VStack(spacing = 2.dp, alignment = Alignment.Start) {
                Text(label, css(FontSize.fs14), color = if (danger) Palette.danger else Palette.ink)
                if (description != null) Text(description, css(FontSize.fs12), color = Palette.inkSecondary)
            }
        }
    }
}

internal class MenuItemStyle(val selected: Boolean) : ButtonStyle {
    @Composable
    override fun Body(configuration: ButtonConfiguration, modifier: Modifier) {
        val fill = when {
            configuration.isEnabled && configuration.isHovered -> Palette.surfaceHover
            selected -> Palette.surfaceMuted
            else -> Color.Transparent
        }
        Box(
            modifier
                .fillMaxWidth()
                .alpha(if (configuration.isEnabled) 1f else 0.45f)
                .background(fill, RoundedCornerShape(Radius.sm))
                .padding(vertical = Space.sp2, horizontal = 10.dp),
            contentAlignment = Alignment.CenterStart,
        ) { configuration.label() }
    }
}

/**
 * The three-dot trigger a device row and an account row open their menu with
 * (`.device-menu-trigger`, `.account-menu-trigger`): a 28 px circle with no tint, the icon in the
 * tertiary ink until the row or the pointer is over it or its menu is open, and the pill's hover
 * tint under the pointer.
 */
class MenuTriggerStyle : ButtonStyle {
    @Composable
    override fun Body(configuration: ButtonConfiguration, modifier: Modifier) {
        val lit = configuration.isEnabled && (configuration.isHovered || configuration.isPressed)
        val ink = if (lit || LocalPopoverIsOpen.current || LocalRowIsHovered.current) Palette.ink else Palette.inkTertiary
        Box(
            modifier.size(28.dp).background(if (lit) Palette.surfaceActive else Color.Transparent, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            WithForeground(ink) { configuration.label() }
        }
    }
}

/**
 * Whether the list row around a control is under the pointer, for the controls a row's hover
 * lights up (`.device-row:hover .device-menu-trigger`).
 */
val LocalRowIsHovered = compositionLocalOf { false }
