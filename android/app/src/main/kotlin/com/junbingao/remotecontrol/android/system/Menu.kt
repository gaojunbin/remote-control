package com.junbingao.remotecontrol.android.system

import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import com.junbingao.remotecontrol.android.icons.SfSymbol

/** One row of a menu, a group of them, or the line between groups. */
sealed interface MenuItem {
    /**
     * A choice: its words, an optional symbol or image (an agent's logo) at the leading edge, a
     * checkmark where it is the one chosen, and red when it destroys something.
     */
    data class Action(
        val title: String,
        val symbol: SfSymbol? = null,
        val image: ImageVector? = null,
        val role: ActionRole = ActionRole.normal,
        val checked: Boolean = false,
        val enabled: Boolean = true,
        val tag: String? = null,
        val action: () -> Unit = {},
    ) : MenuItem

    /** A group, with an optional header, set apart from the rows around it. */
    data class Section(val title: String? = null, val items: List<MenuItem>) : MenuItem

    data object Divider : MenuItem
}

/**
 * SwiftUI's `Menu { … } label: { … }`: tapping [label] opens the menu over it, grown out of the
 * button as iOS 26's is — down from a button near the top, up from one near the foot of the
 * screen ([MenuPlacement]). A `Picker` inside a menu is rows with the chosen one checked.
 */
@Composable
fun Menu(items: List<MenuItem>, modifier: Modifier = Modifier, enabled: Boolean = true, label: @Composable () -> Unit) {
    var open by remember { mutableStateOf(false) }
    var anchor by remember { mutableStateOf<Rect?>(null) }
    Box(
        modifier
            .onGloballyPositioned { anchor = it.boundsInRoot() }
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                enabled = enabled,
                role = Role.DropdownList,
            ) { open = true },
    ) { label() }
    MenuPresentation(open, anchor, items, dimmed = false) { open = false }
}

/**
 * `.contextMenu { … }`: a long press opens the menu beside the element, over a dimmed screen,
 * with the haptic a long press gives. A tap is left to [onClick], so a row can both open and
 * offer its menu.
 */
fun Modifier.contextMenu(items: List<MenuItem>, onClick: (() -> Unit)? = null): Modifier = composed {
    var open by remember { mutableStateOf(false) }
    var anchor by remember { mutableStateOf<Rect?>(null) }
    val haptics = LocalHapticFeedback.current
    MenuPresentation(open, anchor, items, dimmed = true) { open = false }
    onGloballyPositioned { anchor = it.boundsInRoot() }
        .combinedClickable(
            interactionSource = remember { MutableInteractionSource() },
            indication = null,
            onLongClick = {
                if (items.isNotEmpty()) {
                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                    open = true
                }
            },
            onClick = { onClick?.invoke() },
        )
}

@Composable
private fun MenuPresentation(open: Boolean, anchor: Rect?, items: List<MenuItem>, dimmed: Boolean, close: () -> Unit) {
    Present(
        PresentationKind.menu,
        isPresented = open,
        onDismiss = close,
        options = PresentationOptions(anchor = anchor, dismissible = true, dimmed = dimmed),
    ) {
        MenuCard(items, close)
    }
}
