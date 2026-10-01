package com.junbingao.remotecontrol.android.screens.sessions

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.junbingao.remotecontrol.android.design.AgentChip
import com.junbingao.remotecontrol.android.design.StatusLabel
import com.junbingao.remotecontrol.android.design.Text
import com.junbingao.remotecontrol.android.design.Theme
import com.junbingao.remotecontrol.android.navigation.NavigationScreen
import com.junbingao.remotecontrol.android.shell.ConnectionSummary
import com.junbingao.remotecontrol.android.shell.LocalAppModel
import com.junbingao.remotecontrol.android.strings.L10n
import com.junbingao.remotecontrol.android.system.InsetGroupedList
import com.junbingao.remotecontrol.core.state.dotTone

/**
 * Placeholder for `android-lists`, which ports the iPhone's `SessionsView` here. Until then it
 * lists the sessions by device — a title, the agent and the state — and a tap opens the
 * conversation through the model, which is the one thing the shell needs of it.
 */
@Composable
fun SessionsView() {
    val model = LocalAppModel.current
    val list = rememberLazyListState()
    NavigationScreen(
        L10n.string("Sessions"),
        listState = list,
        top = { ConnectionSummary(model.connection.phase, model.isDemo) { model.perform { connection.reconnect() } } },
    ) { insets ->
        InsetGroupedList(Modifier.fillMaxSize(), list, insets.padding()) {
            for (group in model.sessions.groups(model.connection.sessions, model.connection.devices)) {
                section(key = group.device.deviceID, header = { Text(group.device.name, style = Theme.Text.meta, color = Theme.inkSecondary) }) {
                    for (session in group.active) {
                        row(key = session.id, onClick = { model.perform { open(session) } }, tag = "session.${session.sessionID}") {
                            Column(verticalArrangement = Arrangement.spacedBy(Theme.Space.hair + 2.dp)) {
                                Text(session.title.ifEmpty { session.sessionID }, style = Theme.Text.title, color = Theme.ink, lineLimit = 1)
                                AgentChip(session.agent)
                                StatusLabel(session.dotTone(group.device.online), session.state.rawValue)
                            }
                        }
                    }
                }
            }
        }
    }
}
