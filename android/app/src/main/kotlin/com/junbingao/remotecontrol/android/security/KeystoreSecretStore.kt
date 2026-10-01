package com.junbingao.remotecontrol.android.security

import android.annotation.SuppressLint
import android.content.Context
import android.content.SharedPreferences
import android.util.Base64
import com.junbingao.remotecontrol.core.persistence.SecretStore
import com.junbingao.remotecontrol.core.transport.TransportError
import java.security.GeneralSecurityException
import java.security.KeyStoreException
import java.security.UnrecoverableKeyException
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * The gateway bearer token's home on Android: the core's [SecretStore], as `KeychainSecretStore`
 * (`ios/Sources/RCCore/Persistence/SecretStore.swift`) is RCCore's, failing as it fails — with
 * [TransportError.SecureStorageUnavailable], which the core words as the keychain's sentence and
 * the app says in Android's (`L10n.platform`).
 *
 * Each value is sealed with AES-256-GCM under a key the Android Keystore generates and never
 * lets out, usable only while the phone is unlocked — the counterpart of the Keychain's
 * `kSecAttrAccessibleWhenUnlockedThisDeviceOnly`. Only the sealed bytes and their nonce reach
 * the app's private storage, and nothing is backed up (`android:allowBackup="false"`), so a
 * token never leaves this phone. The entry's own name is bound into the seal, so a value copied
 * under another name does not open.
 */
class KeystoreSecretStore internal constructor(
    private val storage: SharedPreferences,
    private val keys: SecretKeySource,
) : SecretStore {
    constructor(context: Context) : this(
        context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE),
        AndroidKeystoreKeySource(ALIAS),
    )

    /** One read or write at a time: the key is generated once, and a write is not interleaved. */
    private val lock = Mutex()

    override suspend fun read(key: String): ByteArray? = access {
        val stored = storage.getString(entry(key), null) ?: return@access null
        // An entry that is not what this store writes can never be opened; it is no token.
        val sealed = try {
            Base64.decode(stored, Base64.NO_WRAP)
        } catch (_: IllegalArgumentException) {
            return@access forget(key)
        }
        if (sealed.size <= NONCE_BYTES) return@access forget(key)
        try {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, keys.key(), GCMParameterSpec(TAG_BITS, sealed, 0, NONCE_BYTES))
            cipher.updateAAD(key.toByteArray(Charsets.UTF_8))
            cipher.doFinal(sealed, NONCE_BYTES, sealed.size - NONCE_BYTES)
        } catch (_: AEADBadTagException) {
            // Sealed under a key that is gone — the Keystore was reset, or the app's data was
            // restored onto another phone. It can never be opened again, so it is no token.
            forget(key)
        } catch (_: UnrecoverableKeyException) {
            forget(key)
        }
    }

    override suspend fun write(data: ByteArray, key: String) {
        access {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.ENCRYPT_MODE, keys.key())
            cipher.updateAAD(key.toByteArray(Charsets.UTF_8))
            val sealed = cipher.iv + cipher.doFinal(data)
            if (!commit { putString(entry(key), Base64.encodeToString(sealed, Base64.NO_WRAP)) }) {
                throw TransportError.SecureStorageUnavailable
            }
        }
    }

    override suspend fun remove(key: String) {
        access {
            if (!commit { remove(entry(key)) }) throw TransportError.SecureStorageUnavailable
        }
    }

    private fun forget(key: String): ByteArray? {
        commit { remove(entry(key)) }
        return null
    }

    /**
     * Written through before returning, and the answer checked: a token the store says it holds
     * and does not is a sign-in lost on the next launch, which `apply()` could not report.
     */
    @SuppressLint("ApplySharedPref", "UseKtx")
    private fun commit(change: SharedPreferences.Editor.() -> Unit): Boolean = storage.edit().apply(change).commit()

    /**
     * Every failure the Keystore can raise reads as the one sentence the Keychain's did: the
     * phone is locked, or its secure hardware is not answering.
     */
    private suspend fun <T> access(work: () -> T): T = withContext(Dispatchers.IO) {
        lock.withLock {
            try {
                work()
            } catch (_: GeneralSecurityException) {
                throw TransportError.SecureStorageUnavailable
            } catch (_: KeyStoreException) {
                throw TransportError.SecureStorageUnavailable
            } catch (_: IllegalStateException) {
                throw TransportError.SecureStorageUnavailable
            } catch (_: IllegalArgumentException) {
                throw TransportError.SecureStorageUnavailable
            }
        }
    }

    private fun entry(key: String) = "secret.$key"

    companion object {
        /** The Keychain service the iPhone files the token under, as the key's alias here. */
        const val ALIAS = "com.junbingao.remotecontrol.gateway"
        private const val FILE = "secrets"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val NONCE_BYTES = 12
        private const val TAG_BITS = 128
    }
}
