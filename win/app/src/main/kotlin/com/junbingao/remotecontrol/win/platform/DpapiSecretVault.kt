package com.junbingao.remotecontrol.win.platform

import com.junbingao.remotecontrol.core.persistence.SecretStore
import com.junbingao.remotecontrol.core.transport.TransportError
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

/**
 * The core's `SecretStore` on Windows: the token on disk, sealed with Windows' data protection
 * for the signed-in Windows user (`docs/DESIGN.md` § "The Windows app": DPAPI, in the app's own
 * data folder), where the Mac keeps it in the Keychain. Each key is one file in `directory`,
 * holding the sealed bytes and nothing else; only the same Windows account on the same machine can
 * open it, and a file another account or another machine made reads as unavailable rather than as
 * a token, as a Keychain item the app may not read does. A write replaces the file whole, so a
 * crash leaves the old token or the new one and never half of either. `--ephemeral`, the renderer
 * and a run anywhere but Windows keep the token in the core's `MemorySecretStore` instead.
 */
class DpapiSecretVault(private val directory: Path, private val protector: Protector = Dpapi) : SecretStore {
    /** What seals the bytes: Windows' DPAPI in the app, and a stand-in in the tests that run anywhere. */
    interface Protector {
        fun protect(data: ByteArray): ByteArray

        fun unprotect(data: ByteArray): ByteArray
    }

    private val lock = Mutex()

    override suspend fun read(key: String): ByteArray? = io {
        val file = file(key)
        if (!Files.exists(file)) return@io null
        val sealed = Files.readAllBytes(file)
        runCatching { protector.unprotect(sealed) }.getOrElse { throw TransportError.SecureStorageUnavailable }
    }

    override suspend fun write(data: ByteArray, key: String) = io {
        val sealed = runCatching { protector.protect(data) }.getOrElse { throw TransportError.SecureStorageUnavailable }
        Files.createDirectories(directory)
        val temporary = Files.createTempFile(directory, ".vault", ".tmp")
        try {
            Files.write(temporary, sealed)
            try {
                Files.move(temporary, file(key), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(temporary, file(key), StandardCopyOption.REPLACE_EXISTING)
            }
        } finally {
            Files.deleteIfExists(temporary)
        }
        Unit
    }

    override suspend fun remove(key: String) = io {
        Files.deleteIfExists(file(key))
        Unit
    }

    /** One file per key; a key that is not a plain name is spelled in hex, so any key is a file name. */
    internal fun file(key: String): Path {
        val plain = key.isNotEmpty() && key.all { it.isLetterOrDigit() && it.code < 0x80 || it == '.' || it == '-' || it == '_' }
        val name = if (plain && !key.startsWith(".")) key else "x-" + key.toByteArray().joinToString("") { "%02x".format(it) }
        return directory.resolve("$name.secret")
    }

    private suspend fun <T> io(block: () -> T): T = lock.withLock { withContext(Dispatchers.IO) { block() } }
}
