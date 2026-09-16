import Foundation
import RCCore

/// Amendment A4: what the app does with each WebSocket close code.
///
/// The socket is driven through a fake connection that closes immediately with
/// a chosen code, so the reconnect decision is observed rather than assumed.
enum SocketChecks {
    static func run() async -> CheckResult {
        let checks = CheckRunner(group: "socket")

        checks.equal(SocketCloseReason(code: 4401), .unauthorized, "4401 maps to unauthorized")
        checks.equal(SocketCloseReason(code: 4403), .forbidden, "4403 maps to forbidden")
        checks.equal(SocketCloseReason(code: 4001), .replaced, "4001 maps to replaced")
        checks.equal(SocketCloseReason(code: 1006), .transient, "an ordinary close is transient")
        checks.equal(SocketCloseReason(code: nil), .transient, "a close with no code is transient")
        checks.expect(!SocketCloseReason.unauthorized.shouldReconnect, "unauthorized stops reconnecting")
        checks.expect(!SocketCloseReason.forbidden.shouldReconnect, "forbidden stops reconnecting")
        checks.expect(!SocketCloseReason.replaced.shouldReconnect, "replaced stops reconnecting")
        checks.expect(SocketCloseReason.transient.shouldReconnect, "a transient close reconnects")

        await terminal(code: 4401, expected: .unauthorized, checks: checks)
        await terminal(code: 4403, expected: .forbidden, checks: checks)
        await terminal(code: 4001, expected: .replaced, checks: checks)
        await transient(checks: checks)
        await writeOrdering(checks: checks)
        await sendWhileReconnecting(checks: checks)
        await sendWithNoSocketAtAll(checks: checks)
        await undecodableFrame(checks: checks)
        await duplicateRequestID(checks: checks)
        return checks.result()
    }

    /// One frame the app cannot read is one frame, not a broken connection.
    ///
    /// Nothing validates frames against the schema at runtime, so a device
    /// adapter that omits a required field reaches every app unfiltered. If
    /// that closed the socket, the app would fail every request in flight as
    /// unconfirmed and reconnect, for as long as that session ran.
    private static func undecodableFrame(checks: CheckRunner) async {
        let connection = FlawedWebSocket()
        let factory = FlawedFactory(connection: connection)
        let socket = GatewaySocket(client: await makeClient(), factory: factory)
        await socket.connect()
        await settle(timeout: 2) { await socket.isConnected }

        var replied = false
        do {
            _ = try await socket.request(.stop(sessionID: "s"))
            replied = true
        } catch {
            checks.expect(false, "a request in flight was failed by an undecodable frame: \(error)")
        }
        checks.expect(replied, "a request in flight still gets its reply past a frame that was dropped")
        checks.equal(await factory.attempts, 1, "and the connection is not torn down and rebuilt")
        checks.expect(await socket.isConnected, "the socket is still the one that was connected")
        await socket.disconnect()
    }

    /// Amendment A12 makes a retry reuse its request id, so two of them can be
    /// outstanding at once. The first caller has to be answered rather than
    /// left suspended for the life of the process.
    private static func duplicateRequestID(checks: CheckRunner) async {
        let connection = HeldReplyWebSocket()
        let socket = GatewaySocket(client: await makeClient(),
                                   factory: HeldReplyFactory(connection: connection))
        await socket.connect()
        await settle(timeout: 2) { await socket.isConnected }

        guard let request = try? GatewayRequest.send(id: "duplicate", sessionID: "s", text: "one",
                                                     attachments: [], mode: .auto) else {
            checks.expect(false, "the duplicate-id check could not build its request")
            return
        }
        let outcomes = OutcomeBox()
        // Neither task is awaited: before the fix the first one never returns,
        // and a check that waited for it would hang rather than fail.
        Task {
            do {
                _ = try await socket.request(request)
                await outcomes.record("first", "replied")
            } catch {
                await outcomes.record("first", label(error))
            }
        }
        await settle(timeout: 2) { await connection.writes == 1 }
        Task {
            do {
                _ = try await socket.request(request)
                await outcomes.record("second", "replied")
            } catch {
                await outcomes.record("second", label(error))
            }
        }
        await settle(timeout: 2) { await connection.writes == 2 }

        await connection.answerOutstanding()
        await settle(timeout: 3) { await outcomes.outcome("second") != nil }

        checks.equal(await outcomes.outcome("first"), "deliveryUncertain",
                     "the caller whose slot was taken is answered, not stranded")
        checks.equal(await outcomes.outcome("second"), "replied",
                     "and the second request gets the gateway's reply")
        await socket.disconnect()
    }

    private static func label(_ error: any Error) -> String {
        guard let transport = error as? TransportError else { return "\(error)" }
        switch transport {
        case .deliveryUncertain: return "deliveryUncertain"
        case .requestTimedOut: return "requestTimedOut"
        default: return "\(transport)"
        }
    }

    /// A request issued while the socket is still coming up waits for the hello
    /// instead of failing.
    ///
    /// The gateway closes a silent socket after 25 s, so an app returning to
    /// the foreground has one to rebuild more often than not. Failing the send
    /// for the length of a TLS handshake reads as a dead Send button.
    private static func sendWhileReconnecting(checks: CheckRunner) async {
        let connection = SlowHelloWebSocket(helloDelay: .milliseconds(400))
        let socket = GatewaySocket(client: await makeClient(),
                                   factory: SlowHelloFactory(connection: connection))
        await socket.connect()

        // Issued at once: the hello is still 400 ms away.
        checks.expect(!(await socket.isConnected), "the request is issued before the socket is up")
        let started = Date()
        var delivered = false
        do {
            _ = try await socket.request(.stop(sessionID: "s"))
            delivered = true
        } catch {
            checks.expect(false, "a send during a reconnect was refused: \(error)")
        }
        checks.expect(delivered, "a request issued while reconnecting is flushed once the hello lands")
        checks.expect(Date().timeIntervalSince(started) >= 0.3,
                      "and it really did wait for the connection rather than racing it")
        checks.equal(await connection.sentTypes, ["session.stop"],
                     "the gateway sees it once, after the hello")
        await socket.disconnect()
    }

    /// The wait is for a socket on its way back, not for one that was never
    /// asked for: a request on a disconnected transport still fails at once.
    private static func sendWithNoSocketAtAll(checks: CheckRunner) async {
        let socket = GatewaySocket(client: await makeClient(),
                                   factory: SlowHelloFactory(connection: SlowHelloWebSocket(helloDelay: .zero)))
        var refused = false
        do { _ = try await socket.request(.stop(sessionID: "s")) }
        catch { refused = (error as? TransportError) == .notConnected }
        checks.expect(refused, "a request with no connection attempt under way fails at once")
    }

    /// Review finding 12: two frames must reach the gateway in the order they
    /// were issued. A slow first write proves the chain rather than luck.
    private static func writeOrdering(checks: CheckRunner) async {
        let connection = OrderedWebSocket(firstWriteDelay: .milliseconds(120))
        let socket = GatewaySocket(client: await makeClient(),
                                   factory: SingleConnectionFactory(connection: connection))
        await socket.connect()
        await settle(timeout: 2) { await socket.isConnected }
        checks.expect(await socket.isConnected, "the ordered-write socket connects")

        let first = Task { try? await socket.request(.stop(sessionID: "s")) }
        try? await Task.sleep(for: .milliseconds(20))
        _ = try? await socket.request(.unsubscribe(sessionID: "s"))
        first.cancel()

        let order = await connection.sentTypes
        checks.equal(order.first, "session.stop", "the first request is written first")
        checks.equal(order.count >= 2 ? order[1] : "", "session.unsubscribe",
                     "a later frame cannot overtake a slow earlier write")
        await socket.disconnect()
    }

    private static func settle(timeout: TimeInterval, _ condition: @Sendable () async -> Bool) async {
        let deadline = Date().addingTimeInterval(timeout)
        while await !condition(), Date() < deadline { try? await Task.sleep(for: .milliseconds(10)) }
    }

    /// A terminal code must produce exactly one connection attempt.
    private static func terminal(code: Int, expected: SocketCloseReason, checks: CheckRunner) async {
        let factory = ClosingWebSocketFactory(closeCode: code)
        let socket = GatewaySocket(client: await makeClient(), factory: factory)
        await socket.connect()
        var reason: SocketCloseReason?
        var sawUnauthorizedState = false
        await consume(socket.events, until: 2) { event in
            switch event {
            case .closed(let value): reason = value; return true
            case .state(.unauthorized): sawUnauthorizedState = true; return false
            default: return false
            }
        }
        checks.equal(reason, expected, "close \(code) reports \(expected)")
        checks.equal(await factory.attempts, 1, "close \(code) is not retried")
        if expected == .unauthorized {
            checks.expect(sawUnauthorizedState, "4401 also reports an unauthorized connection state")
        }
        await socket.disconnect()
    }

    /// An ordinary close keeps trying, with the first backoff at one second.
    private static func transient(checks: CheckRunner) async {
        let factory = ClosingWebSocketFactory(closeCode: 1006)
        let socket = GatewaySocket(client: await makeClient(), factory: factory)
        await socket.connect()
        let deadline = Date().addingTimeInterval(4)
        while await factory.attempts < 2, Date() < deadline {
            try? await Task.sleep(for: .milliseconds(50))
        }
        checks.expect(await factory.attempts >= 2, "a transient close is retried")
        await socket.disconnect()
        let settled = await factory.attempts
        try? await Task.sleep(for: .milliseconds(300))
        checks.equal(await factory.attempts, settled, "disconnecting stops the retry loop")
    }

    private static func makeClient() async -> GatewayHTTPClient {
        let client = GatewayHTTPClient(endpoint: try! GatewayEndpoint("https://rc.example.invalid"),
                                       transport: UnusedHTTPTransport(),
                                       secrets: MemorySecretStore())
        await client.adoptToken("verification-token")
        return client
    }

    /// Read events until the predicate says stop or the budget runs out.
    private static func consume(_ events: AsyncStream<GatewayEvent>, until seconds: TimeInterval,
                                _ handle: (GatewayEvent) -> Bool) async {
        let deadline = Date().addingTimeInterval(seconds)
        for await event in events {
            if handle(event) { return }
            if Date() > deadline { return }
        }
    }
}

private struct SlowHelloFactory: WebSocketFactory {
    let connection: SlowHelloWebSocket
    func makeConnection(request: URLRequest) async -> any WebSocketConnection { connection }
}

/// A connection that takes its time over the hello, then answers every request
/// it is written with an empty successful reply.
private actor SlowHelloWebSocket: WebSocketConnection {
    private let helloDelay: Duration
    private var greeted = false
    private var replies: [String] = []
    private(set) var sentTypes: [String] = []

    init(helloDelay: Duration) { self.helloDelay = helloDelay }

    func resume() {}

    func send(text: String) async throws {
        guard let json = try? JSONDecoder().decode(JSONValue.self, from: Data(text.utf8)),
              let type = json["type"]?.stringValue, let id = json["id"]?.stringValue else { return }
        sentTypes.append(type)
        replies.append(#"{"type":"reply","id":"\#(id)","ok":true,"result":{}}"#)
    }

    func send(binary: Data) async throws {}

    func receive() async throws -> Data {
        if !greeted {
            try await Task.sleep(for: helloDelay)
            greeted = true
            return Data(#"{"type":"hello","protocol":1,"gateway_version":"t","user":{"username":"a"},"server_time":0}"#.utf8)
        }
        while replies.isEmpty {
            try await Task.sleep(for: .milliseconds(5))
        }
        return Data(replies.removeFirst().utf8)
    }

    func closeCode() -> Int? { nil }
    func cancel() {}
}

private struct SingleConnectionFactory: WebSocketFactory {
    let connection: OrderedWebSocket
    func makeConnection(request: URLRequest) async -> any WebSocketConnection { connection }
}

/// Stays open, records the frame types it was sent, and makes the first write
/// slow so an ordering bug would be observable.
private actor OrderedWebSocket: WebSocketConnection {
    private(set) var sentTypes: [String] = []
    private let firstWriteDelay: Duration
    private var wroteOnce = false

    init(firstWriteDelay: Duration) { self.firstWriteDelay = firstWriteDelay }

    func resume() {}

    func send(text: String) async throws {
        if !wroteOnce {
            wroteOnce = true
            try? await Task.sleep(for: firstWriteDelay)
        }
        if let json = try? JSONDecoder().decode(JSONValue.self, from: Data(text.utf8)),
           let type = json["type"]?.stringValue {
            sentTypes.append(type)
        }
    }

    func send(binary: Data) async throws {}

    /// One hello, then silence: the socket stays connected for the test.
    func receive() async throws -> Data {
        if wroteOnce || !sentTypes.isEmpty {
            try await Task.sleep(for: .seconds(30))
            throw TransportError.notConnected
        }
        return Data(#"{"type":"hello","protocol":1,"gateway_version":"t","user":{"username":"a"},"server_time":0}"#.utf8)
    }

    func closeCode() -> Int? { nil }
    func cancel() {}
}

/// A connection that opens, immediately fails its first read, and reports the
/// close code it was built with.
private actor ClosingWebSocketFactory: WebSocketFactory {
    private(set) var attempts = 0
    private let closeCode: Int

    init(closeCode: Int) { self.closeCode = closeCode }

    nonisolated func makeConnection(request: URLRequest) async -> any WebSocketConnection {
        await record()
        return ClosingWebSocket(closeCode: closeCode)
    }

    private func record() { attempts += 1 }
}

private actor ClosingWebSocket: WebSocketConnection {
    private let code: Int
    init(closeCode: Int) { code = closeCode }
    func resume() {}
    func send(text: String) throws {}
    func send(binary: Data) throws {}
    func receive() async throws -> Data { throw TransportError.notConnected }
    func closeCode() -> Int? { code }
    func cancel() {}
}

/// The socket checks never issue an HTTP request; reaching this is a bug.
private struct UnusedHTTPTransport: HTTPTransport {
    func perform(_ request: URLRequest) async throws -> (Data, HTTPURLResponse) {
        throw TransportError.notConnected
    }
}

private actor FlawedFactory: WebSocketFactory {
    private(set) var attempts = 0
    private let connection: FlawedWebSocket

    init(connection: FlawedWebSocket) { self.connection = connection }

    nonisolated func makeConnection(request: URLRequest) async -> any WebSocketConnection {
        await record()
        return connection
    }

    private func record() { attempts += 1 }
}

/// Answers every request, but puts one frame the app cannot read in front of
/// the reply: a `todos` item with no `text`, which violates the schema's
/// `required` and is exactly what a device adapter with a missing field sends.
private actor FlawedWebSocket: WebSocketConnection {
    private var greeted = false
    private var outbox: [String] = []

    func resume() {}

    func send(text: String) async throws {
        guard let json = try? JSONDecoder().decode(JSONValue.self, from: Data(text.utf8)),
              let id = json["id"]?.stringValue else { return }
        outbox.append(#"{"type":"session.event","session_id":"s","event":"#
                      + #"{"seq":1,"ts":0,"kind":"todos","items":[{"status":"pending"}]}}"#)
        outbox.append(#"{"type":"reply","id":"\#(id)","ok":true,"result":{}}"#)
    }

    func send(binary: Data) async throws {}

    func receive() async throws -> Data {
        if !greeted {
            greeted = true
            return Data(#"{"type":"hello","protocol":1,"gateway_version":"t","user":{"username":"a"},"server_time":0}"#.utf8)
        }
        while outbox.isEmpty { try await Task.sleep(for: .milliseconds(5)) }
        return Data(outbox.removeFirst().utf8)
    }

    func closeCode() -> Int? { nil }
    func cancel() {}
}

private struct HeldReplyFactory: WebSocketFactory {
    let connection: HeldReplyWebSocket
    func makeConnection(request: URLRequest) async -> any WebSocketConnection { connection }
}

/// Takes writes and holds their replies, so two requests can be outstanding at
/// once. `answerOutstanding` then replies to the last id it was written, which
/// is what a gateway deduplicating a retry does (amendment A12).
private actor HeldReplyWebSocket: WebSocketConnection {
    private var greeted = false
    private var outbox: [String] = []
    private var lastID: String?
    private(set) var writes = 0

    func resume() {}

    func send(text: String) async throws {
        guard let json = try? JSONDecoder().decode(JSONValue.self, from: Data(text.utf8)),
              let id = json["id"]?.stringValue else { return }
        lastID = id
        writes += 1
    }

    func send(binary: Data) async throws {}

    func answerOutstanding() {
        guard let lastID else { return }
        outbox.append(#"{"type":"reply","id":"\#(lastID)","ok":true,"result":{"accepted":"sent"}}"#)
    }

    func receive() async throws -> Data {
        if !greeted {
            greeted = true
            return Data(#"{"type":"hello","protocol":1,"gateway_version":"t","user":{"username":"a"},"server_time":0}"#.utf8)
        }
        while outbox.isEmpty { try await Task.sleep(for: .milliseconds(5)) }
        return Data(outbox.removeFirst().utf8)
    }

    func closeCode() -> Int? { nil }
    func cancel() {}
}

/// What each of two overlapping requests ended up with, recorded from inside
/// its own task so no check has to wait on a task that may never return.
private actor OutcomeBox {
    private var outcomes: [String: String] = [:]

    func record(_ name: String, _ outcome: String) { outcomes[name] = outcome }
    func outcome(_ name: String) -> String? { outcomes[name] }
}
