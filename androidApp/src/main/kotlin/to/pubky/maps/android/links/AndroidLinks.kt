package to.pubky.maps.android.links

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import to.pubky.maps.links.IncomingLinks
import to.pubky.maps.links.InstalledApps
import to.pubky.maps.links.OpenInAction

/** Pubky Ring's return links: `pubkymaps://auth/{success|error|cancel}?nonce=…`. */
private const val RING_RETURN_SCHEME = "pubkymaps"

/** What an intent that reached the app is for. */
sealed interface IncomingIntent {
    /** Pubky Ring sent the user back from a sign-in. */
    data class RingReturn(
        val url: String,
    ) : IncomingIntent

    /** Another app sent a map link (a link, or text with one). */
    data class MapLink(
        val text: String,
    ) : IncomingIntent
}

/**
 * What this intent asks of the app, or null (the launcher, or an intent replayed when the task is
 * reopened from Recents, which was handled when it first arrived).
 */
fun Intent.incoming(): IncomingIntent? {
    if (flags and Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY != 0) return null
    data?.takeIf { it.scheme == RING_RETURN_SCHEME }?.let { return IncomingIntent.RingReturn(it.toString()) }
    return mapLinkText()?.let(IncomingIntent::MapLink)
}

/** The map-link text of a `VIEW` or `SEND` intent another app sent, or null. */
fun Intent.mapLinkText(): String? =
    IncomingLinks.textOf(
        action = action,
        data = dataString,
        text = getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString(),
        subject = getCharSequenceExtra(Intent.EXTRA_SUBJECT)?.toString(),
    )

/** Which map apps are installed, through the package queries the manifest declares. */
class AndroidInstalledApps(
    private val context: Context,
) : InstalledApps {
    override fun isInstalled(packageName: String): Boolean =
        try {
            context.packageManager.getPackageInfo(packageName, 0)
            true
        } catch (e: PackageManager.NameNotFoundException) {
            false
        }

    override fun canOpenGeo(): Boolean =
        context.packageManager
            .queryIntentActivities(Intent(Intent.ACTION_VIEW, Uri.parse("geo:0,0")), 0)
            .any { it.activityInfo.packageName != context.packageName }
}

/**
 * Opens an "Open in" entry: in its app when it names one, else whatever opens its link (a browser,
 * or the system's chooser for `geo:`). Does nothing when nothing can open it.
 */
fun Context.openInApp(action: OpenInAction) {
    val view = Intent(Intent.ACTION_VIEW, Uri.parse(action.uri)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    action.packageName?.let(view::setPackage)
    val intent =
        if (action.packageName == null &&
            action.uri.startsWith("geo:")
        ) {
            Intent.createChooser(view, null).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        } else {
            view
        }
    try {
        startActivity(intent)
    } catch (e: ActivityNotFoundException) {
        // Uninstalled since the list was made.
    }
}

/** Opens the system share sheet with [text]. Does nothing on a phone with nothing to share to. */
fun Context.shareText(text: String) {
    val send =
        Intent(Intent.ACTION_SEND)
            .setType("text/plain")
            .putExtra(Intent.EXTRA_TEXT, text)
    val chooser = Intent.createChooser(send, null).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    try {
        startActivity(chooser)
    } catch (e: ActivityNotFoundException) {
        // Nothing can take shared text.
    }
}
