// Ported from web/src/strings.ts and web/src/strings.zh-Hans.ts. Keep the two
// tables of this file in step with the web's: the English is the source, and
// the Chinese is the web's own translation, copied rather than re-translated.

import Foundation
import RCCore

/// The `devices` group of the web's string table.
public struct DevicesStrings: Sendable {
    public let title: String
    public let subtitleCount: @Sendable (Int, Int) -> String
    public let empty: String
    public let emptyHint: String
    public let add: String
    public let sessionsCount: @Sendable (Int) -> String
    public let noSessions: String
    public let offline: String
    public let lastSeen: @Sendable (String) -> String
    public let renameTitle: String
    public let renameLabel: String
    /// A36: a device updates itself, so nothing says which client it runs. An
    /// app speaks only while an update runs or has failed, and the only action
    /// left is trying a failure again.
    public let retryUpdate: String
    public let updating: String
    public let updateFailed: @Sendable (String) -> String
    public let updateTitle: String
    public let updateBody: @Sendable (String, String?) -> String
    public let updateConfirm: String
    /// A38: the row's tap needs the same sentence, so the two share one key.
    public let deviceOffline: String
    public let updateNoBuild: String
    /// A38: rule 20 — the row's menu, in this order, and what a tap that opens
    /// nothing says in its place.
    public let showQuota: String
    public let noTerminal: String
    public let revokeTitle: String
    public let revokeBody: @Sendable (String) -> String
    public let revokeConfirm: String
    public let noAgents: String
    public let online: String
}

extension DevicesStrings {
    static let en = DevicesStrings(
        title: "Devices",
        subtitleCount: { online, total in "\(online) connected · \(total) \(total == 1 ? "device" : "devices")" },
        empty: "No devices yet.",
        emptyHint: "Add the machine where your coding agents are installed.",
        add: "Add device",
        sessionsCount: { n in "\(n) \(n == 1 ? "session" : "sessions")" },
        noSessions: "idle",
        offline: "offline",
        lastSeen: { rel in "last seen \(rel)" },
        renameTitle: "Rename device",
        renameLabel: "Device name",
        retryUpdate: "Retry update",
        updating: "Updating…",
        updateFailed: { message in "Update failed · \(message)" },
        updateTitle: "Update device",
        updateBody: { name, version in version == nil ? "Update \(name) to the gateway's client? Its service restarts; sessions it drives are stopped." : "Update \(name) to \(version ?? "")? Its service restarts; sessions it drives are stopped." },
        updateConfirm: "Update device",
        deviceOffline: "This device is offline.",
        updateNoBuild: "This gateway is not serving a client build.",
        showQuota: "Show quota",
        noTerminal: "This device does not offer a terminal.",
        revokeTitle: "Revoke device",
        revokeBody: { name in "Revoke \(name)? Its token stops working and its sessions leave this gateway. The machine keeps its agents and transcripts." },
        revokeConfirm: "Revoke device",
        noAgents: "No agents detected",
        online: "online"
    )

    static let zhHans = DevicesStrings(
        title: "设备",
        subtitleCount: { online, total in "已连接 \(online) · 共 \(total) 台设备" },
        empty: "还没有设备。",
        emptyHint: "添加安装了编程 agent 的机器。",
        add: "添加设备",
        sessionsCount: { n in "\(n) 个会话" },
        noSessions: "空闲",
        offline: "离线",
        lastSeen: { rel in "最后在线 \(rel)" },
        renameTitle: "重命名设备",
        renameLabel: "设备名称",
        retryUpdate: "重试更新",
        updating: "更新中…",
        updateFailed: { message in "更新失败 · \(message)" },
        updateTitle: "更新设备",
        updateBody: { name, version in version == nil ? "将 \(name) 更新到网关提供的客户端？它的服务会重启，由它驱动的会话会被停止。" : "将 \(name) 更新到 \(version ?? "")？它的服务会重启，由它驱动的会话会被停止。" },
        updateConfirm: "更新设备",
        deviceOffline: "此设备已离线。",
        updateNoBuild: "本网关未提供客户端安装包。",
        showQuota: "显示额度",
        noTerminal: "此设备不提供终端。",
        revokeTitle: "吊销设备",
        revokeBody: { name in "吊销 \(name)？它的令牌会失效，它的会话也会离开本网关。机器上的 agent 和记录都会保留。" },
        revokeConfirm: "吊销设备",
        noAgents: "未检测到 agent",
        online: "在线"
    )

    /// The table an interface language reads.
    public static func of(_ language: InterfaceLanguage) -> DevicesStrings {
        switch language {
        case .en: .en
        case .zhHans: .zhHans
        }
    }
}
