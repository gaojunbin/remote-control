import SwiftUI
import RCUI

@main
@MainActor
struct RemoteControlApp: App {
    @UIApplicationDelegateAdaptor(RemoteNotificationAppDelegate.self) private var appDelegate

    /// Built once, here. `AppModel.init` applies the launch arguments, and the
    /// `WindowGroup` body runs again on every scene update — building the model
    /// inside it would re-apply `--reset-state` after the user signed in and
    /// throw away the gateway they had just been remembered on.
    @State private var model = AppModel()

    var body: some Scene {
        WindowGroup {
            RootView(model: model)
        }
    }
}
