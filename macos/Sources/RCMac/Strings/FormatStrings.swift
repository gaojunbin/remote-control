// Ported from web/src/strings.ts and web/src/strings.zh-Hans.ts. Keep the two
// tables of this file in step with the web's: the English is the source, and
// the Chinese is the web's own translation, copied rather than re-translated.

import Foundation
import RCCore

/// Relative times and durations. Numbers stay; only the words move.
public struct FormatStrings: Sendable {
    /// Passed to `toLocaleDateString` for dates older than a week.
    public let dateLocale: String
    public let now: String
    public let minutes: @Sendable (Int) -> String
    public let hours: @Sendable (Int) -> String
    public let days: @Sendable (Int) -> String
    public let justNow: String
    public let minutesAgo: @Sendable (Int) -> String
    public let hoursAgo: @Sendable (Int) -> String
    public let yesterday: String
    public let daysAgo: @Sendable (Int) -> String
    public let millis: @Sendable (Int) -> String
    public let seconds: @Sendable (String) -> String
    public let minutesSeconds: @Sendable (Int, Int) -> String
    public let hoursMinutes: @Sendable (Int, Int) -> String
}

extension FormatStrings {
    static let en = FormatStrings(
        dateLocale: "en",
        now: "now",
        minutes: { n in "\(n)m" },
        hours: { n in "\(n)h" },
        days: { n in "\(n)d" },
        justNow: "just now",
        minutesAgo: { n in "\(n)m ago" },
        hoursAgo: { n in "\(n)h ago" },
        yesterday: "yesterday",
        daysAgo: { n in "\(n)d ago" },
        millis: { n in "\(n)ms" },
        seconds: { s in "\(s)s" },
        minutesSeconds: { m, s in "\(m)m \(s)s" },
        hoursMinutes: { h, m in "\(h)h \(m)m" }
    )

    static let zhHans = FormatStrings(
        dateLocale: "zh-Hans",
        now: "刚刚",
        minutes: { n in "\(n) 分钟" },
        hours: { n in "\(n) 小时" },
        days: { n in "\(n) 天" },
        justNow: "刚刚",
        minutesAgo: { n in "\(n) 分钟前" },
        hoursAgo: { n in "\(n) 小时前" },
        yesterday: "昨天",
        daysAgo: { n in "\(n) 天前" },
        millis: { n in "\(n) 毫秒" },
        seconds: { s in "\(s) 秒" },
        minutesSeconds: { m, s in "\(m) 分 \(s) 秒" },
        hoursMinutes: { h, m in "\(h) 小时 \(m) 分" }
    )

    /// The table an interface language reads.
    public static func of(_ language: InterfaceLanguage) -> FormatStrings {
        switch language {
        case .en: .en
        case .zhHans: .zhHans
        }
    }
}
