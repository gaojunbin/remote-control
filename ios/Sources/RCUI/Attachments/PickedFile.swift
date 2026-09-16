import Foundation

/// A file the person picked from the document browser.
public enum PickedFile {
    /// Read it, eagerly.
    ///
    /// Never `.mappedIfSafe`: the security-scoped access a picked URL carries
    /// is released the moment the caller's scope ends, and the bytes are not
    /// touched again until the message is base64-encoded on its way out. For a
    /// file outside the app container — iCloud Drive, a file provider, another
    /// app's container — faulting a page of a mapping whose scoped access has
    /// ended is a `SIGBUS` rather than a thrown error. The 6 MB cap the caller
    /// checks first is what makes the copy cheap.
    public static func read(_ url: URL) throws -> Data {
        try Data(contentsOf: url)
    }

    /// How big it is, before any of it is read: a large video would spike
    /// memory and could be jetsammed before a size guard after the read ever
    /// ran.
    public static func size(of url: URL) -> Int {
        (try? url.resourceValues(forKeys: [.fileSizeKey]).fileSize) ?? 0
    }
}
