package to.pubky.maps.android.links

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import to.pubky.maps.links.IncomingLinks
import to.pubky.maps.links.InstalledApps

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
