package to.pubky.maps.android

import android.content.res.Configuration
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import com.github.takahirom.roborazzi.ExperimentalRoborazziApi
import com.github.takahirom.roborazzi.RoborazziComposeOptions
import com.github.takahirom.roborazzi.captureRoboImage
import com.github.takahirom.roborazzi.size
import com.github.takahirom.roborazzi.uiMode
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import to.pubky.maps.config.OpenInApp
import to.pubky.maps.config.ServerDefaults
import to.pubky.maps.links.GeoPoint
import to.pubky.maps.links.OpenInAction
import to.pubky.maps.links.OsmId
import to.pubky.maps.links.OsmType
import to.pubky.maps.map.MapScreen
import to.pubky.maps.map.PlacePageSlot
import to.pubky.maps.place.AlsoOnLink
import to.pubky.maps.place.PlaceCardInfo
import to.pubky.maps.place.PlaceCredit
import to.pubky.maps.place.PlaceList
import to.pubky.maps.place.PlacePageActions
import to.pubky.maps.place.PlacePageContext
import to.pubky.maps.place.PlacePageState
import to.pubky.maps.place.PlacePanel
import to.pubky.maps.place.PlacePart
import to.pubky.maps.place.PlacePerson
import to.pubky.maps.place.PlacePhoto
import to.pubky.maps.place.PlacePost
import to.pubky.maps.place.PlaceRef
import to.pubky.maps.place.PlaceSheet
import to.pubky.maps.place.PlaceSocial
import to.pubky.maps.place.PlaceSource
import to.pubky.maps.place.PlaceTag
import to.pubky.maps.place.SheetStop
import to.pubky.maps.ui.PubkyMapsTheme

/**
 * Screenshots of the place page (phone 412x915 dp, tablet 1280x800 dp, light and dark): a place with
 * everything (Pinakes card with photo and OSM details, Pubky tags with the user's own, photos, posts,
 * lists, Also on), a sparse place (a Photon address only), one still loading, and one shown offline
 * from the cache. Photos are grey: images load over the network, which the tests do not use.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36])
@OptIn(ExperimentalRoborazziApi::class)
class PlaceScreenshotTest {
    private enum class Device(
        val widthDp: Int,
        val heightDp: Int,
    ) {
        Phone(412, 915),
        Tablet(1280, 800),

        /** A phone as tall as the whole rich page, to see every section at once. */
        TallPhone(412, 2300),
    }

    private fun capture(
        name: String,
        device: Device,
        dark: Boolean,
        content: @Composable () -> Unit,
    ) {
        val theme = if (dark) "dark" else "light"
        captureRoboImage(
            filePath = "screenshots/$name-${device.name.lowercase()}-$theme.png",
            roborazziComposeOptions =
                RoborazziComposeOptions {
                    size(widthDp = device.widthDp, heightDp = device.heightDp)
                    uiMode(if (dark) Configuration.UI_MODE_NIGHT_YES else Configuration.UI_MODE_NIGHT_NO)
                },
        ) {
            PubkyMapsTheme { content() }
        }
    }

    /** Wednesday noon in Zürich, as UTC so the test does not depend on the machine's zone. */
    private val context =
        PlacePageContext(
            now = LocalDateTime(LocalDate.parse("2026-11-25"), LocalTime(12, 0)),
            nowMillis = 1_795_608_000_000,
            timeZone = TimeZone.UTC,
            language = "en",
            distance = "850 m",
        )

    private val me = "8pinxsr5ctu1ou5ruzomw4fiz4fe1jybsr6o9dnb8jxcc7bpc3wo"
    private val anna = "w36y3s9xx5s37y6xn3hkam6pz66wui8cwek6swg3x13c6bc1nu8y"
    private val ben = "p5onm3q78y8btyjt9ntjcpcj1fq395q8gjuf94k35dz15yxiuy7y"
    private val cleo = "5rgz31wy7bd61jrrw6rpj8hkm5qyrkjgrren1c81x79fku7pppjo"

    private val kunsthausRef =
        PlaceRef(
            uri = "https://www.openstreetmap.org/relation/3768127",
            name = "Kunsthaus",
            point = GeoPoint(47.3702241, 8.5479797),
            osm = OsmId(OsmType.Relation, 3768127),
            source = PlaceSource.Pinakes,
            address = "Heimplatz 1, 8001 Zürich",
            category = "Museum",
        )

    private val kunsthausCard =
        PlaceCardInfo(
            id = "49d6eb1c6a69946a41fe45ccd44c4a72",
            uri = kunsthausRef.uri,
            name = "Kunsthaus",
            type = "Museum",
            category = "tourism/museum",
            address = "Heimplatz 1, 8001 Zürich",
            lat = 47.3702241,
            lon = 8.5479797,
            description = "Art museum in Zürich with one of the most important collections in Switzerland",
            image = "https://example.invalid/kunsthaus.jpg",
            credits =
                listOf(
                    PlaceCredit(
                        "image",
                        "Roland zh",
                        "CC-BY-SA-3.0",
                        "https://creativecommons.org/licenses/by-sa/3.0/",
                        "https://example.invalid/file",
                        "wikimedia_commons",
                    ),
                    PlaceCredit("description", null, "CC0-1.0", null, "https://www.wikidata.org/wiki/Q685038", "wikidata"),
                ),
            attribution = "© OpenStreetMap contributors (ODbL)",
            alsoOn =
                listOf(
                    AlsoOnLink("Official website", "http://www.kunsthaus.ch/", derived = true),
                    AlsoOnLink("Google Maps", "https://maps.app.goo.gl/kunsthaus", derived = false),
                    AlsoOnLink("Wikidata", "https://www.wikidata.org/wiki/Q685038", derived = true),
                    AlsoOnLink("Wikipedia (en)", "https://en.wikipedia.org/wiki/Kunsthaus_Z%C3%BCrich", derived = true),
                    AlsoOnLink("Wikipedia (de)", "https://de.wikipedia.org/wiki/Kunsthaus_Z%C3%BCrich", derived = true),
                ),
            refs = listOf(kunsthausRef.uri),
            collections = 2,
            facts =
                mapOf(
                    "opening_hours" to "Tu-Su 10:00-18:00; Th 10:00-20:00; PH 10:00-18:00",
                    "website" to "https://www.kunsthaus.ch/",
                    "phone" to "+41 44 253 84 84",
                    "wheelchair" to "yes",
                ),
        )

    private val people =
        listOf(
            PlacePerson(me, "Gil", null, followed = false),
            PlacePerson(anna, "Anna", null, followed = true),
            PlacePerson(ben, "Ben", null, followed = false),
        ).associateBy { it.id }

    private fun photo(n: Int) = PlacePhoto(anna, "https://example.invalid/$n.jpg", "https://example.invalid/$n-feed.jpg")

    private val rich =
        PlacePageState(
            place = kunsthausRef,
            card = kunsthausCard,
            social =
                PlaceSocial(
                    tags =
                        listOf(
                            PlaceTag("art", 12, listOf(ben, anna, cleo), mine = false),
                            PlaceTag("museum", 7, listOf(anna, ben), mine = false),
                            PlaceTag("rainyday", 3, listOf(me, anna), mine = true),
                            PlaceTag("architecture", 2, listOf(cleo), mine = false),
                        ),
                    resourceIds = listOf("49d6eb1c6a69946a41fe45ccd44c4a72"),
                    replyCount = 2,
                    embedCount = 3,
                ),
            people = people,
            posts =
                listOf(
                    PlacePost(
                        "pubky://$anna/pub/social/v1/posts/A",
                        anna,
                        "note",
                        "The Giacometti room alone is worth the visit. Go on a Wednesday, entry is free.",
                        1_795_545_000_000,
                        emptyList(),
                    ),
                    PlacePost(
                        "pubky://$ben/pub/social/v1/posts/B",
                        ben,
                        "note",
                        "Chipperfield's new building is impressive, the café less so.",
                        1_795_338_000_000,
                        emptyList(),
                    ),
                ),
            morePosts = true,
            photos = (1..4).map(::photo),
            viewer = me,
            lists =
                listOf(
                    PlaceList("pubky://$me/pub/social/v1/posts/L", me, "Rainy days in Zürich", 14, mine = true),
                    PlaceList("pubky://$anna/pub/social/v1/posts/M", anna, "Museums", 31, mine = false),
                ),
            loading = emptySet(),
        )

    private val sparse =
        PlacePageState(
            place =
                PlaceRef(
                    uri = "https://www.openstreetmap.org/node/10946043353",
                    name = "Babu's Bakery",
                    point = GeoPoint(47.374725, 8.515677),
                    osm = OsmId(OsmType.Node, 10946043353),
                    source = PlaceSource.Photon,
                    address = "Zentralstrasse 127, 8003 Zürich",
                    category = "Bakery shop",
                ),
            social = PlaceSocial(emptyList(), emptyList(), 0, 0),
            loading = emptySet(),
        )

    private val loadingPage = PlacePageState(place = kunsthausRef)

    private val offline =
        rich.copy(
            posts = rich.posts.take(1),
            morePosts = false,
            photos = emptyList(),
            failed = setOf(PlacePart.Card, PlacePart.Social, PlacePart.Posts, PlacePart.Lists),
            savedAt = 1_795_597_920_000,
        )

    private val actions =
        PlacePageActions(
            onClose = {},
            onShare = {},
            openIn =
                listOf(
                    OpenInAction(OpenInApp.OrganicMaps, "Organic Maps", "om://map?ll=47.37,8.54", "app.organicmaps", inBrowser = false),
                    OpenInAction(OpenInApp.GoogleMaps, "Google Maps", "https://www.google.com/maps", null, inBrowser = true),
                ),
            onOpenIn = {},
            onOpenLink = {},
            onLoadMorePosts = {},
        )

    @Composable
    private fun Page(
        page: PlacePageState,
        stop: SheetStop = SheetStop.Half,
    ) {
        val dark = isSystemInDarkTheme()
        MapScreen(
            attribution =
                ServerDefaults.lists
                    .mapStyles(darkTheme = dark)
                    .first()
                    .attribution,
            mapUnavailable = false,
            onRetryMap = {},
            onMyLocation = {},
            onOpenSettings = {},
            placePage =
                PlacePageSlot(
                    full = stop == SheetStop.Full,
                    sheet = { maxHeight, inset ->
                        PlaceSheet(page, context, actions, stop, {}, maxHeight, bottomInset = inset)
                    },
                    panel = { modifier -> PlacePanel(page, context, actions, modifier) },
                ),
        ) {
            Box(
                modifier = Modifier.fillMaxSize().background(if (dark) Color(0xFF2B2D31) else Color(0xFFF2EFE9)),
                contentAlignment = Alignment.Center,
            ) {
                Text("Map", color = if (dark) Color(0xFF8A8D93) else Color(0xFF9A968E))
            }
        }
    }

    @Test
    fun richPhoneLight() = capture("place-rich", Device.Phone, dark = false) { Page(rich) }

    @Test
    fun richPhoneDark() = capture("place-rich", Device.Phone, dark = true) { Page(rich) }

    @Test
    fun richTabletLight() = capture("place-rich", Device.Tablet, dark = false) { Page(rich) }

    @Test
    fun richTabletDark() = capture("place-rich", Device.Tablet, dark = true) { Page(rich) }

    @Test
    fun richFullPhoneLight() = capture("place-rich-full", Device.Phone, dark = false) { Page(rich, SheetStop.Full) }

    @Test
    fun richSectionsLight() = capture("place-rich-sections", Device.TallPhone, dark = false) { Page(rich, SheetStop.Full) }

    @Test
    fun richSectionsDark() = capture("place-rich-sections", Device.TallPhone, dark = true) { Page(rich, SheetStop.Full) }

    @Test
    fun richPeekPhoneLight() = capture("place-rich-peek", Device.Phone, dark = false) { Page(rich, SheetStop.Peek) }

    @Test
    fun sparsePhoneLight() = capture("place-sparse", Device.Phone, dark = false) { Page(sparse) }

    @Test
    fun sparsePhoneDark() = capture("place-sparse", Device.Phone, dark = true) { Page(sparse) }

    @Test
    fun sparseTabletLight() = capture("place-sparse", Device.Tablet, dark = false) { Page(sparse) }

    @Test
    fun sparseTabletDark() = capture("place-sparse", Device.Tablet, dark = true) { Page(sparse) }

    @Test
    fun loadingPhoneLight() = capture("place-loading", Device.Phone, dark = false) { Page(loadingPage) }

    @Test
    fun loadingPhoneDark() = capture("place-loading", Device.Phone, dark = true) { Page(loadingPage) }

    @Test
    fun loadingTabletLight() = capture("place-loading", Device.Tablet, dark = false) { Page(loadingPage) }

    @Test
    fun loadingTabletDark() = capture("place-loading", Device.Tablet, dark = true) { Page(loadingPage) }

    @Test
    fun offlinePhoneLight() = capture("place-offline", Device.Phone, dark = false) { Page(offline) }

    @Test
    fun offlinePhoneDark() = capture("place-offline", Device.Phone, dark = true) { Page(offline) }

    @Test
    fun offlineTabletLight() = capture("place-offline", Device.Tablet, dark = false) { Page(offline) }

    @Test
    fun offlineTabletDark() = capture("place-offline", Device.Tablet, dark = true) { Page(offline) }
}
