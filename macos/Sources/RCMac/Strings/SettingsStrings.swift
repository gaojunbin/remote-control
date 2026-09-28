// Ported from web/src/strings.ts and web/src/strings.zh-Hans.ts. Keep the two
// tables of this file in step with the web's: the English is the source, and
// the Chinese is the web's own translation, copied rather than re-translated.

import Foundation
import RCCore

/// `docs/DESIGN.md` § "The Settings screen": four groups, and every row a
/// title with one sentence under it. A row whose state has something to say
/// says it in place of that sentence, so none of these is a footnote.
public struct SettingsStrings: Sendable {
    public let title: String
    public let account: String
    public let whileAway: String
    public let voice: String
    public let reading: String
    public let usersNote: String
    public let changePassword: String
    public let changePasswordNote: String
    public let signOut: String
    public let signOutNote: String
    public let signOutConfirm: String
    public let notify: String
    public let notifyNote: String
    public let pushBlocked: String
    public let pushUnsupported: String
    public let pushServerDisabled: String
    /// A35: the one switch the account owns, not the browser.
    public let resumeAfterLimit: String
    public let resumeAfterLimitNote: String
    public let resumeUnavailable: String
    /// A44: the web transcribes only on the gateway, so this row names it and offers nothing.
    public let transcribe: String
    public let transcribeGateway: String
    public let transcribeNote: String
    public let voiceServerDisabled: String
    public let polish: String
    public let polishNote: String
    public let polishServerDisabled: String
    public let polishModel: String
    public let polishModelNote: String
    public let polishModelsFailed: String
    public let polishChooseModel: String
    public let polishStrength: String
    public let polishStrengthNote: String
    public let polishModerate: String
    public let polishStrong: String
    public let language: String
    public let languageNote: String
    public let timelineDetail: String
    public let timelineDetailNote: String
    /// The caption that closes the screen. The numbers come from the running code.
    public let versions: @Sendable (String, String) -> String
    /// The header dot's word, which is read aloud and shown on hover, never printed.
    public let connected: String
    public let connecting: String
    public let offline: String
}

extension SettingsStrings {
    static let en = SettingsStrings(
        title: "Settings",
        account: "Account",
        whileAway: "While you're away",
        voice: "Voice",
        reading: "Reading",
        usersNote: "Accounts on this gateway, and whether anyone can create one.",
        changePassword: "Change password",
        changePasswordNote: "The current password and the new one.",
        signOut: "Sign out",
        signOutNote: "Cached sessions and drafts leave this device. Nothing changes on your machines.",
        signOutConfirm: "Sign out of this gateway?",
        notify: "Notify me",
        notifyNote: "Which device and session needs you, and nothing else.",
        pushBlocked: "Blocked in browser settings.",
        pushUnsupported: "This browser does not support push notifications.",
        pushServerDisabled: "The gateway has web push disabled.",
        resumeAfterLimit: "Resume after the limit resets",
        resumeAfterLimitNote: "When Claude Code or Codex stops at a usage limit, the device continues the session a minute after the limit resets.",
        resumeUnavailable: "Your gateway does not offer this yet.",
        transcribe: "Transcribe",
        transcribeGateway: "Gateway",
        transcribeNote: "Your gateway transcribes and recognises the language itself.",
        voiceServerDisabled: "This gateway has no transcription service configured.",
        polish: "Polish dictation with AI",
        polishNote: "Sends what you dictated and the last few messages to this gateway's model. Nothing is sent while it is off.",
        polishServerDisabled: "This gateway has no polish model configured",
        polishModel: "Model",
        polishModelNote: "From the list this gateway serves.",
        polishModelsFailed: "The model list could not be loaded.",
        polishChooseModel: "Choose a model",
        polishStrength: "Strength",
        polishStrengthNote: "Moderate cleans up. Strong also restructures and resolves references.",
        polishModerate: "Moderate",
        polishStrong: "Strong",
        language: "Language",
        languageNote: "The app's own words only; what the agent wrote stays as written.",
        timelineDetail: "Detail",
        timelineDetailNote: "Simple shows only what is written to you. Detailed adds thinking, tool calls and the task list.",
        versions: { gateway, `protocol` in "Gateway \(gateway) · Protocol \(`protocol`)" },
        connected: "Connected",
        connecting: "Connecting",
        offline: "Offline"
    )

    static let zhHans = SettingsStrings(
        title: "设置",
        account: "账户",
        whileAway: "你不在时",
        voice: "语音",
        reading: "阅读",
        usersNote: "此网关上的账户，以及是否开放注册。",
        changePassword: "修改密码",
        changePasswordNote: "输入当前密码和新密码。",
        signOut: "退出登录",
        signOutNote: "缓存的会话和草稿将从此设备移除，你的机器不受影响。",
        signOutConfirm: "退出此网关？",
        notify: "通知我",
        notifyNote: "只告诉你哪台设备的哪个会话需要你，别无其他。",
        pushBlocked: "已在浏览器设置中屏蔽。",
        pushUnsupported: "此浏览器不支持推送通知。",
        pushServerDisabled: "网关未启用网页推送。",
        resumeAfterLimit: "限额恢复后继续会话",
        resumeAfterLimitNote: "Claude Code 或 Codex 因用量限额停下时，设备会在限额恢复一分钟后继续该会话。",
        resumeUnavailable: "此网关尚不支持该功能。",
        transcribe: "转写",
        transcribeGateway: "网关",
        transcribeNote: "由你的网关转写，并自动识别语言。",
        voiceServerDisabled: "此网关未配置转写服务。",
        polish: "用 AI 润色口述",
        polishNote: "开启后，会把你的听写内容和最近几条消息发给此网关配置的模型；关闭时不发送任何内容。",
        polishServerDisabled: "此网关未配置润色模型",
        polishModel: "模型",
        polishModelNote: "从此网关提供的列表中选择。",
        polishModelsFailed: "无法加载模型列表。",
        polishChooseModel: "选择模型",
        polishStrength: "力度",
        polishStrengthNote: "“适度”只做清理；“加强”还会重组语句并明确指代。",
        polishModerate: "适度",
        polishStrong: "加强",
        language: "语言",
        languageNote: "只改应用自身的文案，代理写的内容保持原样。",
        timelineDetail: "详细程度",
        timelineDetailNote: "简约只显示写给你的内容。详细会加上思考、工具调用和任务清单。",
        versions: { gateway, `protocol` in "网关 \(gateway) · 协议 \(`protocol`)" },
        connected: "已连接",
        connecting: "连接中",
        offline: "离线"
    )

    /// The table an interface language reads.
    public static func of(_ language: InterfaceLanguage) -> SettingsStrings {
        switch language {
        case .en: .en
        case .zhHans: .zhHans
        }
    }
}
