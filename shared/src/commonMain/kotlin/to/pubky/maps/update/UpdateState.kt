package to.pubky.maps.update

import kotlinx.coroutines.flow.StateFlow

/** An update the user can install. */
data class AvailableUpdate(
    val versionCode: Long,
    val versionName: String,
    val sizeBytes: Long,
) {
    companion object {
        fun of(feed: UpdateFeed) = AvailableUpdate(feed.versionCode, feed.versionName, feed.apk.size)
    }
}

/** The last check's outcome, shown under "Check for updates". */
sealed interface UpdateStatus {
    data object NotChecked : UpdateStatus

    data object Checking : UpdateStatus

    data class UpToDate(
        val newestVersionName: String,
    ) : UpdateStatus

    data class Available(
        val update: AvailableUpdate,
    ) : UpdateStatus

    data class Failed(
        val message: String,
    ) : UpdateStatus
}

/** The update dialog, when one is open. */
sealed interface UpdateDialog {
    /** "Update available": version and download size; Install or Later. */
    data class Offer(
        val update: AvailableUpdate,
        val installedVersionName: String,
    ) : UpdateDialog

    data class Downloading(
        val update: AvailableUpdate,
        val downloadedBytes: Long,
    ) : UpdateDialog

    /** Android needs "Install unknown apps" turned on for Pubky Maps first. */
    data class NeedsPermission(
        val update: AvailableUpdate,
    ) : UpdateDialog

    /** Nothing was installed, and why. */
    data class NotInstalled(
        val message: String,
    ) : UpdateDialog
}

/** Everything the update UI shows. */
data class UpdatesUiState(
    val channel: UpdateChannel,
    val autoCheck: Boolean,
    val status: UpdateStatus,
    val dialog: UpdateDialog?,
)

/** The update a check found and the user can install now; null while there is none (or a check runs). */
val UpdatesUiState.readyToInstall: AvailableUpdate?
    get() = (status as? UpdateStatus.Available)?.update

/** The in-app updater, for builds that update themselves (the APK published on GitHub). */
interface UpdateController {
    val state: StateFlow<UpdatesUiState>

    fun checkNow()

    fun setAutoCheck(enabled: Boolean)

    /** Downloads the offered update, checks it and hands it to Android's installer. */
    fun install()

    fun openInstallPermissionSettings()

    fun cancelDownload()

    fun dismissDialog()
}

/** The words for a check's result, as shown under "Check for updates". */
fun CheckResult.toStatus(): UpdateStatus =
    when (this) {
        is CheckResult.Available -> {
            UpdateStatus.Available(AvailableUpdate.of(feed))
        }

        is CheckResult.UpToDate -> {
            UpdateStatus.UpToDate(newestVersionName)
        }

        is CheckResult.NeedsNewerAndroid -> {
            UpdateStatus.Failed("Version ${feed.versionName} needs a newer Android (API ${feed.minSdk} or later).")
        }

        is CheckResult.Refused -> {
            UpdateStatus.Failed(problem.message)
        }

        CheckResult.Unreachable -> {
            UpdateStatus.Failed("The update server could not be reached. Try again later.")
        }
    }

/** "14.2 MB": one decimal, decimal megabytes like Android's own storage screens. */
fun formatMegabytes(bytes: Long): String {
    val tenths = (bytes + 50_000) / 100_000
    return "${tenths / 10}.${tenths % 10} MB"
}
