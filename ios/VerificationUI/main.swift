import Foundation
import RCCore
import RCUI
import SwiftUI

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
    /// The same, for a condition that has to ask an actor.
    func settleAsync(timeout: TimeInterval = 3, _ condition: () async -> Bool) async {
        let deadline = Date().addingTimeInterval(timeout)
        while await !condition(), Date() < deadline { try? await Task.sleep(for: .milliseconds(20)) }
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
        equal(shared.statusLabel, "terminal · attached", "the chat header names the terminal")
        equal(shared.originLabel, "Terminal", "and its row says only where it came from")
        equal(shared.dotTone(online: true), DotTone.live,
              "with the amber of a session that is alive and quiet, not the grey of an exited one")

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
    equal(tone(DemoFixtures.liveSessionID), .working, "a running turn is a steady green")
    equal(tone(DemoFixtures.approvalSessionID), .waiting, "a request for approval is a pulsing amber")
    equal(tone(DemoFixtures.sharedSessionID), .live, "an attached session that is quiet is a steady amber")
    equal(tone(DemoFixtures.erroredSessionID), .failed, "an agent that stopped on an error is red")
    equal(tone(DemoFixtures.doneSessionID), .off,
          "a session nothing owns, on a machine that is offline, is grey")
    equal(tone(DemoFixtures.attachHintSessionID), .live,
          "a terminal session on a reachable machine is alive, whatever the app may type into it")

    // What each tone looks like, which is what the reader actually decides on:
    // green means working — leave it; amber means there is something for you,
    // and only the one that still needs an answer moves (owner's ruling,
    // 2026-09-17; `docs/DESIGN.md` § "The status dot").
    equal(Theme.dotColor(.working), Theme.running, "a running turn keeps the green")
    equal(Theme.dotColor(.waiting), Theme.attention, "a session blocked on the user is amber")
    equal(Theme.dotColor(.live), Theme.attention,
          "and so is a finished turn nobody has looked at yet")
    equal(Theme.dotColor(.failed), Theme.danger, "an error is red")
    equal(Theme.dotColor(.off), Theme.resting, "and a session nothing owns is grey")
    expect(StatusDot.pulses(tone: .waiting, reduceMotion: false),
           "the dot that needs an answer is the one that moves")
    for quiet in [DotTone.working, .live, .off, .failed] {
        expect(!StatusDot.pulses(tone: quiet, reduceMotion: false),
               "\(quiet) asks for nothing and stands still")
    }
    expect(!StatusDot.pulses(tone: .waiting, reduceMotion: true),
           "Reduce Motion holds even that one still, where the amber alone says it")

    // MARK: - The session row says where it came from
    //
    // `docs/DESIGN.md` § "The session row says where it came from": the word
    // beside the dot is the origin and never the state, whatever the session is
    // doing, so the row and the dot cannot contradict each other.
    let everyState: [SessionState] = [.starting, .running, .needsApproval, .needsInput,
                                      .idle, .readonly, .stopped, .error]
    func sample(_ origin: EventSource, _ state: SessionState) -> Session {
        Session(sessionID: "s", deviceID: "d", agent: "claude", title: "Work", cwd: "/src",
                state: state, origin: origin, control: .remote)
    }
    expect(everyState.allSatisfy { sample(.terminal, $0).originLabel == "Terminal" },
           "a session a terminal started reads Terminal in every state it can be in")
    expect(everyState.allSatisfy { sample(.remote, $0).originLabel == "Remote Control" },
           "and one started from a phone or a browser reads Remote Control in every state")

    // The demo list, which holds both origins and every tone at once.
    let originWords = Set(helloSessions.map(\.originLabel))
    equal(originWords, ["Terminal", "Remote Control"], "the demo list carries both origins")
    let stateWords = Set(everyState.map(\.label)).union(["terminal · attached"])
    expect(originWords.isDisjoint(with: stateWords), "and no row says what the session is doing")

    // MARK: - A legend, once, and quiet

    equal(DotLegend.entries.map(\.text), ["Working", "For you", "Not running", "Error"],
          "the key to the colours reads in the order the dots are met")
    equal(DotLegend.entries.map(\.tone), [.working, .live, .off, .failed],
          "four entries, not five: one amber covers a question waiting and a turn finished")
    expect(DotLegend.entries.allSatisfy { !StatusDot.pulses(tone: $0.tone, reduceMotion: false) },
           "and nothing in a key to the colours moves")

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
        _ = await sending.value
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

    // MARK: - A message from another agent, at both levels, across a reopen (A34)

    if let shared = model.connection.sessions.first(where: { $0.sessionID == DemoFixtures.sharedSessionID }) {
        let fromAgent: (TimelineEntry) -> Bool = { $0.userMessage?.source == .agent }
        let originalDetail = model.settings.timelineDetail
        model.settings.timelineDetail = .simple
        await model.open(shared)
        await settle(timeout: 10) { model.chat?.timeline.entries.contains(where: fromAgent) ?? false }
        expect(model.chat?.timeline.entries.contains(where: fromAgent) ?? false,
               "the shared demo session carries a message another agent filed")
        expect(!(model.chat?.rows.contains(where: fromAgent) ?? true),
               "Simple does not draw a message from another agent")
        model.settings.timelineDetail = .detailed
        expect(model.chat?.rows.contains(where: fromAgent) ?? false,
               "Detailed draws it as soon as the level changes")
        await model.closeChat(key: shared.id)
        equal(model.chat == nil, true, "closing by key closes the conversation it names")
        await model.open(shared)
        await settle(timeout: 10) { model.chat?.rows.contains(where: fromAgent) ?? false }
        expect(model.chat?.rows.contains(where: fromAgent) ?? false,
               "and still draws it when the conversation is opened again at Detailed")
        await model.closeChat(key: shared.id)
        model.settings.timelineDetail = originalDetail
    } else {
        expect(false, "the demo has the shared session")
    }

    // MARK: - Session list presentation

    let sessions = SessionStore(defaults: UserDefaults(suiteName: "rc-ui-verify-\(UUID().uuidString)")!)
    let groups = sessions.groups(helloSessions, devices: model.connection.devices)
    equal(groups.count, 3, "the list is grouped by device")
    equal(groups.flatMap { $0.active + $0.archive }.count, 13, "every demo session is placed")
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

    // MARK: - Amendment A39: Close, then the Archive
    //
    // `docs/DESIGN.md` § "Close, then the Archive": one action on one kind of
    // row, and a question only where an unfinished turn is lost.
    for control in [SessionControl.remote, .terminal, .shared, .none] {
        for archived in [false, true] {
            let row = Session(sessionID: "s", deviceID: DemoFixtures.macDeviceID, agent: "claude",
                              title: "Work", cwd: "/src", state: .running, control: control,
                              archived: archived)
            equal(SessionListLayout.offersClose(row), control == .remote && !archived,
                  "\(control) and archived \(archived) decide whether the row offers Close")
        }
    }
    expect(SessionListLayout.offersClose(helloSessions.first { $0.sessionID == DemoFixtures.liveSessionID }!),
           "the demo's running remote session is a row that closes")
    expect(!SessionListLayout.offersClose(helloSessions.first { $0.sessionID == DemoFixtures.sharedSessionID }!),
           "a session a terminal is in offers nothing: the terminal owns it")
    expect(!SessionListLayout.offersClose(helloSessions.first { $0.sessionID == DemoFixtures.revivedSessionID }!),
           "and neither does a row already in the Archive")
    expect(SessionClose.asksFirst(helloSessions.first { $0.sessionID == DemoFixtures.liveSessionID }!,
                                  online: true),
           "a working agent is asked about before it is closed")
    expect(!SessionClose.asksFirst(helloSessions.first { $0.sessionID == DemoFixtures.piSessionID }!,
                                   online: true),
           "an idle one closes on the tap, because there is nothing to lose")

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

    // MARK: - What the microphone's loudness maps to
    //
    // `docs/DESIGN.md` § "Voice": the glow is meant to be seen, not found, and
    // it can only swell with the voice if a voice spans the scale. A quiet room
    // is the floor and conversational speech the ceiling.

    equal(InputLevel.from(rms: 0), 0.0, "silence is the bottom of the scale")
    equal(InputLevel.from(rms: pow(10, InputLevel.floorDB / 20)), 0.0, "and so is a quiet room")
    equal(InputLevel.from(rms: pow(10, InputLevel.ceilingDB / 20)), 1.0,
          "conversational speech reaches the top")
    expect(InputLevel.from(rms: pow(10, -31.0 / 20)) > 0.45
           && InputLevel.from(rms: pow(10, -31.0 / 20)) < 0.55,
           "and the middle of a voice's range sits in the middle of the glow's")
    expect(InputLevel.from(rms: 1) <= 1, "nothing louder than the ceiling overshoots it")

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

    // `docs/DESIGN.md` § "The composer" → **While dictation runs, the field
    // follows the words**: the UI test that watches the field follow needs a
    // dictation longer than the eight lines it grows to, arriving the way a
    // long one really does rather than all at once.
    let longBackend = SpeechBackend.make(settings: settings, connection: model.connection,
                                         arguments: ["--voice-preview", "--voice-transcript=long"])
    let longDictation = VoiceInputController(platform: longBackend.platform)
    longDictation.start()
    await settle { !longDictation.transcript.isEmpty }
    let firstPartial = longDictation.transcript
    expect(!firstPartial.isEmpty, "a long dictation starts on its first partial")
    expect(firstPartial.count < ScriptedSpeechInput.longTranscript.count,
           "which is a part of what was said rather than the whole of it")
    await settle { longDictation.transcript.count > firstPartial.count }
    expect(longDictation.transcript.count > firstPartial.count,
           "and the rest of it arrives a partial at a time")
    expect(ScriptedSpeechInput.longTranscript.count > 600,
           "what is finally said is far past the eight lines the field grows to")
    longDictation.cancel()
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

    // MARK: - The device row says less (`docs/DESIGN.md` § "The device row")
    //
    // Both lines are written once, in `DeviceLine`, so what the row says and
    // what only the page says can be read here without drawing a view.

    equal(DeviceLine.platformName(.macos), "macOS", "the row says the platform as a word")
    equal(DeviceLine.platformName(.linux), "Linux", "for each of the two the client runs on")
    equal(DeviceLine.platformName(DevicePlatform(rawValue: "sunos")), "sunos",
          "and prints one this build never heard of as it arrived")

    for device in model.connection.devices {
        let row = DeviceLine.status(device)
        equal(row, "\(device.online ? "online" : "offline") · \(DeviceLine.platformName(device.platform))",
              "\(device.name)'s row says its state and its platform, and nothing else")
        expect(!row.contains(device.hostname), "the row never repeats \(device.name)'s hostname")
        expect(!row.contains(device.arch), "nor names the chip it is built on")
        expect(!row.contains(device.platform.rawValue), "nor shows the raw platform id")
        for info in device.availableAgents {
            expect(!row.contains(info.displayName),
                   "nor writes \(info.displayName) in ink beside its logo")
        }
        let page = DeviceLine.facts(device)
        expect(page.contains(device.hostname), "\(device.name)'s page keeps its hostname")
        expect(page.contains(device.arch), "and the chip it is built on")
    }

    // The glyph before the row is a minimal outline laptop on lucide's 24-unit
    // grid, the drawing the web app uses, so both apps show one mark: the
    // screen is a rounded rectangle, the base one line under it, the stroke
    // 1.5 units with round caps and joins, and the whole scales with its box.
    let laptop = LaptopShape().path(in: CGRect(x: 0, y: 0, width: 24, height: 24)).boundingRect
    equal(laptop, CGRect(x: 2, y: 4, width: 20, height: 16),
          "the laptop spans the grid from the base line's ends to the screen's top")
    let screenBottom = LaptopShape.screen.maxY
    expect(LaptopShape.baseY > screenBottom, "the base is a line under the screen, not touching it")
    expect(LaptopShape.screenCorner > 0, "and the screen's corners are rounded")
    let doubled = LaptopShape().path(in: CGRect(x: 0, y: 0, width: 48, height: 48)).boundingRect
    equal(doubled, CGRect(x: 4, y: 8, width: 40, height: 32), "twice the box draws twice the laptop")
    equal(OutlineGlyph.strokeWidth(for: 24), 1.5, "at grid size the stroke is lucide's 1.5 units")
    equal(OutlineGlyph.strokeWidth(for: 20), 1.25, "and it thins with the box")
    equal(OutlineGlyph.stroke(for: 24).lineCap, .round, "with round caps")
    equal(OutlineGlyph.stroke(for: 24).lineJoin, .round, "and round joins")

    // The folder before a session's working directory is lucide's `Folder` on
    // the same grid: a body from the left edge to the right, its top edge
    // stepping up to a tab on the left, every corner rounded, no fill.
    let folder = FolderShape().path(in: CGRect(x: 0, y: 0, width: 24, height: 24)).boundingRect
    equal(folder, CGRect(x: 2, y: 3, width: 20, height: 17),
          "the folder spans the grid from its left edge to its right and from the tab's top to its bottom")
    let tab = FolderShape.corners
    expect(tab[4].y < tab[3].y && tab[4].x < tab[3].x,
           "the tab rises up and to the left from the body's top edge")
    equal(tab[2].y, tab[3].y, "the body's top edge is level to the tab")
    equal(tab[4].y, tab[5].y, "and the tab's top is level to the left edge")
    expect(FolderShape.cornerRadius > 0, "and its corners are rounded")
    let folderDoubled = FolderShape().path(in: CGRect(x: 0, y: 0, width: 48, height: 48)).boundingRect
    equal(folderDoubled, CGRect(x: 4, y: 6, width: 40, height: 34), "twice the box draws twice the folder")

    // The Devices screen's filter offers the platforms the list holds, in the
    // order it first shows them, and narrows the list to one of them (owner's
    // ruling, 2026-09-18). The demo has two Macs and one Linux runner.
    let demoDevices = model.connection.devices
    equal(DeviceFilter.platforms(in: demoDevices), [.macos, .linux],
          "the filter offers macOS then Linux, once each, for the demo's three machines")
    equal(DeviceFilter.apply(demoDevices, platform: .linux).map(\.deviceID), [DemoFixtures.ciDeviceID],
          "narrowing to Linux leaves the runner alone")
    equal(DeviceFilter.apply(demoDevices, platform: nil).count, 3, "and All keeps the three")
    equal(L10n.string("No %@ devices", DeviceLine.platformName(.linux)), "No Linux devices",
          "an emptied list says which platform it was narrowed to")

    // The agents on the row are logos alone, so their names reach a reader who
    // cannot see them only as the labels the row hands each logo.
    if let studio = model.connection.device(DemoFixtures.macDeviceID) {
        equal(studio.availableAgents.map(\.displayName),
              ["Claude Code", "Codex", "Grok Build", "pi"],
              "every logo on the row is labelled with the agent it stands for")
    }

    // MARK: - Amendment A36: a device keeps itself current

    equal(model.connection.config.servedBuild, DemoFixtures.servedBuild,
          "the app reads the build the gateway serves from /api/config")

    // `docs/DESIGN.md` § "An update names its version": the confirmation says
    // what a retry would install, and has words for a gateway that does not say.
    equal(model.connection.config.servedVersion, DemoFixtures.servedClientVersion,
          "and the version that build is")
    // The only two things an app says about a client (A36). There is no wording
    // for a device being kept current, because its row draws no line at all.
    equal(DeviceUpdateText.line(.updating), "Updating…", "the line follows an update while it runs")
    expect(DeviceUpdateText.line(.failed("no wheel")).contains("no wheel"),
           "and says why one failed")
    equal(DeviceUpdateText.confirmation(name: "macbook-air",
                                        servedVersion: model.connection.config.servedVersion),
          """
          Update macbook-air to \(DemoFixtures.servedClientVersion)? \
          Its service restarts; sessions it drives are stopped.
          """,
          "the confirmation names the machine and the version")
    equal(DeviceUpdateText.confirmation(name: "macbook-air", servedVersion: nil),
          "Update macbook-air to the gateway's client? Its service restarts; sessions it drives are stopped.",
          "and falls back to the gateway's client where there is no version")
    equal(DeviceUpdateText.reason(.offline), "This device is offline.",
          "a retry that cannot act says why")
    equal(DeviceUpdateText.reason(.noServedBuild), "This gateway is not serving a client build.",
          "and so does one with no wheel to install")

    // No row in the demo's list states a client version or a build hash: the
    // machines are the gateway's to keep current, so the numbers are nobody's
    // to watch.
    for device in model.connection.devices {
        let line = DeviceUpdate.notice(for: device).map(DeviceUpdateText.line) ?? ""
        expect(!line.contains(device.clientVersion),
               "\(device.name)'s row never states the client version it runs")
        expect(!line.contains(String(DemoFixtures.servedBuild.prefix(8))),
               "nor any part of a build hash")
    }
    if let studio = model.connection.device(DemoFixtures.macDeviceID) {
        expect(DeviceUpdate.notice(for: studio) == nil,
               "the machine the gateway is keeping current says nothing at all")
        expect(!DeviceUpdate.canRetry(studio), "and has nothing to retry")
    }
    if let runner = model.connection.device(DemoFixtures.ciDeviceID) {
        expect(runner.clientBuild != DemoFixtures.servedBuild, "the runner is on an older build")
        expect(DeviceUpdate.notice(for: runner) == nil,
               "and still says nothing: no version, and never an update available")
        equal(DeviceUpdate.block(for: runner, servedBuild: model.connection.config.servedBuild),
              .offline, "an offline machine could not be retried anyway")
    }

    if let laptop = model.connection.device(DemoFixtures.laptopDeviceID) {
        equal(DeviceUpdate.notice(for: laptop), .failed(DemoFixtures.updateFailure),
              "the machine the gateway gave up on says so, in the words it failed with")
        expect(DeviceUpdate.canRetry(laptop), "and is the one row that offers Retry update")
        expect(DeviceUpdate.block(for: laptop, servedBuild: model.connection.config.servedBuild) == nil,
               "which nothing is stopping")
        await model.updateDevice(laptop)
        await settle { model.connection.device(DemoFixtures.laptopDeviceID)?.updateState == .updating }
        equal(model.connection.device(DemoFixtures.laptopDeviceID)?.updateState, .updating,
              "retrying puts the row in its updating state")
        expect(model.deviceUpdateError(DemoFixtures.laptopDeviceID) == nil,
               "and an accepted retry is not an error")
        await settle(timeout: 15) {
            model.connection.device(DemoFixtures.laptopDeviceID)?.updateState == .idle
        }
        equal(model.connection.device(DemoFixtures.laptopDeviceID)?.clientBuild,
              DemoFixtures.servedBuild, "and the device comes back on the build it was sent to")
        if let back = model.connection.device(DemoFixtures.laptopDeviceID) {
            expect(DeviceUpdate.notice(for: back) == nil, "after which its row is quiet again")
            expect(!DeviceUpdate.canRetry(back), "with nothing left to retry")
        }
    } else {
        expect(false, "the demo lists a device whose update failed")
    }
    if let studio = model.connection.device(DemoFixtures.macDeviceID) {
        // A retry the device refuses — it came current while the alert was up —
        // is the app's own news: no update ever started, so no `device.updated`
        // will ever carry it.
        await model.updateDevice(studio)
        let refusal = model.deviceUpdateError(DemoFixtures.macDeviceID)
        expect(refusal != nil, "a refused retry is held against the row that asked for it")
        equal(DeviceUpdate.notice(for: studio, localError: refusal), .failed(refusal ?? ""),
              "and reads on that row exactly as the gateway's own failure does")
    }

    // MARK: - Amendment A34: another agent's words sit on the agent's side
    //
    // `docs/DESIGN.md` § "The timeline" → "Messages from other agents". The
    // demo's shared session carries one, so both levels can be looked at.

    if let shared = helloSessions.first(where: { $0.sessionID == DemoFixtures.sharedSessionID }) {
        let detail = SettingsStore(defaults: UserDefaults(suiteName: "rc-a34-\(UUID().uuidString)")!)
        let chat = ChatStore(session: shared, channel: DemoGateway())
        chat.detailSource = { detail.timelineDetail }
        for event in DemoFixtures.history(for: DemoFixtures.sharedSessionID) {
            chat.receive(.sessionEvent(sessionID: shared.sessionID, deviceID: shared.deviceID,
                                       event: event))
        }
        let fromAgent = chat.timeline.entries.first { entry in
            if case .userMessage(let payload) = entry.body { return payload.source == .agent }
            return false
        }
        expect(fromAgent != nil, "the scripted session carries a message another agent filed")
        equal(detail.timelineDetail, .simple, "and the reader starts at Simple")
        expect(!chat.rows.contains { $0.id == fromAgent?.id },
               "where it is the agent's working and is not drawn")
        detail.timelineDetail = .detailed
        expect(chat.rows.contains { $0.id == fromAgent?.id },
               "and Detailed draws it with the rest of the working")
        // It is never the person's bubble: the row is chosen by the source.
        if case .userMessage(let payload)? = fromAgent?.body {
            equal(payload.source, .agent, "the row is chosen by what the device said, not by the kind")
            expect(!payload.text.contains("<"), "and the device already stripped the envelope")
        }
    } else {
        expect(false, "the demo lists the attached session the agent message lives in")
    }

    // MARK: - Amendment A35: a session the usage limit stopped
    //
    // `docs/DESIGN.md` § "Paused by the usage limit". One switch on the
    // account, one notice above the transcript with two actions, and rows that
    // say what the device did.

    // The running demo's own hello reaches the store, so the switch in Settings
    // is live before anybody opens the screen.
    expect(model.preferences.isOffered, "the demo's hello seeds the preferences store")
    expect(model.preferences.resumeAfterLimit, "with the value the demo gateway holds")

    // The switch reads and writes the account's preferences, so a gateway that
    // offers none leaves it disabled and a write goes out over HTTP.
    let preferenceGateway = DemoGateway()
    let preferences = PreferencesStore()
    expect(!preferences.isOffered, "a store with no gateway offers nothing")
    expect(!preferences.resumeAfterLimit, "and reads off")
    preferences.attach(api: preferenceGateway)
    preferences.receive(.hello(HelloFrame(
        protocolVersion: RemoteProtocol.version, gatewayVersion: "0.1.0-demo",
        user: UserIdentity(username: "admin"), devices: [], sessions: [], stt: .disabled,
        preferences: Preferences(resumeAfterLimit: false), serverTime: DemoFixtures.now)))
    expect(preferences.isOffered, "a hello that carries preferences offers the switch")
    expect(!preferences.resumeAfterLimit, "off until the person turns it on")
    await preferences.setResumeAfterLimit(true)
    expect(preferences.resumeAfterLimit, "turning it on writes it and keeps it on")
    expect(preferences.errorMessage == nil, "with nothing to report")
    // Another app of the same account turning it off reaches this one.
    preferences.receive(.preferencesUpdated(Preferences(resumeAfterLimit: false)))
    expect(!preferences.resumeAfterLimit, "and preferences.updated moves it back")
    // A gateway older than the amendment sends none at all.
    preferences.receive(.hello(HelloFrame(
        protocolVersion: RemoteProtocol.version, gatewayVersion: "0.1.0-old",
        user: UserIdentity(username: "admin"), devices: [], sessions: [], stt: .disabled,
        serverTime: DemoFixtures.now)))
    expect(!preferences.isOffered, "a gateway that predates the switch offers it disabled")
    equal(ResumeText.settingsSentence(offered: preferences.isOffered),
          L10n.string("Your gateway does not offer this yet."),
          "and says so in the row itself")
    preferences.receive(.preferencesUpdated(Preferences(resumeAfterLimit: true)))
    expect(ResumeText.settingsSentence(offered: preferences.isOffered)
            .contains("a minute after the limit resets"),
           "a gateway that does offer it explains what it does instead")
    preferences.attach(api: nil)
    expect(!preferences.isOffered, "signing out forgets the account's value")

    // The paused demo session: the notice, both actions, and the rows.
    if let paused = helloSessions.first(where: { $0.sessionID == DemoFixtures.pausedSessionID }) {
        let gateway = DemoGateway()
        let detail = SettingsStore(defaults: UserDefaults(suiteName: "rc-a35-\(UUID().uuidString)")!)
        let chat = ChatStore(session: paused, channel: gateway)
        chat.detailSource = { detail.timelineDetail }
        for event in DemoFixtures.history(for: DemoFixtures.pausedSessionID) {
            chat.receive(.sessionEvent(sessionID: paused.sessionID, deviceID: paused.deviceID,
                                       event: event))
        }
        expect(chat.resume != nil, "the demo's paused session carries a resume")
        equal(chat.session.state, .idle,
              "and is idle, so the dot is unchanged and the notice carries the pause")
        if let resume = chat.resume {
            let words = ResumeText.notice(resume)
            expect(words.hasPrefix("Paused by the usage limit"),
                   "the notice opens with what happened")
            expect(words.contains(ResumeText.clock(resume.at)),
                   "and names the time in the viewer's own clock")
            expect(!words.contains("about"), "the vendor's own time is not called a guess")
            let guessed = SessionResume(at: resume.at, estimated: true)
            expect(ResumeText.notice(guessed).contains("about"),
                   "an estimated time is said to be one")
            let again = SessionResume(at: resume.at, attempts: 1)
            expect(ResumeText.notice(again).hasSuffix(L10n.string("second try")),
                   "and a resumed turn that hit the limit again says which try this is")
        }

        // The transcript says what happened: the turn's end, the device's row,
        // and nothing at all for the moment of resuming.
        expect(chat.rows.contains { $0.turnCompleted?.limit != nil },
               "the turn that ran into the limit ends with it")
        expect(chat.rows.contains { $0.resume?.status == .scheduled },
               "and the device's row says what it scheduled")
        equal(detail.timelineDetail, .simple, "at the level the reader starts on")
        if let footer = chat.rows.first(where: { $0.turnCompleted?.limit != nil })?.turnCompleted,
           let limit = footer.limit {
            expect(ResumeText.turnEnd(limit).hasPrefix("Ended at the usage limit"),
                   "the end of the turn names the limit rather than a duration")
        }

        // Change sends `session.resume_set` with the time it was given.
        let moved = Date().addingTimeInterval(3 * 3_600)
        await chat.setResume(at: moved)
        equal(chat.resume?.at, Int64((moved.timeIntervalSince1970 * 1000).rounded()),
              "Change moves the resume to the time the picker returned")
        expect(chat.errorMessage == nil, "and says nothing about it")
        // The row for it comes from the device, as every row does.
        chat.receive(.sessionEvent(
            sessionID: paused.sessionID, deviceID: paused.deviceID,
            event: SessionEvent(seq: 800, ts: DemoFixtures.now, kind: SessionEvent.resumeKind,
                                body: .resume(ResumePayload(status: .rescheduled,
                                                            at: chat.resume?.at ?? 0)))))
        expect(chat.rows.contains { $0.resume?.status == .rescheduled },
               "the device's row records the move")

        // A time the device would refuse never leaves the app.
        await chat.setResume(at: Date().addingTimeInterval(10))
        expect(chat.errorMessage != nil, "a resume less than a minute out is refused here")
        chat.clearError()
        await chat.setResume(at: Date().addingTimeInterval(9 * 86_400))
        expect(chat.errorMessage != nil, "and so is one further out than eight days")
        chat.clearError()

        // Cancel takes it away at once, and asking twice is not an error.
        await chat.cancelResume()
        expect(chat.resume == nil, "Cancel removes the resume")
        chat.receive(.sessionEvent(
            sessionID: paused.sessionID, deviceID: paused.deviceID,
            event: SessionEvent(seq: 801, ts: DemoFixtures.now, kind: SessionEvent.resumeKind,
                                body: .resume(ResumePayload(status: .cancelled,
                                                            reason: "you changed your mind")))))
        expect(chat.rows.contains { $0.resume?.status == .cancelled }, "and the row says so")
        await chat.cancelResume()
        expect(chat.errorMessage == nil, "a second cancel is idempotent, not a failure")

        // The prompt the device sends for the person is the person's own row.
        chat.receive(.sessionEvent(
            sessionID: paused.sessionID, deviceID: paused.deviceID,
            event: SessionEvent(seq: 900, ts: DemoFixtures.now,
                                kind: SessionEvent.userMessageKind, blockID: "resume-1",
                                body: .userMessage(UserMessagePayload(
                                    text: "The usage limit has reset.", source: .resume)))))
        chat.receive(.sessionEvent(
            sessionID: paused.sessionID, deviceID: paused.deviceID,
            event: SessionEvent(seq: 901, ts: DemoFixtures.now, kind: SessionEvent.resumeKind,
                                body: .resume(ResumePayload(status: .fired)))))
        expect(chat.rows.contains { $0.userMessage?.source == .resume },
               "the prompt is drawn at Simple, in the person's bubble")
        expect(!chat.rows.contains { $0.resume?.status == .fired },
               "and the moment of resuming draws no row of its own")
        equal(ResumeText.sentForYou, L10n.string("Sent for you after the limit reset"),
              "the caption says who sent it and why")
    } else {
        expect(false, "the demo lists the session the usage limit paused")
    }

    // The banner the app raises for itself, on the three statuses the gateway
    // pushes for and on no others.
    if let paused = helloSessions.first(where: { $0.sessionID == DemoFixtures.pausedSessionID }) {
        let notifier = TurnNotifier(platform: FakeTurnAlertPlatform())
        for (status, kind) in [(ResumeStatus.scheduled, PushKind.limitReached),
                               (.fired, .resumed), (.dropped, .resumeDropped)] {
            guard let announced = TurnAlerts.kind(resume: status) else {
                expect(false, "\(status.rawValue) is announced")
                continue
            }
            equal(announced, kind, "\(status.rawValue) raises the kind the gateway pushes")
            let alert = notifier.announce(kind: announced, session: paused,
                                          deviceName: "mac-studio-office",
                                          identifier: "resume/\(paused.id)/\(status.rawValue)",
                                          sceneActive: true, enabled: true,
                                          authorization: .authorized)
            expect(alert != nil, "and the app raises its own banner for it")
            expect(alert?.body.isEmpty == false, "with one of the three sentences")
            expect(alert?.route.title.contains("mac-studio-office") == true,
                   "titled with the machine and no time")
        }
        expect(TurnAlerts.kind(resume: .rescheduled) == nil, "a moved resume is not news")
        expect(TurnAlerts.kind(resume: .cancelled) == nil, "and neither is one you cancelled")
        expect(notifier.announce(kind: .limitReached, session: paused, deviceName: "mac",
                                 sceneActive: false, enabled: true,
                                 authorization: .authorized) == nil,
               "nothing is raised while the app is in the background")
        expect(notifier.announce(kind: .limitReached, session: paused, deviceName: "mac",
                                 sceneActive: true, enabled: false,
                                 authorization: .authorized) == nil,
               "nor with the Notifications switch off")
    }

    // MARK: - Amendment A33: how an agent is signed in, and what is left of it
    //
    // `docs/DESIGN.md` § "A device has a page" and § "Quota is a meter, drawn
    // for accounts only". The credentials are on the device list already; the
    // windows are asked for once, per page, and never published.

    if let studio = model.connection.device(DemoFixtures.macDeviceID) {
        let stored = studio.agents.flatMap { $0.accounts ?? [] }
        expect(!stored.isEmpty, "the device list carries what each agent is signed in with")
        expect(stored.allSatisfy { $0.limits == nil && $0.limitsCheckedAt == nil },
               "and carries no window with them, so a percentage never redraws the list")
        equal(studio.agent("claude")?.accounts?.first?.tier, "Max 5x",
              "a tier the device put into words is stored as it wrote it")
    } else {
        expect(false, "the demo lists the machine the page is opened on")
    }

    if let channel = model.connection.channel {
        let fresh = try? await channel.request(.agents(deviceID: DemoFixtures.macDeviceID),
                                               as: AgentsResult.self)
        let claude = fresh?.agents.first { $0.agent == "claude" }?.accounts?.first
        equal(claude?.limits?.count, 3, "the reply carries the windows the device read")
        equal(claude?.limits?.last?.scope, "Fable", "including the one confined to a model")
        let grok = fresh?.agents.first { $0.agent == "grok" }?.accounts?.first
        expect(grok != nil && grok?.limits == nil && grok?.limitsError == nil,
               "a vendor that exposes no windows reports neither a window nor a failure")
        let key = fresh?.agents.first { $0.agent == "pi" }?.accounts?.last
        equal(key?.method, .apiKey, "pi's second credential is a key")
        equal(key?.endpoint, "api.relay.example", "sent to a host that is not the vendor's")

        // Asking does not change what the list holds.
        let after = model.connection.device(DemoFixtures.macDeviceID)?.agents.flatMap { $0.accounts ?? [] }
        expect(after?.allSatisfy { $0.limits == nil } ?? false,
               "and the stored device is untouched by the answer")

        let laptop = try? await channel.request(.agents(deviceID: DemoFixtures.laptopDeviceID),
                                                as: AgentsResult.self)
        let expired = laptop?.agents.first { $0.agent == "claude" }?.accounts?.first
        expect(expired?.limitsError?.isEmpty == false,
               "a check the device could not make says why, in one line")
        equal(laptop?.agents.first { $0.agent == "grok" }?.accounts, [],
              "an agent signed in nowhere reports no credential at all")

        // An offline machine is refused rather than answered, which is the page's
        // "Offline · quota unavailable".
        do {
            _ = try await channel.request(.agents(deviceID: DemoFixtures.ciDeviceID),
                                          as: AgentsResult.self)
            expect(false, "an offline device cannot be asked for its quota")
        } catch let error as GatewayErrorBody {
            equal(error.code, .deviceOffline, "and says so with the code the page reads")
        } catch {
            expect(false, "an offline device is refused by the gateway, not by the transport")
        }
    }

    // The words the page writes around the device's own.
    equal(AccountLine.vendorName("anthropic"), "Anthropic", "a vendor the app knows is named")
    equal(AccountLine.vendorName("mistral"), "mistral", "and one it does not is printed as itself")
    equal(AccountLine.text(for: AgentAccount(provider: "openai", method: .apiKey,
                                             endpoint: "api.relay.example")),
          "OpenAI API key · api.relay.example", "a key names its vendor and the host it is sent to")
    equal(AccountLine.text(for: AgentAccount(provider: "anthropic", method: .account, plan: "max",
                                             tier: "Max 5x", email: "me@example.com")),
          "Anthropic account · Max · Max 5x · me@example.com",
          "and an account raises the plan's first letter and prints the tier as it arrived")
    equal(QuotaWindow.name(AgentLimit(windowMinutes: 10080, scope: "Fable", usedPercent: 64)),
          "7-day · Fable", "a window carries its scope after its length")
    equal(QuotaWindow.band(usedPercent: 80), .normal, "eighty per cent is still the ink colour")
    equal(QuotaWindow.band(usedPercent: 80.5), .warning, "past it the meter warns")
    equal(QuotaWindow.band(usedPercent: 100), .danger, "and a spent window is the danger colour")

    // MARK: - Amendment A37: a folder made where a session will work
    //
    // The picker sends `device.mkdir` for the directory on screen and stands in
    // whatever comes back. What decides the screen is here: the listing a made
    // folder answers with, the parent that now holds it, and the two refusals
    // the alert has words for.
    if let channel = model.connection.channel {
        let made = try? await channel.request(
            .mkdir(deviceID: DemoFixtures.macDeviceID, path: "/Users/me/dev", name: "round-41"),
            as: DirectoryListing.self)
        equal(made?.path, "/Users/me/dev/round-41", "the reply stands in the folder that was made")
        equal(made?.parent, "/Users/me/dev", "under the directory it was made in")
        equal(made?.entries.count, 0, "and it is empty, so Select picks a folder with nothing in it")
        equal(made?.recent.isEmpty, false, "with the recents `device.dirs` would have sent")

        let parent = try? await channel.request(
            .dirs(deviceID: DemoFixtures.macDeviceID, path: "/Users/me/dev"),
            as: DirectoryListing.self)
        expect(parent?.entries.contains { $0.name == "round-41" } ?? false,
               "and browsing back up lists it where it was made")

        do {
            _ = try await channel.request(
                .mkdir(deviceID: DemoFixtures.macDeviceID, path: "/Users/me/dev", name: "round-41"),
                as: DirectoryListing.self)
            expect(false, "a second folder of that name is refused")
        } catch let error as GatewayErrorBody {
            equal(error.code, .conflict, "with the code the alert reads")
            equal(DirectoryError.makeFolder(error), "A folder with that name already exists.",
                  "which the app says in its own words rather than repeating a path")
        } catch {
            expect(false, "a clash is refused by the gateway, not by the transport")
        }

        do {
            _ = try await channel.request(
                .mkdir(deviceID: DemoFixtures.macDeviceID, path: "/Users/me/dev", name: ".hidden"),
                as: DirectoryListing.self)
            expect(false, "a name the rules do not allow is refused")
        } catch let error as GatewayErrorBody {
            equal(error.code, .badRequest, "as a bad request")
            equal(DirectoryError.makeFolder(error), error.message,
                  "and there the device's own sentence is what the reader is shown")
        } catch {
            expect(false, "a bad name is refused by the gateway, not by the transport")
        }
    }

    // MARK: - Amendment A38: a device row opens a terminal
    //
    // Rule 20, and `docs/DESIGN.md` § "The terminal". Three things are settled
    // here: what a row's tap does on each of the three demo machines, what the
    // row's menu offers and in which order, and what one shell actually sends
    // and receives end to end through the demo gateway.
    if let machine = model.connection.device(DemoFixtures.macDeviceID) {
        equal(machine.terminal, true, "the demo's main machine offers a terminal")
        equal(DeviceTap.outcome(for: machine), DeviceTap.Outcome.terminal,
              "so its row's tap opens one")
        equal(DeviceRowAction.menu(for: machine), [.rename, .showQuota, .revoke],
              "and its menu reads Rename · Show quota · Revoke")
    } else {
        expect(false, "the demo lists the machine a terminal opens on")
    }
    if let machine = model.connection.device(DemoFixtures.laptopDeviceID) {
        equal(DeviceTap.outcome(for: machine),
              DeviceTap.Outcome.refused("This device does not offer a terminal."),
              "a machine with the capability off says so instead of opening anything")
    } else {
        expect(false, "the demo lists a machine that offers no terminal")
    }
    // The fourth action appears only while an update has failed (A36, rule 18),
    // and the demo's failed machine was already carried through a retry above,
    // so the state is built here rather than borrowed.
    equal(DeviceRowAction.menu(for: Device(deviceID: "d", name: "n", platform: .macos,
                                           hostname: "h", arch: "arm64", clientVersion: "1",
                                           updateState: .failed, updateMessage: "it did not come back",
                                           online: true, lastSeen: 0, createdAt: 0, terminal: true)),
          [.rename, .retryUpdate, .showQuota, .revoke],
          "Retry update stands between Rename and Show quota where an update failed")
    if let machine = model.connection.device(DemoFixtures.ciDeviceID) {
        equal(DeviceTap.outcome(for: machine),
              DeviceTap.Outcome.refused("This device is offline."),
              "and a machine that is not there says that, though it does offer terminals")
    } else {
        expect(false, "the demo lists an offline machine")
    }
    equal(DeviceRowAction.showQuota.identifier, "device.showQuota",
          "Show quota is what opens the page of A33")

    if let live = model.connection.channel {
        let gateway = RecordingGateway(live)
        let sink = TerminalSink()
        let terminal = TerminalSession(deviceID: DemoFixtures.macDeviceID, channel: gateway,
                                       resizeDelay: .milliseconds(10))
        terminal.onOutput = { sink.append($0) }
        model.connection.addFrameHandler("terminal-checks") { terminal.receive($0) }

        await terminal.open(cols: 100, rows: 32)
        equal(terminal.status, .connected, "opening a terminal on the demo machine connects")
        expect(terminal.terminalID?.isEmpty == false, "and names the terminal it opened")
        let open = await gateway.bodies("terminal.open").first
        equal(open?["device_id"]?.stringValue, DemoFixtures.macDeviceID,
              "terminal.open names the machine")
        equal(open?["cols"]?.intValue, 100, "with the columns the emulator was drawn at")
        equal(open?["rows"]?.intValue, 32, "and the rows")

        await settle { sink.text.contains("demo:~$") }
        expect(sink.text.contains("demo:~$"), "the shell prints a prompt of its own accord")

        terminal.type(Data("pwd\r".utf8))
        await settle { sink.text.contains("/Users/me") }
        expect(sink.text.contains("/Users/me"), "and answers a line that was typed")
        let input = await gateway.bodies("terminal.input").first
        equal(input?["terminal_id"]?.stringValue, terminal.terminalID,
              "terminal.input names the terminal")
        equal(Data(base64Encoded: input?["data"]?.stringValue ?? "").map {
            String(decoding: $0, as: UTF8.self)
        }, "pwd\r", "and carries the bytes as typed, base64")

        terminal.resize(cols: 120, rows: 40)
        await settleAsync { await !gateway.bodies("terminal.resize").isEmpty }
        let resize = await gateway.bodies("terminal.resize").first
        equal(resize?["cols"]?.intValue, 120, "a resize follows the view")
        equal(resize?["rows"]?.intValue, 40, "in both directions")

        // A lost socket, and the attach that brings the same screen back.
        let before = sink.text
        terminal.link(isUp: false)
        equal(terminal.status, .disconnected, "a lost socket is said rather than hidden")
        await terminal.attach()
        equal(terminal.status, .connected, "and attaching takes the shell back")
        expect(sink.text.count > before.count,
               "with the scrollback written into the emulator before anything new")
        let attach = await gateway.bodies("terminal.attach").first
        equal(attach?["terminal_id"]?.stringValue, terminal.terminalID,
              "terminal.attach names the terminal it is taking")

        // The shell ends itself, and the screen offers another.
        terminal.type(Data("exit\r".utf8))
        await settle { TerminalStatusText.offersNewShell(terminal.status) }
        equal(terminal.status, .exited(code: 0), "typing exit ends the shell with its code")
        equal(TerminalStatusText.line(for: terminal.status), "Shell exited (0)",
              "which the status line says in words")
        expect(TerminalStatusText.offersNewShell(terminal.status),
               "and offers a new shell rather than a reconnect")
        equal(terminal.terminalID, nil, "the id is freed")

        // A second shell, closed by hand, sends exactly one `terminal.close`.
        await terminal.open(cols: 80, rows: 24)
        terminal.close()
        terminal.close()
        await settleAsync { await !gateway.bodies("terminal.close").isEmpty }
        equal(await gateway.bodies("terminal.close").count, 1,
              "leaving the screen twice closes the shell once")
        model.connection.removeFrameHandler("terminal-checks")
    } else {
        expect(false, "the demo gateway has a channel to open a terminal on")
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

    // MARK: - A notification opens its session in place
    //
    // `docs/DESIGN.md` § "Status vocabulary": tapping a notification for
    // session B while session A is open replaces A with B, Back returns to the
    // list rather than to A, and B is open the moment it is on screen — never
    // a spinner that waits for a tap.
    //
    // SwiftUI delivers the new view's `.task` before the covered view's
    // `onDisappear`, so the two callbacks are played here in that order. A
    // close addressed to "the open chat" rather than to a named one closed the
    // conversation that had just replaced it, and the visible screen was left
    // with no store, no stream and no composer.
    await model.closeChat()
    model.path = []
    if let held = model.connection.sessions.first(where: { $0.sessionID == DemoFixtures.liveSessionID }),
       let opened = model.connection.sessions.first(where: { $0.sessionID == DemoFixtures.approvalSessionID }) {
        await model.open(held)
        equal(model.path, [held.id], "a session opened from the list is the one thing on the stack")
        model.handle(SessionLink(deviceID: opened.deviceID, sessionID: opened.sessionID))
        await settle { model.chat?.key == opened.id }
        equal(model.path, [opened.id], "a notification replaces the conversation it found open")
        // The covered view's `onDisappear`, which arrives second.
        await model.closeChat(key: held.id)
        equal(model.chat?.key, opened.id,
              "and the conversation it replaced does not close it on the way out")
        await model.closeChat(key: opened.id)
        expect(model.chat == nil, "while the conversation on screen closes when it names itself")
    } else {
        expect(false, "the demo carries two sessions to open one over the other")
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

    // MARK: - What a scene phase means
    //
    // `docs/DESIGN.md` § "The composer" (Voice): "only leaving the app ends it
    // early: the background suspends dictation … Control Centre, the app
    // switcher's peek, an incoming-call banner and a system alert only make the
    // app inactive, and dictation listens through them — as the app lock stays
    // down through them". The privacy shield is the one rule that does follow
    // `.inactive`: the switcher's snapshot is taken there.
    expect(SceneRule.isBackground(.background), "the background is leaving the app")
    expect(!SceneRule.isBackground(.inactive), "and Control Centre is not")
    expect(!SceneRule.isBackground(.active), "nor is the app being used")
    expect(SceneRule.isForeground(.active), "the app is reading the stream only while it is active")
    expect(!SceneRule.isForeground(.inactive), "and not behind Control Centre")
    expect(!SceneRule.isForeground(.background), "and not while it is suspended")
    expect(SceneRule.shields(.inactive), "the privacy shield covers the switcher's snapshot")
    expect(SceneRule.shields(.background), "and the suspended app")
    expect(!SceneRule.shields(.active), "and nothing while the app is being used")

    // MARK: - Attachments are named for what they are
    //
    // `docs/DESIGN.md` § "The composer". The library's own identifier is a
    // `PHAsset` local id — a UUID with slashes and no extension — so a file
    // named from it reaches the agent with nothing to say it is an image.
    equal(AttachmentNaming.libraryPhoto(1), "photo-1.jpg", "the first photo is named for its place")
    equal(AttachmentNaming.libraryPhoto(2), "photo-2.jpg", "and so is the next one")
    equal(AttachmentNaming.cameraPhoto, "photo.jpg", "a camera shot is the one photo there is")
    expect(!AttachmentNaming.libraryPhoto(1).contains("/"),
           "and no name carries a path separator into the device's attachment directory")

    // MARK: - One run of an action at a time
    //
    // Retry on the unconfirmed banner reuses the original request id, so a
    // second tap while the first is still out would put two requests under one
    // id. The button is disabled while it is out, and a tap that beats the
    // redraw starts nothing either.
    let gate = OneAtATime()
    var runs = 0
    var busyWhileRunning = false
    await gate.run {
        runs += 1
        busyWhileRunning = gate.isBusy
        await gate.run { runs += 1 }
    }
    equal(runs, 1, "a control that is already acting starts no second run")
    expect(busyWhileRunning, "and says so while it acts, so the button can be disabled")
    expect(!gate.isBusy, "and comes back when the action returns")

    // MARK: - A picked file is read, not mapped
    //
    // The security-scoped access a picked URL carries ends with the loop that
    // opened it, and faulting a page of a mapping whose access has ended is a
    // `SIGBUS` rather than a thrown error. An eager read is a snapshot, which
    // is what the check below measures.
    let scratch = URL(fileURLWithPath: NSTemporaryDirectory())
        .appendingPathComponent("rc-ui-verify-\(UUID().uuidString).bin")
    let first = Data(repeating: UInt8(ascii: "a"), count: 2 * 1024 * 1024)
    let second = Data(repeating: UInt8(ascii: "b"), count: 2 * 1024 * 1024)
    if (try? first.write(to: scratch)) != nil, let read = try? PickedFile.read(scratch) {
        equal(PickedFile.size(of: scratch), first.count, "a picked file is measured before it is read")
        // In place, through the same inode, which is what a mapping would show
        // through: replacing the file would leave even a mapping on the bytes
        // it was made from.
        if let handle = try? FileHandle(forWritingTo: scratch) {
            try? handle.write(contentsOf: second)
            try? handle.close()
        }
        equal(read.first, UInt8(ascii: "a"), "and the bytes read are a snapshot, not a live mapping")
        equal(read.count, first.count, "of the whole file")
    } else {
        expect(false, "the check can write and read a scratch file")
    }
    try? FileManager.default.removeItem(at: scratch)

    // MARK: - Drafts belong to sessions that still exist, and to one account
    //
    // The `hello` snapshot is the whole list of what an account has, so a draft
    // key it does not name belongs to a session deleted on the device; left
    // alone its words would sit on disk for the life of the install. Signing
    // out takes the rest with the account's cache — before the connection is
    // torn down, because that is what empties `user`.
    let draftDirectory = URL(fileURLWithPath: NSTemporaryDirectory())
        .appendingPathComponent("rc-ui-verify-drafts-\(UUID().uuidString)")
    let drafts = DraftStore(directory: draftDirectory)
    let account = AppModel(connection: .offlineDemo(),
                           settings: SettingsStore(defaults: freshDefaults()),
                           drafts: drafts, arguments: [])
    await account.signIn(origin: "https://rc.example.com",
                         username: DemoFixtures.memberUsername, password: "correct horse")
    await settle { account.connection.hasSnapshot }
    if account.connection.hasSnapshot, let live = account.connection.sessions.first {
        let scope = account.connection.account
        expect(!account.connection.isDemo, "the offline gateway behind the form is an ordinary account")
        await drafts.setDraft("still here", account: scope, key: live.id)
        await drafts.setDraft("long gone", account: scope, key: "\(live.deviceID)/deleted-session")
        await account.adoptSnapshot()
        equal(await drafts.draft(account: scope, key: live.id), "still here",
              "a draft for a session the snapshot lists is kept")
        equal(await drafts.draft(account: scope, key: "\(live.deviceID)/deleted-session"), "",
              "and one for a session it does not name is forgotten")

        await account.signOut()
        equal(await drafts.draft(account: scope, key: live.id), "",
              "signing out clears the account's drafts, under the account that wrote them")
    } else {
        expect(false, "the offline gateway answers with a snapshot to prune against")
    }
    try? FileManager.default.removeItem(at: draftDirectory)

    // MARK: - `--reset-state` forgets every draft
    //
    // A UI test that typed into a session and never sent left its words in the
    // draft file, and every later launch opened that session with them already
    // in the field (round 31 saw "//usageusage" grow across runs). The reset a
    // test launches with has to take the drafts along with the settings.
    let staleDirectory = URL(fileURLWithPath: NSTemporaryDirectory())
        .appendingPathComponent("rc-ui-verify-stale-drafts-\(UUID().uuidString)")
    let staleDrafts = DraftStore(directory: staleDirectory)
    await staleDrafts.setDraft("/usage", account: "left|behind", key: "d/s")
    let resetting = AppModel(connection: .offlineDemo(),
                             settings: SettingsStore(defaults: freshDefaults()),
                             drafts: staleDrafts, arguments: ["--reset-state"])
    _ = resetting
    var remaining = await staleDrafts.draft(account: "left|behind", key: "d/s")
    for _ in 0..<50 where !remaining.isEmpty {
        try? await Task.sleep(for: .milliseconds(50))
        remaining = await staleDrafts.draft(account: "left|behind", key: "d/s")
    }
    equal(remaining, "", "a launch with --reset-state forgets the drafts a previous run left")
    try? FileManager.default.removeItem(at: staleDirectory)

    // MARK: - The Settings screen
    //
    // `docs/DESIGN.md` § "The Settings screen" (owner's ruling, 2026-09-18).
    // Nothing here draws, so what is read back is the pure part — the initials,
    // the host, the dot and its word, the header's own line and the versions —
    // and the shape of the screen as its source states it.

    equal(Initials.of("admin"), "AD", "one word gives its first two letters")
    equal(Initials.of("j.gao"), "JG", "two words give a letter each")
    equal(Initials.of("ci-runner"), "CR", "whatever the separator is")
    equal(Initials.of("\u{674E}\u{96F7}"), "\u{674E}",
          "and a script whose characters are words of their own gives one")
    equal(Initials.of("x"), "X", "a one-letter name is one letter")
    equal(Initials.of(""), "", "and an account with no name draws nothing")

    equal(GatewayHost.of("https://rc.example.com"), "rc.example.com", "the host without its scheme")
    equal(GatewayHost.of("http://192.168.1.4:8787"), "192.168.1.4:8787",
          "with the port where the gateway is not on the usual one")
    equal(GatewayHost.of("https://rc.example.com/"), "rc.example.com", "and no trailing slash")

    equal(ConnectionTone.dot(.connected), .working, "a live connection is green")
    equal(ConnectionTone.dot(.connecting), .waiting, "one being made pulses amber")
    equal(ConnectionTone.dot(.syncing), .waiting, "so does one still catching up")
    equal(ConnectionTone.dot(.reconnecting), .waiting, "and one coming back")
    equal(ConnectionTone.dot(.signedOut), .off, "a link that is down is grey")
    equal(ConnectionTone.dot(.forbidden), .failed, "a gateway that refused it is red")
    equal(ConnectionTone.dot(.incompatible(gatewayVersion: 2)), .failed,
          "and so is one that will not speak to this build")
    equal(ConnectionTone.word(.connected), L10n.string("Connected"), "the dot's word is Connected")
    equal(ConnectionTone.word(.reconnecting), L10n.string("Connecting"), "Connecting while it is made")
    equal(ConnectionTone.word(.signedOut), L10n.string("Offline"), "Offline while there is none")
    equal(ConnectionTone.word(.expired), L10n.string("Refused"), "and Refused when the gateway said no")

    equal(IdentityLine.label(user: UserIdentity(username: DemoFixtures.adminUsername, role: .admin),
                             host: L10n.string("Demo"), phase: .connected),
          "admin, Admin, Demo, Connected",
          "the header reads who is signed in, as what, where, and how the link is")

    let versionsLine = VersionsLine.text(app: AppBuild.version, gateway: "1.5.0",
                                         protocolVersion: RemoteProtocol.version)
    expect(versionsLine.contains(AppBuild.shipped), "the versions line names this build")
    expect(versionsLine.contains("Gateway 1.5.0"), "and the gateway it is talking to")
    expect(versionsLine.hasSuffix("v\(RemoteProtocol.version)"),
           "and ends on the protocol they both speak")
    expect(!VersionsLine.text(app: AppBuild.version, gateway: "", protocolVersion: 1).contains("Gateway"),
           "a gateway that has not said its version yet leaves its half out")

    // The groups, read off the screen's own source: one file each, the captions
    // the ruling names, in the order it names them, and none of the groups it
    // took away.
    let settingsRoot = URL(fileURLWithPath: #filePath)
        .deletingLastPathComponent()   // ios/VerificationUI
        .deletingLastPathComponent()   // ios
        .appending(path: "Sources/RCUI/Screens/Settings")
    func settingsSource(_ name: String) -> String {
        (try? String(contentsOf: settingsRoot.appending(path: name), encoding: .utf8)) ?? ""
    }
    let settingsFiles = ((try? FileManager.default.contentsOfDirectory(
        at: settingsRoot, includingPropertiesForKeys: nil)) ?? []).filter { $0.pathExtension == "swift" }
    let settingsSources = settingsFiles
        .compactMap { try? String(contentsOf: $0, encoding: .utf8) }.joined(separator: "\n")
    expect(settingsFiles.count >= 9, "the screen is a file per group, not one of 380 lines")

    for (file, caption) in [("SettingsAccountGroup.swift", "Account"),
                            ("SettingsAwayGroup.swift", "While you're away"),
                            ("SettingsVoiceGroup.swift", "Voice"),
                            ("SettingsReadingGroup.swift", "Reading"),
                            ("SettingsSecurityGroup.swift", "Security")] {
        expect(settingsSource(file).contains("SettingsGroup(\"\(caption)\")"),
               "the group in \(file) is headed \(caption)")
    }

    let screen = settingsSource("SettingsView.swift")
    var cursor = screen.startIndex
    var inOrder = !screen.isEmpty
    for group in ["SettingsIdentityHeader(", "SettingsAccountGroup(", "SettingsAwayGroup(",
                  "SettingsVoiceGroup(", "SettingsReadingGroup(", "SettingsSecurityGroup(",
                  "SettingsVersionsRow("] {
        guard let found = screen.range(of: group, range: cursor..<screen.endIndex) else {
            inOrder = false
            break
        }
        cursor = found.upperBound
    }
    expect(inOrder, "the header, the five groups and the versions line are drawn in that order")

    for gone in ["About", "Notifications", "Timeline", "App lock", "Sessions", "Language"] {
        expect(!settingsSources.contains("FieldLabel(\"\(gone)\")"),
               "no group is headed \(gone) any more")
    }
    expect(!settingsSources.contains("SettingsFooter("),
           "and no sentence is left under a group: a state speaks in its row")

    // MARK: - The privacy strings and the version the app ships
    //
    // Every system resource the app reaches for needs a purpose string or iOS
    // refuses it, and the local network is one of them: `GatewayEndpoint`
    // accepts a gateway on the LAN and `Info.plist` sets `NSAllowsLocalNetworking`
    // for it. The simulator shares the Mac's network and never asks, so nothing
    // else here would notice the omission.
    let projectFile = URL(fileURLWithPath: #filePath)
        .deletingLastPathComponent().deletingLastPathComponent()
        .appendingPathComponent("project.yml")
    if let project = try? String(contentsOf: projectFile, encoding: .utf8) {
        for key in ["NSPhotoLibraryUsageDescription", "NSCameraUsageDescription",
                    "NSFaceIDUsageDescription", "NSMicrophoneUsageDescription",
                    "NSSpeechRecognitionUsageDescription", "NSLocalNetworkUsageDescription"] {
            expect(project.contains("INFOPLIST_KEY_\(key):"), "the app declares \(key)")
        }
        expect(project.contains("MARKETING_VERSION: '\(AppBuild.shipped)'"),
               "the project ships the version this source tree carries")
        expect(project.contains("CURRENT_PROJECT_VERSION: 19"),
               "and a build number TestFlight can tell apart")
    } else {
        expect(false, "the check can read project.yml")
    }

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
    func preferences() async throws -> PreferencesResponse { throw TransportError.notConnected }
    func patchPreferences(resumeAfterLimit: Bool?) async throws -> PreferencesResponse {
        throw TransportError.notConnected
    }
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

/// Amendment A38: what the emulator would have been fed, collected instead.
@MainActor
final class TerminalSink {
    private(set) var bytes = Data()
    var text: String { String(decoding: bytes, as: UTF8.self) }
    func append(_ data: Data) { bytes.append(data) }
}

/// A channel that passes everything to the demo gateway and remembers what went
/// through it, so the requests a terminal makes can be read as well as their
/// effect.
actor RecordingGateway: GatewayChannel {
    private nonisolated let inner: any GatewayChannel
    private var seen: [GatewayRequest] = []

    nonisolated var events: AsyncStream<GatewayEvent> { inner.events }

    init(_ inner: any GatewayChannel) { self.inner = inner }

    func connect() async { await inner.connect() }
    func disconnect() async { await inner.disconnect() }

    func request(_ request: GatewayRequest) async throws -> JSONValue {
        seen.append(request)
        return try await inner.request(request)
    }

    func bodies(_ type: String) -> [[String: JSONValue]] {
        seen.filter { $0.type == type }.map(\.body)
    }
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
