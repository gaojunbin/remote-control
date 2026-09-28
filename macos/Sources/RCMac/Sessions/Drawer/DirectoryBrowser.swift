import Foundation
import Observation
import RCCore

/// What the directory picker holds (`DirectoryPicker.tsx`): the listing on
/// screen, and the one folder name being typed while New folder is open (A37).
/// A new one is made each time the picker opens.
@MainActor
@Observable
final class DirectoryBrowser {
    private(set) var listing: DirectoryListing?
    private(set) var loading = true
    private(set) var error: String?
    private(set) var naming = false
    var folderName = ""
    private(set) var folderBusy = false
    private(set) var folderError: String?

    let deviceID: String
    @ObservationIgnored private let channel: (any GatewayChannel)?

    init(deviceID: String, channel: (any GatewayChannel)?) {
        self.deviceID = deviceID
        self.channel = channel
    }

    /// Stand in `path`, or in the device's home when there is none.
    func open(_ path: String?) async {
        // The name being typed belongs to the directory on screen, so leaving
        // it takes the row with it.
        naming = false
        loading = true
        defer { loading = false }
        do {
            guard let channel else { throw TransportError.notConnected }
            listing = try await channel.request(.dirs(deviceID: deviceID, path: path), as: DirectoryListing.self)
            error = nil
        } catch {
            self.error = Self.message(error)
        }
    }

    func startNaming() {
        folderName = ""
        folderError = nil
        naming = true
    }

    func stopNaming() { naming = false }

    /// A37: the reply is the new directory's own listing, so making a folder is
    /// also walking into it — "Use this directory" then picks what was just made.
    func createFolder() async {
        let name = folderName.trimmingCharacters(in: .whitespacesAndNewlines)
        guard let listing, !name.isEmpty, !folderBusy else { return }
        folderBusy = true
        folderError = nil
        defer { folderBusy = false }
        do {
            guard let channel else { throw TransportError.notConnected }
            self.listing = try await channel.request(.mkdir(deviceID: deviceID, path: listing.path, name: name),
                                                     as: DirectoryListing.self)
            error = nil
            naming = false
            folderName = ""
        } catch {
            folderError = Self.folderRefusal(error)
        }
    }

    /// `conflict` is the one outcome the picker says in its own words — the
    /// device reports an existing entry of any kind, and a person only needs to
    /// know the name is taken. Everything else, `bad_request` included, is the
    /// device's own sentence.
    static func folderRefusal(_ error: any Error) -> String {
        if (error as? GatewayErrorBody)?.code == .conflict { return S.newSession.newFolderExists }
        return ErrorText.text(error)
    }

    /// A listing the device refused shows its own words; a failure that never
    /// reached it, the generic sentence.
    private static func message(_ error: any Error) -> String? {
        guard let reply = error as? GatewayErrorBody else { return S.errors.generic }
        return reply.message.isEmpty || reply.message == reply.code.rawValue ? nil : reply.message
    }
}
