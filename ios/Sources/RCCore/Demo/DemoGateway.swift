import Foundation

/// An in-memory gateway used by `--demo`, by previews and by the UI test.
///
/// It never constructs a transport, so the demo cannot reach the network even
/// by accident, and every frame it emits is a real protocol frame decoded by
/// the same code path as a live gateway.
public actor DemoGateway: GatewayChannel, GatewayAPI {
    public nonisolated let events: AsyncStream<GatewayEvent>
    public nonisolated let endpoint: GatewayEndpoint

    private let continuation: AsyncStream<GatewayEvent>.Continuation
    private var devices = DemoFixtures.devices
    private var sessionList = DemoFixtures.sessions
    private var transcripts: [String: [SessionEvent]] = [:]
    private var cursors: [String: Int] = [:]
    private var scripted: Task<Void, Never>?
    private var pairing: Task<Void, Never>?
    /// The injection script for the attached session runs on its own task, so
    /// it neither cancels nor is cancelled by the live turn.
    private var injecting: Task<Void, Never>?

    public init() {
        endpoint = (try? GatewayEndpoint("https://demo.remote-control.invalid"))
            ?? GatewayEndpoint.placeholder
        let stream = AsyncStream<GatewayEvent>.makeStream(bufferingPolicy: .bufferingOldest(512))
        events = stream.stream
        continuation = stream.continuation
        for session in DemoFixtures.sessions {
            let history = DemoFixtures.history(for: session.sessionID)
            transcripts[session.sessionID] = history
            cursors[session.sessionID] = history.last?.seq ?? 0
        }
    }

    // MARK: - GatewayChannel

    public func connect() async {
        continuation.yield(.state(.connecting))
        for index in sessionList.indices {
            sessionList[index].lastSeq = cursors[sessionList[index].sessionID] ?? 0
        }
        let hello = HelloFrame(protocolVersion: RemoteProtocol.version, gatewayVersion: "0.1.0-demo",
                               user: UserIdentity(username: "demo"), devices: devices,
                               sessions: sessionList, stt: DemoFixtures.config.stt,
                               serverTime: DemoFixtures.now)
        continuation.yield(.state(.connected))
        continuation.yield(.frame(.hello(hello)))
    }

    public func disconnect() async {
        scripted?.cancel(); scripted = nil
        pairing?.cancel(); pairing = nil
        injecting?.cancel(); injecting = nil
        continuation.yield(.state(.disconnected))
    }

    @discardableResult
    public func request(_ request: GatewayRequest) async throws -> JSONValue {
        switch request.type {
        case "session.subscribe":
            return try subscribe(request)
        case "session.history":
            return try history(request)
        case "session.send":
            return try await send(request)
        case "session.approve":
            return try resolveApproval(request)
        case "session.answer":
            return .object([:])
        case "session.stop":
            return try stop(request)
        case "session.set":
            return try applySet(request)
        case "session.takeover":
            return try takeover(request)
        case "session.archive":
            return try archive(request)
        case "session.create":
            return try create(request)
        case "session.block":
            return try fullBlock(request)
        case "device.dirs":
            return try JSONValue.encode(DemoFixtures.directoryListing)
        case "device.git":
            return try JSONValue.encode(GitStatus(isRepo: true, branch: "main", dirty: false, ahead: 0, behind: 0))
        case "device.agents":
            return try JSONValue.encode(AgentsResult(agents: [DemoFixtures.claude, DemoFixtures.codex]))
        default:
            return .object([:])
        }
    }

    // MARK: - GatewayAPI

    public func login(password: String, username: String?) async throws -> LoginResponse {
        LoginResponse(token: "demo", exp: DemoFixtures.now + 86_400_000, user: UserIdentity(username: "demo"))
    }
    public func session() async throws -> SessionInfoResponse {
        SessionInfoResponse(user: UserIdentity(username: "demo"), exp: DemoFixtures.now + 86_400_000)
    }
    public func logout() async throws {}
    public func config() async throws -> GatewayConfig { DemoFixtures.config }
    public func devices() async throws -> [Device] { devices }
    public func renameDevice(_ deviceID: String, name: String) async throws -> Device {
        guard let index = devices.firstIndex(where: { $0.deviceID == deviceID }) else {
            throw GatewayErrorBody(code: .notFound, message: "No such device")
        }
        let old = devices[index]
        let renamed = Device(deviceID: old.deviceID, name: name, platform: old.platform, hostname: old.hostname,
                             arch: old.arch, clientVersion: old.clientVersion, online: old.online,
                             lastSeen: old.lastSeen, createdAt: old.createdAt, latencyMS: old.latencyMS,
                             agents: old.agents)
        devices[index] = renamed
        continuation.yield(.frame(.deviceUpdated(renamed)))
        return renamed
    }
    public func revokeDevice(_ deviceID: String) async throws {
        devices.removeAll { $0.deviceID == deviceID }
        sessionList.removeAll { $0.deviceID == deviceID }
        continuation.yield(.frame(.deviceRemoved(deviceID: deviceID)))
    }
    public func beginPairing() async throws -> PairingGrant {
        let grant = DemoFixtures.pairingGrant
        pairing?.cancel()
        pairing = Task { [weak self] in await self?.runPairingScript(code: grant.code) }
        return grant
    }
    public func cancelPairing(code: String) async throws { pairing?.cancel(); pairing = nil }
    public func sessions(deviceID: String?, archived: Bool?) async throws -> [Session] {
        sessionList.filter { deviceID == nil || $0.deviceID == deviceID }
    }
    public func registerPush(_ registration: APNSRegistration) async throws {}
    public func unregisterPush(token: String) async throws {}
    public func restoreToken(username: String) async -> Bool { true }
    public func bearerToken() async -> String? { "demo" }
    public func forgetToken(username: String) async {}

    // MARK: - Request handling

    private func requireSessionID(_ request: GatewayRequest) throws -> String {
        guard let id = request.body["session_id"]?.stringValue else {
            throw GatewayErrorBody(code: .badRequest, message: "session_id is required")
        }
        return id
    }

    private func session(_ id: String) throws -> Session {
        guard let session = sessionList.first(where: { $0.sessionID == id }) else {
            throw GatewayErrorBody(code: .notFound, message: "No such session")
        }
        return session
    }

    /// What the device says the agent running this session can do. Amendments
    /// A10 and A11 answer every "may the app do this while attached?" from
    /// here rather than from the agent id.
    private func agent(for session: Session) -> AgentInfo? {
        devices.first { $0.deviceID == session.deviceID }?.agent(session.agent)
    }

    private func subscribe(_ request: GatewayRequest) throws -> JSONValue {
        let id = try requireSessionID(request)
        let session = try session(id)
        let since = request.body["since_seq"]?.intValue
        let all: [SessionEvent] = transcripts[id] ?? []
        let events = since.map { cursor in all.filter { $0.seq > cursor } } ?? []
        if id == DemoFixtures.liveSessionID { startLiveScript(sessionID: id) }
        return try JSONValue.encode(SubscribeResult(session: session, events: events, resync: false))
    }

    private func history(_ request: GatewayRequest) throws -> JSONValue {
        let id = try requireSessionID(request)
        let before = request.body["before_seq"]?.intValue
        let all: [SessionEvent] = transcripts[id] ?? []
        let page = before.map { cursor in all.filter { $0.seq < cursor } } ?? all
        return try JSONValue.encode(HistoryResult(events: page, hasMore: false))
    }

    private func fullBlock(_ request: GatewayRequest) throws -> JSONValue {
        let id = try requireSessionID(request)
        let history: [SessionEvent] = transcripts[id] ?? []
        guard let blockID = request.body["block_id"]?.stringValue,
              let event = history.last(where: { $0.blockID == blockID }) else {
            throw GatewayErrorBody(code: .notFound, message: "No such block")
        }
        return try JSONValue.encode(BlockResult(event: event))
    }

    private func send(_ request: GatewayRequest) async throws -> JSONValue {
        let id = try requireSessionID(request)
        let session = try session(id)
        guard !session.isControlledByTerminal else {
            throw GatewayErrorBody(code: .conflict, message: "Controlled by the terminal; take over first.")
        }
        let text = request.body["text"]?.stringValue ?? ""
        let mode = SendMode(rawValue: request.body["mode"]?.stringValue ?? SendMode.auto.rawValue)
        if session.isAttached {
            return try sendShared(session: session, requestID: request.id, text: text, mode: mode)
        }
        let running = session.state.isWorking
        emit(sessionID: id, body: .userMessage(UserMessagePayload(text: text,
                                                                  source: running ? .queue : .remote)))
        if running {
            emit(sessionID: id, body: .queue(QueuePayload(pending: [
                QueuedMessage(id: request.id, text: text, ts: DemoFixtures.now)
            ])))
            return try JSONValue.encode(SendResult(accepted: .queued, queuedID: request.id))
        }
        update(sessionID: id) { $0.state = .running; $0.turn = TurnMarker(turnID: UUID().uuidString,
                                                                          startedAt: DemoFixtures.now) }
        startReplyScript(sessionID: id)
        return try JSONValue.encode(SendResult(accepted: .sent))
    }

    /// A message for an attached session takes the route the attachment
    /// supports. A channel can only hand keystrokes to a CLI, so it holds the
    /// message and injects it (amendment A10). A daemon is a real client of the
    /// agent's own server, so it starts, steers or interrupts the thread the
    /// terminal is on (amendment A11). Only the device branches on this; the
    /// apps read the acceptance and the agent's capabilities.
    private func sendShared(session: Session, requestID: String, text: String,
                            mode: SendMode) throws -> JSONValue {
        let agent = agent(for: session)
        guard agent?.attach == .daemon else {
            return try inject(sessionID: session.sessionID, requestID: requestID, text: text)
        }
        let id = session.sessionID
        guard session.state.isWorking else {
            emit(sessionID: id, body: .userMessage(UserMessagePayload(text: text, source: .remote)))
            update(sessionID: id) { session in
                session.state = .running
                session.turn = TurnMarker(turnID: UUID().uuidString, startedAt: DemoFixtures.now)
            }
            startReplyScript(sessionID: id)
            return try JSONValue.encode(SendResult(accepted: .sent))
        }
        switch mode {
        case .interrupt:
            emit(sessionID: id, body: .turnCompleted(TurnCompletedPayload(
                turnID: session.turn?.turnID ?? "demo-turn", stopReason: .interrupted, durationMS: 4_000)))
            emit(sessionID: id, body: .userMessage(UserMessagePayload(text: text, source: .remote)))
            update(sessionID: id) { session in
                session.turn = TurnMarker(turnID: UUID().uuidString, startedAt: DemoFixtures.now)
            }
            startReplyScript(sessionID: id)
            return try JSONValue.encode(SendResult(accepted: .sent))
        case .auto where agent?.supports(.steer) == true:
            // The message joins the turn that is already running, so it needs
            // neither a queue entry nor a delivery state.
            emit(sessionID: id, body: .userMessage(UserMessagePayload(text: text, source: .remote)))
            return try JSONValue.encode(SendResult(accepted: .steered))
        default:
            return try inject(sessionID: id, requestID: requestID, text: text)
        }
    }

    /// Amendment A10: a message a channel cannot deliver yet is held by the
    /// device and injected when the terminal is next idle, so the app first
    /// sees it as `pending` and then the same block again as `delivered`.
    private func inject(sessionID: String, requestID: String, text: String) throws -> JSONValue {
        let blockID = "shared-\(requestID)"
        emit(sessionID: sessionID, blockID: blockID,
             body: .userMessage(UserMessagePayload(text: text, source: .remote, delivery: .pending)))
        emit(sessionID: sessionID, body: .queue(QueuePayload(pending: [
            QueuedMessage(id: requestID, text: text, ts: DemoFixtures.now)
        ])))
        injecting?.cancel()
        // A turn that is already running asks for its own permission; only an
        // idle thread reaches the request this script plays.
        let asksForApproval = try !session(sessionID).state.isWorking
        injecting = Task { [weak self] in
            await self?.playInjection(sessionID: sessionID, blockID: blockID, text: text,
                                      asksForApproval: asksForApproval)
        }
        return try JSONValue.encode(SendResult(accepted: .queued, queuedID: requestID))
    }

    private func playInjection(sessionID: String, blockID: String, text: String,
                               asksForApproval: Bool) async {
        try? await Task.sleep(for: .milliseconds(2_400))
        guard !Task.isCancelled else { return }
        emit(sessionID: sessionID, blockID: blockID,
             body: .userMessage(UserMessagePayload(text: text, source: .remote, delivery: .delivered)))
        emit(sessionID: sessionID, body: .queue(QueuePayload(pending: [])))
        emit(sessionID: sessionID, body: .status(StatusPayload(state: .running)))
        update(sessionID: sessionID) { session in
            session.state = .running
            session.turn = TurnMarker(turnID: "demo-turn-shared", startedAt: DemoFixtures.now)
            session.queued = 0
        }
        try? await Task.sleep(for: .milliseconds(900))
        guard !Task.isCancelled, asksForApproval else { return }
        emit(sessionID: sessionID, blockID: "ap-shared", body: .approval(ApprovalPayload(
            requestID: "demo-approval-shared", tool: "Bash", kind: .shell,
            title: "git commit -am 'Draft 0.1.0 release notes'",
            input: ["tool_name": "Bash",
                    "description": "Commit the drafted release notes",
                    "input_preview": "git commit -am 'Draft 0.1.0 release notes'"],
            options: [ApprovalOption(id: "allow", label: "Allow", style: .primary),
                      ApprovalOption(id: "deny", label: "Deny", style: .danger)],
            status: .pending)))
        emit(sessionID: sessionID, body: .status(StatusPayload(state: .needsApproval)))
        update(sessionID: sessionID) { $0.state = .needsApproval }
    }

    private func stop(_ request: GatewayRequest) throws -> JSONValue {
        let id = try requireSessionID(request)
        // Amendment A10: a Claude channel cannot interrupt a running turn.
        // Amendment A11: the Codex daemon relays `turn/interrupt`, and says so.
        let existing = try session(id)
        if existing.isAttached, agent(for: existing)?.sharedInterrupt != true {
            throw GatewayErrorBody(code: .unsupported, message: "Stop it in the terminal.")
        }
        scripted?.cancel(); scripted = nil
        emit(sessionID: id, body: .turnCompleted(TurnCompletedPayload(turnID: "demo-turn",
                                                                      stopReason: .interrupted, durationMS: 4_000)))
        emit(sessionID: id, body: .status(StatusPayload(state: .idle)))
        update(sessionID: id) { $0.state = .idle; $0.turn = nil }
        return .object([:])
    }

    private func resolveApproval(_ request: GatewayRequest) throws -> JSONValue {
        let id = try requireSessionID(request)
        let history: [SessionEvent] = transcripts[id] ?? []
        guard let requestID = request.body["request_id"]?.stringValue,
              let optionID = request.body["option_id"]?.stringValue,
              let pending = history.last(where: { $0.approval?.requestID == requestID }),
              let approval = pending.approval else {
            throw GatewayErrorBody(code: .notFound, message: "That request is no longer open.")
        }
        // Amendment A11: `elsewhere` is a resolution, never a choice. Any id the
        // block did not offer is refused rather than relayed.
        guard approval.options.contains(where: { $0.id == optionID }) else {
            throw GatewayErrorBody(code: .badRequest, message: "That request did not offer that option.")
        }
        emit(sessionID: id, blockID: pending.blockID,
             body: .approval(ApprovalPayload(requestID: approval.requestID, tool: approval.tool,
                                             kind: approval.kind, title: approval.title,
                                             input: approval.input, diff: approval.diff,
                                             options: approval.options, status: .resolved,
                                             decision: ApprovalDecision(optionID: optionID, by: .remote))))
        emit(sessionID: id, body: .status(StatusPayload(state: .running)))
        update(sessionID: id) { $0.state = .running }
        if (try? session(id))?.isAttached == true { startReplyScript(sessionID: id) }
        return .object([:])
    }

    private func applySet(_ request: GatewayRequest) throws -> JSONValue {
        let id = try requireSessionID(request)
        // Amendment A10: only the title is ours to change on an attached
        // session, unless amendment A11's `shared_settings` says the
        // attachment retunes the live thread.
        let existing = try session(id)
        let retunes = request.body["model"] != nil || request.body["permission_mode"] != nil
            || request.body["effort"] != nil
        if existing.isAttached, retunes, agent(for: existing)?.sharedSettings != true {
            throw GatewayErrorBody(code: .unsupported, message: "Change it in the terminal.")
        }
        update(sessionID: id) { session in
            if let model = request.body["model"]?.stringValue { session.model = model }
            if let mode = request.body["permission_mode"]?.stringValue { session.permissionMode = mode }
            if let effort = request.body["effort"]?.stringValue { session.effort = effort }
            if let title = request.body["title"]?.stringValue { session.title = title }
        }
        return try JSONValue.encode(SessionResult(session: try session(id)))
    }

    private func takeover(_ request: GatewayRequest) throws -> JSONValue {
        let id = try requireSessionID(request)
        // Amendment A10: an attached session is already under joint control.
        guard try !session(id).isAttached else {
            throw GatewayErrorBody(code: .conflict, message: "Already attached.")
        }
        update(sessionID: id) { $0.control = .remote; $0.state = .idle }
        emit(sessionID: id, body: .notice(NoticePayload(level: .info, text: "You took over from the terminal.")))
        return try JSONValue.encode(SessionResult(session: try session(id)))
    }

    private func archive(_ request: GatewayRequest) throws -> JSONValue {
        let id = try requireSessionID(request)
        let archived = request.body["archived"]?.boolValue ?? true
        update(sessionID: id) { $0.archived = archived }
        return try JSONValue.encode(SessionResult(session: try session(id)))
    }

    private func create(_ request: GatewayRequest) throws -> JSONValue {
        let session = Session(
            sessionID: "demo-\(UUID().uuidString.prefix(8))",
            deviceID: request.body["device_id"]?.stringValue ?? DemoFixtures.macDeviceID,
            agent: request.body["agent"]?.stringValue ?? "claude",
            title: request.body["title"]?.stringValue ?? "New session",
            cwd: request.body["cwd"]?.stringValue ?? "/Users/me/dev",
            git: GitInfo(branch: "main"), state: .idle, origin: .remote, control: .remote,
            model: request.body["model"]?.stringValue,
            permissionMode: request.body["permission_mode"]?.stringValue,
            effort: request.body["effort"]?.stringValue,
            createdAt: DemoFixtures.now, updatedAt: DemoFixtures.now)
        sessionList.insert(session, at: 0)
        transcripts[session.sessionID] = []
        cursors[session.sessionID] = 0
        continuation.yield(.frame(.sessionUpdated(session)))
        return try JSONValue.encode(SessionResult(session: session))
    }

    // MARK: - Scripts

    /// The live session keeps producing output, so the demo shows a real turn.
    private func startLiveScript(sessionID: String) {
        guard scripted == nil else { return }
        scripted = Task { [weak self] in
            guard let self else { return }
            try? await Task.sleep(for: .milliseconds(700))
            await self.playLiveTurn(sessionID: sessionID)
        }
    }

    private func playLiveTurn(sessionID: String) async {
        let words = ["All", " 100", " runs", " passed.", " The", " shared", " clock", " was", " the",
                     " only", " source", " of", " the", " flake."]
        emit(sessionID: sessionID, blockID: "tool-5",
             body: .toolCall(ToolCallPayload(tool: "Bash", kind: .shell,
                                             title: "pytest tests/test_auth.py --count 100 -q",
                                             status: .succeeded,
                                             output: "..................................... 100%\n100 passed in 52.4s",
                                             durationMS: 52_400)))
        for (index, word) in words.enumerated() {
            guard !Task.isCancelled else { return }
            try? await Task.sleep(for: .milliseconds(90))
            emit(sessionID: sessionID, blockID: "a-live",
                 body: .assistantText(StreamTextPayload(delta: word, done: false)))
            if index == words.count - 1 {
                emit(sessionID: sessionID, blockID: "a-live",
                     body: .assistantText(StreamTextPayload(text: words.joined(), done: true)))
            }
        }
        emit(sessionID: sessionID, body: .todos(TodosPayload(items: [
            TodoItem(id: "1", text: "Reproduce the flake", status: .completed),
            TodoItem(id: "2", text: "Isolate the shared clock", status: .completed),
            TodoItem(id: "3", text: "Guard refresh with the session lock", status: .completed),
            TodoItem(id: "4", text: "Re-run the suite 100 times", status: .completed)
        ])))
        emit(sessionID: sessionID,
             body: .turnCompleted(TurnCompletedPayload(turnID: "demo-turn-1", stopReason: .completed,
                                                       durationMS: 252_000)))
        emit(sessionID: sessionID, body: .status(StatusPayload(state: .idle)))
        update(sessionID: sessionID) { $0.state = .idle; $0.turn = nil; $0.todos = TodoCounts(total: 4, done: 4) }
    }

    private func startReplyScript(sessionID: String) {
        scripted?.cancel()
        scripted = Task { [weak self] in
            guard let self else { return }
            let blockID = "a-\(UUID().uuidString.prefix(6))"
            for word in ["Got", " it", " —", " running", " that", " now."] {
                guard !Task.isCancelled else { return }
                try? await Task.sleep(for: .milliseconds(110))
                await self.emit(sessionID: sessionID, blockID: blockID,
                                body: .assistantText(StreamTextPayload(delta: word, done: false)))
            }
            await self.emit(sessionID: sessionID, blockID: blockID,
                            body: .assistantText(StreamTextPayload(text: "Got it — running that now.", done: true)))
            await self.emit(sessionID: sessionID,
                            body: .turnCompleted(TurnCompletedPayload(turnID: "demo-turn",
                                                                      stopReason: .completed, durationMS: 900)))
            await self.emit(sessionID: sessionID, body: .status(StatusPayload(state: .idle)))
            await self.finishTurn(sessionID: sessionID)
        }
    }

    private func finishTurn(sessionID: String) {
        update(sessionID: sessionID) { $0.state = .idle; $0.turn = nil }
    }

    private func runPairingScript(code: String) async {
        for step in [PairingStep.waiting, .enrolled, .online, .agents] {
            guard !Task.isCancelled else { return }
            try? await Task.sleep(for: .milliseconds(900))
            let device = step == .agents ? devices.first : nil
            continuation.yield(.frame(.pairingProgress(PairingProgress(code: code, step: step, device: device))))
        }
    }

    // MARK: - Emission

    private func emit(sessionID: String, blockID: String? = nil, body: SessionEventBody) {
        let seq = (cursors[sessionID] ?? 0) + 1
        cursors[sessionID] = seq
        let kind = Self.kind(of: body)
        let event = SessionEvent(seq: seq, ts: DemoFixtures.now, kind: kind,
                                 blockID: blockID ?? Self.implicitBlockID(body), body: body)
        var history: [SessionEvent] = transcripts[sessionID] ?? []
        history.append(event)
        transcripts[sessionID] = history
        let deviceID = sessionList.first { $0.sessionID == sessionID }?.deviceID
        continuation.yield(.frame(.sessionEvent(sessionID: sessionID, deviceID: deviceID, event: event)))
    }

    private func update(sessionID: String, _ mutate: (inout Session) -> Void) {
        guard let index = sessionList.firstIndex(where: { $0.sessionID == sessionID }) else { return }
        mutate(&sessionList[index])
        sessionList[index].updatedAt = DemoFixtures.now
        continuation.yield(.frame(.sessionUpdated(sessionList[index])))
    }

    private static func implicitBlockID(_ body: SessionEventBody) -> String? {
        switch body {
        case .userMessage, .assistantText, .thinking, .toolCall, .approval, .question, .error:
            "demo-\(UUID().uuidString.prefix(8))"
        default:
            nil
        }
    }

    private static func kind(of body: SessionEventBody) -> String {
        switch body {
        case .userMessage: SessionEvent.userMessageKind
        case .assistantText: SessionEvent.assistantTextKind
        case .thinking: SessionEvent.thinkingKind
        case .toolCall: SessionEvent.toolCallKind
        case .todos: SessionEvent.todosKind
        case .approval: SessionEvent.approvalKind
        case .question: SessionEvent.questionKind
        case .turnStarted: SessionEvent.turnStartedKind
        case .turnCompleted: SessionEvent.turnCompletedKind
        case .status: SessionEvent.statusKind
        case .meta: SessionEvent.metaKind
        case .queue: SessionEvent.queueKind
        case .notice: SessionEvent.noticeKind
        case .error: SessionEvent.errorKind
        case .unknown(let kind, _): kind
        }
    }
}
