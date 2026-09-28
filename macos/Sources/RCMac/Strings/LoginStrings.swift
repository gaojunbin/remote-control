// Ported from web/src/strings.ts and web/src/strings.zh-Hans.ts. Keep the two
// tables of this file in step with the web's: the English is the source, and
// the Chinese is the web's own translation, copied rather than re-translated.

import Foundation
import RCCore

/// The `login` group of the web's string table.
public struct LoginStrings: Sendable {
    public let title: String
    public let subtitle: String
    public let registerSubtitle: String
    public let usernamePlaceholder: String
    public let passwordPlaceholder: String
    public let submit: String
    public let signingIn: String
    public let createAccount: String
    public let creating: String
    public let createAccountLink: String
    public let signInInstead: String
    public let failed: String
    public let registrationClosed: String
    public let unreachable: String
    public let rateLimited: String
}

extension LoginStrings {
    static let en = LoginStrings(
        title: "Remote Control",
        subtitle: "Sign in to reach your devices.",
        registerSubtitle: "Create an account on this gateway.",
        usernamePlaceholder: "Username",
        passwordPlaceholder: "Password",
        submit: "Sign in",
        signingIn: "Signing in…",
        createAccount: "Create account",
        creating: "Creating…",
        createAccountLink: "Create an account",
        signInInstead: "Sign in instead",
        failed: "Wrong username or password.",
        registrationClosed: "Registration is closed.",
        unreachable: "Cannot reach the gateway.",
        rateLimited: "Too many attempts. Wait a minute and try again."
    )

    static let zhHans = LoginStrings(
        title: "Remote Control",
        subtitle: "登录后即可访问你的设备。",
        registerSubtitle: "在此网关上创建账户。",
        usernamePlaceholder: "用户名",
        passwordPlaceholder: "密码",
        submit: "登录",
        signingIn: "登录中…",
        createAccount: "创建账户",
        creating: "创建中…",
        createAccountLink: "创建账户",
        signInInstead: "返回登录",
        failed: "用户名或密码错误。",
        registrationClosed: "注册已关闭。",
        unreachable: "无法连接网关。",
        rateLimited: "尝试次数过多。请等待一分钟后重试。"
    )

    /// The table an interface language reads.
    public static func of(_ language: InterfaceLanguage) -> LoginStrings {
        switch language {
        case .en: .en
        case .zhHans: .zhHans
        }
    }
}
