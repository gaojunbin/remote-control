import RCCore
import SwiftUI

/// What `web/src/App.tsx` renders: Update required above everything (A45),
/// the canvas while the app decides, the login page whenever nobody is signed
/// in, and otherwise the route — inside the topbar layout, or over the whole
/// window for the conversation and the terminal. The overlay layer covers all
/// of it, and the web's breakpoints are read from the window's width here, once.
public struct RootView: View {
    @Environment(MacAppModel.self) private var model
    @State private var chrome = WindowChrome()
    private let replacement: AnyView?

    public init() { replacement = nil }

    /// One view drawn where a screen goes, with everything the root gives a
    /// screen — the breakpoints, the overlay layer, the base type and ink. The
    /// renderer uses it for a scenario about a single view.
    public init<Content: View>(showing content: Content) { replacement = AnyView(content) }

    public var body: some View {
        GeometryReader { proxy in
            screen
                .frame(width: proxy.size.width, height: proxy.size.height)
                .overlayHost()
                .environment(\.layoutClass, LayoutClass(width: proxy.size.width, height: proxy.size.height))
        }
        .webBase()
        .ignoresSafeArea()
        .environment(\.trafficLightInset, chrome.inset)
        .environment(\.locale, model.settings.language.locale)
        .preferredColorScheme(.light)
        .onPreferenceChange(WindowStripHeightKey.self) { height in
            chrome.stripHeight = height ?? LayoutSize.headerH
        }
        .background { WindowAccessor { window in chrome.attach(window, model: model) } }
    }

    @ViewBuilder private var screen: some View {
        if let replacement {
            replacement
        } else if let requirement = model.connection.updateRequired {
            UpdateRequiredPage(requirement: requirement)
        } else if model.isResuming {
            BootView()
        } else if !model.isSignedIn {
            // Signed out, every path is the login page. Drawing the requested
            // page first and moving from it would run its first requests on a
            // connection that can only refuse them.
            LoginPage()
        } else {
            RoutedScreen(route: model.router.route)
        }
    }
}

/// One route's screen. The topbar routes share one layout, so moving between
/// them keeps the topbar where it is, as the web's nested routes do.
private struct RoutedScreen: View {
    let route: Route

    var body: some View {
        if route.isInLayout {
            AppLayout { LayoutPage(route: route) }
        } else {
            switch route {
            case .chat(let deviceId, let sessionId):
                ChatPage(deviceId: deviceId, sessionId: sessionId)
            case .terminal(let deviceId):
                TerminalPage(deviceId: deviceId)
            default:
                Landing()
            }
        }
    }
}

/// The page a topbar route draws under the topbar.
private struct LayoutPage: View {
    let route: Route

    var body: some View {
        switch route {
        case .devices: DevicesPage()
        case .device(let id): DevicePage(deviceId: id)
        case .sessions: SessionsPage()
        case .settings: SettingsPage()
        case .users: UsersPage()
        default: EmptyView()
        }
    }
}
