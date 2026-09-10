import SwiftUI
import RCUI

/// A macOS host for the SwiftUI layer, so a screen can be iterated on without
/// booting a simulator. It always runs the offline demo.
@main
struct PreviewApp: App {
    var body: some Scene {
        WindowGroup {
            RootView(model: AppModel(arguments: ["--demo"]))
                .frame(minWidth: 402, idealWidth: 402, minHeight: 760, idealHeight: 874)
        }
        .windowResizability(.contentSize)
    }
}
