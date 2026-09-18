import Foundation
import Observation

/// One shell on one machine, for as long as a screen is looking at it
/// (amendment A38, `docs/DESIGN.md` § "The terminal").
///
/// Everything the wire needs is here and nothing the emulator needs is: the
/// bytes go out through `onOutput`, so this object can be driven and checked
/// without SwiftTerm, and the screen around it holds no protocol knowledge.
///
/// Two invariants are worth naming. Input is sent by one task in the order it
/// was typed — two concurrent requests would reorder a person's keystrokes —
/// and nothing here ever logs, prints or stores what travels either way.
@MainActor
@Observable
public final class TerminalSession {
    /// What the status line under the title says.
    public enum Status: Equatable, Sendable {
        case connecting
        case connected
        /// The socket is gone; the device keeps the shell for ten minutes.
        case disconnected
        case exited(code: Int?)
        case failed(String)
    }

    public private(set) var status: Status = .connecting
    /// The id every later request names, and what an `attach` after a lost
    /// socket asks for. Nil before the first open and after the shell ended.
    public private(set) var terminalID: String?
    /// Amendment A38: `seq` rises by one per frame, so a jump is output this
    /// app never received. It is said in the status line rather than guessed at.
    public private(set) var missedOutput = false
    /// The bytes the shell produced, already decoded. Set by the screen.
    @ObservationIgnored public var onOutput: ((Data) -> Void)?

    @ObservationIgnored public let deviceID: String
    @ObservationIgnored private let channel: any GatewayChannel
    @ObservationIgnored private var lastSeq = 0
    /// True between an `attach` and the first frame that follows it: `seq`
    /// carries on from where the other connection left it, which this app
    /// never saw, so the first number after an attach is not a gap.
    @ObservationIgnored private var acceptsAnySeq = false
    @ObservationIgnored private var pendingInput = Data()
    @ObservationIgnored private var sender: Task<Void, Never>?
    @ObservationIgnored private var resizer: Task<Void, Never>?
    @ObservationIgnored private var size: TerminalSize?
    @ObservationIgnored private var sentSize: TerminalSize?
    /// How long a run of size changes is allowed to settle before one
    /// `terminal.resize` is sent. A rotation is many layout passes.
    @ObservationIgnored private let resizeDelay: Duration

    public init(deviceID: String, channel: any GatewayChannel,
                resizeDelay: Duration = .milliseconds(100)) {
        self.deviceID = deviceID
        self.channel = channel
        self.resizeDelay = resizeDelay
    }

    // MARK: - Opening, attaching, ending

    /// Start a shell at the size the emulator is drawn at.
    public func open(cols: Int, rows: Int) async {
        status = .connecting
        missedOutput = false
        lastSeq = 0
        acceptsAnySeq = false
        sentSize = TerminalSize(cols: cols, rows: rows)
        size = sentSize
        do {
            let result = try await channel.request(
                .terminalOpen(deviceID: deviceID, cols: cols, rows: rows),
                as: TerminalOpenResult.self)
            terminalID = result.terminalID
            status = .connected
        } catch {
            sentSize = nil
            status = .failed(message(for: error))
        }
    }

    /// Take the shell back after a lost socket. The scrollback lands in the
    /// emulator before output resumes, so the screen is what it was.
    public func attach() async {
        guard let terminalID else { return }
        status = .connecting
        do {
            let result = try await channel.request(
                .terminalAttach(deviceID: deviceID, terminalID: terminalID),
                as: TerminalAttachResult.self)
            if let bytes = result.scrollbackBytes, !bytes.isEmpty { onOutput?(bytes) }
            // The other side's numbering carries on; this app has not seen it.
            acceptsAnySeq = true
            missedOutput = false
            sentSize = TerminalSize(cols: result.cols, rows: result.rows)
            status = .connected
            // The view may have been rotated while the socket was down.
            if size != nil, size != sentSize { scheduleResize() }
        } catch {
            if let body = error as? GatewayErrorBody, body.code == .notFound {
                self.terminalID = nil
                status = .exited(code: nil)
            } else {
                status = .failed(message(for: error))
            }
        }
    }

    /// End the shell. Idempotent on the device, so leaving twice is safe.
    public func close() {
        guard let terminalID else { return }
        self.terminalID = nil
        sender?.cancel()
        resizer?.cancel()
        pendingInput = Data()
        let channel = self.channel
        let deviceID = self.deviceID
        Task { _ = try? await channel.request(.terminalClose(deviceID: deviceID, terminalID: terminalID)) }
    }

    // MARK: - The link under it

    /// The socket came back, or went. A terminal that was connected when the
    /// socket dropped attaches again by itself the moment it is up.
    public func link(isUp: Bool) {
        guard terminalID != nil else { return }
        if isUp {
            guard status == .disconnected else { return }
            Task { await attach() }
        } else {
            guard status == .connected || status == .connecting else { return }
            status = .disconnected
        }
    }

    // MARK: - Typing and size

    /// Bytes the person typed, or a key from the bar. Queued behind whatever is
    /// already in flight so they arrive in the order they were made.
    public func type(_ bytes: Data) {
        guard !bytes.isEmpty, terminalID != nil else { return }
        pendingInput.append(bytes)
        guard sender == nil else { return }
        sender = Task { [weak self] in await self?.drainInput() }
    }

    public func type(_ bytes: [UInt8]) { type(Data(bytes)) }

    private func drainInput() async {
        defer { sender = nil }
        while !pendingInput.isEmpty, let terminalID {
            let chunk = pendingInput.prefix(TerminalLimits.maxInputBytes)
            pendingInput.removeFirst(chunk.count)
            do {
                _ = try await channel.request(
                    .terminalInput(deviceID: deviceID, terminalID: terminalID, data: Data(chunk)))
            } catch {
                note(error)
                return
            }
        }
    }

    /// The emulator's view changed size. A run of them settles into one
    /// request: a rotation lays out many times and the shell needs the last.
    public func resize(cols: Int, rows: Int) {
        let next = TerminalSize(cols: cols, rows: rows)
        guard size != next else { return }
        size = next
        guard terminalID != nil else { return }
        scheduleResize()
    }

    private func scheduleResize() {
        resizer?.cancel()
        resizer = Task { [weak self] in
            guard let delay = self?.resizeDelay else { return }
            try? await Task.sleep(for: delay)
            guard !Task.isCancelled else { return }
            await self?.sendSize()
        }
    }

    private func sendSize() async {
        guard let terminalID, let size, sentSize != size else { return }
        sentSize = size
        do {
            _ = try await channel.request(.terminalResize(deviceID: deviceID, terminalID: terminalID,
                                                          cols: size.cols, rows: size.rows))
        } catch {
            sentSize = nil
            note(error)
        }
    }

    // MARK: - Frames

    /// Amendment A38: the two frames a terminal produces. Anything for another
    /// terminal or another machine is not this screen's.
    public func receive(_ frame: AppFrame) {
        switch frame {
        case .terminalOutput(let output):
            guard output.terminalID == terminalID, output.deviceID == deviceID else { return }
            apply(output)
        case .terminalExited(let exit):
            guard exit.terminalID == terminalID, exit.deviceID == deviceID else { return }
            terminalID = nil
            status = .exited(code: exit.code)
        default:
            break
        }
    }

    private func apply(_ output: TerminalOutput) {
        if acceptsAnySeq {
            acceptsAnySeq = false
        } else if output.seq <= lastSeq {
            // A late or duplicate frame. Feeding it twice would print it twice.
            return
        } else if output.seq != lastSeq + 1 {
            missedOutput = true
        }
        lastSeq = output.seq
        if status != .connected { status = .connected }
        guard let bytes = output.bytes, !bytes.isEmpty else { return }
        onOutput?(bytes)
    }

    // MARK: - Failures

    private func note(_ error: Error) {
        guard let body = error as? GatewayErrorBody else { return }
        switch body.code {
        case .notFound:
            terminalID = nil
            status = .exited(code: nil)
        case .deviceOffline:
            status = .disconnected
        default:
            status = .failed(body.message)
        }
    }

    /// What the reader is shown when a terminal will not open. The device's own
    /// sentence wherever it sent one, and the app's words for the one refusal
    /// it can word better: a machine that offers no shell at all.
    private func message(for error: Error) -> String {
        guard let body = error as? GatewayErrorBody else {
            return error.localizedDescription
        }
        switch body.code {
        case .unsupported: return L10n.string("This device does not offer a terminal.")
        case .conflict: return L10n.string("This device already runs four terminals. Close one first.")
        case .deviceOffline: return L10n.string("This device is offline.")
        default: return body.message
        }
    }
}

/// The emulator's size, clamped to what the device accepts (A38). A value type
/// rather than a pair, so "has it changed since the last request?" is one
/// comparison and reads as one.
public struct TerminalSize: Sendable, Hashable {
    public let cols: Int
    public let rows: Int

    public init(cols: Int, rows: Int) {
        self.cols = TerminalLimits.cols(cols)
        self.rows = TerminalLimits.rows(rows)
    }
}

/// The words the status line uses, kept beside the states they describe so the
/// screen reads as one line of English and the checks can read them too.
public enum TerminalStatusText {
    public static func line(for status: TerminalSession.Status) -> String {
        switch status {
        case .connecting: L10n.string("Connecting")
        case .connected: L10n.string("Connected")
        case .disconnected: L10n.string("Disconnected")
        case .exited(let code): exited(code: code)
        case .failed(let message): message
        }
    }

    public static func exited(code: Int?) -> String {
        guard let code else { return L10n.string("Shell exited") }
        return L10n.string("Shell exited (%lld)", code)
    }

    /// Whether this state is one the person can act on, and with which action.
    public static func offersReconnect(_ status: TerminalSession.Status) -> Bool {
        status == .disconnected
    }

    public static func offersNewShell(_ status: TerminalSession.Status) -> Bool {
        switch status {
        case .exited, .failed: true
        case .connecting, .connected, .disconnected: false
        }
    }
}
