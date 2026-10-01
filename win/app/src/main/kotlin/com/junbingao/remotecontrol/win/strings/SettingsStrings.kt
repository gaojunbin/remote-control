// Ported from web/src/strings.ts and web/src/strings.zh-Hans.ts. Keep the two
// tables of this file in step with the web's: the English is the source, and
// the Chinese is the web's own translation, copied rather than re-translated.

package com.junbingao.remotecontrol.win.strings

import com.junbingao.remotecontrol.win.standin.InterfaceLanguage

/**
 * `docs/DESIGN.md` § "The Settings screen": four groups, and every row a
 * title with one sentence under it. A row whose state has something to say
 * says it in place of that sentence, so none of these is a footnote.
 */
class SettingsStrings(
    val title: String,
    val account: String,
    val whileAway: String,
    val voice: String,
    val reading: String,
    val usersNote: String,
    val changePassword: String,
    val changePasswordNote: String,
    val signOut: String,
    val signOutNote: String,
    val signOutConfirm: String,
    val notify: String,
    val notifyNote: String,
    val pushBlocked: String,
    val pushUnsupported: String,
    val pushServerDisabled: String,
    /** A35: the one switch the account owns, not the browser. */
    val resumeAfterLimit: String,
    val resumeAfterLimitNote: String,
    val resumeUnavailable: String,
    /** A44: the web transcribes only on the gateway, so this row names it and offers nothing. */
    val transcribe: String,
    val transcribeGateway: String,
    val transcribeNote: String,
    val voiceServerDisabled: String,
    val polish: String,
    val polishNote: String,
    val polishServerDisabled: String,
    val polishModel: String,
    val polishModelNote: String,
    val polishModelsFailed: String,
    val polishChooseModel: String,
    val polishStrength: String,
    val polishStrengthNote: String,
    val polishModerate: String,
    val polishStrong: String,
    val language: String,
    val languageNote: String,
    val timelineDetail: String,
    val timelineDetailNote: String,
    /** The caption that closes the screen. The numbers come from the running code. */
    val versions: (String, String) -> String,
    /** The header dot's word, which is read aloud and shown on hover, never printed. */
    val connected: String,
    val connecting: String,
    val offline: String,
) {
    companion object {
        val en = SettingsStrings(
            title = "Settings",
            account = "Account",
            whileAway = "While you're away",
            voice = "Voice",
            reading = "Reading",
            usersNote = "Accounts on this gateway, and whether anyone can create one.",
            changePassword = "Change password",
            changePasswordNote = "The current password and the new one.",
            signOut = "Sign out",
            signOutNote = "Cached sessions and drafts leave this device. Nothing changes on your machines.",
            signOutConfirm = "Sign out of this gateway?",
            notify = "Notify me",
            notifyNote = "Which device and session needs you, and nothing else.",
            pushBlocked = "Blocked in browser settings.",
            pushUnsupported = "This browser does not support push notifications.",
            pushServerDisabled = "The gateway has web push disabled.",
            resumeAfterLimit = "Resume after the limit resets",
            resumeAfterLimitNote = "When Claude Code or Codex stops at a usage limit, the device continues the session a minute after the limit resets.",
            resumeUnavailable = "Your gateway does not offer this yet.",
            transcribe = "Transcribe",
            transcribeGateway = "Gateway",
            transcribeNote = "Your gateway transcribes and recognises the language itself.",
            voiceServerDisabled = "This gateway has no transcription service configured.",
            polish = "Polish dictation with AI",
            polishNote = "Sends what you dictated and the last few messages to this gateway's model. Nothing is sent while it is off.",
            polishServerDisabled = "This gateway has no polish model configured",
            polishModel = "Model",
            polishModelNote = "From the list this gateway serves.",
            polishModelsFailed = "The model list could not be loaded.",
            polishChooseModel = "Choose a model",
            polishStrength = "Strength",
            polishStrengthNote = "Moderate cleans up. Strong also restructures and resolves references.",
            polishModerate = "Moderate",
            polishStrong = "Strong",
            language = "Language",
            languageNote = "The app's own words only; what the agent wrote stays as written.",
            timelineDetail = "Detail",
            timelineDetailNote = "Simple shows only what is written to you. Detailed adds thinking, tool calls and the task list.",
            versions = { gateway, protocol -> "Gateway $gateway · Protocol $protocol" },
            connected = "Connected",
            connecting = "Connecting",
            offline = "Offline",
        )

        val zhHans = SettingsStrings(
            title = "设置",
            account = "账户",
            whileAway = "你不在时",
            voice = "语音",
            reading = "阅读",
            usersNote = "此网关上的账户，以及是否开放注册。",
            changePassword = "修改密码",
            changePasswordNote = "输入当前密码和新密码。",
            signOut = "退出登录",
            signOutNote = "缓存的会话和草稿将从此设备移除，你的机器不受影响。",
            signOutConfirm = "退出此网关？",
            notify = "通知我",
            notifyNote = "只告诉你哪台设备的哪个会话需要你，别无其他。",
            pushBlocked = "已在浏览器设置中屏蔽。",
            pushUnsupported = "此浏览器不支持推送通知。",
            pushServerDisabled = "网关未启用网页推送。",
            resumeAfterLimit = "限额恢复后继续会话",
            resumeAfterLimitNote = "Claude Code 或 Codex 因用量限额停下时，设备会在限额恢复一分钟后继续该会话。",
            resumeUnavailable = "此网关尚不支持该功能。",
            transcribe = "转写",
            transcribeGateway = "网关",
            transcribeNote = "由你的网关转写，并自动识别语言。",
            voiceServerDisabled = "此网关未配置转写服务。",
            polish = "用 AI 润色口述",
            polishNote = "开启后，会把你的听写内容和最近几条消息发给此网关配置的模型；关闭时不发送任何内容。",
            polishServerDisabled = "此网关未配置润色模型",
            polishModel = "模型",
            polishModelNote = "从此网关提供的列表中选择。",
            polishModelsFailed = "无法加载模型列表。",
            polishChooseModel = "选择模型",
            polishStrength = "力度",
            polishStrengthNote = "“适度”只做清理；“加强”还会重组语句并明确指代。",
            polishModerate = "适度",
            polishStrong = "加强",
            language = "语言",
            languageNote = "只改应用自身的文案，代理写的内容保持原样。",
            timelineDetail = "详细程度",
            timelineDetailNote = "简约只显示写给你的内容。详细会加上思考、工具调用和任务清单。",
            versions = { gateway, protocol -> "网关 $gateway · 协议 $protocol" },
            connected = "已连接",
            connecting = "连接中",
            offline = "离线",
        )

        /** The table an interface language reads. */
        fun of(language: InterfaceLanguage): SettingsStrings = when (language) {
            InterfaceLanguage.en -> en
            InterfaceLanguage.zhHans -> zhHans
        }
    }
}
