import AppKit
import SwiftUI

/// The app's one window (`docs/DESIGN.md` § "The Mac app" → **The window is
/// the page**): no title bar, 1280 × 860 at first and never smaller than
/// 480 × 560, always light. There is no `WindowGroup` and no Settings scene —
/// Settings is a page, as it is on the web.
public struct MainWindowScene: Scene {
    let model: MacAppModel
    let delegate: MacAppDelegate

    public init(model: MacAppModel, delegate: MacAppDelegate) {
        self.model = model
        self.delegate = delegate
    }

    public var body: some Scene {
        Window(S.productName, id: MainWindowScene.id) {
            MainWindowContent(model: model, delegate: delegate)
        }
        .windowStyle(.hiddenTitleBar)
        .defaultSize(width: 1280, height: 860)
        .windowResizability(.contentMinSize)
        .commands { AppCommands(model: model) }
    }

    public static let id = "main"
}

private struct MainWindowContent: View {
    let model: MacAppModel
    let delegate: MacAppDelegate
    @Environment(\.openWindow) private var openWindow

    var body: some View {
        RootView()
            .environment(model)
            .frame(minWidth: 480, minHeight: 560)
            .onAppear {
                delegate.model = model
                // Closing the window leaves the app running; the Dock icon, a
                // notification or a menu command brings it back.
                model.showWindow = {
                    openWindow(id: MainWindowScene.id)
                    NSApp.activate()
                }
            }
            .task { await model.restoreOrPrompt() }
    }
}
