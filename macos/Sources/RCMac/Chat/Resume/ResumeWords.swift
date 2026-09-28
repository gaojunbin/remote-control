import Foundation
import RCCore

/// `web/src/features/chat/resume.ts` — A35: the words a pending resume is drawn
/// with, the words the device's own steps read as, and the bounds the time
/// picker refuses outside of (`docs/DESIGN.md` § "Paused by the usage limit").
///
/// Everything here is pure: a timestamp and the clock go in, the reader's own
/// words come out. The time is always the viewer's, because the gateway has no
/// idea what zone the app is in. Times are epoch milliseconds, as on the wire.
public enum ResumeWords {
    /// §6.3: the soonest and the latest a resume may be set for.
    public static let minAheadMS: Int64 = 60_000
    public static let maxAheadMS: Int64 = 8 * 24 * 60 * 60_000

    /// A time in the viewer's own zone: "3:50 PM" when it falls today, "Sep 18,
    /// 3:50 PM" on any other day. The clock face is the reader's language's, so
    /// 中文 reads 15:50 where English reads 3:50 PM.
    public static func timeText(_ at: Int64, now: Int64 = Format.nowMillis,
                                calendar: Calendar = .current) -> String {
        let when = Date(timeIntervalSince1970: Double(at) / 1000)
        let today = Date(timeIntervalSince1970: Double(now) / 1000)
        let time = format(when, template: "jmm", calendar: calendar)
        if calendar.isDate(when, inSameDayAs: today) { return time }
        return "\(format(when, template: "MMMd", calendar: calendar)), \(time)"
    }

    /// `toLocaleTimeString` and `toLocaleDateString` in the interface
    /// language. The browser writes a plain space before "PM" where Apple's
    /// formatter writes a narrow one, and the web's words are the reference.
    private static func format(_ date: Date, template: String, calendar: Calendar) -> String {
        let formatter = DateFormatter()
        formatter.locale = Locale(identifier: S.format.dateLocale)
        formatter.calendar = calendar
        formatter.timeZone = calendar.timeZone
        formatter.setLocalizedDateFormatFromTemplate(template)
        return formatter.string(from: date).replacingOccurrences(of: "\u{202F}", with: " ")
    }

    /// The try a resume is on, once one has already run into the limit again.
    static func attemptWord(_ attempts: Int) -> String? {
        guard attempts > 0 else { return nil }
        return attempts == 1 ? S.chat.resumeSecondTry : S.chat.resumeThirdTry
    }

    /// "Paused by the usage limit · resumes 3:50 PM · second try".
    public static func noticeText(_ resume: SessionResume, now: Int64 = Format.nowMillis,
                                  calendar: Calendar = .current) -> String {
        let when = timeText(resume.at, now: now, calendar: calendar)
        var parts = [S.chat.pausedByLimit,
                     resume.estimated ? S.chat.resumesAbout(when) : S.chat.resumesAt(when)]
        if let attempt = attemptWord(resume.attempts) { parts.append(attempt) }
        return parts.joined(separator: " · ")
    }

    /// How a turn the usage limit ended closes, with the reset time when the
    /// vendor named one and without it when `resets_at` is null.
    public static func limitEndText(_ limit: LimitStop, now: Int64 = Format.nowMillis,
                                    calendar: Calendar = .current) -> String {
        guard let resetsAt = limit.resetsAt else { return S.chat.turnLimit }
        return S.chat.turnLimitResets(timeText(resetsAt, now: now, calendar: calendar))
    }

    /// One of the device's steps as a timeline row, or nil for `fired`: the
    /// moment of resuming is the prompt in the person's bubble, not a row of
    /// its own.
    public static func rowText(_ event: ResumePayload, now: Int64 = Format.nowMillis,
                               calendar: Calendar = .current) -> String? {
        switch event.status {
        case .scheduled:
            return event.at.map { S.chat.resumeScheduledAt(timeText($0, now: now, calendar: calendar)) }
                ?? S.chat.resumeScheduled
        case .rescheduled:
            return event.at.map { S.chat.resumeMovedTo(timeText($0, now: now, calendar: calendar)) }
                ?? S.chat.resumeMoved
        case .cancelled:
            return withReason(S.chat.resumeCancelled, event.reason)
        case .dropped:
            return withReason(S.chat.resumeDropped, event.reason)
        default:
            return nil
        }
    }

    /// The device's one line of why, after the app's own word for what happened.
    private static func withReason(_ label: String, _ reason: String?) -> String {
        guard let reason, !reason.isEmpty else { return label }
        return "\(label) · \(reason)"
    }

    /// Why a time the reader picked cannot be sent, or nil when it can.
    public static func boundError(_ at: Int64, now: Int64 = Format.nowMillis) -> String? {
        if at < now + minAheadMS { return S.chat.resumeTooSoon }
        if at > now + maxAheadMS { return S.chat.resumeTooFar }
        return nil
    }
}
