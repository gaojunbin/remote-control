import RCCore

/// A gateway channel for the lists' tests: it answers each request type from
/// the table it is given — a result or a refusal — and keeps what it was asked.
actor ListsFakeChannel: GatewayChannel {
    nonisolated let events: AsyncStream<GatewayEvent> = AsyncStream { _ in }
    private let replies: [String: Result<JSONValue, GatewayErrorBody>]
    private(set) var asked: [GatewayRequest] = []

    init(_ replies: [String: Result<JSONValue, GatewayErrorBody>]) {
        self.replies = replies
    }

    func connect() async {}
    func disconnect() async {}

    func request(_ request: GatewayRequest) async throws -> JSONValue {
        asked.append(request)
        switch replies[request.type] {
        case .success(let value): return value
        case .failure(let refusal): throw refusal
        case nil: throw GatewayErrorBody(code: .notFound, message: "no reply for \(request.type)")
        }
    }
}
