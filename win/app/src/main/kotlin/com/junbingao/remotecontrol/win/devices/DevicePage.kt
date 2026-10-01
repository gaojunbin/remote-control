package com.junbingao.remotecontrol.win.devices

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.junbingao.remotecontrol.core.protocol.Device
import com.junbingao.remotecontrol.win.app.LocalAppModel
import com.junbingao.remotecontrol.win.app.LocalLayoutClass
import com.junbingao.remotecontrol.win.app.Route
import com.junbingao.remotecontrol.win.app.device
import com.junbingao.remotecontrol.win.design.Btn
import com.junbingao.remotecontrol.win.design.Button
import com.junbingao.remotecontrol.win.design.ButtonSize
import com.junbingao.remotecontrol.win.design.ButtonStyle
import com.junbingao.remotecontrol.win.design.Disabled
import com.junbingao.remotecontrol.win.design.EmptyState
import com.junbingao.remotecontrol.win.design.FontSize
import com.junbingao.remotecontrol.win.design.HStack
import com.junbingao.remotecontrol.win.design.Help
import com.junbingao.remotecontrol.win.design.Hint
import com.junbingao.remotecontrol.win.design.OnlineDot
import com.junbingao.remotecontrol.win.design.Palette
import com.junbingao.remotecontrol.win.design.Space
import com.junbingao.remotecontrol.win.design.Text
import com.junbingao.remotecontrol.win.design.VStack
import com.junbingao.remotecontrol.win.design.WithForeground
import com.junbingao.remotecontrol.win.design.card
import com.junbingao.remotecontrol.win.design.css
import com.junbingao.remotecontrol.win.design.icons.Icon
import com.junbingao.remotecontrol.win.design.icons.LucideIcon
import com.junbingao.remotecontrol.win.devices.page.AgentCard
import com.junbingao.remotecontrol.win.devices.page.DeviceQuota
import com.junbingao.remotecontrol.win.strings.S

/**
 * `/devices/:deviceId` — `web/src/features/devices/DevicePage.tsx` (A33): the machine's own facts,
 * then one card per coding agent it found, how each is signed in and what is left of its quota
 * (`docs/DESIGN.md` § "A device has a page").
 *
 * The hostname and the architecture live here alone: the row dropped both. No client version and
 * no build (A36). Rename and Revoke belong to the row and are not repeated; what is here is what
 * went wrong — the same "Updating…" and "Update failed" the row says, and the Retry a failure
 * earns.
 */
@Composable
fun DevicePage(deviceId: String) {
    val model = LocalAppModel.current
    val quota = remember { DeviceQuota() }
    val retrying = remember { mutableStateOf<Device?>(null) }
    val device = model.device(id = deviceId)
    VStack(Modifier.fillMaxWidth(), spacing = 0.dp, alignment = Alignment.Start) {
        DevicePageBack { model.router.go(Route.Devices) }
        if (device != null) {
            DevicePageHead(
                device, quota,
                updateError = model.deviceUpdateErrors[device.deviceID],
                servedBuild = model.connection.config.servedBuild,
                onRetry = { retrying.value = device },
            )
            Agents(device, quota)
        } else if (model.connection.hasSnapshot) {
            EmptyState(S.devicePage.goneHint, title = S.devicePage.gone, modifier = Modifier.card())
        }
    }
    RetryUpdateDialog(retrying)
    val online = device?.online ?: false
    LaunchedEffect(deviceId, online, quota.attempt) {
        quota.check(deviceID = deviceId, online = online, channel = model.connection.channel)
    }
}

@Composable
private fun Agents(device: Device, quota: DeviceQuota) {
    val agents = device.availableAgents
    if (agents.isEmpty()) {
        // `.device-page-none`'s 8 collapses into the head's 24.
        Hint(S.devices.noAgents)
    } else {
        VStack(Modifier.fillMaxWidth(), spacing = Space.sp3) {
            for (agent in agents) {
                key(agent.agent) { AgentCard(agent, accounts = quota.accounts[agent.agent] ?: agent.accounts, quota = quota) }
            }
        }
    }
}

/**
 * `.device-page-back`: the way back to the list, in the secondary ink that the pointer turns to
 * the ink, hung 4 px into the gutter.
 */
@Composable
private fun DevicePageBack(action: () -> Unit) {
    Button(action, Modifier.padding(bottom = Space.sp4).offset(x = (-4).dp), style = DevicePageBackStyle) {
        HStack(spacing = 2.dp) {
            Icon(LucideIcon.chevronLeft, size = 15.dp)
            Text(S.devicePage.back, css(FontSize.fs13))
        }
    }
}

private val DevicePageBackStyle = ButtonStyle { configuration, modifier ->
    WithForeground(if (configuration.isHovered) Palette.ink else Palette.inkSecondary) {
        Box(modifier) { configuration.label() }
    }
}

/**
 * The page's head: the online dot and the name as the title, the machine's facts in mono under it,
 * the update notice with its Retry while there is one, and Refresh at the trailing edge.
 */
@Composable
private fun DevicePageHead(device: Device, quota: DeviceQuota, updateError: String?, servedBuild: String?, onRetry: () -> Unit) {
    val titleSize = if (LocalLayoutClass.current.maxWidth760) FontSize.fs22 else FontSize.fs30
    HStack(Modifier.fillMaxWidth().padding(bottom = Space.sp6), spacing = Space.sp4, alignment = Alignment.Top) {
        VStack(Modifier.weight(1f), spacing = 0.dp, alignment = Alignment.Start) {
            HStack(spacing = Space.sp3) {
                OnlineDot(device.online)
                Text(device.name, css(titleSize, weight = FontWeight.SemiBold, tracking = -0.02f), Modifier.semantics { heading() })
            }
            Text(
                "${device.hostname} · ${device.platform.rawValue} · ${device.arch}",
                css(FontSize.fs13, mono = true),
                Modifier.padding(top = 2.dp),
                color = Palette.inkSecondary,
            )
            DeviceUpdateWords.notice(device, localError = updateError)?.let { NoticeLine(device, it, servedBuild, onRetry) }
        }
        Disabled(quota.status == DeviceQuota.Status.checking) {
            Btn(S.devicePage.refresh, icon = LucideIcon.refreshCw) { quota.refresh() }
        }
    }
}

@Composable
private fun NoticeLine(device: Device, notice: DeviceUpdateWords.Notice, servedBuild: String?, onRetry: () -> Unit) {
    val blocked = DeviceUpdateWords.retryBlocked(device, servedBuild)
    WrapRow(spacing = Space.sp3, lineSpacing = Space.sp2, centred = true, modifier = Modifier.padding(top = Space.sp2)) {
        Text(notice.text, css(FontSize.fs13), color = if (notice.failed) Palette.danger else Palette.inkSecondary)
        if (notice.failed) {
            Help(blocked ?: "") {
                Disabled(blocked != null) { Btn(S.devices.retryUpdate, size = ButtonSize.small, action = onRetry) }
            }
        }
    }
}
