import Foundation

/// The features' launch hooks, called once when the model is built. Each
/// feature registers here whatever it keeps for the life of the app — a
/// sign-out handler, a transition handler, a frame handler — without touching
/// the model's own file.
@MainActor
enum Features {
    static func install(on model: MacAppModel) {
        ChatFeature.install(on: model)
        ComposerFeature.install(on: model)
        ListsFeature.install(on: model)
        SettingsFeature.install(on: model)
        UnseenFeature.install(on: model)
    }
}
