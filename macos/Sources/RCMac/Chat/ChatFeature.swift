import Foundation

/// The chat feature's launch hook: a sign-out closes the conversation that is
/// open while the connection still names the account, and forgets the queued
/// edits it kept (`web/src/stores/signOut.ts`).
@MainActor
public enum ChatFeature {
    static func install(on model: MacAppModel) {
        model.onSignOut { [weak model] in
            guard let model else { return }
            await ChatMemory.of(model).signOut(model)
        }
    }
}
