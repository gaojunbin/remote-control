package com.junbingao.remotecontrol.win.sessions.controls

import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.junbingao.remotecontrol.win.design.AgentLogo
import com.junbingao.remotecontrol.win.design.Button
import com.junbingao.remotecontrol.win.design.FontSize
import com.junbingao.remotecontrol.win.design.HStack
import com.junbingao.remotecontrol.win.design.MenuItemStyle
import com.junbingao.remotecontrol.win.design.MenuList
import com.junbingao.remotecontrol.win.design.Space
import com.junbingao.remotecontrol.win.design.Text
import com.junbingao.remotecontrol.win.design.css
import com.junbingao.remotecontrol.win.design.overlay.Popover
import com.junbingao.remotecontrol.win.design.overlay.PopoverAlign
import com.junbingao.remotecontrol.win.strings.S

/**
 * `AgentOption.tsx`: one agent in a list that has room for its name — the logo, then the name
 * (`docs/DESIGN.md` § "Agents"). The logo is one em of the line it stands in. Given a height —
 * a trigger's — the name centres its line in it.
 */
@Composable
internal fun AgentOptionLabel(agent: String, size: Float, modifier: Modifier = Modifier) {
    HStack(modifier, spacing = Space.sp2) {
        AgentLogo(agent, size = size)
        Text(S.agentLabel(agent), css(size), Modifier.fillMaxHeight())
    }
}

/**
 * The Sessions page's agent filter: "All agents" and the agents actually present, each with its
 * logo and its name. The choice lives in the store both lists read, so the page and the chat
 * sidebar never disagree.
 */
@Composable
internal fun AgentFilterMenu(agents: List<String>, selection: String?, onSelect: (String?) -> Unit, initiallyOpen: Boolean = false) {
    Popover(
        align = PopoverAlign.end,
        ariaLabel = S.sessions.agentFilter,
        initiallyOpen = initiallyOpen,
        label = {
            // As tall as the pill, so the line centres itself on the point the Mac's does.
            if (selection != null) {
                AgentOptionLabel(selection, FontSize.fs13, Modifier.fillMaxHeight())
            } else {
                Text(S.sessions.allAgents, css(FontSize.fs13), Modifier.fillMaxHeight())
            }
        },
    ) { close ->
        MenuList(Modifier.semantics { contentDescription = S.sessions.agentFilter }) {
            FilterRow(null, selection, onSelect, close) { Text(S.sessions.allAgents, css(FontSize.fs14)) }
            for (agent in agents) {
                // The browser sets the logo and the name as an inline box on the label's baseline,
                // which deepens its line by a pixel and a half.
                FilterRow(agent, selection, onSelect, close) { AgentOptionLabel(agent, FontSize.fs14, Modifier.padding(bottom = 1.5.dp)) }
            }
        }
    }
}

/** No filter is the "All agents" row, which the menu then marks. */
@Composable
private fun FilterRow(agent: String?, selection: String?, onSelect: (String?) -> Unit, close: () -> Unit, label: @Composable () -> Unit) {
    Button(
        action = {
            onSelect(agent)
            close()
        },
        style = MenuItemStyle(selected = selection == agent),
        label = label,
    )
}
