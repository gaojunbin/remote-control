import Foundation
import Observation
import RCCore

/// This Mac's notifications (`docs/DESIGN.md` § "The Mac app" → **Notify me
/// posts from the app**): the Notify me switch's two halves — the setting,
/// which is this Mac's and not the account's (A41 keeps
/// `notificationsEnabled` on the device), and the system's permission — and
/// the posting itself, at the moments the gateway pushes for.
@MainActor
@Observable
final class TurnNotifier {
    private(set) var permission: NotificationPermission = .notDetermined
    /// True while the system's question is on screen; the switch waits for it.
    private(set) var isAsking = false

    @ObservationIgnored let platform: any NotificationPlatform
    @ObservationIgnored private weak var model: MacAppModel?
    /// A click that came before its conversation could be opened.
    @ObservationIgnored private var pending: NoticeTarget?

    init(model: MacAppModel, platform: any NotificationPlatform) {
        self.model = model
        self.platform = platform
        platform.onOpen = { [weak self] target in self?.open(target) }
    }

    /// What the switch shows: on only while the setting is on and the system
    /// lets the app post.
    var isOn: Bool { (model?.settings.notificationsEnabled ?? false) && permission == .authorized }

    /// Ask the system again: the person may have changed it in System Settings
    /// while the app was behind another window.
    func refresh() async {
        permission = await platform.permission()
    }

    /// The switch. Turning it on is the one thing in the app that asks the
    /// system for permission, and only while it has never been asked.
    func turn(on: Bool) async {
        guard let settings = model?.settings else { return }
        guard on else {
            settings.notificationsEnabled = false
            return
        }
        await refresh()
        if permission == .notDetermined {
            isAsking = true
            permission = await platform.requestPermission()
            isAsking = false
        }
        settings.notificationsEnabled = permission == .authorized
    }

    // MARK: - Posting

    /// A session the app already knew arrived in a newer version.
    func sessionChanged(from previous: Session, to current: Session) {
        for kind in TurnNoticeRule.kinds(previous: previous, current: current) {
            announce(kind, about: current)
        }
    }

    /// A frame on the app socket: a `resume` event that ran or gave up.
    func receive(_ frame: AppFrame) {
        guard case .sessionEvent(let sessionID, let deviceID, let event) = frame,
              let resume = event.resume, let kind = TurnNoticeRule.kind(resume: resume.status),
              let session = model?.connection.sessions.first(where: {
                  $0.sessionID == sessionID && (deviceID == nil || $0.deviceID == deviceID)
              }) else { return }
        announce(kind, about: session)
    }

    private func announce(_ kind: PushKind, about session: Session) {
        guard let model else { return }
        let target = NoticeTarget(deviceID: session.deviceID, sessionID: session.sessionID)
        guard TurnNoticeRule.posts(target, enabled: model.settings.notificationsEnabled, permission: permission,
                                   route: model.router.route, windowActive: model.isWindowActive) else { return }
        // `device_name` falls back to the id, as the gateway's own lookup does.
        let name = model.device(session.deviceID)?.name ?? session.deviceID
        platform.post(TurnNotice(kind: kind, target: target, deviceName: name))
    }

    // MARK: - Opening

    /// A click: the window comes forward and the conversation opens, as a
    /// followed link opens it on the web.
    func open(_ target: NoticeTarget) {
        model?.showWindow()
        pending = target
        openPending()
    }

    /// Opens the waiting click the moment an account, its list and the landing
    /// rule allow it; until then it waits for one of the three to change.
    private func openPending() {
        guard let model, let target = pending else { return }
        let ready = withObservationTracking {
            TurnNoticeRule.opensNow(signedIn: model.isSignedIn, hasSnapshot: model.connection.hasSnapshot,
                                    route: model.router.route)
        } onChange: { [weak self] in
            Task { @MainActor in self?.openPending() }
        }
        guard ready else { return }
        pending = nil
        model.router.go(.chat(deviceId: target.deviceID, sessionId: target.sessionID))
    }

    /// Sign-out: what was posted names the account's machines and sessions.
    func signedOut() {
        pending = nil
        platform.removeDelivered()
    }
}
