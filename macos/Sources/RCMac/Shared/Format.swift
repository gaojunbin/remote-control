import Foundation

/// `web/src/lib/format.ts`: display formatters. The ones that carry words read
/// them from the interface language's table, so "3m ago" becomes "3 分钟前";
/// every number, clock and unit of storage is written the same way in both
/// languages. Times are epoch milliseconds, as the wire carries them.
public enum Format {
    private static let minute: Int64 = 60_000
    private static let hour: Int64 = 60 * minute
    private static let day: Int64 = 24 * hour

    /// Now, in the milliseconds every timestamp on the wire is written in.
    public static var nowMillis: Int64 { Int64(Date().timeIntervalSince1970 * 1000) }

    /// "Sep 28" / "9月28日": what a date older than a week reads.
    static func shortDate(_ ts: Int64) -> String {
        let formatter = DateFormatter()
        formatter.locale = Locale(identifier: S.format.dateLocale)
        formatter.setLocalizedDateFormatFromTemplate("MMMd")
        return formatter.string(from: Date(timeIntervalSince1970: Double(ts) / 1000))
    }

    /// Compact relative time as used in session rows: "4m", "3h", "2d", "now".
    public static func relativeTime(_ ts: Int64, now: Int64 = nowMillis) -> String {
        let delta = max(0, now - ts)
        if delta < 45_000 { return S.format.now }
        if delta < hour { return S.format.minutes(rounded(delta, minute)) }
        if delta < day { return S.format.hours(rounded(delta, hour)) }
        if delta < 7 * day { return S.format.days(rounded(delta, day)) }
        return shortDate(ts)
    }

    /// Longer relative form used for "recent directories": "2h ago", "yesterday".
    public static func relativeAgo(_ ts: Int64, now: Int64 = nowMillis) -> String {
        let delta = max(0, now - ts)
        if delta < minute { return S.format.justNow }
        if delta < hour { return S.format.minutesAgo(rounded(delta, minute)) }
        if delta < day { return S.format.hoursAgo(rounded(delta, hour)) }
        if delta < 2 * day { return S.format.yesterday }
        if delta < 7 * day { return S.format.daysAgo(rounded(delta, day)) }
        return shortDate(ts)
    }

    /// "6.4s", "1m 12s", "820ms" — durations inside tool rows and turn timers.
    public static func duration(_ ms: Double) -> String {
        guard ms.isFinite, ms >= 0 else { return "" }
        if ms < 1000 { return S.format.millis(Int(jsRound(ms))) }
        let seconds = ms / 1000
        if seconds < 60 {
            return S.format.seconds(seconds < 10 ? toFixed(seconds, 1) : String(Int(jsRound(seconds))))
        }
        let m = Int((seconds / 60).rounded(.down))
        let s = Int(jsRound(seconds.truncatingRemainder(dividingBy: 60)))
        if m < 60 { return S.format.minutesSeconds(m, s) }
        return S.format.hoursMinutes(m / 60, m % 60)
    }

    /// "0:12" / "9:47" clock used by pairing countdowns and the voice timer.
    public static func clock(_ ms: Double) -> String {
        let total = max(0, Int(jsRound(ms / 1000)))
        return "\(total / 60):\(String(format: "%02d", total % 60))"
    }

    /// "48.2k" token counts.
    public static func compactNumber(_ n: Double) -> String {
        guard n.isFinite else { return "0" }
        if n < 1000 { return String(Int(jsRound(n))) }
        if n < 1_000_000 {
            let k = n / 1000
            return "\(k < 100 ? toFixed(k, 1) : String(Int(jsRound(k))))k"
        }
        return "\(toFixed(n / 1_000_000, 1))M"
    }

    public static func bytes(_ n: Int) -> String {
        if n < 1024 { return "\(n) B" }
        if n < 1024 * 1024 { return "\(toFixed(Double(n) / 1024, 0)) KiB" }
        return "\(toFixed(Double(n) / (1024 * 1024), 1)) MiB"
    }

    /// Collapse the home directory to `~` the way the prototype shows paths.
    public static func tildePath(_ path: String, home: String? = nil) -> String {
        if let home, !home.isEmpty, path.hasPrefix(home) { return "~" + path.dropFirst(home.count) }
        if let match = path.range(of: "^/(?:home|Users)/[^/]+", options: .regularExpression) {
            return "~" + path[match.upperBound...]
        }
        return path
    }

    /// Last path segment, used as the short cwd label in the sidebar.
    public static func baseName(_ path: String) -> String {
        var trimmed = path
        // `(.)\/+$` → `$1`: trailing slashes go, but a lone "/" stays.
        while trimmed.count > 1, trimmed.hasSuffix("/") { trimmed.removeLast() }
        guard let slash = trimmed.lastIndex(of: "/") else { return trimmed }
        let tail = trimmed[trimmed.index(after: slash)...]
        return tail.isEmpty ? "/" : String(tail)
    }

    public static func latency(_ ms: Double?) -> String {
        guard let ms, ms.isFinite else { return "—" }
        return "\(Int(jsRound(ms))) ms"
    }

    /// Truncate to a line budget, returning the head and whether it was folded.
    public static func foldLines(_ text: String, maxLines: Int) -> (head: String, folded: Bool, total: Int) {
        let lines = text.components(separatedBy: "\n")
        guard lines.count > maxLines else { return (text, false, lines.count) }
        return (lines.prefix(maxLines).joined(separator: "\n"), true, lines.count)
    }

    // MARK: - JavaScript's rounding

    /// `Math.round`: halves go up, towards positive infinity.
    static func jsRound(_ value: Double) -> Double { (value + 0.5).rounded(.down) }

    private static func rounded(_ delta: Int64, _ unit: Int64) -> Int {
        Int(jsRound(Double(delta) / Double(unit)))
    }

    /// `Number.prototype.toFixed`: a tie goes to the larger of the two, where
    /// `%.1f` would round it to even.
    static func toFixed(_ value: Double, _ digits: Int) -> String {
        let scale = pow(10, Double(digits))
        let scaled = (value * scale).rounded(.toNearestOrAwayFromZero) / scale
        return String(format: "%.\(digits)f", scaled)
    }
}
