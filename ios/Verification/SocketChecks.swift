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
        return checks.result()
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
