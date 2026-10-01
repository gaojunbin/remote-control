package com.junbingao.remotecontrol.win.devices

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.junbingao.remotecontrol.win.design.Disabled
import com.junbingao.remotecontrol.win.design.MenuItemRow
import com.junbingao.remotecontrol.win.design.MenuList
import com.junbingao.remotecontrol.win.design.MenuTriggerStyle
import com.junbingao.remotecontrol.win.design.icons.Icon
import com.junbingao.remotecontrol.win.design.icons.LucideIcon
import com.junbingao.remotecontrol.win.design.overlay.Popover
import com.junbingao.remotecontrol.win.design.overlay.PopoverAlign
import com.junbingao.remotecontrol.win.strings.S

/**
 * The row's menu, behind its three dots, in one order (A38, rule 20): Rename · Retry update (only
 * while an update has failed, disabled with the reason while it cannot be sent) · Show quota ·
 * Revoke.
 */
@Composable
internal fun DeviceRowMenu(
    /** Why Retry update cannot be pressed; null when it can. */
    retryBlocked: String?,
    failed: Boolean,
    initiallyOpen: Boolean,
    onRename: () -> Unit,
    onRetryUpdate: () -> Unit,
    onShowQuota: () -> Unit,
    onRevoke: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Popover(
        align = PopoverAlign.end,
        chevron = false,
        triggerStyle = MenuTriggerStyle(),
        ariaLabel = S.a11y.openMenu,
        initiallyOpen = initiallyOpen,
        modifier = modifier,
        label = { Icon(LucideIcon.moreHorizontal, size = 16.dp) },
    ) { close ->
        MenuList {
            MenuItemRow(S.common.rename) {
                close()
                onRename()
            }
            if (failed) {
                Disabled(retryBlocked != null) {
                    MenuItemRow(S.devices.retryUpdate, help = retryBlocked) {
                        close()
                        onRetryUpdate()
                    }
                }
            }
            // A33's page, reached from here now that the row itself opens a shell: the agents on
            // the machine and what is left of each quota.
            MenuItemRow(S.devices.showQuota) {
                close()
                onShowQuota()
            }
            MenuItemRow(S.common.revoke, danger = true) {
                close()
                onRevoke()
            }
        }
    }
}
