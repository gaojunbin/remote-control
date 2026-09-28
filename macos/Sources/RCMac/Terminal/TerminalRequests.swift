import Foundation
import RCCore
import Synchronization

/// The channel RCCore's `TerminalSession` sends through: the connection's own
/// socket, whichever one it is at the moment a request leaves — a sign-in or a
/// take-over makes a new one, and a terminal page outlives neither — with the
/// error of the last refused request kept as the gateway sent it.
///
/// `TerminalSession` words a refusal in the iPhone app's sentences before any
/// screen sees it; this page says the web's instead (`errorText` in
/// `lib/errors.ts`, chosen by `error.code`), so it keeps the error itself, as
/// the login page's `SignInRecorder` does for a sign-in.
struct TerminalRequests: GatewayChannel {
    let socket: @MainActor @Sendable () -> (any GatewayChannel)?
    let failures: TerminalFailures

    /// `TerminalSession` sends requests and reads nothing else; the frames it
    /// is fed come from the connection's own frame handlers.
    var events: AsyncStream<GatewayEvent> { AsyncStream { $0.finish() } }

    func connect() async {}

    func disconnect() async {}

    func request(_ request: GatewayRequest) async throws -> JSONValue {
        guard let channel = await socket() else {
            failures.record(TransportError.notConnected)
            throw TransportError.notConnected
        }
        do {
            return try await channel.request(request)
        } catch {
            failures.record(error)
            throw error
        }
    }
}

/// The last error a terminal request ended with.
final class TerminalFailures: Sendable {
    private let last = Mutex<(any Error)?>(nil)

    var latest: (any Error)? { last.withLock { $0 } }

    func record(_ error: any Error) { last.withLock { $0 = error } }
}
