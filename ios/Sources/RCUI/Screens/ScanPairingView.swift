import SwiftUI
import RCCore
#if os(iOS)
import UIKit
#endif

/// Amendment A23: pairing a host by scanning the QR code it prints.
///
/// The camera fills the screen and the two steps sit over it, because both of
/// them happen while the camera is open: the one-liner is read off this screen
/// and run on the host, and the code it prints is what the camera is pointed
/// at. A payload for another gateway is answered in the strip at the bottom and
/// the camera keeps looking.
struct ScanPairingView: View {
    let flow: PairingFlow
    let origins: [GatewayEndpoint]
    let installCommand: String
    let scanner: any CodeScanning

    @Environment(\.dismiss) private var dismiss
    @State private var status = L10n.string("Hold steady — the QR code is detected automatically.")
    @State private var isClaiming = false
    @State private var copied = false

    var body: some View {
        ZStack(alignment: .top) {
            scanner.makeView(onCode: offer).ignoresSafeArea()
            overlay
        }
        .background(Color.black)
    }

    private var overlay: some View {
        VStack(alignment: .leading, spacing: Theme.Space.medium) {
            HStack {
                Spacer()
                // The chip's tinted fill vanishes over a camera, so this one
                // carries the surface itself.
                Button("Cancel") { dismiss() }
                    .buttonStyle(.plain)
                    .font(Theme.Text.meta)
                    .foregroundStyle(Theme.ink)
                    .padding(.horizontal, Theme.Space.medium)
                    .frame(minHeight: Theme.Touch.minimum)
                    .background(Theme.surface, in: Capsule())
                    .accessibilityIdentifier("scan.cancel")
            }
            steps
            Spacer(minLength: 0)
            strip
        }
        .padding(.horizontal, Theme.Space.page)
        .padding(.vertical, Theme.Space.medium)
    }

    private var steps: some View {
        VStack(alignment: .leading, spacing: Theme.Space.small) {
            Text("Pair with Remote Control")
                .font(Theme.Text.title)
                .foregroundStyle(Theme.ink)
            Text("On the host you want to connect to, run:")
                .font(.subheadline)
                .foregroundStyle(Theme.inkSecondary)
                .fixedSize(horizontal: false, vertical: true)
            HStack(alignment: .top, spacing: Theme.Space.small) {
                Text(installCommand)
                    .font(Theme.mono)
                    .foregroundStyle(Theme.ink)
                    .textSelection(.enabled)
                    .fixedSize(horizontal: false, vertical: true)
                    .accessibilityIdentifier("scan.command")
                Spacer(minLength: 0)
                Button {
                    copy(installCommand)
                } label: {
                    Text(L10n.string(copied ? "Copied" : "Copy")).frame(minWidth: 54)
                }
                .buttonStyle(ChipButtonStyle())
                .accessibilityIdentifier("scan.copy")
            }
            Text("Point this camera at the QR code it prints.")
                .font(.subheadline)
                .foregroundStyle(Theme.inkSecondary)
                .fixedSize(horizontal: false, vertical: true)
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .card()
    }

    private var strip: some View {
        HStack(spacing: Theme.Space.small) {
            if isClaiming { ProgressView() }
            Text(status)
                .font(.footnote)
                .foregroundStyle(Theme.ink)
                .fixedSize(horizontal: false, vertical: true)
                .accessibilityIdentifier("scan.status")
            Spacer(minLength: 0)
        }
        .padding(Theme.Space.small)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(Theme.surface,
                    in: RoundedRectangle(cornerRadius: Theme.Radius.control, style: .continuous))
    }

    /// One payload at a time: a camera reports the same code on every frame it
    /// holds, and a second claim of a spent token would report it as used.
    private func offer(_ payload: String) {
        guard !isClaiming else { return }
        guard let link = PairingClaimLink(payload: payload, gateways: origins) else {
            status = L10n.string("That code belongs to a different gateway")
            return
        }
        isClaiming = true
        status = L10n.string("Claiming that code…")
        Task {
            do {
                // The sheet behind this one holds the same flow and follows the
                // claimed code's progress, so there is nothing to hand back.
                try await flow.claim(token: link.token)
                dismiss()
            } catch {
                status = Self.message(for: error)
                isClaiming = false
            }
        }
    }

    /// A token the gateway has forgotten and a token already claimed are the
    /// only two the person can do anything about, and each says what to do.
    private static func message(for error: any Error) -> String {
        let expired = L10n.string("This code has expired. Run the command again on the host.")
        let used = L10n.string("This code was already used.")
        if case .http(let status, _) = error as? TransportError {
            switch status {
            case 404, 410: return expired
            case 409: return used
            default: break
            }
        }
        if let body = error as? GatewayErrorBody {
            if body.code == .notFound { return expired }
            if body.code == .conflict { return used }
        }
        return L10n.string("Could not claim that code.")
    }

    private func copy(_ text: String) {
        #if os(iOS)
        UIPasteboard.general.string = text
        #endif
        copied = true
        Task { try? await Task.sleep(for: .seconds(2)); copied = false }
    }
}
