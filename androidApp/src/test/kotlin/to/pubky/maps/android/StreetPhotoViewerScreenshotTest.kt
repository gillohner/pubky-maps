package to.pubky.maps.android

import android.content.res.Configuration
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
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
import to.pubky.maps.place.RemoteImageContent
import to.pubky.maps.place.StreetPhoto
import to.pubky.maps.place.StreetPhotoViewer
import to.pubky.maps.place.StreetPhotosState
import to.pubky.maps.ui.PubkyMapsTheme

/**
 * Screenshots of the street-level photo viewer (phone, tablet, light and dark, a 200 % font): a flat
 * photo fitted whole, and a 360° one turned to face the place, with the place's four photos to switch
 * to. Photos are drawn stand-ins (a 4:3 street, a panorama in bands), since they load over the network.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@OptIn(ExperimentalRoborazziApi::class)
class StreetPhotoViewerScreenshotTest {
    private val place = GeoPoint(47.3752, 8.5302)

    private fun photo(
        n: Int,
        author: String,
        taken: String,
        licence: String,
        panorama: Boolean = false,
    ) = StreetPhoto(
        id = "00000000-0000-4000-8000-00000000000$n",
        thumbnailUrl = "https://example.invalid/$n/thumb.jpg",
        viewerUrl = "https://example.invalid/#focus=pic&pic=$n",
        author = author,
        taken = taken,
        licence = licence,
        licenceUrl = "https://example.invalid/licence",
        photoUrl = "https://example.invalid/$n/sd.jpg",
        panorama = panorama,
        point = GeoPoint(47.3752, 8.5300),
        heading = 0.0,
    )

    private val photos =
        listOf(
            photo(1, "captaininler", "Apr 2024", "CC-BY-SA-4.0"),
            photo(2, "Koreller", "Jul 2025", "CC-BY-SA-4.0", panorama = true),
            photo(3, "immergis", "Apr 2026", "etalab-2.0"),
            photo(4, "Koreller", "Jul 2025", "CC-BY-SA-4.0"),
        )

    private fun capture(
        name: String,
        widthDp: Int,
        heightDp: Int,
        dark: Boolean,
        selected: Int = 0,
        largeFont: Boolean = false,
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
            PubkyMapsTheme {
                CompositionLocalProvider(LocalRemoteImage provides StandInPhoto) {
                    StreetPhotoViewer(
                        photos = StreetPhotosState(place, photos, loading = false),
                        place = place,
                        selected = photos[selected].id,
                        onSelect = {},
                        onRetry = {},
                        onOpenLink = {},
                        onBack = {},
                    )
                }
            }
        }
    }

    @Test
    fun phoneLight() = capture("street-photo-viewer-phone", 412, 915, dark = false)

    @Test
    fun phoneDark() = capture("street-photo-viewer-phone", 412, 915, dark = true)

    @Test
    fun panoramaPhoneLight() = capture("street-photo-viewer-360-phone", 412, 915, dark = false, selected = 1)

    @Test
    fun panoramaPhoneDark() = capture("street-photo-viewer-360-phone", 412, 915, dark = true, selected = 1)

    @Test
    fun tabletLight() = capture("street-photo-viewer-tablet", 1280, 800, dark = false)

    @Test
    fun tabletDark() = capture("street-photo-viewer-tablet", 1280, 800, dark = true)

    @Test
    fun panoramaTabletLight() = capture("street-photo-viewer-360-tablet", 1280, 800, dark = false, selected = 1)

    /** A phone on its side: the details beside the photo. */
    @Test
    fun landscapePhoneLight() = capture("street-photo-viewer-landscape-phone", 915, 412, dark = false)

    @Test
    fun landscapePhoneDark() = capture("street-photo-viewer-landscape-phone", 915, 412, dark = true)

    @Test
    fun panoramaTabletDark() = capture("street-photo-viewer-360-tablet", 1280, 800, dark = true, selected = 1)

    @Test
    fun largeFontPhoneLight() = capture("street-photo-viewer-large-font-phone", 412, 915, dark = false, largeFont = true)
}

/**
 * A drawn stand-in for a photo: fitted, a 4:3 street (sky, house fronts, the street) in the middle of
 * its bounds; filling them, a panorama in coloured bands, so its turn shows.
 */
private val StandInPhoto: RemoteImageContent = { _, _, modifier, _, scale, _ ->
    if (scale == ContentScale.Fit) {
        Box(modifier, contentAlignment = Alignment.Center) {
            Box(
                Modifier
                    .aspectRatio(4f / 3f)
                    .background(Brush.verticalGradient(listOf(Color(0xFF9CC3E4), Color(0xFFB9A58E), Color(0xFF6E6A66)))),
            )
        }
    } else {
        val bands = listOf(Color(0xFF6E8FB0), Color(0xFFB9A58E), Color(0xFF7FA36B), Color(0xFFC48B6B), Color(0xFF6E8FB0))
        Box(
            modifier
                .background(
                    Brush.horizontalGradient(bands),
                ).background(Brush.verticalGradient(listOf(Color(0x559CC3E4), Color.Transparent, Color(0x55303030)))),
        )
    }
}
