import Foundation
import RCCore
import Testing
@testable import RCMac

/// `gateway/rc_gateway/push.py`'s moments and words, as this Mac posts them, and
/// `web/tests/service-worker.test.ts`'s line, payload and click.
@Suite("Notification rule")
struct NotificationRuleTests {
    private func session(_ state: SessionState, resume: SessionResume? = nil, id: String = "s1") -> Session {
        Session(sessionID: id, deviceID: "d1", agent: "claude", title: "t", cwd: "/", state: state, resume: resume)
    }

    @Test func aStateChangeIsTheGatewaysTransitionTable() {
        #expect(TurnNoticeRule.kinds(previous: session(.running), current: session(.idle)) == [.turnCompleted])
        #expect(TurnNoticeRule.kinds(previous: session(.needsInput), current: session(.idle)) == [.turnCompleted])
        #expect(TurnNoticeRule.kinds(previous: session(.running), current: session(.needsApproval))
                == [.needsApproval])
        #expect(TurnNoticeRule.kinds(previous: session(.running), current: session(.needsInput)) == [.needsInput])
        #expect(TurnNoticeRule.kinds(previous: session(.running), current: session(.error)) == [.error])
    }

    @Test func aSessionThatWasAlreadyQuietIsNoNews() {
        #expect(TurnNoticeRule.kinds(previous: session(.stopped), current: session(.idle)).isEmpty)
        #expect(TurnNoticeRule.kinds(previous: session(.idle), current: session(.idle)).isEmpty)
        #expect(TurnNoticeRule.kinds(previous: session(.idle), current: session(.running)).isEmpty)
        // Two different sessions are no transition at all.
        #expect(TurnNoticeRule.kinds(previous: session(.running, id: "a"), current: session(.idle, id: "b")).isEmpty)
    }

    @Test func aResumeTheDeviceJustScheduledIsThePause() {
        let pending = SessionResume(at: 1_000)
        #expect(TurnNoticeRule.kinds(previous: session(.idle), current: session(.idle, resume: pending))
                == [.limitReached])
        // A turn the limit ended and the pause it brings, in the order they happened.
        #expect(TurnNoticeRule.kinds(previous: session(.running), current: session(.idle, resume: pending))
                == [.turnCompleted, .limitReached])
        // A time moved is not news, and neither is the resume going.
        let moved = SessionResume(at: 2_000)
        #expect(TurnNoticeRule.kinds(previous: session(.idle, resume: pending), current: session(.idle, resume: moved))
                .isEmpty)
        #expect(TurnNoticeRule.kinds(previous: session(.idle, resume: pending), current: session(.idle)).isEmpty)
    }

    @Test func onlyTheResumeThatRanOrWasGivenUpIsReadFromTheEvent() {
        #expect(TurnNoticeRule.kind(resume: .fired) == .resumed)
        #expect(TurnNoticeRule.kind(resume: .dropped) == .resumeDropped)
        #expect(TurnNoticeRule.kind(resume: .scheduled) == nil)
        #expect(TurnNoticeRule.kind(resume: .rescheduled) == nil)
        #expect(TurnNoticeRule.kind(resume: .cancelled) == nil)
    }

    @Test func itPostsOnlyWithTheSwitchOnAndThePermissionGiven() {
        let target = NoticeTarget(deviceID: "d1", sessionID: "s1")
        #expect(TurnNoticeRule.posts(target, enabled: true, permission: .authorized, route: .sessions,
                                     windowActive: true))
        #expect(!TurnNoticeRule.posts(target, enabled: false, permission: .authorized, route: .sessions,
                                      windowActive: false))
        #expect(!TurnNoticeRule.posts(target, enabled: true, permission: .denied, route: .sessions,
                                      windowActive: false))
        #expect(!TurnNoticeRule.posts(target, enabled: true, permission: .notDetermined, route: .sessions,
                                      windowActive: false))
    }

    @Test func theConversationOpenInTheFrontmostWindowPostsNothing() {
        let target = NoticeTarget(deviceID: "d1", sessionID: "s1")
        let open = Route.chat(deviceId: "d1", sessionId: "s1")
        #expect(!TurnNoticeRule.posts(target, enabled: true, permission: .authorized, route: open, windowActive: true))
        // Behind another window, or with the window closed, it is news again.
        #expect(TurnNoticeRule.posts(target, enabled: true, permission: .authorized, route: open, windowActive: false))
        // Another conversation open is not this one.
        #expect(TurnNoticeRule.posts(target, enabled: true, permission: .authorized,
                                     route: .chat(deviceId: "d1", sessionId: "s2"), windowActive: true))
    }

    @Test func aClickWaitsForAnAccountItsListAndTheLandingRule() {
        #expect(TurnNoticeRule.opensNow(signedIn: true, hasSnapshot: true, route: .sessions))
        #expect(!TurnNoticeRule.opensNow(signedIn: false, hasSnapshot: true, route: .login))
        #expect(!TurnNoticeRule.opensNow(signedIn: true, hasSnapshot: false, route: .sessions))
        #expect(!TurnNoticeRule.opensNow(signedIn: true, hasSnapshot: true, route: .landing))
    }
}

@Suite("Notification words")
struct NotificationWordsTests {
    private let target = NoticeTarget(deviceID: "dev-mac", sessionID: "ses-1")

    @Test func theLineIsTheOneTheGatewayWrites() {
        let words: [(PushKind, String)] = [
            (.needsApproval, "approval needed"), (.needsInput, "waiting for your answer"),
            (.turnCompleted, "turn finished"), (.error, "session error"),
            (.limitReached, "paused by the usage limit"), (.resumed, "resumed after the limit reset"),
            (.resumeDropped, "not resumed")
        ]
        for (kind, phrase) in words {
            let notice = TurnNotice(kind: kind, target: target, deviceName: "mac-studio-office")
            #expect(notice.body == "mac-studio-office: \(phrase)")
            #expect(notice.title == "Remote Control")
        }
    }

    @Test func aKindThisBuildHasNeverSeenStillSaysSomething() {
        let notice = TurnNotice(kind: PushKind(rawValue: "surprise"), target: target, deviceName: "box")
        #expect(notice.body == "box: update")
    }

    @Test func oneSessionHoldsOneNotificationAndItsPayloadIsThePushs() throws {
        let notice = TurnNotice(kind: .turnCompleted, target: target, deviceName: "mac-studio-office")
        #expect(notice.identifier == "rc-ses-1")
        let rc = try #require(notice.payload["rc"] as? [String: Any])
        #expect(rc["v"] as? Int == 1)
        #expect(rc["kind"] as? String == "turn_completed")
        #expect(rc["device_id"] as? String == "dev-mac")
        #expect(rc["session_id"] as? String == "ses-1")
        #expect(rc["device_name"] as? String == "mac-studio-office")
        #expect(rc["title"] as? String == "mac-studio-office: turn finished")
    }

    @Test func aClickReadsItsSessionBackAndIgnoresAPayloadThatNamesNone() {
        let notice = TurnNotice(kind: .needsInput, target: target, deviceName: "x")
        #expect(TurnNotice.target(in: notice.payload) == target)
        #expect(TurnNotice.target(in: ["rc": ["device_id": "dev-mac"]]) == nil)
        #expect(TurnNotice.target(in: ["rc": ["device_id": "", "session_id": "s"]]) == nil)
        #expect(TurnNotice.target(in: [:]) == nil)
    }
}

extension LanguageSensitive {
    /// The notifier on a real model, with the inert platform standing in for
    /// the system's notification centre.
    @Suite("Notifier", .serialized) @MainActor
    struct NotifierTests {
        private func signedIn() async -> MacAppModel {
            let model = MacAppModel(options: LaunchOptions(demo: true, ephemeral: true))
            await model.restoreOrPrompt()
            for _ in 0..<200 where !model.connection.hasSnapshot { try? await Task.sleep(for: .milliseconds(25)) }
            return model
        }

        private func platform(of model: MacAppModel) throws -> InertNotificationPlatform {
            try #require(SettingsFeature.state(of: model).notifier.platform as? InertNotificationPlatform)
        }

        private func session(_ state: SessionState, resume: SessionResume? = nil) -> Session {
            Session(sessionID: "s1", deviceID: DemoFixtures.macDeviceID, agent: "claude", title: "t", cwd: "/",
                    state: state, resume: resume)
        }

        @Test func turningItOnAsksTheSystemOnlyWhileItHasNeverBeenAsked() async throws {
            let model = await signedIn()
            defer { model.discardEphemeralState() }
            let notifier = SettingsFeature.state(of: model).notifier
            let system = try platform(of: model)
            system.granted = .notDetermined
            await notifier.refresh()
            await notifier.turn(on: true)
            #expect(system.requests == 1)
            #expect(notifier.isOn && model.settings.notificationsEnabled)
            await notifier.turn(on: false)
            await notifier.turn(on: true)
            #expect(system.requests == 1)
            #expect(notifier.isOn)
            await model.signOut()
        }

        @Test func aRefusalLeavesItOffAndSaysBlocked() async throws {
            let model = await signedIn()
            defer { model.discardEphemeralState() }
            let notifier = SettingsFeature.state(of: model).notifier
            let system = try platform(of: model)
            system.granted = .notDetermined
            system.answer = .denied
            await notifier.turn(on: true)
            #expect(!notifier.isOn && !model.settings.notificationsEnabled)
            #expect(notifier.permission == .denied)
            await model.signOut()
        }

        @Test func itPostsTheGatewaysMomentsUnderTheDevicesName() async throws {
            let model = await signedIn()
            defer { model.discardEphemeralState() }
            let notifier = SettingsFeature.state(of: model).notifier
            let system = try platform(of: model)
            await notifier.turn(on: true)
            notifier.sessionChanged(from: session(.running), to: session(.idle))
            #expect(system.posted.map(\.body) == ["mac-studio-office: turn finished"])
            notifier.sessionChanged(from: session(.idle), to: session(.running))
            #expect(system.posted.count == 1)
            await model.signOut()
        }

        @Test func nothingIsPostedWithTheSwitchOff() async throws {
            let model = await signedIn()
            defer { model.discardEphemeralState() }
            let notifier = SettingsFeature.state(of: model).notifier
            let system = try platform(of: model)
            notifier.sessionChanged(from: session(.running), to: session(.needsApproval))
            #expect(system.posted.isEmpty)
            await model.signOut()
        }

        @Test func theConversationOnScreenIsNotAnnounced() async throws {
            let model = await signedIn()
            defer { model.discardEphemeralState() }
            let notifier = SettingsFeature.state(of: model).notifier
            let system = try platform(of: model)
            await notifier.turn(on: true)
            model.router.go(.chat(deviceId: DemoFixtures.macDeviceID, sessionId: "s1"))
            model.isWindowActive = true
            notifier.sessionChanged(from: session(.running), to: session(.needsInput))
            #expect(system.posted.isEmpty)
            model.isWindowActive = false
            notifier.sessionChanged(from: session(.running), to: session(.needsInput))
            #expect(system.posted.map(\.kind) == [.needsInput])
            await model.signOut()
        }

        @Test func aResumeThatRanOrWasDroppedIsReadFromItsEvent() async throws {
            let model = await signedIn()
            defer { model.discardEphemeralState() }
            let notifier = SettingsFeature.state(of: model).notifier
            let system = try platform(of: model)
            await notifier.turn(on: true)
            let known = try #require(model.connection.sessions.first)
            for (seq, status) in [(1, ResumeStatus.fired), (2, .scheduled), (3, .dropped), (4, .cancelled)] {
                let event = SessionEvent(seq: seq, ts: 0, kind: SessionEvent.resumeKind,
                                         body: .resume(ResumePayload(status: status)))
                notifier.receive(.sessionEvent(sessionID: known.sessionID, deviceID: known.deviceID, event: event))
            }
            #expect(system.posted.map(\.kind) == [.resumed, .resumeDropped])
            #expect(system.posted.allSatisfy { $0.target.sessionID == known.sessionID })
            await model.signOut()
        }

        @Test func aClickOpensItsConversationAndASignOutTakesWhatWasPosted() async throws {
            let model = await signedIn()
            defer { model.discardEphemeralState() }
            let system = try platform(of: model)
            model.router.replace(.sessions)
            let live = NoticeTarget(deviceID: DemoFixtures.macDeviceID, sessionID: DemoFixtures.liveSessionID)
            system.onOpen?(live)
            #expect(model.router.route == .chat(deviceId: live.deviceID, sessionId: live.sessionID))
            #expect(model.router.canGoBack)
            await SettingsFeature.state(of: model).notifier.turn(on: true)
            SettingsFeature.state(of: model).notifier.sessionChanged(from: session(.running), to: session(.error))
            #expect(!system.posted.isEmpty)
            await model.signOut()
            #expect(system.posted.isEmpty)
        }
    }
}
