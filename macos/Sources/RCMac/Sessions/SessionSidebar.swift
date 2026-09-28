import SwiftUI

/// The conversation page's session list. A placeholder until the lists
/// feature replaces it.
public struct SessionSidebar: View {
    let deviceId: String
    let sessionId: String
    @Environment(MacAppModel.self) private var model

    public init(deviceId: String, sessionId: String) {
        self.deviceId = deviceId
        self.sessionId = sessionId
    }

    public var body: some View {
        PageHead(S.sessions.title)
            .padding(Space.sp4)
            .frame(width: LayoutSize.sidebarW, alignment: .topLeading)
            .frame(maxHeight: .infinity, alignment: .top)
            .background(Palette.canvas)
    }
}
