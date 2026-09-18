import SwiftUI
import RCCore
#if os(iOS)
import LocalAuthentication
#endif

/// A shell on one machine (amendment A38, `docs/DESIGN.md` § "The terminal").
///
/// Full screen, the machine's name as the title, **Close** at the trailing
/// edge, a thin status line under it and the emulator taking everything else.
/// The protocol lives in `TerminalSession` and the rendering in `TerminalHost`;
/// what is here is the screen between them — when to open, what the line says,
/// and the key bar a phone needs.
struct TerminalScreen: View {
    let deviceID: String

    @Environment(AppModel.self) private var model
    @Environment(\.dismiss) private var dismiss
    @State private var session: TerminalSession?
    @State private var feed = TerminalFeed()
    @State private var latch = ControlLatch()
    /// Whether the App Lock has been answered. Nothing is opened, and no
    /// emulator is built, until it has (`docs/DESIGN.md` § "The terminal" →
    /// **Safety**).
    @State private var isUnlocked = false
    @State private var hasOpened = false

    private var device: Device? { model.connection.device(deviceID) }
    private var isLocked: Bool { model.settings.appLockEnabled && !isUnlocked }

    var body: some View {
        VStack(spacing: 0) {
            statusLine
            if isLocked {
                Color.clear
            } else {
                TerminalHost(feed: feed,
                             fontSize: model.settings.terminalFontSize,
                             onSize: laidOut(at:),
                             onInput: typed(_:),
                             onFontSize: { model.settings.terminalFontSize = $0 })
                    .background(Theme.surface)
            }
        }
        .pageBackground()
        .navigationTitle(device?.name ?? "")
        .inlineNavigationTitle()
        .hideTabBar()
        .toolbar {
            ToolbarItem(placement: .trailingBar) {
                Button("Close") { leave() }
                    .accessibilityIdentifier("terminal.close")
            }
        }
        .safeAreaInset(edge: .bottom, spacing: 0) {
            if !isLocked {
                TerminalKeyBar(isControlArmed: latch.isArmed, onKey: press(_:))
            }
        }
        // No identifier on the screen itself: SwiftUI hands one on a container
        // down to every element inside it, which would rename the status line,
        // the emulator and every key on the bar.
        .task { await unlock() }
        .onDisappear { close() }
        // The socket, not the app: backgrounding sends nothing, and it is the
        // loss of the link that makes the screen attach again when it returns.
        .onChange(of: model.connection.phase) { _, phase in
            session?.link(isUp: phase == .connected)
        }
    }

    // MARK: - The status line

    /// `docs/DESIGN.md`: Connecting, Connected, or Disconnected with a
    /// Reconnect; the exit code with a New shell; and a machine that has gone
    /// says so in place of any of them.
    private var statusLine: some View {
        HStack(spacing: Theme.Space.small) {
            Text(line)
                .font(Theme.Text.caption)
                .foregroundStyle(Theme.inkSecondary)
                .accessibilityIdentifier("terminal.status")
            if session?.missedOutput == true {
                Text("Some output was lost.")
                    .font(Theme.Text.caption)
                    .foregroundStyle(Theme.attention)
                    .accessibilityIdentifier("terminal.gap")
            }
            Spacer(minLength: 0)
            action
        }
        .padding(.horizontal, Theme.Space.page)
        .padding(.vertical, Theme.Space.tight)
        .frame(maxWidth: .infinity)
        .barBackground()
    }

    private var line: String {
        if device == nil { return L10n.string("That device is no longer on this gateway.") }
        if device?.online == false { return L10n.string("This device is offline.") }
        guard let session else { return L10n.string("Connecting") }
        return TerminalStatusText.line(for: session.status)
    }

    @ViewBuilder
    private var action: some View {
        if let status = session?.status, device?.online != false {
            if TerminalStatusText.offersReconnect(status) {
                Button("Reconnect") { Task { await session?.attach() } }
                    .buttonStyle(ChipButtonStyle())
                    .accessibilityIdentifier("terminal.reconnect")
            } else if TerminalStatusText.offersNewShell(status) {
                Button("New shell") { start() }
                    .buttonStyle(ChipButtonStyle())
                    .accessibilityIdentifier("terminal.newShell")
            }
        }
    }

    // MARK: - Opening and leaving

    /// The App Lock first, then the session. Opening a terminal is the most
    /// powerful thing this app can do to a machine, so an enabled lock is asked
    /// again here even inside an unlocked app; a refusal goes back.
    private func unlock() async {
        guard model.settings.appLockEnabled else {
            isUnlocked = true
            begin()
            return
        }
        #if os(iOS)
        let context = LAContext()
        let granted = (try? await context.evaluatePolicy(
            .deviceOwnerAuthentication,
            localizedReason: L10n.string("Open a terminal on this device"))) ?? false
        guard granted else {
            dismiss()
            return
        }
        #endif
        isUnlocked = true
        begin()
    }

    private func begin() {
        guard session == nil, let channel = model.connection.channel else { return }
        let session = TerminalSession(deviceID: deviceID, channel: channel)
        session.onOutput = { [feed] bytes in feed.write(bytes) }
        self.session = session
        model.connection.addFrameHandler("terminal") { [weak session] frame in session?.receive(frame) }
        // The emulator may already have been laid out while the lock was up.
        if let size = feed.size { laidOut(at: size) }
    }

    /// The emulator has a size. The first one opens the shell; every one after
    /// it follows the view (`terminal.resize`).
    private func laidOut(at size: TerminalSize) {
        guard let session else { return }
        guard hasOpened else {
            hasOpened = true
            Task { await session.open(cols: size.cols, rows: size.rows) }
            return
        }
        session.resize(cols: size.cols, rows: size.rows)
    }

    /// Ask for another shell after one exited, at whatever size the emulator is.
    private func start() {
        guard let session, let size = feed.size else { return }
        Task { await session.open(cols: size.cols, rows: size.rows) }
    }

    private func leave() {
        close()
        dismiss()
    }

    private func close() {
        model.connection.removeFrameHandler("terminal")
        session?.close()
    }

    // MARK: - Typing

    /// Bytes the emulator produced from a keystroke, through the sticky Ctrl.
    private func typed(_ bytes: Data) {
        session?.type(latch.apply([UInt8](bytes)))
    }

    private func press(_ key: TerminalKey) {
        switch key {
        case .control:
            latch.toggle()
        case .paste:
            latch.disarm()
            if let bytes = TerminalPasteboard.bytes() { session?.type(bytes) }
        default:
            guard let bytes = key.bytes else { return }
            session?.type(latch.apply(bytes))
        }
    }
}
