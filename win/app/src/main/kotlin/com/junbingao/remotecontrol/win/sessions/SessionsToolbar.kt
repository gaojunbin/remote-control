package com.junbingao.remotecontrol.win.sessions

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.junbingao.remotecontrol.core.protocol.Device
import com.junbingao.remotecontrol.win.app.LocalLayoutClass
import com.junbingao.remotecontrol.win.design.FontSize
import com.junbingao.remotecontrol.win.design.HStack
import com.junbingao.remotecontrol.win.design.Space
import com.junbingao.remotecontrol.win.design.Text
import com.junbingao.remotecontrol.win.design.VStack
import com.junbingao.remotecontrol.win.design.css
import com.junbingao.remotecontrol.win.design.overlay.MenuOption
import com.junbingao.remotecontrol.win.design.overlay.PopoverAlign
import com.junbingao.remotecontrol.win.design.overlay.SelectMenu
import com.junbingao.remotecontrol.win.sessions.controls.AgentFilterMenu
import com.junbingao.remotecontrol.win.sessions.controls.ListSearchField
import com.junbingao.remotecontrol.win.strings.S

/**
 * `.sessions-toolbar`: the search, then the agent filter — only when the list holds more than one
 * agent — and the device filter. At 640 and narrower the search takes a line of its own and the two
 * filters wrap under it.
 */
@Composable
internal fun SessionsToolbar(
    query: String,
    onQuery: (String) -> Unit,
    agentFilter: String?,
    onAgentFilter: (String?) -> Unit,
    deviceFilter: String?,
    onDeviceFilter: (String?) -> Unit,
    agents: List<String>,
    /** In name order, as the web's device store keeps them. */
    devices: List<Device>,
    openFilter: SessionsToolbar.Filter? = null,
    modifier: Modifier = Modifier,
) {
    val filters: @Composable () -> Unit = {
        Filters(agentFilter, onAgentFilter, deviceFilter, onDeviceFilter, agents, devices, openFilter)
    }
    if (LocalLayoutClass.current.maxWidth640) {
        VStack(modifier.fillMaxWidth(), spacing = Space.sp2, alignment = Alignment.Start) {
            Search(query, onQuery, maxWidth = null)
            HStack(spacing = Space.sp2) { filters() }
        }
    } else {
        HStack(modifier.fillMaxWidth(), spacing = Space.sp2) {
            Search(query, onQuery, maxWidth = 320.dp)
            filters()
            Spacer(Modifier.weight(1f))
        }
    }
}

object SessionsToolbar {
    /** Which filter a preview stage draws open. */
    enum class Filter { agent, device }

    /** The row id the device filter uses for "no filter". */
    internal const val all = "__all__"
}

@Composable
private fun Search(query: String, onQuery: (String) -> Unit, maxWidth: Dp?) {
    ListSearchField(query, onQuery, S.sessions.searchPlaceholder, maxWidth = maxWidth)
}

@Composable
private fun Filters(
    agentFilter: String?,
    onAgentFilter: (String?) -> Unit,
    deviceFilter: String?,
    onDeviceFilter: (String?) -> Unit,
    agents: List<String>,
    devices: List<Device>,
    openFilter: SessionsToolbar.Filter?,
) {
    if (agents.size > 1) {
        AgentFilterMenu(agents, agentFilter, onAgentFilter, initiallyOpen = openFilter == SessionsToolbar.Filter.agent)
    }
    // The web marks no row while nothing is filtered: its value is null, which no option carries.
    SelectMenu(
        options = listOf(MenuOption(SessionsToolbar.all, S.sessions.allDevices)) + devices.map { MenuOption(it.deviceID, it.name) },
        value = deviceFilter,
        ariaLabel = S.sessions.allDevices,
        align = PopoverAlign.end,
        initiallyOpen = openFilter == SessionsToolbar.Filter.device,
        onSelect = { onDeviceFilter(if (it == SessionsToolbar.all) null else it) },
    ) {
        Text(
            deviceFilter?.let { id -> devices.firstOrNull { it.deviceID == id }?.name ?: id } ?: S.sessions.allDevices,
            css(FontSize.fs13),
            Modifier.fillMaxHeight(),
        )
    }
}
