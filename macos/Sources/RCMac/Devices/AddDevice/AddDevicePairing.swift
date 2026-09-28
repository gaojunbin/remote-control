import Foundation
import Observation
import RCCore

/// One visit to the Add device modal (`AddDeviceModal.tsx`, with
/// `usePairingProgress`): the code it minted, the handshake frames that name
/// that code, and what the reader asked it to show. A new one is made each time
/// the modal opens, so each visit requests exactly one pairing code.
@MainActor
@Observable
final class AddDevicePairing {
    /// Which of the two one-liners was just copied, for its button to say so.
    enum Copied: Sendable, Equatable {
        case code
        case scan
    }

    private(set) var grant: PairingGrant?
    /// The request for a code failed. The web never takes the line back, not
    /// even when a later New code goes through.
    private(set) var failed = false
    var manual = false
    private(set) var copied: Copied?
    /// When the modal started listening, so it can say how long.
    let openedAt = Format.nowMillis

    /// The newest `pairing.progress` frame, for whichever code it names.
    private var latest: PairingProgress?
    @ObservationIgnored private var copiedReset: Task<Void, Never>?

    /// The handshake of this visit's code; nothing while another code's
    /// progress is the one in flight.
    var live: PairingProgress? {
        guard let grant, let latest, latest.code == grant.code else { return nil }
        return latest
    }

    var step: PairingStep? { live?.step }

    /// The device answered: it is enrolled and its socket is up.
    var connected: Bool { step == .online || step == .agents }

    /// The one command to run on the host. The gateway hands out `install.macos`
    /// and `install.linux` so a platform whose command really differs can be
    /// added without a wire change, but the two are the same string today: the
    /// installer tells macOS from Linux itself, so nobody is asked which.
    var command: String { grant?.install.macos ?? "" }

    /// Ask the gateway for a code — on opening, and again for New code.
    func request(api: (any GatewayAPI)?) async {
        do {
            guard let api else { throw TransportError.notConnected }
            grant = try await api.beginPairing()
        } catch {
            failed = true
        }
    }

    func receive(_ frame: AppFrame) {
        if case .pairingProgress(let progress) = frame { latest = progress }
    }

    /// The code a closing modal gives back: one the device has not claimed.
    var unclaimedCode: String? {
        guard let grant, !connected else { return nil }
        return grant.code
    }

    /// Put the text on the pasteboard, and say Copied for 1.4 s.
    func copy(_ text: String, as target: Copied) {
        Clipboard.write(text)
        copied = target
        copiedReset?.cancel()
        copiedReset = Task { [weak self] in
            try? await Task.sleep(for: .milliseconds(1400))
            guard !Task.isCancelled else { return }
            self?.copied = nil
        }
    }

    /// How long the code has left on the gateway's clock, in milliseconds.
    func remaining(now: Int64) -> Int64 {
        guard let grant else { return 0 }
        return max(0, grant.expiresAt - now)
    }

    func hasExpired(now: Int64) -> Bool {
        grant != nil && remaining(now: now) == 0 && !connected
    }
}
