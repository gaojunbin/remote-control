import SwiftUI

/// The conversation, `/sessions/:deviceId/:sessionId`, drawn over the whole
/// window. A placeholder until the chat feature replaces it.
public struct ChatPage: View {
    let deviceId: String
    let sessionId: String
    @Environment(MacAppModel.self) private var model

    public init(deviceId: String, sessionId: String) {
        self.deviceId = deviceId
        self.sessionId = sessionId
    }

    public var body: some View {
        let title = model.connection.session(deviceID: deviceId, sessionID: sessionId).map(S.sessionTitle)
        PageHead(title ?? S.sessions.untitled)
            .padding(Space.sp6)
            .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .topLeading)
            .background(Palette.canvas)
    }
}
