package com.junbingao.remotecontrol.android.screens.sessions

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import com.junbingao.remotecontrol.android.design.Button
import com.junbingao.remotecontrol.android.design.PrimaryButtonStyle
import com.junbingao.remotecontrol.android.design.Text
import com.junbingao.remotecontrol.android.design.Theme
import com.junbingao.remotecontrol.android.design.dismissesKeyboardOnBackgroundTap
import com.junbingao.remotecontrol.android.navigation.LocalTabBarReserve
import com.junbingao.remotecontrol.android.navigation.NavigationScreen
import com.junbingao.remotecontrol.android.navigation.TitleDisplayMode
import com.junbingao.remotecontrol.android.shell.LocalAppModel
import com.junbingao.remotecontrol.android.strings.L10n
import com.junbingao.remotecontrol.android.system.ActivityIndicator
import com.junbingao.remotecontrol.android.system.BarTextButton
import com.junbingao.remotecontrol.android.system.BottomBar
import com.junbingao.remotecontrol.android.system.InsetGroupedList
import com.junbingao.remotecontrol.android.system.LocalBottomBarReach
import com.junbingao.remotecontrol.android.system.Sheet
import com.junbingao.remotecontrol.android.system.safeArea
import com.junbingao.remotecontrol.core.protocol.AgentCapability
import com.junbingao.remotecontrol.core.protocol.DirectoryListing
import com.junbingao.remotecontrol.core.protocol.GatewayRequest
import com.junbingao.remotecontrol.core.protocol.GitStatus
import com.junbingao.remotecontrol.core.protocol.RecentDirectory
import com.junbingao.remotecontrol.core.protocol.SessionResult
import com.junbingao.remotecontrol.core.protocol.SpeedChange
import com.junbingao.remotecontrol.core.state.request
import com.junbingao.remotecontrol.core.state.trimmed
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/** Device, agent, working directory and git. */
@Composable
fun NewSessionSheet(dismiss: () -> Unit) {
    val model = LocalAppModel.current
    val scope = rememberCoroutineScope()
    val form = remember { NewSessionForm() }
    var isBrowsing by remember { mutableStateOf(false) }
    var isStarting by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    suspend fun refreshGit() {
        val channel = model.connection.channel ?: return
        val path = form.cwd.trimmed
        if (path.isEmpty()) return
        form.git = attempt { channel.request(GatewayRequest.git(deviceID = form.deviceID, path = path), GitStatus.serializer()) }
        if (form.git?.isRepo != true) form.worktree = false
    }

    suspend fun prepareForDevice() {
        form.agentID = model.connection.device(form.deviceID)?.availableAgents?.firstOrNull()?.agent ?: ""
        form.adoptAgentDefaults(model.connection.device(form.deviceID)?.agent(form.agentID))
        form.worktree = false
        val channel = model.connection.channel ?: return
        if (form.deviceID.isEmpty()) return
        attempt { channel.request(GatewayRequest.dirs(deviceID = form.deviceID), DirectoryListing.serializer()) }?.let { listing ->
            form.recent = listing.recent
            if (form.cwd.isEmpty()) form.cwd = listing.recent.firstOrNull()?.path ?: listing.path
        }
        refreshGit()
    }

    suspend fun start() {
        val channel = model.connection.channel ?: return
        val agent = model.connection.device(form.deviceID)?.agent(form.agentID) ?: return
        isStarting = true
        error = null
        try {
            val request = GatewayRequest.createSession(
                deviceID = form.deviceID, agent = form.agentID, cwd = form.cwd.trimmed,
                model = form.modelID.ifEmpty { null },
                permissionMode = form.permissionMode.ifEmpty { null },
                effort = form.effort.ifEmpty { null },
                speed = form.speed.takeIf { it != SpeedChange.Standard },
                worktree = if (agent.supports(AgentCapability.worktree)) form.worktree else null,
            )
            val result = channel.request(request, SessionResult.serializer())
            dismiss()
            model.open(result.session)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            error = model.connection.message(failure)
        } finally {
            isStarting = false
        }
    }

    LaunchedEffect(Unit) {
        if (form.deviceID.isEmpty()) form.deviceID = model.connection.onlineDevices.firstOrNull()?.deviceID ?: ""
        prepareForDevice()
    }

    val device = model.connection.device(form.deviceID)
    val agent = device?.agent(form.agentID)
    val actions = NewSessionForm.Actions(
        selectDevice = { id ->
            form.deviceID = id
            scope.launch { prepareForDevice() }
        },
        selectAgent = { id ->
            form.agentID = id
            form.adoptAgentDefaults(model.connection.device(form.deviceID)?.agent(id))
        },
        refreshGit = { scope.launch { refreshGit() } },
        browse = { isBrowsing = true },
    )
    // A sheet stands over the tab bar, so its foot is the screen's own.
    CompositionLocalProvider(LocalTabBarReserve provides safeArea().bottom) {
        NavigationScreen(
            L10n.string("New session"),
            Modifier.dismissesKeyboardOnBackgroundTap(),
            displayMode = TitleDisplayMode.inline,
            showsBack = false,
            leading = { BarTextButton(L10n.string("Cancel"), dismiss) },
            bottomBar = {
                BottomBar(Modifier.barBacking(down = LocalBottomBarReach.current)) {
                    Button(
                        // The person asked for this, so it runs to its end however the sheet goes.
                        onClick = { model.perform { start() } },
                        Modifier.testTag("newsession.start"),
                        enabled = !isStarting && form.deviceID.isNotEmpty() && form.agentID.isNotEmpty() && form.cwd.trimmed.isNotEmpty(),
                        style = PrimaryButtonStyle(),
                    ) {
                        Row(horizontalArrangement = Arrangement.spacedBy(Theme.Space.small), verticalAlignment = Alignment.CenterVertically) {
                            if (isStarting) ActivityIndicator(tint = Theme.onAccent)
                            Text(L10n.string("Start session"))
                        }
                    }
                }
            },
        ) { insets ->
            val padding = PaddingValues(top = insets.top + BarFoot.height, bottom = insets.bottom)
            InsetGroupedList(Modifier.fillMaxSize().testTag("newsession.form"), rememberLazyListState(), padding) {
                deviceSection(form, model.connection.onlineDevices, actions)
                agentSection(form, device, agent, actions)
                settingsSections(form, agent)
                directorySection(form, actions)
                gitSection(form, agent)
                error?.let { message -> errorSection(message) }
            }
        }
    }

    Sheet(isBrowsing, onDismiss = { isBrowsing = false }) {
        DirectoryPicker(
            deviceID = form.deviceID,
            onSelect = { path ->
                form.cwd = path
                scope.launch { refreshGit() }
            },
            dismiss = { isBrowsing = false },
        )
    }
}

/** A request whose refusal leaves the field as it was: `try?` on the iPhone. */
private suspend fun <T> attempt(request: suspend () -> T): T? = try {
    request()
} catch (cancelled: CancellationException) {
    throw cancelled
} catch (_: Exception) {
    null
}

/** What the sheet is filling in, held together so the sections can read and write it. */
internal class NewSessionForm {
    var deviceID by mutableStateOf("")
    var agentID by mutableStateOf("")
    var cwd by mutableStateOf("")
    var modelID by mutableStateOf("")
    var effort by mutableStateOf("")
    var permissionMode by mutableStateOf("")
    var speed by mutableStateOf<SpeedChange>(SpeedChange.Standard)
    var worktree by mutableStateOf(false)
    var recent by mutableStateOf<List<RecentDirectory>>(emptyList())
    var git by mutableStateOf<GitStatus?>(null)

    /** The agent's own defaults, so a sheet opened and sent untouched asks for exactly what the device would have chosen on its own. */
    fun adoptAgentDefaults(agent: com.junbingao.remotecontrol.core.protocol.AgentInfo?) {
        modelID = agent?.defaultModel ?: ""
        effort = agent?.defaultEffort ?: ""
        permissionMode = agent?.defaultPermissionMode ?: ""
        speed = SpeedChange.Standard
    }

    /** What the sections do beyond setting a field. */
    class Actions(
        val selectDevice: (String) -> Unit,
        val selectAgent: (String) -> Unit,
        val refreshGit: () -> Unit,
        val browse: () -> Unit,
    )
}
