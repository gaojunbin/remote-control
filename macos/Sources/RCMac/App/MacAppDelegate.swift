import AppKit

/// Closing the window leaves the app running and connected, as a chat app on
/// the Mac does, so notifications keep arriving; the Dock icon opens it again
/// and Quit ends it — and an ephemeral run takes what it wrote with it.
@MainActor
public final class MacAppDelegate: NSObject, NSApplicationDelegate {
    weak var model: MacAppModel?
    private var termination: DispatchSourceSignal?

    public override init() { super.init() }

    /// `kill` and `launchctl` stop an app with SIGTERM, which skips everything
    /// a Quit does; taken here, it is a Quit.
    public func applicationDidFinishLaunching(_ notification: Notification) {
        signal(SIGTERM, SIG_IGN)
        let source = DispatchSource.makeSignalSource(signal: SIGTERM, queue: .main)
        source.setEventHandler { MainActor.assumeIsolated { NSApp.terminate(nil) } }
        source.resume()
        termination = source
    }

    public func applicationShouldTerminateAfterLastWindowClosed(_ sender: NSApplication) -> Bool { false }

    public func applicationShouldHandleReopen(_ sender: NSApplication, hasVisibleWindows: Bool) -> Bool {
        if !hasVisibleWindows { model?.showWindow() }
        return true
    }

    public func applicationWillTerminate(_ notification: Notification) {
        model?.discardEphemeralState()
    }
}
