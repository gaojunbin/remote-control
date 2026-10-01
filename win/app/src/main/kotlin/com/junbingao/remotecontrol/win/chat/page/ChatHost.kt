package com.junbingao.remotecontrol.win.chat.page

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.junbingao.remotecontrol.core.state.ChatStore
import com.junbingao.remotecontrol.win.app.WinAppModel
import com.junbingao.remotecontrol.win.app.agent
import com.junbingao.remotecontrol.win.app.device
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

/**
 * The open conversation of a chat page, from the moment the page shows a session to the moment it
 * leaves it — the web's `useChat` open and close, done the way the iPhone app's
 * `AppModel.open(_:)` and `closeChat()` do: the draft and an unfinished queued edit put back, the
 * frame handler added, the cached transcript painted, and on the way out all of it written back.
 */
class ChatHost {
    var chat: ChatStore? by mutableStateOf(null)
        private set
    var actions: ChatActions? by mutableStateOf(null)
        private set

    /**
     * Open `deviceId`/`sessionId`, closing whatever this page had open first. Nothing happens until
     * the gateway lists the session and a channel is there to subscribe on.
     *
     * The page that asks may go while the draft is read, and then nothing opens; once the
     * conversation is open, its subscription runs to the end whatever happens to the page, as the
     * Mac's does, and the page's leaving closes it.
     */
    suspend fun open(deviceId: String, sessionId: String, model: WinAppModel) {
        val current = chat
        if (current != null && current.deviceID == deviceId && current.sessionID == sessionId) return
        close(model)
        val session = model.connection.session(deviceID = deviceId, sessionID = sessionId) ?: return
        val channel = model.connection.channel ?: return
        val store = ChatStore(session = session, channel = channel, tasks = model.tasks)
        store.agent = model.agent(session)
        // The preference stays in one place: changing it in Settings redraws an open
        // conversation at once.
        store.detailSource = { model.settings.timelineDetail }
        store.deviceOnline = model.device(deviceId)?.online ?: false
        store.canReachGateway = model.connection.phase.canReachGateway || model.isDemo
        store.draft = model.drafts.draft(account = model.account, key = session.id)
        val memory = ChatMemory.of(model)
        store.resumeEdit(memory.queuedEdits.remove(session.id))
        chat = store
        actions = ChatActions(chat = store, channel = channel)
        memory.open = this
        model.connection.addFrameHandler(frameToken) { frame -> store.receive(frame) }
        withContext(NonCancellable) {
            val cached = model.connection.cachedTranscript(sessionID = session.sessionID, deviceID = session.deviceID)
            store.open(cached = cached)
        }
    }

    /** Leave the conversation: its draft, its queued edit and its transcript are kept for next time, and the gateway stops streaming it. */
    suspend fun close(model: WinAppModel) = withContext(NonCancellable) {
        val store = chat ?: return@withContext
        chat = null
        actions = null
        model.connection.removeFrameHandler(frameToken)
        val memory = ChatMemory.of(model)
        if (memory.open === this@ChatHost) memory.open = null
        store.queuedEdit?.let { memory.queuedEdits[store.key] = it }
        model.drafts.setDraft(store.draft, account = model.account, key = store.key)
        model.connection.persist(
            transcript = store.timeline.entries.mapNotNull { it.sourceEvent },
            sessionID = store.sessionID,
            deviceID = store.deviceID,
        )
        store.close()
    }

    /**
     * Keep what the conversation reads of the inventory in step with it: the agent's capabilities,
     * whether the device is online, whether the gateway can be reached.
     */
    fun sync(model: WinAppModel) {
        val store = chat ?: return
        val agent = model.agent(store.session)
        if (store.agent != agent) store.agent = agent
        val online = model.device(store.deviceID)?.online ?: false
        if (store.deviceOnline != online) store.deviceOnline = online
        val reachable = model.connection.phase.canReachGateway || model.isDemo
        if (store.canReachGateway != reachable) store.canReachGateway = reachable
    }

    companion object {
        /** The token the conversation's frame handler is filed under. */
        const val frameToken = "chat"
    }
}
