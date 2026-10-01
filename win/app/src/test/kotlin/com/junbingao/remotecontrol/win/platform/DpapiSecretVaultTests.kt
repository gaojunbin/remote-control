package com.junbingao.remotecontrol.win.platform

import com.junbingao.remotecontrol.core.transport.TransportError
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.condition.EnabledOnOs
import org.junit.jupiter.api.condition.OS
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The token's vault on Windows. Its files are checked everywhere with a stand-in seal; the seal
 * itself is Windows' and is checked on Windows, where CI runs the same suite.
 */
class DpapiSecretVaultTests {
    /** Reverses the bytes and marks them, so a file holds nothing readable as the token. */
    private object FakeSeal : DpapiSecretVault.Protector {
        override fun protect(data: ByteArray) = byteArrayOf(0x7F) + data.reversedArray()

        override fun unprotect(data: ByteArray): ByteArray {
            require(data.firstOrNull() == 0x7F.toByte()) { "not sealed here" }
            return data.drop(1).reversed().toByteArray()
        }
    }

    @Test
    fun theDpapiVaultKeepsOneSealedFilePerKey(@TempDir directory: Path) = runTest {
        val vault = DpapiSecretVault(directory.resolve("secrets"), FakeSeal)
        assertNull(vault.read("token"))
        vault.write("first".toByteArray(), "token")
        vault.write("second".toByteArray(), "token")
        assertContentEquals("second".toByteArray(), vault.read("token"))
        val file = vault.file("token")
        assertEquals("token.secret", file.fileName.toString())
        assertFalse(Files.readAllBytes(file).decodeToString().contains("second"), "the file holds the sealed bytes")
        assertEquals(listOf("token.secret"), Files.list(directory.resolve("secrets")).map { it.fileName.toString() }.toList())
        vault.remove("token")
        assertNull(vault.read("token"))
        vault.remove("token")
    }

    @Test
    fun anyKeyIsAFileName(@TempDir directory: Path) {
        val vault = DpapiSecretVault(directory, FakeSeal)
        assertEquals("gateway.token.secret", vault.file("gateway.token").fileName.toString())
        assertTrue(vault.file("a/b:c").fileName.toString().startsWith("x-"))
        assertTrue(vault.file("..").fileName.toString().startsWith("x-"))
    }

    @Test
    fun aFileSealedElsewhereReadsAsUnavailable(@TempDir directory: Path) = runTest {
        val vault = DpapiSecretVault(directory, FakeSeal)
        Files.write(vault.file("token"), "plain".toByteArray())
        assertEquals(TransportError.SecureStorageUnavailable, assertFailsWith<TransportError> { vault.read("token") })
    }

    @Test
    @EnabledOnOs(OS.WINDOWS)
    fun windowsDataProtectionRoundTrips(@TempDir directory: Path) = runTest {
        val vault = DpapiSecretVault(directory)
        vault.write("a token".toByteArray(), "token")
        assertContentEquals("a token".toByteArray(), vault.read("token"))
        assertFalse(Files.readAllBytes(vault.file("token")).decodeToString().contains("a token"))
    }
}
