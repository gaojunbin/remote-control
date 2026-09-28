import RCCore
import SwiftUI

/// `/devices/:deviceId/terminal` (A38), `web/src/features/devices/TerminalPage.tsx`,
/// drawn over the whole window: the device's name as the title, a thin status
/// line under it, Close at the trailing edge, and the emulator taking the rest
/// (`docs/DESIGN.md` § "The terminal"). There is no key bar: the keyboard here
/// is real. Nothing that travels through the terminal is stored or logged.
public struct TerminalPage: View {
    let deviceId: String
    @Environment(MacAppModel.self) private var model

    public init(deviceId: String) { self.deviceId = deviceId }

    public var body: some View {
        TerminalPageBody(screen: TerminalScreen(deviceID: deviceId, model: model))
            .id(deviceId)
    }
}

/// The page around one `TerminalScreen`, which lives exactly as long as it.
private struct TerminalPageBody: View {
    @State private var screen: TerminalScreen
    @State private var typedStage = false
    @Environment(MacAppModel.self) private var model
    @Environment(\.layoutClass) private var layout
    @Environment(\.previewStage) private var stage

    init(screen: TerminalScreen) { _screen = State(initialValue: screen) }

    var body: some View {
        let device = model.device(screen.deviceID)
        let say = blocked(device)
        VStack(spacing: 0) {
            TerminalHead(title: device?.name ?? S.terminal.title, onBack: leave, onClose: {
                screen.close()
                leave()
            }) {
                // A device with no shell to give says so once, where the
                // shell would have been, rather than in the status line too.
                if say == nil {
                    TerminalStatusLine(status: screen.status, exitCode: screen.exitCode, reason: screen.reason,
                                       missedOutput: screen.missedOutput,
                                       onReconnect: { screen.connect() }, onRestart: { screen.restart() })
                        .padding(.top, 1)
                }
            }
            ZStack {
                TerminalEmulator(feed: screen.feed, onSize: { screen.resized($0) }, onInput: { screen.type($0) })
                    .padding(.top, layout.maxWidth480 ? Space.sp2 : Space.sp3)
                    .padding(.horizontal, layout.maxWidth480 ? Space.sp3 : Space.sp4)
                    .padding(.bottom, layout.maxWidth480 ? Space.sp3 : Space.sp4)
                if let say { TerminalBlocked(text: say) }
            }
            .frame(maxWidth: .infinity, maxHeight: .infinity)
            .background(Palette.surfaceSunken)
        }
        .background(Palette.canvas)
        .onAppear { screen.show() }
        .onDisappear { screen.close() }
        .onChange(of: screen.available && screen.socketOpen) { _, up in
            if up { screen.connect() }
        }
        .onChange(of: screen.status) { _, status in typeStagedLine(once: status) }
    }

    /// A render types one line into the shell once it is connected: a command,
    /// or the `exit` that ends it.
    private func typeStagedLine(once status: TerminalScreen.Status) {
        let lines = ["terminal.ls": "ls\r", "terminal.exit": "exit\r"]
        guard status == .connected, !typedStage, let line = lines[stage ?? ""] else { return }
        typedStage = true
        Task {
            try? await Task.sleep(for: .milliseconds(300))
            screen.type(Data(line.utf8))
        }
    }

    /// Rule 20 for a link somebody kept, and for a device that goes offline
    /// while the page is open: what the row would have said instead of opening.
    /// Nothing is said before the device list has arrived, and nothing once a
    /// shell has run here — a loss then is the status line's to tell.
    private func blocked(_ device: Device?) -> String? {
        guard !screen.started, model.connection.hasSnapshot || device != nil else { return nil }
        guard let device else { return S.terminal.gone }
        if !device.online { return S.devices.deviceOffline }
        return device.offersTerminal ? nil : S.devices.noTerminal
    }

    private func leave() { model.router.go(.devices) }
}

/// `.terminal-blocked`: a device that cannot give a shell says so where the
/// shell would have been.
private struct TerminalBlocked: View {
    let text: String

    var body: some View {
        Text(text)
            .css(FontSize.fs14)
            .foregroundStyle(Palette.inkSecondary)
            .multilineTextAlignment(.center)
            .padding(Space.sp4)
            .frame(maxWidth: .infinity, maxHeight: .infinity)
            .background(Palette.surfaceSunken)
    }
}
