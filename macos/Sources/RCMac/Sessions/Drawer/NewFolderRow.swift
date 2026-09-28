import SwiftUI

/// `NewFolderRow.tsx` (A37): the one row the directory picker reveals to name a
/// folder — a mono field that takes the focus, Create and Cancel — and the
/// device's refusal under it. It owns no request: the picker sends
/// `device.mkdir` and hands the outcome back, so the name survives a clash and
/// can be edited where it was typed.
struct NewFolderRow: View {
    let browser: DirectoryBrowser
    @Environment(\.overlayRegistry) private var overlays
    @State private var escapeID = UUID()
    @State private var fieldFocused = false
    /// Whether the name had the focus when it was sent: the browser keeps it
    /// there through the request, while the field is disabled.
    @State private var refocusAfter = false
    @State private var refocus = 0

    var body: some View {
        @Bindable var browser = browser
        VStack(alignment: .leading, spacing: 0) {
            HStack(spacing: Space.sp2) {
                SizedField(text: $browser.folderName, placeholder: S.newSession.newFolderName, mono: true,
                           fontSize: FontSize.fs13, height: 32, autofocus: true, refocus: refocus,
                           onFocus: focusChanged, onSubmit: create)
                    .disabled(browser.folderBusy)
                Button(S.newSession.newFolderCreate, action: create)
                    .buttonStyle(RowBtnStyle(primary: true))
                    .disabled(browser.folderBusy || browser.folderName.trimmingCharacters(in: .whitespaces).isEmpty)
                Button(S.common.cancel) { browser.stopNaming() }
                    .buttonStyle(RowBtnStyle(primary: false))
                    .disabled(browser.folderBusy)
            }
            if let error = browser.folderError {
                Text(error)
                    .css(FontSize.fs13)
                    .foregroundStyle(Palette.danger)
                    .padding(.top, Space.sp2)
            }
        }
        .onDisappear { holdEscape(false) }
        .onChange(of: browser.folderBusy) { _, busy in
            if busy {
                refocusAfter = refocusAfter || fieldFocused
            } else if refocusAfter {
                refocusAfter = false
                refocus += 1
            }
        }
    }

    private func create() {
        refocusAfter = fieldFocused
        Task { await browser.createFolder() }
    }

    private func focusChanged(_ focused: Bool) {
        fieldFocused = focused
        holdEscape(focused)
    }

    /// Escape belongs to the row, not to the modal around it, while the name
    /// has the focus: the row stands as the newest overlay for that long, so
    /// the one Escape closes is the row.
    private func holdEscape(_ focused: Bool) {
        if focused {
            overlays?.register(escapeID, openedAt: OverlayClock.next(), isPopover: false) { browser.stopNaming() }
        } else {
            overlays?.unregister(escapeID)
        }
    }
}
