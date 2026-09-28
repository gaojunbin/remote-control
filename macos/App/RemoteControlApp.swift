import RCMac
import SwiftUI

@main
@MainActor
struct RemoteControlApp: App {
    @NSApplicationDelegateAdaptor(MacAppDelegate.self) private var delegate

    /// Built once, here. `MacAppModel.init` applies the launch arguments, and a
    /// scene's body runs again on every scene update — building the model
    /// inside it would re-apply `--reset-state` after the person signed in and
    /// throw away the gateway they had just been remembered on.
    @State private var model = MacAppModel()

    init() {
        // Before the first line of text is drawn: the web's type carries no
        // font smoothing, and neither does this app's.
        TextRendering.matchWeb()
    }

    var body: some Scene {
        MainWindowScene(model: model, delegate: delegate)
    }
}
