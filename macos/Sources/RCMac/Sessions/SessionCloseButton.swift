import RCCore
import SwiftUI

/// `SessionCloseButton.tsx`: the Close action at the end of a session row (A39,
/// `docs/DESIGN.md` § "Close, then the Archive"). It ends the session on the
/// machine — the device interrupts the turn and lets go of the agent — and the
/// row lands in the Archive when the device's reply comes back.
///
/// The question is asked only while the session is working, by the dot's own
/// tone, because that is the only moment there is unfinished work to lose. An
/// idle session closes on the click.
struct SessionCloseButton: View {
    let session: Session
    let online: Bool
    /// A preview stage's: the question drawn open.
    var asksOnAppear = false
    @Environment(MacAppModel.self) private var model
    @State private var asking = false
    @State private var busy = false

    var body: some View {
        Button(action: tap) { Icon(.circleX, size: 15) }
            .buttonStyle(.iconBtn)
            .disabled(busy)
            .help(S.sessions.close)
            .accessibilityLabel(S.sessions.close)
            .confirmDialog(isPresented: $asking, title: S.sessions.closeTitle, body: S.sessions.closeBody,
                           confirmLabel: S.sessions.close, danger: true, busy: busy, onConfirm: close)
            .onAppear { if asksOnAppear && SessionClose.asksFirst(session, online: online) { asking = true } }
    }

    private func tap() {
        if SessionClose.asksFirst(session, online: online) { asking = true } else { close() }
    }

    /// Closes, and keeps the question open when the device refused: the row is
    /// only closed once the store holds it archived.
    private func close() {
        busy = true
        Task {
            await model.connection.close(session: session)
            if model.connection.session(deviceID: session.deviceID, sessionID: session.sessionID)?.archived != false {
                asking = false
            }
            busy = false
        }
    }
}
