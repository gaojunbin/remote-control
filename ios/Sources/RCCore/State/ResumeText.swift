import Foundation

/// Amendment A35 — the words a paused session is described with, in one place
/// so the notice above the transcript, the timeline rows and the checks all
/// read the same sentence (`docs/DESIGN.md` § "Paused by the usage limit").
///
/// Every time is the viewer's own: the device sends a timestamp and nothing
/// else, so the zone and the clock format come from here.
public enum ResumeText {
    /// "3:50 PM", with the date in front of it when the time is not today.
    public static func clock(_ milliseconds: Int64, now: Date = Date(),
                             calendar: Calendar = .current, locale: Locale = .current) -> String {
        let date = Date(timeIntervalSince1970: Double(milliseconds) / 1000)
        var style = Date.FormatStyle(locale: locale, calendar: calendar,
                                     timeZone: calendar.timeZone).hour().minute()
        if !calendar.isDate(date, inSameDayAs: now) {
            style = style.month(.abbreviated).day()
        }
        return date.formatted(style)
    }

    /// The notice above the transcript: "Paused by the usage limit · resumes
    /// 3:50 PM", "about" where the device estimated the time, and the attempt
    /// where a resumed turn has already run into the limit again.
    public static func notice(_ resume: SessionResume, now: Date = Date(),
                              calendar: Calendar = .current, locale: Locale = .current) -> String {
        let time = clock(resume.at, now: now, calendar: calendar, locale: locale)
        let head = resume.estimated
            ? L10n.string("Paused by the usage limit · resumes about %@", time)
            : L10n.string("Paused by the usage limit · resumes %@", time)
        guard let attempt = attempt(resume.attempts) else { return head }
        return "\(head) · \(attempt)"
    }

    /// The end of a turn the vendor's window stopped. Without a time when the
    /// vendor named none, because "resets" with nothing after it says less than
    /// the first half alone.
    public static func turnEnd(_ limit: LimitStop, now: Date = Date(),
                               calendar: Calendar = .current, locale: Locale = .current) -> String {
        guard let resetsAt = limit.resetsAt else {
            return L10n.string("Ended at the usage limit")
        }
        return L10n.string("Ended at the usage limit · resets %@",
                           clock(resetsAt, now: now, calendar: calendar, locale: locale))
    }

    /// One `resume` event as a timeline row, or nil for the one status that
    /// draws nothing: the moment of resuming is the prompt in the person's
    /// bubble and the turn it starts, not a row of its own.
    public static func row(_ payload: ResumePayload, now: Date = Date(),
                           calendar: Calendar = .current, locale: Locale = .current) -> String? {
        guard payload.status.isDrawn else { return nil }
        switch payload.status {
        case .scheduled, .rescheduled:
            let time = payload.at.map { clock($0, now: now, calendar: calendar, locale: locale) }
            return scheduleRow(payload.status, time: time, estimated: payload.estimated)
        case .cancelled:
            return joined(L10n.string("Resume cancelled"), payload.reason)
        case .dropped:
            return joined(L10n.string("Not resumed"), payload.reason)
        default:
            return nil
        }
    }

    /// The caption under the one message the device writes for the person.
    public static var sentForYou: String {
        L10n.string("Sent for you after the limit reset")
    }

    /// The sentence under the Sessions group in Settings: what the switch does,
    /// or why it cannot be turned on.
    public static func settingsFooter(offered: Bool) -> String {
        guard offered else { return L10n.string("Your gateway does not offer this yet.") }
        return L10n.string(
            "When Claude Code or Codex stops at a usage limit, the device continues the session a minute after the limit resets.")
    }

    /// "second try", "third try", or nothing at all for the first one.
    private static func attempt(_ attempts: Int) -> String? {
        switch attempts {
        case 1: L10n.string("second try")
        case 2: L10n.string("third try")
        // The device drops the resume after the third try, so there is no
        // fourth to name and a number nobody expects says nothing.
        default: nil
        }
    }

    private static func scheduleRow(_ status: ResumeStatus, time: String?,
                                    estimated: Bool) -> String {
        guard let time else {
            return status == .rescheduled ? L10n.string("Resume moved") : L10n.string("Resume scheduled")
        }
        if status == .rescheduled {
            return estimated ? L10n.string("Resume moved to about %@", time)
                             : L10n.string("Resume moved to %@", time)
        }
        return estimated ? L10n.string("Resume scheduled for about %@", time)
                         : L10n.string("Resume scheduled for %@", time)
    }

    /// The device's own one-line reason after the app's word for what happened.
    private static func joined(_ head: String, _ reason: String?) -> String {
        guard let reason, !reason.isEmpty else { return head }
        return "\(head) · \(reason)"
    }
}
