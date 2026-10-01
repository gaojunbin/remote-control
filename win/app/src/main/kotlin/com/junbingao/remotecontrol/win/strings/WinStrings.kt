package com.junbingao.remotecontrol.win.strings

import com.junbingao.remotecontrol.core.state.InterfaceLanguage

/**
 * The Windows app's own words: the ones the web has no need for, because it is served by its
 * gateway and runs in a browser. They are the Mac's (`S.mac`), with Windows named where the Mac
 * names the Mac (`docs/DESIGN.md` § "The Windows app" → **Windows' own words**). There is no menu
 * bar, so the Mac's menu words have no place here; the notification area's two do. The Update
 * required words are the iPhone app's, in both languages, because the screen is the same screen
 * on every app.
 *
 * A feature's own Windows-only words go in a file of its own beside this one, built the same way:
 * one class, an English and a Chinese table made with its constructor, and an accessor on `S`.
 */
class WinStrings(
    /** Sign-in names the gateway: the one field the web's login page does not have, above the username. */
    val gateway: String,
    val gatewayPlaceholder: String,
    /** What an address the core refuses reads (`TransportError.invalidEndpoint`). */
    val gatewayInvalid: String,
    /** A46: the blocking screen of PROTOCOL 8.16. */
    val updateTitle: String,
    val updateBody: String,
    val updateThisApp: String,
    val updateGatewayNeeds: String,
    /** A Windows build is handed out from a web page, and the button says what it opens. */
    val updateOpenDownload: String,
    /** The notification area: the icon opens the window again and offers Quit. */
    val trayOpen: String,
    val trayQuit: String,
    /** Where the web names the browser, the Windows app names Windows. */
    val pushBlocked: String,
    /**
     * The Add device modal on Windows, beside the web's `pairing.scanBody`: there is no
     * pairing-link screen here, so the link opens elsewhere.
     */
    val pairingScanBody: String,
    /**
     * The Settings caption starts with the app's own version, because the Windows app is
     * installed apart from the gateway that serves the web.
     */
    val versions: (String, String, String) -> String,
) {
    companion object {
        val en = WinStrings(
            gateway = "Gateway",
            gatewayPlaceholder = "https://rc.example.com",
            gatewayInvalid = "Enter the full gateway address, for example https://rc.example.com.",
            updateTitle = "Update required",
            updateBody = "This gateway needs a newer version of the app.",
            updateThisApp = "This app",
            updateGatewayNeeds = "Gateway needs",
            updateOpenDownload = "Open the download page",
            trayOpen = "Open",
            trayQuit = "Quit",
            pushBlocked = "Blocked in Windows Settings.",
            pairingScanBody = "The host prints a QR code. Scan it with the phone app, or open its link in a browser.",
            versions = { app, gateway, protocol -> "Remote Control $app · Gateway $gateway · Protocol $protocol" },
        )

        val zhHans = WinStrings(
            gateway = "网关",
            gatewayPlaceholder = "https://rc.example.com",
            gatewayInvalid = "请输入完整的网关地址，例如 https://rc.example.com。",
            updateTitle = "需要更新",
            updateBody = "此网关需要更新版本的 App。",
            updateThisApp = "当前 App",
            updateGatewayNeeds = "网关要求",
            updateOpenDownload = "打开下载页面",
            trayOpen = "打开",
            trayQuit = "退出",
            pushBlocked = "已在 Windows 设置中屏蔽。",
            pairingScanBody = "主机会打印一个二维码。用手机 App 扫描它，或在浏览器中打开它的链接。",
            versions = { app, gateway, protocol -> "Remote Control $app · 网关 $gateway · 协议 $protocol" },
        )

        fun of(language: InterfaceLanguage): WinStrings = when (language) {
            InterfaceLanguage.en -> en
            InterfaceLanguage.zhHans -> zhHans
        }
    }
}
