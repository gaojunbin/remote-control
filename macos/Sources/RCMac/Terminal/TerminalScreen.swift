import Foundation
import Observation
import RCCore

/// `useTerminal.ts` — A38 §7.3: one terminal on one device, from this app
/// connection. RCCore's `TerminalSession` is the whole protocol side — open,
/// attach, input, resize, close, `seq` gaps and repeats — and this is the web
/// page's own rules on top of it:
///
/// - the shell is started once the emulator has measured itself, and only
///   while the device is online, offers one, and the socket is open;
/// - after a lost socket, or the device coming back, the terminal is taken
///   over again, and an attach's scrollback is drawn on a reset emulator:
///   it is the screen as it was left, and appending it would print everything
///   the person already read a second time;
/// - an answer that comes back after the page was left is closed rather than
///   left running for ten minutes with nobody attached;
/// - leaving the page ends the shell.
///
/// What a shell writes is bytes, decoded once, by the emulator. Nothing here
/// logs, stores or inspects them.
@MainActor
@Observable
final class TerminalScreen {
    /// The status line's word (`TerminalStatus` in `useTerminal.ts`).
    enum Status: Equatable {
        case connecting
        case connected
        case disconnected
        case exited
    }

    let deviceID: String
    let feed = TerminalFeed()
    /// True once a shell has run here, which is what makes a loss a loss.
    private(set) var started = false

    @ObservationIgnored private weak var model: MacAppModel?
    private let failures: TerminalFailures
    private let session: TerminalSession
    private let frameToken = "terminal.\(UUID().uuidString)"
    @ObservationIgnored private var size: TerminalSize?
    @ObservationIgnored private var isShown = false
    @ObservationIgnored private var attempting = false
    @ObservationIgnored private var again = false
    @ObservationIgnored private var resetBeforeScrollback = false

    init(deviceID: String, model: MacAppModel) {
        let failures = TerminalFailures()
        self.deviceID = deviceID
        self.model = model
        self.failures = failures
        session = TerminalSession(deviceID: deviceID, channel: TerminalRequests(
            socket: { [weak model] in model?.connection.channel }, failures: failures))
    }

    // MARK: - What the page reads

    /// Rule 20: a device that is offline or offers no terminal is never asked for one.
    var available: Bool {
        guard let device = model?.device(deviceID) else { return false }
        return device.online && device.offersTerminal
    }

    var socketOpen: Bool { model?.connection.phase.isOpen ?? false }

    var status: Status {
        if case .exited = session.status { return .exited }
        guard available, socketOpen else { return started ? .disconnected : .connecting }
        switch session.status {
        case .connected: return .connected
        case .disconnected, .failed: return .disconnected
        case .connecting, .exited: return .connecting
        }
    }

    /// The shell's exit code, or nil when the device never said (§7.3).
    var exitCode: Int? {
        if case .exited(let code) = session.status { return code }
        return nil
    }

    /// Why the last attempt failed, in the reader's language, read now.
    var reason: String? {
        guard available, socketOpen, case .failed = session.status else { return nil }
        return failures.latest.map { ErrorText.text($0) } ?? S.errors.generic
    }

    /// §7.3: a `seq` that skipped, so bytes were dropped rather than delayed.
    var missedOutput: Bool { session.missedOutput }

    // MARK: - The page's life

    /// The page is on screen: frames for this terminal reach the session, a
    /// sign-out can end it, and the shell starts if the emulator has already
    /// measured itself — AppKit lays it out before the page appears.
    func show() {
        guard !isShown, let model else { return }
        isShown = true
        session.onOutput = { [weak self] bytes in self?.output(bytes) }
        model.connection.addFrameHandler(frameToken) { [weak self] frame in self?.session.receive(frame) }
        SettingsFeature.state(of: model).track(self)
        connect()
    }

    /// Leaving the page — Close, the back arrow, any other route, a sign-out —
    /// ends the shell.
    func close() {
        guard isShown else { return }
        isShown = false
        session.close()
        model?.connection.removeFrameHandler(frameToken)
        if let model { SettingsFeature.state(of: model).forget(self) }
    }

    /// A sign-out ends the shell while the connection still names the account:
    /// the page's own close goes out by itself and would lose the race with the
    /// socket closing, so this one is waited for. The device takes a second
    /// close of the same terminal as the first.
    func end() async {
        guard isShown else { return }
        let terminalID = session.terminalID
        close()
        guard let terminalID, let channel = model?.connection.channel else { return }
        _ = try? await channel.request(.terminalClose(deviceID: deviceID, terminalID: terminalID))
    }

    /// The emulator measured itself: the first size starts the shell, and every
    /// later one is sent once the dragging stops.
    func resized(_ next: TerminalSize) {
        let first = size == nil
        size = next
        session.resize(cols: next.cols, rows: next.rows)
        if first { connect() }
    }

    func type(_ bytes: Data) { session.type(bytes) }

    // MARK: - Connecting

    /// Open a shell, or take back the one this page had: on the first size,
    /// when the socket or the device comes back, and on Reconnect.
    func connect() { attempt(afterExit: false) }

    /// New shell: the ended one is behind the page, and another is asked for.
    func restart() {
        started = false
        attempt(afterExit: true)
    }

    private func attempt(afterExit: Bool) {
        guard isShown, let size, available, socketOpen else { return }
        if case .exited = session.status, !afterExit { return }
        guard !attempting else {
            again = true
            return
        }
        attempting = true
        Task {
            await run(size)
            attempting = false
            if again {
                again = false
                connect()
            }
        }
    }

    private func run(_ size: TerminalSize) async {
        if session.terminalID != nil {
            resetBeforeScrollback = true
            await session.attach()
            // An empty scrollback feeds nothing, and the screen is reset all the same.
            if resetBeforeScrollback, session.status == .connected { feed.reset() }
            resetBeforeScrollback = false
        } else {
            await session.open(cols: size.cols, rows: size.rows)
            if session.terminalID != nil { started = true }
        }
        if !isShown { session.close() }
    }

    private func output(_ bytes: Data) {
        if resetBeforeScrollback {
            resetBeforeScrollback = false
            feed.reset()
        }
        feed.write(bytes)
    }
}
