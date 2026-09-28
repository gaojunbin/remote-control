// Ported from web/src/strings.ts and web/src/strings.zh-Hans.ts. Keep the two
// tables of this file in step with the web's: the English is the source, and
// the Chinese is the web's own translation, copied rather than re-translated.

import Foundation
import RCCore

/// The `composer` group of the web's string table.
public struct ComposerStrings: Sendable {
    public let placeholder: String
    public let placeholderQueued: String
    public let placeholderSteer: String
    public let placeholderTerminal: String
    public let placeholderOffline: String
    public let send: String
    public let queue: String
    /// A20: what Send becomes while the timeline holds a pending question.
    public let answer: String
    public let placeholderAnswer: String
    public let interruptAndSend: String
    public let sendOptions: String
    public let attach: String
    public let attachTooMany: @Sendable (Int) -> String
    public let attachTooLarge: @Sendable (String, String) -> String
    public let attachFailed: @Sendable (String) -> String
    public let textTooLong: String
    public let deliveryUnconfirmed: String
    public let deliveryUnconfirmedBody: String
    public let micStart: String
    public let model: String
    public let permissionMode: String
    public let effort: String
    /// A21: the one chip that carries the model, the effort and the tier.
    public let modelCard: String
    /// A21: "Speed, Fast" / "Speed, Standard" on the tier toggle.
    public let speed: @Sendable (String) -> String
    public let speedStandard: String
    /// The accessible name of a control in the card: "Model, Opus 4.6".
    public let option: @Sendable (String, String) -> String
    /// A17: what a terminal chose, shown where its picker would be.
    public let setInTerminal: @Sendable (String, String) -> String
    public let sendFailed: String
    /// A43: the first chip of the control row (A44), and the list it opens.
    public let upNext: String
    public let upNextCount: @Sendable (Int) -> String
    /// A43: the strip over the field while it holds a queued message.
    public let editingQueued: String
    /// A43: the device delivered the message before the tap reached it.
    public let alreadySent: String
}

extension ComposerStrings {
    static let en = ComposerStrings(
        placeholder: "Message the agent…",
        placeholderQueued: "Message will be queued…",
        placeholderSteer: "Message will steer the turn…",
        placeholderTerminal: "Controlled by the terminal",
        placeholderOffline: "Device is offline",
        send: "Send",
        queue: "Queue",
        answer: "Answer",
        placeholderAnswer: "Type your answer…",
        interruptAndSend: "Interrupt & send",
        sendOptions: "Send options",
        attach: "Attach files",
        attachTooMany: { max in "At most \(max) attachments." },
        attachTooLarge: { name, max in "\(name) is larger than \(max)." },
        attachFailed: { name in "Could not read \(name)." },
        textTooLong: "Message is too long (64 KiB limit).",
        deliveryUnconfirmed: "Delivery unconfirmed",
        deliveryUnconfirmedBody: "The gateway did not confirm your last message.",
        micStart: "Start voice input",
        model: "Model",
        permissionMode: "Permission mode",
        effort: "Effort",
        modelCard: "Model and effort",
        speed: { tier in "Speed, \(tier)" },
        speedStandard: "Standard",
        option: { name, value in "\(name), \(value)" },
        setInTerminal: { name, value in "\(name) · \(value) · set in the terminal" },
        sendFailed: "Could not send the message.",
        upNext: "Up next",
        upNextCount: { n in "Up next · \(n)" },
        editingQueued: "Editing a queued message",
        alreadySent: "That message has already been sent."
    )

    static let zhHans = ComposerStrings(
        placeholder: "给 agent 发消息…",
        placeholderQueued: "消息将排队…",
        placeholderSteer: "消息将转向当前任务…",
        placeholderTerminal: "由终端控制",
        placeholderOffline: "设备已离线",
        send: "发送",
        queue: "排队",
        answer: "回答",
        placeholderAnswer: "输入你的回答…",
        interruptAndSend: "中断并发送",
        sendOptions: "发送选项",
        attach: "添加附件",
        attachTooMany: { max in "最多 \(max) 个附件。" },
        attachTooLarge: { name, max in "\(name) 超过 \(max)。" },
        attachFailed: { name in "无法读取 \(name)。" },
        textTooLong: "消息过长（上限 64 KiB）。",
        deliveryUnconfirmed: "未确认送达",
        deliveryUnconfirmedBody: "网关没有确认你的上一条消息。",
        micStart: "开始语音输入",
        model: "模型",
        permissionMode: "权限模式",
        effort: "思考强度",
        modelCard: "模型与思考强度",
        speed: { tier in "速度：\(tier)" },
        speedStandard: "标准",
        option: { name, value in "\(name)：\(value)" },
        setInTerminal: { name, value in "\(name) · \(value) · 在终端设置" },
        sendFailed: "无法发送消息。",
        upNext: "待发送",
        upNextCount: { n in "待发送 · \(n)" },
        editingQueued: "正在编辑排队的消息",
        alreadySent: "这条消息已经发出。"
    )

    /// The table an interface language reads.
    public static func of(_ language: InterfaceLanguage) -> ComposerStrings {
        switch language {
        case .en: .en
        case .zhHans: .zhHans
        }
    }
}
