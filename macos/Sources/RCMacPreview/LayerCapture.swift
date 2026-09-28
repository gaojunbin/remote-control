import AppKit
import Metal
import QuartzCore

/// A picture of a window as Core Animation composites it on screen.
///
/// The layer tree is drawn through `CARenderer` into a Metal texture: the same
/// compositor the window server runs, so shadows fall off as they do in a live
/// window, and blurs and masks are drawn. `NSView.cacheDisplay` and
/// `CALayer.render(in:)` go through Core Graphics instead, which draws layer
/// shadows at half their spread, places their offsets upside down, and skips
/// every filter — none of which a live window does.
@MainActor
enum LayerCapture {
    enum Failure: Error, CustomStringConvertible {
        case noMetal
        case noLayer
        case encode

        var description: String {
            switch self {
            case .noMetal: "this Mac has no Metal device to render with"
            case .noLayer: "the window has no layer to render"
            case .encode: "the picture could not be encoded as PNG"
            }
        }
    }

    /// The layer tree under `view`, at `scale` pixels per point, as sRGB PNG data.
    static func png(of view: NSView, scale: Int) throws -> Data {
        guard let layer = view.layer else { throw Failure.noLayer }
        guard let device = MTLCreateSystemDefaultDevice(), let queue = device.makeCommandQueue() else {
            // A machine with no GPU to render with still gets a picture, through
            // Core Graphics, with the differences the note above lists.
            FileHandle.standardError.write(Data("no Metal device: drawing through Core Graphics instead\n".utf8))
            return try coreGraphicsPNG(of: layer, size: view.bounds.size, scale: scale)
        }
        let width = Int(view.bounds.width) * scale
        let height = Int(view.bounds.height) * scale
        let descriptor = MTLTextureDescriptor.texture2DDescriptor(pixelFormat: .bgra8Unorm, width: width,
                                                                  height: height, mipmapped: false)
        descriptor.usage = [.renderTarget, .shaderRead, .shaderWrite]
        descriptor.storageMode = .private
        guard let texture = device.makeTexture(descriptor: descriptor),
              let buffer = device.makeBuffer(length: width * height * 4, options: .storageModeShared) else {
            throw Failure.noMetal
        }
        let sRGB = CGColorSpace(name: CGColorSpace.sRGB)!

        // The renderer draws a point as a pixel, so the tree is scaled for the
        // length of the frame and put back afterwards, with no animation.
        let saved = layer.transform
        CATransaction.begin()
        CATransaction.setDisableActions(true)
        layer.transform = CATransform3DMakeScale(CGFloat(scale), CGFloat(scale), 1)
        CATransaction.commit()
        defer {
            CATransaction.begin()
            CATransaction.setDisableActions(true)
            layer.transform = saved
            CATransaction.commit()
        }

        let renderer = CARenderer(mtlTexture: texture, options: [
            kCARendererMetalCommandQueue as String: queue,
            kCARendererColorSpace as String: sRGB
        ])
        renderer.layer = layer
        renderer.bounds = CGRect(x: 0, y: 0, width: width, height: height)
        CATransaction.flush()
        renderer.beginFrame(atTime: CACurrentMediaTime(), timeStamp: nil)
        renderer.addUpdate(renderer.bounds)
        renderer.render()
        renderer.endFrame()

        guard let commands = queue.makeCommandBuffer(), let blit = commands.makeBlitCommandEncoder() else {
            throw Failure.noMetal
        }
        blit.copy(from: texture, sourceSlice: 0, sourceLevel: 0, sourceOrigin: MTLOrigin(x: 0, y: 0, z: 0),
                  sourceSize: MTLSize(width: width, height: height, depth: 1), to: buffer,
                  destinationOffset: 0, destinationBytesPerRow: width * 4,
                  destinationBytesPerImage: width * height * 4)
        blit.endEncoding()
        commands.commit()
        commands.waitUntilCompleted()
        return try encode(buffer.contents(), width: width, height: height, colorSpace: sRGB)
    }

    /// `CALayer.render(in:)` into an sRGB bitmap: no filters, and shadows as
    /// Core Graphics draws them.
    private static func coreGraphicsPNG(of layer: CALayer, size: CGSize, scale: Int) throws -> Data {
        let width = Int(size.width) * scale, height = Int(size.height) * scale
        let sRGB = CGColorSpace(name: CGColorSpace.sRGB)!
        guard let context = CGContext(data: nil, width: width, height: height, bitsPerComponent: 8, bytesPerRow: width * 4,
                                      space: sRGB, bitmapInfo: CGImageAlphaInfo.premultipliedLast.rawValue) else {
            throw Failure.encode
        }
        context.scaleBy(x: CGFloat(scale), y: CGFloat(scale))
        layer.render(in: context)
        guard let data = context.data else { throw Failure.encode }
        return try encode(data, width: width, height: height, colorSpace: sRGB,
                          info: CGImageAlphaInfo.premultipliedLast.rawValue)
    }

    /// The texture's rows run bottom to top, as Core Animation's do; a PNG's
    /// run top to bottom.
    private static func encode(_ pixels: UnsafeMutableRawPointer, width: Int, height: Int,
                               colorSpace: CGColorSpace,
                               info: UInt32 = CGImageAlphaInfo.premultipliedFirst.rawValue
                                   | CGBitmapInfo.byteOrder32Little.rawValue) throws -> Data {
        let rowBytes = width * 4
        let flipped = UnsafeMutableRawPointer.allocate(byteCount: rowBytes * height, alignment: 16)
        defer { flipped.deallocate() }
        for row in 0..<height {
            flipped.advanced(by: row * rowBytes)
                .copyMemory(from: pixels.advanced(by: (height - 1 - row) * rowBytes), byteCount: rowBytes)
        }
        guard let context = CGContext(data: flipped, width: width, height: height, bitsPerComponent: 8,
                                      bytesPerRow: rowBytes, space: colorSpace, bitmapInfo: info),
              let image = context.makeImage(),
              let data = NSBitmapImageRep(cgImage: image).representation(using: .png, properties: [:]) else {
            throw Failure.encode
        }
        return data
    }
}
