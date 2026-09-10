import Foundation
#if canImport(FoundationNetworking)
import FoundationNetworking
#endif

public enum ConnectionState: String, Sendable, Codable, Hashable {
    case idle, connecting, connected, reconnecting, unauthorized, disconnected
}

/// Why the gateway closed the socket, and therefore what to do next.
///
/// Amendment A4: 4401 and 4403 are terminal for this session; 4001 means this
/// connection was replaced and reconnecting would just fight the replacement;
/// anything else is transient and earns a backoff.
public enum SocketCloseReason: Sendable, Equatable {
    case unauthorized
    case forbidden
    case replaced
    case transient

    public static let unauthorizedCode = 4401
    public static let forbiddenCode = 4403
    public static let replacedCode = 4001

    public init(code: Int?) {
        switch code {
        case Self.unauthorizedCode: self = .unauthorized
        case Self.forbiddenCode: self = .forbidden
        case Self.replacedCode: self = .replaced
        default: self = .transient
        }
    }

    /// Only a transient close is worth another attempt.
    public var shouldReconnect: Bool { self == .transient }
}

public enum GatewayEvent: Sendable {
    case state(ConnectionState)
    case frame(AppFrame)
    /// The socket closed for a reason the app must act on.
    case closed(SocketCloseReason)
    /// A request left the app but no reply arrived before the socket dropped.
    /// The app shows "delivery unconfirmed" and never resends on its own.
    case requestUncertain(id: String)
    case failure(TransportError)
}

/// The app's connection to `/ws/app`.
///
/// Reconnecting sends only `hello` handling and heartbeats. A request is never
/// replayed automatically: the user decides whether to retry, and the retry
/// reuses the original request id so the device can detect the duplicate.
public actor GatewaySocket {
    public nonisolated let events: AsyncStream<GatewayEvent>

    private let continuation: AsyncStream<GatewayEvent>.Continuation
    private let client: GatewayHTTPClient
    private let factory: any WebSocketFactory
    private var connection: (any WebSocketConnection)?
    private var worker: Task<Void, Never>?
    private var watchdog: Task<Void, Never>?
    private var pending: [String: CheckedContinuation<JSONValue, any Error>] = [:]
    private var writes: Task<Void, any Error>?
    private var epoch = 0
    private var active = false
    private var connected = false
    private var lastReceived = Date()

    /// A frame at least this often, or the connection is half-open. Mobile NAT
    /// silently drops sockets without ever delivering a close.
    private static let silenceLimit: TimeInterval = 60
    private static let forwardedTimeout: TimeInterval = 65
    private static let localTimeout: TimeInterval = 20

    public init(client: GatewayHTTPClient, factory: any WebSocketFactory = URLSessionWebSocketFactory()) {
        self.client = client
        self.factory = factory
        let stream = AsyncStream<GatewayEvent>.makeStream(bufferingPolicy: .bufferingOldest(1024))
        events = stream.stream
        continuation = stream.continuation
    }

    public func connect() {
        guard !active else { return }
        active = true
        epoch += 1
        let generation = epoch
        worker = Task { [weak self] in await self?.runLoop(generation: generation) }
    }

    public func disconnect() async {
        active = false
        connected = false
        epoch += 1
        worker?.cancel(); worker = nil
        watchdog?.cancel(); watchdog = nil
        let old = connection
        connection = nil
        writes?.cancel()
        writes = nil
        failPending(TransportError.deliveryUncertain)
        emit(.state(.disconnected))
        await old?.cancel()
    }

    public var isConnected: Bool { connected }

    /// Send a request and wait for its `reply`. Throws `deliveryUncertain` when
    /// the socket dropped before an answer arrived.
    ///
    /// The continuation is registered before the write is queued, and writes go
    /// through one chain, so a reply can never arrive before the app is ready
    /// for it and two sends cannot reach the gateway out of order.
    @discardableResult
    public func request(_ request: GatewayRequest) async throws -> JSONValue {
        guard active, connected, let connection else { throw TransportError.notConnected }
        let text = String(decoding: try request.encoded(), as: UTF8.self)
        guard request.expectsReply else {
            try await enqueue(text, on: connection).value
            return .object([:])
        }
        let generation = epoch
        let timeout = Self.timeout(for: request.type)
        let deadline = Task { [weak self] in
            try? await Task.sleep(for: .seconds(timeout))
            guard !Task.isCancelled else { return }
            await self?.expire(id: request.id, generation: generation)
        }
        defer { deadline.cancel() }
        return try await withCheckedThrowingContinuation { continuation in
            pending[request.id] = continuation
            let write = enqueue(text, on: connection)
            Task { [weak self] in
                do { try await write.value }
                catch { await self?.failRequest(id: request.id, error: TransportError.deliveryUncertain) }
            }
        }
    }

    /// Append a frame to the write chain. Each write awaits the previous one,
    /// so call order is the order the gateway sees.
    private func enqueue(_ text: String, on connection: any WebSocketConnection) -> Task<Void, any Error> {
        let previous = writes
        let write = Task {
            _ = try? await previous?.value
            try await connection.send(text: text)
        }
        writes = write
        return write
    }

    /// Typed convenience over `request`.
    public func request<T: Decodable>(_ request: GatewayRequest, as type: T.Type) async throws -> T {
        try await self.request(request).decode(type)
    }

    private static func timeout(for type: String) -> TimeInterval {
        // The gateway itself gives a device 60 s for these two.
        type == "session.create" || type == "session.history" ? forwardedTimeout : localTimeout
    }

    private func expire(id: String, generation: Int) {
        guard epoch == generation, let continuation = pending.removeValue(forKey: id) else { return }
        continuation.resume(throwing: TransportError.requestTimedOut)
        emit(.requestUncertain(id: id))
    }

    private func failRequest(id: String, error: any Error) {
        guard let continuation = pending.removeValue(forKey: id) else { return }
        continuation.resume(throwing: error)
        emit(.requestUncertain(id: id))
    }

    private func failPending(_ error: any Error) {
        let ids = Array(pending.keys)
        for id in ids {
            pending.removeValue(forKey: id)?.resume(throwing: error)
            emit(.requestUncertain(id: id))
        }
    }

    private func runLoop(generation: Int) async {
        var attempt = 0
        var closeReason = SocketCloseReason.transient
        while active, epoch == generation, !Task.isCancelled {
            emit(.state(attempt == 0 ? .connecting : .reconnecting))
            do {
                guard let token = await client.bearerToken() else { throw TransportError.unauthorized }
                var request = URLRequest(url: client.endpoint.socketURL(path: "/ws/app"))
                request.setValue("Bearer \(token)", forHTTPHeaderField: "Authorization")
                request.timeoutInterval = 30
                let socket = await factory.makeConnection(request: request)
                guard active, epoch == generation else { await socket.cancel(); break }
                connection = socket
                await socket.resume()
                lastReceived = Date()
                connected = true
                closeReason = .transient
                startWatchdog(generation: generation, connection: socket)
                while active, epoch == generation, !Task.isCancelled {
                    let data = try await socket.receive()
                    guard active, epoch == generation else { break }
                    // A connection only counts as healthy once it has delivered
                    // a frame; otherwise a gateway that accepts and immediately
                    // closes would loop at the shortest backoff forever.
                    attempt = 0
                    lastReceived = Date()
                    let frame = try AppFrame(data: data)
                    try await handle(frame, on: socket, generation: generation)
                }
            } catch {
                guard active, epoch == generation, !Task.isCancelled else { break }
                closeReason = SocketCloseReason(code: await connection?.closeCode())
                if let failure = error as? ProtocolFailure, case .unsupportedVersion(let version) = failure {
                    emit(.failure(.protocolMismatch(version)))
                    active = false
                } else if let failure = error as? TransportError, failure == .unauthorized {
                    closeReason = .unauthorized
                } else if let failure = error as? TransportError, closeReason == .transient {
                    emit(.failure(failure))
                }
            }
            guard epoch == generation else { break }
            connected = false
            watchdog?.cancel(); watchdog = nil
            let old = connection
            connection = nil
            writes?.cancel()
            writes = nil
            failPending(TransportError.deliveryUncertain)
            await old?.cancel()
            guard closeReason.shouldReconnect else {
                // A replaced or refused connection must not be retried: one
                // stops a reconnect war, the other cannot succeed.
                active = false
                if closeReason == .unauthorized { emit(.state(.unauthorized)) }
                emit(.closed(closeReason))
                break
            }
            guard active, epoch == generation, !Task.isCancelled else { break }
            emit(.state(.reconnecting))
            attempt += 1
            // Jitter keeps a fleet of apps from retrying in lockstep after a
            // gateway restart.
            let backoff = min(pow(2.0, Double(attempt - 1)), 15.0)
            try? await Task.sleep(for: .seconds(backoff * Double.random(in: 0.85...1.15)))
        }
        if epoch == generation { worker = nil; connected = false }
    }

    private func handle(_ frame: AppFrame, on socket: any WebSocketConnection, generation: Int) async throws {
        switch frame {
        case .hello(let hello):
            guard hello.protocolVersion == RemoteProtocol.version else {
                throw ProtocolFailure.unsupportedVersion(hello.protocolVersion)
            }
            emit(.state(.connected))
            emit(.frame(frame))
        case .ping:
            try? await socket.send(text: String(decoding: try GatewayRequest.pong().encoded(), as: UTF8.self))
        case .reply(let id, let result):
            guard let continuation = pending.removeValue(forKey: id) else { return }
            switch result {
            case .success(let value): continuation.resume(returning: value)
            case .failure(let error): continuation.resume(throwing: error)
            }
        default:
            emit(.frame(frame))
        }
    }

    private func startWatchdog(generation: Int, connection socket: any WebSocketConnection) {
        watchdog?.cancel()
        watchdog = Task { [weak self] in
            while !Task.isCancelled {
                do { try await Task.sleep(for: .seconds(5)) } catch { return }
                guard let self else { return }
                guard await self.dropIfSilent(generation: generation) else { continue }
                await socket.cancel()
                return
            }
        }
    }

    private func dropIfSilent(generation: Int) -> Bool {
        guard active, connected, epoch == generation else { return false }
        return Date().timeIntervalSince(lastReceived) > Self.silenceLimit
    }

    private func emit(_ event: GatewayEvent) {
        continuation.yield(event)
    }

    deinit {
        worker?.cancel()
        watchdog?.cancel()
        continuation.finish()
    }
}
