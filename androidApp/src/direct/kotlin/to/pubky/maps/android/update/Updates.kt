package to.pubky.maps.android.update

import android.app.Activity
import android.app.Application
import android.content.Intent
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import androidx.core.net.toUri
import to.pubky.maps.BuildConfig
import to.pubky.maps.config.ServerConfigRepository
import to.pubky.maps.update.InstalledBuild
import to.pubky.maps.update.UpdateChannel
import to.pubky.maps.update.UpdateChecker
import to.pubky.maps.update.UpdateController
import to.pubky.maps.update.decodePublicKey
import java.io.File

private const val APK_MIME = "application/vnd.android.package-archive"

/**
 * The in-app updater of this build, started; null for a build without an update channel (local
 * builds, which are not signed with the release key and could not install a release anyway).
 */
fun startUpdater(
    app: Application,
    servers: ServerConfigRepository,
): UpdateController? {
    val channel = UpdateChannel.fromId(BuildConfig.UPDATE_CHANNEL) ?: return null
    // The update probe's build reads its feed from the phone's localhost (scripts/ci/update-probe.py).
    val probe = BuildConfig.UPDATE_FEED_URL.isNotEmpty()
    if (probe) {
        servers.update { it.copy(updateFeeds = it.updateFeeds.withChannel(channel, listOf(BuildConfig.UPDATE_FEED_URL))) }
    }
    val installed =
        InstalledBuild(
            appId = app.packageName,
            versionCode = BuildConfig.VERSION_CODE.toLong(),
            versionName = BuildConfig.VERSION_NAME,
            sdkInt = Build.VERSION.SDK_INT,
            channel = channel,
        )
    val checker =
        UpdateChecker(
            fetch = ::fetchFeed,
            trustedKeys = listOf(decodePublicKey(BuildConfig.UPDATE_PUBLIC_KEY)),
            verifier = Ed25519Verifier,
            allowLocalHttp = probe,
        )
    return ApkUpdater(app, installed, servers, checker, AndroidUpdatePlatform(app)).also { it.onAppStart() }
}

/** [UpdatePlatform] on Android: HTTP downloads, the package manager, the system installer. */
class AndroidUpdatePlatform(
    private val app: Application,
) : UpdatePlatform {
    override fun download(
        url: String,
        target: File,
        expectedSize: Long,
        onProgress: (Long) -> Unit,
    ): Long = downloadTo(url, target, expectedSize, onProgress)

    override fun readArchive(file: File): ArchiveFacts? {
        val info = app.packageManager.getPackageArchiveInfo(file.path, 0) ?: return null

        @Suppress("DEPRECATION")
        val code = if (Build.VERSION.SDK_INT >= 28) info.longVersionCode else info.versionCode.toLong()
        return ArchiveFacts(info.packageName, code)
    }

    override fun canInstallPackages(): Boolean = app.packageManager.canRequestPackageInstalls()

    override fun launchInstaller(
        activity: Activity,
        file: File,
    ) {
        val uri = FileProvider.getUriForFile(app, "${app.packageName}.updates", file)
        activity.startActivity(
            Intent(Intent.ACTION_VIEW)
                .setDataAndType(uri, APK_MIME)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION),
        )
    }

    override fun openInstallPermissionSettings(activity: Activity) {
        activity.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, "package:${app.packageName}".toUri()))
    }

    override fun now(): Long = System.currentTimeMillis()
}
