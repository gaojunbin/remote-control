import Foundation
import RCCore
import RCUI

/// Amendment A47 through the iPhone's model (`docs/DESIGN.md` § "A red dot for
/// a session that stopped and waits for you"): the conversation on screen with
/// the app in front is seen, one opened while the app is away or behind its
/// lock is seen when it comes forward, a session on screen when its turn ends
/// keeps no dot, and the home-screen badge follows the dots while Notify me is
/// on and goes with the account.
@MainActor
func unseenChecks() async -> (passed: Int, failures: [String]) {
    var passed = 0
    var failures: [String] = []
    func expect(_ condition: Bool, _ label: String) {
        if condition { passed += 1 } else { failures.append(label) }
    }
    func equal<T: Equatable>(_ lhs: T, _ rhs: T, _ label: String) {
        if lhs == rhs { passed += 1 } else { failures.append("\(label) — got \(lhs), expected \(rhs)") }
    }
    func settle(timeout: TimeInterval = 3, _ condition: () -> Bool) async {
        let deadline = Date().addingTimeInterval(timeout)
        while !condition(), Date() < deadline { try? await Task.sleep(for: .milliseconds(20)) }
    }

    let badges = FakeBadgePlatform()
    let gateway = DemoGateway(echoDelay: .zero, resumeDelay: nil, changesPreferencesElsewhere: false)
    let connection = ConnectionStore()
    let model = AppModel(connection: connection, settings: SettingsStore(defaults: freshDefaults()),
                         push: PushController(platform: FakeNotificationPlatform()), badge: badges,
                         arguments: [])
    await connection.enterDemo(api: gateway, channel: gateway)
    await settle { connection.hasSnapshot }
    func session(_ id: String) -> Session? { connection.sessions.first { $0.sessionID == id } }
    func turn(_ text: String) async {
        _ = try? await gateway.request(.send(sessionID: DemoFixtures.piSessionID, text: text))
        await settle { session(DemoFixtures.piSessionID)?.state == .running }
        await settle { session(DemoFixtures.piSessionID)?.state == .idle }
    }
    guard let vite = session(DemoFixtures.approvalSessionID), let pi = session(DemoFixtures.piSessionID) else {
        expect(false, "the demo carries the marked approval session and the pi session")
        return (passed, failures)
    }

    // The badge belongs to Notify me: with the switch off nothing is pushed to
    // this phone, so the app keeps no number it could not keep true.
    equal(badges.counts, [0], "the badge starts at nothing while Notify me is off")
    model.settings.notificationsEnabled = true
    await settle { badges.counts.last == 1 }
    equal(badges.counts.last, 1, "Notify me on, the badge is the demo's one red dot")

    // Opened while the app is away: not in front of anyone yet.
    await model.open(vite)
    try? await Task.sleep(for: .milliseconds(200))
    expect(session(vite.sessionID)?.unseen == true, "a conversation opened behind the app's back is not seen")
    model.setSceneActive(true)
    await settle { session(vite.sessionID)?.unseen == false }
    expect(session(vite.sessionID)?.unseen == false, "the app coming forward with it open sends session.seen")
    await settle { badges.counts.last == 0 }
    equal(badges.counts.last, 0, "and the badge drops with the dot")

    // Nothing open: a turn that ends is one to come back to.
    await model.closeChat()
    await turn("go")
    await settle { session(pi.sessionID)?.unseen == true }
    expect(session(pi.sessionID)?.unseen == true, "a turn that ends with nothing open marks its session")
    await settle { badges.counts.last == 1 }
    equal(badges.counts.last, 1, "and the badge counts it")
    await model.open(pi)
    await settle { session(pi.sessionID)?.unseen == false }
    expect(session(pi.sessionID)?.unseen == false, "opening it in front of the person clears it")

    // On screen when the turn ends: the mark goes as soon as it comes.
    await turn("again")
    await settle { session(pi.sessionID)?.unseen == false }
    expect(session(pi.sessionID)?.unseen == false, "a session on screen when its turn ends keeps no dot")

    // Behind the lock the conversation is not in front of anyone.
    model.isLocked = true
    await turn("locked")
    await settle { session(pi.sessionID)?.unseen == true }
    try? await Task.sleep(for: .milliseconds(200))
    expect(session(pi.sessionID)?.unseen == true, "the app lock keeps the dot until it is lifted")
    model.isLocked = false
    await settle { session(pi.sessionID)?.unseen == false }
    expect(session(pi.sessionID)?.unseen == false, "and unlocking onto the conversation clears it")

    // Away from the app, then back.
    model.setSceneActive(false)
    await turn("away")
    await settle { session(pi.sessionID)?.unseen == true }
    expect(session(pi.sessionID)?.unseen == true, "a turn that ends while the app is away marks it")
    await settle { badges.counts.last == 1 }
    model.settings.notificationsEnabled = false
    await settle { badges.counts.last == 0 }
    equal(badges.counts.last, 0, "Notify me off takes the number off the icon")
    model.settings.notificationsEnabled = true
    await settle { badges.counts.last == 1 }
    equal(badges.counts.last, 1, "and on again puts it back")
    model.setSceneActive(true)
    await settle { session(pi.sessionID)?.unseen == false }
    expect(session(pi.sessionID)?.unseen == false, "coming back with the conversation open clears it")

    // With a dot still on the account, the number goes with the account.
    await model.closeChat()
    await turn("elsewhere")
    await settle { session(pi.sessionID)?.unseen == true }
    await settle { badges.counts.last == 1 }
    await model.signOut()
    equal(badges.counts.last, 0, "signing out clears the badge")

    let push = URL(fileURLWithPath: #filePath)
        .deletingLastPathComponent().deletingLastPathComponent()
        .appending(path: "Sources/RCUI/Push/SystemNotifications.swift")
    let source = (try? String(contentsOf: push, encoding: .utf8)) ?? ""
    expect(source.contains("requestAuthorization(options: [.alert, .sound, .badge])"),
           "Notify me asks for the badge with alerts and sounds, in one question")

    return (passed, failures)
}

/// The home-screen badge, with no UserNotifications behind it.
@MainActor
final class FakeBadgePlatform: BadgePlatform {
    private(set) var counts: [Int] = []
    func setBadge(_ count: Int) { counts.append(count) }
}
