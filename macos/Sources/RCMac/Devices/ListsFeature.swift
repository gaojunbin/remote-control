import Foundation

/// The lists feature's launch hook: what the device and session lists keep for
/// the life of the app.
@MainActor
public enum ListsFeature {
    /// The gateway's clock per model. The model is the foundation's and has no
    /// slot for a feature's state, so the one thing the lists read off `hello`
    /// is kept here, beside the connection it came from.
    private static var clocks: [ObjectIdentifier: GatewayClock] = [:]

    static func install(on model: MacAppModel) {
        let clock = GatewayClock()
        clocks[ObjectIdentifier(model.connection)] = clock
        model.connection.addFrameHandler("lists.clock") { frame in clock.receive(frame) }
        // `useSessions.reset()`: the agent filter is the account's view of its
        // own list, so the next person starts at All agents.
        model.onSignOut { [weak model] in model?.sessions.agentFilter = nil }
    }

    /// The gateway's clock as the connection's last `hello` set it.
    static func clock(for model: MacAppModel) -> GatewayClock {
        clocks[ObjectIdentifier(model.connection)] ?? GatewayClock()
    }
}
