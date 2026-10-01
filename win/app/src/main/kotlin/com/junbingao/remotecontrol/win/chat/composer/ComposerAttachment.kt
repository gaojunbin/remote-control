package com.junbingao.remotecontrol.win.chat.composer

import com.junbingao.remotecontrol.core.protocol.OutboundAttachment
import com.junbingao.remotecontrol.win.shared.AttachmentLimits
import com.junbingao.remotecontrol.win.shared.Format
import com.junbingao.remotecontrol.win.strings.S
import java.io.File
import java.io.IOException
import java.net.URLConnection
import java.nio.file.Files
import java.util.UUID

/**
 * A file the composer holds for the next message (`AttachmentDraft` in `attachments.ts`): what goes
 * on the wire, and the size its chip prints.
 */
class ComposerAttachment(
    val id: UUID = UUID.randomUUID(),
    val name: String,
    val mime: String,
    val data: ByteArray,
) {
    val size: Int get() = data.size

    val outbound: OutboundAttachment get() = OutboundAttachment(id = id, name = name, mime = mime, data = data)

    override fun equals(other: Any?): Boolean = other is ComposerAttachment && id == other.id &&
        name == other.name && mime == other.mime && data.contentEquals(other.data)

    override fun hashCode(): Int = listOf(id, name, mime, data.contentHashCode()).hashCode()

    override fun toString(): String = "ComposerAttachment(name=$name, mime=$mime, size=$size)"
}

/**
 * Where the files of one attach come from: a file the person chose, dropped or pasted, or the
 * picture on the clipboard, which a browser hands a page as a file named `image.png`.
 */
sealed interface AttachmentSource {
    data class File(val file: java.io.File) : AttachmentSource

    class Data(val name: String, val mime: String, val data: ByteArray) : AttachmentSource
}

/**
 * `readAttachments` in `web/src/features/chat/attachments.ts`: the files read, and one sentence for
 * each one that could not be. The budget is what was left when the read began; the store enforces
 * the cap again where the list is written, because two reads can be in flight at once.
 */
class AttachmentRead {
    val attachments = mutableListOf<ComposerAttachment>()
    val errors = mutableListOf<String>()

    /** A file past the contract's size is refused before it is read. */
    private fun admit(name: String, size: Long?): Boolean {
        if (size == null || size <= AttachmentLimits.maxAttachmentBytes) return true
        errors += S.composer.attachTooLarge(name, Format.bytes(AttachmentLimits.maxAttachmentBytes))
        return false
    }

    companion object {
        fun read(sources: List<AttachmentSource>, alreadyAttached: Int): AttachmentRead {
            val result = AttachmentRead()
            var budget = AttachmentLimits.maxAttachments - alreadyAttached
            for (source in sources) {
                if (budget <= 0) {
                    result.errors += S.composer.attachTooMany(AttachmentLimits.maxAttachments)
                    break
                }
                when (source) {
                    is AttachmentSource.Data -> if (result.admit(source.name, source.data.size.toLong())) {
                        result.attachments += ComposerAttachment(name = source.name, mime = source.mime, data = source.data)
                        budget -= 1
                    }
                    is AttachmentSource.File -> {
                        val name = source.file.name
                        if (!result.admit(name, size(source.file))) continue
                        val data = contents(source.file)
                        if (data == null) {
                            result.errors += S.composer.attachFailed(name)
                            continue
                        }
                        result.attachments += ComposerAttachment(name = name, mime = mime(source.file), data = data)
                        budget -= 1
                    }
                }
            }
            return result
        }

        private fun size(file: File): Long? = if (file.isFile) file.length() else null

        private fun contents(file: File): ByteArray? {
            if (!file.isFile) return null
            return try {
                file.readBytes()
            } catch (_: IOException) {
                null
            } catch (_: SecurityException) {
                null
            }
        }

        /**
         * The type a browser gives the same file, from its extension: the system's own table first —
         * Windows' registry, which Chrome consults there too, and UTType on a Mac, which is what the
         * Mac app reads — then Java's.
         */
        fun mime(file: File): String {
            val system = try {
                Files.probeContentType(file.toPath())
            } catch (_: IOException) {
                null
            } catch (_: SecurityException) {
                null
            }
            return system ?: URLConnection.guessContentTypeFromName(file.name) ?: "application/octet-stream"
        }
    }
}
