import Foundation

/// The words a machine is described by: the line its row carries and the line
/// its page carries.
///
/// `docs/DESIGN.md` § "The device row" (owner's ruling, 2026-09-17): the row
/// says the state and the platform as a word, and nothing else — the name is
/// the title above it, and the hostname repeating that name one line down was
/// the noise the ruling removed. The hostname and the architecture stay on the
/// machine's own page, which is where someone goes to check them.
///
/// The platform is a product name, so it is mapped rather than translated, and
/// an id this build has never heard of prints as itself: a gateway that grows a
/// third platform needs no new app.
public enum DeviceLine {
    public static let separator = " · "

    private static let platforms = [
        "macos": "macOS",
        "linux": "Linux"
    ]

    /// The platform as a word, never the raw id.
    public static func platformName(_ platform: DevicePlatform) -> String {
        platforms[platform.rawValue] ?? platform.rawValue
    }

    /// The row's line, beside its dot: what the machine is doing and what it
    /// runs. It names neither the machine nor any agent on it.
    public static func status(_ device: Device) -> String {
        [device.online ? L10n.string("online") : L10n.string("offline"),
         platformName(device.platform)]
            .joined(separator: separator)
    }

    /// The page's line, under the machine's name: what it calls itself and what
    /// it is built on. Either half is dropped where the device reported none.
    public static func facts(_ device: Device) -> String {
        [device.hostname, device.arch]
            .filter { !$0.isEmpty }
            .joined(separator: separator)
    }
}
