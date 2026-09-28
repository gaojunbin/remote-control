import AppKit
import Foundation
import RCMac

// The renderer every agent verifies with: the real RootView on a real
// MacAppModel, drawn in an offscreen window and captured as the window server
// would composite it. It never shows a window, a Dock icon or a menu bar.
TextRendering.matchWeb()
// Scroll bars are overlays in every render, whatever this Mac's setting or its
// mouse: `-AppleShowScrollBars Always` on the command line renders the other case.
var arguments = UserDefaults.standard.volatileDomain(forName: UserDefaults.argumentDomain)
if arguments["AppleShowScrollBars"] == nil { arguments["AppleShowScrollBars"] = "WhenScrolling" }
UserDefaults.standard.setVolatileDomain(arguments, forName: UserDefaults.argumentDomain)
let app = NSApplication.shared
app.setActivationPolicy(.prohibited)

Task { @MainActor in
    exit(await PreviewCommand.run(CommandLine.arguments))
}
app.run()
