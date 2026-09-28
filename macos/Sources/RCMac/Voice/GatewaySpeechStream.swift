import Foundation
import RCCore

/// One utterance through RCCore's `STTSocket`, reported the way the web's
/// `SttSocket` reports it (`web/src/features/voice/sttSocket.ts`).
///
/// Two things differ underneath and are mapped here. RCCore's socket words its
/// own transport failures in English, where the web's socket reports a failure
/// with no message and the composer says `voice.failed`; those become `failed`
/// with no message. And it gives up waiting for a final transcript on a timer
/// of its own, which the web leaves to `useVoice` (`VoiceTiming.finalTimeout`)
/// and which keeps the words without an error: that one becomes `closed`.
///
/// The socket is an actor, so the frames, the stop and the cancel go to it
/// through one queue, in the order they were given.
@MainActor
final class GatewaySpeechStream: SpeechStream {
    private enum Outgoing: Sendable {
        case frame(Data)
        case stop
        case cancel
    }

    private let socket: STTSocket?
    private let onEvent: @MainActor (SpeechEvent) -> Void
    private let outgoing: AsyncStream<Outgoing>.Continuation
    private var reader: Task<Void, Never>?
    private var cancelled = false

    init(client: GatewayHTTPClient?, onEvent: @escaping @MainActor (SpeechEvent) -> Void) {
        let socket = client.map { STTSocket(client: $0) }
        self.socket = socket
        self.onEvent = onEvent
        let (stream, continuation) = AsyncStream<Outgoing>.makeStream()
        outgoing = continuation
        Task {
            for await item in stream {
                switch item {
                case .frame(let frame): await socket?.append(frame)
                case .stop: await socket?.stop()
                case .cancel:
                    await socket?.cancel()
                    return
                }
            }
        }
    }

    deinit { outgoing.finish() }

    func start() async throws {
        guard let socket else { throw TransportError.unauthorized }
        try await socket.start()
        reader = Task { [weak self] in
            for await event in socket.events {
                guard let self, !self.cancelled else { return }
                self.onEvent(Self.event(for: event))
            }
        }
    }

    func append(_ frame: Data) {
        guard !cancelled else { return }
        outgoing.yield(.frame(frame))
    }

    func stop() {
        guard !cancelled else { return }
        outgoing.yield(.stop)
    }

    func cancel() {
        guard !cancelled else { return }
        cancelled = true
        reader?.cancel()
        outgoing.yield(.cancel)
        outgoing.finish()
    }

    nonisolated static func event(for event: STTEvent) -> SpeechEvent {
        switch event {
        case .partial(let text): .partial(text)
        case .final(let text, _): .final(text)
        case .closed: .closed
        case .failed(let message):
            if message == L10n.string("The gateway did not return a transcript.") {
                .closed
            } else if ownSentences.contains(message) {
                .failed(nil)
            } else {
                .failed(message)
            }
        }
    }

    /// What RCCore's socket says for a failure of its own, rather than one the
    /// gateway reported: `stt.error` with no message, and the transport's.
    nonisolated private static var ownSentences: Set<String> {
        ["Transcription failed.", "audio upload failed",
         L10n.string("The transcription connection dropped."),
         L10n.string("The transcript did not finish. What was recognised is in your draft.")]
    }
}
