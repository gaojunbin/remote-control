package com.junbingao.remotecontrol.win.design.overlay

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.junbingao.remotecontrol.win.design.Button
import com.junbingao.remotecontrol.win.design.ButtonStyle
import com.junbingao.remotecontrol.win.design.Disabled
import com.junbingao.remotecontrol.win.design.HStack
import com.junbingao.remotecontrol.win.design.LocalIsEnabled
import com.junbingao.remotecontrol.win.design.MenuItemRow
import com.junbingao.remotecontrol.win.design.MenuList
import com.junbingao.remotecontrol.win.design.Palette
import com.junbingao.remotecontrol.win.design.icons.Icon
import com.junbingao.remotecontrol.win.design.icons.LucideIcon
import com.junbingao.remotecontrol.win.design.pill

/**
 * `web/src/components/Popover.tsx`: a trigger — a `.pill` unless the feature gives it another
 * style — with a 13 px chevron in the tertiary ink, and the panel it opens and closes. The panel
 * is placed against the window by `PopoverPlacement`, so no clipped surface or scrolling pane can
 * cut it off, and it closes on Escape, a press outside it and its trigger, a second press on the
 * trigger, or the `close` its content is handed.
 *
 * This one keeps its own open state; `initiallyOpen` is how a preview stage shows it open without
 * a click.
 */
@Composable
fun Popover(
    align: PopoverAlign = PopoverAlign.start,
    side: PopoverSide = PopoverSide.bottom,
    chevron: Boolean = true,
    triggerStyle: ButtonStyle = pill,
    ariaLabel: String? = null,
    initiallyOpen: Boolean = false,
    modifier: Modifier = Modifier,
    label: @Composable () -> Unit,
    content: @Composable (close: () -> Unit) -> Unit,
) {
    var open by remember { mutableStateOf(initiallyOpen) }
    Popover(open, { open = it }, align, side, chevron, triggerStyle, ariaLabel, modifier, label, content)
}

/** A popover whose open state belongs to the view that shows it. */
@Composable
fun Popover(
    isOpen: Boolean,
    onOpenChange: (Boolean) -> Unit,
    align: PopoverAlign = PopoverAlign.start,
    side: PopoverSide = PopoverSide.bottom,
    chevron: Boolean = true,
    triggerStyle: ButtonStyle = pill,
    ariaLabel: String? = null,
    modifier: Modifier = Modifier,
    label: @Composable () -> Unit,
    content: @Composable (close: () -> Unit) -> Unit,
) {
    val enabled = LocalIsEnabled.current
    LaunchedEffect(enabled) { if (!enabled) onOpenChange(false) }
    CompositionLocalProvider(LocalPopoverIsOpen provides isOpen) {
        Button(
            action = { onOpenChange(!isOpen) },
            modifier = modifier.anchoredPanel(isOpen, { onOpenChange(false) }, align, side) { content { onOpenChange(false) } },
            style = triggerStyle,
            accessibilityLabel = ariaLabel,
        ) {
            HStack(spacing = 6.dp) {
                label()
                if (chevron) Icon(LucideIcon.chevronDown, size = 13.dp, color = Palette.inkTertiary)
            }
        }
    }
}

/** One choice of a `SelectMenu`. */
data class MenuOption(val id: String, val label: String, val description: String? = null, val disabled: Boolean = false)

/**
 * `Menu` in `Popover.tsx`: a popover listing options as `.menu-item` rows, the current one
 * selected, which closes once one is chosen.
 */
@Composable
fun SelectMenu(
    options: List<MenuOption>,
    value: String?,
    ariaLabel: String,
    align: PopoverAlign = PopoverAlign.start,
    side: PopoverSide = PopoverSide.bottom,
    initiallyOpen: Boolean = false,
    modifier: Modifier = Modifier,
    onSelect: (String) -> Unit,
    label: @Composable () -> Unit,
) {
    Popover(align = align, side = side, ariaLabel = ariaLabel, initiallyOpen = initiallyOpen, modifier = modifier, label = label) { close ->
        MenuList {
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
