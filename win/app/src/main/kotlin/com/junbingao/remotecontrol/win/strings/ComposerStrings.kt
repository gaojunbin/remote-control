// Ported from web/src/strings.ts and web/src/strings.zh-Hans.ts. Keep the two
// tables of this file in step with the web's: the English is the source, and
// the Chinese is the web's own translation, copied rather than re-translated.

package com.junbingao.remotecontrol.win.strings

import com.junbingao.remotecontrol.win.standin.InterfaceLanguage

/** The `composer` group of the web's string table. */
class ComposerStrings(
    val placeholder: String,
    val placeholderQueued: String,
    val placeholderSteer: String,
    val placeholderTerminal: String,
    val placeholderOffline: String,
    val send: String,
    val queue: String,
    /** A20: what Send becomes while the timeline holds a pending question. */
    val answer: String,
    val placeholderAnswer: String,
    val interruptAndSend: String,
    val sendOptions: String,
    val attach: String,
    val attachTooMany: (Int) -> String,
    val attachTooLarge: (String, String) -> String,
    val attachFailed: (String) -> String,
    val textTooLong: String,
    val deliveryUnconfirmed: String,
    val deliveryUnconfirmedBody: String,
    val micStart: String,
    val model: String,
    val permissionMode: String,
    val effort: String,
    /** A21: the one chip that carries the model, the effort and the tier. */
    val modelCard: String,
    /** A21: "Speed, Fast" / "Speed, Standard" on the tier toggle. */
    val speed: (String) -> String,
    val speedStandard: String,
    /** The accessible name of a control in the card: "Model, Opus 4.6". */
    val option: (String, String) -> String,
    /** A17: what a terminal chose, shown where its picker would be. */
    val setInTerminal: (String, String) -> String,
    val sendFailed: String,
    /** A43: the first chip of the control row (A44), and the list it opens. */
    val upNext: String,
    val upNextCount: (Int) -> String,
    /** A43: the strip over the field while it holds a queued message. */
    val editingQueued: String,
    /** A43: the device delivered the message before the tap reached it. */
    val alreadySent: String,
) {
    companion object {
        val en = ComposerStrings(
            placeholder = "Message the agent…",
            placeholderQueued = "Message will be queued…",
            placeholderSteer = "Message will steer the turn…",
            placeholderTerminal = "Controlled by the terminal",
            placeholderOffline = "Device is offline",
            send = "Send",
            queue = "Queue",
            answer = "Answer",
            placeholderAnswer = "Type your answer…",
            interruptAndSend = "Interrupt & send",
            sendOptions = "Send options",
            attach = "Attach files",
            attachTooMany = { max -> "At most $max attachments." },
            attachTooLarge = { name, max -> "$name is larger than $max." },
            attachFailed = { name -> "Could not read $name." },
            textTooLong = "Message is too long (64 KiB limit).",
            deliveryUnconfirmed = "Delivery unconfirmed",
            deliveryUnconfirmedBody = "The gateway did not confirm your last message.",
            micStart = "Start voice input",
            model = "Model",
            permissionMode = "Permission mode",
            effort = "Effort",
            modelCard = "Model and effort",
            speed = { tier -> "Speed, $tier" },
            speedStandard = "Standard",
            option = { name, value -> "$name, $value" },
            setInTerminal = { name, value -> "$name · $value · set in the terminal" },
            sendFailed = "Could not send the message.",
            upNext = "Up next",
            upNextCount = { n -> "Up next · $n" },
            editingQueued = "Editing a queued message",
            alreadySent = "That message has already been sent.",
        )

        val zhHans = ComposerStrings(
            placeholder = "给 agent 发消息…",
            placeholderQueued = "消息将排队…",
            placeholderSteer = "消息将转向当前任务…",
            placeholderTerminal = "由终端控制",
            placeholderOffline = "设备已离线",
            send = "发送",
            queue = "排队",
            answer = "回答",
            placeholderAnswer = "输入你的回答…",
            interruptAndSend = "中断并发送",
            sendOptions = "发送选项",
            attach = "添加附件",
            attachTooMany = { max -> "最多 $max 个附件。" },
            attachTooLarge = { name, max -> "$name 超过 $max。" },
            attachFailed = { name -> "无法读取 $name。" },
            textTooLong = "消息过长（上限 64 KiB）。",
            deliveryUnconfirmed = "未确认送达",
            deliveryUnconfirmedBody = "网关没有确认你的上一条消息。",
            micStart = "开始语音输入",
            model = "模型",
            permissionMode = "权限模式",
            effort = "思考强度",
            modelCard = "模型与思考强度",
            speed = { tier -> "速度：$tier" },
            speedStandard = "标准",
            option = { name, value -> "$name：$value" },
            setInTerminal = { name, value -> "$name · $value · 在终端设置" },
            sendFailed = "无法发送消息。",
            upNext = "待发送",
            upNextCount = { n -> "待发送 · $n" },
            editingQueued = "正在编辑排队的消息",
            alreadySent = "这条消息已经发出。",
        )

        /** The table an interface language reads. */
        fun of(language: InterfaceLanguage): ComposerStrings = when (language) {
            InterfaceLanguage.en -> en
            InterfaceLanguage.zhHans -> zhHans
        }
    }
}
