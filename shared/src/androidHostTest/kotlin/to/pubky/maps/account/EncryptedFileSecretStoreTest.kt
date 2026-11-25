package to.pubky.maps.account

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import java.io.File
import java.nio.file.Files
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The store with a software AES key (the app's key lives in the Android Keystore). */
class EncryptedFileSecretStoreTest {
    private val dir: File = Files.createTempDirectory("secrets").toFile()
    private val key: SecretKey = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
    private val store = EncryptedFileSecretStore(dir, { key }, Dispatchers.IO)
    private val secret = "pubky-grant-credential-v1:homeserver:client-key:header.payload.signature"

    @AfterTest
    fun cleanUp() {
        dir.deleteRecursively()
    }

    @Test
    fun aSecretReadsBackAndIsNotStoredInPlainText() =
        runBlocking<Unit> {
            store.write(SecretStore.SESSION, secret)

            assertEquals(secret, store.read(SecretStore.SESSION))
            val files = dir.listFiles()!!.toList()
            assertEquals(listOf("session.bin"), files.map { it.name })
            val bytes = files.single().readBytes()
            val text = bytes.decodeToString()
            assertFalse(text.contains("pubky-grant"), "no plain text")
            assertFalse(text.contains("client-key"))
            assertFalse(bytes.toList().windowed(8).contains(secret.encodeToByteArray().take(8)))
            // A new IV each time: the same secret never gives the same file.
            store.write(SecretStore.SESSION, secret)
            assertFalse(bytes.contentEquals(files.single().readBytes()))
        }

    @Test
    fun aFileCopiedToAnotherNameOrChangedDoesNotDecryptAndIsDeleted() =
        runBlocking<Unit> {
            store.write(SecretStore.SESSION, secret)
            File(dir, "session.bin").copyTo(File(dir, "pending-sign-in.bin"))
            assertNull(store.read(SecretStore.PENDING_SIGN_IN))
            assertFalse(File(dir, "pending-sign-in.bin").exists())

            val file = File(dir, "session.bin")
            val bytes = file.readBytes()
            bytes[bytes.size - 1] = (bytes.last() + 1).toByte()
            file.writeBytes(bytes)
            assertNull(store.read(SecretStore.SESSION))
            assertFalse(file.exists())
        }

    @Test
    fun aLostKeyReadsAsNoSecret() =
        runBlocking<Unit> {
            store.write(SecretStore.SESSION, secret)
            val otherKey = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()

            assertNull(EncryptedFileSecretStore(dir, { otherKey }, Dispatchers.IO).read(SecretStore.SESSION))
        }

    @Test
    fun deleteRemovesTheFileAndMissingSecretsAreNull() =
        runBlocking<Unit> {
            assertNull(store.read(SecretStore.SESSION))
            store.write(SecretStore.PENDING_SIGN_IN, "state")
            assertTrue(File(dir, "pending-sign-in.bin").exists())

            store.delete(SecretStore.PENDING_SIGN_IN)

            assertNull(store.read(SecretStore.PENDING_SIGN_IN))
            assertTrue(dir.listFiles()!!.isEmpty())
        }
}
