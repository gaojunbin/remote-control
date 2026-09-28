import Foundation
import RCCore

/// Where this run keeps what outlives a screen: the bearer token, the
/// preferences, the cached lists and transcripts, the drafts.
///
/// An ordinary run keeps them where the app always does — the Keychain under
/// the Mac app's own service, the standard defaults, Application Support.
/// `--ephemeral` keeps the token and the defaults in memory and the files in a
/// scratch directory that quitting removes, so nothing of the person's is read
/// or written.
@MainActor
public final class Persistence {
    public let defaults: UserDefaults
    public let secrets: any SecretStore
    public let cache: LocalCache
    public let drafts: DraftStore
    /// Where an ephemeral run keeps its cache and its drafts.
    private let scratch: URL?

    /// The Keychain service the Mac app files its gateway token under.
    public static let keychainService = "com.junbingao.remotecontrol.mac.gateway"

    public init(ephemeral: Bool) {
        if ephemeral {
            let scratch = FileManager.default.temporaryDirectory
                .appending(path: "RemoteControl-\(UUID().uuidString)", directoryHint: .isDirectory)
            self.scratch = scratch
            defaults = MemoryDefaults()
            secrets = MemorySecretStore()
            cache = LocalCache(directory: scratch.appending(path: "Cache", directoryHint: .isDirectory))
            drafts = DraftStore(directory: scratch.appending(path: "Drafts", directoryHint: .isDirectory))
        } else {
            scratch = nil
            defaults = .standard
            secrets = KeychainSecretStore(service: Self.keychainService)
            cache = LocalCache()
            drafts = DraftStore()
        }
    }

    public var isEphemeral: Bool { scratch != nil }

    /// Remove what an ephemeral run wrote: the scratch directory of its cache
    /// and drafts. Its defaults and its token were only ever in memory. Called
    /// when the app quits, and by the renderer and the tests after every model.
    public func discard() {
        if let scratch { try? FileManager.default.removeItem(at: scratch) }
    }
}
