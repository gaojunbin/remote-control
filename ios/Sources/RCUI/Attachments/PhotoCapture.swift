import Foundation
import SwiftUI
import ImageIO
import RCCore
import UniformTypeIdentifiers
#if os(iOS)
import UIKit
#endif

enum PhotoPreparationError: LocalizedError {
    case invalidImage, imageTooLarge
    /// Built here rather than drawn by a `Text`, so it goes through `L10n` at
    /// the point it is made: the composer shows it with the verbatim
    /// initialiser and a catalogue entry alone would never reach it.
    var errorDescription: String? {
        switch self {
        case .invalidImage: L10n.string("That image could not be read.")
        case .imageTooLarge: L10n.string("That image is too large. Pick a smaller one.")
        }
    }
}

enum PhotoPreparation {
    /// Decode directly to a thumbnail, avoiding a full-resolution raster in app memory.
    static func jpeg(from data: Data) throws -> Data {
        guard data.count <= 64 * 1024 * 1024 else { throw PhotoPreparationError.imageTooLarge }
        guard let source = CGImageSourceCreateWithData(data as CFData, [kCGImageSourceShouldCache: false] as CFDictionary),
              let image = CGImageSourceCreateThumbnailAtIndex(source, 0, [
                kCGImageSourceCreateThumbnailFromImageAlways: true,
                kCGImageSourceCreateThumbnailWithTransform: true,
                kCGImageSourceThumbnailMaxPixelSize: 2048,
                kCGImageSourceShouldCacheImmediately: true
              ] as CFDictionary) else { throw PhotoPreparationError.invalidImage }
        let result = NSMutableData()
        guard let destination = CGImageDestinationCreateWithData(result, UTType.jpeg.identifier as CFString, 1, nil) else { throw PhotoPreparationError.invalidImage }
        // Re-encoding omits source EXIF, including location metadata.
        CGImageDestinationAddImage(destination, image, [kCGImageDestinationLossyCompressionQuality: 0.86] as CFDictionary)
        guard CGImageDestinationFinalize(destination), result.length <= 6 * 1024 * 1024 else { throw PhotoPreparationError.imageTooLarge }
        return result as Data
    }
}

#if os(iOS)
struct CameraCapture: UIViewControllerRepresentable {
    let onCapture: (Data) -> Void
    @Environment(\.dismiss) private var dismiss
    func makeCoordinator() -> Coordinator { Coordinator(parent: self) }
    func makeUIViewController(context: Context) -> UIImagePickerController {
        let picker = UIImagePickerController()
        // Documented as required: setting a source type the machine does not
        // have raises rather than refusing. The composer offers the item only
        // where `Camera.exists`, and this is the second lock on the same door.
        if UIImagePickerController.isSourceTypeAvailable(.camera) { picker.sourceType = .camera }
        picker.mediaTypes = [UTType.image.identifier]
        picker.modalPresentationStyle = .fullScreen
        picker.delegate = context.coordinator
        return picker
    }
    func updateUIViewController(_ controller: UIImagePickerController, context: Context) {}
    @MainActor final class Coordinator: NSObject, UINavigationControllerDelegate, UIImagePickerControllerDelegate {
        var parent: CameraCapture
        init(parent: CameraCapture) { self.parent = parent }
        func imagePickerControllerDidCancel(_ picker: UIImagePickerController) { parent.dismiss() }
        func imagePickerController(_ picker: UIImagePickerController, didFinishPickingMediaWithInfo info: [UIImagePickerController.InfoKey: Any]) {
            if let image = info[.originalImage] as? UIImage, let data = image.jpegData(compressionQuality: 0.9) { parent.onCapture(data) }
            parent.dismiss()
        }
    }
}
#endif
