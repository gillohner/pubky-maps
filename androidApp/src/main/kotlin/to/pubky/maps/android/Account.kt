package to.pubky.maps.android

import android.content.Context
import app.cash.sqldelight.driver.android.AndroidSqliteDriver
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import to.pubky.maps.account.AccountController
import to.pubky.maps.account.AccountUi
import to.pubky.maps.account.AndroidPubkyRing
import to.pubky.maps.account.CoreServers
import to.pubky.maps.account.EncryptedFileSecretStore
import to.pubky.maps.account.androidKeystoreKey
import to.pubky.maps.account.createUniffiPubkyCore
import to.pubky.maps.cache.SqlAccountCache
import to.pubky.maps.cache.db.CacheDatabase
import to.pubky.maps.config.Service
import to.pubky.maps.config.SettingsRepository
import to.pubky.maps.status.ServiceHealth
import java.io.File

/** The Keystore key the secrets are encrypted with. */
private const val SECRETS_KEY_ALIAS = "pubky-maps-secrets"

/**
 * The account of this process: secrets (session, running sign-in) encrypted with a Keystore key,
 * the cache of the user's data in SQLite, both in the no-backup folder (they never leave the
 * phone in a backup), and the Rust core, built off the main thread when [AccountController.start]
 * runs, with the Pkarr relays and sign-in relays of the user's server lists (a change applies from
 * the next start).
 */
internal fun startAccount(
    context: Context,
    scope: CoroutineScope,
    health: ServiceHealth,
    settings: SettingsRepository,
): AccountUi {
    val key by lazy { androidKeystoreKey(SECRETS_KEY_ALIAS) }
    val secrets = EncryptedFileSecretStore(File(context.noBackupFilesDir, "secrets"), { key }, Dispatchers.IO)
    val driver = AndroidSqliteDriver(CacheDatabase.Schema, context, File(context.noBackupFilesDir, "account-cache.db").path)
    val cache = SqlAccountCache(driver)
    val controller =
        AccountController(
            createCore = {
                val servers =
                    settings.settings
                        .filterNotNull()
                        .first()
                        .servers
                createUniffiPubkyCore(
                    CoreServers(
                        pkarrRelays = servers[Service.PkarrRelay].map { it.url },
                        authRelays = servers[Service.AuthRelay].map { it.url },
                    ),
                )
            },
            secrets = secrets,
            cache = cache,
            health = health,
            scope = scope,
            io = Dispatchers.IO,
            now = System::currentTimeMillis,
        )
    controller.start(prepare = { importCiFixture(context, secrets, cache) })
    return AccountUi(controller, AndroidPubkyRing(context), health)
}
