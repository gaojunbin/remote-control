package com.junbingao.remotecontrol.android.screens.sessions

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.junbingao.remotecontrol.android.design.AgentLogo
import com.junbingao.remotecontrol.android.design.Button
import com.junbingao.remotecontrol.android.design.EmptyStateView
import com.junbingao.remotecontrol.android.design.Label
import com.junbingao.remotecontrol.android.design.PrimaryButtonStyle
import com.junbingao.remotecontrol.android.design.StatusDot
import com.junbingao.remotecontrol.android.design.Text
import com.junbingao.remotecontrol.android.design.Theme
import com.junbingao.remotecontrol.android.design.barBackground
import com.junbingao.remotecontrol.android.icons.Sf
import com.junbingao.remotecontrol.android.navigation.NavigationMetrics
import com.junbingao.remotecontrol.android.navigation.NavigationScreen
import com.junbingao.remotecontrol.android.navigation.TitleDisplayMode
import com.junbingao.remotecontrol.android.shell.AppModel
import com.junbingao.remotecontrol.android.shell.ConnectionSummary
import com.junbingao.remotecontrol.android.shell.LocalAppModel
import com.junbingao.remotecontrol.android.strings.L10n
import com.junbingao.remotecontrol.android.system.ActionRole
import com.junbingao.remotecontrol.android.system.Alert
import com.junbingao.remotecontrol.android.system.AlertAction
import com.junbingao.remotecontrol.android.system.BottomBar
import com.junbingao.remotecontrol.android.system.InsetGroupedList
import com.junbingao.remotecontrol.android.system.ListMetrics
import com.junbingao.remotecontrol.android.system.LocalBottomBarReach
import com.junbingao.remotecontrol.android.system.LocalTopBarReach
import com.junbingao.remotecontrol.android.system.MenuItem
import com.junbingao.remotecontrol.android.system.RowStyle
import com.junbingao.remotecontrol.android.system.SearchField
import com.junbingao.remotecontrol.android.system.SectionScope
import com.junbingao.remotecontrol.android.system.Sheet
import com.junbingao.remotecontrol.android.system.SwipeAction
import com.junbingao.remotecontrol.core.protocol.AgentLabel
import com.junbingao.remotecontrol.core.protocol.Session
import com.junbingao.remotecontrol.core.state.ConnectionPhase
import com.junbingao.remotecontrol.core.state.DotLegend
import com.junbingao.remotecontrol.core.state.SessionClose
import com.junbingao.remotecontrol.core.state.SessionListLayout
import com.junbingao.remotecontrol.core.state.trimmed

/**
 * Every session, grouped by the machine it runs on: what that machine still holds, then its own
 * collapsed Archive underneath.
 */
@Composable
fun SessionsView() {
    val model = LocalAppModel.current
    val sessions = model.sessions
    var isCreating by rememberSaveable { mutableStateOf(false) }
    // The working session whose Close is waiting on an answer (A39).
    var closing by remember { mutableStateOf<Session?>(null) }
    val search = rememberSearchDrawer()
    val groups = sessions.groups(model.connection.sessions, devices = model.connection.devices)
    // A list that opens empty and fills when the hello arrives starts again at its top: a lazy list
    // holds on to the item it began with — the summary — and would open at its foot, where the
    // iPhone's opens at its head.
    val list = rememberSaveable(groups.isEmpty(), saver = LazyListState.Saver) { LazyListState() }
    val folding = if (search.isActive) 0.dp else collapse(list)
    val closeTint = Theme.accent

    fun close(session: Session) {
        closing = null
        model.perform { connection.close(session) }
    }

    // The row's one action (`docs/DESIGN.md` § "Close, then the Archive", A39): Close ends the
    // session on the machine and files it. Only a session this device is driving offers it — a
    // terminal's row leaves Active when the terminal exits, and an archived row comes back by being
    // written to. A working agent is asked about first, because its unfinished turn is what the
    // tap throws away. The swipe takes the app's own tint, as a destructive swipe does on the
    // iPhone where the root sets one.
    fun SectionScope.sessionRow(session: Session, online: Boolean) {
        val offersClose = SessionListLayout.offersClose(session)
        val closeSession = {
            if (SessionClose.asksFirst(session, online = online)) closing = session else close(session)
        }
        val tag = "session.close.${session.sessionID}"
        row(
            key = session.id,
            style = RowStyle.sessionRowLayout,
            onClick = { model.perform { open(session) } },
            swipeActions = if (offersClose) listOf(SwipeAction(L10n.string("Close"), Sf.xmarkCircle, closeTint, tag) { closeSession() }) else emptyList(),
            contextMenu = if (offersClose) listOf(MenuItem.Action(L10n.string("Close"), Sf.xmarkCircle, role = ActionRole.destructive, tag = tag) { closeSession() }) else emptyList(),
            tag = "session.${session.sessionID}",
        ) { SessionRow(session, online = online) }
    }

    NavigationScreen(
        if (search.isActive) "" else L10n.string("Sessions"),
        displayMode = if (search.isActive) TitleDisplayMode.inline else TitleDisplayMode.large,
        leading = if (search.isActive) {
            {
                SearchField(
                    sessions.searchText,
                    { sessions.searchText = it },
                    prompt = L10n.string("Search sessions"),
                    isActive = true,
                    onCancel = {
                        sessions.searchText = ""
                        search.close()
                    },
                )
            }
        } else {
            null
        },
        trailing = if (search.isActive) null else { { AgentFilter(model) } },
        listState = list,
        top = { Top(model, search, collapse = folding) },
        // The list ends the way the Devices list ends: one primary button in the bottom bar,
        // exactly where Devices puts Add device.
        bottomBar = {
            BottomBar(Modifier.barBacking(down = LocalBottomBarReach.current)) {
                Button(
                    onClick = { isCreating = true },
                    Modifier.testTag("sessions.new"),
                    enabled = model.connection.onlineDevices.isNotEmpty(),
                    style = PrimaryButtonStyle(),
                ) { Label(L10n.string("New session"), Sf.plus) }
            }
        },
    ) { insets ->
        // The connection line's `.background(.bar)` reaches up through the safe area, so while it
        // has something to say the material stands behind the bar and the large title from the top
        // of the screen.
        if (summaryShows(model)) Spacer(Modifier.fillMaxWidth().height(insets.top - folding).background(Theme.canvas).barBackground())
        // With a bar of its own under the navigation bar, iOS 26 starts the list right under it.
        val padding = PaddingValues(top = insets.top - ListMetrics.firstSectionTop, bottom = insets.bottom)
        InsetGroupedList(Modifier.fillMaxSize().nestedScroll(search.connection).testTag("sessions.list"), list, padding) {
            // What the colours mean, above the first machine. An empty list draws no dots, so it gets
            // no key to them either.
            if (groups.isNotEmpty()) {
                section(key = "legend") { row(key = "legend", style = LegendRow) { Legend() } }
            }
            for (group in groups) {
                section(key = group.id, header = { DeviceHeader(group) { sessions.toggleCollapsed(group.id) } }) {
                    if (!group.collapsed) {
                        for (session in group.active) sessionRow(session, group.online)
                        if (group.archive.isNotEmpty()) {
                            row(
                                key = "${group.id}.archive",
                                style = ArchiveRow,
                                onClick = { sessions.toggleArchive(group.id) },
                                tag = "sessions.archive.${group.id}",
                            ) { ArchiveHeader(group, Modifier.rowHeight(ArchiveInset, ArchiveInset)) }
                            if (group.archiveExpanded) for (session in group.archive) sessionRow(session, group.online)
                        }
                    }
                }
            }
            section(key = "summary") {
                if (groups.isEmpty()) {
                    row(key = "empty", style = ClearRow) {
                        EmptyStateView("bubble.left.and.text.bubble.right", emptyTitle(model), emptyMessage(model))
                    }
                }
                // What the account adds up to, as the last thing in the list. It used to hold the
                // bottom bar, which is where the primary action belongs on both screens.
                row(key = "summary", style = ClearRow) {
                    Text(
                        model.connection.inventorySummary,
                        Modifier
                            .padding(top = if (groups.isEmpty()) 0.dp else RowMetrics.headerlessGap)
                            .fillMaxWidth()
                            .rowHeight(ClearInset, ClearInset)
                            .testTag("sessions.summary"),
                        style = Theme.Text.caption,
                        color = Theme.inkSecondary,
                        alignment = TextAlign.Center,
                    )
                }
            }
        }
    }

    Sheet(isCreating, onDismiss = { isCreating = false }) { NewSessionSheet(dismiss = { isCreating = false }) }
    // Closing a working session throws away the part of the turn it has not finished, so that is
    // the one row the tap asks about. It asks in an alert, the way Revoke does on the Devices
    // screen: both answers are drawn, where a confirmation dialog draws the destructive one alone.
    Alert(
        closing != null,
        L10n.string("Close this session?"),
        onDismiss = { closing = null },
        message = L10n.string("The agent is still working; what it has not finished is lost."),
        actions = listOf(
            AlertAction(L10n.string("Cancel"), ActionRole.cancel, tag = "alert.cancel") { closing = null },
            AlertAction(L10n.string("Close"), ActionRole.destructive, tag = "session.close.confirm") { closing?.let(::close) },
        ),
    )
}

/**
 * Under the bar: the search drawer while it is out but not in use, then the foot of the bar and
 * the line that says how the connection is, on the bar's own material. It rides up with the large
 * title as the list scrolls, as `.safeAreaInset(edge: .top)` follows the iPhone's bar, and as the
 * title folds away the line's material reaches from the top of the screen down to it, so what
 * scrolls under the bar passes under one surface. With nothing to say there is no material at all.
 */
@Composable
private fun Top(model: AppModel, search: SearchDrawer, collapse: Dp) {
    val sessions = model.sessions
    val reach = LocalTopBarReach.current
    val folded = (collapse / NavigationMetrics.largeTitleHeight).coerceIn(0f, 1f)
    val says = summaryShows(model)
    Column(
        Modifier
            .fillMaxWidth()
            .offset { IntOffset(0, -collapse.roundToPx()) }
            .then(if (says) Modifier.barBacking(up = reach - collapse, strength = folded) else Modifier),
    ) {
        if (search.isRevealed && !search.isActive) {
            DrawerSearchField(
                sessions.searchText,
                L10n.string("Search sessions"),
                shown = with(LocalDensity.current) { search.shown.toDp() },
                onActivate = search::activate,
                modifier = if (says) Modifier.barBackground() else Modifier,
            )
        }
        if (says) Spacer(Modifier.fillMaxWidth().height(BarFoot.height).barBackground())
        ConnectionSummary(model.connection.phase, model.isDemo) { model.perform { connection.reconnect() } }
    }
}

/**
 * How far the large title has folded, read off the list as `NavigationScreen` reads it. Once the
 * title has gone the answer stops changing, so the screen stops being recomposed by the scroll.
 */
@Composable
private fun collapse(list: LazyListState): Dp {
    val density = LocalDensity.current
    val folded by remember(list, density) {
        derivedStateOf {
            val scrolled = if (list.firstVisibleItemIndex > 0) NavigationMetrics.largeTitleHeight else with(density) { list.firstVisibleItemScrollOffset.toDp() }
            scrolled.coerceAtMost(NavigationMetrics.largeTitleHeight)
        }
    }
    return folded
}

/** Whether the connection has a line to say, which is when `ConnectionSummary` draws one. */
private fun summaryShows(model: AppModel): Boolean {
    val phase = model.connection.phase
    return model.isDemo || (phase != ConnectionPhase.Connected && phase != ConnectionPhase.SignedOut)
}

/**
 * The key to the dots, once per screen and in a caption's voice (`docs/DESIGN.md` § "A legend,
 * once, and quiet"): four colours with their words, no box, no border, no title. The Devices
 * screen and the chat never draw it, because nothing is explained twice. One element rather than
 * eight: a reader hears the key as a sentence and moves on.
 */
@Composable
private fun Legend() {
    val entries = DotLegend.entries
    Row(
        Modifier
            .fillMaxWidth()
            .rowHeight(0.dp, Theme.Space.tight)
            .testTag("sessions.legend")
            .clearAndSetSemantics { contentDescription = entries.joinToString(", ") { it.text } },
        horizontalArrangement = Arrangement.spacedBy(Theme.Space.tight),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        for (entry in entries) {
            Row(horizontalArrangement = Arrangement.spacedBy(5.dp), verticalAlignment = Alignment.CenterVertically) {
                // The size the rows use, so the key reads as the same mark.
                StatusDot(entry.tone)
                Text(entry.text, style = Theme.Text.caption, color = Theme.inkSecondary, lineLimit = 1)
            }
        }
    }
}

/** All, then the agents the list actually contains. The choice is a view of this list rather than a setting, so it is not remembered. */
@Composable
private fun AgentFilter(model: AppModel) {
    val sessions = model.sessions
    val options = sessions.agentOptions(model.connection.sessions)
    if (options.isEmpty()) return
    val chosen = sessions.agentFilter
    // A menu row carries one image, so the chosen row spends it on the checkmark and the others on
    // the agent's logo. The bar's button is where the chosen agent's logo is drawn instead.
    fun choice(agent: String?, label: String): MenuItem = MenuItem.Action(
        label,
        symbol = if (chosen == agent) Sf.checkmark else null,
        image = if (chosen != agent && agent != null) AgentLogo.vector(agent) else null,
        checked = chosen == agent,
        tag = "sessions.agentFilter.${agent ?: "all"}",
    ) { sessions.agentFilter = agent }
    FilterMenu(
        items = listOf(choice(null, L10n.string("All agents"))) + options.map { choice(it, AgentLabel.name(it)) },
        narrowed = chosen != null,
        description = L10n.string("Filter by agent"),
        value = chosen?.let(AgentLabel::name) ?: L10n.string("All agents"),
        tag = "sessions.agentFilter",
        choice = chosen?.let { agent ->
            {
                Row(horizontalArrangement = Arrangement.spacedBy(Theme.Space.hair + 2.dp), verticalAlignment = Alignment.CenterVertically) {
                    AgentLogo(agent, tint = null)
                    Text(AgentLabel.name(agent), style = Theme.Text.meta)
                }
            }
        },
    )
}

private fun emptyTitle(model: AppModel): String =
    L10n.string(if (model.sessions.searchText.trimmed.isEmpty()) "No sessions yet" else "Nothing matches")

private fun emptyMessage(model: AppModel): String {
    if (model.sessions.searchText.trimmed.isNotEmpty()) return L10n.string("No session title, folder or agent matches that.")
    model.sessions.agentFilter?.let { return L10n.string("No session on any device is running %@.", AgentLabel.name(it)) }
    return L10n.string(
        if (model.connection.devices.isEmpty()) "Add a device first, then start a session on it." else "Start a session to drive an agent from here.",
    )
}

/** `listRowInsets(top: 0, leading: medium, bottom: tight, trailing: medium)` on the page. */
private val LegendRow = RowStyle(
    insets = PaddingValues(start = Theme.Space.medium, top = 0.dp, end = Theme.Space.medium, bottom = Theme.Space.tight),
    background = Color.Transparent,
    separator = false,
)

/** `listRowInsets(top: 9, leading: medium, bottom: 9, trailing: medium)` on the card. */
private val ArchiveInset = 9.dp
private val ArchiveRow = RowStyle(
    insets = PaddingValues(start = Theme.Space.medium, top = ArchiveInset, end = Theme.Space.medium, bottom = ArchiveInset),
    separator = false,
)

/** A row on the page rather than on the card, at UIKit's own insets. */
private val ClearRow = RowStyle(background = Color.Transparent, separator = false)
private val ClearInset = ListMetrics.defaultInsets.calculateTopPadding()
