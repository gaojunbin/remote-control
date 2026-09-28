import RCCore
import RCMac
import SwiftUI

/// One picture the renderer takes: a place in the app, the size of the
/// window, a stage the views can read to show a state that takes a click, and
/// what to do before the picture is taken.
///
/// A feature's scenarios go in its own file beside this one (`ChatScenarios`,
/// `ComposerScenarios`, `ListsScenarios`, `SettingsScenarios`), and
/// `PreviewScenarios.all` joins them.
struct PreviewScenario: Sendable {
    /// Who the window is signed in as.
    enum Account: Sendable {
        /// Signed in, on whichever gateway the command line named.
        case signedIn
        /// Nobody yet: the sign-in form, on the command line's gateway (the
        /// offline demo's account form under `--demo`).
        case signedOut
        /// The offline demo, with a minimum above this build (A45).
        case updateRequired
    }

    let name: String
    let route: Route
    /// The window's size in points; nil is the command line's (1280 × 860).
    let width: CGFloat?
    let height: CGFloat?
    /// Put in the environment as `\.previewStage`.
    let stage: String?
    let account: Account
    let language: InterfaceLanguage?
    /// How long the window is left to draw before the picture is taken.
    let settle: Duration
    /// Run once the account is reached, before the window exists: what the
    /// first frame must already read, such as a remembered gateway.
    let setup: @MainActor @Sendable (PreviewContext) async -> Void
    /// Run after the window shows the route, before it settles.
    let prepare: @MainActor @Sendable (PreviewContext) async -> Void
    /// A view drawn in place of the route, for a scenario about one view. It
    /// reads what `setup` opened through the context — a conversation, say.
    let content: (@MainActor @Sendable (PreviewContext) -> AnyView)?

    init(name: String, route: Route = .landing, width: CGFloat? = nil, height: CGFloat? = nil,
         stage: String? = nil, account: Account = .signedIn, language: InterfaceLanguage? = nil,
         settle: Duration = .milliseconds(900),
         setup: @escaping @MainActor @Sendable (PreviewContext) async -> Void = { _ in },
         prepare: @escaping @MainActor @Sendable (PreviewContext) async -> Void = { _ in },
         content: (@MainActor @Sendable (PreviewContext) -> AnyView)? = nil) {
        self.name = name
        self.route = route
        self.width = width
        self.height = height
        self.stage = stage
        self.account = account
        self.language = language
        self.settle = settle
        self.setup = setup
        self.prepare = prepare
        self.content = content
    }
}

/// What a scenario's preparation can reach: the model the window draws, and
/// a way to wait for the gateway.
@MainActor
struct PreviewContext {
    let model: MacAppModel
    /// The gateway the command line named, or nil under `--demo`.
    let gateway: URL?
    /// What `setup` opened for `content` to draw. One per render.
    let opened: PreviewOpened

    /// The conversation `openChat` opened, if it has.
    var chat: ChatStore? { opened.chat }

    /// Open one session's conversation, for a scenario that draws a piece of
    /// it alone — the composer, a block — while the page that normally opens
    /// it belongs to another feature. Call it from `setup`; `content` reads
    /// it back as `chat`. Nil when the gateway has no such session.
    @discardableResult
    func openChat(deviceId: String, sessionId: String) async -> ChatStore? {
        guard let session = model.connection.sessions.first(where: {
            $0.deviceID == deviceId && $0.sessionID == sessionId
        }), let channel = model.connection.channel else { return nil }
        let store = ChatStore(session: session, channel: channel)
        store.agent = model.agent(for: session)
        store.detailSource = { [settings = model.settings] in settings.timelineDetail }
        model.connection.addFrameHandler("preview-chat") { [weak store] frame in store?.receive(frame) }
        await store.open()
        opened.chat = store
        return store
    }

    /// Wait until `condition` holds, for at most `timeout`.
    @discardableResult
    func wait(timeout: Duration = .seconds(5), until condition: @MainActor () -> Bool) async -> Bool {
        let deadline = ContinuousClock.now + timeout
        while !condition() {
            guard ContinuousClock.now < deadline else { return false }
            try? await Task.sleep(for: .milliseconds(50))
        }
        return true
    }
}

/// What a render's `setup` opened, held for the length of the render.
@MainActor
final class PreviewOpened {
    var chat: ChatStore?

    func close(on connection: ConnectionStore) async {
        guard let chat else { return }
        connection.removeFrameHandler("preview-chat")
        await chat.close()
        self.chat = nil
    }
}

enum PreviewScenarios {
    /// Every scenario, in the order `--all` renders them.
    static var all: [PreviewScenario] {
        FoundationScenarios.all + ChatScenarios.all + ComposerScenarios.all
            + ListsScenarios.all + SettingsScenarios.all
    }
}
