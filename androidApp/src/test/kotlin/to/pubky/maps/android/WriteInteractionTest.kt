package to.pubky.maps.android

import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.github.takahirom.roborazzi.ExperimentalRoborazziApi
import com.github.takahirom.roborazzi.captureScreenRoboImage
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import to.pubky.maps.links.GeoPoint
import to.pubky.maps.place.PlacePageActions
import to.pubky.maps.place.PlacePageContext
import to.pubky.maps.place.PlacePageState
import to.pubky.maps.place.PlacePanel
import to.pubky.maps.place.PlaceRef
import to.pubky.maps.place.PlaceSocial
import to.pubky.maps.place.PlaceSource
import to.pubky.maps.place.PlaceTag
import to.pubky.maps.place.PlaceWriteActions
import to.pubky.maps.ui.PubkyMapsTheme
import to.pubky.maps.write.OwnPlace
import to.pubky.maps.write.OwnTag
import to.pubky.maps.write.TagPage
import to.pubky.maps.write.TagPageState

private const val PHONE = "w412dp-h915dp"
private const val TABLET = "w1280dp-h800dp"

/**
 * One-tap tags driven the way a user drives them: the first tap on a tag chip asks once whether the
 * user knows tags are public (a dialog over the page, captured as the whole screen), later taps tag at
 * once; and the tag page's Undo snackbar.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36])
@OptIn(ExperimentalRoborazziApi::class)
class WriteInteractionTest {
    @get:Rule
    val compose = createComposeRule()

    private val osm = "https://www.openstreetmap.org/node/270803095"
    private val toggled = mutableListOf<String>()
    private var explained by mutableStateOf(false)

    private val page =
        PlacePageState(
            place = PlaceRef(osm, "Conditorei Schober", GeoPoint(47.3722, 8.5446), null, PlaceSource.Map, category = "Café"),
            social =
                PlaceSocial(
                    tags = listOf(PlaceTag("hot-chocolate", 5, emptyList(), mine = false), PlaceTag("cozy", 2, emptyList(), mine = false)),
                    resourceIds = emptyList(),
                    replyCount = 0,
                    embedCount = 0,
                ),
            loading = emptySet(),
        )

    private val context =
        PlacePageContext(
            now = LocalDateTime(LocalDate.parse("2026-11-25"), LocalTime(12, 0)),
            nowMillis = 1_795_608_000_000,
            timeZone = TimeZone.UTC,
            language = "en",
            distance = null,
        )

    private fun showPlace() {
        compose.setContent {
            PubkyMapsTheme {
                PlacePanel(
                    page,
                    context,
                    PlacePageActions(
                        onClose = {},
                        onShare = null,
                        openIn = emptyList(),
                        onOpenIn = {},
                        onOpenLink = {},
                        onLoadMorePosts = {},
                        writes =
                            PlaceWriteActions(
                                own = OwnPlace.None,
                                viewer = "8pinxsr5ctu1ou5ruzomw4fiz4fe1jybsr6o9dnb8jxcc7bpc3wo",
                                onToggleSave = {},
                                onTag = {},
                                onToggleTag = { toggled += it },
                                onPost = {},
                                onPhoto = {},
                                onAddToList = {},
                                publicTagsExplained = explained,
                                onPublicTagsExplained = { explained = true },
                            ),
                    ),
                )
            }
        }
    }

    private fun firstOneTapTag(name: String) {
        showPlace()
        compose.onNodeWithText("hot-chocolate").performClick()
        compose.onNodeWithText("Tags are public").assertExists()
        assertEquals("nothing is sent before the user says so", emptyList<String>(), toggled)
        captureScreenRoboImage("screenshots/$name.png")
    }

    @Test
    @Config(qualifiers = "$PHONE-notnight-mdpi")
    fun publicTagDialogPhoneLight() = firstOneTapTag("write-public-tag-phone-light")

    @Test
    @Config(qualifiers = "$PHONE-night-mdpi")
    fun publicTagDialogPhoneDark() = firstOneTapTag("write-public-tag-phone-dark")

    @Test
    @Config(qualifiers = "$TABLET-notnight-mdpi")
    fun publicTagDialogTabletLight() = firstOneTapTag("write-public-tag-tablet-light")

    @Test
    @Config(qualifiers = "$TABLET-night-mdpi")
    fun publicTagDialogTabletDark() = firstOneTapTag("write-public-tag-tablet-dark")

    @Test
    @Config(qualifiers = "$PHONE-notnight-mdpi")
    fun confirmingTagsAndLaterTapsTagAtOnce() {
        showPlace()
        compose.onNodeWithText("hot-chocolate").performClick()
        compose.onNodeWithText("Tag it").performClick()

        assertEquals(listOf("hot-chocolate"), toggled)
        assertEquals(true, explained)
        compose.onNodeWithText("Tags are public").assertDoesNotExist()

        compose.onNodeWithText("cozy").performClick()
        compose.onNodeWithText("Tags are public").assertDoesNotExist()
        assertEquals(listOf("hot-chocolate", "cozy"), toggled)
    }

    @Test
    @Config(qualifiers = "$PHONE-notnight-mdpi")
    fun cancellingTagsNothing() {
        showPlace()
        compose.onNodeWithText("hot-chocolate").performClick()
        compose.onNodeWithText("Cancel").performClick()

        assertEquals(emptyList<String>(), toggled)
        assertEquals(false, explained)
    }

    private fun tagPageWithUndo(name: String) {
        compose.setContent {
            PubkyMapsTheme {
                val host = remember { SnackbarHostState() }
                LaunchedEffect(Unit) { host.showSnackbar("Tagged “cozy”. Tags are public.", "Undo") }
                TagPage(
                    state =
                        TagPageState(
                            placeName = "Conditorei Schober",
                            mine = listOf(OwnTag(osm, "cozy", pending = true)),
                            suggestions = listOf("hot-chocolate", "cafe"),
                        ),
                    onTextChange = {},
                    onAdd = {},
                    onRemove = {},
                    onRetry = {},
                    onClose = {},
                    snackbarHostState = host,
                )
            }
        }
        compose.onNodeWithText("Undo").assertExists()
        captureScreenRoboImage("screenshots/$name.png")
    }

    @Test
    @Config(qualifiers = "$PHONE-notnight-mdpi")
    fun tagUndoPhoneLight() = tagPageWithUndo("write-tag-undo-phone-light")

    @Test
    @Config(qualifiers = "$PHONE-night-mdpi")
    fun tagUndoPhoneDark() = tagPageWithUndo("write-tag-undo-phone-dark")

    @Test
    @Config(qualifiers = "$TABLET-notnight-mdpi")
    fun tagUndoTabletLight() = tagPageWithUndo("write-tag-undo-tablet-light")

    @Test
    @Config(qualifiers = "$TABLET-night-mdpi")
    fun tagUndoTabletDark() = tagPageWithUndo("write-tag-undo-tablet-dark")
}
