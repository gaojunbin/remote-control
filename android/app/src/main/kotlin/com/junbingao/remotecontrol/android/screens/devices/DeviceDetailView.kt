package com.junbingao.remotecontrol.android.screens.devices

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.junbingao.remotecontrol.android.design.Button
import com.junbingao.remotecontrol.android.design.Text
import com.junbingao.remotecontrol.android.design.Theme
import com.junbingao.remotecontrol.android.design.scrollIndicator
import com.junbingao.remotecontrol.android.design.weight
import com.junbingao.remotecontrol.android.navigation.NavigationMetrics
import com.junbingao.remotecontrol.android.navigation.NavigationScreen
import com.junbingao.remotecontrol.android.navigation.TitleDisplayMode
import com.junbingao.remotecontrol.android.shell.AppModel
import com.junbingao.remotecontrol.android.shell.LocalAppModel
import com.junbingao.remotecontrol.android.strings.L10n
import com.junbingao.remotecontrol.android.system.ActionRole
import com.junbingao.remotecontrol.android.system.Alert
import com.junbingao.remotecontrol.android.system.AlertAction
import com.junbingao.remotecontrol.core.protocol.AgentAccount
import com.junbingao.remotecontrol.core.protocol.AgentInfo
import com.junbingao.remotecontrol.core.protocol.AgentsResult
import com.junbingao.remotecontrol.core.protocol.Device
import com.junbingao.remotecontrol.core.protocol.GatewayErrorBody
import com.junbingao.remotecontrol.core.protocol.GatewayErrorCode
import com.junbingao.remotecontrol.core.protocol.GatewayRequest
import com.junbingao.remotecontrol.core.state.DeviceUpdate
import com.junbingao.remotecontrol.core.state.request
import kotlinx.coroutines.CancellationException

/**
 * Amendment A33: one machine's page — what it is, which coding agents are on it, how each one is
 * signed in and what is left of its quota.
 *
 * `docs/DESIGN.md` § "A device has a page". The credentials are already in the device list, so
 * they are on screen the moment the page opens; the windows are read on request, so the page asks
 * for them itself and says "Checking…" where the meters go until the answer arrives. What that
 * answer brings stays here: the stored device keeps its accounts without windows, so the list
 * behind this page never redraws because a percentage moved.
 *
 * The row's Rename and Revoke are not repeated here. Retry update is, because it belongs to the
 * notice it stands beside (`docs/DESIGN.md` § "A device keeps itself current").
 */
@Composable
fun DeviceDetailView(deviceID: String) {
    val model = LocalAppModel.current
    var fresh by remember { mutableStateOf<Map<String, List<AgentAccount>>>(emptyMap()) }
    var phase by remember { mutableStateOf(QuotaPhase.checking) }
    var failure by remember { mutableStateOf<String?>(null) }
    var isRetrying by remember { mutableStateOf(false) }
    val device = model.connection.device(deviceID)

    // Ask the device what its agents are signed in with and what they have spent. An offline
    // machine is not asked at all: the accounts on screen are its last word, and the meters say so
    // rather than failing.
    suspend fun read() {
        val current = model.connection.device(deviceID) ?: return
        val channel = model.connection.channel ?: return
        failure = null
        if (!current.online) {
            phase = QuotaPhase.offline
            return
        }
        phase = if (fresh.isEmpty()) QuotaPhase.checking else QuotaPhase.ready
        try {
            val result = channel.request(GatewayRequest.agents(deviceID = deviceID), AgentsResult.serializer())
            // An agent the reply says nothing about keeps what the list stored, so "the device did
            // not look" stays what it was rather than becoming "signed in nowhere".
            fresh = result.agents.mapNotNull { agent -> agent.accounts?.let { agent.agent to it } }.toMap()
            phase = QuotaPhase.ready
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (refusal: GatewayErrorBody) {
            if (refusal.code == GatewayErrorCode.deviceOffline) {
                phase = QuotaPhase.offline
            } else {
                phase = QuotaPhase.unavailable
                failure = model.connection.message(refusal)
            }
        } catch (error: Exception) {
            phase = QuotaPhase.unavailable
            failure = model.connection.message(error)
        }
    }

    LaunchedEffect(deviceID) { read() }

    NavigationScreen(device?.name ?: "", displayMode = TitleDisplayMode.inline) { insets ->
        Refreshable(onRefresh = { read() }, top = insets.top) {
            val scroll = rememberScrollState()
            Column(
                Modifier
                    .fillMaxSize()
                    .scrollIndicator(scroll, top = insets.top, bottom = insets.bottom)
                    .verticalScroll(scroll)
                    .padding(top = insets.top + NavigationMetrics.barFoot, bottom = insets.bottom)
                    .padding(Theme.Space.page)
                    .testTag("device.page"),
                verticalArrangement = Arrangement.spacedBy(Theme.Space.medium),
            ) {
                if (device != null) {
                    Header(model, device, failure) { isRetrying = true }
                    Agents(device, phase) { info -> fresh[info.agent] ?: info.accounts }
                } else {
                    Text(L10n.string("That device is no longer on this gateway."), style = Theme.Text.meta, color = Theme.inkSecondary)
                }
            }
        }
    }

    Alert(
        isRetrying,
        L10n.string("Update device"),
        onDismiss = { isRetrying = false },
        message = DeviceUpdateText.confirmation(device?.name ?: L10n.string("This device"), model.connection.config.servedVersion),
        actions = listOf(
            AlertAction(L10n.string("Cancel"), ActionRole.cancel, tag = "alert.cancel") { isRetrying = false },
            // A refused retry is the page's own news, not the gateway's: nothing started, so no
            // `device.updated` will ever carry it.
            AlertAction(L10n.string("Update"), tag = "device.update.confirm") {
                isRetrying = false
                device?.let { model.perform { updateDevice(it) } }
            },
        ),
    )
}

/**
 * The machine as its row words it, minus the name the navigation bar is already carrying, plus the
 * facts the row no longer carries: the hostname and the architecture are checked here or nowhere
 * (`docs/DESIGN.md` § "The device row").
 */
@Composable
private fun Header(model: AppModel, device: Device, failure: String?, retry: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(Theme.Space.small)) {
            DeviceStatusLine(device, Modifier.weight(1f).alignByBaseline())
            Text(trailing(device), Modifier.alignByBaseline(), style = Theme.Text.caption, color = Theme.inkSecondary)
        }
        DeviceFactsLine(device)
        UpdateLine(model, device, retry)
        failure?.let { Text(it, Modifier.testTag("device.quota.failure"), style = Theme.Text.caption, color = Theme.inkSecondary) }
    }
}

/**
 * The page says no more about the client than the row does (A36): what is happening to it, and —
 * where the gateway gave up — a way to ask again. A machine being kept current draws no line here
 * at all.
 */
@Composable
private fun UpdateLine(model: AppModel, device: Device, retry: () -> Unit) {
    val notice = DeviceUpdate.notice(device, localError = model.deviceUpdateError(device.deviceID)) ?: return
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Theme.Space.small), verticalAlignment = Alignment.CenterVertically) {
        DeviceUpdateLine(notice)
        if (DeviceUpdate.canRetry(device)) RetryButton(model, device, retry)
    }
}

@Composable
private fun RetryButton(model: AppModel, device: Device, retry: () -> Unit) {
    val blocked = DeviceUpdate.block(device, servedBuild = model.connection.config.servedBuild)
    Button(
        onClick = retry,
        Modifier
            .alpha(if (blocked == null) 1f else 0.4f)
            .semantics { blocked?.let { stateDescription = DeviceUpdateText.reason(it) } }
            .testTag("device.retryUpdate"),
        enabled = blocked == null,
    ) {
        Text(L10n.string("Retry update"), style = Theme.Text.caption.weight(FontWeight.Medium), color = Theme.accent)
    }
}

/** [accounts]: the fresh credentials where the reply brought them, the stored ones until then. An agent the device did not look at has none either way. */
@Composable
private fun Agents(device: Device, phase: QuotaPhase, accounts: (AgentInfo) -> List<AgentAccount>?) {
    val agents = device.availableAgents
    if (agents.isEmpty()) {
        Text(L10n.string("This machine reported no agents."), Modifier.testTag("device.noAgents"), style = Theme.Text.meta, color = Theme.inkSecondary)
        return
    }
    // The device's own order, which is the order its row lists them in.
    for (info in agents) AgentAccountCard(info, accounts(info), phase)
}
