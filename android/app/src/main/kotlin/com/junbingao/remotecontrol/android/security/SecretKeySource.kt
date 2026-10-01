package com.junbingao.remotecontrol.android.security

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyStore
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey

/** Where [KeystoreSecretStore] gets the key it seals with. */
internal fun interface SecretKeySource {
    fun key(): SecretKey
}

/**
 * The Android Keystore's key under one alias, made the first time it is asked for. The key
 * material never leaves the Keystore: the store holds a handle, and the Keystore does the
 * sealing.
 */
internal class AndroidKeystoreKeySource(private val alias: String) : SecretKeySource {
    override fun key(): SecretKey {
        val keystore = KeyStore.getInstance(PROVIDER).apply { load(null) }
        (keystore.getKey(alias, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, PROVIDER)
        generator.init(
            KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .setRandomizedEncryptionRequired(true)
                // Readable only while the phone is unlocked, as the Keychain item is.
                .setUnlockedDeviceRequired(true)
                .build(),
        )
        return generator.generateKey()
    }

    private companion object {
        const val PROVIDER = "AndroidKeyStore"
    }
}
