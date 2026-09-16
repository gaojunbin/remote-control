import Foundation

/// The buffering both transport streams use.
///
/// A stream of frames is worth reading for its most recent element, so
/// back-pressure has to cost the oldest one. `bufferingOldest` is the other way
/// round: once the buffer fills it keeps the stale frames and discards every
/// new one, so a burst of session events that outran the MainActor pump would
/// leave the app rendering the beginning of the burst and silently losing the
/// end of it.
enum EventBuffer {
    /// App frames. A burst of session events can outrun the pump on a busy
    /// session, and the transcript's gap repair costs a round trip.
    static let appCapacity = 1024
    /// Dictation events, which are few and small — but a lost `stt.final`
    /// leaves the panel waiting for its 30 s timeout.
    static let sttCapacity = 256

    static func makeStream<Element: Sendable>(
        of type: Element.Type, capacity: Int
    ) -> (stream: AsyncStream<Element>, continuation: AsyncStream<Element>.Continuation) {
        AsyncStream<Element>.makeStream(of: type, bufferingPolicy: .bufferingNewest(capacity))
    }
}
