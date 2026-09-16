import Foundation

/// What an attachment is called when it reaches the device.
///
/// `docs/DESIGN.md` § "The composer" → **Attachments are named for what they
/// are**: "A photo from the library is `photo-1.jpg`, `photo-2.jpg`, … in the
/// order attached, with the extension of what is actually sent, never the
/// library's own identifier; a camera shot is `photo.jpg`."
///
/// The library's identifier is a `PHAsset` local id — a UUID with slashes and
/// no extension at all — so a name taken from it tells the agent nothing about
/// the file it has been handed. Every photo leaves here re-encoded as JPEG by
/// `PhotoPreparation`, so every photo is named as one.
public enum AttachmentNaming {
    /// The extension for the only format a photo is ever sent in.
    public static let photoExtension = "jpg"

    /// A shot taken here and now. There is only ever one of it.
    public static let cameraPhoto = "photo.\(photoExtension)"

    /// A photo from the library, numbered by the order it was attached in.
    public static func libraryPhoto(_ index: Int) -> String { "photo-\(index).\(photoExtension)" }
}
