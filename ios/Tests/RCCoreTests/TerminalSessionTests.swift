import Testing
import Foundation
@testable import RCCore

/// Amendment A38: one shell over the gateway, without an emulator under it.
///
/// What is checked here is everything the screen cannot see by looking: that
/// keystrokes leave in the order they were made, that a gap in `seq` is noticed
/// rather than guessed at, that a duplicate frame is not printed twice, and
/// that a lost socket ends in an `attach` whose scrollback arrives first.
@MainActor
@Suite("Amendment A38, a terminal over the gateway")
struct TerminalSessionTests {
    private let deviceID = "d1"

    private func session(_ channel: ShellChannel) -> (TerminalSession, Collected) {
        let session = TerminalSession(deviceID: deviceID, channel: channel,
                                      resizeDelay: .milliseconds(1))
        let collected = Collected()
        session.onOutput = { collected.append($0) }
        return (session, collected)
    }

    private func output(_ terminalID: String, seq: Int, _ text: String) -> AppFrame {
        .terminalOutput(TerminalOutput(terminalID: terminalID, deviceID: deviceID, seq: seq,
                                       data: Data(text.utf8).base64EncodedString()))
    }

    @Test("Opening asks for the size the emulator is drawn at, and gets an id")
    func opens() async throws {
        let channel = ShellChannel()
        let (session, _) = session(channel)
        await session.open(cols: 100, rows: 32)

        #expect(session.status == .connected)
        #expect(session.terminalID == ShellChannel.terminalID)
        let open = try #require(await channel.first("terminal.open"))
        #expect(open["device_id"]?.stringValue == deviceID)
        #expect(open["cols"]?.intValue == 100)
        #expect(open["rows"]?.intValue == 32)
    }

    @Test("A machine that offers no terminal is said in the app's own words")
    func unsupported() async {
        let channel = ShellChannel()
        await channel.refuse(.init(code: .unsupported, message: "terminal disabled"))
        let (session, _) = session(channel)
        await session.open(cols: 80, rows: 24)

        #expect(session.status == .failed("This device does not offer a terminal."))
        #expect(session.terminalID == nil)
        #expect(TerminalStatusText.offersNewShell(session.status))
    }

    @Test("A fifth terminal on one machine says what to do about it")
    func conflict() async {
        let channel = ShellChannel()
        await channel.refuse(.init(code: .conflict, message: "four already"))
        let (session, _) = session(channel)
        await session.open(cols: 80, rows: 24)

        #expect(session.status == .failed("This device already runs four terminals. Close one first."))
    }

    @Test("Output is fed in order, a repeat is dropped and a gap is noticed")
    func outputSequencing() async {
        let channel = ShellChannel()
        let (session, collected) = session(channel)
        await session.open(cols: 80, rows: 24)
        let id = ShellChannel.terminalID

        session.receive(output(id, seq: 1, "one"))
        session.receive(output(id, seq: 2, "two"))
        #expect(collected.text == "onetwo")
        #expect(!session.missedOutput)

        session.receive(output(id, seq: 2, "two again"))
        #expect(collected.text == "onetwo", "a frame already applied is not printed twice")
        #expect(!session.missedOutput, "and is not a gap either")

        session.receive(output(id, seq: 5, "five"))
        #expect(collected.text == "onetwofive")
        #expect(session.missedOutput, "three and four never arrived, and the line says so")
    }

    @Test("Another terminal's output, and another machine's, are not this screen's")
    func ignoresOtherTerminals() async {
        let channel = ShellChannel()
        let (session, collected) = session(channel)
        await session.open(cols: 80, rows: 24)

        session.receive(output("someone-else", seq: 1, "no"))
        session.receive(.terminalOutput(TerminalOutput(terminalID: ShellChannel.terminalID,
                                                       deviceID: "another-machine", seq: 1,
                                                       data: Data("no".utf8).base64EncodedString())))
        #expect(collected.text.isEmpty)
    }

    @Test("Keystrokes leave in the order they were typed")
    func inputOrder() async throws {
        let channel = ShellChannel()
        let (session, _) = session(channel)
        await session.open(cols: 80, rows: 24)

        for letter in "hello" { session.type(Data(String(letter).utf8)) }
        await channel.settle(untilRequests: 6)

        let typed = await channel.bodies("terminal.input")
            .compactMap { Data(base64Encoded: $0["data"]?.stringValue ?? "") }
            .map { String(decoding: $0, as: UTF8.self) }
            .joined()
        #expect(typed == "hello")
    }

    @Test("A run of size changes settles into one resize")
    func resizeSettles() async {
        let channel = ShellChannel()
        let (session, _) = session(channel)
        await session.open(cols: 80, rows: 24)

        session.resize(cols: 81, rows: 24)
        session.resize(cols: 90, rows: 30)
        session.resize(cols: 100, rows: 40)
        await channel.settle(untilRequests: 2)

        let resizes = await channel.bodies("terminal.resize")
        #expect(resizes.count == 1, "one request, not three")
        #expect(resizes.first?["cols"]?.intValue == 100)
        #expect(resizes.first?["rows"]?.intValue == 40)
    }

    @Test("A lost socket is said, and its return attaches with the scrollback first")
    func reattaches() async throws {
        let channel = ShellChannel()
        let (session, collected) = session(channel)
        await session.open(cols: 80, rows: 24)
        session.receive(output(ShellChannel.terminalID, seq: 1, "before"))

        session.link(isUp: false)
        #expect(session.status == .disconnected)
        #expect(TerminalStatusText.offersReconnect(session.status))

        await channel.scrollback("before the drop")
        await session.attach()
        #expect(session.status == .connected)
        #expect(collected.text == "beforebefore the drop",
                "the scrollback lands before anything new does")

        // Protocol 7.3: `seq` carries on from where the other connection left
        // it, so the first frame after an attach is never read as a gap.
        session.receive(output(ShellChannel.terminalID, seq: 91, "after"))
        #expect(!session.missedOutput)
        #expect(collected.text.hasSuffix("after"))
    }

    @Test("A terminal the device no longer keeps offers a new shell, not a retry")
    func attachAfterTheShellIsGone() async {
        let channel = ShellChannel()
        let (session, _) = session(channel)
        await session.open(cols: 80, rows: 24)
        session.link(isUp: false)

        await channel.refuse(.init(code: .notFound, message: "gone"))
        await session.attach()
        #expect(session.status == .exited(code: nil))
        #expect(session.terminalID == nil)
        #expect(TerminalStatusText.offersNewShell(session.status))
    }

    @Test("The shell ending frees the terminal and names its code")
    func exits() async {
        let channel = ShellChannel()
        let (session, _) = session(channel)
        await session.open(cols: 80, rows: 24)

        session.receive(.terminalExited(TerminalExited(terminalID: ShellChannel.terminalID,
                                                       deviceID: deviceID, code: 130)))
        #expect(session.status == .exited(code: 130))
        #expect(session.terminalID == nil)
        #expect(TerminalStatusText.line(for: session.status) == "Shell exited (130)")
        #expect(TerminalStatusText.exited(code: nil) == "Shell exited")
    }

    @Test("Closing ends the shell once, and typing after it sends nothing")
    func closes() async {
        let channel = ShellChannel()
        let (session, _) = session(channel)
        await session.open(cols: 80, rows: 24)

        session.close()
        session.close()
        session.type(Data("x".utf8))
        await channel.settle(untilRequests: 2)

        #expect(await channel.bodies("terminal.close").count == 1)
        #expect(await channel.bodies("terminal.input").isEmpty)
    }

    @Test("Nothing happens to a terminal that was never opened")
    func inertBeforeOpen() async {
        let channel = ShellChannel()
        let (session, _) = session(channel)

        session.type(Data("x".utf8))
        session.resize(cols: 90, rows: 30)
        session.link(isUp: false)
        session.close()
        await channel.settle(untilRequests: 1)

        #expect(await channel.bodies("terminal.input").isEmpty)
        #expect(await channel.bodies("terminal.resize").isEmpty)
        #expect(await channel.bodies("terminal.close").isEmpty)
        #expect(session.status == .connecting)
    }
}

/// What the emulator would have been fed.
@MainActor
private final class Collected {
    private(set) var bytes = Data()
    var text: String { String(decoding: bytes, as: UTF8.self) }
    func append(_ data: Data) { bytes.append(data) }
}

/// A gateway that answers the five terminal requests and remembers them.
private actor ShellChannel: GatewayChannel {
    static let terminalID = "t-1"

    nonisolated let events: AsyncStream<GatewayEvent>
    private var seen: [(type: String, body: [String: JSONValue])] = []
    private var refusal: GatewayErrorBody?
    private var scrollbackText = ""

    init() { events = AsyncStream<GatewayEvent>.makeStream().stream }

    func connect() async {}
    func disconnect() async {}

    func refuse(_ error: GatewayErrorBody) { refusal = error }
    func scrollback(_ text: String) { scrollbackText = text }

    func request(_ request: GatewayRequest) async throws -> JSONValue {
        seen.append((request.type, request.body))
        if let refusal { throw refusal }
        switch request.type {
        case "terminal.open":
            return try JSONValue.encode(TerminalOpenResult(terminalID: Self.terminalID))
        case "terminal.attach":
            return try JSONValue.encode(TerminalAttachResult(
                terminalID: Self.terminalID, cols: 80, rows: 24,
                scrollback: Data(scrollbackText.utf8).base64EncodedString()))
        default:
            return .object([:])
        }
    }

    func bodies(_ type: String) -> [[String: JSONValue]] {
        seen.filter { $0.type == type }.map(\.body)
    }

    func first(_ type: String) -> [String: JSONValue]? { bodies(type).first }

    /// Wait for the session's own tasks to run. They are main-actor tasks with
    /// no timer behind them, so a handful of yields is enough; the deadline is
    /// there so a broken expectation fails rather than hangs.
    func settle(untilRequests count: Int) async {
        let deadline = Date().addingTimeInterval(2)
        while seen.count < count, Date() < deadline {
            try? await Task.sleep(for: .milliseconds(5))
        }
        try? await Task.sleep(for: .milliseconds(20))
    }
}
