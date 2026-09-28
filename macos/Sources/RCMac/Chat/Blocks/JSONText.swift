import Foundation
import RCCore

/// `JSON.stringify(value, null, 2)`, which is how the web prints a tool's
/// input and an approval's: two-space indents, `"key": value`, empty objects
/// and arrays as `{}` and `[]`, and JavaScript's own escapes and numbers.
///
/// RCCore keeps an object as a dictionary, so the order the device wrote the
/// keys in is not known here. The keys are printed in the order JavaScript
/// would give a fresh object built from them: integer-like keys first, in
/// ascending order, then the rest — alphabetically, where the web keeps the
/// device's order.
public enum JSONText {
    public static func stringify(_ value: JSONValue) -> String {
        var out = ""
        write(value, indent: "", into: &out)
        return out
    }

    private static func write(_ value: JSONValue, indent: String, into out: inout String) {
        switch value {
        case .null: out += "null"
        case .bool(let flag): out += flag ? "true" : "false"
        case .integer(let number): out += String(number)
        case .number(let number): out += numberText(number)
        case .string(let text): out += quoted(text)
        case .array(let items):
            guard !items.isEmpty else { out += "[]"; return }
            let inner = indent + "  "
            out += "[\n"
            for (index, item) in items.enumerated() {
                out += inner
                write(item, indent: inner, into: &out)
                out += index == items.count - 1 ? "\n" : ",\n"
            }
            out += indent + "]"
        case .object(let fields):
            guard !fields.isEmpty else { out += "{}"; return }
            let inner = indent + "  "
            let keys = orderedKeys(fields.keys)
            out += "{\n"
            for (index, key) in keys.enumerated() {
                out += inner + quoted(key) + ": "
                write(fields[key] ?? .null, indent: inner, into: &out)
                out += index == keys.count - 1 ? "\n" : ",\n"
            }
            out += indent + "}"
        }
    }

    /// Integer-like keys ("0", "12") come first in ascending order, as a
    /// JavaScript object orders them.
    static func orderedKeys<Keys: Collection>(_ keys: Keys) -> [String] where Keys.Element == String {
        let indices = keys.compactMap { key -> (UInt32, String)? in
            guard let number = UInt32(key), String(number) == key, number < UInt32.max else { return nil }
            return (number, key)
        }
        let integers = Set(indices.map(\.1))
        return indices.sorted { $0.0 < $1.0 }.map(\.1) + keys.filter { !integers.contains($0) }.sorted()
    }

    /// `Number.prototype.toString`: the shortest digits that round-trip — the
    /// same digits Swift finds — in JavaScript's notation: fixed from 1e-6 up
    /// to 1e21, and `1.5e+21` or `1e-7` outside it.
    static func numberText(_ number: Double) -> String {
        guard number.isFinite else { return "null" }
        guard number != 0 else { return "0" }
        let described = "\(abs(number))"
        let parts = described.split(separator: "e")
        let exponent = parts.count > 1 ? Int(parts[1]) ?? 0 : 0
        let mantissa = String(parts[0])
        let point = mantissa.firstIndex(of: ".").map { mantissa.distance(from: mantissa.startIndex, to: $0) }
            ?? mantissa.count
        var digits = mantissa.replacingOccurrences(of: ".", with: "")
        var n = point + exponent
        while digits.hasPrefix("0") && digits.count > 1 {
            digits.removeFirst()
            n -= 1
        }
        while digits.hasSuffix("0") && digits.count > 1 { digits.removeLast() }
        let k = digits.count
        let sign = number < 0 ? "-" : ""
        if k <= n && n <= 21 { return sign + digits + String(repeating: "0", count: n - k) }
        if 0 < n && n <= 21 {
            return sign + digits.prefix(n) + "." + digits.dropFirst(n)
        }
        if -6 < n && n <= 0 { return sign + "0." + String(repeating: "0", count: -n) + digits }
        let tail = k > 1 ? "." + digits.dropFirst() : ""
        let power = n - 1
        return sign + digits.prefix(1) + tail + "e" + (power >= 0 ? "+" : "-") + String(abs(power))
    }

    /// A JSON string as `JSON.stringify` writes it: `"` and `\` escaped, the
    /// control characters as `\n`, `\t` … or `\u00XX`, everything else as is.
    static func quoted(_ text: String) -> String {
        var out = "\""
        for scalar in text.unicodeScalars {
            switch scalar {
            case "\"": out += "\\\""
            case "\\": out += "\\\\"
            case "\n": out += "\\n"
            case "\r": out += "\\r"
            case "\t": out += "\\t"
            case "\u{08}": out += "\\b"
            case "\u{0C}": out += "\\f"
            case let control where control.value < 0x20:
                out += String(format: "\\u%04x", control.value)
            default: out.unicodeScalars.append(scalar)
            }
        }
        return out + "\""
    }
}
