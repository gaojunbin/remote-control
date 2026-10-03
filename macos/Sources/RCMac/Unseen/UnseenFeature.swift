import Foundation
import RCCore

/// Amendment A47's launch hook: the conversation open in the window that has
/// focus is reported seen whenever it carries a red dot, and the Dock icon's
/// badge follows the number of dots — for the life of the model, one set per
/// model, found again with `state(of:)`.
@MainActor
public enum UnseenFeature {
    static func install(on model: MacAppModel) {
        _ = state(of: model)
    }

    /// The feature's state for this model, made and started the first time it
    /// is asked for (which `install` does when the model is built).
    static func state(of model: MacAppModel) -> UnseenFeatureState {
        let key = ObjectIdentifier(model)
        // A model that has gone can leave its identifier to a new one.
        if let known = states[key], known.model === model { return known }
        let state = UnseenFeatureState(model: model, dock: DockBadges.make())
        states[key] = state
        return state
    }

    private static var states: [ObjectIdentifier: UnseenFeatureState] = [:]
}

/// What the feature keeps for the life of one model.
@MainActor
final class UnseenFeatureState {
    private(set) weak var model: MacAppModel?
    let dock: any DockBadgePlatform
    let seen: SeenReporter
    let badge: DockBadgeKeeper

    init(model: MacAppModel, dock: any DockBadgePlatform) {
        self.model = model
        self.dock = dock
        seen = SeenReporter(connection: model.connection) { [weak model] in model?.conversationInFront }
        // The dots are the signed-in account's, so nobody signed in has none.
        badge = DockBadgeKeeper(platform: dock) { [weak model] in
            guard let model, model.isSignedIn else { return 0 }
            return UnseenMark.count(in: model.connection.sessions, excluding: model.conversationInFront)
        }
        seen.start()
        badge.start()
    }
}

extension MacAppModel {
    /// The conversation the person has in front of them: the one the route
    /// shows, while the window it is in has focus.
    var conversationInFront: String? {
        guard isWindowActive, case .chat(let deviceId, let sessionId) = router.route else { return nil }
        return "\(deviceId)/\(sessionId)"
    }

    /// Whether a row draws the session's red dot: it carries the mark and is
    /// not the conversation in front of the person, which is being looked at
    /// while the `session.seen` that clears the mark is on its way — the web's
    /// `useFront` rule, so the dot never flickers on the row that is open.
    func showsUnseenDot(_ session: Session) -> Bool {
        session.unseen && session.id != conversationInFront
    }
}
