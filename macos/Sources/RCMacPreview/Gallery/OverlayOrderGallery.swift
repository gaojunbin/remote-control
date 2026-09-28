import Observation
import RCMac
import SwiftUI

/// A regression check for the overlay layer: one control carrying a modal, a
/// modal with a close button, the drawer, a confirmation and an anchored panel,
/// chained in one of three orders, with one of them opened after the first
/// frame. The modal is built from a form the view's body reads, made for the
/// opening as the web's dialogs make theirs, and the form changes while the
/// modal is open. Whatever the order, the open overlay is drawn whole and
/// current; a modal or the drawer blurs the page, and a modal blurs the drawer
/// under it, opened beside it or from inside it. Focus moves as the web's does:
/// to the rename modal's field, and to no field where a close button or a
/// button comes first.
struct OverlayOrderGallery: View {
    /// The last hands the modal's form on as a binding to the views that draw
    /// it, in a body that never reads it: the other way a dialog stays current.
    enum Order { case confirmFirst, panelFirst, modalFirst, formBoundToChildren }
    enum Overlay { case modal, drawer, confirm, panel, closableModal, modalOverDrawer, modalInDrawer }

    let order: Order
    let open: Overlay
    @State private var form: RenameForm?
    @State private var drawer = false
    @State private var confirm = false
    @State private var panel = false
    @State private var closable = false
    @State private var picker = false

    var body: some View {
        VStack(alignment: .leading, spacing: Space.sp4) {
            PageHead("Overlay order") {}
            chained
            Hint("One control carries every kind of overlay; the order it chains them in changes nothing.")
            Spacer()
        }
        .padding(Space.sp8)
        .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .topLeading)
        .background(Palette.canvas)
        .task { await openAfterFirstFrame() }
    }

    @ViewBuilder private var chained: some View {
        let trigger = Button("Actions") {}.buttonStyle(.pill)
        switch order {
        case .confirmFirst:
            trigger.signOutConfirm($confirm).renameModal(form, isPresented: $form.isOpen)
                .newSessionDrawer($drawer, picker: $picker).actionsPanel($panel).folderModal($closable)
        case .panelFirst:
            trigger.actionsPanel($panel).folderModal($closable).newSessionDrawer($drawer, picker: $picker)
                .renameModal(form, isPresented: $form.isOpen).signOutConfirm($confirm)
        case .modalFirst:
            trigger.folderModal($closable).renameModal(form, isPresented: $form.isOpen).actionsPanel($panel)
                .signOutConfirm($confirm).newSessionDrawer($drawer, picker: $picker)
        case .formBoundToChildren:
            trigger.signOutConfirm($confirm).renameModal(through: $form)
        }
    }

    private func openAfterFirstFrame() async {
        try? await Task.sleep(for: .milliseconds(200))
        switch open {
        case .modal:
            let opened = RenameForm()
            form = opened
            try? await Task.sleep(for: .milliseconds(300))
            opened.name = "ci-runner-01"
        case .drawer: drawer = true
        case .confirm: confirm = true
        case .panel: panel = true
        case .closableModal: closable = true
        case .modalOverDrawer:
            drawer = true
            try? await Task.sleep(for: .milliseconds(300))
            let opened = RenameForm()
            opened.name = "ci-runner-01"
            form = opened
        case .modalInDrawer:
            drawer = true
            try? await Task.sleep(for: .milliseconds(300))
            picker = true
        }
    }
}

/// The modal's form: empty when it opens, and Save waits for a name.
@MainActor
@Observable
final class RenameForm {
    var name = ""
    var ready: Bool { !name.isEmpty }
}

private struct RenameFields: View {
    @Bindable var form: RenameForm

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            FieldLabel("Device name")
            WebField(text: $form.name)
        }
    }
}

/// A child view's `@Binding` is kept current where a binding captured in the
/// modal's closure would hold the value it had when the body last ran.
private struct BoundFields: View {
    @Binding var form: RenameForm?

    var body: some View {
        if let form { RenameFields(form: form) }
    }
}

private struct BoundFooter: View {
    @Binding var form: RenameForm?

    var body: some View {
        if let opened = form {
            Btn(S.common.cancel) { form = nil }
            Btn(S.common.save, variant: .primary) { form = nil }
                .disabled(!opened.ready)
        }
    }
}

fileprivate extension Optional {
    /// Open while there is a form, and closing lets the form go.
    var isOpen: Bool {
        get { self != nil }
        set { if !newValue { self = nil } }
    }
}

private extension View {
    func renameModal(_ form: RenameForm?, isPresented: Binding<Bool>) -> some View {
        modal(isPresented: isPresented, title: "Rename device", width: 420) {
            if let form { RenameFields(form: form) }
        } footer: {
            if let form {
                Btn(S.common.cancel) { isPresented.wrappedValue = false }
                Btn(S.common.save, variant: .primary) { isPresented.wrappedValue = false }
                    .disabled(!form.ready)
            }
        }
    }

    func renameModal(through form: Binding<RenameForm?>) -> some View {
        modal(isPresented: form.isOpen, title: "Rename device", width: 420) {
            BoundFields(form: form)
        } footer: {
            BoundFooter(form: form)
        }
    }

    /// The drawer, with a modal it opens itself, as the New session drawer
    /// opens its directory picker.
    func newSessionDrawer(_ isPresented: Binding<Bool>, picker: Binding<Bool>) -> some View {
        drawer(isPresented: isPresented, title: "New session", subtitle: "Pick a device to start on") {
            FieldLabel("Working directory")
            WebField(text: .constant("~/github/remote-control"), mono: true)
                .folderModal(picker)
        } footer: {
            Btn("Start session", variant: .primary, size: .block) {}
        }
    }

    /// A modal whose head holds a close button, like the directory picker.
    func folderModal(_ isPresented: Binding<Bool>) -> some View {
        modal(isPresented: isPresented, title: "Choose a folder", width: 520, showClose: true) {
            FieldLabel("Path")
            WebField(text: .constant("~/github"), mono: true)
        }
    }

    func signOutConfirm(_ isPresented: Binding<Bool>) -> some View {
        confirmDialog(isPresented: isPresented, title: "Sign out of this gateway?",
                      body: "Cached sessions and drafts leave this device. Nothing changes on your machines.",
                      confirmLabel: "Sign out", danger: true) {}
    }

    func actionsPanel(_ isPresented: Binding<Bool>) -> some View {
        anchoredPanel(isPresented: isPresented) {
            MenuList {
                MenuItemRow(S.common.rename) {}
                MenuItemRow(S.common.revoke, danger: true) {}
            }
        }
    }
}
