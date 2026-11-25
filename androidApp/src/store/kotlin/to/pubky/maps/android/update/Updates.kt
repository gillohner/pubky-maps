package to.pubky.maps.android.update

import android.app.Application
import to.pubky.maps.config.ServerConfigRepository
import to.pubky.maps.update.UpdateController

/** App-store builds have no updater: the store updates the app. */
@Suppress("UNUSED_PARAMETER", "FunctionOnlyReturningConstant")
fun startUpdater(
    app: Application,
    servers: ServerConfigRepository,
): UpdateController? = null
