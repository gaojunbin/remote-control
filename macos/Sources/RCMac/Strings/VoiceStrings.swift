// Ported from web/src/strings.ts and web/src/strings.zh-Hans.ts. Keep the two
// tables of this file in step with the web's: the English is the source, and
// the Chinese is the web's own translation, copied rather than re-translated.

import Foundation
import RCCore

/// The `voice` group of the web's string table.
public struct VoiceStrings: Sendable {
    public let transcribing: String
    public let connecting: String
    public let done: String
    /// What Done gave way to is waiting for: the backend's last word.
    public let finishing: String
    public let listeningFor: @Sendable (String) -> String
    public let denied: String
    public let unsupported: String
    public let failed: String
    /// A29: the one word the status line reads while the model is working.
    public let polishing: String
    public let polished: String
    public let undo: String
    public let polishFailed: String
}

extension VoiceStrings {
    static let en = VoiceStrings(
        transcribing: "Transcribing live · edit before sending",
        connecting: "Connecting…",
        done: "Done",
        finishing: "Finishing the transcript",
        listeningFor: { elapsed in "Listening for \(elapsed)" },
        denied: "Microphone permission was denied.",
        unsupported: "This browser cannot capture audio.",
        failed: "Transcription failed.",
        polishing: "Polishing…",
        polished: "Polished",
        undo: "Undo",
        polishFailed: "Polishing failed, your words are unchanged"
    )

    static let zhHans = VoiceStrings(
        transcribing: "实时转写 · 发送前可编辑",
        connecting: "连接中…",
        done: "完成",
        finishing: "正在整理转写结果",
        listeningFor: { elapsed in "已录制 \(elapsed)" },
        denied: "麦克风权限被拒绝。",
        unsupported: "此浏览器无法采集音频。",
        failed: "转写失败。",
        polishing: "润色中…",
        polished: "已润色",
        undo: "撤销",
        polishFailed: "润色失败，你的原话未改动"
    )

    /// The table an interface language reads.
    public static func of(_ language: InterfaceLanguage) -> VoiceStrings {
        switch language {
        case .en: .en
        case .zhHans: .zhHans
        }
    }
}
