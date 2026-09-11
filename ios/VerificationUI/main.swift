import Foundation
import RCCore
import RCUI

/// Checks that need SwiftUI or the main actor. Everything that can live without
/// them is in `RCVerify`, which runs with plain Command Line Tools.
@MainActor
func run() async -> (passed: Int, failures: [String]) {
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

    // MARK: - The demo app model

    let model = AppModel(arguments: ["--demo"])
    await settle { model.connection.hasSnapshot }
    // The list exactly as the hello delivered it. The scripted device keeps
    // changing sessions after this point — a turn runs, and the archived
    // session is resumed (A15) — so the layout checks below read a snapshot
    // rather than racing it.
    let helloSessions = model.connection.sessions
    expect(model.isDemo, "the demo launch argument enters demo mode")
    expect(model.connection.hasSnapshot, "the demo hello arrives")
    equal(model.connection.devices.count, 3, "the demo serves three devices")
    equal(model.tab, .sessions, "the app opens on the sessions tab")

    // Opening a session installs a chat store and pushes navigation.
    let live = model.connection.sessions.first { $0.sessionID == DemoFixtures.liveSessionID }
    expect(live != nil, "the demo live session is listed")
    if let live {
        await model.open(live)
        await settle { (model.chat?.timeline.entries.count ?? 0) > 5 }
        equal(model.chat?.key, live.id, "opening a session installs its chat store")
        equal(model.path.count, 1, "opening a session pushes one navigation entry")
        expect((model.chat?.timeline.roots.count ?? 0) > 3, "the transcript has renderable rows")
        expect(model.chat?.timeline.todos.count == 4, "the todos snapshot is present")

        // A draft survives closing and reopening the same conversation.
        model.chat?.draft = "half a thought"
        await model.closeChat()
        expect(model.chat == nil, "closing the chat releases the store")
        await model.open(live)
        await settle { model.chat?.draft.isEmpty == false }
        equal(model.chat?.draft, "half a thought", "the draft is restored on reopening")
        model.chat?.draft = ""
        await model.saveDraft()
    }

    // MARK: - Amendment A7 through the UI model

    if let terminal = model.connection.sessions.first(where: {
        $0.control == .terminal && $0.state == .running
    }) {
        let locked = ChatStore(session: terminal, channel: DemoGateway())
        locked.agent = model.agent(for: terminal)
        locked.draft = "hello"
        expect(locked.isReadOnly, "a running terminal session is still read-only")
        expect(!locked.canStop, "the app does not stop a terminal turn")
        expect(locked.canTakeover, "and the agent advertises takeover")
        equal(locked.statusLine, "Controlled by the terminal · Take over to send",
              "the takeover line shows while the terminal turn runs")
        equal(locked.attachHint, ChatStore.AttachHint.restartSession,
              "a prepared device says the running process was started without the attachment")
    } else {
        expect(false, "the demo has a terminal-controlled session")
    }

    // MARK: - Amendment A10 through the UI model

    if let shared = model.connection.sessions.first(where: {
        $0.sessionID == DemoFixtures.sharedSessionID
    }) {
        await model.open(shared)
        await settle { model.chat?.key == shared.id }
        guard let chat = model.chat else {
            expect(false, "the attached session opens")
            return (passed, failures)
        }
        expect(!chat.isReadOnly, "an attached session types like a remote one")
        expect(chat.isAttached, "and knows a terminal owns it")
        expect(!chat.canTakeover, "takeover is never offered on an attached session")
        expect(!chat.canStop, "and a Claude channel cannot interrupt the turn")
        expect(!chat.allowsAttachments, "attachments cannot reach a live CLI")
        expect(!chat.allowsSettingsChanges, "model, permission mode and effort stay in the terminal")
        equal(chat.statusLine, nil, "and the composer repeats none of it")
        equal(shared.statusLabel, "terminal · attached", "the session list names the terminal")
        equal(shared.dotTone(online: true), DotTone.live,
              "with the green of a session that is alive and quiet, not the grey of an exited one")
        await model.closeChat()
    } else {
        expect(false, "the demo has an attached session")
    }

    // MARK: - Amendment A11 through the UI model

    if let codex = model.connection.sessions.first(where: {
        $0.sessionID == DemoFixtures.codexSharedSessionID
    }) {
        await model.open(codex)
        await settle { model.chat?.key == codex.id }
        guard let chat = model.chat else {
            expect(false, "the shared Codex thread opens")
            return (passed, failures)
        }
        expect(chat.isAttached, "the daemon shares the thread with the terminal")
        expect(chat.allowsSettingsChanges, "shared_settings reopens the model and effort pickers")
        expect(chat.allowsAttachments, "shared_attachments reopens the attachment button")
        expect(chat.canStop, "shared_interrupt offers Stop while the terminal's turn runs")
        expect(!chat.canTakeover, "a shared thread is never taken over")
        equal(chat.timeline.pendingRequest?.approval?.options.count, 4,
              "the daemon's four decisions all reach the card")
        expect(chat.steersRunningTurn, "and a message joins the running turn rather than queueing")
        await model.closeChat()
    } else {
        expect(false, "the demo has a shared Codex thread")
    }

    // MARK: - The status dot through the demo list

    // The five tones are all on the sessions screen at once, and each one comes
    // from all three facts rather than from `state` alone.
    let online = Dictionary(uniqueKeysWithValues: model.connection.devices.map { ($0.deviceID, $0.online) })
    func tone(_ sessionID: String) -> DotTone? {
        model.connection.sessions.first { $0.sessionID == sessionID }
            .map { $0.dotTone(online: online[$0.deviceID] ?? false) }
    }
    equal(tone(DemoFixtures.liveSessionID), .working, "a running turn pulses green")
    equal(tone(DemoFixtures.approvalSessionID), .waiting, "a request for approval is amber")
    equal(tone(DemoFixtures.sharedSessionID), .live, "an attached session that is quiet is solid green")
    equal(tone(DemoFixtures.erroredSessionID), .failed, "an agent that stopped on an error is red")
    equal(tone(DemoFixtures.doneSessionID), .off,
          "a session nothing owns, on a machine that is offline, is grey")
    equal(tone(DemoFixtures.attachHintSessionID), .live,
          "a terminal session on a reachable machine is alive, whatever the app may type into it")

    // MARK: - Amendment A12: the message is on screen before the device says so
    //
    // Sending starts a turn, so this runs after the dot tones have been read.

    if let quiet = model.connection.sessions.first(where: {
        $0.sessionID == DemoFixtures.erroredSessionID
    }) {
        await model.open(quiet)
        await settle { model.chat?.key == quiet.id }
        guard let chat = model.chat else {
            expect(false, "the quiet session opens")
            return (passed, failures)
        }
        chat.draft = "one more thing"
        let sending = Task { await chat.send() }
        await settle { chat.timeline.roots.contains { $0.pending != nil } }
        expect(chat.timeline.roots.last?.pending?.text == "one more thing",
               "the bubble is in the transcript before the request has been answered")
        expect(chat.draft.isEmpty, "and the field is clear the moment Send is tapped")
        await sending.value
        await settle(timeout: 5) { chat.timeline.optimistic.isEmpty }
        expect(chat.timeline.optimistic.isEmpty,
               "the device's echo under the same id retires the pending row")
        equal(chat.timeline.roots.filter { $0.userMessage?.text == "one more thing" }.count, 1,
              "leaving exactly one copy of the message")
        await model.closeChat()
    } else {
        expect(false, "the demo has a reachable session with no turn running")
    }

    // A socket that is coming back is not a reason to grey out Send: the
    // transport holds the request until the hello lands.
    expect(ConnectionPhase.reconnecting.canReachGateway, "a reconnecting app can still send")
    expect(!ConnectionPhase.expired.canReachGateway, "an expired session cannot")

    if let hinted = model.connection.sessions.first(where: {
        $0.sessionID == DemoFixtures.attachHintSessionID
    }) {
        let locked = ChatStore(session: hinted, channel: DemoGateway())
        locked.agent = model.agent(for: hinted)
        equal(locked.attachHint, ChatStore.AttachHint.installShim,
              "a device without the shim says how to install it")
    } else {
        expect(false, "the demo has a terminal session the device cannot attach")
    }

    // MARK: - Deep links

    model.handle(SessionLink(deviceID: DemoFixtures.macDeviceID, sessionID: DemoFixtures.approvalSessionID))
    await settle { model.chat?.sessionID == DemoFixtures.approvalSessionID }
    equal(model.chat?.sessionID, DemoFixtures.approvalSessionID, "a deep link opens the named session")
    equal(model.tab, .sessions, "a deep link lands on the sessions tab")

    model.handle(SessionLink(deviceID: "nope", sessionID: "nope"))
    await settle { model.toast != nil }
    expect(model.toast != nil, "a link to an unknown session says so instead of opening nothing")
    model.toast = nil

    // MARK: - Session list presentation

    let sessions = SessionStore(defaults: UserDefaults(suiteName: "rc-ui-verify-\(UUID().uuidString)")!)
    let groups = sessions.groups(helloSessions, devices: model.connection.devices)
    equal(groups.count, 3, "the list is grouped by device")
    equal(groups.flatMap { $0.active + $0.archive }.count, 9, "every demo session is placed")
    equal(groups.first?.active.first?.state, .needsApproval,
          "a session waiting on the user sorts first")
    equal(groups.first?.name, "mac-studio-office", "the machine with live work leads the list")
    equal(groups.last?.active.count, 0, "the machine whose CLI exited holds nothing live")
    equal(groups.last?.archive.count, 1, "and keeps that session in its own Archive")
    equal(groups.first?.archive.map(\.sessionID), [DemoFixtures.revivedSessionID],
          "the machine with live work carries the one session archived by hand")
    expect(groups.allSatisfy { !$0.collapsed && !$0.archiveExpanded },
           "groups open and archives closed, until the reader says otherwise")
    equal(groups.first?.active.first?.agentLabel, "Codex", "a row can name its agent")

    sessions.agentFilter = "claude"
    let claudeOnly = sessions.groups(helloSessions, devices: model.connection.devices)
    equal(claudeOnly.count, 2, "the agent filter drops a machine with nothing left")
    expect(claudeOnly.allSatisfy { $0.active.allSatisfy { $0.agent == "claude" } },
           "and every row that remains runs the chosen agent")
    sessions.agentFilter = nil

    // MARK: - Push reconciliation

    let platform = FakeNotificationPlatform()
    let push = PushController(platform: platform, bundleID: "com.junbingao.remotecontrol")
    let gateway = DemoGateway()
    var opened: PushRoute?
    push.attach(api: gateway, enabled: false) { opened = $0 }
    await settle { push.statusText == "Off" }
    equal(push.statusText, "Off", "notifications start off")

    platform.authorizationValue = .authorized
    platform.tokenValue = "abcdef0123456789"
    push.setEnabled(true)
    await settle { push.statusText == "On" }
    equal(push.statusText, "On", "an authorized device with a token registers")
    expect(platform.registerCalls > 0, "registration is requested from the system")

    push.setEnabled(false)
    await settle { push.statusText == "Off" }
    equal(push.statusText, "Off", "turning notifications off unregisters")

    platform.authorizationValue = .denied
    push.setEnabled(true)
    await settle { push.statusText.contains("Blocked") }
    expect(push.statusText.contains("Blocked"), "a denied device says where to fix it")

    platform.onOpen?(PushRoute(kind: .needsApproval, deviceID: "d", sessionID: "s",
                               deviceName: "mac", title: "mac: approval needed"))
    equal(opened?.sessionID, "s", "a notification tap is routed to the app")

    // MARK: - Dictation lifecycle

    let scripted = ScriptedSpeechInput(transcript: "run the suite again")
    let voice = InlineVoiceDraftSession(platform: scripted, isPreview: true)
    let target = VoiceDraftTarget(account: "demo", deviceID: "d", sessionID: "s")
    voice.start(draft: "before", target: target)
    await settle { voice.voice.phase == .listening }
    equal(voice.voice.phase, .listening, "dictation reaches the listening phase")
    equal(voice.updateDraft(currentDraft: "before", currentTarget: target), "before\nrun the suite again",
          "a partial transcript is merged into the draft")
    equal(voice.updateDraft(currentDraft: "before\nrun the suite again", currentTarget: target), nil,
          "an unchanged transcript does not rewrite the draft")

    // A human edit wins: dictation stops rewriting what the user typed.
    equal(voice.updateDraft(currentDraft: "typed by hand", currentTarget: target), nil,
          "a typed edit stops dictation from overwriting it")

    let other = VoiceDraftTarget(account: "demo", deviceID: "d", sessionID: "another")
    voice.start(draft: "x", target: target)
    await settle { voice.voice.phase == .listening }
    equal(voice.updateDraft(currentDraft: "x", currentTarget: other), nil,
          "a transcript never lands in a different session's composer")

    voice.reset()
    equal(voice.voice.phase, .idle, "resetting dictation returns to idle")

    // MARK: - Dictation has no maximum duration

    expect(VoiceInputController.listeningDeadline == nil,
           "listening has no deadline of its own")

    let segmented = SegmentedSpeechInput()
    let unlimited = VoiceInputController(platform: segmented)
    unlimited.start()
    await settle { unlimited.phase == .listening }
    equal(unlimited.phase, .listening, "dictation starts listening")
    expect(!unlimited.isAwaitingFinalTranscript, "listening arms no timer of its own")

    segmented.hear("re-run the auth suite")
    await settle { unlimited.transcript == "re-run the auth suite" }

    // The recognition request underneath expires. A backend rolls over to a new
    // one rather than ending the session, and the microphone never stops.
    segmented.rollOver()
    segmented.hear("on the CI runner too")
    await settle { unlimited.transcript.contains("CI runner") }
    equal(unlimited.phase, .listening,
          "a recognition request ending mid-session is a restart, not a stop")
    equal(segmented.requests, 2, "the backend opened a second request underneath")
    equal(unlimited.transcript, "re-run the auth suite on the CI runner too",
          "each segment is appended in the order it was spoken")

    unlimited.finish()
    await settle { unlimited.phase == .review }
    equal(unlimited.phase, .review, "Done ends the session")
    equal(unlimited.transcript, "re-run the auth suite on the CI runner too",
          "and keeps every segment of the transcript")
    expect(!unlimited.isAwaitingFinalTranscript, "with no timer left behind")
    unlimited.cancel()

    // MARK: - Done keeps the draft, Cancel puts back the one before it

    let composerTarget = VoiceDraftTarget(account: "demo", deviceID: "d", sessionID: "s")

    let keeping = SegmentedSpeechInput()
    let keepSession = InlineVoiceDraftSession(platform: keeping, isPreview: true)
    keepSession.start(draft: "", target: composerTarget)
    await settle { keepSession.voice.phase == .listening }
    keeping.hear("first half")
    keeping.rollOver()
    keeping.hear("second half")
    await settle { keepSession.voice.transcript.contains("second half") }
    keepSession.finish()
    await settle { keepSession.voice.phase == .review }
    equal(keepSession.updateDraft(currentDraft: "", currentTarget: composerTarget),
          "first half second half",
          "Done leaves the whole transcript in the message field")
    keepSession.reset()

    let cancelling = SegmentedSpeechInput()
    let cancelSession = InlineVoiceDraftSession(platform: cancelling, isPreview: true)
    cancelSession.start(draft: "the draft I already had", target: composerTarget)
    await settle { cancelSession.voice.phase == .listening }
    cancelling.hear("and some dictation")
    await settle { cancelSession.voice.transcript == "and some dictation" }
    let merged = cancelSession.updateDraft(currentDraft: "the draft I already had",
                                           currentTarget: composerTarget)
    equal(merged, "the draft I already had\nand some dictation",
          "dictation is appended after whatever the user already had")
    equal(cancelSession.cancel(currentDraft: merged ?? "", currentTarget: composerTarget),
          "the draft I already had",
          "Cancel discards what this dictation added and restores the previous draft")
    equal(cancelSession.voice.phase, .idle, "and leaves dictation idle")

    // MARK: - How far the message field grows

    equal(ComposerLayout.growth.lowerBound, 1, "an empty composer is one line")
    equal(ComposerLayout.growth.upperBound, 8, "and it stops growing at eight")
    equal(ComposerLayout.lines(in: ""), 1, "an empty draft is a single row")
    equal(ComposerLayout.lines(in: "one line of dictated text"), 1,
          "a short draft stays compact")
    equal(ComposerLayout.lines(in: "one\ntwo\nthree"), 3, "the field grows with the draft")
    let paragraph = String(repeating: "line\n", count: 20)
    equal(ComposerLayout.lines(in: paragraph), 8, "and stops at the cap")
    expect(!ComposerLayout.scrolls("one\ntwo"), "a short draft does not scroll")
    expect(ComposerLayout.scrolls(paragraph), "past the cap the text scrolls inside the field")

    // MARK: - The listening glow follows the display

    equal(DisplayCorner.radius(bottomSafeArea: 34), DisplayCorner.fallbackRadius,
          "a display with a home indicator has round corners to follow")
    equal(DisplayCorner.radius(bottomSafeArea: 0), DisplayCorner.squareRadius,
          "an older display does not")

    // MARK: - Review finding 2: the finish grace belongs to the backend

    equal(SystemSpeechRecognizer(localeIdentifier: "en-US").finishGracePeriod, 2,
          "on-device recognition answers within two seconds")
    let gatewayClient = GatewayHTTPClient(endpoint: GatewayEndpoint.placeholder)
    let gatewayRecognizer = GatewaySpeechRecognizer(client: gatewayClient, language: "en")
    expect(gatewayRecognizer.finishGracePeriod >= STTSocket.finalTimeout,
           "the gateway grace outlasts the socket's own deadline")

    // A backend with a long grace must not be cancelled at the on-device value.
    let patient = SlowSpeechInput(grace: 4)
    let patientController = VoiceInputController(platform: patient)
    patientController.start()
    await settle { patientController.phase == .listening }
    patientController.finish()
    equal(patientController.phase, .finishing, "stopping enters the finishing phase")
    try? await Task.sleep(for: .milliseconds(2_600))
    equal(patientController.phase, .finishing,
          "a gateway-length grace is still waiting after the on-device timeout")
    patient.deliverFinal("the whole sentence")
    await settle { patientController.phase == .review }
    equal(patientController.transcript, "the whole sentence",
          "the late transcript is kept rather than discarded")
    patientController.cancel()

    // MARK: - Speech backend selection

    let settings = SettingsStore(defaults: UserDefaults(suiteName: "rc-ui-verify-\(UUID().uuidString)")!)
    settings.voiceBackend = .gateway
    let demoBackend = SpeechBackend.make(settings: settings, connection: model.connection, arguments: [])
    expect(!demoBackend.isScripted, "a shipping path never selects the scripted platform")
    let scriptedBackend = SpeechBackend.make(settings: settings, connection: model.connection,
                                             arguments: ["--voice-preview"])
    #if DEBUG
    expect(scriptedBackend.isScripted, "the scripted platform needs an explicit launch argument")
    #else
    expect(!scriptedBackend.isScripted, "release builds have no scripted speech platform")
    #endif

    // MARK: - Markdown resources

    let bundle = MarkdownAssets.directory
    expect(bundle != nil, "the Markdown resource directory is reachable without a force unwrap")
    if let bundle {
        for asset in ["katex.min.js", "katex.min.css", "mermaid.min.js", "renderer.js",
                      "VENDOR.json", "katex-LICENSE.txt", "mermaid-LICENSE.txt"] {
            expect(FileManager.default.fileExists(atPath: bundle.appending(path: asset).path),
                   "the Markdown bundle ships \(asset)")
        }
        let fonts = (try? FileManager.default.contentsOfDirectory(atPath: bundle.appending(path: "fonts").path)) ?? []
        expect(fonts.count >= 20, "the KaTeX fonts ship with the bundle")
    }

    // MARK: - Sign out

    await model.signOut()
    equal(model.connection.phase, .signedOut, "signing out returns to the login screen")
    expect(model.chat == nil, "signing out closes the open conversation")

    return (passed, failures)
}

/// A platform that models what both speech backends do for a dictation with no
/// maximum duration: the microphone stays up while the recognition request
/// underneath is rolled over, each request owns one slot in the transcript, and
/// nothing is called final until the user is done.
@MainActor
final class SegmentedSpeechInput: SpeechInputPlatform {
    private var onEvent: (@Sendable (SpeechInputEvent) -> Void)?
    private var segments = TranscriptSegments()
    private var slot = 0
    private(set) var requests = 0

    func requestPermission() async throws {}

    func start(onEvent: @escaping @Sendable (SpeechInputEvent) -> Void) throws {
        self.onEvent = onEvent
        segments = TranscriptSegments()
        slot = segments.begin()
        requests = 1
    }

    /// One more partial result for the request that is running.
    func hear(_ text: String) {
        segments.update(slot, text: text)
        publish()
    }

    /// The running request expired. A new one takes over without the session
    /// ending and without anything final reaching the controller.
    func rollOver() {
        segments.end(slot)
        slot = segments.begin()
        requests += 1
        publish()
    }

    func finish() {
        segments.end(slot)
        publish(isFinal: segments.isSettled)
    }

    func cancel() { onEvent = nil }

    private func publish(isFinal: Bool = false) {
        onEvent?(.transcript(segments.joined, isFinal: isFinal))
    }
}

/// A platform that answers `finish()` only when told to, with a configurable
/// grace period.
@MainActor
final class SlowSpeechInput: SpeechInputPlatform {
    private var onEvent: (@Sendable (SpeechInputEvent) -> Void)?
    let finishGracePeriod: TimeInterval

    init(grace: TimeInterval) { finishGracePeriod = grace }

    func requestPermission() async throws {}
    func start(onEvent: @escaping @Sendable (SpeechInputEvent) -> Void) throws {
        self.onEvent = onEvent
        onEvent(.transcript("the whole", isFinal: false))
    }
    func finish() {}
    func cancel() { onEvent = nil }
    func deliverFinal(_ text: String) { onEvent?(.transcript(text, isFinal: true)) }
}

/// A notification platform with no UserNotifications behind it.
@MainActor
final class FakeNotificationPlatform: NotificationPlatform {
    var supported = true
    var environment: String? = "sandbox"
    var tokenValue: String?
    var authorizationValue: PushAuthorization = .notDetermined
    var registerCalls = 0
    var onChange: (() -> Void)?
    var onOpen: ((PushRoute) -> Void)?

    var token: String? { tokenValue }
    func authorization() async -> PushAuthorization { authorizationValue }
    func requestAuthorization() async throws -> Bool { authorizationValue = .authorized; return true }
    func register() { registerCalls += 1 }
    func unregister() { tokenValue = nil }
    func openSettings() {}
}

let result = await run()
if result.failures.isEmpty {
    print("PASS: \(result.passed) UI checks")
} else {
    print("FAIL: \(result.failures.count) of \(result.passed + result.failures.count) UI checks")
    for failure in result.failures { print("  · \(failure)") }
    exit(1)
}
