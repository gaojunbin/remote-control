// Ported from web/src/strings.ts and web/src/strings.zh-Hans.ts. Keep the two
// tables of this file in step with the web's: the English is the source, and
// the Chinese is the web's own translation, copied rather than re-translated.

package com.junbingao.remotecontrol.win.strings

import com.junbingao.remotecontrol.win.standin.InterfaceLanguage

/** The `errors` group of the web's string table. */
class ErrorsStrings(
    val generic: String,
    val stopFailed: String,
    val approveFailed: String,
    val answerFailed: String,
    val expandFailed: String,
    val queueRemoveFailed: String,
    val setFailed: String,
    val takeoverFailed: String,
    /** A35: the two requests behind the notice above the transcript. */
    val resumeSetFailed: String,
    val resumeCancelFailed: String,
    val notFound: String,
    val sessionMissing: String,
    val deviceOffline: String,
    val conflictTerminal: String,
    val timeout: String,
    val unsupported: String,
    val tooLarge: String,
) {
    companion object {
        val en = ErrorsStrings(
            generic = "Something went wrong.",
            stopFailed = "Could not stop the turn.",
            approveFailed = "Could not send that decision.",
            answerFailed = "Could not send that answer.",
            expandFailed = "Could not load the full output.",
            queueRemoveFailed = "Could not remove the queued message.",
            setFailed = "Could not change that setting.",
            takeoverFailed = "Could not take over the session.",
            resumeSetFailed = "Could not change the resume time.",
            resumeCancelFailed = "Could not cancel the resume.",
            notFound = "Not found.",
            sessionMissing = "This session is no longer available.",
            deviceOffline = "That device is offline.",
            conflictTerminal = "This session is controlled by the terminal. Take over first.",
            timeout = "The device did not answer in time.",
            unsupported = "Not supported by this device.",
            tooLarge = "That payload is too large.",
        )

        val zhHans = ErrorsStrings(
            generic = "出了点问题。",
            stopFailed = "无法停止本轮任务。",
            approveFailed = "无法发送该决定。",
            answerFailed = "无法发送该回答。",
            expandFailed = "无法加载完整输出。",
            queueRemoveFailed = "无法移除排队的消息。",
            setFailed = "无法更改该设置。",
            takeoverFailed = "无法接管会话。",
            resumeSetFailed = "无法更改继续时间。",
            resumeCancelFailed = "无法取消继续。",
            notFound = "未找到。",
            sessionMissing = "该会话已不存在。",
            deviceOffline = "该设备已离线。",
            conflictTerminal = "该会话由终端控制。请先接管。",
            timeout = "设备未在规定时间内响应。",
            unsupported = "此设备不支持。",
            tooLarge = "负载过大。",
        )

        /** The table an interface language reads. */
        fun of(language: InterfaceLanguage): ErrorsStrings = when (language) {
            InterfaceLanguage.en -> en
            InterfaceLanguage.zhHans -> zhHans
        }
    }
}
