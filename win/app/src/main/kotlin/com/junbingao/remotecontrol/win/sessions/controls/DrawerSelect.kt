package com.junbingao.remotecontrol.win.sessions.controls

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.Row
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
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.junbingao.remotecontrol.win.design.ButtonConfiguration
import com.junbingao.remotecontrol.win.design.ButtonStyle
import com.junbingao.remotecontrol.win.design.Disabled
import com.junbingao.remotecontrol.win.design.FontSize
import com.junbingao.remotecontrol.win.design.FontSpec
import com.junbingao.remotecontrol.win.design.HStack
import com.junbingao.remotecontrol.win.design.LocalContentColor
import com.junbingao.remotecontrol.win.design.LocalFont
import com.junbingao.remotecontrol.win.design.LocalReduceMotion
import com.junbingao.remotecontrol.win.design.MenuItemRow
import com.junbingao.remotecontrol.win.design.MenuList
import com.junbingao.remotecontrol.win.design.Motion
import com.junbingao.remotecontrol.win.design.Palette
import com.junbingao.remotecontrol.win.design.Radius
import com.junbingao.remotecontrol.win.design.Space
import com.junbingao.remotecontrol.win.design.focusOutline
import com.junbingao.remotecontrol.win.design.icons.Icon
import com.junbingao.remotecontrol.win.design.icons.LucideIcon
import com.junbingao.remotecontrol.win.design.overlay.MenuOption
import com.junbingao.remotecontrol.win.design.overlay.Popover
import com.junbingao.remotecontrol.win.design.overlay.PopoverAlign

/**
 * The New session drawer's menus: the web's `Menu` with its pill stretched to the drawer's width,
 * 46 px tall on a 12 px radius, the choice at the leading edge and the chevron at the trailing one
 * (`.drawer .popover-root > .pill`). The list it opens is `SelectMenu`'s.
 */
@Composable
internal fun DrawerSelect(
    options: List<MenuOption>,
    value: String?,
    ariaLabel: String,
    align: PopoverAlign = PopoverAlign.start,
    initiallyOpen: Boolean = false,
    onSelect: (String) -> Unit,
    label: @Composable () -> Unit,
) {
    // The trigger draws its own chevron: the choice takes every point the chevron leaves it. The
    // label is as tall as the pill, so a line in it centres itself on the point the Mac's does.
    Popover(
        align = align,
        chevron = false,
        triggerStyle = DrawerSelectStyle,
        ariaLabel = ariaLabel,
        initiallyOpen = initiallyOpen,
        modifier = Modifier.fillMaxWidth(),
        label = {
            HStack(Modifier.fillMaxWidth().fillMaxHeight(), spacing = 6.dp) {
                Box(Modifier.weight(1f).fillMaxHeight(), contentAlignment = Alignment.CenterStart) { label() }
                Icon(LucideIcon.chevronDown, size = 13.dp, color = Palette.inkTertiary)
            }
        },
    ) { close ->
        MenuList(Modifier.semantics { contentDescription = ariaLabel }) {
            for (option in options) {
                Disabled(option.disabled) {
                    MenuItemRow(option.label, description = option.description, selected = value == option.id) {
                        onSelect(option.id)
                        close()
                    }
                }
            }
        }
    }
}

/** `.pill` as the drawer draws it. */
internal val DrawerSelectStyle = ButtonStyle { configuration, modifier -> DrawerSelectBody(configuration, modifier) }

@Composable
private fun DrawerSelectBody(configuration: ButtonConfiguration, modifier: Modifier) {
    val enabled = configuration.isEnabled
    val lit = enabled && (configuration.isHovered || configuration.isPressed)
    val fill by animateColorAsState(if (lit) Palette.surfaceActive else Palette.surfaceMuted, Motion.ease(Motion.durFast, LocalReduceMotion.current))
    val shape = RoundedCornerShape(if (configuration.isFocused) 4.dp else Radius.md)
    Row(
        modifier
            .fillMaxWidth()
            .height(46.dp)
            .focusOutline(configuration.isFocused)
            .alpha(if (enabled) 1f else 0.6f)
            .background(fill, shape)
            .padding(horizontal = Space.sp4),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CompositionLocalProvider(
            LocalContentColor provides if (enabled) Palette.ink else Palette.inkTertiary,
            LocalFont provides FontSpec(FontSize.fs13),
        ) { configuration.label() }
    }
}
