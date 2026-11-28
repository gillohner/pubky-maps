package to.pubky.maps.android

import android.content.res.Configuration
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import com.github.takahirom.roborazzi.ExperimentalRoborazziApi
import com.github.takahirom.roborazzi.RoborazziComposeOptions
import com.github.takahirom.roborazzi.captureRoboImage
import com.github.takahirom.roborazzi.fontScale
import com.github.takahirom.roborazzi.size
import com.github.takahirom.roborazzi.uiMode
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode
import to.pubky.maps.links.GeoPoint
import to.pubky.maps.place.LocalRemoteImage
import to.pubky.maps.place.PlacePageActions
import to.pubky.maps.place.PlacePageState
import to.pubky.maps.place.PlaceRef
import to.pubky.maps.place.PlaceSource
import to.pubky.maps.place.RemoteImageContent
import to.pubky.maps.place.SheetStop
import to.pubky.maps.place.StreetPhoto
import to.pubky.maps.ui.PubkyMapsTheme

/**
 * Screenshots of a place page with Panoramax' street-level photos (phone at its top stop and at a
 * 200 % font, tablet, light and dark): a café found in search, nothing on Pubky yet, four photos of
 * three photographers under two licences. Thumbnails are drawn stand-ins: images load over the
 * network, which the tests do not use.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@OptIn(ExperimentalRoborazziApi::class)
class StreetPhotosScreenshotTest {
    private val pages = PlaceScreenshotTest()

    private val cafe =
        PlacePageState(
            place =
                PlaceRef(
                    uri = "https://www.openstreetmap.org/node/4",
                    name = "Café Kaserne",
                    point = GeoPoint(47.3752, 8.5302),
                    osm = null,
                    source = PlaceSource.Photon,
                    address = "Kasernenstrasse 49, 8004 Zürich",
                    category = "Café",
                ),
            loading = emptySet(),
        )

    private fun photo(
        n: Int,
        author: String?,
        taken: String,
        licence: String,
    ) = StreetPhoto(
        id = "00000000-0000-4000-8000-00000000000$n",
        thumbnailUrl = "https://example.invalid/$n.jpg",
        viewerUrl = "https://example.invalid/#focus=pic&pic=$n",
        author = author,
        taken = taken,
        licence = licence,
        licenceUrl = "https://example.invalid/licence",
    )

    private val photos =
        listOf(
            photo(1, "captaininler", "Apr 2024", "CC-BY-SA-4.0"),
            photo(2, "Koreller", "Jul 2025", "CC-BY-SA-4.0"),
            photo(3, "immergis", "Apr 2026", "etalab-2.0"),
            photo(4, "Koreller", "Jul 2025", "CC-BY-SA-4.0"),
        )

    private val actions =
        PlacePageActions(
            onClose = {},
            onShare = {},
            openIn = emptyList(),
            onOpenIn = {},
            onOpenLink = {},
            onLoadMorePosts = {},
            onDirections = {},
            onReportProblem = {},
            streetPhotos = photos,
        )

    private fun capture(
        name: String,
        widthDp: Int,
        heightDp: Int,
        dark: Boolean,
        largeFont: Boolean = false,
        content: @Composable () -> Unit,
    ) {
        captureRoboImage(
            filePath = "screenshots/$name-${if (dark) "dark" else "light"}.png",
            roborazziComposeOptions =
                RoborazziComposeOptions {
                    size(widthDp = widthDp, heightDp = heightDp)
                    uiMode(if (dark) Configuration.UI_MODE_NIGHT_YES else Configuration.UI_MODE_NIGHT_NO)
                    if (largeFont) fontScale(2f)
                },
        ) {
            PubkyMapsTheme { CompositionLocalProvider(LocalRemoteImage provides StreetImage) { content() } }
        }
    }

    @Test
    fun phoneLight() = capture("place-street-photos-phone", 412, 915, dark = false) { pages.Page(cafe, SheetStop.Full, actions) }

    @Test
    fun phoneDark() = capture("place-street-photos-phone", 412, 915, dark = true) { pages.Page(cafe, SheetStop.Full, actions) }

    @Test
    fun tabletLight() = capture("place-street-photos-tablet", 1280, 800, dark = false) { pages.Page(cafe, SheetStop.Full, actions) }

    @Test
    fun tabletDark() = capture("place-street-photos-tablet", 1280, 800, dark = true) { pages.Page(cafe, SheetStop.Full, actions) }

    @Test
    fun largeFontPhoneLight() =
        capture("place-street-photos-large-font-phone", 412, 915, dark = false, largeFont = true) {
            pages.Page(cafe, SheetStop.Full, actions)
        }
}

/** A drawn stand-in for a street photo: sky, house fronts, the street. */
private val StreetImage: RemoteImageContent = { _, _, modifier, _, _, _ ->
    Box(modifier.background(Brush.verticalGradient(listOf(Color(0xFF9CC3E4), Color(0xFFB9A58E), Color(0xFF6E6A66)))))
}
