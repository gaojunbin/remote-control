// Ported from web/src/strings.ts and web/src/strings.zh-Hans.ts. Keep the two
// tables of this file in step with the web's: the English is the source, and
// the Chinese is the web's own translation, copied rather than re-translated.

import Foundation
import RCCore

/// A24: the admin's accounts screen.
public struct UsersStrings: Sendable {
    public let title: String
    public let registration: String
    public let registrationCaption: String
    public let add: String
    public let meta: @Sendable (String, String) -> String
    public let deviceCount: @Sendable (Int) -> String
    public let lastSignIn: @Sendable (String) -> String
    public let never: String
    public let resetPassword: String
    public let resetPasswordTitle: @Sendable (String) -> String
    public let disable: String
    public let enable: String
    public let deleteAction: String
    public let deleteTitle: String
    public let deleteBody: @Sendable (String) -> String
    public let deleteBodyDevices: @Sendable (String, Int) -> String
    public let deleteConfirm: String
    public let loadFailed: String
    public let registrationFailed: String
}

extension UsersStrings {
    static let en = UsersStrings(
        title: "Users",
        registration: "Registration",
        registrationCaption: "Anyone with the gateway address can create an account",
        add: "Add user",
        meta: { role, state in "\(role) · \(state)" },
        deviceCount: { n in "\(n) \(n == 1 ? "device" : "devices")" },
        lastSignIn: { rel in "last sign-in \(rel)" },
        never: "never",
        resetPassword: "Reset password",
        resetPasswordTitle: { username in "Reset password for \(username)" },
        disable: "Disable",
        enable: "Enable",
        deleteAction: "Delete",
        deleteTitle: "Delete account",
        deleteBody: { username in "Delete \(username)? Its sign-ins stop working at once and nothing of it is kept." },
        deleteBodyDevices: { username, n in "Delete \(username) and its \(n) \(n == 1 ? "device" : "devices")? The devices are revoked and their sessions leave this gateway. The machines keep their agents and transcripts." },
        deleteConfirm: "Delete account",
        loadFailed: "Could not load the accounts.",
        registrationFailed: "Could not change that setting."
    )

    static let zhHans = UsersStrings(
        title: "用户",
        registration: "开放注册",
        registrationCaption: "知道网关地址的人都可以创建账户",
        add: "添加用户",
        meta: { role, state in "\(role) · \(state)" },
        deviceCount: { n in "\(n) 台设备" },
        lastSignIn: { rel in "上次登录 \(rel)" },
        never: "从未",
        resetPassword: "重设密码",
        resetPasswordTitle: { username in "为 \(username) 重设密码" },
        disable: "禁用",
        enable: "启用",
        deleteAction: "删除",
        deleteTitle: "删除账户",
        deleteBody: { username in "删除 \(username)？它的登录会立即失效，不保留任何内容。" },
        deleteBodyDevices: { username, n in "删除 \(username) 及其 \(n) 台设备？这些设备将被吊销，其会话会离开本网关。机器上的 agent 和记录不受影响。" },
        deleteConfirm: "删除账户",
        loadFailed: "无法加载账户列表。",
        registrationFailed: "无法修改该设置。"
    )

    /// The table an interface language reads.
    public static func of(_ language: InterfaceLanguage) -> UsersStrings {
        switch language {
        case .en: .en
        case .zhHans: .zhHans
        }
    }
}
