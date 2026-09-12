import Foundation
import Observation

/// Drives the "Add device" sheet: one short-lived code, the install one-liner,
/// and the live checklist the gateway pushes as the device comes up.
@MainActor
@Observable
public final class PairingFlow {
    public struct Step: Identifiable, Sendable, Equatable {
        public let id: PairingStep
        public let title: String
        public var done: Bool
    }

    public private(set) var grant: PairingGrant?
    public private(set) var reached: PairingStep = .waiting
    public private(set) var pairedDevice: Device?
    public private(set) var errorMessage: String?
    public private(set) var isRequesting = false
    public var platform: DevicePlatform = .macos

    @ObservationIgnored private let api: any GatewayAPI

    public init(api: any GatewayAPI) { self.api = api }

    public var command: String { grant.map { $0.install.command(for: platform) } ?? "" }

    public var code: String { grant?.code ?? "" }

    public var isComplete: Bool { pairedDevice != nil && reached == .agents }

    public func expiry(now: Date = Date()) -> String {
        guard let grant else { return "" }
        return RelativeTime.countdown(to: grant.expiresAt, now: now)
    }

    public func hasExpired(now: Date = Date()) -> Bool {
        guard let grant else { return false }
        return Double(grant.expiresAt) / 1000 <= now.timeIntervalSince1970
    }

    public var steps: [Step] {
        [
            Step(id: .waiting, title: L10n.string("Gateway ready"), done: true),
            Step(id: .enrolled, title: L10n.string("Device handshake"),
                 done: reached.order >= PairingStep.enrolled.order),
            Step(id: .online, title: L10n.string("Device online"),
                 done: reached.order >= PairingStep.online.order),
            Step(id: .agents, title: L10n.string("Detect installed agents"),
                 done: reached.order >= PairingStep.agents.order)
        ]
    }

    /// Agent names the paired device reported, for the last checklist row.
    public var detectedAgents: String {
        pairedDevice?.availableAgents.map(\.agent).joined(separator: " · ") ?? ""
    }

    public func begin() async {
        guard !isRequesting else { return }
        isRequesting = true
        errorMessage = nil
        defer { isRequesting = false }
        do {
            grant = try await api.beginPairing()
            reached = .waiting
            pairedDevice = nil
        } catch {
            errorMessage = (error as? TransportError)?.errorDescription ?? error.localizedDescription
        }
    }

    public func cancel() async {
        guard let code = grant?.code else { return }
        grant = nil
        reached = .waiting
        pairedDevice = nil
        try? await api.cancelPairing(code: code)
    }

    public func receive(_ frame: AppFrame) {
        guard case .pairingProgress(let progress) = frame, progress.code == grant?.code else { return }
        if progress.step.order >= reached.order { reached = progress.step }
        if let device = progress.device { pairedDevice = device }
    }
}
