import Foundation
#if canImport(FoundationNetworking)
import FoundationNetworking
#endif

/// One live WebSocket. The demo gateway and the tests provide their own.
public protocol WebSocketConnection: Sendable {
    func resume() async
    func send(text: String) async throws
    func send(binary: Data) async throws
    func receive() async throws -> Data
    /// The close code the peer sent, once the socket has closed. It decides
    /// whether reconnecting is the right response or a loop.
    func closeCode() async -> Int?
    func cancel() async
}

public protocol WebSocketFactory: Sendable {
    func makeConnection(request: URLRequest) async -> any WebSocketConnection
}

public struct URLSessionWebSocketFactory: WebSocketFactory {
    public init() {}
    public func makeConnection(request: URLRequest) async -> any WebSocketConnection {
        NativeWebSocket(request: request)
    }
}

/// Ephemeral session: no cookies, no credential storage, no cache. The bearer
/// header on the upgrade request is the only credential in play.
private actor NativeWebSocket: WebSocketConnection {
    private let session: URLSession
    private let task: URLSessionWebSocketTask

    init(request: URLRequest) {
        let configuration = URLSessionConfiguration.ephemeral
        configuration.httpCookieStorage = nil
        configuration.httpShouldSetCookies = false
        configuration.urlCredentialStorage = nil
        configuration.urlCache = nil
        session = URLSession(configuration: configuration)
        task = session.webSocketTask(with: request)
        task.maximumMessageSize = 16 * 1024 * 1024
    }

    func resume() { task.resume() }

    func send(text: String) async throws { try await task.send(.string(text)) }

    func send(binary: Data) async throws { try await task.send(.data(binary)) }

    func receive() async throws -> Data {
        switch try await task.receive() {
        case .data(let data): return data
        case .string(let text): return Data(text.utf8)
        @unknown default: throw TransportError.invalidResponse
        }
    }

    func closeCode() -> Int? {
        let value = task.closeCode.rawValue
        return value == 0 ? nil : value
    }

    func cancel() {
        task.cancel(with: .goingAway, reason: nil)
        session.invalidateAndCancel()
    }

    deinit { session.invalidateAndCancel() }
}
