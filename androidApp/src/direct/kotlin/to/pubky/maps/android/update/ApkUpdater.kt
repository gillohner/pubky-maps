package to.pubky.maps.android.update

import android.app.Activity
import android.app.Application
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import androidx.core.content.FileProvider
import androidx.core.net.toUri
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import to.pubky.maps.config.ServerConfigRepository
import to.pubky.maps.update.AvailableUpdate
import to.pubky.maps.update.CheckResult
import to.pubky.maps.update.InstalledBuild
import to.pubky.maps.update.UpdateChecker
import to.pubky.maps.update.UpdateController
import to.pubky.maps.update.UpdateDialog
import to.pubky.maps.update.UpdateFeed
import to.pubky.maps.update.UpdateStatus
import to.pubky.maps.update.UpdatesUiState
import to.pubky.maps.update.isAutoCheckDue
import to.pubky.maps.update.toStatus
import java.io.File
import java.io.IOException
import java.lang.ref.WeakReference

private const val TAG = "PubkyMapsUpdates"
private const val PREFS = "updates"
private const val KEY_AUTO_CHECK = "auto_check"
private const val KEY_LAST_CHECK = "last_check_ms"
private const val APK_MIME = "application/vnd.android.package-archive"

/**
 * The in-app updater of the APK published on GitHub. It checks the signed feed of the build's
 * channel (at start at most once a day, and on request), offers a newer build, downloads it into the
 * app's cache, checks its size and SHA-256 against the signed feed and its package and versionCode,
 * checks the file again, and hands it to Android's installer through a FileProvider. Android then
 * installs it only when it is signed with the same key as the installed app.
 *
 * Runs on the main thread; network and file work go to [Dispatchers.IO].
 */
class ApkUpdater(
    private val app: Application,
    private val installed: InstalledBuild,
    private val servers: ServerConfigRepository,
    private val checker: UpdateChecker,
) : UpdateController {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val updatesDir = File(app.cacheDir, "updates")

    private val mutableState =
        MutableStateFlow(
            UpdatesUiState(
                channel = installed.channel,
                autoCheck = prefs.getBoolean(KEY_AUTO_CHECK, true),
                status = UpdateStatus.NotChecked,
                dialog = null,
            ),
        )
    override val state: StateFlow<UpdatesUiState> = mutableState.asStateFlow()

    private var offered: UpdateFeed? = null
    private var verified: Pair<File, UpdateFeed>? = null
    private var checkJob: Job? = null
    private var downloadJob: Job? = null
    private var awaitingPermission = false
    private var handOffPending = false
    private var resumed: WeakReference<Activity> = WeakReference(null)

    init {
        app.registerActivityLifecycleCallbacks(ResumedActivityTracker())
    }

    /** At app start: removes finished downloads and runs the daily check when it is due. */
    fun onAppStart() {
        val auto = if (state.value.autoCheck) "on" else "off"
        Log.i(TAG, "updates: ${installed.versionName} (${installed.versionCode}) on ${installed.channel.id}; automatic checks $auto")
        scope.launch {
            withContext(Dispatchers.IO) {
                updatesDir.listFiles()?.forEach { it.delete() }
            }
        }
        val last = prefs.getLong(KEY_LAST_CHECK, 0L).takeIf { it > 0 }
        if (isAutoCheckDue(state.value.autoCheck, last, System.currentTimeMillis())) {
            check(automatic = true)
        }
    }

    override fun checkNow() = check(automatic = false)

    override fun setAutoCheck(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_AUTO_CHECK, enabled).apply()
        mutableState.update { it.copy(autoCheck = enabled) }
    }

    private fun check(automatic: Boolean) {
        if (checkJob?.isActive == true || downloadJob?.isActive == true) return
        mutableState.update { it.copy(status = UpdateStatus.Checking) }
        checkJob =
            scope.launch {
                val urls =
                    servers.config.value.updateFeeds
                        .forChannel(installed.channel)
                val result = withContext(Dispatchers.IO) { checker.check(urls, installed) }
                if (result != CheckResult.Unreachable) {
                    prefs.edit().putLong(KEY_LAST_CHECK, System.currentTimeMillis()).apply()
                }
                Log.i(TAG, "updates: ${if (automatic) "automatic" else "manual"} check: ${describe(result)}")
                offered = (result as? CheckResult.Available)?.feed
                val offer = offered?.let { UpdateDialog.Offer(AvailableUpdate.of(it), installed.versionName) }
                mutableState.update { it.copy(status = result.toStatus(), dialog = offer ?: it.dialog) }
            }
    }

    override fun install() {
        val feed = offered ?: return
        if (downloadJob?.isActive == true) return
        val update = AvailableUpdate.of(feed)
        verified?.let { (file, verifiedFeed) ->
            if (verifiedFeed == feed && file.exists()) {
                mutableState.update { it.copy(dialog = null) }
                handOff()
                return
            }
        }
        mutableState.update { it.copy(dialog = UpdateDialog.Downloading(update, 0)) }
        downloadJob =
            scope.launch {
                val problem = download(feed, update)
                if (problem != null) {
                    Log.w(TAG, "updates: not installed: $problem")
                    mutableState.update { it.copy(dialog = UpdateDialog.NotInstalled(problem)) }
                } else {
                    mutableState.update { it.copy(dialog = null) }
                    handOff()
                }
            }
    }

    /** Downloads and checks [feed]'s APK; null when it is ready for the installer, else why not. */
    private suspend fun download(
        feed: UpdateFeed,
        update: AvailableUpdate,
    ): String? {
        val file = File(updatesDir, "pubky-maps-${feed.versionCode}.apk")
        val part = File(updatesDir, "${file.name}.part")
        verified = null
        return try {
            withContext(Dispatchers.IO) {
                updatesDir.mkdirs()
                updatesDir.listFiles()?.forEach { it.delete() }
            }
            runInterruptible(Dispatchers.IO) {
                downloadTo(feed.apk.url, part, feed.apk.size) { bytes ->
                    mutableState.update { state ->
                        val dialog = state.dialog
                        if (dialog is UpdateDialog.Downloading) state.copy(dialog = dialog.copy(downloadedBytes = bytes)) else state
                    }
                }
            }
            val problem = withContext(Dispatchers.IO) { checkApk(part, feed) }
            if (problem == null) {
                withContext(Dispatchers.IO) { part.renameTo(file) }
                verified = file to feed
                Log.i(TAG, "updates: ${file.name} matches the signed feed (size, SHA-256, package, versionCode)")
            } else {
                withContext(Dispatchers.IO) { part.delete() }
            }
            problem
        } catch (e: IOException) {
            withContext(Dispatchers.IO) { part.delete() }
            Log.w(TAG, "updates: download failed", e)
            "The download failed (${update.versionName}). Check your connection and try again."
        }
    }

    /** The file is the one the signed feed names, and Android reads it as the right package and version. */
    private fun checkApk(
        file: File,
        feed: UpdateFeed,
    ): String? {
        checkDownload(file, feed.apk.size, feed.apk.sha256)?.let { return it }
        val info = app.packageManager.getPackageArchiveInfo(file.path, 0)

        @Suppress("DEPRECATION")
        val code = info?.let { if (Build.VERSION.SDK_INT >= 28) it.longVersionCode else it.versionCode.toLong() }
        if (info?.packageName != installed.appId || code != feed.versionCode) {
            return "The downloaded file is not Pubky Maps ${feed.versionName}. Nothing was installed."
        }
        return null
    }

    /**
     * Hands the checked APK to Android's installer, from the activity in front. Asks for "Install unknown
     * apps" first when it is off, and checks the file once more right before.
     */
    private fun handOff() {
        val (file, feed) = verified ?: return
        val activity = resumed.get()
        if (activity == null) {
            // The app is in the background: the installer opens when it comes back.
            handOffPending = true
            return
        }
        if (!app.packageManager.canRequestPackageInstalls()) {
            mutableState.update { it.copy(dialog = UpdateDialog.NeedsPermission(AvailableUpdate.of(feed))) }
            return
        }
        scope.launch {
            val problem = withContext(Dispatchers.IO) { if (file.exists()) checkApk(file, feed) else "The download is gone." }
            if (problem != null) {
                verified = null
                withContext(Dispatchers.IO) { file.delete() }
                Log.w(TAG, "updates: not installed: $problem")
                mutableState.update { it.copy(dialog = UpdateDialog.NotInstalled(problem)) }
                return@launch
            }
            val uri = FileProvider.getUriForFile(app, "${app.packageName}.updates", file)
            val intent =
                Intent(Intent.ACTION_VIEW)
                    .setDataAndType(uri, APK_MIME)
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            Log.i(TAG, "updates: ${file.name} checked again; handed to the installer")
            activity.startActivity(intent)
        }
    }

    override fun openInstallPermissionSettings() {
        val activity = resumed.get() ?: return
        awaitingPermission = true
        mutableState.update { it.copy(dialog = null) }
        activity.startActivity(
            Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, "package:${app.packageName}".toUri()),
        )
    }

    override fun cancelDownload() {
        downloadJob?.cancel()
        mutableState.update { it.copy(dialog = null) }
    }

    override fun dismissDialog() {
        if (state.value.dialog is UpdateDialog.Downloading) return
        awaitingPermission = false
        mutableState.update { it.copy(dialog = null) }
    }

    private fun onResumed(activity: Activity) {
        resumed = WeakReference(activity)
        if (awaitingPermission || handOffPending) {
            awaitingPermission = false
            handOffPending = false
            handOff()
        }
    }

    private inner class ResumedActivityTracker : Application.ActivityLifecycleCallbacks {
        override fun onActivityResumed(activity: Activity) = onResumed(activity)

        override fun onActivityPaused(activity: Activity) {
            if (resumed.get() === activity) resumed = WeakReference(null)
        }

        override fun onActivityCreated(
            activity: Activity,
            savedInstanceState: Bundle?,
        ) = Unit

        override fun onActivityStarted(activity: Activity) = Unit

        override fun onActivityStopped(activity: Activity) = Unit

        override fun onActivitySaveInstanceState(
            activity: Activity,
            outState: Bundle,
        ) = Unit

        override fun onActivityDestroyed(activity: Activity) = Unit
    }

    private fun describe(result: CheckResult): String =
        when (result) {
            is CheckResult.Available -> "${result.feed.versionName} (${result.feed.versionCode}) is available"
            is CheckResult.UpToDate -> "this is the newest version (feed: ${result.newestVersionName})"
            is CheckResult.NeedsNewerAndroid -> "${result.feed.versionName} needs API ${result.feed.minSdk}"
            is CheckResult.Refused -> "refused: ${result.problem.name}"
            CheckResult.Unreachable -> "no feed server could be reached"
        }
}
