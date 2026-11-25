package to.pubky.maps.android.update

import android.app.Application
import android.os.Build
import to.pubky.maps.BuildConfig
import to.pubky.maps.config.ServerConfigRepository
import to.pubky.maps.update.InstalledBuild
import to.pubky.maps.update.UpdateChannel
import to.pubky.maps.update.UpdateChecker
import to.pubky.maps.update.UpdateController
import to.pubky.maps.update.decodePublicKey

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
    if (BuildConfig.UPDATE_FEED_URL.isNotEmpty()) {
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
        )
    return ApkUpdater(app, installed, servers, checker).also { it.onAppStart() }
}
