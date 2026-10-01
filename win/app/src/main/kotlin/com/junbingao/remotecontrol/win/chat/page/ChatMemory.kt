package com.junbingao.remotecontrol.win.chat.page

import com.junbingao.remotecontrol.core.state.QueuedEdit
import com.junbingao.remotecontrol.win.app.WinAppModel
import java.lang.ref.WeakReference
import java.util.WeakHashMap

/**
 * What the chat keeps for the life of the app, beside the model it serves: an unfinished edit of a
 * queued message (A43) for each conversation it was open in when that conversation closed — the
 * message is out of the line and the draft it replaced is aside, so both wait here for the
 * conversation to open again, in memory, as the web keeps its drafts — and the conversation open
 * now, which a sign-out closes first.
 */
class ChatMemory private constructor() {
    val queuedEdits = HashMap<String, QueuedEdit>()
    private var openHost = WeakReference<ChatHost>(null)

    var open: ChatHost?
        get() = openHost.get()
        set(value) {
            openHost = WeakReference(value)
        }

    /**
     * `signOut.ts`: the conversation closes while the connection still names the account that is
     * leaving, and nothing of it stays behind.
     */
    suspend fun signOut(model: WinAppModel) {
        open?.close(model)
        queuedEdits.clear()
    }

    companion object {
        /** One memory per model. A model the renderer has let go of takes its memory with it. */
        private val all = WeakHashMap<WinAppModel, ChatMemory>()

        fun of(model: WinAppModel): ChatMemory = synchronized(all) { all.getOrPut(model) { ChatMemory() } }
    }
}
