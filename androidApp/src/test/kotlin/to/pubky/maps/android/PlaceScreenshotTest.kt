package to.pubky.maps.android

import android.content.res.Configuration
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import com.github.takahirom.roborazzi.ExperimentalRoborazziApi
import com.github.takahirom.roborazzi.RoborazziComposeOptions
import com.github.takahirom.roborazzi.captureRoboImage
import com.github.takahirom.roborazzi.fontScale
import com.github.takahirom.roborazzi.size
import com.github.takahirom.roborazzi.uiMode
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode
import to.pubky.maps.config.OpenInApp
import to.pubky.maps.config.ServerDefaults
import to.pubky.maps.links.GeoPoint
import to.pubky.maps.links.OpenInAction
import to.pubky.maps.links.OsmId
import to.pubky.maps.links.OsmType
import to.pubky.maps.map.MapScreen
import to.pubky.maps.map.PlacePageSlot
import to.pubky.maps.map.droppedPin
import to.pubky.maps.map.routeCredits
import to.pubky.maps.place.AlsoOnLink
import to.pubky.maps.place.KeptDetails
import to.pubky.maps.place.LocalRemoteImage
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
import to.pubky.maps.place.RemoteImageContent
import to.pubky.maps.place.SheetStop
import to.pubky.maps.routing.Directions
import to.pubky.maps.routing.DirectionsUi
import to.pubky.maps.routing.NoRouteReason
import to.pubky.maps.routing.Route
import to.pubky.maps.routing.RouteStatus
import to.pubky.maps.routing.RouteStep
import to.pubky.maps.routing.TravelMode
import to.pubky.maps.routing.Turn
import to.pubky.maps.ui.PubkyMapsTheme

/**
 * Screenshots of the place page (phone 412x915 dp, tablet 1280x800 dp, light and dark): a place with
 * everything (Pinakes card with photo and OSM details, Pubky tags with the user's own, photos, posts,
 * lists, Also on), a sparse place (a Photon address only), one still loading, one shown offline
 * from the cache, and a pin dropped by a long-press. Photos are grey: images load over the network,
 * which the tests do not use.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
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

        /** A phone's half of the screen in split screen, where a header at a large font is taller than half the sheet. */
        SplitPhone(412, 450),
    }

    private fun capture(
        name: String,
        device: Device,
        dark: Boolean,
        imagesFail: Boolean = false,
        largeFont: Boolean = false,
        content: @Composable () -> Unit,
    ) {
        val theme = if (dark) "dark" else "light"
        val variant = if (largeFont) "-large-font" else ""
        captureRoboImage(
            filePath = "screenshots/$name$variant-${device.name.lowercase()}-$theme.png",
            roborazziComposeOptions =
                RoborazziComposeOptions {
                    size(widthDp = device.widthDp, heightDp = device.heightDp)
                    uiMode(if (dark) Configuration.UI_MODE_NIGHT_YES else Configuration.UI_MODE_NIGHT_NO)
                    if (largeFont) fontScale(2f)
                },
        ) {
            PubkyMapsTheme {
                CompositionLocalProvider(LocalRemoteImage provides if (imagesFail) FailingImage else StandInImage) { content() }
            }
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
            PlacePerson(anna, "Anna", "https://example.invalid/anna.jpg", followed = true),
            PlacePerson(ben, "Ben", null, followed = false),
        ).associateBy { it.id }

    private fun photo(n: Int) = PlacePhoto(anna, "https://example.invalid/$n.jpg", "https://example.invalid/$n-feed.jpg")

    internal val rich =
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
                    category = "Bakery",
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

    /**
     * A café tapped on the map of a downloaded area with no connection, its page never opened before:
     * the OSM details the area keeps, fetched the day before.
     */
    private val tappedOffline =
        PlacePageState(
            place =
                PlaceRef(
                    uri = "https://www.openstreetmap.org/node/4285164389",
                    name = "Café Schurter",
                    point = GeoPoint(47.373380, 8.544260),
                    osm = OsmId(OsmType.Node, 4285164389),
                    source = PlaceSource.Map,
                    category = "Café",
                ),
            loading = emptySet(),
            failed = setOf(PlacePart.Card, PlacePart.Social, PlacePart.Posts, PlacePart.Lists),
            locality = "Altstadt, Zürich",
            keptDetails =
                KeptDetails(
                    mapOf(
                        "opening_hours" to "Mo-Fr 08:00-18:30; Sa 08:00-17:00",
                        "phone" to "+41 44 251 00 00",
                        "website" to "https://www.cafe-schurter.ch",
                        "wheelchair" to "limited",
                    ),
                    fetchedAt = 1_795_510_000_000,
                ),
        )

    /**
     * A pin dropped by a long-press on a path above Zürich in a downloaded area: its coordinates, the
     * district and town the area's labels give, no server asked.
     */
    private val droppedPinPage =
        PlacePageState(
            place = droppedPin(47.38143, 8.56922),
            loading = emptySet(),
            locality = "Fluntern, Zürich",
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
            onDirections = {},
        )

    /** Directions to the Kunsthaus from Zürich HB, in [status]; found: FOSSGIS' route by car. */
    private fun directionsTo(
        status: RouteStatus,
        mode: TravelMode = TravelMode.Car,
        stepsShown: Boolean = false,
    ) = actions.copy(
        directions =
            DirectionsUi(
                directions =
                    Directions(kunsthausRef.uri, kunsthausRef.point!!, mode, from = GeoPoint(47.3779, 8.5403), status, stepsShown),
                onMode = {},
                onSteps = {},
                onClose = {},
                onRetry = {},
                onOpenSettings = {},
                credits = routeCredits(ServerDefaults.lists, null),
            ),
    )

    private val foundRoute =
        RouteStatus.Found(
            Route(
                listOf(GeoPoint(47.3779, 8.5403), kunsthausRef.point!!),
                seconds = 289.373,
                meters = 1401.0,
                steps =
                    listOf(
                        RouteStep("Drive north.", Turn.Depart, 35.0),
                        RouteStep("Turn right onto Museumstrasse.", Turn.Right, 52.0),
                        RouteStep("Bear right onto Bahnhofquai.", Turn.SlightRight, 30.0),
                        RouteStep("Keep left to stay on Bahnhofquai.", Turn.KeepLeft, 406.0),
                        RouteStep("Turn left onto Rudolf-Brun-Brücke.", Turn.Left, 329.0),
                        RouteStep("Turn right onto Seilergraben.", Turn.Right, 205.0),
                        RouteStep("Bear right onto Hirschengraben.", Turn.SlightRight, 262.0),
                        RouteStep("Bear right onto Heimplatz.", Turn.SlightRight, 81.0),
                        RouteStep("Your destination is on the right.", Turn.Arrive, 0.0),
                    ),
            ),
            server = "https://valhalla1.openstreetmap.de",
        )

    @Composable
    internal fun Page(
        page: PlacePageState,
        stop: SheetStop = SheetStop.Half,
        actions: PlacePageActions = this.actions,
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
    fun noPhotoPhoneLight() = capture("place-no-photo", Device.Phone, dark = false, imagesFail = true) { Page(rich) }

    @Test
    fun richPeekPhoneLight() = capture("place-rich-peek", Device.Phone, dark = false) { Page(rich, SheetStop.Peek) }

    @Test
    fun richPeekLargeFontPhoneLight() =
        capture("place-rich-peek", Device.Phone, dark = false, largeFont = true) { Page(rich, SheetStop.Peek) }

    @Test
    fun richFullLargeFontSplitPhoneLight() =
        capture("place-rich-full", Device.SplitPhone, dark = false, largeFont = true) { Page(rich, SheetStop.Full) }

    @Test
    fun richFullLargeFontSplitPhoneDark() =
        capture("place-rich-full", Device.SplitPhone, dark = true, largeFont = true) { Page(rich, SheetStop.Full) }

    @Test
    fun sparsePhoneLight() = capture("place-sparse", Device.Phone, dark = false) { Page(sparse) }

    @Test
    fun sparseFullPhoneLight() = capture("place-sparse-full", Device.Phone, dark = false) { Page(sparse, SheetStop.Full) }

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
    fun tappedOfflinePhoneLight() = capture("place-tapped-offline", Device.Phone, dark = false) { Page(tappedOffline) }

    @Test
    fun tappedOfflinePhoneDark() = capture("place-tapped-offline", Device.Phone, dark = true) { Page(tappedOffline) }

    @Test
    fun tappedOfflineTabletLight() = capture("place-tapped-offline", Device.Tablet, dark = false) { Page(tappedOffline) }

    @Test
    fun tappedOfflineTabletDark() = capture("place-tapped-offline", Device.Tablet, dark = true) { Page(tappedOffline) }

    @Test
    fun directionsPhoneLight() = capture("place-directions", Device.Phone, dark = false) { Page(rich, actions = directionsTo(foundRoute)) }

    @Test
    fun directionsPhoneDark() = capture("place-directions", Device.Phone, dark = true) { Page(rich, actions = directionsTo(foundRoute)) }

    @Test
    fun directionsTabletLight() =
        capture("place-directions", Device.Tablet, dark = false) { Page(rich, actions = directionsTo(foundRoute)) }

    @Test
    fun directionsTabletDark() = capture("place-directions", Device.Tablet, dark = true) { Page(rich, actions = directionsTo(foundRoute)) }

    @Test
    fun directionsPeekLargeFontPhoneLight() =
        capture("place-directions-peek", Device.Phone, dark = false, largeFont = true) {
            Page(rich, SheetStop.Peek, directionsTo(foundRoute, TravelMode.Bike))
        }

    @Test
    fun directionsStepsPhoneLight() =
        capture("place-directions-steps", Device.Phone, dark = false) {
            Page(rich, SheetStop.Full, directionsTo(foundRoute, stepsShown = true))
        }

    @Test
    fun directionsStepsPhoneDark() =
        capture("place-directions-steps", Device.Phone, dark = true) {
            Page(rich, SheetStop.Full, directionsTo(foundRoute, stepsShown = true))
        }

    @Test
    fun directionsStepsTabletLight() =
        capture("place-directions-steps", Device.Tablet, dark = false) { Page(rich, actions = directionsTo(foundRoute, stepsShown = true)) }

    @Test
    fun directionsStepsTabletDark() =
        capture("place-directions-steps", Device.Tablet, dark = true) { Page(rich, actions = directionsTo(foundRoute, stepsShown = true)) }

    @Test
    fun directionsStepsLargeFontPhoneLight() =
        capture("place-directions-steps", Device.Phone, dark = false, largeFont = true) {
            Page(rich, SheetStop.Full, directionsTo(foundRoute, stepsShown = true))
        }

    @Test
    fun directionsFindingPhoneLight() =
        capture("place-directions-finding", Device.Phone, dark = false) {
            Page(sparse, actions = directionsTo(RouteStatus.Finding, TravelMode.Walk))
        }

    @Test
    fun directionsNoLocationPhoneDark() =
        capture("place-directions-no-location", Device.Phone, dark = true) {
            Page(sparse, actions = directionsTo(RouteStatus.NoLocation(permissionOff = true)))
        }

    @Test
    fun directionsTooFarPhoneLight() =
        capture("place-directions-too-far", Device.Phone, dark = false) {
            Page(sparse, actions = directionsTo(RouteStatus.NoRoute(NoRouteReason.TooFar), TravelMode.Walk))
        }

    @Test
    fun droppedPinPhoneLight() = capture("place-dropped-pin", Device.Phone, dark = false) { Page(droppedPinPage, SheetStop.Peek) }

    @Test
    fun droppedPinPhoneDark() = capture("place-dropped-pin", Device.Phone, dark = true) { Page(droppedPinPage, SheetStop.Peek) }

    @Test
    fun droppedPinTabletLight() = capture("place-dropped-pin", Device.Tablet, dark = false) { Page(droppedPinPage) }

    @Test
    fun droppedPinTabletDark() = capture("place-dropped-pin", Device.Tablet, dark = true) { Page(droppedPinPage) }

    @Test
    fun droppedPinHalfPhoneLight() = capture("place-dropped-pin-half", Device.Phone, dark = false) { Page(droppedPinPage) }

    @Test
    fun offlinePhoneLight() = capture("place-offline", Device.Phone, dark = false) { Page(offline) }

    @Test
    fun offlinePhoneDark() = capture("place-offline", Device.Phone, dark = true) { Page(offline) }

    @Test
    fun offlineTabletLight() = capture("place-offline", Device.Tablet, dark = false) { Page(offline) }

    @Test
    fun offlineTabletDark() = capture("place-offline", Device.Tablet, dark = true) { Page(offline) }
}

/** Images load over the network, which tests do not use: a drawn stand-in (sky over a roof line). */
private val StandInImage: RemoteImageContent = { _, _, modifier, _, _ ->
    Box(modifier.background(Brush.verticalGradient(listOf(Color(0xFF8DB6D9), Color(0xFFC9DCEB), Color(0xFF7A6A5A)))))
}

/** An image that cannot be loaded: the page leaves it out. */
internal val FailingImage: RemoteImageContent = { _, _, _, _, onError ->
    SideEffect { onError() }
}
