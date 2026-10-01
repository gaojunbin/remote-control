package com.junbingao.remotecontrol.android.screens.devices

import androidx.compose.ui.graphics.Color
import com.junbingao.remotecontrol.android.icons.Sf
import com.junbingao.remotecontrol.android.icons.SfSymbol
import com.junbingao.remotecontrol.android.strings.L10n
import com.junbingao.remotecontrol.android.system.ActionRole
import com.junbingao.remotecontrol.android.system.MenuItem
import com.junbingao.remotecontrol.android.system.SwipeAction
import com.junbingao.remotecontrol.core.protocol.Device
import com.junbingao.remotecontrol.core.state.DeviceUpdate

/**
 * One action, wherever it is drawn. The swipe and the menu carry the same buttons, so there is one
 * place that decides what each one says and does (`DevicesView.button(_:for:)` on the iPhone).
 *
 * Retry update (A36): the gateway keeps every device on the wheel it serves, so there is nothing to
 * offer until one of those updates fails — only then is the action on the row, and it is drawn
 * disabled rather than disappearing again while the device is offline or no wheel is served.
 */
internal class DeviceRowActions(
    private val servedBuild: String?,
    private val tints: Tints,
    private val rename: (Device) -> Unit,
    private val retry: (Device) -> Unit,
    private val showQuota: (Device) -> Unit,
    private val revoke: (Device) -> Unit,
) {
    /**
     * The swipe's colours, read where the screen is drawn: Rename and Show quota grey, Retry the
     * accent, Revoke the danger red. The red is explicit, as on the iPhone, where the app's own
     * tint would otherwise take over a destructive swipe button.
     */
    data class Tints(val rename: Color, val retry: Color, val quota: Color, val revoke: Color)

    fun swipe(action: DeviceRowAction, device: Device): SwipeAction {
        val blocked = blocked(action, device)
        val tint = when (action) {
            DeviceRowAction.rename -> tints.rename
            DeviceRowAction.retryUpdate -> tints.retry
            DeviceRowAction.showQuota -> tints.quota
            DeviceRowAction.revoke -> tints.revoke
        }
        return SwipeAction(title(action), glyph(action), if (blocked == null) tint else tint.copy(alpha = DISABLED), action.identifier) {
            if (blocked == null) perform(action, device)
        }
    }

    fun menuItem(action: DeviceRowAction, device: Device): MenuItem = MenuItem.Action(
        title(action),
        glyph(action),
        role = if (action == DeviceRowAction.revoke) ActionRole.destructive else ActionRole.normal,
        enabled = blocked(action, device) == null,
        tag = action.identifier,
    ) { perform(action, device) }

    private fun perform(action: DeviceRowAction, device: Device) = when (action) {
        DeviceRowAction.rename -> rename(device)
        DeviceRowAction.retryUpdate -> retry(device)
        // Amendment A33's page, which the row's tap used to open.
        DeviceRowAction.showQuota -> showQuota(device)
        DeviceRowAction.revoke -> revoke(device)
    }

    /** Why Retry update cannot act on this machine; nothing stops the other three. */
    private fun blocked(action: DeviceRowAction, device: Device): DeviceUpdate.Block? =
        if (action == DeviceRowAction.retryUpdate) DeviceUpdate.block(device, servedBuild = servedBuild) else null

    private fun title(action: DeviceRowAction): String = when (action) {
        DeviceRowAction.rename -> L10n.string("Rename")
        DeviceRowAction.retryUpdate -> L10n.string("Retry update")
        DeviceRowAction.showQuota -> L10n.string("Show quota")
        DeviceRowAction.revoke -> L10n.string("Revoke")
    }

    private fun glyph(action: DeviceRowAction): SfSymbol = checkNotNull(Sf.named(action.symbol)) { "no symbol ${action.symbol}" }

    private companion object {
        /** A control that cannot act keeps its place at a fraction of its strength. */
        const val DISABLED = 0.4f
    }
}
