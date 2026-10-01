package com.junbingao.remotecontrol.win.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.junbingao.remotecontrol.core.protocol.AgentInfo
import com.junbingao.remotecontrol.core.state.ChatStore
import com.junbingao.remotecontrol.win.app.LocalAppModel
import com.junbingao.remotecontrol.win.app.LocalLayoutClass
import com.junbingao.remotecontrol.win.app.WinAppModel
import com.junbingao.remotecontrol.win.app.agent
import com.junbingao.remotecontrol.win.app.device
import com.junbingao.remotecontrol.win.chat.page.ChatHost
import com.junbingao.remotecontrol.win.chat.page.ChatMain
import com.junbingao.remotecontrol.win.chat.page.ChatMemory
import com.junbingao.remotecontrol.win.chat.page.ChatMissing
import com.junbingao.remotecontrol.win.design.HStack
import com.junbingao.remotecontrol.win.design.LayoutSize
import com.junbingao.remotecontrol.win.design.Palette
import com.junbingao.remotecontrol.win.sessions.SessionSidebar
import kotlinx.coroutines.launch

/**
 * `web/src/features/chat/ChatPage.tsx`, `/sessions/:deviceId/:sessionId`, drawn over the whole
 * window: the session sidebar and the conversation at 1024 points and wider, the conversation
 * alone with a way back below (`docs/DESIGN.md` § "The Windows app"). The page owns the
 * conversation's life: it opens when the page shows a session and closes when the page leaves it.
 */
@Composable
fun ChatPage(deviceId: String, sessionId: String) {
    val model = LocalAppModel.current
    val layout = LocalLayoutClass.current
    val host = remember { ChatHost() }
    // Whether the gateway lists the session the route names.
    val listed = model.connection.session(deviceID = deviceId, sessionID = sessionId) != null
    val hasChannel = model.connection.channel != null
    // What opening a conversation waits for: the route, the gateway listing the session, and a
    // channel to subscribe on.
    LaunchedEffect(deviceId, sessionId, listed, hasChannel) { host.open(deviceId, sessionId, model) }
    // What the open conversation reads of the inventory, so a change to it — the agent's
    // capabilities, the device going offline, the socket coming back — reaches the conversation.
    val inventory = InventoryState(model, deviceId, sessionId)
    LaunchedEffect(inventory) { host.sync(model) }
    DisposableEffect(host) {
        onDispose { model.tasks.launch { host.close(model) } }
    }
    HStack(Modifier.fillMaxSize().background(Palette.canvas), spacing = 0.dp) {
        if (!layout.maxWidth1023) {
            Box(Modifier.width(LayoutSize.sidebarW).fillMaxHeight()) { SessionSidebar(deviceId = deviceId, sessionId = sessionId) }
        }
        Box(Modifier.weight(1f).fillMaxHeight()) { ChatColumn(model, host, deviceId, sessionId, listed) }
    }
}

@Composable
private fun ChatColumn(model: WinAppModel, host: ChatHost, deviceId: String, sessionId: String, listed: Boolean) {
    val chat = host.chat
    val actions = host.actions
    when {
        !listed && model.connection.hasSnapshot -> Box(Modifier.fillMaxSize().background(Palette.surface)) { ChatMissing() }
        chat != null && actions != null && chat.deviceID == deviceId && chat.sessionID == sessionId -> ChatMain(chat, actions)
        else -> Box(Modifier.fillMaxSize().background(Palette.surface))
    }
}

object ChatPage {
    /** The conversation a chat page of `model` has open, for a preview that puts a block into it as a device would. */
    fun conversation(model: WinAppModel): ChatStore? = ChatMemory.of(model).open?.chat
}

private data class InventoryState(val agent: AgentInfo?, val online: Boolean, val reachable: Boolean) {
    companion object {
        operator fun invoke(model: WinAppModel, deviceId: String, sessionId: String): InventoryState {
            val session = model.connection.session(deviceID = deviceId, sessionID = sessionId)
            return InventoryState(
                agent = session?.let { model.agent(it) },
                online = model.device(deviceId)?.online ?: false,
                reachable = model.connection.phase.canReachGateway || model.isDemo,
            )
        }
    }
}
