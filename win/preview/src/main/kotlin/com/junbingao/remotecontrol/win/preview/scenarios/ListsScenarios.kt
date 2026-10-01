package com.junbingao.remotecontrol.win.preview.scenarios

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import com.junbingao.remotecontrol.core.protocol.AppFrame
import com.junbingao.remotecontrol.core.protocol.Device
import com.junbingao.remotecontrol.core.protocol.DeviceUpdateState
import com.junbingao.remotecontrol.core.protocol.PairingStep
import com.junbingao.remotecontrol.core.protocol.SessionState
import com.junbingao.remotecontrol.core.state.DeviceGroup
import com.junbingao.remotecontrol.core.state.InterfaceLanguage
import com.junbingao.remotecontrol.win.app.Route
import com.junbingao.remotecontrol.win.app.device
import com.junbingao.remotecontrol.win.app.updateDevice
import com.junbingao.remotecontrol.win.design.Palette
import com.junbingao.remotecontrol.win.devices.DeviceOrder
import com.junbingao.remotecontrol.win.sessions.SessionSidebar
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * The lists feature's scenarios: the Devices page with its menus, dialogs and the Add device
 * handshake, a device's page, the Sessions page with its groups, search, filters and close
 * question, the New session drawer with its directory picker, and the conversation's session
 * sidebar alone. Each works on `--demo` and on the web's mock gateway: a scenario about "the device
 * whose update failed" finds that device in whichever gateway it is on.
 */
object ListsScenarios {
    val all: List<PreviewScenario>
        get() = devices + pairing + devicePage + sessions + drawer + sidebar

    private val devices: List<PreviewScenario>
        get() = listOf(
            PreviewScenario("devices", route = Route.Devices),
            PreviewScenario("devices-900", route = Route.Devices, width = 900),
            PreviewScenario("devices-600", route = Route.Devices, width = 600, height = 760),
            PreviewScenario("devices-480", route = Route.Devices, width = 480, height = 760),
            PreviewScenario("devices-zh", route = Route.Devices, language = InterfaceLanguage.zhHans),
            PreviewScenario("devices-menu", route = Route.Devices, stage = "devices.menu"),
            PreviewScenario("devices-menu-failed", route = Route.Devices, stage = "devices.menu.failed"),
            PreviewScenario("devices-refusal", route = Route.Devices, stage = "devices.refusal"),
            PreviewScenario("devices-rename", route = Route.Devices, stage = "devices.rename"),
            PreviewScenario("devices-revoke", route = Route.Devices, stage = "devices.revoke"),
            PreviewScenario("devices-retry", route = Route.Devices, stage = "devices.retry"),
            PreviewScenario("devices-updating", route = Route.Devices, prepare = prepare@{ context ->
                // A retry the gateway takes on: the row says "Updating…" with its dot pulsing until
                // the device comes back.
                val failed = context.model.connection.devices.firstOrNull { it.updateState == DeviceUpdateState.failed }
                    ?: return@prepare
                context.model.updateDevice(failed)
                context.wait { context.model.device(id = failed.deviceID)?.updateState == DeviceUpdateState.updating }
            }),
        )

    /** Add device at each step of the handshake, as `pairing.progress` arrives. */
    private val pairing: List<PreviewScenario>
        get() = listOf(
            PreviewScenario("devices-add", route = Route.Devices, stage = "devices.add", settle = 200.milliseconds),
            PreviewScenario("devices-add-manual", route = Route.Devices, stage = "devices.add.manual"),
            handshake("devices-add-enrolled", reaching = PairingStep.enrolled),
            handshake("devices-add-online", reaching = PairingStep.online),
            handshake("devices-add-agents", reaching = PairingStep.agents),
            PreviewScenario("devices-add-zh", route = Route.Devices, stage = "devices.add", language = InterfaceLanguage.zhHans),
        )

    private fun handshake(name: String, reaching: PairingStep): PreviewScenario =
        PreviewScenario(name, route = Route.Devices, stage = "devices.add", settle = 300.milliseconds, prepare = { context ->
            var reached = false
            context.model.connection.addFrameHandler("preview-pairing") { frame ->
                if (frame is AppFrame.PairingProgress && frame.progress.step.order >= reaching.order) reached = true
            }
            context.wait(timeout = 10.seconds) { reached }
            context.model.connection.removeFrameHandler("preview-pairing")
        })

    private val devicePage: List<PreviewScenario>
        get() = listOf(
            page("device-page", settle = 1800.milliseconds) { devices -> devices.firstOrNull { it.agents.size > 3 } ?: devices.firstOrNull() },
            page("device-page-checking", settle = 150.milliseconds) { devices -> devices.firstOrNull { it.agents.size > 3 } ?: devices.firstOrNull() },
            page("device-page-failed", settle = 1800.milliseconds) { devices -> devices.firstOrNull { it.updateState == DeviceUpdateState.failed } },
            page("device-page-offline") { devices -> devices.firstOrNull { !it.online } ?: devices.firstOrNull() },
            page("device-page-480", width = 480, settle = 1800.milliseconds) { devices -> devices.firstOrNull { it.agents.size > 3 } },
            page("device-page-zh", language = InterfaceLanguage.zhHans, settle = 1800.milliseconds) { devices ->
                devices.firstOrNull { it.agents.size > 3 }
            },
            PreviewScenario("device-page-gone", route = Route.Device(id = "no-such-device")),
        )

    /** A device's page, for the device `pick` finds on the gateway the render is on. */
    private fun page(
        name: String,
        width: Int? = null,
        language: InterfaceLanguage? = null,
        settle: Duration = 900.milliseconds,
        pick: (List<Device>) -> Device?,
    ): PreviewScenario = PreviewScenario(name, route = Route.Devices, width = width, language = language, settle = settle, setup = setup@{ context ->
        val device = pick(DeviceOrder.byName(context.model.connection.devices)) ?: return@setup
        context.model.router.replace(Route.Device(id = device.deviceID))
    })

    private val sessions: List<PreviewScenario>
        get() = listOf(
            PreviewScenario("sessions", route = Route.Sessions),
            PreviewScenario("sessions-900", route = Route.Sessions, width = 900),
            PreviewScenario("sessions-600", route = Route.Sessions, width = 600),
            PreviewScenario("sessions-zh", route = Route.Sessions, language = InterfaceLanguage.zhHans),
            PreviewScenario("sessions-legend", route = Route.Sessions, height = 420),
            PreviewScenario("sessions-collapsed", route = Route.Sessions, setup = { context ->
                fold(context) { groups -> groups.firstOrNull()?.let { context.model.sessions.toggleCollapsed(it.id) } }
            }),
            // Tall enough to reach the Archives under the active rows.
            PreviewScenario("sessions-archive", route = Route.Sessions, height = 2400, setup = { context ->
                fold(context) { groups ->
                    for (group in groups) if (group.archive.isNotEmpty()) context.model.sessions.toggleArchive(group.id)
                }
            }),
            PreviewScenario("sessions-search", route = Route.Sessions, stage = "sessions.search:vite"),
            PreviewScenario("sessions-search-none", route = Route.Sessions, stage = "sessions.search:zzzz"),
            PreviewScenario("sessions-filter-agent", route = Route.Sessions, stage = "sessions.filter.agent"),
            PreviewScenario("sessions-filter-device", route = Route.Sessions, stage = "sessions.filter.device"),
            PreviewScenario("sessions-filtered", route = Route.Sessions, setup = { context -> context.model.sessions.agentFilter = "codex" }),
            PreviewScenario("sessions-close", route = Route.Sessions, stage = "sessions.close"),
        )

    /** Fold or open groups the way a reader would, on the groups the gateway lists. */
    private fun fold(context: PreviewContext, change: (List<DeviceGroup>) -> Unit) {
        val model = context.model
        change(model.sessions.groups(model.connection.sessions, devices = model.connection.devices))
    }

    private val drawer: List<PreviewScenario>
        get() = listOf(
            PreviewScenario("new-session", route = Route.Sessions, stage = "sessions.new", settle = 1500.milliseconds),
            PreviewScenario("new-session-600", route = Route.Sessions, width = 600, height = 860, stage = "sessions.new", settle = 1500.milliseconds),
            PreviewScenario("new-session-zh", route = Route.Sessions, stage = "sessions.new", language = InterfaceLanguage.zhHans, settle = 1500.milliseconds),
            PreviewScenario("new-session-device", route = Route.Sessions, stage = "sessions.new.device", settle = 1500.milliseconds),
            PreviewScenario("new-session-browse", route = Route.Sessions, stage = "sessions.new.browse", settle = 2000.milliseconds),
            PreviewScenario("new-session-folder", route = Route.Sessions, stage = "sessions.new.folder", settle = 2000.milliseconds),
            PreviewScenario("new-session-folder-clash", route = Route.Sessions, stage = "sessions.new.folder.clash", settle = 2400.milliseconds),
        )

    /** The sidebar alone, as the conversation page places it, on the session the render finds waiting for an approval. */
    private val sidebar: List<PreviewScenario>
        get() = listOf(sidebarScenario("sidebar"), sidebarScenario("sidebar-zh", language = InterfaceLanguage.zhHans))

    private fun sidebarScenario(name: String, language: InterfaceLanguage? = null): PreviewScenario =
        PreviewScenario(name, route = Route.Sessions, language = language, content = { context ->
            val sessions = context.model.connection.sessions
            val open = sessions.firstOrNull { it.state == SessionState.needsApproval } ?: sessions.firstOrNull()
            Row(Modifier.fillMaxSize()) {
                SessionSidebar(deviceId = open?.deviceID ?: "", sessionId = open?.sessionID ?: "")
                Box(Modifier.weight(1f).fillMaxHeight().background(Palette.surface))
            }
        })
}
