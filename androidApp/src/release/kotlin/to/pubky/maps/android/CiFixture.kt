package to.pubky.maps.android

import android.content.Context
import to.pubky.maps.account.SecretStore
import to.pubky.maps.cache.AccountCache

/** Release builds take no test fixture (the debug build's emulator-test seed). */
@Suppress("UNUSED_PARAMETER")
internal suspend fun importCiFixture(
    context: Context,
    secrets: SecretStore,
    cache: AccountCache,
) = Unit
