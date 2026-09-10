import AppKit

// A black rounded square with a white "connected dots" mark, matching the app's
// own AppMark. App Store icons must not contain an alpha channel, so the drawing
// happens on an RGBA surface and is exported through a separate RGB bitmap.
let size = 1024
let ink = NSColor(calibratedRed: 0.067, green: 0.067, blue: 0.067, alpha: 1)

let bitmap = NSBitmapImageRep(bitmapDataPlanes: nil, pixelsWide: size, pixelsHigh: size,
                             bitsPerSample: 8, samplesPerPixel: 4, hasAlpha: true, isPlanar: false,
                             colorSpaceName: .deviceRGB, bytesPerRow: 0, bitsPerPixel: 0)!
NSGraphicsContext.saveGraphicsState()
NSGraphicsContext.current = NSGraphicsContext(bitmapImageRep: bitmap)

ink.setFill()
NSRect(x: 0, y: 0, width: size, height: size).fill()

// Three dots joined by two links: a device, the gateway and the phone.
let points = [NSPoint(x: 300, y: 660), NSPoint(x: 724, y: 660), NSPoint(x: 512, y: 330)]
let link = NSBezierPath()
link.move(to: points[0])
link.line(to: points[1])
link.line(to: points[2])
link.close()
link.lineWidth = 34
link.lineJoinStyle = .round
NSColor.white.withAlphaComponent(0.55).setStroke()
link.stroke()

for point in points {
    ink.setFill()
    NSBezierPath(ovalIn: NSRect(x: point.x - 92, y: point.y - 92, width: 184, height: 184)).fill()
    NSColor.white.setFill()
    NSBezierPath(ovalIn: NSRect(x: point.x - 62, y: point.y - 62, width: 124, height: 124)).fill()
}
NSGraphicsContext.restoreGraphicsState()

let rgb = NSBitmapImageRep(bitmapDataPlanes: nil, pixelsWide: size, pixelsHigh: size,
                          bitsPerSample: 8, samplesPerPixel: 3, hasAlpha: false, isPlanar: false,
                          colorSpaceName: .deviceRGB, bytesPerRow: 0, bitsPerPixel: 0)!
let source = bitmap.bitmapData!
let destination = rgb.bitmapData!
for y in 0..<size {
    for x in 0..<size {
        let sourceOffset = y * bitmap.bytesPerRow + x * 4
        let destinationOffset = y * rgb.bytesPerRow + x * 3
        precondition(source[sourceOffset + 3] == 255, "Icon drawing must be fully opaque")
        for channel in 0..<3 { destination[destinationOffset + channel] = source[sourceOffset + channel] }
    }
}

let url = URL(fileURLWithPath: CommandLine.arguments[1])
let png = rgb.representation(using: .png, properties: [:])!
precondition(png.count > 25 && png[25] == 2, "Icon encoder must produce RGB PNG without alpha")
try png.write(to: url, options: .atomic)
print("Wrote \(url.path)")
