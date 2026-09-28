import AppKit

/// A task list item's `<input type="checkbox" disabled>` as Chrome draws its
/// own controls: a 13-point box with a 2-point radius — a light edge on an
/// almost-white fill, or a grey fill with a light check once done — its bottom
/// on the baseline, 4 points of margin before it and 3 after.
enum MDCheckbox {
    static let leading: CGFloat = 4
    static let trailing: CGFloat = 3
    static let side: CGFloat = 13

    @MainActor private static var cache: [Bool: NSImage] = [:]

    @MainActor static func image(checked: Bool) -> NSImage {
        if let cached = cache[checked] { return cached }
        let size = NSSize(width: leading + side + trailing, height: side)
        let image = NSImage(size: size, flipped: true) { _ in
            draw(checked: checked)
            return true
        }
        cache[checked] = image
        return image
    }

    private static func draw(checked: Bool) {
        let box = NSRect(x: leading, y: 0, width: side, height: side)
        let edge = NSColor(srgbRed: 0xD1 / 255, green: 0xD1 / 255, blue: 0xD1 / 255, alpha: 1)
        if checked {
            edge.setFill()
            NSBezierPath(roundedRect: box, xRadius: 2, yRadius: 2).fill()
            // Chrome's check: from a fifth across and halfway down, a fifth
            // over and down, then up to a fifth from the far corner.
            let check = NSBezierPath()
            check.move(to: NSPoint(x: box.minX + side * 0.2, y: box.minY + side * 0.5))
            check.line(to: NSPoint(x: box.minX + side * 0.4, y: box.minY + side * 0.7))
            check.line(to: NSPoint(x: box.maxX - side * 0.2, y: box.minY + side * 0.2))
            check.lineWidth = side * 0.16
            NSColor(srgbRed: 0xED / 255, green: 0xED / 255, blue: 0xED / 255, alpha: 1).setStroke()
            check.stroke()
        } else {
            let inner = box.insetBy(dx: 0.5, dy: 0.5)
            let path = NSBezierPath(roundedRect: inner, xRadius: 1.5, yRadius: 1.5)
            NSColor(srgbRed: 0xF8 / 255, green: 0xF8 / 255, blue: 0xF8 / 255, alpha: 1).setFill()
            path.fill()
            edge.setStroke()
            path.lineWidth = 1
            path.stroke()
        }
    }
}
