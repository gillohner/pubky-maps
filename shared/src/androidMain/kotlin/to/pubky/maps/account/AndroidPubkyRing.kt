package to.pubky.maps.account

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri

/** Pubky Ring through Android intents: Ring handles `pubkyauth://` links. */
class AndroidPubkyRing(
    private val context: Context,
) : PubkyRing {
    // Package visibility: the manifest's <queries> lists this intent.
    override fun isInstalled(): Boolean = context.packageManager.queryIntentActivities(view("pubkyauth://signin_grant"), 0).isNotEmpty()

    override fun open(link: String): Boolean =
        try {
            context.startActivity(view(link).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            true
        } catch (_: ActivityNotFoundException) {
            false
        }

    private fun view(link: String) = Intent(Intent.ACTION_VIEW, Uri.parse(link))
}
