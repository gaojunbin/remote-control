import RCCore
import SwiftUI

/// `AddDeviceModal`: a 580-point modal over the Devices list, open while there
/// is a pairing to show. Opening one asks the gateway for a code and listens
/// for its handshake; Cancel, Continue, Escape and the backdrop all close it,
/// and a code the device never claimed is given back.
struct AddDeviceModal: ViewModifier {
    @Binding var pairing: AddDevicePairing?
    @Environment(MacAppModel.self) private var model

    /// The frame handler this modal listens with while it is open.
    private static let token = "lists.pairing"

    func body(content: Content) -> some View {
        content
            .modal(isPresented: Binding(get: { pairing != nil }, set: { if !$0 { close() } }),
                   title: S.pairing.title, width: 580) {
                if let pairing { AddDeviceBody(pairing: pairing) }
            } footer: {
                Btn(S.common.cancel, action: close)
                Btn(S.common.continue, variant: .primary, action: close)
                    .disabled(pairing?.connected != true)
            }
            .onChange(of: pairing.map(ObjectIdentifier.init), initial: true) { _, opened in
                if opened != nil { start() }
            }
    }

    private func start() {
        guard let pairing else { return }
        model.connection.addFrameHandler(Self.token) { [weak pairing] frame in pairing?.receive(frame) }
        Task { await pairing.request(api: model.connection.api) }
    }

    private func close() {
        guard let open = pairing else { return }
        pairing = nil
        model.connection.removeFrameHandler(Self.token)
        guard let code = open.unclaimedCode, let api = model.connection.api else { return }
        Task { try? await api.cancelPairing(code: code) }
    }
}

/// What the modal says, top to bottom: one sentence, the one-liner with Copy
/// and the code under it, the live handshake, the scan flow, and Manual install.
struct AddDeviceBody: View {
    let pairing: AddDevicePairing
    @Environment(MacAppModel.self) private var model

    var body: some View {
        // `useNow(500)`: the countdown and the listening clock move twice a second.
        TimelineView(.periodic(from: .now, by: 0.5)) { _ in
            let now = ListsFeature.clock(for: model).now
            VStack(alignment: .leading, spacing: 0) {
                Hint(S.pairing.intro)
                    .frame(maxWidth: TextMeasure.ch(FontSize.fs13) * 46, alignment: .leading)
                if pairing.failed {
                    // `.login-error`'s -6 top margin, collapsed into the intro's 16.
                    FormError(S.pairing.createFailed).padding(.top, 10)
                }
                PairCommandBox(pairing: pairing, now: now,
                               newCode: { Task { await pairing.request(api: model.connection.api) } })
                    .padding(.top, Space.sp4)
                PairingSteps(pairing: pairing, elapsed: Format.nowMillis - pairing.openedAt)
                    .padding(.top, Space.sp4)
                PairScanSection(pairing: pairing, command: S.pairing.scanCommand(model.origin))
                    .padding(.top, Space.sp4)
                ManualInstall(pairing: pairing, origin: model.origin)
                    .padding(.top, Space.sp4)
            }
        }
    }
}
