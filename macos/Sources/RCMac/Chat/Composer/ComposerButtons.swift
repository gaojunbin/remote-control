import AppKit
import RCCore
import SwiftUI

/// `.composer-buttons`: attach, the mic, the ⋯ beside Send while a turn runs,
/// and the one primary slot — Send, Queue or Answer, or the spinner while the
/// words are still on their way.
struct ComposerButtons: View {
    let composer: ComposerModel
    let sendMenuOpen: Bool

    var body: some View {
        let gates = composer.gates
        HStack(spacing: 2) {
            if gates.showAttach {
                IconBtn(.paperclip, size: 16, label: S.composer.attach) { chooseFiles() }
                    .disabled(gates.disabled)
            }
            if composer.host.sttEnabled {
                IconBtn(.mic, size: 16, label: S.composer.micStart) { composer.startVoice() }
                    .disabled(gates.disabled)
            }
            if composer.showsSendMenu {
                Popover(align: .end, side: .top, chevron: false, triggerStyle: SendAltStyle(),
                        ariaLabel: S.composer.sendOptions, initiallyOpen: sendMenuOpen) {
                    Text("⋯").css(FontSize.fs13).accessibilityHidden(true)
                } content: { close in
                    MenuList {
                        MenuItemRow(S.composer.interruptAndSend) {
                            close()
                            composer.submit(.interrupt)
                        }
                    }
                }
            }
            SendSlot(composer: composer)
        }
        .padding(.bottom, 2)
    }

    /// The paperclip opens the system's file dialog, as a browser's file input
    /// does, on the window the composer is in.
    private func chooseFiles() {
        let panel = NSOpenPanel()
        panel.allowsMultipleSelection = true
        panel.canChooseFiles = true
        panel.canChooseDirectories = false
        let composer = composer
        let chosen: (NSApplication.ModalResponse) -> Void = { response in
            guard response == .OK else { return }
            composer.attach(panel.urls.map(AttachmentSource.file))
        }
        if let window = NSApp.keyWindow ?? NSApp.mainWindow {
            panel.beginSheetModal(for: window, completionHandler: chosen)
        } else {
            panel.begin(completionHandler: chosen)
        }
    }
}

/// What the one primary slot holds (`PrimarySlot`): Send while the field holds
/// what will be sent, the spinner while the model or the device still has it.
struct SendSlot: View {
    let composer: ComposerModel

    var body: some View {
        if composer.slot == .send {
            let running = composer.gates.running
            Button { composer.primarySubmit() } label: {
                if running {
                    Text(composer.primaryLabel).css(FontSize.fs13, weight: .medium)
                } else {
                    Icon(.arrowUp, size: 15)
                }
            }
            .buttonStyle(.btn(.primary, size: .small))
            .disabled(composer.primaryDisabled)
            .accessibilityLabel(running ? composer.primaryLabel : S.composer.send)
        } else {
            // Dictation is over and the model has the words, or an edited
            // message is on its way back into the line (A43): the slot waits
            // where Send was, and takes no click while it does.
            WorkingPill(label: composer.returning ? S.chat.sending : S.voice.polishing)
        }
    }
}
