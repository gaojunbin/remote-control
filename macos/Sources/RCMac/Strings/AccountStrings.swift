// Ported from web/src/strings.ts and web/src/strings.zh-Hans.ts. Keep the two
// tables of this file in step with the web's: the English is the source, and
// the Chinese is the web's own translation, copied rather than re-translated.

import Foundation
import RCCore

/// A24: the words about an account, wherever one is named or typed.
public struct AccountStrings: Sendable {
    public let username: String
    public let password: String
    public let currentPassword: String
    public let newPassword: String
    public let role: String
    public let rules: String
    public let taken: String
    public let disabled: String
    public let wrongCurrentPassword: String
    public let notAllowed: String
    public let gone: String
}

extension AccountStrings {
    static let en = AccountStrings(
        username: "Username",
        password: "Password",
        currentPassword: "Current password",
        newPassword: "New password",
        role: "Role",
        rules: "Usernames are 3 to 32 characters: lower-case letters, digits, dots, underscores and hyphens. Passwords are 8 characters or more.",
        taken: "That username is taken.",
        disabled: "This account is disabled.",
        wrongCurrentPassword: "That is not your current password.",
        notAllowed: "This account cannot be changed.",
        gone: "That account no longer exists."
    )

    static let zhHans = AccountStrings(
        username: "用户名",
        password: "密码",
        currentPassword: "当前密码",
        newPassword: "新密码",
        role: "角色",
        rules: "用户名为 3 到 32 个字符：小写字母、数字、点、下划线和连字符。密码至少 8 个字符。",
        taken: "该用户名已被占用。",
        disabled: "该账户已被禁用。",
        wrongCurrentPassword: "当前密码不正确。",
        notAllowed: "该账户无法修改。",
        gone: "该账户已不存在。"
    )

    /// The table an interface language reads.
    public static func of(_ language: InterfaceLanguage) -> AccountStrings {
        switch language {
        case .en: .en
        case .zhHans: .zhHans
        }
    }
}
