import Foundation
import RCCore

/// Store behaviour that does not need SwiftUI, kept in the fast executable so
/// it runs without Xcode.
enum StoreChecks {
    @MainActor
    static func run() async -> CheckResult {
        let checks = CheckRunner(group: "stores")
        await connection(checks)
        await chat(checks)
        sessionsList(checks)
        dotTones(checks)
        settings(checks)
        await pairing(checks)
        await closeHandling(checks)
        await reconnectResubscribe(checks)
        await gapRepair(checks)
        await warmOpen(checks)
        await sendability(checks)
        await optimisticSend(checks)
        await readingPosition(checks)
        return checks.result()
    }

    /// The status dot, over the whole table in `docs/DESIGN.md`. The tone is a
    /// function of all three facts, which is the point: `state` alone cannot
    /// tell a finished turn on a live session from a session nothing owns.
    @MainActor
    private static func dotTones(_ checks: CheckRunner) {
        let owners: [SessionControl] = [.remote, .terminal, .shared]

        for control in owners + [.none] {
            for state in [SessionState.starting, .running] {
                checks.equal(DotTone.of(state: state, control: control, online: true), .working,
                             "\(state) under \(control) is a turn under way")
            }
            for state in [SessionState.needsApproval, .needsInput] {
                checks.equal(DotTone.of(state: state, control: control, online: true), .waiting,
                             "\(state) under \(control) is blocked on the user")
            }
            checks.equal(DotTone.of(state: .error, control: control, online: true), .failed,
                         "an error under \(control) is red")
            checks.equal(DotTone.of(state: .stopped, control: control, online: true), .off,
                         "a stopped session under \(control) is grey")
        }

        for control in owners {
            for state in [SessionState.idle, .readonly] {
                checks.equal(DotTone.of(state: state, control: control, online: true), .live,
                             "\(state) is alive and quiet while \(control) still holds it")
            }
        }
        for state in [SessionState.idle, .readonly] {
            checks.equal(DotTone.of(state: state, control: .none, online: true), .off,
                         "\(state) with nothing holding it is an exited session")
        }

        for state in [SessionState.starting, .running, .needsApproval, .needsInput,
                      .idle, .readonly, .stopped, .error] {
            checks.equal(DotTone.of(state: state, control: .remote, online: false), .off,
                         "a machine that is gone reports nothing, whatever \(state) said")
        }
        checks.equal(DotTone.of(state: SessionState(rawValue: "compacting"), control: .remote, online: true),
                     .off, "a state this build has never heard of claims nothing")

        // The five tones the demo carries, so every colour is on screen at once.
        let devices = Dictionary(uniqueKeysWithValues: DemoFixtures.devices.map { ($0.deviceID, $0.online) })
        let tones = DemoFixtures.sessions.map { $0.dotTone(online: devices[$0.deviceID] ?? false) }
        checks.equal(Set(tones), Set(DotTone.allCases), "the demo list shows all five tones")
        checks.equal(tones.filter { $0 == .failed }.count, 1, "exactly one of them failed")
    }

    /// The timeline follows the newest content only while the reader is at the
    /// foot of it, and counts blocks rather than streaming deltas while they
    /// are not. Both apps state the same rule in `docs/DESIGN.md`.
    @MainActor
    private static func readingPosition(_ checks: CheckRunner) async {
        checks.expect(ScrollTail.isAtBottom(contentHeight: 2_000, containerHeight: 600, offset: 1_400),
                      "the foot of the content is the bottom")
        checks.expect(ScrollTail.isAtBottom(contentHeight: 2_000, containerHeight: 600, offset: 1_365),
                      "a row that settles a few points short is still the bottom")
        checks.expect(!ScrollTail.isAtBottom(contentHeight: 2_000, containerHeight: 600, offset: 1_200),
                      "a screenful up is not the bottom")
        checks.expect(ScrollTail.isAtBottom(contentHeight: 200, containerHeight: 600, offset: 0),
                      "a transcript shorter than its container has no bottom to leave")
        checks.equal(ScrollTail.badge(updates: 0), nil, "nothing missed carries no count")
        checks.equal(ScrollTail.badge(updates: 3), "3", "three blocks read as three")
        checks.equal(ScrollTail.badge(updates: 140), "99+", "the count stops at 99")

        let session = Session(sessionID: "reading", deviceID: "d", agent: "claude", title: "T",
                              cwd: "/tmp", state: .running)
        let chat = ChatStore(session: session, channel: ScriptedChannel())
        func arriving(_ seq: Int, blockID: String, delta: Bool = false) -> AppFrame {
            let payload = delta
                ? StreamTextPayload(delta: "more", done: false)
                : StreamTextPayload(text: "a line", done: true)
            return .sessionEvent(sessionID: "reading", deviceID: "d",
                                 event: SessionEvent(seq: seq, ts: Int64(1_788_944_400_000 + seq),
                                                     kind: SessionEvent.assistantTextKind,
                                                     blockID: blockID, body: .assistantText(payload)))
        }
        chat.receive(arriving(1, blockID: "a-1"))
        checks.equal(chat.updatesWhileAway, 0, "nothing is counted while the reader is at the bottom")
        chat.isFollowingTail = false
        chat.receive(arriving(2, blockID: "a-2"))
        chat.receive(arriving(3, blockID: "a-3"))
        chat.receive(arriving(4, blockID: "a-3", delta: true))
        checks.equal(chat.updatesWhileAway, 2,
                     "blocks are counted while the reader is away, streaming deltas are not")
        chat.isFollowingTail = true
        checks.equal(chat.updatesWhileAway, 0, "returning to the bottom clears the count")

        chat.isFollowingTail = false
        chat.draft = "back to the bottom"
        await chat.send()
        checks.expect(chat.isFollowingTail, "sending returns the transcript to the tail")
    }

    /// Review finding 1: a reconnect must re-issue `session.subscribe`, or the
    /// transcript silently stops receiving events while the list keeps updating.
    @MainActor
    private static func reconnectResubscribe(_ checks: CheckRunner) async {
        let channel = ScriptedChannel()
        let session = DemoFixtures.sessions[0]
        channel.subscribeReply = SubscribeResult(session: session, events: [], resync: false)
        let chat = ChatStore(session: session, channel: channel)
        await chat.open()
        checks.equal(channel.requests(ofType: "session.subscribe").count, 1, "opening subscribes once")

        // A live event moves the cursor, so the resubscribe must carry it.
        chat.receive(.sessionEvent(sessionID: session.sessionID, deviceID: session.deviceID,
                                   event: SessionEvent(seq: 12, ts: 1, kind: SessionEvent.noticeKind,
                                                       body: .notice(NoticePayload(level: .info, text: "x")))))
        checks.equal(chat.timeline.lastSeq, 12, "a live event advances the cursor")

        chat.receive(.hello(HelloFrame(protocolVersion: RemoteProtocol.version, gatewayVersion: "test",
                                       user: UserIdentity(username: "admin"), devices: [], sessions: [],
                                       stt: .disabled, serverTime: 0)))
        await settle { channel.requests(ofType: "session.subscribe").count == 2 }
        let resubscribes = channel.requests(ofType: "session.subscribe")
        checks.equal(resubscribes.count, 2, "a hello resubscribes the open conversation")
        checks.equal(resubscribes.last?.body["since_seq"]?.intValue, 12,
                     "the resubscribe carries the cursor, so it takes the replay path")
    }

    /// Review finding 4: a skipped seq means an event was dropped. The
    /// transcript must refill instead of rendering a hole.
    @MainActor
    private static func gapRepair(_ checks: CheckRunner) async {
        let channel = ScriptedChannel()
        let session = DemoFixtures.sessions[0]
        channel.subscribeReply = SubscribeResult(session: session, events: [], resync: false)
        let chat = ChatStore(session: session, channel: channel)
        await chat.open()
        let opening = channel.requests(ofType: "session.subscribe").count

        func notice(_ seq: Int) -> AppFrame {
            .sessionEvent(sessionID: session.sessionID, deviceID: session.deviceID,
                          event: SessionEvent(seq: seq, ts: 1, kind: SessionEvent.noticeKind,
                                              body: .notice(NoticePayload(level: .info, text: "x"))))
        }
        chat.receive(notice(41))
        checks.expect(!chat.timeline.hasGap, "consecutive events leave no gap")
        chat.receive(notice(42))
        checks.expect(!chat.timeline.hasGap, "still no gap")
        chat.receive(notice(44))
        checks.expect(chat.timeline.hasGap, "a skipped seq is detected")
        await settle { channel.requests(ofType: "session.subscribe").count > opening }
        checks.expect(channel.requests(ofType: "session.subscribe").count > opening,
                      "a detected gap refills from the gateway")
        await settle { !chat.timeline.hasGap }
        checks.expect(!chat.timeline.hasGap, "a completed refill clears the gap")
    }

    /// Review finding 7: a warm open must not blank the transcript.
    @MainActor
    private static func warmOpen(_ checks: CheckRunner) async {
        let channel = ScriptedChannel()
        let session = DemoFixtures.sessions[0]
        channel.subscribeReply = SubscribeResult(session: session, events: [], resync: false)
        let chat = ChatStore(session: session, channel: channel)
        let cached = DemoFixtures.liveHistory()
        await chat.open(cached: cached)
        checks.expect(chat.timeline.entries.isEmpty == false, "the cached transcript survives the open")
        checks.equal(chat.timeline.lastSeq, cached.map(\.seq).max(),
                     "a warm open adopts the cached cursor")
        checks.equal(channel.requests(ofType: "session.subscribe").first?.body["since_seq"]?.intValue,
                     cached.map(\.seq).max(), "a warm open subscribes from the cached cursor")
        checks.equal(channel.requests(ofType: "session.history").count, 0,
                     "a warm open does not page history")

        // A gateway that reports resync does rebuild.
        let cold = ScriptedChannel()
        cold.subscribeReply = SubscribeResult(session: session, events: [], resync: true)
        let rebuilt = ChatStore(session: session, channel: cold)
        await rebuilt.open(cached: cached)
        checks.equal(cold.requests(ofType: "session.history").count, 1,
                     "a resync rebuilds from history")
    }

    /// Review findings 8 and 13: a send that cannot succeed says why, and a
    /// retry carries the same bytes.
    @MainActor
    private static func sendability(_ checks: CheckRunner) async {
        let channel = ScriptedChannel()
        let session = DemoFixtures.sessions[0]
        channel.subscribeReply = SubscribeResult(session: session, events: [], resync: false)
        let chat = ChatStore(session: session, channel: channel)
        chat.draft = "hello"
        checks.expect(chat.canSend, "a connected session with a draft can send")

        chat.canReachGateway = false
        checks.expect(!chat.canSend, "an offline app cannot send")
        checks.equal(chat.sendBlockReason, "Offline · your draft is saved", "and it says so")
        chat.canReachGateway = true

        chat.deviceOnline = false
        checks.equal(chat.sendBlockReason, "That device is offline", "an offline device says so")
        chat.deviceOnline = true

        // A socket on its way back is not a reason to refuse: the transport
        // holds the request until the hello lands.
        checks.expect(ConnectionPhase.reconnecting.canReachGateway,
                      "a reconnecting socket still reaches the gateway")
        checks.expect(ConnectionPhase.connecting.canReachGateway, "and so does one still connecting")
        checks.expect(ConnectionPhase.syncing.canReachGateway, "and one still catching up")
        checks.expect(!ConnectionPhase.signedOut.canReachGateway, "a signed-out app does not")
        checks.expect(!ConnectionPhase.expired.canReachGateway, "and neither does an expired session")
        checks.expect(!ConnectionPhase.superseded.canReachGateway,
                      "nor a connection another device replaced")

        // The bytes ride along on a pending send, so a retry is the same message.
        let attachment = OutboundAttachment(name: "shot.png", mime: "image/png", data: Data([1, 2, 3]))
        let pending = PendingSend(id: "fixed", text: "look at this", attachments: [attachment],
                                  mode: .auto, status: .uncertain)
        checks.equal(pending.attachments.first?.data, Data([1, 2, 3]),
                     "an unconfirmed send keeps its attachment bytes")
        checks.equal(pending.attachmentInfo.first?.size, 3, "and can still describe them")
        await chat.retry(pending)
        let sends = channel.requests(ofType: "session.send")
        checks.equal(sends.first?.id, "fixed", "a retry reuses the original request id")
        checks.equal(sends.first?.json["attachments"]?.arrayValue?.count, 1,
                     "a retry carries the attachments the first attempt had")
    }

    /// Amendment A4 as the connection store sees it.
    @MainActor
    private static func closeHandling(_ checks: CheckRunner) async {
        // 4401 and 4403 end the session and return to the login screen.
        for reason in [SocketCloseReason.unauthorized, .forbidden] {
            let channel = ScriptedChannel()
            let store = ConnectionStore()
            await store.enterDemo(api: DemoGateway(), channel: channel)
            await settle { store.hasSnapshot }
            channel.close(reason)
            await settle { store.phase == .signedOut }
            checks.equal(store.phase, .signedOut, "\(reason) returns to the login screen")
            checks.expect(store.errorMessage?.isEmpty == false, "\(reason) explains itself")
        }

        // 4001 keeps the user signed in and waits for a deliberate reconnect.
        let channel = ScriptedChannel()
        let store = ConnectionStore()
        await store.enterDemo(api: DemoGateway(), channel: channel)
        await settle { store.hasSnapshot }
        channel.close(.replaced)
        await settle { store.phase == .superseded }
        checks.equal(store.phase, .superseded, "a replaced connection is its own state")
        checks.expect(store.isSignedIn, "a replaced connection does not sign the user out")
        checks.expect(store.devices.isEmpty == false, "a replaced connection keeps the last known lists")
    }

    @MainActor
    private static func connection(_ checks: CheckRunner) async {
        let gateway = DemoGateway()
        let store = ConnectionStore()
        await store.enterDemo(api: gateway, channel: gateway)
        await settle { store.hasSnapshot }
        checks.expect(store.hasSnapshot, "the demo hello arrives")
        checks.equal(store.phase, .connected, "the store reports a connected phase")
        checks.equal(store.devices.count, 3, "hello populates the device list")
        checks.equal(store.sessions.count, 8, "hello populates the session list")
        checks.equal(store.inventorySummary, "3 devices · 1 waiting", "the inventory summary counts waiting sessions")
        checks.equal(store.onlineDevices.count, 2, "only the online devices are offered for a new session")
        checks.expect(store.device(DemoFixtures.macDeviceID)?.agent("claude")?.supports(.takeover) == true,
                      "capabilities survive the hello round trip")
        await store.signOut()
        checks.equal(store.phase, .signedOut, "signing out clears the phase")
        checks.equal(store.devices.count, 0, "signing out clears the device list")
    }

    @MainActor
    private static func chat(_ checks: CheckRunner) async {
        let gateway = DemoGateway()
        let connection = ConnectionStore()
        await connection.enterDemo(api: gateway, channel: gateway)
        await settle { connection.hasSnapshot }
        guard let live = connection.session(deviceID: DemoFixtures.macDeviceID,
                                            sessionID: DemoFixtures.liveSessionID) else {
            checks.expect(false, "the demo live session exists")
            return
        }
        let chat = ChatStore(session: live, channel: gateway)
        connection.addFrameHandler("chat") { [weak chat] frame in chat?.receive(frame) }
        await chat.open()
        await settle { chat.timeline.entries.count > 5 }
        checks.expect(chat.timeline.entries.count >= 10, "history paints the transcript")
        checks.expect(chat.timeline.todos.count == 4, "the todos snapshot arrives with history")
        checks.equal(chat.session.todos?.total, 4,
                     "a snapshot from history is mirrored into the session summary")
        checks.expect(chat.statusLine?.contains("queued") == true, "a running session offers to queue")

        // A message sent during a turn queues rather than interrupting.
        chat.draft = "also add a retry to the token refresh path"
        await chat.send()
        await settle { chat.lastAcceptance != nil }
        checks.equal(chat.lastAcceptance, .queued, "sending during a turn queues the message")
        checks.equal(chat.pendingSends.count, 0, "an accepted message leaves the pending list")
        checks.expect(chat.unconfirmedSend == nil, "an accepted message is not shown as unconfirmed")
        checks.equal(chat.draft, "", "sending clears the draft")
        // The demo holds the message for a couple of seconds before injecting
        // it, so these waits have to outlast that on a loaded machine.
        await settle(timeout: 10) { chat.timeline.queue.count == 1 }
        checks.equal(chat.timeline.queue.count, 1, "the queue snapshot carries the queued message")

        // Streaming from the scripted turn lands in one block.
        await settle(timeout: 6) { chat.timeline.entry(id: "a-live")?.text.contains("passed") == true }
        checks.expect(chat.timeline.entry(id: "a-live")?.text.hasPrefix("All 100") == true,
                      "streamed deltas accumulate into one block")

        // A terminal-controlled session refuses a send instead of pretending.
        guard let readonly = connection.sessions.first(where: { $0.control == .terminal }) else {
            checks.expect(false, "the demo has a terminal-controlled session")
            return
        }
        let locked = ChatStore(session: readonly, channel: gateway)
        locked.agent = connection.device(readonly.deviceID)?.agent(readonly.agent)
        checks.expect(locked.isReadOnly, "a terminal-controlled session is read-only")
        checks.expect(locked.statusLine?.contains("Take over") == true, "the read-only status offers a takeover")
        locked.draft = "hello"
        checks.expect(!locked.canSend, "the composer is disabled while the terminal owns the session")

        await sharedSession(connection: connection, gateway: gateway, checks: checks)
        await codexSharedSession(connection: connection, gateway: gateway, checks: checks)

        // Approvals send only the option ids the device supplied.
        guard let waiting = connection.sessions.first(where: { $0.state == .needsApproval }) else {
            checks.expect(false, "the demo has a session waiting for approval")
            return
        }
        let approving = ChatStore(session: waiting, channel: gateway)
        connection.addFrameHandler("approving") { [weak approving] frame in approving?.receive(frame) }
        await approving.open()
        await settle { approving.timeline.pendingRequest != nil }
        guard let pending = approving.timeline.pendingRequest, let approval = pending.approval else {
            checks.expect(false, "the pending approval is visible")
            return
        }
        checks.equal(approval.options.count, 3, "every server option is offered")
        checks.equal(approval.primaryOption?.id, "approved", "the primary option is the device's own id")
        await approving.approve(requestID: approval.requestID, optionID: approval.primaryOption?.id ?? "")
        await settle { approving.timeline.pendingRequest == nil }
        checks.expect(approving.timeline.pendingRequest == nil, "an answered approval stops being actionable")

        connection.removeFrameHandler("chat")
        connection.removeFrameHandler("approving")
    }

    /// Amendment A10: an attached session types, queues and approves like a
    /// remote one, and the device reports what became of each message.
    @MainActor
    private static func sharedSession(connection: ConnectionStore, gateway: DemoGateway,
                                      checks: CheckRunner) async {
        guard let session = connection.sessions.first(where: {
            $0.sessionID == DemoFixtures.sharedSessionID
        }) else {
            checks.expect(false, "the demo has an attached Claude session")
            return
        }
        let chat = ChatStore(session: session, channel: gateway)
        chat.agent = connection.device(session.deviceID)?.agent(session.agent)
        connection.addFrameHandler("shared") { [weak chat] frame in chat?.receive(frame) }
        defer { connection.removeFrameHandler("shared") }
        await chat.open()
        await settle { chat.timeline.entries.count >= 3 }
        checks.expect(!chat.isReadOnly, "an attached session is not read-only")
        checks.expect(chat.isAttached, "and reports itself as attached")
        checks.expect(!chat.canTakeover, "takeover is never offered on an attached session")
        checks.equal(chat.statusLine, nil,
                     "an idle attached session prints nothing the header has not already said")
        checks.expect(!chat.allowsSettingsChanges,
                      "model, permission mode and effort belong to the terminal")
        checks.expect(!chat.allowsAttachments, "and attachments cannot reach a live CLI")

        chat.draft = "also mention the iOS app in the notes"
        await chat.send()
        // The demo holds the message for a couple of seconds before injecting
        // it, so these waits have to outlast that on a loaded machine.
        await settle(timeout: 10) { chat.timeline.queue.count == 1 }
        guard let held = chat.timeline.entries.last(where: { $0.userMessage?.delivery != nil }) else {
            checks.expect(false, "the held message is in the transcript")
            return
        }
        checks.equal(held.userMessage?.delivery, .pending, "a held message says it is waiting")
        checks.equal(chat.timeline.queue.count, 1, "and it is listed in the queue")
        checks.equal(chat.lastAcceptance, .queued,
                     "a held send is accepted with the ordinary queued acceptance")

        await settle(timeout: 10) { chat.timeline.entry(id: held.id)?.userMessage?.delivery == .delivered }
        checks.equal(chat.timeline.entry(id: held.id)?.userMessage?.delivery, .delivered,
                     "the replacement event marks the same block delivered")
        checks.expect(chat.timeline.queue.isEmpty, "and clears the queue")

        await settle(timeout: 10) { chat.timeline.pendingRequest != nil }
        guard let approval = chat.timeline.pendingRequest?.approval else {
            checks.expect(false, "the relayed approval arrives")
            return
        }
        checks.equal(approval.options.map(\.id), ["allow", "deny"],
                     "a relayed request offers exactly allow and deny")
        checks.expect(!chat.canStop, "a Claude channel cannot interrupt the turn it is attached to")
        await chat.approve(requestID: approval.requestID, optionID: "allow")
        await settle(timeout: 10) { chat.timeline.pendingRequest == nil }
        checks.expect(chat.timeline.pendingRequest == nil, "answering here resolves the relayed request")
    }

    /// Amendment A11: the same session shape on an attachment that carries the
    /// settings, the attachments and an interrupt. Nothing is dimmed, Stop is
    /// offered, and `session.set` reaches the live thread.
    @MainActor
    private static func codexSharedSession(connection: ConnectionStore, gateway: DemoGateway,
                                           checks: CheckRunner) async {
        guard let session = connection.sessions.first(where: {
            $0.sessionID == DemoFixtures.codexSharedSessionID
        }) else {
            checks.expect(false, "the demo has a shared Codex thread")
            return
        }
        let chat = ChatStore(session: session, channel: gateway)
        chat.agent = connection.device(session.deviceID)?.agent(session.agent)
        connection.addFrameHandler("codex-shared") { [weak chat] frame in chat?.receive(frame) }
        defer { connection.removeFrameHandler("codex-shared") }
        await chat.open()
        await settle { chat.timeline.entries.count >= 3 }

        checks.expect(chat.isAttached, "the daemon shares the thread with the terminal")
        checks.expect(chat.allowsSettingsChanges, "the pickers open because shared_settings is true")
        checks.expect(chat.allowsAttachments, "and the attachment button because shared_attachments is")
        checks.expect(chat.canStop, "a running shared thread offers Stop")
        checks.expect(!chat.canTakeover, "and still never a takeover")

        guard let approval = chat.timeline.pendingRequest?.approval else {
            checks.expect(false, "the daemon's request is in the transcript")
            return
        }
        checks.equal(approval.options.count, 4, "all four decisions are offered")
        checks.equal(approval.otherOptions.count, 2, "two of them stack between primary and danger")

        // Section 5's send modes: `auto` joins the running turn, `queue` waits.
        chat.draft = "also check the drawer's tests"
        await chat.send(mode: .auto)
        checks.equal(chat.lastAcceptance, .steered, "auto steers a running shared thread")

        // Amendment A14: the agent reads a steered message at its next step, so
        // the row waits at the foot of the transcript while the turn carries on,
        // and the device's block lands after the output that preceded it.
        checks.equal(chat.timeline.roots.last?.pending?.text, "also check the drawer's tests",
                     "a steered message waits at the foot rather than vanishing on acceptance")
        checks.expect(chat.timeline.roots.last?.pending?.isSteering == true,
                      "and never counts down towards an unconfirmed delivery")
        // The row is the last of these; whatever the turn says before the agent
        // takes the message pushes the block past where the row stands now.
        let rowsWhileWaiting = chat.timeline.roots.count
        await settle(timeout: 4) { chat.timeline.optimistic.isEmpty }
        checks.expect(chat.timeline.optimistic.isEmpty, "the block arrives when the agent takes it")
        let landed = chat.timeline.roots.firstIndex {
            $0.userMessage?.text == "also check the drawer's tests"
        }
        checks.equal(chat.timeline.roots.filter {
            $0.userMessage?.text == "also check the drawer's tests"
        }.count, 1, "exactly one copy of the steered message")
        checks.expect(landed.map { $0 >= rowsWhileWaiting } ?? false,
                      "and it sorts after the output the turn produced while it waited")

        chat.draft = "and then run the linter"
        await chat.send(mode: .queue)
        checks.equal(chat.lastAcceptance, .queued, "queue holds the message instead")

        // An option the block never offered is refused, `elsewhere` included.
        await chat.approve(requestID: approval.requestID,
                           optionID: ApprovalPayload.elsewhereOptionID)
        checks.expect(chat.errorMessage != nil, "elsewhere is a resolution, never a choice to send")
        chat.clearError()

        await chat.set(effort: "high")
        await settle { chat.session.effort == "high" }
        checks.equal(chat.session.effort, "high", "session.set retunes the shared thread")

        await chat.approve(requestID: approval.requestID, optionID: "allow_session")
        await settle(timeout: 10) { chat.timeline.pendingRequest == nil }
        checks.expect(chat.timeline.pendingRequest == nil, "and the request is answered from here")

        await chat.stop()
        await settle(timeout: 10) { !chat.isRunning }
        checks.expect(!chat.isRunning, "Stop interrupts the turn through the daemon")
    }

    @MainActor
    private static func sessionsList(_ checks: CheckRunner) {
        let defaults = UserDefaults(suiteName: "rc-verify-\(UUID().uuidString)")!
        let store = SessionStore(defaults: defaults)
        let sessions = DemoFixtures.sessions
        let devices = DemoFixtures.devices

        let groups = store.groups(sessions, devices: devices)
        checks.equal(groups.map(\.name), ["mac-studio-office", "macbook-air", "ci-runner-01"],
                     "a machine with live work comes first, then the rest by activity")
        checks.equal(groups.first?.active.count, 6, "the busy machine holds six live sessions")
        checks.equal(groups.first?.active.first?.state, .needsApproval,
                     "a session waiting on the user sorts first inside its device")
        checks.expect(groups.allSatisfy { !$0.collapsed }, "every group starts expanded")
        checks.expect(groups.first?.archive.isEmpty == true, "and the busy machine has nothing archived")

        guard let quiet = groups.last else { return checks.expect(false, "the third machine is listed") }
        checks.equal(quiet.active.count, 0, "the machine whose CLI exited holds nothing live")
        checks.equal(quiet.archive.first?.sessionID, DemoFixtures.doneSessionID,
                     "the session nothing owns sits in that machine's own Archive")
        checks.expect(!quiet.archiveExpanded, "which starts collapsed")

        store.toggleArchive(quiet.id)
        checks.expect(store.groups(sessions, devices: devices).last?.archiveExpanded == true,
                      "one tap opens it")
        checks.expect(SessionStore(defaults: defaults).expandedArchives.contains(quiet.id),
                      "and the choice outlives the launch")
        store.toggleArchive(quiet.id)

        store.toggleCollapsed(quiet.id)
        checks.expect(store.groups(sessions, devices: devices).last?.collapsed == true,
                      "a machine folds away on a tap")
        checks.expect(SessionStore(defaults: defaults).collapsedDevices == [quiet.id],
                      "and stays folded across a launch")
        store.toggleCollapsed(quiet.id)

        store.searchText = "vite"
        let byTitle = store.groups(sessions, devices: devices)
        checks.equal(byTitle.count, 1, "search drops a machine with no match entirely")
        checks.equal(byTitle.first?.active.count, 1, "and keeps the row it matched")
        store.searchText = "/work/api"
        let byPath = store.groups(sessions, devices: devices)
        checks.equal(byPath.first?.archive.count, 1, "search matches the working directory")
        checks.expect(byPath.first?.archiveExpanded == true,
                      "a match inside an Archive opens it, whatever the stored preference says")
        store.searchText = ""

        checks.equal(store.agentOptions(sessions), ["claude", "codex"],
                     "the filter offers the agents the list actually contains")
        store.agentFilter = "codex"
        let codexOnly = store.groups(sessions, devices: devices)
        checks.equal(codexOnly.map(\.name), ["mac-studio-office", "ci-runner-01"],
                     "an agent filter removes a machine whose sessions all drop out")
        checks.expect(codexOnly.allSatisfy { group in
            (group.active + group.archive).allSatisfy { $0.agent == "codex" }
        }, "and leaves only that agent's sessions behind")
        store.agentFilter = nil

        let archivedByHand = Session(sessionID: "s", deviceID: DemoFixtures.macDeviceID,
                                     agent: "claude", title: "Old", cwd: "/tmp",
                                     control: .remote, updatedAt: DemoFixtures.now, archived: true)
        checks.expect(SessionListLayout.isArchived(archivedByHand),
                      "a hand-archived session belongs to its device's Archive")
        let withArchived = store.groups(sessions + [archivedByHand], devices: devices)
        checks.equal(withArchived.first?.archive.map(\.sessionID), ["s"],
                     "it joins that machine's Archive rather than a global one")
        checks.equal(withArchived.first?.active.count, 6,
                     "and never counts as live, whatever still owns it")

        checks.equal(SessionListLayout.urgency(.needsInput), 0, "waiting on the user comes first")
        checks.equal(SessionListLayout.urgency(.starting), 1, "a starting agent counts as working")
        checks.equal(SessionListLayout.urgency(.stopped), 2, "everything at rest comes last")

        checks.equal(RelativeTime.short(since: DemoFixtures.now - 240_000), "4m", "relative minutes")
        checks.equal(RelativeTime.short(since: DemoFixtures.now - 10_800_000), "3h", "relative hours")
        checks.equal(RelativeTime.duration(milliseconds: 6_400), "6.4s", "sub-minute durations")
        checks.equal(RelativeTime.duration(milliseconds: 72_000), "1m 12s", "minute durations")
        checks.equal(RelativeTime.compactCount(48_200), "48.2k", "compact token counts")
    }

    @MainActor
    private static func settings(_ checks: CheckRunner) {
        let defaults = UserDefaults(suiteName: "rc-verify-\(UUID().uuidString)")!
        let store = SettingsStore(defaults: defaults)
        checks.equal(store.voiceBackend, .onDevice, "voice defaults to on-device recognition")
        store.remember(origin: "https://rc.example.com", username: "admin")
        let reloaded = SettingsStore(defaults: defaults)
        checks.equal(reloaded.lastOrigin, "https://rc.example.com", "the last origin is remembered")

        let report = store.diagnosticReport(appVersion: "0.1.0", platform: "iOS", osVersion: "18.0",
                                            phase: .connected, deviceCount: 2, sessionCount: 4,
                                            sttEnabled: true, isDemo: false)
        checks.expect(report.contains("Protocol: v1"), "the report names the protocol version")
        checks.expect(report.contains("Cache schema: v1"), "the report names the cache schema")
        checks.expect(!report.contains("rc.example.com"), "the report never contains the gateway address")
        checks.expect(!report.contains("admin"), "the report never contains the account name")
    }

    @MainActor
    private static func pairing(_ checks: CheckRunner) async {
        let gateway = DemoGateway()
        let flow = PairingFlow(api: gateway)
        await flow.begin()
        checks.equal(flow.code, "RC-7K42-QX9M", "the pairing code is shown verbatim")
        checks.expect(flow.command.contains("--pair RC-7K42-QX9M"), "the install one-liner carries the code")
        flow.platform = .linux
        checks.expect(flow.command.contains("install.sh"), "the Linux command is available too")
        checks.equal(flow.steps.count, 4, "the checklist has four steps")
        checks.expect(flow.steps[1].done == false, "later steps start incomplete")
        flow.receive(.pairingProgress(PairingProgress(code: flow.code, step: .online)))
        checks.expect(flow.steps[2].done, "progress lights up the matching step")
        flow.receive(.pairingProgress(PairingProgress(code: "RC-OTHER-CODE", step: .agents)))
        checks.expect(!flow.steps[3].done, "progress for a different code is ignored")
        checks.expect(!flow.expiry().isEmpty, "the code shows a countdown")
    }

    /// A channel that serves one hello, records every request, and closes or
    /// emits frames on command.
    @MainActor
    final class ScriptedChannel: GatewayChannel {
        nonisolated let events: AsyncStream<GatewayEvent>
        private let continuation: AsyncStream<GatewayEvent>.Continuation
        private var recorded: [GatewayRequest] = []
        var subscribeReply: SubscribeResult?
        var historyReply: HistoryResult?

        init() {
            let stream = AsyncStream<GatewayEvent>.makeStream(bufferingPolicy: .bufferingOldest(64))
            events = stream.stream
            continuation = stream.continuation
        }

        var requests: [GatewayRequest] { recorded }

        func requests(ofType type: String) -> [GatewayRequest] { requests.filter { $0.type == type } }

        func connect() async {
            continuation.yield(.state(.connected))
            emitHello()
        }

        func emitHello() {
            continuation.yield(.frame(.hello(HelloFrame(
                protocolVersion: RemoteProtocol.version, gatewayVersion: "test",
                user: UserIdentity(username: "admin"), devices: DemoFixtures.devices,
                sessions: DemoFixtures.sessions, stt: .disabled, serverTime: 0))))
        }

        func emit(_ frame: AppFrame) { continuation.yield(.frame(frame)) }

        func disconnect() async { continuation.yield(.state(.disconnected)) }

        func close(_ reason: SocketCloseReason) {
            if reason == .unauthorized { continuation.yield(.state(.unauthorized)) }
            continuation.yield(.closed(reason))
        }

        @discardableResult
        func request(_ request: GatewayRequest) async throws -> JSONValue {
            recorded.append(request)
            switch request.type {
            case "session.subscribe":
                guard let subscribeReply else { return .object([:]) }
                return try JSONValue.encode(subscribeReply)
            case "session.history":
                return try JSONValue.encode(historyReply ?? HistoryResult(events: [], hasMore: false))
            default:
                return .object([:])
            }
        }
    }

    /// Amendment A12: the message is in the transcript before the request has
    /// been answered, and the device's echo under the same id replaces it
    /// rather than adding a second copy.
    @MainActor
    private static func optimisticSend(_ checks: CheckRunner) async {
        let gateway = DemoGateway()
        let connection = ConnectionStore()
        await connection.enterDemo(api: gateway, channel: gateway)
        await settle { connection.hasSnapshot }
        guard let session = connection.sessions.first(where: {
            $0.sessionID == DemoFixtures.erroredSessionID
        }) else {
            return checks.expect(false, "the demo has a reachable session with no turn running")
        }
        let chat = ChatStore(session: session, channel: gateway)
        connection.addFrameHandler("a12") { [weak chat] frame in chat?.receive(frame) }
        defer { connection.removeFrameHandler("a12") }
        await chat.open()

        chat.draft = "one more thing"
        let sending = Task { await chat.send() }
        await settle(timeout: 2) { chat.timeline.roots.contains { $0.pending != nil } }
        checks.equal(chat.timeline.roots.last?.pending?.text, "one more thing",
                     "the message is on screen before the request has been answered")
        checks.expect(chat.draft.isEmpty, "and the field is already empty")
        await sending.value
        checks.equal(chat.lastAcceptance, .sent, "the demo accepts it outright")
        checks.expect(!chat.timeline.optimistic.isEmpty,
                      "and the row waits for the device's own event, not for the reply")

        await settle(timeout: 4) { chat.timeline.optimistic.isEmpty }
        checks.expect(chat.timeline.optimistic.isEmpty, "the echo retires the row")
        checks.equal(chat.timeline.roots.filter { $0.userMessage?.text == "one more thing" }.count, 1,
                     "and the transcript holds exactly one copy of the message")
    }

    /// Wait for an asynchronous condition without a fixed sleep.
    @MainActor
    private static func settle(timeout: TimeInterval = 3, _ condition: () -> Bool) async {
        let deadline = Date().addingTimeInterval(timeout)
        while !condition(), Date() < deadline {
            try? await Task.sleep(for: .milliseconds(20))
        }
    }
}
