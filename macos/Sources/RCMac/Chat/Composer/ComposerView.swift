import RCCore
import SwiftUI

/// The composer of one conversation: the web's `<Composer>`, placed where the
/// chat page places it, reading everything else it needs from the app model.
///
/// `docs/DESIGN.md` § "The composer" — **A draft belongs to its session**. A
/// composer is made for one conversation's store and ends with it: opening
/// another conversation draws a fresh one, which is what ends a dictation with
/// the session it was spoken for, its words staying in that session's draft.
public struct ComposerView: View {
    let chat: ChatStore
    @Environment(MacAppModel.self) private var model
    @Environment(\.previewStage) private var previewStage

    public init(chat: ChatStore) { self.chat = chat }

    public var body: some View {
        ComposerSession(chat: chat, model: model, stage: ComposerStage(previewStage))
            .id(ObjectIdentifier(chat))
    }
}

/// The composer of one store, holding the model that lives exactly as long.
private struct ComposerSession: View {
    let stage: ComposerStage?
    @State private var composer: ComposerModel

    init(chat: ChatStore, model: MacAppModel, stage: ComposerStage?) {
        self.stage = stage
        _composer = State(initialValue: ComposerModel(chat: chat, host: AppComposerHost(model: model, stage: stage)))
    }

    var body: some View {
        ComposerBody(composer: composer, stage: stage)
            // A dictation ends when the composer stops taking one: the device
            // went offline, or a terminal took the session back.
            .onChange(of: composer.voiceEnabled, initial: true) { _, enabled in composer.voice.enabled = enabled }
            .onDisappear { composer.shutDown() }
            .task {
                if let stage { await stage.run(on: composer) }
            }
    }
}
