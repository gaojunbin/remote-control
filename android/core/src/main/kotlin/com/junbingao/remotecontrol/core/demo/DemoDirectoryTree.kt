package com.junbingao.remotecontrol.core.demo

import com.junbingao.remotecontrol.core.protocol.DirectoryEntry
import com.junbingao.remotecontrol.core.protocol.DirectoryListing
import com.junbingao.remotecontrol.core.protocol.GatewayErrorBody
import com.junbingao.remotecontrol.core.protocol.GatewayErrorCode
import com.junbingao.remotecontrol.core.protocol.RecentDirectory

/**
 * The directories `--demo` browses, and the one thing amendment A37 lets a picker do to them.
 *
 * The demo answered every `device.dirs` with one fixed listing while browsing was all a picker
 * could do. Making a folder has to be seen to have happened — the new directory is listed, its
 * parent holds it, and a second folder of the same name is refused — so the demo keeps a tree and
 * answers both requests from it, with the errors a device would send.
 */
internal class DemoDirectoryTree(
    /**
     * Every directory that exists, by absolute path, holding its children's names. A path with no
     * entry here is a directory the demo does not have.
     */
    private var children: Map<String, Set<String>>,
    /** The directories the demo draws a repository glyph for. */
    private val repositories: Set<String>,
    /** Where a listing starts when the request names no path. */
    val home: String,
    val recent: List<RecentDirectory>,
) {
    companion object {
        /** One machine with a couple of projects on it, and the two directories the demo account worked in last. */
        val demo: DemoDirectoryTree
            get() {
                val now = DemoFixtures.now
                return DemoDirectoryTree(
                    children = mapOf(
                        "/Users/me" to setOf("dev"),
                        "/Users/me/dev" to setOf("remote-control", "gateway", "notes"),
                        "/Users/me/dev/remote-control" to setOf("gateway", "web", "ios"),
                        "/Users/me/dev/remote-control/gateway" to emptySet(),
                        "/Users/me/dev/remote-control/web" to emptySet(),
                        "/Users/me/dev/remote-control/ios" to emptySet(),
                        "/Users/me/dev/gateway" to emptySet(),
                        "/Users/me/dev/notes" to emptySet(),
                    ),
                    repositories = setOf("/Users/me/dev/remote-control", "/Users/me/dev/gateway"),
                    home = "/Users/me/dev",
                    recent = listOf(
                        RecentDirectory(path = "/Users/me/dev/remote-control/gateway", lastUsed = now - 7_200_000),
                        RecentDirectory(path = "/Users/me/dev/remote-control/web", lastUsed = now - 86_400_000),
                    ),
                )
            }
    }

    /** What `device.dirs` answers: the sub-directories of `path`, or of the home directory when the request named none. */
    fun listing(of: String?): DirectoryListing {
        val target = of?.ifEmpty { null } ?: home
        val names = children[target]
            ?: throw GatewayErrorBody(code = GatewayErrorCode.notFound, message = "No such directory: $target")
        val entries = names.sortedBy { it.lowercase() }.map { name ->
            val child = "$target/$name"
            DirectoryEntry(name = name, path = child, isGit = child in repositories)
        }
        return DirectoryListing(path = target, parent = parent(of = target), entries = entries, recent = recent)
    }

    /**
     * Amendment A37: one directory, one level, inside a directory that exists. The reply is the
     * new directory's listing, which is empty.
     */
    fun makeDirectory(path: String, named: String): DirectoryListing {
        validate(named)
        val names = children[path]
            ?: throw GatewayErrorBody(code = GatewayErrorCode.notFound, message = "No such directory: $path")
        if (named in names) {
            throw GatewayErrorBody(code = GatewayErrorCode.conflict, message = "$path/$named already exists")
        }
        children = children + (path to names + named) + ("$path/$named" to emptySet())
        return listing(of = "$path/$named")
    }

    /**
     * The name rules of A37, said the way a device says them: one sentence a person can act on,
     * because the app shows whatever the device answered.
     */
    private fun validate(name: String) {
        fun refuse(reason: String) = GatewayErrorBody(code = GatewayErrorCode.badRequest, message = reason)
        if (name.isEmpty()) throw refuse("A folder needs a name.")
        if ('/' in name || '\\' in name) throw refuse("A folder name cannot contain a slash.")
        if ('\u0000' in name) throw refuse("That name cannot be used.")
        if (name.startsWith(".")) throw refuse("A folder name cannot start with a dot.")
        if (name.encodeToByteArray().size > 255) throw refuse("That name is longer than 255 bytes.")
    }

    /** The directory above this one, or nothing when the demo's tree stops here. */
    private fun parent(of: String): String? {
        val above = deletingLastPathComponent(of)
        return if (children[above] == null) null else above
    }
}

/**
 * `NSString.deletingLastPathComponent`: the path without its last component — `/` for one at the
 * top, nothing for a bare name — and without the slashes that ended it.
 */
private fun deletingLastPathComponent(path: String): String {
    val trimmed = path.trimEnd('/')
    if (trimmed.isEmpty()) return if (path.startsWith("/")) "/" else ""
    val slash = trimmed.lastIndexOf('/')
    if (slash < 0) return ""
    return trimmed.substring(0, slash).trimEnd('/').ifEmpty { "/" }
}
