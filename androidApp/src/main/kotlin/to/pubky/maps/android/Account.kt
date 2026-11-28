package to.pubky.maps.android

import android.content.Context
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.android.AndroidSqliteDriver
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import to.pubky.maps.account.AccountController
import to.pubky.maps.account.AccountUi
import to.pubky.maps.account.AndroidPubkyRing
import to.pubky.maps.account.EncryptedFileSecretStore
import to.pubky.maps.account.SharedCore
import to.pubky.maps.account.WebPagePlaces
import to.pubky.maps.account.androidKeystoreKey
import to.pubky.maps.account.createUniffiPubkyCore
import to.pubky.maps.cache.SqlAccountCache
import to.pubky.maps.cache.db.CacheDatabase
import to.pubky.maps.status.ServiceHealth
import java.io.File

/** The Keystore key the secrets are encrypted with. */
private const val SECRETS_KEY_ALIAS = "pubky-maps-secrets"

/**
 * The account of this process: secrets (session, running sign-in) encrypted with a Keystore key,
 * the cache of the user's data in SQLite, both in the no-backup folder (they never leave the
 * phone in a backup), and the process's shared Rust [core], built off the main thread when
 * [AccountController.start] runs. A refresh leaves out the bookmarks and tags of web pages that
 * [webPages] says are not places.
 */
internal fun startAccount(
    context: Context,
    scope: CoroutineScope,
    health: ServiceHealth,
    core: SharedCore,
    webPages: WebPagePlaces,
): Pair<AccountUi, SqlDriver> {
    val key by lazy { androidKeystoreKey(SECRETS_KEY_ALIAS) }
    val secrets = EncryptedFileSecretStore(File(context.noBackupFilesDir, "secrets"), { key }, Dispatchers.IO)
    val driver = AndroidSqliteDriver(CacheDatabase.Schema, context, File(context.noBackupFilesDir, "account-cache.db").path)
    val cache = SqlAccountCache(driver)
    val controller =
        AccountController(
            createCore = { createUniffiPubkyCore(core::get) },
            secrets = secrets,
            cache = cache,
            health = health,
            scope = scope,
            io = Dispatchers.IO,
            now = System::currentTimeMillis,
            webPages = webPages,
        )
    controller.start(prepare = { importCiFixture(context, secrets, cache) })
    // The outbox of writes shares the cache's database, so sign-out clears both.
    return AccountUi(controller, AndroidPubkyRing(context), health) to driver
}
