import Foundation
#if canImport(FoundationNetworking)
import FoundationNetworking
#endif

public enum STTEvent: Sendable, Equatable {
    case partial(String)
    case final(text: String, language: String)
    case failed(String)
    case closed
}

/// Streaming speech-to-text against the gateway.
///
/// The caller pushes PCM16LE, 16 kHz, mono frames; the gateway answers with a
/// partial transcript roughly every two seconds and one final transcript after
/// `stop()`. Audio leaves the phone here, which is the difference from
/// on-device recognition and is stated in the voice settings.
public actor STTSocket {
    public nonisolated let events: AsyncStream<STTEvent>

    /// Gateway limits: 120 s of audio, 4 MiB total per utterance.
    public static let maxAudioBytes = 4 * 1024 * 1024
    public static let maxAudioSeconds: TimeInterval = 120
    public static let sampleRate: Double = 16_000

    private let continuation: AsyncStream<STTEvent>.Continuation
    private let client: GatewayHTTPClient
    private let factory: any WebSocketFactory
    private let language: String
    private var connection: (any WebSocketConnection)?
    private var reader: Task<Void, Never>?
    private var sentBytes = 0
    private var finished = false
    private var receivedFinal = false
    private var deadline: Task<Void, Never>?

    /// How long the gateway gets to answer `stt.stop`. It has to transcribe up
    /// to 120 s of audio, so this is generous; it exists only so a gateway that
    /// never answers cannot leave the panel waiting forever.
    public static let finalTimeout: TimeInterval = 30

    public init(client: GatewayHTTPClient, language: String,
                factory: any WebSocketFactory = URLSessionWebSocketFactory()) {
        self.client = client
        self.language = language
        self.factory = factory
        let stream = AsyncStream<STTEvent>.makeStream(bufferingPolicy: .bufferingOldest(256))
        events = stream.stream
        continuation = stream.continuation
    }

    public func start() async throws {
        guard connection == nil, !finished else { return }
        guard let token = await client.bearerToken() else { throw TransportError.unauthorized }
        var components = URLComponents(url: client.endpoint.socketURL(path: "/ws/stt"), resolvingAgainstBaseURL: false)
        components?.queryItems = [URLQueryItem(name: "language", value: language)]
        guard let url = components?.url else { throw TransportError.invalidEndpoint }
        var request = URLRequest(url: url)
        request.setValue("Bearer \(token)", forHTTPHeaderField: "Authorization")
        request.timeoutInterval = 30
        let socket = await factory.makeConnection(request: request)
        connection = socket
        await socket.resume()
        reader = Task { [weak self] in await self?.readLoop(socket) }
    }

    /// Append one capture buffer. Silently drops audio past the gateway budget
    /// rather than letting the socket be closed under the user mid-sentence.
    public func append(_ pcm: Data) async {
        guard let connection, !finished, !pcm.isEmpty else { return }
        guard sentBytes + pcm.count <= Self.maxAudioBytes else { await stop(); return }
        sentBytes += pcm.count
        do { try await connection.send(binary: pcm) } catch { await fail("audio upload failed") }
    }

    /// Finish and transcribe everything sent so far, then wait for `stt.final`.
    public func stop() async {
        guard let connection, !finished else { return }
        finished = true
        try? await connection.send(text: #"{"type":"stt.stop"}"#)
        deadline?.cancel()
        deadline = Task { [weak self] in
            try? await Task.sleep(for: .seconds(Self.finalTimeout))
            guard !Task.isCancelled else { return }
            await self?.giveUpWaiting()
        }
    }

    private func giveUpWaiting() async {
        guard !receivedFinal else { return }
        await fail(L10n.string("The gateway did not return a transcript."))
    }

    /// Discard the utterance. The gateway drops the audio and closes.
    public func cancel() async {
        let socket = connection
        finished = true
        try? await socket?.send(text: #"{"type":"stt.cancel"}"#)
        await close()
    }

    public func close() async {
        deadline?.cancel(); deadline = nil
        reader?.cancel(); reader = nil
        let socket = connection
        connection = nil
        await socket?.cancel()
        continuation.yield(.closed)
        continuation.finish()
    }

    private func readLoop(_ socket: any WebSocketConnection) async {
        while !Task.isCancelled {
            do {
                let data = try await socket.receive()
                guard let json = try? JSONDecoder().decode(JSONValue.self, from: data),
                      let object = json.objectValue, let type = object.string("type") else { continue }
                switch type {
                case "stt.partial":
                    continuation.yield(.partial(object.string("text") ?? ""))
                case "stt.final":
                    receivedFinal = true
                    continuation.yield(.final(text: object.string("text") ?? "",
                                              language: object.string("language") ?? language))
                    await close()
                    return
                case "stt.error":
                    await fail(object.string("message") ?? "Transcription failed.")
                    return
                default:
                    continue
                }
            } catch {
                guard !Task.isCancelled else { return }
                // A close without a final is the end of the utterance: whatever
                // the last partial produced is what the user gets.
                await fail(finished
                    ? L10n.string("The transcript did not finish. What was recognised is in your draft.")
                    : L10n.string("The transcription connection dropped."))
                return
            }
        }
    }

    private func fail(_ message: String) async {
        continuation.yield(.failed(message))
        await close()
    }
}
