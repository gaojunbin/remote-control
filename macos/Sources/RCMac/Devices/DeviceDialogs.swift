import RCCore
import SwiftUI

/// The Devices page's three dialogs: the rename modal, and the Retry update and
/// Revoke confirmations. Each closes once its request went through and stays
/// open when it was refused, as the web's do.
struct DeviceDialogs: ViewModifier {
    @Binding var renaming: Device?
    @Binding var revoking: Device?
    @Binding var retrying: Device?
    @Environment(MacAppModel.self) private var model
    @State private var renameValue = ""
    @State private var busy = false

    func body(content: Content) -> some View {
        content
            .modal(isPresented: ListPresence.of($renaming), title: S.devices.renameTitle, width: 420) {
                VStack(alignment: .leading, spacing: 0) {
                    FieldLabel(S.devices.renameLabel)
                    WebField(text: $renameValue)
                }
            } footer: {
                Btn(S.common.cancel) { renaming = nil }
                Btn(S.common.save, variant: .primary, busy: busy, action: rename)
                    .disabled(renameValue.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty)
            }
            .modifier(RetryUpdateDialog(device: $retrying))
            .confirmDialog(isPresented: ListPresence.of($revoking), title: S.devices.revokeTitle,
                           body: S.devices.revokeBody(revoking?.name ?? ""), confirmLabel: S.devices.revokeConfirm,
                           danger: true, busy: busy, onConfirm: revoke)
            .onChange(of: renaming?.deviceID, initial: true) { renameValue = renaming?.name ?? "" }
    }

    private func rename() {
        guard let device = renaming, let api = model.connection.api else { return }
        let name = renameValue.trimmingCharacters(in: .whitespacesAndNewlines)
        busy = true
        Task {
            defer { busy = false }
            // The gateway's `device.updated` carries the new name to the list.
            guard (try? await api.renameDevice(device.deviceID, name: name)) != nil else { return }
            if renaming?.deviceID == device.deviceID { renaming = nil }
        }
    }

    private func revoke() {
        guard let device = revoking, let api = model.connection.api else { return }
        busy = true
        Task {
            defer { busy = false }
            // `device.removed` takes the row away.
            guard (try? await api.revokeDevice(device.deviceID)) != nil else { return }
            if revoking?.deviceID == device.deviceID { revoking = nil }
        }
    }
}

/// A36: the one update a person asks for — a retry of one that failed —
/// confirmed with the version it would install. Shared by the Devices page and
/// a device's own page.
struct RetryUpdateDialog: ViewModifier {
    @Binding var device: Device?
    @Environment(MacAppModel.self) private var model
    @State private var busy = false

    func body(content: Content) -> some View {
        content.confirmDialog(isPresented: ListPresence.of($device), title: S.devices.updateTitle,
                              body: S.devices.updateBody(device?.name ?? "", model.connection.config.servedVersion),
                              confirmLabel: S.devices.updateConfirm, busy: busy, onConfirm: retry)
    }

    /// A refusal is the device's own words, which the row then shows; either
    /// way nothing is left to confirm.
    private func retry() {
        guard let target = device, model.connection.config.servedBuild != nil else { return }
        busy = true
        Task {
            await model.updateDevice(target)
            device = nil
            busy = false
        }
    }
}
