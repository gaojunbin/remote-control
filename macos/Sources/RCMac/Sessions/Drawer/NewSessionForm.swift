import Foundation
import Observation
import RCCore

/// What the New session drawer holds (`NewSessionDrawer.tsx`'s form). A new one
/// is made each time the drawer opens, so every field starts from a fresh
/// default derived from the device and its agent rather than being reset.
///
/// The device list itself is not kept: it is the online devices in name order,
/// read afresh on every draw, so a device that goes offline leaves the menu.
@MainActor
@Observable
final class NewSessionForm {
    var deviceID: String?
    private(set) var pickedAgent: String?
    /// What the reader chose instead of the agent's own defaults. Cleared with
    /// the agent, because a model id belongs to one agent and means nothing to
    /// another.
    var options = SessionOptions()
    var cwd = ""
    /// Whether the reader typed or picked a path, which a device change keeps.
    private(set) var cwdTouched = false
    var worktree = false
    private(set) var recent: [RecentDirectory] = []
    var browsing = false
    private(set) var busy = false
    private(set) var error: String?
    let probe = DirectoryProbe()

    init(devices: [Device], preset: String?) {
        if let preset, devices.contains(where: { $0.deviceID == preset }) {
            deviceID = preset
        } else {
            deviceID = devices.first?.deviceID
        }
    }

    func device(in devices: [Device]) -> Device? {
        devices.first { $0.deviceID == deviceID }
    }

    /// The agent the reader picked, else the first one installed, else the first.
    func agent(of device: Device?) -> AgentInfo? {
        let agents = device?.agents ?? []
        return agents.first { $0.agent == pickedAgent } ?? agents.first(where: \.available) ?? agents.first
    }

    func chooseDevice(_ id: String) {
        deviceID = id
        pickedAgent = nil
        options = SessionOptions()
        if !cwdTouched { cwd = "" }
    }

    func chooseAgent(_ id: String) {
        pickedAgent = id
        options = SessionOptions()
    }

    func setPath(_ path: String) {
        cwd = path
        cwdTouched = true
    }

    /// The device's home listing, which seeds the recent list and — while the
    /// field is still empty — the working directory.
    func loadHome(channel: (any GatewayChannel)?) async {
        guard let deviceID, let channel else { return }
        do {
            let listing = try await channel.request(.dirs(deviceID: deviceID), as: DirectoryListing.self)
            guard !Task.isCancelled else { return }
            recent = listing.recent
            if cwd.isEmpty { cwd = listing.recent.first?.path ?? listing.path }
        } catch {
            guard !Task.isCancelled else { return }
            recent = []
        }
    }

    // MARK: - The session it would start

    func model(of agent: AgentInfo?) -> String? { options.model ?? agent?.defaultModel }
    func effort(of agent: AgentInfo?) -> String? { options.effort ?? agent?.defaultEffort }
    func permissionMode(of agent: AgentInfo?) -> String? { options.permissionMode ?? agent?.defaultPermissionMode }
    /// A21: nil is the standard speed, whether or not the reader said so.
    var speed: String? { options.speed ?? nil }

    func canWorktree(_ agent: AgentInfo?) -> Bool { agent?.supports(.worktree) ?? false }

    func canStart(device: Device?, agent: AgentInfo?) -> Bool {
        !busy && device != nil && agent?.available == true
            && !cwd.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
    }

    /// `session.create`, then the conversation it made. What the agent would
    /// choose on its own is sent explicitly, and the standard speed and a
    /// worktree nobody asked for are left out.
    func start(device: Device?, agent: AgentInfo?, channel: (any GatewayChannel)?) async -> Session? {
        guard canStart(device: device, agent: agent), let device, let agent else { return nil }
        busy = true
        error = nil
        defer { busy = false }
        do {
            guard let channel else { throw TransportError.notConnected }
            let request = GatewayRequest.createSession(
                deviceID: device.deviceID, agent: agent.agent,
                cwd: cwd.trimmingCharacters(in: .whitespacesAndNewlines),
                model: model(of: agent), permissionMode: permissionMode(of: agent), effort: effort(of: agent),
                speed: speed.map(SpeedChange.tier),
                worktree: canWorktree(agent) && worktree ? true : nil)
            return try await channel.request(request, as: SessionResult.self).session
        } catch {
            self.error = Self.refusal(error)
            return nil
        }
    }

    /// The device's own sentence, as the web shows `err.message`; a reply with
    /// no words of its own says nothing, as the web's empty message does, and a
    /// failure that never reached the device is the drawer's own sentence.
    static func refusal(_ error: any Error) -> String? {
        guard let reply = error as? GatewayErrorBody else { return S.newSession.startFailed }
        return reply.message.isEmpty || reply.message == reply.code.rawValue ? nil : reply.message
    }
}
