import Foundation
import RCCore

/// The Mac's own words: the ones the web has no need for, because it is served
/// by its gateway, runs in a browser and has no menu bar. `docs/DESIGN.md` §
/// "The Mac app" decides each of them. The Update required words are the
/// iPhone app's (`ios/App/Localizable.xcstrings`), in both languages, because
/// the screen is the same screen on both apps.
///
/// A feature's own Mac-only words go in a file of its own beside this one,
/// built the same way: one struct, an English and a Chinese table made with its
/// memberwise initialiser, and an accessor on `S`.
public struct MacStrings: Sendable {
    /// Sign-in names the gateway: the one field the web's login page does not
    /// have, above the username.
    public let gateway: String
    public let gatewayPlaceholder: String
    /// What an address RCCore refuses reads (`TransportError.invalidEndpoint`).
    public let gatewayInvalid: String

    /// A45: the blocking screen of PROTOCOL 8.16.
    public let updateTitle: String
    public let updateBody: String
    public let updateThisApp: String
    public let updateGatewayNeeds: String
    public let updateOpenTestFlight: String
    public let updateOpenAppStore: String
    /// A link to anywhere but TestFlight or the App Store: a Mac app can be
    /// handed out from a web page, and the button says what it opens.
    public let updateOpenDownload: String

    /// The menu bar: keyboard shortcuts to places the web already has, and
    /// browser-like history.
    public let menuGo: String
    public let menuBack: String
    public let menuForward: String
    public let menuSettings: String

    /// Where the web names the browser, the Mac names the Mac.
    public let pushBlocked: String
    /// The Add device modal on the Mac, beside the web's `pairing.scanBody`:
    /// there is no pairing-link screen here, so the link opens elsewhere.
    public let pairingScanBody: String
    /// The Settings caption starts with the app's own version, because the Mac
    /// app is installed apart from the gateway that serves the web.
    public let versions: @Sendable (String, String, String) -> String
}

extension MacStrings {
    static let en = MacStrings(
        gateway: "Gateway",
        gatewayPlaceholder: "https://rc.example.com",
        gatewayInvalid: "Enter the full gateway address, for example https://rc.example.com.",
        updateTitle: "Update required",
        updateBody: "This gateway needs a newer version of the app.",
        updateThisApp: "This app",
        updateGatewayNeeds: "Gateway needs",
        updateOpenTestFlight: "Open TestFlight",
        updateOpenAppStore: "Open the App Store",
        updateOpenDownload: "Open the download page",
        menuGo: "Go",
        menuBack: "Back",
        menuForward: "Forward",
        menuSettings: "Settings…",
        pushBlocked: "Blocked in System Settings.",
        pairingScanBody: "The host prints a QR code. Scan it with the phone app, or open its link in a browser.",
        versions: { app, gateway, `protocol` in "Remote Control \(app) · Gateway \(gateway) · Protocol \(`protocol`)" }
    )

    static let zhHans = MacStrings(
        gateway: "网关",
        gatewayPlaceholder: "https://rc.example.com",
        gatewayInvalid: "请输入完整的网关地址，例如 https://rc.example.com。",
        updateTitle: "需要更新",
        updateBody: "此网关需要更新版本的 App。",
        updateThisApp: "当前 App",
        updateGatewayNeeds: "网关要求",
        updateOpenTestFlight: "打开 TestFlight",
        updateOpenAppStore: "打开 App Store",
        updateOpenDownload: "打开下载页面",
        menuGo: "前往",
        menuBack: "返回",
        menuForward: "前进",
        menuSettings: "设置…",
        pushBlocked: "已在系统设置中屏蔽。",
        pairingScanBody: "主机会打印一个二维码。用手机 App 扫描它，或在浏览器中打开它的链接。",
        versions: { app, gateway, `protocol` in "Remote Control \(app) · 网关 \(gateway) · 协议 \(`protocol`)" }
    )

    public static func of(_ language: InterfaceLanguage) -> MacStrings {
        switch language {
        case .en: .en
        case .zhHans: .zhHans
        }
    }
}

extension S {
    /// The Mac's own words, read like every other group.
    public static var mac: MacStrings { .of(InterfaceLanguageSource.shared.current) }
}
