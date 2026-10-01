package com.junbingao.remotecontrol.win.notifications

import com.junbingao.remotecontrol.core.protocol.PushKind
import com.junbingao.remotecontrol.win.strings.S

/**
 * One notification this PC posts: the push the gateway would have sent, raised by the app itself.
 *
 * It reads as the web's service worker shows a push — "Remote Control" over the line the gateway
 * writes in `rc.title`, the device's name and one generic phrase, never anything the agent wrote.
 * The phrase is the gateway's own English, whatever language the app reads, as the browser's
 * notification is. Windows' toast carries the conversation it opens itself (`ToastNotice.target`),
 * so there is no payload to read it back from.
 */
data class TurnNotice(val kind: PushKind, val target: NoticeTarget, val deviceName: String) {
    val title: String get() = S.productName

    val body: String get() = "$deviceName: ${phrase(kind)}"

    companion object {
        /** `_TEXTS` in `push.py`, and its fallback for a kind it does not know. */
        fun phrase(kind: PushKind): String = when (kind) {
            PushKind.needsApproval -> "approval needed"
            PushKind.needsInput -> "waiting for your answer"
            PushKind.turnCompleted -> "turn finished"
            PushKind.error -> "session error"
            PushKind.limitReached -> "paused by the usage limit"
            PushKind.resumed -> "resumed after the limit reset"
            PushKind.resumeDropped -> "not resumed"
            else -> "update"
        }
    }
}
