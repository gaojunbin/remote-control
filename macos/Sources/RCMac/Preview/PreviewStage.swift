import SwiftUI

extension EnvironmentValues {
    /// Set by the renderer (`RCMacPreview`) from a scenario's `stage`, and nil
    /// in the app. A feature view reads it to show a state that normally takes
    /// a click — a popover, a modal, the drawer open — so a render can capture
    /// it: `if stage == "devices.add" { adding = true }` in `onAppear`.
    @Entry public var previewStage: String?
}
