package com.junbingao.remotecontrol.core.persistence

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Where the gateway bearer token is kept. RCCore's is the keychain; each app brings its own
 * platform store — the Android Keystore, Windows DPAPI — and the core only ever sees this.
 */
interface SecretStore {
    suspend fun read(key: String): ByteArray?
    suspend fun write(data: ByteArray, key: String)
    suspend fun remove(key: String)
}

/** For tests and explicitly ephemeral sessions. Never writes credentials to disk. */
class MemorySecretStore : SecretStore {
    private val lock = Mutex()
    private val values = mutableMapOf<String, ByteArray>()

    override suspend fun read(key: String): ByteArray? = lock.withLock { values[key]?.copyOf() }

    override suspend fun write(data: ByteArray, key: String) = lock.withLock { values[key] = data.copyOf() }

    override suspend fun remove(key: String) {
        lock.withLock { values.remove(key) }
    }
}
