import Testing
import Foundation
@testable import RCCore

/// Amendment A35 — a session the usage limit stopped resumes itself.
///
/// The fixtures the amendment added, decoded here as the app decodes them, and
/// the words the phone writes around them.
@Suite("Amendment A35, paused by the usage limit")
struct UsageLimitTests {
    // MARK: - The fixtures the amendment added

    @Test("Every fixture A35 added decodes", arguments: [
        "objects/session.resume-pending.json",
        "events/turn_completed.limit.json",
        "events/resume.json",
        "events/resume.dropped.json",
        "events/user_message.resume.json",
        "events/turn_started.resume.json",
        "app/preferences.updated.json",
        "app/session.resume_set.json",
        "app/session.resume_cancel.json",
        "app/reply.session.resume_set.json",
        "device/preferences.json",
        "device/forwarded/session.resume_set.json",
        "device/forwarded/session.resume_cancel.json",
        "http/preferences.response.json",
        "http/preferences.patch.request.json",
        "http/push.payload.limit.json"
    ])
    func fixtureParses(name: String) throws {
        #expect(try fixture(name) != .null)
    }

    @Test("A session carries the resume the device has pending")
    func sessionResume() throws {
        let session = try fixture("objects/session.resume-pending.json").decode(Session.self)
        #expect(session.resume?.at == 1_788_966_060_000)
        #expect(session.resume?.estimated == false)
        #expect(session.resume?.attempts == 0)
        #expect(session.resume?.windowMinutes == 300)
        // The pause is the notice's to carry: the session itself is idle.
        #expect(session.state == .idle)
        #expect(session.control == .shared)
    }

    @Test("A session with no resume field has no resume pending")
    func sessionWithoutResume() throws {
        let session = try fixture("objects/session.shared-idle.json").decode(Session.self)
        #expect(session.resume == nil)
    }

    @Test("A turn the limit ended is an error stop that says when it resets")
    func turnCompletedLimit() throws {
        let event = try fixture("events/turn_completed.limit.json").decode(SessionEvent.self)
        guard case .turnCompleted(let payload) = event.body else {
            Issue.record("expected a turn_completed")
            return
        }
        #expect(payload.stopReason == .error)
        #expect(payload.limit?.windowMinutes == 300)
        #expect(payload.limit?.resetsAt == 1_788_966_000_000)
        // The field survives a round trip, so a cached transcript keeps it.
        let again = try JSONValue.encode(event).decode(SessionEvent.self)
        #expect(again.body == event.body)
    }

    @Test("A limit the vendor named no time for decodes with a null reset")
    func limitWithoutResetTime() throws {
        let event: JSONValue = [
            "seq": 9, "ts": 1, "kind": "turn_completed", "turn_id": "t",
            "stop_reason": "error", "duration_ms": 900,
            "limit": ["window_minutes": 10_080, "resets_at": .null]
        ]
        guard case .turnCompleted(let payload) = try event.decode(SessionEvent.self).body else {
            Issue.record("expected a turn_completed")
            return
        }
        #expect(payload.limit != nil)
        #expect(payload.limit?.resetsAt == nil)
        #expect(payload.limit?.windowMinutes == 10_080)
    }

    @Test("A resume event says what the device did, and one status draws nothing")
    func resumeEvents() throws {
        let scheduled = try fixture("events/resume.json").decode(SessionEvent.self)
        #expect(scheduled.kind == SessionEvent.resumeKind)
        #expect(scheduled.resume?.status == .scheduled)
        #expect(scheduled.resume?.at == 1_788_966_060_000)
        #expect(scheduled.resume?.estimated == false)

        let dropped = try fixture("events/resume.dropped.json").decode(SessionEvent.self)
        #expect(dropped.resume?.status == .dropped)
        #expect(dropped.resume?.reason?.isEmpty == false)

        #expect(ResumeStatus.fired.isDrawn == false)
        for status in [ResumeStatus.scheduled, .rescheduled, .cancelled, .dropped] {
            #expect(status.isDrawn)
        }
    }

    @Test("A resume status this build has not heard of decodes rather than throwing")
    func unknownResumeStatus() throws {
        let event: JSONValue = ["seq": 1, "ts": 1, "kind": "resume", "status": "deferred"]
        let payload = try event.decode(SessionEvent.self).resume
        #expect(payload?.status.rawValue == "deferred")
        #expect(payload?.status.isDrawn == true)
    }

    @Test("The prompt is the person's own message and the turn it starts is theirs")
    func resumeSourceAndTrigger() throws {
        let message = try fixture("events/user_message.resume.json").decode(SessionEvent.self)
        #expect(message.userMessage?.source == .resume)
        // Amendment A30's "somebody else started this" rule must not claim it.
        #expect(message.userMessage?.source.isElsewhere == false)

        let started = try fixture("events/turn_started.resume.json").decode(SessionEvent.self)
        guard case .turnStarted(let payload) = started.body else {
            Issue.record("expected a turn_started")
            return
        }
        #expect(payload.trigger == .resume)
        #expect(payload.trigger.isElsewhere == false)
    }

    @Test("Preferences ride on hello, on their own frame and on the REST route")
    func preferences() throws {
        let hello = try fixture("app/hello.json")
        guard case .hello(let payload) = try AppFrame(json: hello) else {
            Issue.record("expected a hello")
            return
        }
        #expect(payload.preferences != nil)
        #expect(payload.preferences?.resumeAfterLimit == false)

        guard case .preferencesUpdated(let updated) =
                try AppFrame(json: fixture("app/preferences.updated.json")) else {
            Issue.record("expected a preferences.updated")
            return
        }
        #expect(updated.resumeAfterLimit)

        let response = try fixture("http/preferences.response.json").decode(PreferencesResponse.self)
        #expect(response.preferences.resumeAfterLimit)
    }

    @Test("A gateway older than the amendment offers no preferences at all")
    func preferencesAbsent() throws {
        var hello = try fixture("app/hello.json").objectValue ?? [:]
        hello.removeValue(forKey: "preferences")
        guard case .hello(let payload) = try AppFrame(json: .object(hello)) else {
            Issue.record("expected a hello")
            return
        }
        #expect(payload.preferences == nil)
    }

    @Test("The push kinds a resume produces decode")
    func pushKinds() throws {
        let route = try fixture("http/push.payload.limit.json")["rc"]?.decode(PushRoute.self)
        #expect(route?.kind == .limitReached)
        #expect(route?.title == "mac-studio-office: paused by the usage limit")
        #expect(TurnAlerts.kind(resume: .scheduled) == .limitReached)
        #expect(TurnAlerts.kind(resume: .fired) == .resumed)
        #expect(TurnAlerts.kind(resume: .dropped) == .resumeDropped)
        // Neither is news: one is a detail of a pause already told, the other
        // is usually the person's own doing.
        #expect(TurnAlerts.kind(resume: .rescheduled) == nil)
        #expect(TurnAlerts.kind(resume: .cancelled) == nil)
    }

    @Test("The two requests are built exactly as the fixtures are")
    func requests() throws {
        let set = try fixture("app/session.resume_set.json")
        let at = try #require(set["at"]?.intValue)
        let built = GatewayRequest.resumeSet(sessionID: set["session_id"]?.stringValue ?? "",
                                             at: Date(timeIntervalSince1970: Double(at) / 1000))
        #expect(built.type == "session.resume_set")
        #expect(built.body["at"] == .integer(Int64(at)))
        #expect(built.body["session_id"] == set["session_id"])

        let cancel = try fixture("app/session.resume_cancel.json")
        let cancelled = GatewayRequest.resumeCancel(sessionID: cancel["session_id"]?.stringValue ?? "")
        #expect(cancelled.type == "session.resume_cancel")
        #expect(cancelled.body["session_id"] == cancel["session_id"])

        let reply = try fixture("app/reply.session.resume_set.json")
        let session = try #require(reply["result"]).decode(SessionResult.self).session
        #expect(session.resume?.at == 1_788_967_860_000)
    }

    // MARK: - The bounds the picker is held to

    @Test("A resume is between a minute from now and eight days away", arguments: [
        (30.0, false), (59.0, false), (61.0, true), (3600.0, true),
        (8 * 86_400.0, true), (8 * 86_400.0 + 60, false)
    ])
    func bounds(offset: TimeInterval, allowed: Bool) {
        let now = Date(timeIntervalSince1970: 1_788_966_000)
        #expect(ResumeBounds.allows(now.addingTimeInterval(offset), now: now) == allowed)
    }

    // MARK: - The words the phone writes

    /// One fixed zone and one fixed language, so the words are the same
    /// wherever the check runs.
    private static let calendar: Calendar = {
        var calendar = Calendar(identifier: .gregorian)
        calendar.timeZone = TimeZone(identifier: "Asia/Singapore") ?? .gmt
        return calendar
    }()
    private static let locale = Locale(identifier: "en_US")
    /// 2026-09-09 20:00 in Singapore, which is the day the fixtures are on.
    private static let now = Date(timeIntervalSince1970: 1_788_955_200)
    /// 22:20 the same evening, and 09:00 the next morning.
    private static let tonight: Int64 = 1_788_963_600_000
    private static let tomorrow: Int64 = 1_789_002_000_000

    private func clock(_ milliseconds: Int64) -> String {
        ResumeText.clock(milliseconds, now: Self.now, calendar: Self.calendar, locale: Self.locale)
    }

    /// The clock itself is the system's, in the viewer's zone and language, so
    /// what is pinned here is the rule the app owns: a time today is a time,
    /// and a time on another day says which day. Pinning the pattern would pin
    /// this suite to one release's locale data and nothing else.
    @Test("A time today is a clock time, and one on another day names the day")
    func clockWording() {
        let tonight = clock(Self.tonight)
        #expect(tonight.contains("10:20"))
        #expect(tonight.contains("PM"))
        #expect(!tonight.contains("Sep"))

        let tomorrow = clock(Self.tomorrow)
        #expect(tomorrow.contains("9:00"))
        #expect(tomorrow.contains("Sep"))
        #expect(tomorrow.contains("10"))
    }

    @Test("The clock is read in the viewer's own zone, not the device's")
    func clockZone() {
        var london = Calendar(identifier: .gregorian)
        london.timeZone = TimeZone(identifier: "Europe/London") ?? .gmt
        let text = ResumeText.clock(Self.tonight, now: Self.now, calendar: london,
                                    locale: Self.locale)
        // 22:20 in Singapore is 15:20 in London, on the same day either way.
        #expect(text.contains("3:20"))
        #expect(text.contains("PM"))
    }

    @Test("The notice names the time, the guess and the attempt")
    func noticeWording() {
        func notice(_ resume: SessionResume) -> String {
            ResumeText.notice(resume, now: Self.now, calendar: Self.calendar, locale: Self.locale)
        }
        let at = Self.tonight
        let time = clock(at)
        #expect(notice(SessionResume(at: at)) == "Paused by the usage limit · resumes \(time)")
        #expect(notice(SessionResume(at: at, estimated: true))
                == "Paused by the usage limit · resumes about \(time)")
        #expect(notice(SessionResume(at: at, attempts: 1))
                == "Paused by the usage limit · resumes \(time) · second try")
        #expect(notice(SessionResume(at: at, attempts: 2))
                == "Paused by the usage limit · resumes \(time) · third try")
        // The device drops the resume after the third try, so there is no
        // fourth to name.
        #expect(notice(SessionResume(at: at, attempts: 3))
                == "Paused by the usage limit · resumes \(time)")
    }

    @Test("A turn the limit ended says so, with the reset time when there is one")
    func turnEndWording() {
        func end(_ limit: LimitStop) -> String {
            ResumeText.turnEnd(limit, now: Self.now, calendar: Self.calendar, locale: Self.locale)
        }
        #expect(end(LimitStop(windowMinutes: 300, resetsAt: Self.tonight))
                == "Ended at the usage limit · resets \(clock(Self.tonight))")
        #expect(end(LimitStop(windowMinutes: 10_080)) == "Ended at the usage limit")
    }

    @Test("Each resume row is in the notice voice, and the fired one is no row")
    func rowWording() {
        func row(_ payload: ResumePayload) -> String? {
            ResumeText.row(payload, now: Self.now, calendar: Self.calendar, locale: Self.locale)
        }
        let at = Self.tonight
        let time = clock(at)
        #expect(row(ResumePayload(status: .scheduled, at: at)) == "Resume scheduled for \(time)")
        #expect(row(ResumePayload(status: .scheduled, at: at, estimated: true))
                == "Resume scheduled for about \(time)")
        #expect(row(ResumePayload(status: .rescheduled, at: at)) == "Resume moved to \(time)")
        // A device that names no time still gets a row rather than a sentence
        // that stops halfway.
        #expect(row(ResumePayload(status: .scheduled)) == "Resume scheduled")
        #expect(row(ResumePayload(status: .rescheduled)) == "Resume moved")
        #expect(row(ResumePayload(status: .fired)) == nil)
        #expect(row(ResumePayload(status: .cancelled, reason: "you sent a message"))
                == "Resume cancelled · you sent a message")
        #expect(row(ResumePayload(status: .dropped,
                                  reason: "The terminal that owned this session was closed."))
                == "Not resumed · The terminal that owned this session was closed.")
        // A device that says nothing about why still gets a row.
        #expect(row(ResumePayload(status: .cancelled)) == "Resume cancelled")
    }

    @Test("Simple keeps the limit end, the device's rows and the captioned prompt")
    func simpleKeepsThem() {
        var timeline = Timeline()
        timeline.apply(SessionEvent(
            seq: 1, ts: 1, kind: SessionEvent.turnCompletedKind,
            body: .turnCompleted(TurnCompletedPayload(
                turnID: "t", stopReason: .error, durationMS: 1_200,
                limit: LimitStop(windowMinutes: 300, resetsAt: Self.tonight)))))
        timeline.apply(SessionEvent(seq: 2, ts: 2, kind: SessionEvent.resumeKind,
                                    body: .resume(ResumePayload(status: .scheduled, at: 3))))
        timeline.apply(SessionEvent(seq: 3, ts: 3, kind: SessionEvent.resumeKind,
                                    body: .resume(ResumePayload(status: .fired))))
        timeline.apply(SessionEvent(seq: 4, ts: 4, kind: SessionEvent.userMessageKind, blockID: "u",
                                    body: .userMessage(UserMessagePayload(text: "go on",
                                                                          source: .resume))))
        timeline.apply(SessionEvent(seq: 5, ts: 5, kind: SessionEvent.turnStartedKind,
                                    body: .turnStarted(TurnStartedPayload(turnID: "t2",
                                                                          trigger: .resume))))
        let simple = timeline.roots(at: .simple)
        #expect(simple.contains { $0.turnCompleted?.limit != nil })
        #expect(simple.contains { $0.resume?.status == .scheduled })
        #expect(simple.contains { $0.userMessage?.source == .resume })
        // The moment of resuming is not a row at any level.
        #expect(!simple.contains { $0.resume?.status == .fired })
        #expect(!timeline.roots(at: .detailed).contains { $0.resume?.status == .fired })
    }

    // MARK: - Fixtures

    private static let fixtures = URL(filePath: #filePath)
        .deletingLastPathComponent()   // RCCoreTests
        .deletingLastPathComponent()   // Tests
        .deletingLastPathComponent()   // ios
        .deletingLastPathComponent()   // repository root
        .appending(path: "protocol/fixtures")

    private func fixture(_ relativePath: String) throws -> JSONValue {
        let data = try Data(contentsOf: Self.fixtures.appending(path: relativePath))
        return try JSONDecoder().decode(JSONValue.self, from: data)
    }
}
