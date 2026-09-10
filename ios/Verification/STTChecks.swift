import Foundation
import RCCore

/// Review finding 31: the gateway transcription socket had no behavioural test,
/// which is why finding 2 (a two-second cancel after Stop) went unnoticed.
enum STTChecks {
    static func run() async -> CheckResult {
        let checks = CheckRunner(group: "stt")
        await framing(checks)
        await finalTranscript(checks)
        await cancelled(checks)
        await gatewayError(checks)
        await closeWithoutFinal(checks)
        await audioBudget(checks)
        return checks.result()
    }

    /// `stt.stop` and `stt.cancel` are the only text frames the app sends.
    private static func framing(_ checks: CheckRunner) async {
        let connection = FakeSTTConnection()
        let socket = await makeSocket(connection)
        try? await socket.start()
        await socket.append(Data(repeating: 0, count: 320))
        await socket.stop()
        await settle { await connection.sentText.count == 1 }
        checks.equal(await connection.sentText, [#"{"type":"stt.stop"}"#], "stop sends stt.stop")
        checks.equal(await connection.sentBinary.count, 1, "audio is sent as binary frames")
        checks.equal(await connection.sentBinary.first?.count, 320, "the buffer is sent verbatim")
        await socket.close()

        let cancelling = FakeSTTConnection()
        let second = await makeSocket(cancelling)
        try? await second.start()
        await second.cancel()
        await settle { await cancelling.sentText.count == 1 }
        checks.equal(await cancelling.sentText, [#"{"type":"stt.cancel"}"#], "cancel sends stt.cancel")
    }

    /// A partial updates the draft; the final ends the utterance.
    private static func finalTranscript(_ checks: CheckRunner) async {
        let connection = FakeSTTConnection()
        let socket = await makeSocket(connection)
        let recorder = Recorder()
        let reader = Task { for await event in socket.events { await recorder.append(event) } }
        try? await socket.start()
        await connection.deliver(#"{"type":"stt.partial","text":"re-run the"}"#)
        await settle { await recorder.contains(.partial("re-run the")) }
        checks.expect(await recorder.contains(.partial("re-run the")), "a partial transcript is delivered")

        await socket.stop()
        await connection.deliver(#"{"type":"stt.final","text":"re-run the suite","language":"en"}"#)
        let final = STTEvent.final(text: "re-run the suite", language: "en")
        await settle { await recorder.contains(final) }
        checks.expect(await recorder.contains(final),
                      "the final transcript is delivered with its language")
        await settle { await recorder.contains(.closed) }
        checks.expect(await recorder.contains(.closed), "the socket closes after a final")
        reader.cancel()
    }

    private static func cancelled(_ checks: CheckRunner) async {
        let connection = FakeSTTConnection()
        let socket = await makeSocket(connection)
        try? await socket.start()
        await socket.cancel()
        checks.expect(await connection.cancelled, "cancelling tears the connection down")
    }

    private static func gatewayError(_ checks: CheckRunner) async {
        let connection = FakeSTTConnection()
        let socket = await makeSocket(connection)
        let recorder = Recorder()
        let reader = Task { for await event in socket.events { await recorder.append(event) } }
        try? await socket.start()
        await connection.deliver(#"{"type":"stt.error","message":"no model"}"#)
        await settle { await recorder.failure() != nil }
        checks.expect(await recorder.failure() != nil, "an stt.error is surfaced")
        reader.cancel()
    }

    /// Review finding 2: a close without a final is the terminal outcome, and
    /// it must be reported rather than leaving the panel waiting.
    private static func closeWithoutFinal(_ checks: CheckRunner) async {
        let connection = FakeSTTConnection()
        let socket = await makeSocket(connection)
        let recorder = Recorder()
        let reader = Task { for await event in socket.events { await recorder.append(event) } }
        try? await socket.start()
        await connection.deliver(#"{"type":"stt.partial","text":"half a sentence"}"#)
        await settle { await recorder.contains(.partial("half a sentence")) }
        await socket.stop()
        await connection.dropConnection()
        await settle { await recorder.failure() != nil }
        checks.expect(await recorder.failure()?.contains("draft") == true,
                      "a close without a final says the recognised text was kept")
        checks.expect(await recorder.contains(.partial("half a sentence")),
                      "the last partial is still what the caller received")
        reader.cancel()
    }

    /// The gateway caps an utterance at 4 MiB; the app stops rather than being
    /// closed under the user.
    private static func audioBudget(_ checks: CheckRunner) async {
        let connection = FakeSTTConnection()
        let socket = await makeSocket(connection)
        try? await socket.start()
        let chunk = Data(repeating: 0, count: 512 * 1024)
        for _ in 0..<10 { await socket.append(chunk) }
        let sent = await connection.sentBinary.reduce(0) { $0 + $1.count }
        checks.expect(sent <= STTSocket.maxAudioBytes, "the PCM budget is not exceeded")
        checks.expect(await connection.sentText.contains(#"{"type":"stt.stop"}"#),
                      "reaching the budget finishes the utterance instead of dropping audio silently")
        await socket.close()
    }

    private static func makeSocket(_ connection: FakeSTTConnection) async -> STTSocket {
        let client = GatewayHTTPClient(endpoint: GatewayEndpoint.placeholder,
                                       transport: UnusedTransport(), secrets: MemorySecretStore())
        await client.adoptToken("verification-token")
        return STTSocket(client: client, language: "en", factory: FakeSTTFactory(connection: connection))
    }

    private static func settle(timeout: TimeInterval = 2,
                               _ condition: @Sendable () async -> Bool) async {
        let deadline = Date().addingTimeInterval(timeout)
        while await !condition(), Date() < deadline {
            try? await Task.sleep(for: .milliseconds(10))
        }
    }
}

/// Collects socket events off the reading task without a shared mutable local.
private actor Recorder {
    private var events: [STTEvent] = []
    func append(_ event: STTEvent) { events.append(event) }
    func contains(_ event: STTEvent) -> Bool { events.contains(event) }
    func failure() -> String? {
        events.compactMap { if case .failed(let message) = $0 { message } else { nil } }.first
    }
}

private struct FakeSTTFactory: WebSocketFactory {
    let connection: FakeSTTConnection
    func makeConnection(request: URLRequest) async -> any WebSocketConnection { connection }
}

/// Records what the app sends and lets a check push gateway frames back.
private actor FakeSTTConnection: WebSocketConnection {
    private(set) var sentText: [String] = []
    private(set) var sentBinary: [Data] = []
    private(set) var cancelled = false
    private var inbox: [Data] = []
    private var waiting: CheckedContinuation<Data, any Error>?

    func resume() {}

    func send(text: String) { sentText.append(text) }

    func send(binary: Data) { sentBinary.append(binary) }

    func receive() async throws -> Data {
        if !inbox.isEmpty { return inbox.removeFirst() }
        return try await withCheckedThrowingContinuation { continuation in waiting = continuation }
    }

    func closeCode() -> Int? { nil }

    func cancel() {
        cancelled = true
        waiting?.resume(throwing: TransportError.notConnected)
        waiting = nil
    }

    func deliver(_ json: String) {
        let data = Data(json.utf8)
        if let waiting { self.waiting = nil; waiting.resume(returning: data) } else { inbox.append(data) }
    }

    /// The gateway went away without answering.
    func dropConnection() {
        waiting?.resume(throwing: TransportError.notConnected)
        waiting = nil
    }
}

private struct UnusedTransport: HTTPTransport {
    func perform(_ request: URLRequest) async throws -> (Data, HTTPURLResponse) {
        throw TransportError.notConnected
    }
}
