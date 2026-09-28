import Foundation

/// The composer feature's launch hook. The files of every session's draft live
/// for the app's life, and nothing of an account's stays behind it: signing
/// out empties them, as `web/src/stores/signOut.ts` resets the drafts store.
@MainActor
public enum ComposerFeature {
    static func install(on model: MacAppModel) {
        let drafts = ComposerDrafts.of(model)
        model.onSignOut { drafts.reset() }
    }
}
