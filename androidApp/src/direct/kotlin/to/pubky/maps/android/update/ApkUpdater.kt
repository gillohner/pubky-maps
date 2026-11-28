package to.pubky.maps.android.update

import android.app.Activity
import android.app.Application
import android.content.ActivityNotFoundException
import android.content.Context
import android.os.Bundle
import android.util.Log
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
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
private const val KEY_PENDING = "pending_version_code"
private const val KEY_PROMPTED = "prompted_version_code"
private const val KEY_FOUND = "found_feed"

/** What Android reads from an APK file: its package and versionCode. */
data class ArchiveFacts(
    val packageName: String,
    val versionCode: Long,
)

/** What the updater needs from Android, replaceable in tests. */
interface UpdatePlatform {
    /** Downloads [url] to [target] (blocking, interruptible); see [downloadTo]. */
    fun download(
        url: String,
        target: File,
        expectedSize: Long,
        onProgress: (Long) -> Unit,
    ): Long

    fun readArchive(file: File): ArchiveFacts?

    /** Whether "Install unknown apps" is allowed for the app. */
    fun canInstallPackages(): Boolean

    /** Opens Android's installer for [file] from [activity]. */
    fun launchInstaller(
        activity: Activity,
        file: File,
    )

    /** Opens the "Install unknown apps" setting of the app. */
    fun openInstallPermissionSettings(activity: Activity)

    fun now(): Long
}

/**
 * The in-app updater of the APK published on GitHub. It checks the signed feed of the build's channel
 * (at start at most once a day, and on request), offers a newer build (in a dialog after a check the
 * user asked for or one interrupted by "Install unknown apps"; after the daily check on the map, once
 * per version, see [UpdatesUiState.prompt]), keeps the signed feed of a found update so it stays
 * ready after a restart (read again with [UpdateChecker.reread] at start, then confirmed by a check) and
 * while no server gives a valid answer ([UpdatesUiState.ready]), downloads it into the app's
 * cache, checks its size and SHA-256 against the signed feed and its package and versionCode, checks
 * the file again right before, and hands it to Android's installer. Android then installs it only
 * when it is signed with the same key as the installed app.
 *
 * Runs on [main]; network, file and preference work go to [io]. Every failure ends in a state the
 * user can see; nothing here can crash the app.
 */
class ApkUpdater(
    private val app: Application,
    private val installed: InstalledBuild,
    private val servers: ServerConfigRepository,
    private val checker: UpdateChecker,
    private val platform: UpdatePlatform,
    private val main: CoroutineDispatcher = Dispatchers.Main.immediate,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) : UpdateController {
    private val failures =
        CoroutineExceptionHandler { _, e ->
            Log.e(TAG, "updates: failed", e)
            mutableState.update {
                it.copy(status = UpdateStatus.Failed("Something went wrong. Try again later."), dialog = null)
            }
        }
    private val scope = CoroutineScope(SupervisorJob() + main + failures)
    private val prefs by lazy { app.getSharedPreferences(PREFS, Context.MODE_PRIVATE) }
    private val updatesDir = File(app.cacheDir, "updates")

    private val mutableState =
        MutableStateFlow(
            UpdatesUiState(channel = installed.channel, autoCheck = true, status = UpdateStatus.NotChecked, dialog = null),
        )
    override val state: StateFlow<UpdatesUiState> = mutableState.asStateFlow()

    private var offered: UpdateFeed? = null

    /** The update the last check that reached a server found (or one kept from before a restart). */
    private var found: CheckResult.Available? = null

    /** The versionCode the map last offered; read at app start. */
    private var prompted = 0L
    private var verified: Pair<File, UpdateFeed>? = null
    private var checkJob: Job? = null
    private var downloadJob: Job? = null
    private var awaitingPermission = false
    private var handOffPending = false
    private var resumed: WeakReference<Activity> = WeakReference(null)

    init {
        app.registerActivityLifecycleCallbacks(ResumedActivityTracker())
    }

    /**
     * At app start: removes old downloads, offers the update a check found before while its kept feed is
     * still valid for this build, then checks when the daily check is due, when such a kept update waits
     * (and automatic checks are on), or when the app was stopped while the user was allowing "Install
     * unknown apps" for an update (offered again).
     */
    fun onAppStart() {
        scope.launch {
            val stored =
                withContext(io) {
                    updatesDir.listFiles()?.forEach { it.delete() }
                    Stored(
                        autoCheck = prefs.getBoolean(KEY_AUTO_CHECK, true),
                        lastCheck = prefs.getLong(KEY_LAST_CHECK, 0L).takeIf { it > 0 },
                        pending = prefs.getLong(KEY_PENDING, 0L),
                        prompted = prefs.getLong(KEY_PROMPTED, 0L),
                        kept = prefs.getString(KEY_FOUND, null)?.let { checker.reread(it, installed) },
                    )
                }
            prompted = maxOf(prompted, stored.prompted)
            mutableState.update { it.copy(autoCheck = stored.autoCheck) }
            offerKept(stored.kept)
            val auto = if (stored.autoCheck) "on" else "off"
            Log.i(TAG, "updates: ${installed.versionName} (${installed.versionCode}) on ${installed.channel.id}; automatic checks $auto")
            val pending = stored.pending > installed.versionCode
            if (!pending && stored.pending != 0L) setPending(null)
            // While a kept update waits, the check runs at every start, even when the daily one is not
            // due: a newer build can have replaced it (edge removes the APKs of older builds).
            val confirm = stored.autoCheck && found != null
            if (pending || confirm || isAutoCheckDue(stored.autoCheck, stored.lastCheck, platform.now())) {
                check(automatic = !pending)
            }
        }
    }

    override fun checkNow() = check(automatic = false)

    override fun setAutoCheck(enabled: Boolean) {
        mutableState.update { it.copy(autoCheck = enabled) }
        scope.launch { withContext(io) { prefs.edit().putBoolean(KEY_AUTO_CHECK, enabled).apply() } }
    }

    private fun check(automatic: Boolean) {
        if (checkJob?.isActive == true || downloadJob?.isActive == true) return
        // The update found before stays ready (dot, card) while the check runs.
        mutableState.update { it.copy(status = UpdateStatus.Checking, ready = foundUpdate()) }
        checkJob =
            scope.launch {
                try {
                    val urls =
                        servers.config.value.updateFeeds
                            .forChannel(installed.channel)
                    val answer = withContext(io) { checker.check(urls, installed) }
                    if (answer != CheckResult.Unreachable) {
                        withContext(io) { prefs.edit().putLong(KEY_LAST_CHECK, platform.now()).apply() }
                    }
                    // A valid feed decides: it replaces or ends the update found before. No server reached
                    // or every answer refused is no news about it: its kept feed is still validly signed,
                    // so it stays ready, and the status says what the check found.
                    val decided = answer !is CheckResult.Unreachable && answer !is CheckResult.Refused
                    if (decided) keep(answer as? CheckResult.Available)
                    val stillReady = if (decided || found == null) "" else "; the update found before stays ready"
                    Log.i(TAG, "updates: ${if (automatic) "automatic" else "manual"} check: ${describe(answer)}$stillReady")
                    offered = found?.feed
                    val update = foundUpdate()
                    // A check the user asked for answers in a dialog when it found an update. The automatic
                    // one at start does not open a dialog over the map: the map offers a version once, in a
                    // snackbar.
                    val offer =
                        update?.takeIf { !automatic && answer is CheckResult.Available }?.let {
                            UpdateDialog.Offer(
                                it,
                                installed.versionName,
                            )
                        }
                    val prompt = update?.takeIf { automatic && it.versionCode != prompted }
                    if (offer != null) markSeen(offer.update)
                    mutableState.update {
                        it.copy(
                            status = answer.toStatus(),
                            ready = update,
                            dialog = offer ?: it.dialog,
                            prompt = prompt,
                        )
                    }
                } finally {
                    // Cancelled or failed: never left showing "Checking…".
                    mutableState.update {
                        if (it.status != UpdateStatus.Checking) {
                            it
                        } else {
                            it.copy(status = foundUpdate()?.let { update -> UpdateStatus.Available(update) } ?: UpdateStatus.NotChecked)
                        }
                    }
                }
            }
    }

    private fun foundUpdate(): AvailableUpdate? = found?.let { AvailableUpdate.of(it.feed) }

    /**
     * Offers the update kept from before the restart while it is still valid for this build, as its
     * check did (in Settings, and on the map if it was never offered there); forgets one that is not
     * (installed meanwhile, or refused now).
     */
    private fun offerKept(kept: CheckResult?) {
        if (kept == null) return
        if (kept !is CheckResult.Available) {
            Log.i(TAG, "updates: the update found before is no longer offered: ${describe(kept)}")
            keep(null)
            return
        }
        // A check the user started meanwhile and that already answered decides instead.
        val status = state.value.status
        if (status != UpdateStatus.NotChecked && status != UpdateStatus.Checking) return
        found = kept
        offered = kept.feed
        val update = AvailableUpdate.of(kept.feed)
        Log.i(TAG, "updates: ${update.versionName} (${update.versionCode}), found before, is still ready")
        mutableState.update {
            if (it.status == UpdateStatus.Checking) {
                it.copy(ready = update)
            } else {
                it.copy(status = UpdateStatus.Available(update), prompt = update.takeIf { update.versionCode != prompted })
            }
        }
    }

    /** Keeps the signed feed of [update] for the next start, or forgets the kept one when null. */
    private fun keep(update: CheckResult.Available?) {
        found = update
        scope.launch {
            withContext(io) {
                prefs.edit().apply { if (update == null) remove(KEY_FOUND) else putString(KEY_FOUND, update.signedFile) }.apply()
            }
        }
    }

    override fun install() {
        val feed = offered ?: return
        if (downloadJob?.isActive == true) return
        val update = AvailableUpdate.of(feed)
        markSeen(update)
        val ready = verified
        if (ready != null && ready.second == feed && ready.first.exists()) {
            mutableState.update { it.copy(dialog = null) }
            handOff()
            return
        }
        mutableState.update { it.copy(dialog = UpdateDialog.Downloading(update, 0)) }
        // A cancelled download can still be winding down (a blocked read ends only at the read
        // timeout) and its clean-up deletes the part file, so the new one starts after it ended.
        val previous = downloadJob
        downloadJob =
            scope.launch {
                previous?.join()
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
        var keep = false
        try {
            withContext(io) {
                updatesDir.mkdirs()
                updatesDir.listFiles()?.forEach { it.delete() }
            }
            runInterruptible(io) {
                platform.download(feed.apk.url, part, feed.apk.size) { bytes ->
                    mutableState.update { state ->
                        val dialog = state.dialog
                        if (dialog is UpdateDialog.Downloading) state.copy(dialog = dialog.copy(downloadedBytes = bytes)) else state
                    }
                }
            }
            val problem = withContext(io) { checkApk(part, feed) ?: if (part.renameTo(file)) null else "The download could not be saved." }
            if (problem == null) {
                keep = true
                verified = file to feed
                Log.i(TAG, "updates: ${file.name} matches the signed feed (size, SHA-256, package, versionCode)")
            }
            return problem
        } catch (e: HttpStatusException) {
            Log.w(TAG, "updates: download failed", e)
            // A newer build replaced it (edge removes the APKs of older builds): only a new check helps.
            if (e.code == 404 || e.code == 410) {
                return "Pubky Maps ${update.versionName} is no longer on the update server. Check for updates to get the newest version."
            }
            return "The download of ${update.versionName} failed. Try again later."
        } catch (e: IOException) {
            Log.w(TAG, "updates: download failed", e)
            return "The download of ${update.versionName} failed. Check your connection and try again."
        } finally {
            // Also when cancelled: no partial or refused file stays in the cache.
            withContext(NonCancellable + io) {
                part.delete()
                if (!keep) file.delete()
            }
        }
    }

    /** The file is the one the signed feed names, and Android reads it as the right package and version. */
    private fun checkApk(
        file: File,
        feed: UpdateFeed,
    ): String? {
        checkDownload(file, feed.apk.size, feed.apk.sha256)?.let { return it }
        val facts = platform.readArchive(file)
        if (facts?.packageName != installed.appId || facts.versionCode != feed.versionCode) {
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
        if (resumed.get() == null) {
            // The app is in the background: the installer opens when it comes back.
            handOffPending = true
            return
        }
        if (!platform.canInstallPackages()) {
            mutableState.update { it.copy(dialog = UpdateDialog.NeedsPermission(AvailableUpdate.of(feed))) }
            return
        }
        scope.launch {
            val problem = withContext(io) { if (file.exists()) checkApk(file, feed) else "The download is gone." }
            if (problem != null) {
                verified = null
                withContext(io) { file.delete() }
                Log.w(TAG, "updates: not installed: $problem")
                mutableState.update { it.copy(dialog = UpdateDialog.NotInstalled(problem)) }
                return@launch
            }
            val activity = resumed.get()
            if (activity == null) {
                handOffPending = true
                return@launch
            }
            try {
                platform.launchInstaller(activity, file)
                setPending(null)
                Log.i(TAG, "updates: ${file.name} checked again; handed to the installer")
            } catch (e: ActivityNotFoundException) {
                Log.w(TAG, "updates: no installer", e)
                mutableState.update { it.copy(dialog = UpdateDialog.NotInstalled("This phone has no app installer for updates.")) }
            }
        }
    }

    override fun openInstallPermissionSettings() {
        val activity = resumed.get() ?: return
        val feed = verified?.second ?: return
        mutableState.update { it.copy(dialog = null) }
        try {
            platform.openInstallPermissionSettings(activity)
            awaitingPermission = true
            // If Android stops the app meanwhile, the next start offers this update again.
            setPending(feed.versionCode)
        } catch (e: ActivityNotFoundException) {
            Log.w(TAG, "updates: no settings page for Install unknown apps", e)
            mutableState.update {
                it.copy(dialog = UpdateDialog.NotInstalled("Android has no setting to allow updates here. Install the update by hand."))
            }
        }
    }

    override fun cancelDownload() {
        downloadJob?.cancel()
        mutableState.update { it.copy(dialog = null) }
    }

    override fun dismissDialog() {
        if (state.value.dialog is UpdateDialog.Downloading) return
        awaitingPermission = false
        setPending(null)
        mutableState.update { it.copy(dialog = null) }
    }

    override fun promptShown() {
        markSeen(state.value.prompt ?: return)
    }

    /** The user has seen [update] offered: the map does not offer that version again. */
    private fun markSeen(update: AvailableUpdate) {
        mutableState.update { it.copy(prompt = null) }
        if (update.versionCode == prompted) return
        prompted = update.versionCode
        scope.launch { withContext(io) { prefs.edit().putLong(KEY_PROMPTED, update.versionCode).apply() } }
    }

    private fun setPending(versionCode: Long?) {
        scope.launch {
            withContext(io) {
                prefs.edit().apply { if (versionCode == null) remove(KEY_PENDING) else putLong(KEY_PENDING, versionCode) }.apply()
            }
        }
    }

    private fun onResumed(activity: Activity) {
        resumed = WeakReference(activity)
        if (awaitingPermission || handOffPending) {
            awaitingPermission = false
            handOffPending = false
            handOff()
        }
    }

    private data class Stored(
        val autoCheck: Boolean,
        val lastCheck: Long?,
        val pending: Long,
        val prompted: Long,
        /** The kept feed of the update found before, read again; null when none is kept. */
        val kept: CheckResult?,
    )

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
