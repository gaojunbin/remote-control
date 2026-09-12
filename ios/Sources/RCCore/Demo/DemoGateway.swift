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
    /// How long this device takes to report a message it was sent. A real one
    /// is a round trip away and the app is meant to look the same either way,
    /// so the demo keeps that moment rather than hiding it (amendment A12).
    private let echoDelay: Duration
    /// Amendment A15: when the archived demo session is resumed, or nil to
    /// leave it in the Archive for the whole run.
    private let resumeDelay: Duration?
    private var devices = DemoFixtures.devices
    private var sessionList = DemoFixtures.sessions
    private var transcripts: [String: [SessionEvent]] = [:]
    private var cursors: [String: Int] = [:]
    private var scripted: Task<Void, Never>?
    private var pairing: Task<Void, Never>?
    /// The injection script for the attached session runs on its own task, so
    /// it neither cancels nor is cancelled by the live turn.
    private var injecting: Task<Void, Never>?
    /// Amendment A12: the delayed echo of a message the app sent, which is what
    /// gives the demo the same "sending" moment a real device does.
    private var echoing: Task<Void, Never>?
    /// Amendment A14: the step at which the agent reads a message steered into
    /// a running turn, which is when the block for it is emitted.
    private var steering: Task<Void, Never>?
    /// Amendment A15: the moment the archived demo session is resumed from its
    /// terminal, which is when the device clears `archived` and publishes it.
    private var reviving: Task<Void, Never>?
    /// Amendment A17: the moment the terminal switches model on the attached
    /// session, which the device reads from the transcript and publishes.
    private var retuning: Task<Void, Never>?

    /// The default is what a quick local device feels like. A UI test asks for
    /// a longer one so the state a real send passes through can be looked at
    /// rather than raced.
    public static let defaultEchoDelay = Duration.milliseconds(400)
    /// Amendment A15: how long the archived session sits in the Archive before
    /// its terminal resumes it. A UI test asks for none, so the list it
    /// measures holds still.
    public static let defaultResumeDelay = Duration.seconds(5)
    /// Amendment A17: how long after the attached session is opened its
    /// terminal switches model. Short enough to be seen without waiting for it.
    private static let retuneDelay = Duration.milliseconds(700)

    public init(echoDelay: Duration = DemoGateway.defaultEchoDelay,
                resumeDelay: Duration? = DemoGateway.defaultResumeDelay) {
        self.echoDelay = echoDelay
        self.resumeDelay = resumeDelay
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
        reviving?.cancel()
        reviving = Task { [weak self] in await self?.resumeArchivedSession() }
    }

    public func disconnect() async {
        scripted?.cancel(); scripted = nil
        pairing?.cancel(); pairing = nil
        injecting?.cancel(); injecting = nil
        reviving?.cancel(); reviving = nil
        retuning?.cancel(); retuning = nil
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
        if id == DemoFixtures.sharedSessionID { startRetuneScript(sessionID: id) }
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
        // Amendment A12: the device echoes the request id as the block id, so
        // the app's own copy is replaced in place rather than duplicated.
        if running {
            emit(sessionID: id, blockID: request.id,
                 body: .userMessage(UserMessagePayload(text: text, source: .queue)))
            emit(sessionID: id, body: .queue(QueuePayload(pending: [
                QueuedMessage(id: request.id, text: text, ts: DemoFixtures.now)
            ])))
            return try JSONValue.encode(SendResult(accepted: .queued, queuedID: request.id))
        }
        update(sessionID: id) { $0.state = .running; $0.turn = TurnMarker(turnID: UUID().uuidString,
                                                                          startedAt: DemoFixtures.now) }
        // A real device reports the message it was given before it reports what
        // the agent said about it, so the echo leads and the reply follows it.
        echoing?.cancel()
        let delay = echoDelay
        echoing = Task { [weak self] in
            try? await Task.sleep(for: delay)
            guard !Task.isCancelled else { return }
            await self?.echoThenReply(sessionID: id, blockID: request.id, text: text)
        }
        return try JSONValue.encode(SendResult(accepted: .sent))
    }

    private func echoThenReply(sessionID: String, blockID: String, text: String) {
        emit(sessionID: sessionID, blockID: blockID,
             body: .userMessage(UserMessagePayload(text: text, source: .remote)))
        startReplyScript(sessionID: sessionID)
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
            // neither a queue entry nor a delivery state. Amendment A14: the
            // agent reads it at its next step, not where it was sent, so the
            // block waits for the step and the app's own row holds its place.
            steering?.cancel()
            let delay = echoDelay
            steering = Task { [weak self] in
                try? await Task.sleep(for: delay)
                guard !Task.isCancelled else { return }
                await self?.takeSteeredMessage(sessionID: id, blockID: requestID, text: text)
            }
            return try JSONValue.encode(SendResult(accepted: .steered))
        default:
            return try inject(sessionID: id, requestID: requestID, text: text)
        }
    }

    /// Amendment A14: the turn finishes the sentence it was already saying, the
    /// agent reads the steered message at its next step, and only then does the
    /// block for it appear — under the request id, after the output above it.
    /// That is the order a terminal on the same thread draws.
    private func takeSteeredMessage(sessionID: String, blockID: String, text: String) {
        emit(sessionID: sessionID, blockID: "a-\(UUID().uuidString.prefix(6))",
             body: .assistantText(StreamTextPayload(
                text: "The typecheck is clean now that the drawer passes `effort` through again.",
                done: true)))
        emit(sessionID: sessionID, blockID: blockID,
             body: .userMessage(UserMessagePayload(text: text, source: .remote, delivery: .delivered)))
        startReplyScript(sessionID: sessionID)
    }

    /// Amendment A10: a message a channel cannot deliver yet is held by the
    /// device and injected when the terminal is next idle, so the app first
    /// sees it as `pending` and then the same block again as `delivered`.
    private func inject(sessionID: String, requestID: String, text: String) throws -> JSONValue {
        let blockID = requestID
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

    /// Amendment A15: a terminal attaches to the session the reader left in the
    /// Archive. The device clears `archived`, reports the terminal as its owner
    /// and publishes the session, all in one `session.updated`. The row leaves
    /// the Archive for the live rows without a reload and without a turn: the
    /// transcript it already carries is a finished one, and the demo shows the
    /// attach rather than inventing output nobody asked for.
    private func resumeArchivedSession() async {
        guard let resumeDelay else { return }
        try? await Task.sleep(for: resumeDelay)
        guard !Task.isCancelled else { return }
        update(sessionID: DemoFixtures.revivedSessionID) { session in
            session.archived = false
            session.origin = .terminal
            session.control = .terminal
            session.state = .idle
        }
    }

    /// Amendment A17: somebody types `/model` in the terminal that owns the
    /// attached session. The device reads the change out of the transcript and
    /// publishes it as `meta` and as a session summary; the app has no picker
    /// to keep in step, only the chip that says what the terminal chose, and it
    /// follows without a reload.
    private func startRetuneScript(sessionID: String) {
        guard retuning == nil else { return }
        retuning = Task { [weak self] in
            try? await Task.sleep(for: Self.retuneDelay)
            guard !Task.isCancelled else { return }
            await self?.retune(sessionID: sessionID, model: "claude-opus-4-1")
        }
    }

    private func retune(sessionID: String, model: String) {
        emit(sessionID: sessionID, body: .meta(MetaPayload(model: model)))
        update(sessionID: sessionID) { $0.model = model }
    }

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
