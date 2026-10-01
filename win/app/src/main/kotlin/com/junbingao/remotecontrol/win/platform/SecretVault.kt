package com.junbingao.remotecontrol.win.platform

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Where the gateway's token is kept, in the shape of the core's `SecretStore`: bytes under a key,
 * read, written and removed. The Windows app keeps it with Windows' data protection
 * (`DpapiSecretVault`); `--ephemeral`, the renderer and a run anywhere but Windows keep it in
 * memory. Stage 2 hands one of these to the core as its `SecretStore`.
 */
interface SecretVault {
    suspend fun read(key: String): ByteArray?

    suspend fun write(data: ByteArray, key: String)

    suspend fun remove(key: String)
}

/** For tests and explicitly ephemeral runs. Never writes a credential to disk. */
class MemorySecretVault : SecretVault {
    private val lock = Mutex()
    private val values = mutableMapOf<String, ByteArray>()

    override suspend fun read(key: String): ByteArray? = lock.withLock { values[key]?.copyOf() }

    override suspend fun write(data: ByteArray, key: String) {
        lock.withLock { values[key] = data.copyOf() }
    }

    override suspend fun remove(key: String) {
        lock.withLock { values.remove(key) }
    }
}

/** The vault could not be read or written: the token is not stored, and the person signs in again. */
class SecureStorageUnavailable(cause: Throwable? = null) : Exception("Secure storage is unavailable.", cause)
