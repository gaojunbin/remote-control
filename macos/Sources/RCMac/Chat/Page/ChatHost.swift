import Foundation
import Observation
import RCCore

/// The open conversation of a chat page, from the moment the page shows a
/// session to the moment it leaves it — the web's `useChat` open and close,
/// done the way the iPhone app's `AppModel.open(_:)` and `closeChat()` do: the
/// draft and an unfinished queued edit put back, the frame handler added, the
/// cached transcript painted, and on the way out all of it written back.
@MainActor
@Observable
final class ChatHost {
    private(set) var chat: ChatStore?
    private(set) var actions: ChatActions?

    /// The token the conversation's frame handler is filed under.
    static let frameToken = "chat"

    /// Open `deviceId`/`sessionId`, closing whatever this page had open first.
    /// Nothing happens until the gateway lists the session and a channel is
    /// there to subscribe on.
    func open(deviceId: String, sessionId: String, model: MacAppModel) async {
        guard chat?.deviceID != deviceId || chat?.sessionID != sessionId else { return }
        await close(model: model)
        guard let session = model.connection.session(deviceID: deviceId, sessionID: sessionId),
              let channel = model.connection.channel else { return }
        let store = ChatStore(session: session, channel: channel)
        store.agent = model.agent(for: session)
        // The preference stays in one place: changing it in Settings redraws
        // an open conversation at once.
        store.detailSource = { [settings = model.settings] in settings.timelineDetail }
        store.deviceOnline = model.device(deviceId)?.online ?? false
        store.canReachGateway = model.connection.phase.canReachGateway || model.isDemo
        store.draft = await model.drafts.draft(account: model.account, key: session.id)
        guard !Task.isCancelled else { return }
        let memory = ChatMemory.of(model)
        store.resumeEdit(memory.queuedEdits.removeValue(forKey: session.id))
        chat = store
        actions = ChatActions(chat: store, channel: channel)
        memory.open = self
        model.connection.addFrameHandler(Self.frameToken) { [weak store] frame in store?.receive(frame) }
        let cached = await model.connection.cachedTranscript(sessionID: session.sessionID, deviceID: session.deviceID)
        await store.open(cached: cached)
    }

    /// Leave the conversation: its draft, its queued edit and its transcript
    /// are kept for next time, and the gateway stops streaming it.
    func close(model: MacAppModel) async {
        guard let store = chat else { return }
        chat = nil
        actions = nil
        model.connection.removeFrameHandler(Self.frameToken)
        let memory = ChatMemory.of(model)
        if memory.open === self { memory.open = nil }
        if let edit = store.queuedEdit { memory.queuedEdits[store.key] = edit }
        await model.drafts.setDraft(store.draft, account: model.account, key: store.key)
        await model.connection.persist(transcript: store.timeline.entries.compactMap(\.sourceEvent),
                                       sessionID: store.sessionID, deviceID: store.deviceID)
        await store.close()
    }

    /// Keep what the conversation reads of the inventory in step with it: the
    /// agent's capabilities, whether the device is online, whether the gateway
    /// can be reached.
    func sync(model: MacAppModel) {
        guard let store = chat else { return }
        let agent = model.agent(for: store.session)
        if store.agent != agent { store.agent = agent }
        let online = model.device(store.deviceID)?.online ?? false
        if store.deviceOnline != online { store.deviceOnline = online }
        let reachable = model.connection.phase.canReachGateway || model.isDemo
        if store.canReachGateway != reachable { store.canReachGateway = reachable }
    }
}
