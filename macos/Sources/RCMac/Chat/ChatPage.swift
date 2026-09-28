import RCCore
import SwiftUI

/// `web/src/features/chat/ChatPage.tsx`, `/sessions/:deviceId/:sessionId`,
/// drawn over the whole window: the session sidebar and the conversation at
/// 1024 points and wider, the conversation alone with a way back below
/// (`docs/DESIGN.md` § "The Mac app"). The page owns the conversation's life:
/// it opens when the page shows a session and closes when the page leaves it.
public struct ChatPage: View {
    let deviceId: String
    let sessionId: String
    @Environment(MacAppModel.self) private var model
    @Environment(\.layoutClass) private var layout
    @State private var host = ChatHost()

    public init(deviceId: String, sessionId: String) {
        self.deviceId = deviceId
        self.sessionId = sessionId
    }

    public var body: some View {
        HStack(spacing: 0) {
            if !layout.maxWidth1023 {
                SessionSidebar(deviceId: deviceId, sessionId: sessionId)
                    .frame(width: LayoutSize.sidebarW)
                    .frame(maxHeight: .infinity)
            }
            column
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .background(Palette.canvas)
        .task(id: OpenRequest(deviceId: deviceId, sessionId: sessionId, listed: listed, channel: hasChannel)) {
            await host.open(deviceId: deviceId, sessionId: sessionId, model: model)
        }
        .onChange(of: InventoryState(model: model, deviceId: deviceId, sessionId: sessionId)) { host.sync(model: model) }
        .onDisappear {
            let host = host
            let model = model
            Task { await host.close(model: model) }
        }
    }

    @ViewBuilder private var column: some View {
        if !listed && model.connection.hasSnapshot {
            ChatMissing()
                .background(Palette.surface)
                .background(alignment: .top) { WindowStrip().frame(height: LayoutSize.headerH) }
        } else if let chat = host.chat, let actions = host.actions,
                  chat.deviceID == deviceId, chat.sessionID == sessionId {
            ChatMain(chat: chat, actions: actions)
        } else {
            Palette.surface
                .background(alignment: .top) { WindowStrip().frame(height: LayoutSize.headerH) }
        }
    }

    /// The conversation a chat page of `model` has open, for a preview that puts
    /// a block into it as a device would.
    @MainActor
    public static func conversation(in model: MacAppModel) -> ChatStore? { ChatMemory.of(model).open?.chat }

    /// Whether the gateway lists the session the route names.
    private var listed: Bool { model.connection.session(deviceID: deviceId, sessionID: sessionId) != nil }

    private var hasChannel: Bool { model.connection.channel != nil }
}

/// What opening a conversation waits for: the route, the gateway listing the
/// session, and a channel to subscribe on.
private struct OpenRequest: Equatable {
    let deviceId: String
    let sessionId: String
    let listed: Bool
    let channel: Bool
}

/// What the open conversation reads of the inventory, so a change to it —
/// the agent's capabilities, the device going offline, the socket coming back
/// — reaches the conversation.
private struct InventoryState: Equatable {
    let agent: AgentInfo?
    let online: Bool
    let reachable: Bool

    @MainActor
    init(model: MacAppModel, deviceId: String, sessionId: String) {
        let session = model.connection.session(deviceID: deviceId, sessionID: sessionId)
        agent = session.flatMap { model.agent(for: $0) }
        online = model.device(deviceId)?.online ?? false
        reachable = model.connection.phase.canReachGateway || model.isDemo
    }
}
