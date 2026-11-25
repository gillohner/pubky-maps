package to.pubky.maps.account

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import java.io.File
import java.security.GeneralSecurityException
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

private const val TRANSFORMATION = "AES/GCM/NoPadding"
private const val IV_BYTES = 12
private const val TAG_BITS = 128

/**
 * [SecretStore] as one file per secret in [dir], each AES-256-GCM encrypted with [key] (on Android a
 * Keystore key that never leaves the secure hardware or the Keystore process, see
 * [androidKeystoreKey]). A file holds the 12-byte IV and the ciphertext; the secret's name is the
 * associated data, so a file copied to another name does not decrypt. A file that does not decrypt
 * (a lost or replaced key, a damaged file) reads as missing and is deleted.
 */
class EncryptedFileSecretStore(
    private val dir: File,
    private val key: () -> SecretKey,
    private val io: CoroutineDispatcher,
) : SecretStore {
    override suspend fun read(name: String): String? =
        withContext(io) {
            val file = fileOf(name)
            if (!file.exists()) return@withContext null
            try {
                val bytes = file.readBytes()
                if (bytes.size <= IV_BYTES) throw GeneralSecurityException("too short")
                val cipher = Cipher.getInstance(TRANSFORMATION)
                cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(TAG_BITS, bytes, 0, IV_BYTES))
                cipher.updateAAD(name.encodeToByteArray())
                cipher.doFinal(bytes, IV_BYTES, bytes.size - IV_BYTES).decodeToString()
            } catch (_: GeneralSecurityException) {
                file.delete()
                null
            }
        }

    override suspend fun write(
        name: String,
        value: String,
    ) = withContext(io) {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        // The cipher picks a fresh random IV (Keystore keys do not accept one from outside).
        cipher.init(Cipher.ENCRYPT_MODE, key())
        cipher.updateAAD(name.encodeToByteArray())
        val sealed = cipher.doFinal(value.encodeToByteArray())
        val iv = cipher.iv
        check(iv.size == IV_BYTES) { "unexpected IV size ${iv.size}" }
        dir.mkdirs()
        val part = File(dir, "$name.part")
        part.writeBytes(iv + sealed)
        if (!part.renameTo(fileOf(name))) {
            part.delete()
            throw java.io.IOException("could not store the secret $name")
        }
    }

    override suspend fun delete(name: String) {
        withContext(io) { fileOf(name).delete() }
    }

    private fun fileOf(name: String): File {
        require(name.matches(Regex("[a-z-]+"))) { "bad secret name" }
        return File(dir, "$name.bin")
    }
}

/**
 * The app's AES-256 key in the Android Keystore, made on first use. It cannot be exported, and it
 * needs no screen lock, so a session can be restored when the app starts.
 */
fun androidKeystoreKey(alias: String): SecretKey {
    val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
    (keyStore.getKey(alias, null) as? SecretKey)?.let { return it }
    val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
    generator.init(
        KeyGenParameterSpec
            .Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .build(),
    )
    return generator.generateKey()
}
