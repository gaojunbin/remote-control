package com.junbingao.remotecontrol.android.security

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import javax.crypto.KeyGenerator

/**
 * The store's sealing and filing, with a software key standing in for the Keystore's: the JVM
 * has no Android Keystore provider, so the key's own home — generated in secure hardware, usable
 * only while the phone is unlocked — is checked on a phone, not here.
 */
@RunWith(AndroidJUnit4::class)
class KeystoreSecretStoreTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val storage = context.getSharedPreferences("secrets-test", Context.MODE_PRIVATE).also { it.edit().clear().commit() }
    private val key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
    private val store = KeystoreSecretStore(storage) { key }

    @Test
    fun aWrittenSecretReadsBack() = runTest {
        store.write("token-1".toByteArray(), "gateway.token")
        assertArrayEquals("token-1".toByteArray(), store.read("gateway.token"))
    }

    @Test
    fun aSecretThatWasNeverWrittenIsNone() = runTest {
        assertNull(store.read("absent"))
    }

    @Test
    fun whatReachesStorageIsNotTheSecret() = runTest {
        store.write("bearer-abc".toByteArray(), "gateway.token")
        val stored = storage.all.values.single() as String
        assertNotEquals(-1, stored.length)
        assertEquals(false, stored.contains("bearer-abc"))
    }

    @Test
    fun aSecondWriteReplacesTheFirst() = runTest {
        store.write("old".toByteArray(), "k")
        store.write("new".toByteArray(), "k")
        assertArrayEquals("new".toByteArray(), store.read("k"))
    }

    @Test
    fun aRemovedSecretIsGone() = runTest {
        store.write("x".toByteArray(), "k")
        store.remove("k")
        assertNull(store.read("k"))
    }

    @Test
    fun aValueFiledUnderAnotherNameDoesNotOpen() = runTest {
        store.write("secret".toByteArray(), "a")
        val sealed = storage.getString("secret.a", null)
        storage.edit().putString("secret.b", sealed).commit()
        assertNull(store.read("b"))
        assertNull(storage.getString("secret.b", null))
    }

    @Test
    fun aValueSealedUnderAKeyThatIsGoneIsForgotten() = runTest {
        store.write("secret".toByteArray(), "a")
        val other = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
        val reset = KeystoreSecretStore(storage) { other }
        assertNull(reset.read("a"))
        assertNull(storage.getString("secret.a", null))
    }

    @Test
    fun anEntryThisStoreDidNotWriteIsForgotten() = runTest {
        storage.edit().putString("secret.k", "not base64 !!!").commit()
        assertNull(store.read("k"))
        assertNull(storage.getString("secret.k", null))
    }

    @Test
    fun aKeystoreThatDoesNotAnswerIsTheOneSentence() = runTest {
        val broken = KeystoreSecretStore(storage) { throw java.security.KeyStoreException("locked") }
        try {
            broken.write("x".toByteArray(), "k")
            fail("expected SecureStorageUnavailable")
        } catch (failure: SecureStorageUnavailable) {
            assertEquals("Could not reach the Android Keystore. Unlock this device and try again.", failure.message)
        }
    }
}
