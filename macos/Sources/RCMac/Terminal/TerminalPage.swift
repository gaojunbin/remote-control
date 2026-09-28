import SwiftUI

/// `/devices/:deviceId/terminal` (A38), drawn over the whole window. A
/// placeholder until the settings feature replaces it.
public struct TerminalPage: View {
    let deviceId: String
    @Environment(MacAppModel.self) private var model

    public init(deviceId: String) { self.deviceId = deviceId }

    public var body: some View {
        PageHead(S.terminal.title)
            .padding(Space.sp6)
            .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .topLeading)
            .background(Palette.canvas)
    }
}
