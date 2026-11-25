package to.pubky.maps.account

/**
 * Small secrets kept on this phone only, encrypted with a key the user's device holds (Android:
 * Keystore). Values are never logged.
 */
interface SecretStore {
    suspend fun read(name: String): String?

    suspend fun write(
        name: String,
        value: String,
    )

    suspend fun delete(name: String)

    companion object {
        /** The session's credential, while signed in. */
        const val SESSION = "session"

        /** A running sign-in's state, only while it runs. */
        const val PENDING_SIGN_IN = "pending-sign-in"
    }
}
