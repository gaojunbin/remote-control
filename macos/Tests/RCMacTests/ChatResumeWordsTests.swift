import Foundation
import RCCore
import Testing
@testable import RCMac

extension LanguageSensitive {
    /// `web/tests/resume.test.ts` — A35: the words a pending resume and the
    /// device's own steps are drawn with. The time is the viewer's, the date
    /// joins it when it is not today, "about" says the device estimated it, and
    /// the try is appended once a resume has run into the limit again.
    @Suite("Chat resume words") @MainActor
    struct ChatResumeWordsTests {
        init() { InterfaceLanguageSource.shared.current = .en }

        /// A local instant, so the assertions read in whatever zone the suite runs in.
        private func at(_ hour: Int, _ minute: Int, day: Int = 0) -> Int64 {
            let components = DateComponents(year: 2026, month: 9, day: 17 + day, hour: hour, minute: minute)
            let date = Calendar.current.date(from: components) ?? Date()
            return Int64(date.timeIntervalSince1970 * 1000)
        }

        private var now: Int64 { at(10, 0) }

        private func pending(estimated: Bool = false, attempts: Int = 0) -> SessionResume {
            SessionResume(at: at(15, 50), estimated: estimated, attempts: attempts, windowMinutes: 300)
        }

        @Test func aTimeTodayIsAClockAlone() {
            #expect(ResumeWords.timeText(at(15, 50), now: now) == "3:50 PM")
        }

        @Test func anotherDayCarriesTheDate() {
            #expect(ResumeWords.timeText(at(15, 50, day: 1), now: now) == "Sep 18, 3:50 PM")
        }

        @Test func theClockFollowsTheInterfaceLanguage() {
            InterfaceLanguageSource.shared.current = .zhHans
            #expect(ResumeWords.timeText(at(15, 50), now: now) == "15:50")
            #expect(ResumeWords.timeText(at(15, 50, day: 1), now: now) == "9月18日, 15:50")
            InterfaceLanguageSource.shared.current = .en
        }

        @Test func theNoticeSaysWhatHappenedAndWhenItResumes() {
            #expect(ResumeWords.noticeText(pending(), now: now) == "Paused by the usage limit · resumes 3:50 PM")
            #expect(ResumeWords.noticeText(pending(estimated: true), now: now)
                    == "Paused by the usage limit · resumes about 3:50 PM")
        }

        @Test func theTryIsAppendedOnceAResumeHitTheLimitAgain() {
            #expect(ResumeWords.noticeText(pending(attempts: 1), now: now).hasSuffix(" · second try"))
            #expect(ResumeWords.noticeText(pending(attempts: 2), now: now).hasSuffix(" · third try"))
            #expect(!ResumeWords.noticeText(pending(), now: now).contains("try"))
        }

        @Test func theNoticeIsSaidInTheInterfaceLanguage() {
            InterfaceLanguageSource.shared.current = .zhHans
            #expect(ResumeWords.noticeText(pending(), now: now) == "已因用量限额暂停 · 将于 15:50 继续")
            InterfaceLanguageSource.shared.current = .en
        }

        @Test func theDeviceStepsNameTheirTime() {
            #expect(ResumeWords.rowText(ResumePayload(status: .scheduled, at: at(15, 50)), now: now)
                    == "Resume scheduled for 3:50 PM")
            #expect(ResumeWords.rowText(ResumePayload(status: .rescheduled, at: at(16, 20)), now: now)
                    == "Resume moved to 4:20 PM")
        }

        @Test func theMomentOfResumingDrawsNothing() {
            #expect(ResumeWords.rowText(ResumePayload(status: .fired), now: now) == nil)
        }

        @Test func theDevicesOwnReasonFollowsWhatHappened() {
            #expect(ResumeWords.rowText(ResumePayload(status: .cancelled, reason: "you sent a message"), now: now)
                    == "Resume cancelled · you sent a message")
            #expect(ResumeWords.rowText(ResumePayload(status: .dropped, reason: "the terminal was closed"), now: now)
                    == "Not resumed · the terminal was closed")
            #expect(ResumeWords.rowText(ResumePayload(status: .cancelled), now: now) == "Resume cancelled")
            #expect(ResumeWords.rowText(ResumePayload(status: .scheduled), now: now) == "Resume scheduled")
        }

        @Test func theTurnTheLimitEndedSaysWhenItResets() {
            #expect(ResumeWords.limitEndText(LimitStop(windowMinutes: 300, resetsAt: at(15, 50)), now: now)
                    == "Ended at the usage limit · resets 3:50 PM")
            #expect(ResumeWords.limitEndText(LimitStop(resetsAt: nil), now: now) == "Ended at the usage limit")
        }

        @Test func thePickerTakesAMinuteToEightDaysAndNothingElse() {
            #expect(ResumeWords.boundError(now + ResumeWords.minAheadMS, now: now) == nil)
            #expect(ResumeWords.boundError(now + ResumeWords.maxAheadMS, now: now) == nil)
            #expect(ResumeWords.boundError(now + 30_000, now: now) == "Pick a time at least a minute from now.")
            #expect(ResumeWords.boundError(now + ResumeWords.maxAheadMS + 60_000, now: now)
                    == "Pick a time within the next eight days.")
        }
    }
}

/// The change form's field draws the time as Chrome's `datetime-local` does.
@Suite("Chat resume field")
struct ChatResumeFieldTests {
    @Test func everyNumberTakesTheFieldsWidth() {
        #expect(ResumeFieldFormat.fieldPattern("y/M/d HH:mm") == "yyyy/MM/dd HH:mm")
        #expect(ResumeFieldFormat.fieldPattern("M/d/yy, h:mm a") == "MM/dd/yyyy, hh:mm a")
        #expect(ResumeFieldFormat.fieldPattern("y/MM/dd H:mm") == "yyyy/MM/dd HH:mm")
    }

    @Test func quotedTextIsKept() {
        #expect(ResumeFieldFormat.fieldPattern("y'年'M'月'd'日' H:mm") == "yyyy'年'MM'月'dd'日' HH:mm")
        #expect(ResumeFieldFormat.fieldPattern("d 'dd' M") == "dd 'dd' MM")
    }

    @Test @MainActor func theFieldReadsBackWhatItShows() {
        let at = Date(timeIntervalSince1970: 1_790_000_000)
        let shown = ResumeFieldFormat.text(at)
        let minute = Date(timeIntervalSince1970: (at.timeIntervalSince1970 / 60).rounded(.down) * 60)
        #expect(ResumeFieldFormat.date(shown) == minute)
        #expect(ResumeFieldFormat.date("  ") == nil)
        #expect(ResumeFieldFormat.date("not a time") == nil)
    }
}
