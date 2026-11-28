package to.pubky.maps.write

import android.content.Context
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.android.AndroidSqliteDriver
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.StateFlow
import to.pubky.maps.account.AccountController
import to.pubky.maps.account.SharedCore
import to.pubky.maps.account.WebPagePlaces
import to.pubky.maps.config.AppSettings
import to.pubky.maps.write.db.PhoneDataDatabase
import java.io.File
import java.io.IOException
import java.util.UUID

/**
 * The user's writes for the app process: the outbox and lists in the account cache's database
 * ([accountDriver], deleted at sign-out), what is kept on the phone only in its own database, and
 * photos waiting to be sent in the no-backup folder. Create it once per process. Collection items on
 * web pages that [webPages] says are not places show as other links.
 */
fun androidWriteController(
    context: Context,
    scope: CoroutineScope,
    account: AccountController,
    accountDriver: SqlDriver,
    core: SharedCore,
    settings: StateFlow<AppSettings?>,
    webPages: WebPagePlaces,
): WriteController {
    val phoneDriver =
        AndroidSqliteDriver(PhoneDataDatabase.Schema, context, File(context.noBackupFilesDir, "phone-data.db").path)
    return WriteController(
        writes = { UniffiPubkyWrites(core.get()) },
        store = SqlWriteStore(accountDriver),
        phone = SqlPhoneStore(phoneDriver),
        files = DirectoryOutboxFiles(File(context.noBackupFilesDir, "outbox-photos")),
        account = account.state,
        updateAccount = account::updateCached,
        refreshAccount = account::refresh,
        settings = { settings.value ?: AppSettings() },
        scope = scope,
        io = Dispatchers.IO,
        now = System::currentTimeMillis,
        webPages = webPages,
    )
}

/** [OutboxFiles] in one folder, one file per queued photo (the same photo queued twice is two files). */
class DirectoryOutboxFiles(
    private val dir: File,
) : OutboxFiles {
    override fun write(bytes: ByteArray): String {
        dir.mkdirs()
        val name = "${System.currentTimeMillis()}-${UUID.randomUUID()}"
        val partial = File(dir, "$name.part")
        val file = File(dir, "$name.photo")
        partial.writeBytes(bytes)
        if (!partial.renameTo(file)) throw IOException("the photo could not be stored on this phone")
        return file.path
    }

    override fun read(path: String): ByteArray? = File(path).takeIf { it.parentFile == dir && it.isFile }?.readBytes()

    override fun delete(path: String) {
        File(path).takeIf { it.parentFile == dir }?.delete()
    }

    override fun deleteAllBut(keep: Set<String>) {
        dir.listFiles()?.filterNot { it.path in keep }?.forEach { it.delete() }
    }
}
