import SwiftUI

/// The menu bar. The Mac adds keyboard shortcuts to places the web already
/// has and nothing else: ⌘1, ⌘2 and ⌘3 for the three tabs, ⌘, for Settings,
/// ⌘N for New session, and ⌘[ and ⌘] to walk the history as a browser does.
/// The standard Edit menu stays as it is.
public struct AppCommands: Commands {
    let model: MacAppModel

    public init(model: MacAppModel) { self.model = model }

    /// Nothing but the form, or the Update required screen, is reachable
    /// without an account the gateway accepts.
    private var canNavigate: Bool { model.isSignedIn && model.connection.updateRequired == nil }

    public var body: some Commands {
        CommandGroup(replacing: .newItem) {
            Button(S.sessions.new) { model.router.requestNewSession() }
                .keyboardShortcut("n")
                .disabled(!canNavigate)
        }
        CommandGroup(replacing: .appSettings) {
            Button(S.mac.menuSettings) { model.router.go(.settings) }
                .keyboardShortcut(",")
                .disabled(!canNavigate)
        }
        CommandMenu(S.mac.menuGo) {
            Group {
                Button(S.nav.devices) { model.router.go(.devices) }
                    .keyboardShortcut("1")
                Button(S.nav.sessions) { model.router.go(.sessions) }
                    .keyboardShortcut("2")
                Button(S.nav.settings) { model.router.go(.settings) }
                    .keyboardShortcut("3")
            }
            .disabled(!canNavigate)
            Divider()
            Button(S.mac.menuBack) { model.router.back() }
                .keyboardShortcut("[")
                .disabled(!canNavigate || !model.router.canGoBack)
            Button(S.mac.menuForward) { model.router.forward() }
                .keyboardShortcut("]")
                .disabled(!canNavigate || !model.router.canGoForward)
        }
    }
}
