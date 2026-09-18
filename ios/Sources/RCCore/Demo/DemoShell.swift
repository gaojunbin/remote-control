import Foundation

/// The shell behind a demo terminal (amendment A38).
///
/// It prints a prompt, echoes what is typed, answers a line with the line, and
/// ends on `exit` or Ctrl-D — enough to drive the screen, the key bar and the
/// reconnect without a machine to reach. Its output is bytes the way a real
/// shell's is, so the emulator on the other side is doing its real job.
///
/// What it prints is not the app's own words and is never translated: it stands
/// in for a machine, and a machine writes in its own language.
public struct DemoShell: Sendable {
    /// The last 64 KiB it produced, which is what an `attach` replies with.
    public private(set) var scrollback = Data()
    public private(set) var cols: Int
    public private(set) var rows: Int
    private var line = ""

    /// Protocol 7.3: the ring a device keeps per terminal.
    public static let scrollbackLimit = 64 * 1024
    private static let prompt = "demo:~$ "

    public init(cols: Int = 80, rows: Int = 24) {
        self.cols = cols
        self.rows = rows
    }

    public mutating func start() -> Data {
        emit("Remote Control demo shell — nothing here reaches a real machine.\r\n\(Self.prompt)")
    }

    public mutating func resize(cols: Int, rows: Int) {
        self.cols = TerminalLimits.cols(cols)
        self.rows = TerminalLimits.rows(rows)
    }

    /// Feeds typed bytes. The output is what the shell writes back; the code is
    /// set once the shell has ended, and nothing should be fed to it after.
    public mutating func feed(_ input: Data) -> (output: Data, code: Int?) {
        var written = ""
        for byte in input {
            switch byte {
            case 0x0d, 0x0a:
                written += "\r\n"
                if let code = answer(to: line.trimmingCharacters(in: .whitespaces), into: &written) {
                    return (emit(written), code)
                }
                line = ""
                written += Self.prompt
            case 0x7f, 0x08:
                guard !line.isEmpty else { continue }
                line.removeLast()
                written += "\u{8} \u{8}"
            case 0x03:
                line = ""
                written += "^C\r\n\(Self.prompt)"
            case 0x04:
                written += "exit\r\n"
                return (emit(written), 0)
            case 0x09:
                line += "\t"
                written += "\t"
            case 0x20...0x7e:
                let character = Character(Unicode.Scalar(byte))
                line.append(character)
                written.append(character)
            default:
                // Anything else — an arrow, a control the demo has no use for —
                // is swallowed rather than printed as rubbish.
                continue
            }
        }
        return (emit(written), nil)
    }

    /// What one line of input answers with, or an exit code when it ends the
    /// shell. Everything this shell knows is here, and it is deliberately
    /// little: a demo that pretends to be a machine invites being trusted as one.
    private mutating func answer(to command: String, into written: inout String) -> Int? {
        switch command {
        case "":
            return nil
        case "exit", "logout":
            written += "logout\r\n"
            return 0
        case "pwd":
            written += "/Users/me\r\n"
        case "size":
            written += "\(cols)x\(rows)\r\n"
        case "help":
            written += "This is a demo. Try: pwd, size, exit. Anything else is echoed back.\r\n"
        default:
            written += "\(command)\r\n"
        }
        return nil
    }

    private mutating func emit(_ text: String) -> Data {
        let bytes = Data(text.utf8)
        scrollback.append(bytes)
        if scrollback.count > Self.scrollbackLimit {
            scrollback.removeFirst(scrollback.count - Self.scrollbackLimit)
        }
        return bytes
    }
}
