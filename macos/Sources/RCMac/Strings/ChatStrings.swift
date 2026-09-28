// Ported from web/src/strings.ts and web/src/strings.zh-Hans.ts. Keep the two
// tables of this file in step with the web's: the English is the source, and
// the Chinese is the web's own translation, copied rather than re-translated.

import Foundation
import RCCore

/// The `chat` group of the web's string table.
public struct ChatStrings: Sendable {
    public let newSession: String
    public let searchPlaceholder: String
    public let sidebarFooter: @Sendable (Int, Int) -> String
    public let todos: @Sendable (Int, Int) -> String
    public let todosTitle: String
    public let stop: String
    public let stopping: String
    public let takeOver: String
    public let backToLatest: String
    public let newUpdates: @Sendable (Int) -> String
    public let loadingHistory: String
    public let historyStart: String
    public let thoughtFor: @Sendable (String) -> String
    public let thinking: String
    public let openFullOutput: String
    public let outputTruncated: String
    public let inputTruncated: String
    public let input: String
    public let output: String
    public let patchTruncated: String
    public let subtasks: @Sendable (Int) -> String
    public let attachments: @Sendable (Int) -> String
    public let running: String
    public let failed: String
    public let cancelled: String
    /// The caption above a message nobody here sent: typed at the keyboard, or
    /// put into the conversation by another agent (A30).
    public let fromTerminal: String
    public let fromAgent: String
    /// A35: the one message the device wrote for the person, and why.
    public let fromResume: String
    public let sending: String
    public let steering: String
    public let deliveryAbsorbed: String
    public let attachHintChannel: String
    public let attachHintDaemon: String
    public let attachHintExtension: String
    public let attachHintLeader: String
    public let attachHintRestart: String
    public let queuedRemove: String
    public let approvalNeeded: String
    public let approvalResolved: @Sendable (String, String) -> String
    public let approvalElsewhere: String
    public let approvalExpired: String
    public let questionResolved: String
    /// A20: the question was answered in the CLI's own dialog, not from here.
    public let questionAnsweredInTerminal: String
    public let questionExpired: String
    public let submitAnswer: String
    public let freeTextPlaceholder: String
    public let secretPlaceholder: String
    public let sessionErrored: String
    public let emptyTimeline: String
    public let turnInterrupted: String
    public let turnFailed: String
    public let copyCode: String
    /// A35 — a session the usage limit stopped, and the resume the device holds
    /// for it (`docs/DESIGN.md` § "Paused by the usage limit"). The notice above
    /// the transcript reads "Paused by the usage limit · resumes 3:50 PM", with
    /// "about" when the device estimated the time and the try appended once a
    /// resume has run into the limit again.
    public let pausedByLimit: String
    public let resumesAt: @Sendable (String) -> String
    public let resumesAbout: @Sendable (String) -> String
    public let resumeSecondTry: String
    public let resumeThirdTry: String
    public let resumeChange: String
    public let resumeCancel: String
    public let resumeAt: String
    public let resumeSet: String
    public let resumeTooSoon: String
    public let resumeTooFar: String
    /// The device's own steps, as rows of the timeline in the notice voice.
    public let resumeScheduledAt: @Sendable (String) -> String
    public let resumeScheduled: String
    public let resumeMovedTo: @Sendable (String) -> String
    public let resumeMoved: String
    public let resumeCancelled: String
    public let resumeDropped: String
    /// How a turn the vendor's usage limit ended closes.
    public let turnLimit: String
    public let turnLimitResets: @Sendable (String) -> String
}

extension ChatStrings {
    static let en = ChatStrings(
        newSession: "New session",
        searchPlaceholder: "Search",
        sidebarFooter: { devices, waiting in "\(devices) \(devices == 1 ? "device" : "devices") · \(waiting) waiting" },
        todos: { done, total in "Todos \(done)/\(total)" },
        todosTitle: "Todos",
        stop: "Stop",
        stopping: "Stopping…",
        takeOver: "Take over",
        backToLatest: "Back to latest",
        newUpdates: { n in "\(n) new" },
        loadingHistory: "Loading earlier messages…",
        historyStart: "Start of the conversation",
        thoughtFor: { s in "Thought for \(s)" },
        thinking: "Thinking…",
        openFullOutput: "Open full output",
        outputTruncated: "output truncated",
        inputTruncated: "input truncated",
        input: "Input",
        output: "Output",
        patchTruncated: "patch truncated",
        subtasks: { n in "\(n) \(n == 1 ? "step" : "steps")" },
        attachments: { n in "\(n) \(n == 1 ? "attachment" : "attachments")" },
        running: "running",
        failed: "failed",
        cancelled: "cancelled",
        fromTerminal: "terminal",
        fromAgent: "from another agent",
        fromResume: "Sent for you after the limit reset",
        sending: "Sending…",
        steering: "the agent will read it at its next step",
        deliveryAbsorbed: "will be re-sent",
        attachHintChannel: "Start claude through the Remote Control shim to control it from here",
        attachHintDaemon: "Run rc-client codex setup on the device to attach its Codex sessions",
        attachHintExtension: "Run rc-client pi setup on the device to attach its pi sessions",
        attachHintLeader: "Run rc-client grok setup on the device, then restart Grok to attach its sessions",
        attachHintRestart: "This terminal session was started without the attachment; restart it to control it from here",
        queuedRemove: "Remove from queue",
        approvalNeeded: "Needs your approval",
        approvalResolved: { by, label in "\(label) · decided by \(by)" },
        approvalElsewhere: "Answered in the terminal",
        approvalExpired: "This request expired.",
        questionResolved: "Answered",
        questionAnsweredInTerminal: "Answered in the terminal",
        questionExpired: "This question expired.",
        submitAnswer: "Submit",
        freeTextPlaceholder: "Type your answer…",
        secretPlaceholder: "Value is not stored or logged",
        sessionErrored: "The session reported an error.",
        emptyTimeline: "No messages yet. Say something to get started.",
        turnInterrupted: "Turn interrupted",
        turnFailed: "Turn failed",
        copyCode: "Copy code",
        pausedByLimit: "Paused by the usage limit",
        resumesAt: { when in "resumes \(when)" },
        resumesAbout: { when in "resumes about \(when)" },
        resumeSecondTry: "second try",
        resumeThirdTry: "third try",
        resumeChange: "Change",
        resumeCancel: "Cancel",
        resumeAt: "Resume at",
        resumeSet: "Set",
        resumeTooSoon: "Pick a time at least a minute from now.",
        resumeTooFar: "Pick a time within the next eight days.",
        resumeScheduledAt: { when in "Resume scheduled for \(when)" },
        resumeScheduled: "Resume scheduled",
        resumeMovedTo: { when in "Resume moved to \(when)" },
        resumeMoved: "Resume moved",
        resumeCancelled: "Resume cancelled",
        resumeDropped: "Not resumed",
        turnLimit: "Ended at the usage limit",
        turnLimitResets: { when in "Ended at the usage limit · resets \(when)" }
    )

    static let zhHans = ChatStrings(
        newSession: "新建会话",
        searchPlaceholder: "搜索",
        sidebarFooter: { devices, waiting in "\(devices) 台设备 · \(waiting) 个等待" },
        todos: { done, total in "待办 \(done)/\(total)" },
        todosTitle: "待办",
        stop: "停止",
        stopping: "停止中…",
        takeOver: "接管",
        backToLatest: "回到最新",
        newUpdates: { n in "\(n) 条新消息" },
        loadingHistory: "加载更早的消息…",
        historyStart: "对话开始",
        thoughtFor: { s in "思考了 \(s)" },
        thinking: "思考中…",
        openFullOutput: "查看完整输出",
        outputTruncated: "输出已截断",
        inputTruncated: "输入已截断",
        input: "输入",
        output: "输出",
        patchTruncated: "补丁已截断",
        subtasks: { n in "\(n) 步" },
        attachments: { n in "\(n) 个附件" },
        running: "运行中",
        failed: "失败",
        cancelled: "已取消",
        fromTerminal: "终端",
        fromAgent: "来自其他代理",
        fromResume: "限额恢复后代你发送",
        sending: "发送中…",
        steering: "agent 会在下一步读到它",
        deliveryAbsorbed: "将重新发送",
        attachHintChannel: "通过 Remote Control 垫片启动 claude，才能从这里控制它",
        attachHintDaemon: "在该设备上运行 rc-client codex setup，才能附着它的 Codex 会话",
        attachHintExtension: "在该设备上运行 rc-client pi setup，才能附着它的 pi 会话",
        attachHintLeader: "在该设备上运行 rc-client grok setup 并重启 Grok，才能附着它的会话",
        attachHintRestart: "这个终端会话启动时没有附着；重启它才能从这里控制",
        queuedRemove: "从队列中移除",
        approvalNeeded: "需要你的批准",
        approvalResolved: { by, label in "\(label) · 由\(by)决定" },
        approvalElsewhere: "已在终端回答",
        approvalExpired: "该请求已过期。",
        questionResolved: "已回答",
        questionAnsweredInTerminal: "已在终端回答",
        questionExpired: "该问题已过期。",
        submitAnswer: "提交",
        freeTextPlaceholder: "输入你的回答…",
        secretPlaceholder: "该值不会被保存或记录",
        sessionErrored: "会话报告了一个错误。",
        emptyTimeline: "还没有消息。说点什么开始吧。",
        turnInterrupted: "本轮已中断",
        turnFailed: "本轮失败",
        copyCode: "复制代码",
        pausedByLimit: "已因用量限额暂停",
        resumesAt: { when in "将于 \(when) 继续" },
        resumesAbout: { when in "约于 \(when) 继续" },
        resumeSecondTry: "第二次尝试",
        resumeThirdTry: "第三次尝试",
        resumeChange: "更改",
        resumeCancel: "取消",
        resumeAt: "继续时间",
        resumeSet: "设定",
        resumeTooSoon: "请选择至少一分钟之后的时间。",
        resumeTooFar: "请选择八天以内的时间。",
        resumeScheduledAt: { when in "已安排于 \(when) 继续" },
        resumeScheduled: "已安排继续",
        resumeMovedTo: { when in "继续时间改为 \(when)" },
        resumeMoved: "继续时间已更改",
        resumeCancelled: "已取消继续",
        resumeDropped: "未继续",
        turnLimit: "本轮因用量限额结束",
        turnLimitResets: { when in "本轮因用量限额结束 · \(when) 恢复" }
    )

    /// The table an interface language reads.
    public static func of(_ language: InterfaceLanguage) -> ChatStrings {
        switch language {
        case .en: .en
        case .zhHans: .zhHans
        }
    }
}
