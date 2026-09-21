import Foundation

/// A `major.minor.patch` build number, compared part by part.
///
/// `"1.2.3" < "1.10.0"`, because ten is a number here and not a character.
/// Missing parts are zero and a part that is not a number is zero, so reading a
/// version can never fail and never throws: the worst a malformed string can do
/// is compare low.
public struct AppVersion: Comparable, Sendable, Hashable, CustomStringConvertible {
    public let major: Int
    public let minor: Int
    public let patch: Int

    public init(major: Int, minor: Int, patch: Int) {
        self.major = major
        self.minor = minor
        self.patch = patch
    }

    public init(_ text: String) {
        let parts = text.split(separator: ".", omittingEmptySubsequences: false)
            .map { Int($0.trimmingCharacters(in: .whitespaces)) ?? 0 }
        major = parts.count > 0 ? parts[0] : 0
        minor = parts.count > 1 ? parts[1] : 0
        patch = parts.count > 2 ? parts[2] : 0
    }

    public static func < (lhs: AppVersion, rhs: AppVersion) -> Bool {
        (lhs.major, lhs.minor, lhs.patch) < (rhs.major, rhs.minor, rhs.patch)
    }

    public var description: String { "\(major).\(minor).\(patch)" }
}

/// This build's own version, as the gateway's minimum is measured against it.
public enum AppBuild {
    /// The version this source tree ships. `ios/project.yml` sets the same
    /// number as `MARKETING_VERSION` and `RCUIVerify` holds the two together,
    /// so one edit here and one there is the whole version bump. Everything
    /// with no app bundle to ask reads it: the check suites, the UI test
    /// runner, and the demo gateway, whose served client is this app's own
    /// version because a round ships all four components together.
    public static let shipped = "1.5.4"

    /// `CFBundleShortVersionString`, which the Xcode project fills from
    /// `MARKETING_VERSION`, and `shipped` wherever there is no app bundle.
    public static let version: String =
        Bundle.main.object(forInfoDictionaryKey: "CFBundleShortVersionString") as? String ?? shipped
}

/// Amendment A31: this build is older than the gateway will talk to.
///
/// Nothing else in the app is reachable while one of these is set, so the rule
/// that produces it is pure and small enough to read in one go.
public struct AppUpdateRequirement: Sendable, Hashable {
    /// What this app is.
    public let current: AppVersion
    /// The oldest build the gateway works with.
    public let minimum: AppVersion
    /// Where a newer build is, when the operator named a place. Only https.
    public let updateURL: URL?

    public init(current: AppVersion, minimum: AppVersion, updateURL: URL? = nil) {
        self.current = current
        self.minimum = minimum
        self.updateURL = updateURL
    }

    /// The rule: nil when the gateway states no minimum, when this build meets
    /// it, and when this build is newer. Only "below" produces a requirement.
    public static func of(_ apps: AppsInfo?, current: String = AppBuild.version) -> AppUpdateRequirement? {
        guard let ios = apps?.ios else { return nil }
        let minimum = AppVersion(ios.minimumVersion)
        let version = AppVersion(current)
        guard version < minimum else { return nil }
        return AppUpdateRequirement(current: version, minimum: minimum,
                                    updateURL: updateLink(ios.updateURL))
    }

    /// A link the app will open, or nothing. The schema says https and the app
    /// checks it too: a blocking screen is no place to follow an odd scheme.
    private static func updateLink(_ text: String?) -> URL? {
        guard let text, let url = URL(string: text), url.scheme?.lowercased() == "https" else { return nil }
        return url
    }
}
