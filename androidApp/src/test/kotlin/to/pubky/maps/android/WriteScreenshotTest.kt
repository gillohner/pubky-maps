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
import to.pubky.maps.account.AccountScreen
import to.pubky.maps.account.AccountState
import to.pubky.maps.config.ServerDefaults
import to.pubky.maps.links.GeoPoint
import to.pubky.maps.links.OsmId
import to.pubky.maps.links.OsmType
import to.pubky.maps.map.MapScreen
import to.pubky.maps.map.PlacePageSlot
import to.pubky.maps.place.PlacePageActions
import to.pubky.maps.place.PlacePageContext
import to.pubky.maps.place.PlacePageState
import to.pubky.maps.place.PlacePanel
import to.pubky.maps.place.PlacePerson
import to.pubky.maps.place.PlacePost
import to.pubky.maps.place.PlaceRef
import to.pubky.maps.place.PlaceSheet
import to.pubky.maps.place.PlaceSocial
import to.pubky.maps.place.PlaceSource
import to.pubky.maps.place.PlaceTag
import to.pubky.maps.place.PlaceWriteActions
import to.pubky.maps.place.SheetStop
import to.pubky.maps.ui.PubkyMapsTheme
import to.pubky.maps.write.Kept
import to.pubky.maps.write.ListChoice
import to.pubky.maps.write.ListPage
import to.pubky.maps.write.ListPageState
import to.pubky.maps.write.OwnList
import to.pubky.maps.write.OwnPlace
import to.pubky.maps.write.OwnPost
import to.pubky.maps.write.OwnTag
import to.pubky.maps.write.PhotoPage
import to.pubky.maps.write.PhotoPageState
import to.pubky.maps.write.PostPage
import to.pubky.maps.write.PreparedPhoto
import to.pubky.maps.write.SavedEntry
import to.pubky.maps.write.SavedRow
import to.pubky.maps.write.SavedScreen
import to.pubky.maps.write.SavedScreenState
import to.pubky.maps.write.TagPage
import to.pubky.maps.write.TagPageState
import to.pubky.maps.write.WriteIntent
import to.pubky.maps.write.signInReason

/**
 * Screenshots of the writes (phone 412x915 dp, tablet 1280x800 dp, light and dark): the place page
 * with its write actions and add entry points, the tag, post, photo and list pages, the Saved
 * screen, and the sign-in a write asks for first.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36])
@OptIn(ExperimentalRoborazziApi::class)
class WriteScreenshotTest {
    private enum class Device(
        val widthDp: Int,
        val heightDp: Int,
    ) {
        Phone(412, 915),
        Tablet(1280, 800),

        /** A phone as tall as the whole page, to see every section at once. */
        TallPhone(412, 1900),
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

    private fun all(
        name: String,
        devices: List<Device> = listOf(Device.Phone, Device.Tablet),
        content: @Composable () -> Unit,
    ) = devices.forEach { device -> listOf(false, true).forEach { dark -> capture(name, device, dark, content) } }

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
    private val osm = "https://www.openstreetmap.org/node/270803095"

    private val place =
        PlaceRef(
            uri = osm,
            name = "Conditorei Schober",
            point = GeoPoint(47.3722, 8.5446),
            osm = OsmId(OsmType.Node, 270803095),
            source = PlaceSource.Map,
            address = "Napfgasse 4, 8001 Zürich",
            category = "Café",
        )

    private val page =
        PlacePageState(
            place = place,
            social =
                PlaceSocial(
                    tags =
                        listOf(
                            PlaceTag("hot-chocolate", 5, listOf(anna), mine = false),
                            PlaceTag("cozy", 2, listOf(anna), mine = false),
                        ),
                    resourceIds = emptyList(),
                    replyCount = 1,
                    embedCount = 0,
                ),
            people = mapOf(anna to PlacePerson(anna, "Anna", null, followed = true), me to PlacePerson(me, "Gil", null, followed = false)),
            posts =
                listOf(
                    PlacePost(
                        "pubky://$anna/pub/social/v1/posts/A",
                        anna,
                        "note",
                        "The best hot chocolate in the old town.",
                        1_795_545_000_000,
                        emptyList(),
                    ),
                ),
            viewer = me,
            loading = emptySet(),
        )

    private val emptyPage = page.copy(social = PlaceSocial(emptyList(), emptyList(), 0, 0), posts = emptyList(), people = emptyMap())

    private val coffee = OwnList("pubky://$me/pub/social/v1/posts/L1", Kept.Public, "Coffee in Zürich", listOf(osm, "geo:47.37,8.54"))

    private fun writes(own: OwnPlace) =
        PlaceWriteActions(
            own = own,
            viewer = me,
            onToggleSave = {},
            onTag = {},
            onToggleTag = {},
            onPost = {},
            onPhoto = {},
            onAddToList = {},
        )

    private val ownPlace =
        OwnPlace(
            tags = listOf(OwnTag(osm, "cake", pending = true)),
            saved = listOf(SavedEntry(osm, Kept.Private, "Conditorei Schober")),
            lists = listOf(coffee),
            posts = listOf(OwnPost(osm, "Lovely terrace in summer, cosy inside in winter.", 1_795_600_000_000, uri = null)),
            photos = emptyList(),
            allLists = setOf(coffee.uri),
        )

    private fun actions(own: OwnPlace) =
        PlacePageActions(
            onClose = {},
            onShare = {},
            openIn = emptyList(),
            onOpenIn = {},
            onOpenLink = {},
            onLoadMorePosts = {},
            writes = writes(own),
        )

    @Composable
    private fun Page(
        page: PlacePageState,
        own: OwnPlace,
        stop: SheetStop = SheetStop.Half,
    ) {
        val dark = isSystemInDarkTheme()
        val actions = actions(own)
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
                    sheet = { maxHeight, inset -> PlaceSheet(page, context, actions, stop, {}, maxHeight, bottomInset = inset) },
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
    fun placeWithWrites() = all("write-place") { Page(page, ownPlace) }

    @Test
    fun placeWithWritesEverySection() = all("write-place-sections", listOf(Device.TallPhone)) { Page(page, ownPlace, SheetStop.Full) }

    @Test
    fun placeNothingYet() = all("write-place-empty", listOf(Device.TallPhone)) { Page(emptyPage, OwnPlace.None, SheetStop.Full) }

    @Test
    fun tagPage() =
        all("write-tag") {
            TagPage(
                state =
                    TagPageState(
                        placeName = "Conditorei Schober",
                        mine = listOf(OwnTag(osm, "cozy"), OwnTag(osm, "cake", pending = true)),
                        suggestions = listOf("hot-chocolate", "cafe", "coffee-shop", "cake-shop"),
                        text = "Café crème",
                    ),
                onTextChange = {},
                onAdd = {},
                onRemove = {},
                onClose = {},
            )
        }

    @Test
    fun postPage() =
        all("write-post") {
            PostPage(
                placeName = "Conditorei Schober",
                text = "Lovely terrace in summer, cosy inside in winter. The hot chocolate is thick and not too sweet.",
                onTextChange = {},
                onPost = {},
                onClose = {},
            )
        }

    @Test
    fun photoPageEmpty() =
        all("write-photo-choose") {
            PhotoPage(PhotoPageState(placeName = "Conditorei Schober"), {}, {}, {}, {}, {}, {})
        }

    @Test
    fun photoPageReady() =
        all("write-photo-ready") {
            PhotoPage(
                state =
                    PhotoPageState(
                        placeName = "Conditorei Schober",
                        // No bytes: the preview stays grey, so the screenshot never depends on decoding.
                        photo = PreparedPhoto(ByteArray(0), "image/jpeg", 4, 3, keptLocation = false, removedLocation = true),
                        alt = "Two cups of hot chocolate on a marble table",
                        caption = "Sunday afternoon",
                    ),
                onChoose = {},
                onTake = {},
                onAltChange = {},
                onCaptionChange = {},
                onPost = {},
                onClose = {},
            )
        }

    @Test
    fun listPage() =
        all("write-lists") {
            ListPage(
                state =
                    ListPageState(
                        placeName = "Conditorei Schober",
                        lists =
                            listOf(
                                ListChoice(coffee, holds = true),
                                ListChoice(
                                    OwnList("pubky://$me/priv/social/v1/posts/L2", Kept.Private, "Date ideas", listOf("geo:1,2")),
                                    holds = false,
                                ),
                                ListChoice(OwnList("phone:1", Kept.Phone, "Sunday walks", emptyList()), holds = false),
                                ListChoice(
                                    OwnList("pubky://$me/pub/social/v1/posts/L3", Kept.Public, "Old town", (1..100).map { "geo:$it,1" }),
                                    holds = false,
                                ),
                            ),
                        newName = "Cake",
                        newKept = Kept.Public,
                    ),
                onToggle = {},
                onNewNameChange = {},
                onNewKeptChange = {},
                onCreate = {},
                onClose = {},
            )
        }

    @Test
    fun savedScreen() =
        all("write-saved") {
            SavedScreen(
                state =
                    SavedScreenState(
                        places =
                            listOf(
                                SavedRow(osm, "Conditorei Schober", Kept.Private),
                                SavedRow("https://maps.app.goo.gl/x1", "Lindenhof", Kept.Private, pending = true),
                                SavedRow("geo:47.36667,8.55", null, Kept.Phone),
                            ),
                        lists = listOf(coffee, OwnList("phone:1", Kept.Phone, "Sunday walks", emptyList())),
                        signedIn = true,
                    ),
                onOpen = {},
                onBack = {},
            )
        }

    @Test
    fun savedScreenEmpty() =
        all("write-saved-empty", listOf(Device.Phone)) {
            SavedScreen(SavedScreenState(places = emptyList(), lists = emptyList(), signedIn = false), onOpen = {}, onBack = {})
        }

    @Test
    fun signInFirst() =
        all("write-sign-in-first") {
            AccountScreen(
                state = AccountState.SignedOut(),
                ringOnThisPhone = true,
                onSignIn = {},
                onOpenRing = {},
                onCancelSignIn = {},
                onRefresh = {},
                onSignOut = {},
                onBack = {},
                reason = WriteIntent.Tag.signInReason,
            )
        }
}
