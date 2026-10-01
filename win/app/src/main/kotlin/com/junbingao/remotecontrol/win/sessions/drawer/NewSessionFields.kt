package com.junbingao.remotecontrol.win.sessions.drawer

import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import com.junbingao.remotecontrol.core.protocol.AgentInfo
import com.junbingao.remotecontrol.core.protocol.Device
import com.junbingao.remotecontrol.core.state.trimmed
import com.junbingao.remotecontrol.win.app.LocalAppModel
import com.junbingao.remotecontrol.win.app.WinAppModel
import com.junbingao.remotecontrol.win.design.AgentLogo
import com.junbingao.remotecontrol.win.design.Disabled
import com.junbingao.remotecontrol.win.design.FieldLabel
import com.junbingao.remotecontrol.win.design.FontSize
import com.junbingao.remotecontrol.win.design.HStack
import com.junbingao.remotecontrol.win.design.LocalPreviewStage
import com.junbingao.remotecontrol.win.design.OnlineDot
import com.junbingao.remotecontrol.win.design.Palette
import com.junbingao.remotecontrol.win.design.SegmentOption
import com.junbingao.remotecontrol.win.design.Segmented
import com.junbingao.remotecontrol.win.design.Space
import com.junbingao.remotecontrol.win.design.Text
import com.junbingao.remotecontrol.win.design.VStack
import com.junbingao.remotecontrol.win.design.css
import com.junbingao.remotecontrol.win.design.overlay.MenuOption
import com.junbingao.remotecontrol.win.devices.DeviceOrder
import com.junbingao.remotecontrol.win.sessions.controls.DrawerSelect
import com.junbingao.remotecontrol.win.shared.Format
import com.junbingao.remotecontrol.win.strings.S
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * The drawer's sections, 20 px apart: the device with its latency, the agent as a segmented
 * control of logos, the three lists the agent offers and its speed, the working directory with the
 * recent ones, and git.
 */
@Composable
internal fun NewSessionFields(form: NewSessionForm) {
    val model = LocalAppModel.current
    val stage = LocalPreviewStage.current
    val browser = remember { mutableStateOf<DirectoryBrowser?>(null) }
    val devices = DeviceOrder.online(model.connection.devices)
    val device = form.device(devices)
    val agent = form.agent(of = device)

    // Browse opens the picker on the path in the field, or on the home.
    fun browse() {
        val deviceID = form.deviceID ?: return
        val opened = DirectoryBrowser(deviceID = deviceID, channel = model.connection.channel)
        val start = form.cwd.trimmed
        browser.value = opened
        model.tasks.launch { opened.open(start.ifEmpty { null }) }
    }

    VStack(Modifier.fillMaxWidth(), spacing = Space.sp5, alignment = Alignment.Start) {
        DeviceField(form, devices, device, open = stage == NewSessionFields.Stage.device)
        AgentField(form, device, agent)
        AgentOptionFields(form, agent)
        DirectoryField(form, browse = ::browse)
        GitField(form, agent)
    }
    LaunchedEffect(form.deviceID) { form.loadHome(channel = model.connection.channel) }
    LaunchedEffect(DirectoryProbe.key(form.deviceID, form.cwd)) {
        form.probe.run(deviceID = form.deviceID, path = form.cwd, channel = model.connection.channel)
    }
    DirectoryPicker(browser) { form.setPath(it) }
    LaunchedEffect(stage) { NewSessionFields.openStaged(stage, form, model, browser, ::browse) }
}

// Preview stages

internal object NewSessionFields {
    object Stage {
        const val device = "sessions.new.device"
        const val browse = "sessions.new.browse"
        const val folder = "sessions.new.folder"
        const val clash = "sessions.new.folder.clash"
    }

    /**
     * The picker, its new folder row, and a name the device already has, for a render — each once
     * the drawer's first listing is in. The clash is made on the device with the most agents, one
     * level up from where the picker opens, where a sibling's name is taken.
     */
    suspend fun openStaged(stage: String?, form: NewSessionForm, model: WinAppModel, browser: MutableState<DirectoryBrowser?>, browse: () -> Unit) {
        if (stage == null || stage !in listOf(Stage.browse, Stage.folder, Stage.clash)) return
        if (stage == Stage.clash) {
            DeviceOrder.online(model.connection.devices).maxByOrNull { it.agents.size }?.let { form.chooseDevice(it.deviceID) }
        }
        waitFor { form.cwd.isNotEmpty() }
        browse()
        val opened = browser.value
        if (stage == Stage.browse || opened == null) return
        waitFor { opened.listing != null }
        val parent = opened.listing?.parent
        if (stage == Stage.clash && opened.listing?.entries?.isEmpty() == true && parent != null) opened.open(parent)
        opened.startNaming()
        if (stage != Stage.clash) return
        val taken = opened.listing?.entries?.firstOrNull()?.name ?: return
        opened.folderName = taken
        opened.createFolder()
    }

    private suspend fun waitFor(condition: () -> Boolean) {
        repeat(40) {
            if (condition()) return
            delay(50)
        }
    }
}

/** The device, with its online dot, its name and its latency, from the online devices in name order. */
@Composable
private fun DeviceField(form: NewSessionForm, devices: List<Device>, device: Device?, open: Boolean) {
    VStack(Modifier.fillMaxWidth(), spacing = 0.dp, alignment = Alignment.Start) {
        FieldLabel(S.newSession.device)
        Disabled(devices.isEmpty()) {
            DrawerSelect(
                options = devices.map { MenuOption(it.deviceID, it.name, description = Format.latency(it.latencyMS?.toDouble())) },
                value = form.deviceID,
                ariaLabel = S.newSession.device,
                initiallyOpen = open,
                onSelect = form::chooseDevice,
            ) {
                HStack(Modifier.fillMaxWidth().fillMaxHeight(), spacing = Space.sp3) {
                    OnlineDot(device?.online ?: false)
                    Text(device?.name ?: S.newSession.noDevices, css(FontSize.fs15), Modifier.weight(1f).fillMaxHeight(), lineLimit = 1)
                    Text(
                        device?.let { Format.latency(it.latencyMS?.toDouble()) } ?: "",
                        css(FontSize.fs12, mono = true),
                        Modifier.fillMaxHeight(),
                        color = Palette.inkSecondary,
                    )
                }
            }
        }
    }
}

/**
 * A25: the agents no longer fit side by side under their names, so each is its logo and the line
 * under the control names the one that is chosen.
 */
@Composable
private fun AgentField(form: NewSessionForm, device: Device?, agent: AgentInfo?) {
    VStack(Modifier.fillMaxWidth(), spacing = 0.dp, alignment = Alignment.Start) {
        FieldLabel(S.newSession.agent)
        Segmented(
            value = agent?.agent ?: "",
            options = device?.agents.orEmpty().map { option ->
                val name = if (option.available) S.agentLabel(option.agent) else "${S.agentLabel(option.agent)} · ${S.newSession.agentUnavailable}"
                SegmentOption(value = option.agent, disabled = !option.available, name = name) { AgentLogo(option.agent, size = 18f) }
            },
            ariaLabel = S.newSession.agent,
            modifier = Modifier.fillMaxWidth(),
            onChange = form::chooseAgent,
        )
        if (agent != null) AgentLine(agent, Modifier.padding(top = Space.sp2))
    }
}

@Composable
private fun AgentLine(agent: AgentInfo, modifier: Modifier) {
    val version = agent.version?.takeIf { it.isNotEmpty() }?.let { " $it" } ?: ""
    val model = agent.defaultModel?.takeIf { it.isNotEmpty() }?.let { " · $it" } ?: ""
    val text = buildAnnotatedString {
        append(S.agentLabel(agent.agent))
        withStyle(SpanStyle(fontFamily = FontFamily.Monospace)) { append(version + model) }
    }
    Text(text, css(FontSize.fs12), modifier, color = Palette.inkSecondary)
}
