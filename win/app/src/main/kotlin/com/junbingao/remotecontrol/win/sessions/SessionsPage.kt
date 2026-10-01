package com.junbingao.remotecontrol.win.sessions

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.junbingao.remotecontrol.core.state.DeviceGroup
import com.junbingao.remotecontrol.core.state.DotTone
import com.junbingao.remotecontrol.core.state.SessionListLayout
import com.junbingao.remotecontrol.core.state.dotTone
import com.junbingao.remotecontrol.core.state.trimmed
import com.junbingao.remotecontrol.win.app.LocalAppModel
import com.junbingao.remotecontrol.win.design.Btn
import com.junbingao.remotecontrol.win.design.ButtonVariant
import com.junbingao.remotecontrol.win.design.LocalPreviewStage
import com.junbingao.remotecontrol.win.design.Space
import com.junbingao.remotecontrol.win.design.VStack
import com.junbingao.remotecontrol.win.design.icons.LucideIcon
import com.junbingao.remotecontrol.win.devices.DeviceOrder
import com.junbingao.remotecontrol.win.layout.PageHead
import com.junbingao.remotecontrol.win.sessions.drawer.NewSessionDrawer
import com.junbingao.remotecontrol.win.sessions.drawer.NewSessionForm
import com.junbingao.remotecontrol.win.strings.S

/**
 * `/sessions` — `web/src/features/sessions/SessionsPage.tsx`: every session across every device,
 * one collapsible group per device with its active rows and then its own folded Archive, a search,
 * the agent and device filters, the dot legend once above the list, and New session in the
 * right-hand drawer. Ctrl+N lands here and opens the drawer.
 */
@Composable
fun SessionsPage() {
    val model = LocalAppModel.current
    val stage = LocalPreviewStage.current
    var query by remember { mutableStateOf("") }
    // One device, or all of them. A view of the list, never remembered.
    var deviceFilter by remember { mutableStateOf<String?>(null) }
    val creating = remember { mutableStateOf<NewSessionForm?>(null) }
    val sessions = model.sessions
    val devices = DeviceOrder.byName(model.connection.devices)
    val needle = query.trimmed
    val groups = SessionLayout.build(
        sessions = model.connection.sessions, devices = devices,
        deviceFilter = deviceFilter, agentFilter = sessions.agentFilter,
        query = needle, collapsedDevices = sessions.collapsedDevices,
        archiveExpanded = sessions.expandedArchives,
    )

    fun openNewSession() {
        creating.value = NewSessionForm(devices = DeviceOrder.online(model.connection.devices), preset = null)
    }

    VStack(Modifier.fillMaxWidth(), spacing = 0.dp, alignment = Alignment.Start) {
        PageHead(S.sessions.title) {
            Btn(S.sessions.new, icon = LucideIcon.plus, variant = ButtonVariant.primary, action = ::openNewSession)
        }
        SessionsToolbar(
            query = query, onQuery = { query = it },
            agentFilter = sessions.agentFilter, onAgentFilter = { sessions.agentFilter = it },
            deviceFilter = deviceFilter, onDeviceFilter = { deviceFilter = it },
            agents = SessionLayout.agents(model.connection.sessions), devices = devices,
            openFilter = SessionsPage.stagedFilter(stage),
            modifier = Modifier.padding(bottom = Space.sp3),
        )
        if (groups.isEmpty()) {
            SessionsEmptyCard(
                title = if (needle.isEmpty()) S.sessions.empty else S.sessions.noMatches,
                hint = if (needle.isEmpty()) S.sessions.emptyHint else null,
            )
        } else {
            SessionLegend(Modifier.padding(bottom = Space.sp5))
            val asksToClose = SessionsPage.stagedClose(stage, groups)
            VStack(Modifier.fillMaxWidth(), spacing = Space.sp6, alignment = Alignment.Start) {
                for (group in groups) key(group.id) { SessionGroupSection(group, asksToClose = asksToClose) }
            }
        }
    }
    NewSessionDrawer(creating)
    LaunchedEffect(Unit) {
        SessionsPage.queryOf(stage)?.let { query = it }
        if (SessionsPage.opensDrawer(stage)) openNewSession()
    }
    LaunchedEffect(model.router.pendingNewSession) {
        if (model.router.takeNewSessionRequest()) openNewSession()
    }
}

// Preview stages

object SessionsPage {
    private const val SEARCH_PREFIX = "sessions.search:"

    /** "sessions.search:<query>" types a query. */
    internal fun queryOf(stage: String?): String? = stage?.takeIf { it.startsWith(SEARCH_PREFIX) }?.removePrefix(SEARCH_PREFIX)

    /** "sessions.new…" opens the drawer. */
    internal fun opensDrawer(stage: String?): Boolean = stage?.startsWith("sessions.new") == true

    internal fun stagedFilter(stage: String?): SessionsToolbar.Filter? = when (stage) {
        "sessions.filter.agent" -> SessionsToolbar.Filter.agent
        "sessions.filter.device" -> SessionsToolbar.Filter.device
        else -> null
    }

    /** The first working row, whose close asks before it acts. */
    internal fun stagedClose(stage: String?, groups: List<DeviceGroup>): String? {
        if (stage != "sessions.close") return null
        for (group in groups) {
            val row = group.active.firstOrNull { SessionListLayout.offersClose(it) && it.dotTone(online = group.online) == DotTone.working }
            if (row != null) return row.id
        }
        return null
    }
}
