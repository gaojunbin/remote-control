import SwiftUI

/// SVG path data, read into a SwiftUI `Path`: every command of the `d`
/// attribute (M L H V C S Q T A Z, absolute and relative) with the shorthand
/// SVG allows — implicit repeats, numbers run together (`1.5.5`, `2-3`) and
/// arc flags written without a separator (`a1 1 0 011 1`). The icons and the
/// agent logos are the web's own SVG, so the Mac draws them from the same data.
enum SVGPath {
    static func parse(_ data: String) -> Path {
        var reader = Reader(data)
        var builder = Builder()
        var command: UInt8 = 0
        while let next = reader.nextCommand(continuing: command) {
            command = next
            builder.apply(command, reader: &reader)
            // After a moveto, further coordinate pairs are linetos.
            if command == UInt8(ascii: "M") { command = UInt8(ascii: "L") }
            if command == UInt8(ascii: "m") { command = UInt8(ascii: "l") }
            if reader.failed { break }
        }
        return builder.path
    }

    /// A `points` attribute: `x,y x,y …`.
    static func points(_ list: String) -> [CGPoint] {
        var reader = Reader(list)
        var points: [CGPoint] = []
        while let x = reader.number(), let y = reader.number() { points.append(CGPoint(x: x, y: y)) }
        return points
    }
}

private struct Reader {
    private let bytes: [UInt8]
    private var index = 0
    private(set) var failed = false

    init(_ text: String) { bytes = Array(text.utf8) }

    private static let commands = Set("MmLlHhVvCcSsQqTtAaZz".utf8)

    private mutating func skipSeparators() {
        while index < bytes.count, bytes[index] == 0x20 || bytes[index] == 0x2C
            || bytes[index] == 0x0A || bytes[index] == 0x0D || bytes[index] == 0x09 {
            index += 1
        }
    }

    /// The next command letter, or the current one again when numbers follow.
    mutating func nextCommand(continuing current: UInt8) -> UInt8? {
        skipSeparators()
        guard index < bytes.count else { return nil }
        if Self.commands.contains(bytes[index]) {
            defer { index += 1 }
            return bytes[index]
        }
        guard current != 0, current != UInt8(ascii: "Z"), current != UInt8(ascii: "z") else {
            failed = true
            return nil
        }
        return current
    }

    mutating func number() -> CGFloat? {
        skipSeparators()
        let start = index
        if index < bytes.count, bytes[index] == UInt8(ascii: "-") || bytes[index] == UInt8(ascii: "+") { index += 1 }
        var digits = false
        while index < bytes.count, (0x30...0x39).contains(bytes[index]) { index += 1; digits = true }
        if index < bytes.count, bytes[index] == UInt8(ascii: ".") {
            index += 1
            while index < bytes.count, (0x30...0x39).contains(bytes[index]) { index += 1; digits = true }
        }
        if digits, index < bytes.count, bytes[index] == UInt8(ascii: "e") || bytes[index] == UInt8(ascii: "E") {
            var probe = index + 1
            if probe < bytes.count, bytes[probe] == UInt8(ascii: "-") || bytes[probe] == UInt8(ascii: "+") { probe += 1 }
            if probe < bytes.count, (0x30...0x39).contains(bytes[probe]) {
                index = probe
                while index < bytes.count, (0x30...0x39).contains(bytes[index]) { index += 1 }
            }
        }
        guard digits, let text = String(bytes: bytes[start..<index], encoding: .ascii),
              let value = Double(text) else {
            index = start
            return nil
        }
        return CGFloat(value)
    }

    /// An arc flag: a single 0 or 1, which may touch the next number.
    mutating func flag() -> Bool? {
        skipSeparators()
        guard index < bytes.count, bytes[index] == UInt8(ascii: "0") || bytes[index] == UInt8(ascii: "1") else {
            return nil
        }
        defer { index += 1 }
        return bytes[index] == UInt8(ascii: "1")
    }

    mutating func point() -> CGPoint? {
        guard let x = number(), let y = number() else { return nil }
        return CGPoint(x: x, y: y)
    }

    mutating func fail() { failed = true }
}

private struct Builder {
    var path = Path()
    private var current = CGPoint.zero
    private var start = CGPoint.zero
    /// The last control point of a C/S or Q/T, for the reflection S and T use.
    private var lastCubic: CGPoint?
    private var lastQuad: CGPoint?

    mutating func apply(_ command: UInt8, reader: inout Reader) {
        let relative = command >= UInt8(ascii: "a")
        let base = relative ? current : .zero
        func offset(_ p: CGPoint) -> CGPoint { CGPoint(x: p.x + base.x, y: p.y + base.y) }
        var cubic: CGPoint?
        var quad: CGPoint?
        switch command | 0x20 {
        case UInt8(ascii: "m"):
            guard let p = reader.point() else { return reader.fail() }
            current = offset(p); start = current
            path.move(to: current)
        case UInt8(ascii: "l"):
            guard let p = reader.point() else { return reader.fail() }
            current = offset(p)
            path.addLine(to: current)
        case UInt8(ascii: "h"):
            guard let x = reader.number() else { return reader.fail() }
            current = CGPoint(x: relative ? current.x + x : x, y: current.y)
            path.addLine(to: current)
        case UInt8(ascii: "v"):
            guard let y = reader.number() else { return reader.fail() }
            current = CGPoint(x: current.x, y: relative ? current.y + y : y)
            path.addLine(to: current)
        case UInt8(ascii: "c"):
            guard let c1 = reader.point(), let c2 = reader.point(), let p = reader.point() else { return reader.fail() }
            path.addCurve(to: offset(p), control1: offset(c1), control2: offset(c2))
            cubic = offset(c2); current = offset(p)
        case UInt8(ascii: "s"):
            guard let c2 = reader.point(), let p = reader.point() else { return reader.fail() }
            let c1 = lastCubic.map { CGPoint(x: 2 * current.x - $0.x, y: 2 * current.y - $0.y) } ?? current
            path.addCurve(to: offset(p), control1: c1, control2: offset(c2))
            cubic = offset(c2); current = offset(p)
        case UInt8(ascii: "q"):
            guard let c = reader.point(), let p = reader.point() else { return reader.fail() }
            path.addQuadCurve(to: offset(p), control: offset(c))
            quad = offset(c); current = offset(p)
        case UInt8(ascii: "t"):
            guard let p = reader.point() else { return reader.fail() }
            let c = lastQuad.map { CGPoint(x: 2 * current.x - $0.x, y: 2 * current.y - $0.y) } ?? current
            path.addQuadCurve(to: offset(p), control: c)
            quad = c; current = offset(p)
        case UInt8(ascii: "a"):
            guard let rx = reader.number(), let ry = reader.number(), let rotation = reader.number(),
                  let large = reader.flag(), let sweep = reader.flag(), let p = reader.point() else {
                return reader.fail()
            }
            let end = offset(p)
            SVGArc.add(to: &path, from: current, to: end, radii: CGSize(width: rx, height: ry),
                       rotation: rotation, largeArc: large, sweep: sweep)
            current = end
        case UInt8(ascii: "z"):
            path.closeSubpath()
            current = start
        default:
            reader.fail()
        }
        lastCubic = cubic
        lastQuad = quad
    }
}
