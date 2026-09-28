import Foundation
import RCCore

/// The settings feature's launch hook: the notifier that posts this Mac's own
/// notifications, the accounts list the Users screen keeps between visits, and
/// the terminals a sign-out ends — one set per model, found again with
/// `state(of:)`.
@MainActor
public enum SettingsFeature {
    static func install(on model: MacAppModel) {
        _ = state(of: model)
    }

    /// The feature's state for this model, made and wired the first time it is
    /// asked for (which `install` does when the model is built).
    static func state(of model: MacAppModel) -> SettingsFeatureState {
        let key = ObjectIdentifier(model)
        // A model that has gone can leave its identifier to a new one.
        if let known = states[key], known.model === model { return known }
        let state = SettingsFeatureState(model: model, platform: NotificationPlatforms.make())
        states[key] = state
        wire(state, to: model)
        return state
    }

    private static var states: [ObjectIdentifier: SettingsFeatureState] = [:]

    private static func wire(_ state: SettingsFeatureState, to model: MacAppModel) {
        model.onSessionTransition { [weak state] previous, current in
            state?.notifier.sessionChanged(from: previous, to: current)
        }
        model.connection.addFrameHandler("settings.notifications") { [weak state] frame in
            state?.notifier.receive(frame)
        }
        model.onSignOut { [weak state] in await state?.signOut() }
        Task { await state.notifier.refresh() }
    }
}

/// What the settings feature keeps for the life of one model.
@MainActor
final class SettingsFeatureState {
    private(set) weak var model: MacAppModel?
    let notifier: TurnNotifier
    let users = UsersModel()
    /// The terminal pages on screen, whose shells a sign-out ends while the
    /// connection still names the account.
    private var terminals: [ObjectIdentifier: TerminalScreen] = [:]

    init(model: MacAppModel, platform: any NotificationPlatform) {
        self.model = model
        notifier = TurnNotifier(model: model, platform: platform)
    }

    func track(_ terminal: TerminalScreen) { terminals[ObjectIdentifier(terminal)] = terminal }

    func forget(_ terminal: TerminalScreen) { terminals.removeValue(forKey: ObjectIdentifier(terminal)) }

    /// `web/src/stores/signOut.ts`: nothing of the account stays behind.
    func signOut() async {
        for terminal in terminals.values { await terminal.end() }
        terminals.removeAll()
        notifier.signedOut()
        users.reset()
    }
}
