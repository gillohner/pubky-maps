package to.pubky.maps.android

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import to.pubky.maps.account.SecretStore
import to.pubky.maps.cache.AccountCache
import to.pubky.maps.cache.CachedAccount
import to.pubky.maps.cache.CachedBookmark
import to.pubky.maps.cache.CachedTag
import java.io.File

/**
 * Debug builds only: the emulator tests (scripts/ci/) seed a signed-in account by writing
 * `files/ci-fixture.json` with `run-as` before the app starts: `{"secret", "publicKey", "name",
 * "tags": [{"place", "label"}], "bookmarks": [{"place"}]}`. It goes through the app's own secret
 * store and cache, then the file is deleted.
 */
internal suspend fun importCiFixture(
    context: Context,
    secrets: SecretStore,
    cache: AccountCache,
) {
    val file = File(context.filesDir, "ci-fixture.json")
    val fixture =
        withContext(Dispatchers.IO) {
            if (!file.exists()) return@withContext null
            try {
                JSONObject(file.readText())
            } finally {
                file.delete()
            }
        } ?: return
    val publicKey = fixture.getString("publicKey")
    val tags = fixture.optJSONArray("tags") ?: JSONArray()
    val bookmarks = fixture.optJSONArray("bookmarks") ?: JSONArray()
    val account =
        CachedAccount(
            publicKey = publicKey,
            name = fixture.optString("name").takeIf { it.isNotEmpty() },
            refreshedAt = null,
            tags =
                (0 until tags.length()).map { i ->
                    val tag = tags.getJSONObject(i)
                    CachedTag("/pub/social/v1/tags/fixture-$i.json", tag.getString("place"), tag.getString("label"), i.toLong())
                },
            bookmarks =
                (0 until bookmarks.length()).map { i ->
                    CachedBookmark("/priv/social/v1/bookmarks/fixture-$i.json", bookmarks.getJSONObject(i).getString("place"))
                },
        )
    withContext(Dispatchers.IO) { cache.replace(account) }
    secrets.write(SecretStore.SESSION, fixture.getString("secret"))
}
