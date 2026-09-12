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

    /// The code this flow is following. Amendment A23: it is minted here for
    /// the code flow and handed over by a claim for the scan flow, and only the
    /// first of those carries an install command — the host that printed a QR
    /// code has already run one.
    public struct Pairing: Sendable, Hashable {
        public let code: String
        public let expiresAt: Int64
        public let install: InstallCommands?

        public init(code: String, expiresAt: Int64, install: InstallCommands? = nil) {
            self.code = code
            self.expiresAt = expiresAt
            self.install = install
        }
    }

    public private(set) var pairing: Pairing?
    public private(set) var reached: PairingStep = .waiting
    public private(set) var pairedDevice: Device?
    public private(set) var errorMessage: String?
    public private(set) var isRequesting = false
    public var platform: DevicePlatform = .macos

    @ObservationIgnored private let api: any GatewayAPI

    public init(api: any GatewayAPI) { self.api = api }

    public var command: String { pairing?.install?.command(for: platform) ?? "" }

    public var code: String { pairing?.code ?? "" }

    public var isComplete: Bool { pairedDevice != nil && reached == .agents }

    public func expiry(now: Date = Date()) -> String {
        guard let pairing else { return "" }
        return RelativeTime.countdown(to: pairing.expiresAt, now: now)
    }

    public func hasExpired(now: Date = Date()) -> Bool {
        guard let pairing else { return false }
        return Double(pairing.expiresAt) / 1000 <= now.timeIntervalSince1970
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
            let grant = try await api.beginPairing()
            pairing = Pairing(code: grant.code, expiresAt: grant.expiresAt, install: grant.install)
            reached = .waiting
            pairedDevice = nil
        } catch {
            errorMessage = (error as? TransportError)?.errorDescription ?? error.localizedDescription
        }
    }

    /// Amendment A23: follow the code the gateway minted for a scanned host.
    /// The code this sheet was already showing is given back first, so one
    /// sheet never leaves two codes outstanding.
    public func claim(token: String) async throws {
        let claim = try await api.claimPairingRequest(token: token)
        await cancel()
        pairing = Pairing(code: claim.code, expiresAt: claim.expiresAt)
        errorMessage = nil
    }

    public func cancel() async {
        guard let code = pairing?.code else { return }
        pairing = nil
        reached = .waiting
        pairedDevice = nil
        try? await api.cancelPairing(code: code)
    }

    public func receive(_ frame: AppFrame) {
        guard case .pairingProgress(let progress) = frame, progress.code == pairing?.code else { return }
        if progress.step.order >= reached.order { reached = progress.step }
        if let device = progress.device { pairedDevice = device }
    }
}
