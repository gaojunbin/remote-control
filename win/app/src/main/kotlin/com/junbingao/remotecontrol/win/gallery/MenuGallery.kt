package com.junbingao.remotecontrol.win.gallery

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.junbingao.remotecontrol.win.design.MenuItemRow
import com.junbingao.remotecontrol.win.design.MenuList
import com.junbingao.remotecontrol.win.design.MenuTriggerStyle
import com.junbingao.remotecontrol.win.design.Palette
import com.junbingao.remotecontrol.win.design.icons.Icon
import com.junbingao.remotecontrol.win.design.icons.LucideIcon
import com.junbingao.remotecontrol.win.design.overlay.Popover
import com.junbingao.remotecontrol.win.design.overlay.PopoverAlign
import com.junbingao.remotecontrol.win.strings.S

/**
 * A row menu open, where the web's Devices page has the first row's: the three-dot trigger 28 px
 * square at (1108, 202), the panel hung from its trailing edge. The reference for every row menu
 * the lists draw.
 */
@Composable
fun MenuGallery() {
    Box(Modifier.fillMaxSize().background(Palette.canvas)) {
        Popover(
            align = PopoverAlign.end,
            chevron = false,
            triggerStyle = MenuTriggerStyle(),
            ariaLabel = S.a11y.openMenu,
            initiallyOpen = true,
            modifier = Modifier.offset(x = 1108.dp, y = 202.dp),
            label = { Icon(LucideIcon.moreHorizontal, size = 16.dp) },
        ) { close ->
            MenuList {
                MenuItemRow(S.common.rename) { close() }
                MenuItemRow(S.devices.retryUpdate) { close() }
                MenuItemRow(S.devices.showQuota) { close() }
                MenuItemRow(S.common.revoke, danger = true) { close() }
            }
        }
    }
}
