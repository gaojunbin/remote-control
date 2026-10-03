package com.junbingao.remotecontrol.core.state

import androidx.compose.runtime.snapshotFlow
import com.junbingao.remotecontrol.core.protocol.Session
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * Amendment A47: `session.seen` for the conversation the person has in front of them, so a session
 * already on screen when its turn ends never keeps a red dot (`docs/DESIGN.md` § "A red dot for a
 * session that stopped and waits for you").
 *
 * Each app says what "in front" means — the phone's open conversation while the app is active and
 * unlocked, the computer's while its window has focus — as the key of that conversation, or null.
 * This watches that answer and the session's mark together, so the three moments the protocol names
 * (the conversation opening in front, its window coming to the front, a mark arriving while it is
 * there) are one moment here, and so is a `hello` that brings a mark set while the app was away.
 *
 * RCCore's observation tracking is snapshot observation here: `inFront` is read inside a
 * [snapshotFlow], so whatever snapshot state it reads is watched, and the work runs in [tasks]. A
 * snapshot that is written with an equal value changes nothing, where an observed Swift property
 * reports every assignment, so the socket coming back — whose `hello` may change nothing at all — is
 * read here as a moment of its own: nothing is due while the connection is not up.
 */
class SeenReporter(
    private val connection: ConnectionStore,
    private val tasks: CoroutineScope,
    private val inFront: () -> String?,
) {
    private var watching: Job? = null

    /** Start watching, for the life of this reporter. A second call does nothing. */
    fun start() {
        if (watching != null) return
        watching = tasks.launch {
            snapshotFlow { markedConversationInFront() }.collect { due ->
                if (due != null) launch { connection.markSeen(deviceID = due.deviceID, sessionID = due.sessionID) }
            }
        }
    }

    /** The conversation in front of the person, when its copy carries the mark and the gateway can hear about it. */
    private fun markedConversationInFront(): Session? {
        val key = inFront() ?: return null
        if (connection.phase != ConnectionPhase.Connected) return null
        return connection.sessions.firstOrNull { it.id == key && it.unseen }
    }
}
