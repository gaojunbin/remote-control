import Foundation

/// The directories `--demo` browses, and the one thing amendment A37 lets a
/// picker do to them.
///
/// The demo answered every `device.dirs` with one fixed listing while browsing
/// was all a picker could do. Making a folder has to be seen to have happened —
/// the new directory is listed, its parent holds it, and a second folder of the
/// same name is refused — so the demo keeps a tree and answers both requests
/// from it, with the errors a device would send.
struct DemoDirectoryTree: Sendable {
    /// Every directory that exists, by absolute path, holding its children's
    /// names. A path with no entry here is a directory the demo does not have.
    private var children: [String: Set<String>]
    /// The directories the demo draws a repository glyph for.
    private let repositories: Set<String>
    /// Where a listing starts when the request names no path.
    let home: String
    let recent: [RecentDirectory]

    /// One machine with a couple of projects on it, and the two directories the
    /// demo account worked in last.
    static var demo: DemoDirectoryTree {
        let now = DemoFixtures.now
        return DemoDirectoryTree(
            children: [
                "/Users/me": ["dev"],
                "/Users/me/dev": ["remote-control", "gateway", "notes"],
                "/Users/me/dev/remote-control": ["gateway", "web", "ios"],
                "/Users/me/dev/remote-control/gateway": [],
                "/Users/me/dev/remote-control/web": [],
                "/Users/me/dev/remote-control/ios": [],
                "/Users/me/dev/gateway": [],
                "/Users/me/dev/notes": []
            ],
            repositories: ["/Users/me/dev/remote-control", "/Users/me/dev/gateway"],
            home: "/Users/me/dev",
            recent: [
                RecentDirectory(path: "/Users/me/dev/remote-control/gateway", lastUsed: now - 7_200_000),
                RecentDirectory(path: "/Users/me/dev/remote-control/web", lastUsed: now - 86_400_000)
            ])
    }

    /// What `device.dirs` answers: the sub-directories of `path`, or of the home
    /// directory when the request named none.
    func listing(of path: String?) throws -> DirectoryListing {
        let target = path.flatMap { $0.isEmpty ? nil : $0 } ?? home
        guard let names = children[target] else {
            throw GatewayErrorBody(code: .notFound, message: "No such directory: \(target)")
        }
        let entries = names
            .sorted { $0.lowercased() < $1.lowercased() }
            .map { name -> DirectoryEntry in
                let child = "\(target)/\(name)"
                return DirectoryEntry(name: name, path: child, isGit: repositories.contains(child))
            }
        return DirectoryListing(path: target, parent: parent(of: target), entries: entries, recent: recent)
    }

    /// Amendment A37: one directory, one level, inside a directory that exists.
    /// The reply is the new directory's listing, which is empty.
    mutating func makeDirectory(in path: String, named name: String) throws -> DirectoryListing {
        try validate(name)
        guard let names = children[path] else {
            throw GatewayErrorBody(code: .notFound, message: "No such directory: \(path)")
        }
        guard !names.contains(name) else {
            throw GatewayErrorBody(code: .conflict, message: "\(path)/\(name) already exists")
        }
        children[path] = names.union([name])
        children["\(path)/\(name)"] = []
        return try listing(of: "\(path)/\(name)")
    }

    /// The name rules of A37, said the way a device says them: one sentence a
    /// person can act on, because the app shows whatever the device answered.
    private func validate(_ name: String) throws {
        func refuse(_ reason: String) -> GatewayErrorBody {
            GatewayErrorBody(code: .badRequest, message: reason)
        }
        guard !name.isEmpty else { throw refuse("A folder needs a name.") }
        guard !name.contains("/"), !name.contains("\\") else {
            throw refuse("A folder name cannot contain a slash.")
        }
        guard !name.contains("\0") else { throw refuse("That name cannot be used.") }
        guard !name.hasPrefix(".") else { throw refuse("A folder name cannot start with a dot.") }
        guard name.utf8.count <= 255 else { throw refuse("That name is longer than 255 bytes.") }
    }

    /// The directory above this one, or nothing when the demo's tree stops here.
    private func parent(of path: String) -> String? {
        let above = (path as NSString).deletingLastPathComponent
        return children[above] == nil ? nil : above
    }
}
