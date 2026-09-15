import Foundation

/// Amendment A33: one rate-limit window, worded for a meter row.
///
/// `docs/DESIGN.md` § "Quota is a meter, drawn for accounts only": the window's
/// name with its scope after it, the share used, and when it resets in the
/// reader's own words.
public enum QuotaWindow {
    /// How full a meter is allowed to look before it stops being the ink
    /// colour. The bands are the page's only colour rule.
    public enum Band: Sendable, Hashable {
        case normal
        case warning
        case danger
    }

    public static func band(usedPercent: Double) -> Band {
        if usedPercent >= 100 { return .danger }
        if usedPercent > 80 { return .warning }
        return .normal
    }

    /// The share of the bar that is filled, clamped to what a bar can draw.
    public static func fill(usedPercent: Double) -> Double {
        guard usedPercent.isFinite else { return 0 }
        return min(1, max(0, usedPercent / 100))
    }

    public static func percentage(_ usedPercent: Double) -> String {
        guard usedPercent.isFinite else { return L10n.string("%lld%%", 0) }
        return L10n.string("%lld%%", Int(min(100, max(0, usedPercent)).rounded()))
    }

    /// The window's name: a length in the unit it divides into, with the scope
    /// after it where the vendor confined it to one.
    public static func name(_ limit: AgentLimit) -> String {
        let length = length(minutes: limit.windowMinutes)
        guard let scope = limit.scope, !scope.isEmpty else { return length }
        return length + AccountLine.separator + scope
    }

    /// 300 → "5-hour", 1440 → "24-hour", 10080 → "7-day". A window that is not
    /// whole hours is named in minutes rather than rounded into a lie.
    public static func length(minutes: Int) -> String {
        guard minutes > 0 else { return L10n.string("%lld-minute", 0) }
        // A day is the longest unit an hour still reads well in: a weekly
        // window is seven days, and the daily one is the vendor's own 24 hours.
        if minutes % 1440 == 0, minutes > 1440 { return L10n.string("%lld-day", minutes / 1440) }
        if minutes % 60 == 0 { return L10n.string("%lld-hour", minutes / 60) }
        return L10n.string("%lld-minute", minutes)
    }

    /// "resets 15:40" for a window that resets today, "resets Tue 22:00" for
    /// one that does not. The clock is the reader's, not the device's.
    public static func resets(at milliseconds: Int64, now: Date = Date(),
                              calendar: Calendar = .current, locale: Locale = .current) -> String {
        L10n.string("resets %@", clock(at: milliseconds, now: now, calendar: calendar, locale: locale))
    }

    static func clock(at milliseconds: Int64, now: Date,
                      calendar: Calendar, locale: Locale) -> String {
        let date = Date(timeIntervalSince1970: Double(milliseconds) / 1000)
        let formatter = DateFormatter()
        formatter.locale = locale
        formatter.calendar = calendar
        formatter.timeZone = calendar.timeZone
        formatter.dateFormat = calendar.isDate(date, inSameDayAs: now) ? "HH:mm" : "EEE HH:mm"
        return formatter.string(from: date)
    }
}
