// Ported from web/src/strings.ts and web/src/strings.zh-Hans.ts. Keep the two
// tables of this file in step with the web's: the English is the source, and
// the Chinese is the web's own translation, copied rather than re-translated.

import Foundation
import RCCore

/// A33: the page one device has. Vendor names, plan words, tiers, emails and
/// hosts are what the device reported and are never translated.
public struct DevicePageStrings: Sendable {
    public let back: String
    public let gone: String
    public let goneHint: String
    public let refresh: String
    public let accountOf: @Sendable (String) -> String
    public let apiKeyOf: @Sendable (String) -> String
    public let notSignedIn: String
    public let checking: String
    public let offlineQuota: String
    public let windowHours: @Sendable (Int) -> String
    public let windowDays: @Sendable (Int) -> String
    public let windowMinutes: @Sendable (Int) -> String
    public let percent: @Sendable (Int) -> String
    public let resets: @Sendable (String) -> String
    public let usage: @Sendable (String) -> String
}

extension DevicePageStrings {
    static let en = DevicePageStrings(
        back: "Devices",
        gone: "This device is no longer here.",
        goneHint: "It was revoked, or it never reached this gateway.",
        refresh: "Refresh",
        accountOf: { vendor in "\(vendor) account" },
        apiKeyOf: { vendor in "\(vendor) API key" },
        notSignedIn: "Not signed in",
        checking: "Checking…",
        offlineQuota: "Offline · quota unavailable",
        windowHours: { n in "\(n)-hour" },
        windowDays: { n in "\(n)-day" },
        windowMinutes: { n in "\(n)-minute" },
        percent: { n in "\(n)%" },
        resets: { when in "resets \(when)" },
        usage: { window in "\(window) usage" }
    )

    static let zhHans = DevicePageStrings(
        back: "设备",
        gone: "该设备已不在这里。",
        goneHint: "它已被吊销，或从未连接到本网关。",
        refresh: "刷新",
        accountOf: { vendor in "\(vendor) 账户" },
        apiKeyOf: { vendor in "\(vendor) API key" },
        notSignedIn: "未登录",
        checking: "检查中…",
        offlineQuota: "离线 · 无法获取配额",
        windowHours: { n in "\(n) 小时" },
        windowDays: { n in "\(n) 天" },
        windowMinutes: { n in "\(n) 分钟" },
        percent: { n in "\(n)%" },
        resets: { when in "\(when) 重置" },
        usage: { window in "\(window)用量" }
    )

    /// The table an interface language reads.
    public static func of(_ language: InterfaceLanguage) -> DevicePageStrings {
        switch language {
        case .en: .en
        case .zhHans: .zhHans
        }
    }
}
