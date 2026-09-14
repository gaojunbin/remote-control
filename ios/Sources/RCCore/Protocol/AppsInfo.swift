import Foundation

/// Amendment A31: the oldest build of each separately installed app this
/// gateway still works with.
///
/// It rides on `GET /api/health`, `GET /api/config` and `hello`. The health
/// route is the one that matters most: it is unauthenticated, so an app too old
/// for a gateway is stopped at the sign-in form rather than after it. A gateway
/// that sends nothing states no requirement, which is what every gateway older
/// than the amendment does.
public struct AppsInfo: Codable, Sendable, Hashable {
    public let ios: AppSupport?

    public init(ios: AppSupport?) {
        self.ios = ios
    }

    public init(from decoder: Decoder) throws {
        let values = try decoder.container(keyedBy: CodingKeys.self)
        ios = try values.decodeIfPresent(AppSupport.self, forKey: .ios)
    }
}

/// What one app has to be, and where a newer build of it is.
public struct AppSupport: Codable, Sendable, Hashable {
    /// `major.minor.patch`. Anything else compares as zero, which is no bar.
    public let minimumVersion: String
    /// TestFlight or the App Store, when the operator named one. Always https.
    public let updateURL: String?

    public init(minimumVersion: String, updateURL: String? = nil) {
        self.minimumVersion = minimumVersion
        self.updateURL = updateURL
    }

    enum CodingKeys: String, CodingKey {
        case minimumVersion = "minimum_version"
        case updateURL = "update_url"
    }

    public init(from decoder: Decoder) throws {
        let values = try decoder.container(keyedBy: CodingKeys.self)
        minimumVersion = try values.decodeIfPresent(String.self, forKey: .minimumVersion) ?? ""
        updateURL = try values.decodeIfPresent(String.self, forKey: .updateURL)
    }
}
