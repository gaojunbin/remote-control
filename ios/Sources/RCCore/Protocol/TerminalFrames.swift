import Foundation

/// Amendment A38: bytes one terminal produced, on their way to the one app
/// connection that holds it.
///
/// The gateway strips the device's `to` and adds `device_id`, so an app reads
/// which machine the bytes came from and never learns which connection was
/// addressed. `seq` starts at 1 and rises by one per frame per terminal, which
/// is how a gap is told from a pause (protocol 7.3).
public struct TerminalOutput: Sendable, Hashable {
    public let terminalID: String
    public let deviceID: String
    public let seq: Int
    /// The bytes, still base64. Decoding is the screen's job, and a frame that
    /// is not valid base64 is dropped rather than fed as rubbish.
    public let data: String

    public init(terminalID: String, deviceID: String, seq: Int, data: String) {
        self.terminalID = terminalID
        self.deviceID = deviceID
        self.seq = seq
        self.data = data
    }

    public var bytes: Data? { Data(base64Encoded: data) }

    public init(json: JSONValue) throws {
        guard let object = json.objectValue,
              let terminalID = object.string("terminal_id"),
              let deviceID = object.string("device_id"),
              let seq = object.int("seq") else {
            throw ProtocolFailure.malformed("terminal.output")
        }
        self.terminalID = terminalID
        self.deviceID = deviceID
        self.seq = seq
        self.data = object.string("data") ?? ""
    }
}

/// Amendment A38: the shell ended and the terminal is gone. `code` is null
/// where the device could not read one, which is why it is an optional here
/// and not a zero.
public struct TerminalExited: Sendable, Hashable {
    public let terminalID: String
    public let deviceID: String
    public let code: Int?

    public init(terminalID: String, deviceID: String, code: Int?) {
        self.terminalID = terminalID
        self.deviceID = deviceID
        self.code = code
    }

    public init(json: JSONValue) throws {
        guard let object = json.objectValue,
              let terminalID = object.string("terminal_id"),
              let deviceID = object.string("device_id") else {
            throw ProtocolFailure.malformed("terminal.exited")
        }
        self.terminalID = terminalID
        self.deviceID = deviceID
        self.code = object.int("code")
    }
}

/// The reply to `terminal.open`: the id every later request names.
public struct TerminalOpenResult: Codable, Sendable, Hashable {
    public let terminalID: String

    public init(terminalID: String) { self.terminalID = terminalID }

    enum CodingKeys: String, CodingKey {
        case terminalID = "terminal_id"
    }
}

/// The reply to `terminal.attach`: the size the shell is running at and the
/// last 64 KiB it produced, so the emulator is caught up before output resumes.
public struct TerminalAttachResult: Codable, Sendable, Hashable {
    public let terminalID: String
    public let cols: Int
    public let rows: Int
    public let scrollback: String

    public init(terminalID: String, cols: Int, rows: Int, scrollback: String) {
        self.terminalID = terminalID
        self.cols = cols
        self.rows = rows
        self.scrollback = scrollback
    }

    public var scrollbackBytes: Data? { Data(base64Encoded: scrollback) }

    enum CodingKeys: String, CodingKey {
        case cols, rows, scrollback
        case terminalID = "terminal_id"
    }

    public init(from decoder: Decoder) throws {
        let values = try decoder.container(keyedBy: CodingKeys.self)
        terminalID = try values.decode(String.self, forKey: .terminalID)
        cols = try values.decodeIfPresent(Int.self, forKey: .cols) ?? 80
        rows = try values.decodeIfPresent(Int.self, forKey: .rows) ?? 24
        scrollback = try values.decodeIfPresent(String.self, forKey: .scrollback) ?? ""
    }
}

/// The bounds the device enforces on a terminal (protocol 6.3, A38). The app
/// clamps to them so a rotation into an odd size is never a refused request.
public enum TerminalLimits {
    public static let minCols = 1
    public static let maxCols = 500
    public static let minRows = 1
    public static let maxRows = 200
    /// At most 64 KiB decoded in one `terminal.input`.
    public static let maxInputBytes = 64 * 1024

    public static func cols(_ value: Int) -> Int { min(max(minCols, value), maxCols) }
    public static func rows(_ value: Int) -> Int { min(max(minRows, value), maxRows) }
}
