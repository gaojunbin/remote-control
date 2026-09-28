// Ported from web/src/strings.ts and web/src/strings.zh-Hans.ts. Keep the two
// tables of this file in step with the web's: the English is the source, and
// the Chinese is the web's own translation, copied rather than re-translated.

import Foundation
import RCCore

/// The `status` group of the web's string table.
public struct StatusStrings: Sendable {
    public let working: @Sendable (String) -> String
    public let workingQueued: @Sendable (String) -> String
    public let workingSteer: @Sendable (String) -> String
    public let needsApproval: String
    public let needsInput: String
    public let terminalControlled: String
    public let terminalBusy: String
    /// Added to the line above only where the agent can be taken over.
    public let takeOverToSend: String
    public let starting: String
    public let stopped: String
    public let errored: String
    public let idle: String
    public let offline: String
}

extension StatusStrings {
    static let en = StatusStrings(
        working: { agent in "\(agent) is working" },
        workingQueued: { agent in "\(agent) is working · your message will be queued" },
        workingSteer: { agent in "\(agent) is working · your message will steer the turn" },
        needsApproval: "Needs your approval",
        needsInput: "Waiting for your answer",
        terminalControlled: "Controlled by the terminal",
        terminalBusy: "a turn is running there",
        takeOverToSend: "take over to send",
        starting: "Starting the agent…",
        stopped: "Stopped",
        errored: "Errored",
        idle: "Idle",
        offline: "Device offline"
    )

    static let zhHans = StatusStrings(
        working: { agent in "\(agent) 正在工作" },
        workingQueued: { agent in "\(agent) 正在工作 · 消息将排队" },
        workingSteer: { agent in "\(agent) 正在工作 · 消息将转向当前任务" },
        needsApproval: "需要你的批准",
        needsInput: "等待你的回答",
        terminalControlled: "由终端控制",
        terminalBusy: "那边正在执行一轮任务",
        takeOverToSend: "接管后可发送",
        starting: "正在启动 agent…",
        stopped: "已停止",
        errored: "出错",
        idle: "空闲",
        offline: "设备离线"
    )

    /// The table an interface language reads.
    public static func of(_ language: InterfaceLanguage) -> StatusStrings {
        switch language {
        case .en: .en
        case .zhHans: .zhHans
        }
    }
}
