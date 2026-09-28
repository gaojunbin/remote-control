import RCMac
import SwiftUI

/// The overlays, open for a render: a menu open from a pill, and — each in a
/// scenario of its own — a modal with a footer, the drawer and a confirmation.
struct OverlayGallery: View {
    enum Kind { case popover, modal, drawer, confirm }

    let kind: Kind
    @State private var modal = false
    @State private var drawer = false
    @State private var confirm = false
    @State private var value: String? = "sonnet"
    @State private var name = "ci-runner-01"

    var body: some View {
        VStack(alignment: .leading, spacing: Space.sp4) {
            PageHead("Overlays") {
                Btn("Add device", icon: .plus, variant: .primary) {}
            }
            HStack(spacing: Space.sp3) {
                SelectMenu(options: [
                    MenuOption(id: "sonnet", label: "Sonnet 4.5", description: "The default for most work"),
                    MenuOption(id: "opus", label: "Opus 4.6", description: "Deeper reasoning, slower"),
                    MenuOption(id: "haiku", label: "Haiku 4.5", disabled: true)
                ], value: value, ariaLabel: "Model", initiallyOpen: kind == .popover) { value = $0 } label: {
                    Text(value == "opus" ? "Opus 4.6" : "Sonnet 4.5")
                }
                Button("Another pill") {}.buttonStyle(.pill)
            }
            Hint("The page under an overlay is dimmed and blurred.")
            Spacer()
        }
        .padding(Space.sp8)
        .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .topLeading)
        .background(Palette.canvas)
        .modal(isPresented: $modal, title: "Rename device", width: 420) {
            VStack(alignment: .leading, spacing: 0) {
                FieldLabel("Device name")
                WebField(text: $name)
            }
        } footer: {
            Btn(S.common.cancel) { modal = false }
            Btn(S.common.save, variant: .primary) { modal = false }
        }
        .drawer(isPresented: $drawer, title: "New session", subtitle: "Pick a device to start on") {
            FieldLabel("Working directory")
            WebField(text: .constant("~/github/remote-control"), mono: true)
            Hint("A drawer section.")
        } footer: {
            Btn("Start session", variant: .primary, size: .block) {}
        }
        .confirmDialog(isPresented: $confirm, title: "Sign out of this gateway?",
                       body: "Cached sessions and drafts leave this device. Nothing changes on your machines.",
                       confirmLabel: "Sign out", danger: true) {}
        .onAppear {
            modal = kind == .modal
            drawer = kind == .drawer
            confirm = kind == .confirm
        }
    }
}
