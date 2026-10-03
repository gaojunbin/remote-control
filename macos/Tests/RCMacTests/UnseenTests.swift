import AppKit
import Foundation
import RCCore
import Testing
@testable import RCMac

/// Amendment A47 on the Mac (`docs/DESIGN.md` § "A red dot for a session that
/// stopped and waits for you"): the conversation open in the window that has
/// focus is seen — when it opens there, when the window comes forward, and when
/// a mark arrives while it is there — the Dock badge is the number of dots and
/// nothing at zero, and signing out takes it away.
@Suite("Red dot and Dock badge", .serialized) @MainActor
struct UnseenTests {
    private let mac = DemoFixtures.macDeviceID

    private func signedIn() async -> MacAppModel {
        let model = MacAppModel(options: LaunchOptions(demo: true, ephemeral: true))
        await model.restoreOrPrompt()
        await settle { model.connection.hasSnapshot }
        return model
    }

    private func settle(_ condition: () -> Bool) async {
        for _ in 0..<200 where !condition() { try? await Task.sleep(for: .milliseconds(25)) }
    }

    private func session(_ model: MacAppModel, _ id: String) -> Session? {
        model.connection.sessions.first { $0.sessionID == id }
    }

    private func dock(of model: MacAppModel) throws -> InertDockBadge {
        try #require(UnseenFeature.state(of: model).dock as? InertDockBadge)
    }

    /// Send to a session the demo device drives and wait for its turn to end.
    private func turn(_ model: MacAppModel, _ id: String) async throws {
        let channel = try #require(model.connection.channel)
        _ = try await channel.request(.send(sessionID: id, text: "go"))
        await settle { session(model, id)?.state == .running }
        await settle { session(model, id)?.state == .idle }
    }

    @Test func theBadgeIsTheNumberOfDotsAndNothingAtZero() {
        #expect(DockBadgeKeeper.label(for: 0) == nil)
        #expect(DockBadgeKeeper.label(for: 1) == "1")
        #expect(DockBadgeKeeper.label(for: 12) == "12")
    }

    @Test func theRowsSayItInBothLanguages() {
        #expect(StringTable.en.sessions.unseen == "not yet opened")
        #expect(StringTable.zhHans.sessions.unseen == "未查看")
    }

    @Test func theConversationInFrontIsTheRoutesWhileItsWindowHasFocus() async {
        let model = await signedIn()
        defer { model.discardEphemeralState() }
        model.router.go(.chat(deviceId: mac, sessionId: "s1"))
        model.isWindowActive = false
        #expect(model.conversationInFront == nil, "a window behind another is in front of nobody")
        model.isWindowActive = true
        #expect(model.conversationInFront == "\(mac)/s1")
        model.router.go(.sessions)
        #expect(model.conversationInFront == nil, "and the list is not a conversation")
        await model.signOut()
    }

    @Test func theRowOfTheConversationInFrontDrawsNoDot() async {
        let model = await signedIn()
        defer { model.discardEphemeralState() }
        let open = Session(sessionID: "s1", deviceID: mac, agent: "claude", title: "t", cwd: "/", unseen: true)
        let other = Session(sessionID: "s2", deviceID: mac, agent: "claude", title: "t", cwd: "/", unseen: true)
        let quiet = Session(sessionID: "s3", deviceID: mac, agent: "claude", title: "t", cwd: "/")
        model.router.go(.chat(deviceId: mac, sessionId: "s1"))
        model.isWindowActive = true
        #expect(!model.showsUnseenDot(open), "it is being looked at while its session.seen is on the way")
        #expect(model.showsUnseenDot(other), "while every other marked row keeps its dot")
        #expect(!model.showsUnseenDot(quiet))
        model.isWindowActive = false
        #expect(model.showsUnseenDot(open), "and behind another window nobody is looking at it")
        await model.signOut()
    }

    @Test func aConversationOpenBehindAnotherWindowIsSeenWhenItComesForward() async throws {
        let model = await signedIn()
        defer { model.discardEphemeralState() }
        let dock = try dock(of: model)
        await settle { dock.labels.last == "1" }
        #expect(dock.labels.last == "1", "the demo opens with one dot, and the Dock says so")

        model.isWindowActive = false
        model.router.go(.chat(deviceId: mac, sessionId: DemoFixtures.approvalSessionID))
        try await Task.sleep(for: .milliseconds(200))
        #expect(session(model, DemoFixtures.approvalSessionID)?.unseen == true,
                "open in a window without focus, it has not been looked at")

        model.isWindowActive = true
        await settle { session(model, DemoFixtures.approvalSessionID)?.unseen == false }
        #expect(session(model, DemoFixtures.approvalSessionID)?.unseen == false,
                "the window coming forward sends session.seen")
        await settle { dock.labels.last == .some(nil) }
        #expect(dock.labels.last == .some(nil), "and with no dot left the Dock icon carries no badge")
        await model.signOut()
    }

    @Test func aTurnThatEndsUnwatchedIsCountedAndOneOnScreenIsNot() async throws {
        let model = await signedIn()
        defer { model.discardEphemeralState() }
        let dock = try dock(of: model)
        model.isWindowActive = true
        model.router.go(.sessions)

        try await turn(model, DemoFixtures.piSessionID)
        await settle { session(model, DemoFixtures.piSessionID)?.unseen == true }
        #expect(session(model, DemoFixtures.piSessionID)?.unseen == true, "nothing had it open")
        await settle { dock.labels.last == "2" }
        #expect(dock.labels.last == "2", "so it joins the count")

        model.router.go(.chat(deviceId: mac, sessionId: DemoFixtures.piSessionID))
        await settle { session(model, DemoFixtures.piSessionID)?.unseen == false }
        #expect(session(model, DemoFixtures.piSessionID)?.unseen == false, "opening it clears it")

        try await turn(model, DemoFixtures.piSessionID)
        await settle { session(model, DemoFixtures.piSessionID)?.unseen == false }
        #expect(session(model, DemoFixtures.piSessionID)?.unseen == false,
                "a mark arriving for the conversation on screen is cleared at once")
        await settle { dock.labels.last == "1" }
        #expect(dock.labels.last == "1", "and only the session nobody opened is counted")
        await model.signOut()
    }

    @Test func signingOutTakesTheBadgeOff() async throws {
        let model = await signedIn()
        defer { model.discardEphemeralState() }
        let dock = try dock(of: model)
        await settle { dock.labels.last == "1" }
        await model.signOut()
        await settle { dock.labels.last == .some(nil) }
        #expect(dock.labels.last == .some(nil))
        #expect(UnseenFeature.state(of: model).badge.shown == 0)
    }

    @Test func theRenderersWindowStandsForTheWindowInFront() async throws {
        let model = await signedIn()
        defer { model.discardEphemeralState() }
        let rendered = FrontWindowStub(contentRect: NSRect(x: -30000, y: -30000, width: 300, height: 200),
                                       styleMask: [.borderless], backing: .buffered, defer: false)
        rendered.isReleasedWhenClosed = false
        defer { rendered.close() }
        WindowChrome().attach(rendered, model: model)
        #expect(model.isWindowActive, "a render is a picture of the window the person is looking at")

        let plain = NSWindow(contentRect: NSRect(x: -30000, y: -30000, width: 300, height: 200),
                             styleMask: [.borderless], backing: .buffered, defer: false)
        plain.isReleasedWhenClosed = false
        defer { plain.close() }
        WindowChrome().attach(plain, model: model)
        #expect(!model.isWindowActive, "while a window AppKit has not made key is not in front")
        await model.signOut()
    }
}

/// A window of the renderer's kind.
private final class FrontWindowStub: NSWindow, FrontmostWindow {}
