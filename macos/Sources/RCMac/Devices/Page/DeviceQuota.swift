import Foundation
import Observation
import RCCore

/// `useDeviceQuota`: the fresh half of a device page (A33).
///
/// `hello` and `agents.updated` carry an account without its windows, because
/// they come from files that change rarely. The windows are read on request, so
/// the page asks `device.agents` the moment it opens and keeps the reply here,
/// in the page's own state: nothing with limits in it reaches the stored
/// device, which the list and the new-session drawer read.
@MainActor
@Observable
final class DeviceQuota {
    /// Where the meters stand: waiting, drawn, or replaced by one line saying why.
    enum Status: Sendable, Equatable {
        case checking
        case ready
        case offline
        case failed
    }

    private(set) var status: Status = .checking
    /// Accounts with their limits, by agent id. Empty until the reply arrives.
    private(set) var accounts: [String: [AgentAccount]] = [:]
    /// Why the request failed, in the device's own words, when it did.
    private(set) var error: String?
    /// Which Refresh this is: part of what the page asks again for.
    private(set) var attempt = 0

    func refresh() { attempt += 1 }

    /// One ask, for one device in one reachability. The page runs it for every
    /// change of the three, cancelling the one before, so an answer only ever
    /// lands on the question it belongs to. An offline device is not asked at
    /// all: it has accounts and no meters, and asking could only time out.
    func check(deviceID: String, online: Bool, channel: (any GatewayChannel)?) async {
        accounts = [:]
        error = nil
        guard online else {
            status = .offline
            return
        }
        status = .checking
        do {
            guard let channel else { throw TransportError.notConnected }
            let result = try await channel.request(.agents(deviceID: deviceID), as: AgentsResult.self)
            guard !Task.isCancelled else { return }
            var fresh: [String: [AgentAccount]] = [:]
            for agent in result.agents {
                if let accounts = agent.accounts { fresh[agent.agent] = accounts }
            }
            accounts = fresh
            status = .ready
        } catch {
            guard !Task.isCancelled else { return }
            if (error as? GatewayErrorBody)?.code == .deviceOffline {
                status = .offline
            } else {
                status = .failed
                self.error = ErrorText.text(error)
            }
        }
    }
}
