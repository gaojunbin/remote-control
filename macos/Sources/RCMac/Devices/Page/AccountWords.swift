import Foundation
import RCCore

/// `web/src/features/devices/accounts.ts`: the words a device page puts on an
/// account and its quota windows (A33; `docs/DESIGN.md` § "A device has a page"
/// and § "Quota is a meter").
///
/// Pure functions of what the device reported. The vendor's name comes from
/// `S.vendorLabels`; the plan word, the tier, the email and the third-party host
/// are printed exactly as they arrived, in both interface languages, because
/// they are data rather than the app's own words.
enum AccountWords {
    /// The band a meter's fill is drawn in: the ink colour up to 80 % of the
    /// window, the warning colour past it, the danger colour once it is spent.
    enum MeterTone: Sendable, Equatable {
        case ink
        case warn
        case danger
    }

    /// The one line under an agent's name. Both methods lead with the vendor:
    /// "Anthropic account · Max · Max 5x · me@example.com", "Anthropic API key",
    /// or "OpenAI API key · api.relay.example". Nothing is drawn for what the
    /// device did not report.
    static func signInLine(_ account: AgentAccount) -> String {
        let vendor = S.vendorLabel(account.provider)
        if account.method == .apiKey {
            let key = S.devicePage.apiKeyOf(vendor)
            guard let endpoint = account.endpoint, !endpoint.isEmpty else { return key }
            return "\(key) · \(endpoint)"
        }
        var parts = [S.devicePage.accountOf(vendor)]
        if let plan = account.plan, !plan.isEmpty { parts.append(planWord(plan)) }
        if let tier = account.tier, !tier.isEmpty { parts.append(tier) }
        if let email = account.email, !email.isEmpty { parts.append(email) }
        return parts.joined(separator: " · ")
    }

    /// The window's name, with what it is confined to after it: "7-day · Fable".
    static func windowName(_ limit: AgentLimit) -> String {
        let length = windowLength(limit.windowMinutes)
        guard let scope = limit.scope, !scope.isEmpty else { return length }
        return "\(length) · \(scope)"
    }

    static func meterTone(_ usedPercent: Double) -> MeterTone {
        if usedPercent >= 100 { return .danger }
        if usedPercent > 80 { return .warn }
        return .ink
    }

    /// The share of the window that is drawn and printed, clamped to the meter.
    static func usedPercent(_ limit: AgentLimit) -> Int {
        Int(min(100, max(0, Format.jsRound(limit.usedPercent))))
    }

    /// When the window resets, in the reader's own words: "resets 15:40" for a
    /// reset later today, "resets Tue 22:00" for one on another day.
    static func resetsText(_ resetsAt: Int64, now: Int64 = Format.nowMillis) -> String {
        let when = Date(timeIntervalSince1970: Double(resetsAt) / 1000)
        let locale = Locale(identifier: S.format.dateLocale)
        let clock = DateFormatter()
        clock.locale = locale
        clock.dateFormat = "HH:mm"
        let time = clock.string(from: when)
        if Calendar.current.isDate(when, inSameDayAs: Date(timeIntervalSince1970: Double(now) / 1000)) {
            return S.devicePage.resets(time)
        }
        let weekday = DateFormatter()
        weekday.locale = locale
        weekday.setLocalizedDateFormatFromTemplate("EEE")
        return S.devicePage.resets("\(weekday.string(from: when)) \(time)")
    }

    /// The plan reads as a word rather than as an id: `max` is drawn "Max".
    private static func planWord(_ plan: String) -> String {
        plan.prefix(1).uppercased() + plan.dropFirst()
    }

    /// A window's length in the reader's units: 300 minutes is "5-hour", 1440
    /// is "24-hour", 10080 is "7-day". A day is only reached above a day's worth
    /// of hours, so a window of exactly 24 hours stays an hour window.
    private static func windowLength(_ minutes: Int) -> String {
        guard minutes % 60 == 0 else { return S.devicePage.windowMinutes(minutes) }
        let hours = minutes / 60
        if hours > 24 && minutes % 1440 == 0 { return S.devicePage.windowDays(minutes / 1440) }
        return S.devicePage.windowHours(hours)
    }
}
