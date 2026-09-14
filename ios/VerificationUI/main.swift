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
    expect(model.isResuming, "the demo is an account, so the first frame is the app and not the form")
    await model.restoreOrPrompt()
    expect(!model.isResuming, "and the wait ends once the account is up")
    await settle { model.connection.hasSnapshot }
    // The list exactly as the hello delivered it. The scripted device keeps
    // changing sessions after this point — a turn runs, and the archived
    // session is resumed (A15) — so the layout checks below read a snapshot
    // rather than racing it.
    let helloSessions = model.connection.sessions
    expect(model.isDemo, "the demo launch argument enters demo mode")
    expect(model.connection.hasSnapshot, "the demo hello arrives")
    equal(model.connection.devices.count, 3, "the demo serves three devices")

    // MARK: - Three tabs, one order, one landing rule
    //
    // `docs/DESIGN.md` § "Three tabs, one order, one landing rule".
    equal(AppModel.landingTab(hasDevices: true), .sessions,
          "an account with a machine lands on the conversation")
    equal(AppModel.landingTab(hasDevices: false), .devices,
          "and a new account lands where its first job is")

    // Nothing is decided before the first device list: the one-shot is not
    // spent by a call that arrives while the snapshot is still in flight.
    let waiting = AppModel(arguments: [])
    waiting.tab = .settings
    waiting.decideLandingTab()
    equal(waiting.tab, .settings, "the rule does not run until the device list has arrived")
    await waiting.enterDemo()
    await settle { waiting.connection.hasSnapshot }
    waiting.decideLandingTab()
    equal(waiting.tab, .sessions, "and runs on the first list that does arrive")

    // Decided once. A tab chosen by hand afterwards is not bounced back.
    waiting.tab = .devices
    waiting.decideLandingTab()
    equal(waiting.tab, .devices, "the landing rule runs once per sign-in and no more")
    await waiting.signOut()

    model.decideLandingTab()
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

        // MARK: - Two levels of detail, Simple by default
        //
        // The level is read from Settings on every draw, so the open transcript
        // changes with it and nothing has to be reloaded.
        if let chat = model.chat {
            equal(model.settings.timelineDetail, .simple, "a transcript opens at Simple")
            equal(chat.detail, .simple, "and the open conversation is drawn at that level")
            expect(!chat.rows.isEmpty, "Simple still draws the conversation")
            expect(chat.rows.allSatisfy { $0.toolCall == nil && $0.thinking == nil },
                   "with no tool call and no thinking among its rows")
            expect(chat.rows.contains { $0.userMessage != nil }, "the reader's own message is there")
            expect(chat.rows.contains { $0.assistantText != nil }, "and so is the agent's prose")
            expect(!chat.showsTodos, "and the header carries no todo chip")
            // Amendment A17: this session is the app's own, so its settings are
            // offered rather than shown.
            expect(chat.allowsSettingsChanges, "a session this app drives keeps its pickers")
            expect(chat.terminalSettings.isEmpty, "and shows no read-only chips beside them")

            // Nothing is fetched: the same transcript is filtered, both ways.
            let held = chat.timeline.entries.count
            model.settings.timelineDetail = .detailed
            equal(chat.detail, .detailed, "changing the preference reaches the open conversation")
            expect(chat.rows.contains { $0.toolCall != nil }, "Detailed draws the tool calls")
            expect(chat.rows.contains { $0.thinking != nil }, "and the thinking")
            expect(chat.showsTodos, "and the todo chip is back")
            expect(chat.timeline.entries.count >= held,
                   "with no reload: the store held every block all along")

            model.settings.timelineDetail = .simple
            expect(chat.rows.allSatisfy { $0.toolCall == nil && $0.thinking == nil },
                   "and switching back hides them again, without touching the store")
        }

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
        equal(locked.statusLine, "Controlled by the terminal · take over to send",
              "the takeover line shows while the terminal turn runs")
        equal(locked.attachHint, ChatStore.AttachHint.restartSession,
              "a prepared device says the running process was started without the attachment")

        // Amendment A17: the composer cannot retune this session, so it shows
        // the three values the device read out of the terminal's transcript
        // where the pickers would be.
        expect(locked.isTunedByTerminal, "a terminal session is tuned where this app cannot reach")
        expect(!locked.allowsSettingsChanges, "so no control is offered")
        equal(locked.terminalSettings.map(\.id), ["modelCard", "permissionMode"],
              "and the chips stand in the order the live controls stand in (A21)")
        equal(locked.terminalSettings.map(\.text), ["Sonnet 4.5 High", "Ask before edits"],
              "model and effort on one chip, each labelled by the agent's own list")
        equal(locked.terminalSettings.map(\.field.label), ["Model", "Permissions"],
              "and named for assistive technology by what the control is called")
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

        // MARK: - Amendment A17: what the terminal chose is shown, not offered
        //
        // The hello's own copy of the session, so the reading is of what the
        // demo delivered rather than of a race with the script below.
        equal(TerminalSetting.all(for: shared, agent: model.agent(for: shared)).map(\.text),
              ["Sonnet 4.5 High", "auto"],
              "a Claude channel shows what the device read, and `auto` by its raw id")
        // Somebody types `/model` in that terminal. There is no picker here to
        // keep in step, only the chip, and it follows the `meta` in place.
        await settle { chat.session.model == "claude-opus-4-1" }
        equal(chat.terminalSettings.map(\.text), ["Opus 4.1 High", "auto"],
              "and a model changed in the terminal reaches the chip without a reload")
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
        expect(chat.terminalSettings.isEmpty,
               "and A17 draws no read-only chips where the pickers are live")
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
    equal(groups.flatMap { $0.active + $0.archive }.count, 12, "every demo session is placed")
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

    // MARK: - Amendments A25 and A26, the agents beside Claude and Codex
    //
    // `docs/DESIGN.md` § "Agents": four names, four logos, and no control at
    // all where the agent advertises no such setting.

    equal(SessionListLayout.agents(in: helloSessions).map(AgentLabel.name),
          ["Claude Code", "Codex", "Grok Build", "pi"],
          "the filter names all four agents, in label order")
    equal(SessionListLayout.agents(in: helloSessions).map(AgentLabel.initial),
          ["C", "C", "G", "P"],
          "and an agent with no logo of its own falls back to its first letter")
    for agent in ["claude", "codex", "grok", "pi"] {
        expect(AgentLogo.image(agent) != nil, "\(agent) is drawn by its own logo")
    }
    expect(AgentLogo.image("aider") == nil, "an agent nobody knows has no logo to draw")

    if let piSession = helloSessions.first(where: { $0.sessionID == DemoFixtures.piSessionID }) {
        let chat = ChatStore(session: piSession, channel: DemoGateway())
        chat.agent = model.agent(for: piSession)
        equal(chat.agent?.permissionModes.map(\.id), ["untrusted", "on-request", "never"],
              "A26: pi's permission modes are the device's own, enforced by the extension")
        equal(chat.agent?.attach, AgentAttach.extension, "which pi loads into every session")
        expect(chat.allowsSettingsChanges, "a pi session the app started keeps its live controls")
        equal(ModelCardText.words(for: piSession, agent: chat.agent), "Claude Sonnet 4.5 Medium",
              "and its model card carries the model and the thinking level")
        equal(ModelCardSizing.pairs(for: chat.agent, model: "pi").count, 8,
              "which the sizer measures as every model against every level")
    } else {
        expect(false, "the demo carries a pi session")
    }

    if let grokSession = helloSessions.first(where: { $0.sessionID == DemoFixtures.grokSessionID }) {
        let chat = ChatStore(session: grokSession, channel: DemoGateway())
        chat.agent = model.agent(for: grokSession)
        expect(chat.isReadOnly, "a Grok session mirrored from a terminal is read-only")
        expect(chat.isTunedByTerminal, "and is tuned where this app cannot reach")
        equal(chat.terminalSettings.map(\.id), ["modelCard"],
              "its update log carries no permission mode, so one chip stands where two would")
        equal(chat.terminalSettings.map(\.text), ["Grok 4.6 High"],
              "reading the model and the level the terminal chose")
        expect(!chat.canTakeover, "Grok Build advertises no takeover")
        equal(chat.statusLine, "Controlled by the terminal",
              "so the line above the composer names no way out")
        equal(chat.sendBlockReason, "Controlled by the terminal",
              "and the field says the short sentence, as it does for every agent")
        // Amendment A27: the device still says what the session offers, and the
        // app still draws no panel, because nothing here can type into it.
        await chat.loadCommands()
        chat.draft = "/hooks"
        expect(chat.commandDraft == nil, "a terminal-held session opens no command panel")
        // Amendment A28: this machine leaves `[cli] use_leader` off, which is
        // the only way a Grok session is still terminal-held, so the hint names
        // the command that would put its terminals in the leader.
        equal(chat.agent?.attach, AgentAttach.leader, "Grok Build attaches through its leader")
        expect(chat.agent?.attachReady == false, "which this machine is not configured for")
        equal(chat.attachHint, ChatStore.AttachHint.enableLeader,
              "so the line under the status says how to configure it")
    } else {
        expect(false, "the demo carries a Grok session a terminal holds")
    }

    // MARK: - Amendment A28: a Grok session shared through the leader
    //
    // The device is another client of the same process, so the turn the TUI set
    // off is stoppable here and the pickers are live — and the attachment
    // button is gone, because a Grok prompt carries no images.

    if let shared = model.connection.sessions.first(where: {
        $0.sessionID == DemoFixtures.grokSharedSessionID
    }) {
        await model.open(shared)
        await settle { model.chat?.key == shared.id }
        if let chat = model.chat {
            expect(chat.isAttached, "a Grok session on the leader is attached, not watched")
            expect(!chat.isReadOnly, "so the composer takes what is typed into it")
            equal(chat.session.origin, EventSource.terminal, "though the terminal started it")
            expect(chat.canStop, "shared_interrupt and the capability together offer Stop")
            expect(chat.allowsSettingsChanges, "shared_settings keeps the model card live")
            expect(!chat.allowsAttachments, "and shared_attachments is false, so there is no `+`")
            expect(chat.attachHint == nil, "an attached session explains nothing; it works")
            equal(ModelCardText.words(for: chat.session, agent: chat.agent), "Grok 4.6 High",
                  "and the card reads the model and the effort the leader loaded")
        } else {
            expect(false, "the shared Grok session opens")
        }
        await model.closeChat()
    } else {
        expect(false, "the demo carries a Grok session shared through the leader")
    }

    // MARK: - Amendment A27: the terminal's `/` menu, opened from the composer
    //
    // `docs/DESIGN.md` § "The composer". The list is fetched as the
    // conversation opens, so the card is there for the first `/` rather than a
    // round trip after it.

    if let parser = model.connection.sessions.first(where: { $0.sessionID == DemoFixtures.piSessionID }) {
        await model.open(parser)
        await settle { model.chat?.key == parser.id }
        if let chat = model.chat {
            await settle { !chat.commands.isEmpty }
            expect(chat.offersCommands, "pi takes commands from an app")
            equal(chat.commands.count, DemoFixtures.piCommands.count,
                  "and opening the conversation is what fetched them")

            chat.draft = "/"
            equal(chat.commandSections.map(\.title), ["Prompts", "Skills", "Extensions", "Built-in"],
                  "the card is sectioned by where each command came from")
            expect(chat.commandRows.count > SlashDraft.visibleRows,
                   "and is taller than the cap, so the demo shows it scrolling")

            chat.draft = "/rel"
            equal(chat.commandRows.map(\.name), ["release-notes"], "letters after the slash filter it")
            if let row = chat.commandRows.first { chat.take(row) }
            equal(chat.draft, "/release-notes ", "taking a row writes the name and the space after it")
            equal(chat.commandHint?.argument, "tag", "which is where the placeholder is shown")
            expect(chat.canSend, "and Send runs it")
            chat.draft = ""
        } else {
            expect(false, "the pi session opens")
        }
        await model.closeChat()
    } else {
        expect(false, "the demo carries a pi session the app drives")
    }

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

    // The demo, and the moment before a sign-in: the switch is on and the app's
    // own banners work, but there is nobody to hand a device token to, so Apple
    // is never asked for one.
    let local = FakeNotificationPlatform()
    local.authorizationValue = .authorized
    let localPush = PushController(platform: local, bundleID: "com.junbingao.remotecontrol")
    localPush.attach(api: nil, enabled: true) { _ in }
    await settle { localPush.statusText.contains("this app only") }
    equal(localPush.statusText, "On, in this app only", "with no gateway the switch still reads on")
    equal(local.registerCalls, 0, "and nothing is registered with Apple")

    // MARK: - The app's own banner when a turn ends
    //
    // `docs/DESIGN.md` § "Being told when a turn ends". Three gates, and a
    // payload that is the gateway's own, so tapping the banner opens the
    // session through the path a push already takes.

    let alerts = FakeTurnAlertPlatform()
    let notifier = TurnNotifier(platform: alerts)
    func demoSession(_ state: SessionState) -> Session {
        Session(sessionID: "s-1", deviceID: "d-1", agent: "claude", title: "Fix the flake",
                cwd: "/w", state: state, updatedAt: 1_700_000_000_000)
    }
    let ran = demoSession(.running)
    let quiet = demoSession(.idle)

    expect(notifier.announce(previous: ran, current: quiet, deviceName: "mac",
                             sceneActive: false, enabled: true, authorization: .authorized) == nil,
           "a suspended app raises nothing: the gateway's push is what reaches a locked phone")
    expect(notifier.announce(previous: ran, current: quiet, deviceName: "mac",
                             sceneActive: true, enabled: false, authorization: .authorized) == nil,
           "the Notifications switch is the one switch, and off means off")
    expect(notifier.announce(previous: ran, current: quiet, deviceName: "mac",
                             sceneActive: true, enabled: true, authorization: .notDetermined) == nil,
           "nothing is raised before the system has granted permission")
    expect(notifier.announce(previous: ran, current: quiet, deviceName: "mac",
                             sceneActive: true, enabled: true, authorization: .denied) == nil,
           "and nothing after it has been refused")
    expect(notifier.announce(previous: quiet, current: quiet, deviceName: "mac",
                             sceneActive: true, enabled: true, authorization: .authorized) == nil,
           "a session that was already quiet is not a finished turn")
    equal(alerts.posted.count, 0, "none of those reached the system")

    guard let finished = notifier.announce(previous: ran, current: quiet, deviceName: "mac",
                                           sceneActive: true, enabled: true,
                                           authorization: .authorized) else {
        expect(false, "an authorized app in the foreground announces a finished turn")
        return (passed, failures)
    }
    equal(alerts.posted.count, 1, "one request, once")
    equal(finished.title, "mac", "the banner is headed by the device's name")
    equal(finished.body, "Turn finished", "and says the status word, and nothing about the turn")
    equal(finished.threadIdentifier, quiet.id, "banners stack under the session they belong to")
    equal(finished.route.title, "mac: Turn finished", "the payload carries the push's own sentence")

    // The payload shape of `build_payload()` in `gateway/rc_gateway/push.py`:
    // it has to parse back through the path a tap takes.
    if let data = try? JSONSerialization.data(withJSONObject: finished.userInfo),
       let parsed = try? PushRoute(userInfo: data) {
        equal(parsed, finished.route, "the userInfo parses back into the route it was built from")
        equal(parsed.deepLink?.absoluteString, "remotecontrol://session?device=d-1&id=s-1",
              "which is the deep link that opens the session")
    } else {
        expect(false, "the userInfo is the gateway's payload and parses as one")
    }

    let asking = demoSession(.needsInput)
    equal(notifier.announce(previous: ran, current: asking, deviceName: "mac",
                            sceneActive: true, enabled: true, authorization: .provisional)?.body,
          "Waiting for your answer", "a provisional grant still delivers, with the same word")

    // No double banners: the gateway's push for a transition this app has
    // already announced is not drawn a second time over the open app.
    expect(ForegroundBanner.shows(remote: false, suppressesRemote: true),
           "the app's own banner is always shown")
    expect(ForegroundBanner.shows(remote: true, suppressesRemote: false),
           "and so is a push, while the app is not reading the stream")
    expect(!ForegroundBanner.shows(remote: true, suppressesRemote: true),
           "but not while it is: that one has already been announced")
    SystemNotifications.shared.suppressesRemoteBanners = true
    expect(!SystemNotifications.shared.presents(remote: true),
           "the flag the model sets is what the delegate reads")
    expect(SystemNotifications.shared.presents(remote: false), "local alerts pass it")
    SystemNotifications.shared.suppressesRemoteBanners = false

    // The model owns the flag, and sets it from the two facts it is made of.
    let foreground = AppModel(arguments: [])
    foreground.setSceneActive(true)
    expect(!SystemNotifications.shared.suppressesRemoteBanners,
           "an app with no connection suppresses nothing")
    await foreground.enterDemo()
    await settle { foreground.connection.hasSnapshot }
    foreground.setSceneActive(true)
    expect(SystemNotifications.shared.suppressesRemoteBanners,
           "open and connected is what drops the second banner")
    foreground.setSceneActive(false)
    expect(!SystemNotifications.shared.suppressesRemoteBanners,
           "and leaving the foreground hands the push back its banner")

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

    // MARK: - Done is the one way out, and it keeps the draft

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

    let appending = SegmentedSpeechInput()
    let appendSession = InlineVoiceDraftSession(platform: appending, isPreview: true)
    appendSession.start(draft: "the draft I already had", target: composerTarget)
    await settle { appendSession.voice.phase == .listening }
    appending.hear("and some dictation")
    await settle { appendSession.voice.transcript == "and some dictation" }
    equal(appendSession.updateDraft(currentDraft: "the draft I already had",
                                    currentTarget: composerTarget),
          "the draft I already had\nand some dictation",
          "dictation is appended after whatever the user already had")
    appendSession.reset()
    equal(appendSession.voice.phase, .idle, "and leaving the composer leaves dictation idle")

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

    // MARK: - Amendment A22: a device is updated from the app

    equal(model.connection.config.servedBuild, DemoFixtures.servedBuild,
          "the app reads the build the gateway serves from /api/config")
    if let laptop = model.connection.device(DemoFixtures.laptopDeviceID) {
        equal(DeviceUpdate.notice(for: laptop, servedBuild: model.connection.config.servedBuild),
              .available, "a device on an older build says so on its row")
        await model.updateDevice(laptop)
        await settle { model.connection.device(DemoFixtures.laptopDeviceID)?.updateState == .updating }
        equal(model.connection.device(DemoFixtures.laptopDeviceID)?.updateState, .updating,
              "asking for the update puts the row in its updating state")
        expect(model.deviceUpdateError(DemoFixtures.laptopDeviceID) == nil,
               "and an accepted update is not an error")
        await settle(timeout: 15) {
            model.connection.device(DemoFixtures.laptopDeviceID)?.updateState == .idle
        }
        equal(model.connection.device(DemoFixtures.laptopDeviceID)?.clientBuild,
              DemoFixtures.servedBuild, "and the device comes back on the build it was sent to")
    } else {
        expect(false, "the demo lists a device on an older build")
    }
    if let studio = model.connection.device(DemoFixtures.macDeviceID) {
        // A device already on the served build is refused by the device, and a
        // refusal is the app's own news: no update ever started.
        await model.updateDevice(studio)
        expect(model.deviceUpdateError(DemoFixtures.macDeviceID) != nil,
               "a refused update is held against the row that asked for it")
        equal(DeviceUpdate.block(for: studio, servedBuild: model.connection.config.servedBuild),
              .current, "and the action says why it could not act")
    }

    // MARK: - The interface language

    let languageDefaults = UserDefaults(suiteName: "rc-ui-verify-\(UUID().uuidString)")!
    let languageSettings = SettingsStore(defaults: languageDefaults)
    equal(languageSettings.language, .en, "English is the default whatever the phone is set to")
    equal(InterfaceLanguage.allCases.map(\.title), ["English", "中文"],
          "and each language names itself in its own script")
    languageSettings.language = .zhHans
    equal(SettingsStore(defaults: languageDefaults).language, .zhHans,
          "the choice outlives the launch that made it")
    equal(AppModel(settings: SettingsStore(defaults: languageDefaults),
                   arguments: ["--reset-state"]).settings.language, .en,
          "a reset returns the app to English")
    equal(AppModel(settings: SettingsStore(defaults: languageDefaults),
                   arguments: ["--reset-state", "--language=zh-Hans"]).settings.language, .zhHans,
          "and a test can launch straight into the language it is about to read")
    equal(InterfaceLanguage.zhHans.locale.identifier, "zh-Hans",
          "the locale the root hands SwiftUI is the catalogue's own name")

    // MARK: - Launch shows the app, never the sign-in form, when there is an account

    let storedSettings = SettingsStore(defaults: UserDefaults(suiteName: "rc-ui-verify-\(UUID().uuidString)")!)
    storedSettings.remember(origin: "https://rc.example.com", username: "me")
    let resuming = AppModel(settings: storedSettings, arguments: [])
    expect(resuming.isResuming, "a stored gateway means the app has something to come back to")
    expect(!resuming.isSignedIn, "and nothing is signed in until the keychain answers")

    let fresh = AppModel(settings: SettingsStore(defaults: UserDefaults(suiteName: "rc-ui-verify-\(UUID().uuidString)")!),
                         arguments: [])
    expect(!fresh.isResuming, "a fresh install resumes nothing, so the form is the first screen")
    await fresh.restoreOrPrompt()
    expect(!fresh.isSignedIn, "and nothing signs it in")

    // The keychain is what unlocks the screens; the gateway is asked behind them.
    let held = StoredAccountGateway(answer: .success(SessionInfoResponse(user: UserIdentity(username: "me"), exp: 0)))
    let launching = ConnectionStore(makeAPI: { _ in held }, makeChannel: { _ in held })
    let adopted = await launching.restore(origin: "https://rc.example.com", username: "me")
    expect(adopted, "a keychain token is enough to adopt the account")
    expect(launching.isSignedIn, "the app is signed in before the gateway has answered")
    equal(launching.phase.canReachGateway, true, "and its screens draw in a connecting state")
    await held.answerAccountCheck()
    await settle { launching.username == "me" }
    equal(launching.username, "me", "the round trip only confirms what was already on screen")

    // A refused token is the one answer that brings the form back, and it says so.
    let refused = StoredAccountGateway(answer: .failure(.unauthorized))
    let expiring = ConnectionStore(makeAPI: { _ in refused }, makeChannel: { _ in refused })
    _ = await expiring.restore(origin: "https://rc.example.com", username: "me")
    expect(expiring.isSignedIn, "the shell is drawn while the stored token is being checked")
    await refused.answerAccountCheck()
    await settle { !expiring.isSignedIn }
    expect(!expiring.isSignedIn, "a gateway that refuses the token sends the user back to the form")
    equal(expiring.errorMessage, "Your session expired. Sign in again.", "which says why it asked")

    // MARK: - Accounts (A24)
    //
    // `docs/DESIGN.md` § "Accounts". The offline gateway is reached through the
    // sign-in form rather than around it, so every answer the form has to tell
    // apart — an unknown account, a disabled one, a member, an admin — is
    // driven here without a gateway to reach.

    let closed = AppModel(connection: .offlineDemo(),
                          settings: SettingsStore(defaults: freshDefaults()),
                          arguments: [])
    expect(!(await closed.connection.registrationOpen(origin: "https://rc.example.com")),
           "a gateway that is not taking accounts offers no way to create one")

    await closed.signIn(origin: "https://rc.example.com",
                        username: DemoFixtures.disabledUsername, password: "correct horse")
    expect(!closed.isSignedIn, "a disabled account cannot sign in")
    equal(closed.connection.errorMessage, "This account is disabled.", "and is told why")

    await closed.signIn(origin: "https://rc.example.com",
                        username: "nobody", password: "correct horse")
    equal(closed.connection.errorMessage, "Wrong username or password.",
          "while an unknown account is not told that it is unknown")

    await closed.signIn(origin: "https://rc.example.com",
                        username: DemoFixtures.memberUsername, password: "correct horse")
    expect(closed.isSignedIn, "a member signs in")
    equal(closed.connection.username, DemoFixtures.memberUsername, "as itself")
    expect(!closed.connection.isAdmin, "a member is not an admin")
    expect(closed.connection.usersStore() == nil, "so the accounts screen is not theirs to open")
    equal(closed.settings.lastUsername, DemoFixtures.memberUsername,
          "and the form remembers the username for next time")
    await closed.signOut()

    let open = AppModel(connection: .offlineDemo(registrationOpen: true),
                        settings: SettingsStore(defaults: freshDefaults()),
                        arguments: [])
    expect(await open.connection.registrationOpen(origin: "https://rc.example.com"),
           "a gateway taking accounts says so, which is what draws Create an account")
    await open.register(origin: "https://rc.example.com", username: "Carol",
                        password: "correct horse battery staple")
    expect(open.isSignedIn, "creating an account signs it in")
    equal(open.connection.username, "carol", "under the lower-cased name the gateway keeps")
    equal(open.connection.user.role, .member, "as a member")
    await open.register(origin: "https://rc.example.com", username: "carol",
                        password: "correct horse battery staple")
    equal(open.connection.errorMessage, "That username is taken.", "and a second time is refused by name")
    await open.register(origin: "https://rc.example.com", username: "no",
                        password: "correct horse battery staple")
    equal(open.connection.errorMessage, AccountError.rules,
          "while a name outside the rules is the one time the rule is stated")
    await open.signOut()

    // The admin's screen, driven through the store the screen reads.
    let operating = AppModel(connection: .offlineDemo(),
                             settings: SettingsStore(defaults: freshDefaults()),
                             arguments: [])
    await operating.signIn(origin: "https://rc.example.com",
                           username: DemoFixtures.adminUsername, password: "correct horse")
    expect(operating.connection.isAdmin, "the operator signs in as an admin")
    if let users = operating.connection.usersStore() {
        await users.load()
        equal(users.users.count, 3, "the accounts screen lists every account")
        expect(!users.registrationOpen, "with the registration switch where the gateway has it")
        expect(users.users.first?.isOperator == true, "the operator is the first row")
        try? await users.setRegistration(open: true)
        expect(users.registrationOpen, "and the switch is what the gateway answered")

        try? await users.create(username: "dave", password: "correct horse battery staple", role: .member)
        equal(users.users.count, 4, "adding an account adds a row")
        equal(users.users.last?.role, .member, "as a member unless an admin was chosen")

        try? await users.setState(.disabled, of: DemoFixtures.memberUsername)
        equal(users.users.first { $0.username == DemoFixtures.memberUsername }?.state, .disabled,
              "disabling an account changes its row and nothing else")
        try? await users.setState(.active, of: DemoFixtures.memberUsername)
        equal(users.users.first { $0.username == DemoFixtures.memberUsername }?.state, .active,
              "and enabling it puts it back")

        try? await users.delete("dave")
        equal(users.users.count, 3, "deleting an account takes its row with it")

        // The operator's row has no actions on the screen; the gateway refuses
        // them as well, so a race cannot do what the screen would not offer.
        var refused: String?
        do { try await users.setState(.disabled, of: DemoFixtures.adminUsername) } catch {
            refused = AccountError.manage(error)
        }
        equal(refused, "This account cannot be changed.",
              "the operator cannot be disabled even by asking directly")
    } else {
        expect(false, "an admin has an accounts screen")
    }

    // The app's own settings belong to the person, not to the phone.
    let shared = freshDefaults()
    let mine = SettingsStore(defaults: shared)
    mine.remember(origin: "https://rc.example.com", username: "alice")
    mine.language = .zhHans
    mine.timelineDetail = .detailed
    mine.notificationsEnabled = true
    let yours = SettingsStore(defaults: shared)
    yours.remember(origin: "https://rc.example.com", username: "bob")
    equal(yours.language, .en, "signing in as someone else does not inherit their language")
    equal(yours.timelineDetail, .simple, "nor their reading level")
    expect(!yours.notificationsEnabled, "nor their notification choice")
    yours.remember(origin: "https://rc.example.com", username: "alice")
    equal(yours.language, .zhHans, "and coming back finds their own choices again")
    equal(SettingsStore(defaults: shared).lastUsername, "alice",
          "while the gateway and the account used there prefill the form on the next launch")
    yours.remember(origin: "https://other.example.com", username: "bob")
    equal(yours.username(for: "https://rc.example.com"), "alice",
          "and each gateway keeps the username that signed in on it")
    let cleared = SettingsStore(defaults: shared)
    cleared.reset()
    cleared.remember(origin: "https://rc.example.com", username: "alice")
    equal(cleared.language, .en, "a reset forgets every account's preferences, not only the last one's")

    // MARK: - A29, polishing what was dictated

    // The demo gateway has a polish model, so the Voice group can offer the
    // switch and the composer can act on it.
    expect(model.connection.polish.enabled, "the demo gateway can polish a dictation")
    expect(!model.settings.polishEnabled, "and the setting is the person's own, off until asked for")
    let polishModels = try? await model.connection.api?.polishModels()
    equal(polishModels?.models.count, 2, "the demo serves two models to choose between")
    equal(PolishStrength.allCases.map(\.title), ["Moderate", "Strong"],
          "with two strengths under them, gentler first")

    if let shared = model.connection.sessions.first(where: {
        $0.sessionID == DemoFixtures.sharedSessionID
    }) {
        await model.open(shared)
        await settle { model.chat?.timeline.entries.isEmpty == false }
        if let chat = model.chat {
            let span = DictationSpan(base: "", dictated: "um the the dot should stop blinking")
            chat.draft = span.dictatedDraft
            chat.polishService = { [api = model.connection.api] request in
                guard let api else { throw TransportError.notConnected }
                return try await api.polish(request).text
            }
            chat.polish(span: span, model: "gpt-4.1-mini", strength: .moderate, language: "en")
            equal(chat.statusLine, "Polishing…", "the status line says what is happening to the draft")
            await settle(timeout: 5) { chat.polishPhase != .polishing }
            equal(chat.draft, "The dot should stop blinking",
                  "the demo's model hands back the same request said cleanly")
            chat.undoPolish()
            equal(chat.draft, span.dictatedDraft, "and Undo puts the dictated words back")

            // A30: the demo's attached Claude session carries a message its CLI
            // filed for another agent, which is not the person's own.
            let fromAgent = chat.timeline.entries.filter {
                if case .userMessage(let payload) = $0.body { return payload.source == .agent }
                return false
            }
            equal(fromAgent.count, 1, "the transcript holds one message another agent filed")
            equal(chat.timeline.entries.filter {
                if case .turnStarted(let payload) = $0.body { return payload.trigger == .agent }
                return false
            }.count, 1, "and the turn it started says who started it")
        }
        await model.closeChat()
    } else {
        expect(false, "the demo carries the attached Claude session")
    }

    // MARK: - Sign out

    await model.signOut()
    equal(model.connection.phase, .signedOut, "signing out returns to the login screen")
    expect(model.chat == nil, "signing out closes the open conversation")

    // MARK: - A31, an app older than its gateway

    let outdated = AppModel(arguments: ["--demo", "--demo-update-required"])
    await outdated.restoreOrPrompt()
    await settle { outdated.connection.updateRequired != nil }
    if let requirement = outdated.connection.updateRequired {
        equal(requirement.current, AppVersion(AppBuild.version), "the screen names this build")
        equal(requirement.minimum, AppVersion(DemoFixtures.laterAppVersion),
              "and the one the gateway asks for")
        expect(requirement.updateURL != nil, "with somewhere to get it")
    } else {
        expect(false, "a gateway that wants a newer build blocks this one")
    }
    await outdated.signOut()
    equal(outdated.connection.updateRequired, nil,
          "and signing out is the way to a gateway this build can talk to")

    return (passed, failures)
}

/// A `UserDefaults` suite nothing else has written to, so a check reads what it
/// put there and not what an earlier run left behind.
@MainActor
func freshDefaults() -> UserDefaults {
    UserDefaults(suiteName: "rc-ui-verify-\(UUID().uuidString)")!
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

/// A gateway that holds its `/api/session` answer until it is released, so the
/// launch moment — the keychain has answered, the gateway has not — can be
/// looked at rather than raced. Nothing else on it is exercised.
actor StoredAccountGateway: GatewayAPI, GatewayChannel {
    nonisolated let endpoint = GatewayEndpoint.placeholder
    nonisolated let events: AsyncStream<GatewayEvent>

    private let continuation: AsyncStream<GatewayEvent>.Continuation
    private let answer: Result<SessionInfoResponse, TransportError>
    private var waiting: CheckedContinuation<Void, Never>?
    private var released = false

    init(answer: Result<SessionInfoResponse, TransportError>) {
        self.answer = answer
        let stream = AsyncStream<GatewayEvent>.makeStream()
        events = stream.stream
        continuation = stream.continuation
    }

    func session() async throws -> SessionInfoResponse {
        if !released { await withCheckedContinuation { waiting = $0 } }
        return try answer.get()
    }

    /// Let the held account check finish.
    func answerAccountCheck() {
        released = true
        waiting?.resume()
        waiting = nil
    }

    func connect() async { continuation.yield(.state(.connecting)) }
    func disconnect() async { continuation.yield(.state(.disconnected)) }
    @discardableResult
    func request(_ request: GatewayRequest) async throws -> JSONValue { .object([:]) }

    func health() async throws -> HealthResponse { throw TransportError.notConnected }
    func login(username: String, password: String) async throws -> LoginResponse {
        throw TransportError.notConnected
    }
    func register(username: String, password: String) async throws -> LoginResponse {
        throw TransportError.notConnected
    }
    func changePassword(current: String, new: String) async throws {
        throw TransportError.notConnected
    }
    func polishModels() async throws -> PolishModelsResponse { throw TransportError.notConnected }
    func polish(_ request: PolishRequest) async throws -> PolishResponse {
        throw TransportError.notConnected
    }
    func users() async throws -> UserListResponse { throw TransportError.notConnected }
    func createUser(username: String, password: String, role: UserRole) async throws -> UserRecord {
        throw TransportError.notConnected
    }
    func patchUser(_ username: String, state: UserState?, role: UserRole?,
                   password: String?) async throws -> UserRecord {
        throw TransportError.notConnected
    }
    func deleteUser(_ username: String) async throws { throw TransportError.notConnected }
    func setRegistration(open: Bool) async throws -> Bool { throw TransportError.notConnected }
    func logout() async throws {}
    func config() async throws -> GatewayConfig { throw TransportError.notConnected }
    func devices() async throws -> [Device] { throw TransportError.notConnected }
    func renameDevice(_ deviceID: String, name: String) async throws -> Device {
        throw TransportError.notConnected
    }
    func revokeDevice(_ deviceID: String) async throws { throw TransportError.notConnected }
    func beginPairing() async throws -> PairingGrant { throw TransportError.notConnected }
    func cancelPairing(code: String) async throws {}
    func claimPairingRequest(token: String) async throws -> PairingClaim {
        throw TransportError.notConnected
    }
    func sessions(deviceID: String?, archived: Bool?) async throws -> [Session] { [] }
    func registerPush(_ registration: APNSRegistration) async throws {}
    func unregisterPush(token: String) async throws {}
    func restoreToken(username: String) async -> Bool { true }
    func bearerToken() async -> String? { "stored" }
    func forgetToken(username: String) async {}
}

/// The posting side of a local banner, with no UserNotifications behind it.
@MainActor
final class FakeTurnAlertPlatform: TurnAlertPlatform {
    private(set) var posted: [TurnAlert] = []
    func post(_ alert: TurnAlert) { posted.append(alert) }
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
