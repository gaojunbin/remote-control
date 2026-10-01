package com.junbingao.remotecontrol.android.screens.alerts

import com.junbingao.remotecontrol.android.strings.L10n
import com.junbingao.remotecontrol.core.protocol.PushKind
import com.junbingao.remotecontrol.core.protocol.Session
import com.junbingao.remotecontrol.core.transport.PushRoute

/**
 * One notification the app raises for itself when a turn ends (`docs/DESIGN.md` § "Being told
 * when a turn ends"): the iPhone's `TurnAlert`, carrying the gateway's own push payload, so a tap
 * takes the path a push takes and opens the session.
 */
data class TurnAlert(
    /** The device's name, over the status word. */
    val title: String,
    /** One word from the status vocabulary, and nothing else: no prompt text, no output, no file name. */
    val body: String,
    /** The session key, so a machine's notifications group under the conversation they belong to. */
    val threadIdentifier: String,
    /** What the system deduplicates on: the session and the moment it changed, or a resume's own seq. */
    val identifier: String,
    val route: PushRoute,
) {
    companion object {
        operator fun invoke(kind: PushKind, deviceName: String, session: Session, identifier: String? = null): TurnAlert {
            val word = kind.alertWord
            return TurnAlert(
                title = deviceName,
                body = word,
                threadIdentifier = session.id,
                identifier = identifier ?: "turn/${session.id}/${kind.rawValue}/${session.updatedAt}",
                route = PushRoute(kind = kind, deviceID = session.deviceID, sessionID = session.sessionID,
                                  deviceName = deviceName, title = "$deviceName: $word"),
            )
        }
    }
}

/** The status vocabulary of `docs/DESIGN.md`, which is the same word in both apps and in a notification. */
val PushKind.alertWord: String
    get() = when (this) {
        PushKind.turnCompleted -> L10n.string("Turn finished")
        PushKind.needsApproval -> L10n.string("Needs your approval")
        PushKind.needsInput -> L10n.string("Waiting for your answer")
        PushKind.error -> L10n.string("Errored")
        // Amendment A35: the same three sentences the gateway pushes.
        PushKind.limitReached -> L10n.string("Paused by the usage limit")
        PushKind.resumed -> L10n.string("Resumed after the limit reset")
        PushKind.resumeDropped -> L10n.string("Not resumed")
        else -> rawValue
    }
