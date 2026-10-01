package com.junbingao.remotecontrol.win.users

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.junbingao.remotecontrol.core.protocol.UserRecord
import com.junbingao.remotecontrol.core.protocol.UserState
import com.junbingao.remotecontrol.win.app.LocalLayoutClass
import com.junbingao.remotecontrol.win.design.FontSize
import com.junbingao.remotecontrol.win.design.HStack
import com.junbingao.remotecontrol.win.design.LocalReduceMotion
import com.junbingao.remotecontrol.win.design.LocalRowIsHovered
import com.junbingao.remotecontrol.win.design.MenuItemRow
import com.junbingao.remotecontrol.win.design.MenuList
import com.junbingao.remotecontrol.win.design.MenuTriggerStyle
import com.junbingao.remotecontrol.win.design.Motion
import com.junbingao.remotecontrol.win.design.Palette
import com.junbingao.remotecontrol.win.design.RowHeight
import com.junbingao.remotecontrol.win.design.Space
import com.junbingao.remotecontrol.win.design.Text
import com.junbingao.remotecontrol.win.design.VStack
import com.junbingao.remotecontrol.win.design.css
import com.junbingao.remotecontrol.win.design.icons.Icon
import com.junbingao.remotecontrol.win.design.icons.LucideIcon
import com.junbingao.remotecontrol.win.design.overlay.Popover
import com.junbingao.remotecontrol.win.design.overlay.PopoverAlign
import com.junbingao.remotecontrol.win.settings.SettingsFlexWrap
import com.junbingao.remotecontrol.win.shared.Format
import com.junbingao.remotecontrol.win.strings.S

/**
 * `UserRow.tsx` / `.account-row`: the username, `role · state`, the devices it enrolled and its
 * last sign-in, and the row's menu — Reset password, Disable or Enable, Delete. `admin` is the
 * operator: none of the three is allowed on it, so it is given no menu.
 */
@Composable
fun UserRow(
    user: UserRecord,
    actionable: Boolean,
    /** A render's stage opens this row's menu. */
    menuOpen: Boolean,
    onResetPassword: () -> Unit,
    onToggleState: () -> Unit,
    onDelete: () -> Unit,
) {
    val layout = LocalLayoutClass.current
    val reduceMotion = LocalReduceMotion.current
    val source = remember { MutableInteractionSource() }
    val hovered by source.collectIsHoveredAsState()
    val fill by animateColorAsState(if (hovered) Palette.hover else Color.Transparent, Motion.ease(Motion.durFast, reduceMotion))
    val disabled = user.state == UserState.disabled
    CompositionLocalProvider(LocalRowIsHovered provides hovered) {
        HStack(
            Modifier
                .fillMaxWidth()
                .heightIn(min = RowHeight.rowH)
                .background(fill)
                .hoverable(source)
                .padding(horizontal = if (layout.maxWidth640) Space.sp4 else Space.sp5),
            spacing = Space.sp4,
        ) {
            VStack(Modifier.weight(1f), spacing = 0.dp, alignment = Alignment.Start) {
                Text(
                    user.username,
                    css(FontSize.fs15, weight = FontWeight.SemiBold, lineHeight = 1.4f, tracking = -0.01f),
                    // A disabled account stays legible and stops looking current.
                    color = if (disabled) Palette.inkSecondary else Palette.ink,
                )
                UserMetaLine(user, Modifier.padding(top = 1.dp))
            }
            if (actionable) UserMenu(disabled, menuOpen, onResetPassword, onToggleState, onDelete)
        }
    }
}

@Composable
private fun UserMenu(disabled: Boolean, menuOpen: Boolean, onResetPassword: () -> Unit, onToggleState: () -> Unit, onDelete: () -> Unit) {
    Popover(
        align = PopoverAlign.end,
        chevron = false,
        triggerStyle = MenuTriggerStyle(),
        ariaLabel = S.a11y.openMenu,
        initiallyOpen = menuOpen,
        label = { Icon(LucideIcon.moreHorizontal, size = 16.dp) },
    ) { close ->
        MenuList {
            MenuItemRow(S.users.resetPassword) {
                close()
                onResetPassword()
            }
            MenuItemRow(if (disabled) S.users.enable else S.users.disable) {
                close()
                onToggleState()
            }
            MenuItemRow(S.users.deleteAction, danger = true) {
                close()
                onDelete()
            }
        }
    }
}

/**
 * `.account-meta`: `role · state`, then the devices and the last sign-in in the tertiary ink
 * behind a faint dot — two spans that each keep to one line and wrap under each other when the
 * row is too narrow for both.
 */
@Composable
fun UserMetaLine(user: UserRecord, modifier: Modifier = Modifier) {
    val lastSignIn = S.users.lastSignIn(user.lastLoginAt?.let { Format.relativeAgo(it) } ?: S.users.never)
    SettingsFlexWrap(modifier, spacing = Space.sp2, lineSpacing = 2.dp) {
        Meta(S.users.meta(S.roleLabel(user.role.rawValue), S.userStateLabel(user.state.rawValue)), Palette.inkSecondary)
        HStack(spacing = Space.sp2) {
            Meta("·", Palette.lineStrong)
            Meta("${S.users.deviceCount(user.devices)} · $lastSignIn", Palette.inkTertiary)
        }
    }
}

@Composable
private fun Meta(text: String, color: Color) {
    Text(text, css(FontSize.fs12, lineHeight = 1.45f), color = color, lineLimit = 1)
}
