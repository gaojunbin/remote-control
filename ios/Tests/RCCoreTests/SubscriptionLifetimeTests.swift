import Testing
import Foundation
@testable import RCCore

/// A subscription lives exactly as long as the conversation that opened it.
///
/// A `hello` means the gateway has forgotten this connection's subscriptions,
/// and a gap in the seq means an event never arrived; both resubscribe. Neither
/// may do so for a conversation that has already been closed — nothing draws
/// the result, and the gateway streams it until the next reconnect.
@Suite("Subscription lifetime")
struct SubscriptionLifetimeTests {
    private func session() -> Session {
        Session(sessionID: "s", deviceID: "d", agent: "claude", title: "T", cwd: "/tmp",
                state: .idle, control: .remote)
    }

    private func hello() -> AppFrame {
        .hello(HelloFrame(protocolVersion: RemoteProtocol.version, gatewayVersion: "test",
                          user: UserIdentity(username: "me"), devices: [], sessions: [],
                          stt: .disabled, serverTime: 0))
    }

    @Test("A closed conversation does not resubscribe itself when the socket comes back")
    @MainActor
    func closedChatIgnoresHello() async throws {
        let channel = RecordingChannel()
        let chat = ChatStore(session: session(), channel: channel)
        await chat.subscribe()
        #expect(await channel.count(of: "session.subscribe") == 1)

        await chat.close()
        chat.receive(hello())
        try await Task.sleep(for: .milliseconds(150))

        #expect(await channel.count(of: "session.subscribe") == 1,
                "the conversation that said unsubscribe stays unsubscribed")
        #expect(await channel.count(of: "session.unsubscribe") == 1)
    }

    @Test("An open conversation still resubscribes when the socket comes back")
    @MainActor
    func openChatFollowsHello() async throws {
        let channel = RecordingChannel()
        let chat = ChatStore(session: session(), channel: channel)
        await chat.subscribe()

        chat.receive(hello())
        try await Task.sleep(for: .milliseconds(150))

        #expect(await channel.count(of: "session.subscribe") == 2)
    }
}

/// A channel that records what it was asked for and refuses every request, so
/// a test can count the requests a store issues without answering any of them.
private actor RecordingChannel: GatewayChannel {
    nonisolated let events: AsyncStream<GatewayEvent>
    private var types: [String] = []

    init() { events = AsyncStream<GatewayEvent>.makeStream().stream }

    func connect() async {}
    func disconnect() async {}

    func request(_ request: GatewayRequest) async throws -> JSONValue {
        types.append(request.type)
        throw TransportError.notConnected
    }

    func count(of type: String) -> Int { types.filter { $0 == type }.count }
}
