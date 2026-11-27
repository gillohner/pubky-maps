package to.pubky.maps.write

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import to.pubky.maps.account.AccountState
import to.pubky.maps.account.AccountUser
import to.pubky.maps.account.Connection
import to.pubky.maps.account.CoreFailure
import to.pubky.maps.cache.CachedAccount
import to.pubky.maps.cache.CachedBookmark
import to.pubky.maps.cache.CachedTag
import to.pubky.maps.config.AppSettings
import to.pubky.maps.config.DataKind
import to.pubky.maps.config.StorageChoice
import to.pubky.maps.links.GeoPoint
import to.pubky.maps.place.PlaceSource
import kotlin.coroutines.ContinuationInterceptor
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

private const val ME = "8pinxxgqs41n4aididenw5apqp1urfmzdztr8jt4abrkdn435ewo"
private const val OSM = "https://www.openstreetmap.org/node/270803095"
private const val GOOGLE = "https://maps.app.goo.gl/x1"
private val SCHOBER = GeoPoint(47.37159, 8.54531)
private const val TAGGED_COFFEE = "Tagged “coffee”. Tags are public."

@OptIn(ExperimentalCoroutinesApi::class)
class WriteControllerTest {
    private val writes = FakePubkyWrites()
    private val store = MemoryWriteStore()
    private val phone = MemoryPhoneStore()
    private val files = MemoryOutboxFiles()
    private val account = MutableStateFlow<AccountState>(AccountState.Loading)
    private var settings = AppSettings()
    private var accountRefreshes = 0
    private val messages = mutableListOf<String>()
    private val actions = mutableListOf<WriteMessage>()
    private val target = WriteTarget(OSM, "Conditorei Schober", SCHOBER)

    private fun signedIn(
        connection: Connection = Connection.Online,
        tags: List<CachedTag> = emptyList(),
    ) {
        account.value = AccountState.SignedIn(AccountUser(ME, "Ada"), CachedAccount(ME, "Ada", 500, tags, emptyList()), connection)
    }

    private val cached: CachedAccount get() = (account.value as AccountState.SignedIn).cached

    /** Calls to the homeserver other than reading the lists. */
    private val sent: List<String> get() = writes.calls.filterNot { it.startsWith("lists(") }

    private fun TestScope.controller(): WriteController {
        val controller =
            WriteController(
                writes = { writes },
                store = store,
                phone = phone,
                files = files,
                account = account,
                updateAccount = { change ->
                    (account.value as? AccountState.SignedIn)?.let { account.value = it.copy(cached = change(it.cached)) }
                },
                refreshAccount = { accountRefreshes++ },
                settings = { settings },
                scope = backgroundScope,
                io = StandardTestDispatcher(testScheduler),
                now = { 1_000L },
            )
        backgroundScope.launch {
            controller.messages.collect {
                messages += it.text
                actions += it
            }
        }
        runCurrent()
        return controller
    }

    @Test
    fun aTagShowsAtOnceThenGoesToTheHomeserverAndIntoTheCache() =
        runTest {
            signedIn()
            val writer = controller()
            val homeserver = CompletableDeferred<Unit>()
            writes.gate = homeserver

            writer.tag(target, "coffee")
            runCurrent()

            assertEquals(listOf(OwnTag(OSM, "coffee", pending = true)), writer.own.value.tags)
            assertEquals(1, writer.own.value.pending)
            assertEquals(listOf("tag($OSM, coffee)"), sent)

            homeserver.complete(Unit)
            runCurrent()

            assertEquals(emptyList(), store.outbox)
            assertEquals(listOf("coffee"), cached.tags.map { it.label })
            assertEquals(listOf(OwnTag(OSM, "coffee")), writer.own.value.tags)
            assertEquals(listOf(TAGGED_COFFEE), messages, "said with Undo")
        }

    @Test
    fun writesGoOnTheLinkTheUserHad() =
        runTest {
            signedIn()
            val writer = controller()
            val pasted = WriteTarget(GOOGLE, "Pasted place")

            writer.tag(pasted, "view")
            writer.post(pasted, "  Great view from the top.  ")
            writer.save(pasted)
            runCurrent()

            assertEquals(listOf("post($GOOGLE, Great view from the top.)"), writes.builds)
            assertEquals(
                listOf("tag($GOOGLE, view)", "put(/pub/social/v1/posts/P1/P1.json)", "save($GOOGLE)"),
                sent,
                "each write once, in order",
            )
            assertEquals("Pasted place", store.names[GOOGLE], "the name is kept for the Saved screen")
        }

    @Test
    fun removingATagDeletesItFromTheLinkItWasPutOn() =
        runTest {
            signedIn(tags = listOf(CachedTag("/pub/social/v1/tags/T.json", GOOGLE, "coffee", 1)))
            val writer = controller()

            writer.untag(target, links = setOf(OSM, GOOGLE), label = "coffee")
            runCurrent()

            assertEquals(listOf("untag($GOOGLE, coffee)"), sent)
            assertEquals(emptyList(), cached.tags)
        }

    @Test
    fun aWriteWithoutAConnectionWaitsInTheOutboxAndIsSentLater() =
        runTest {
            signedIn()
            val writer = controller()
            writes.failures += CoreFailure.Network("no route to host")

            writer.tag(target, "coffee")
            runCurrent()

            assertEquals(1, store.outbox.size)
            assertEquals(listOf(OwnTag(OSM, "coffee", pending = true)), writer.own.value.tags, "it stays on screen")
            assertTrue(writer.own.value.offline)
            assertEquals(listOf(TAGGED_COFFEE, OFFLINE_MESSAGE), messages)

            advanceTimeBy(16.seconds)
            runCurrent()

            assertEquals(emptyList(), store.outbox)
            assertEquals(2, sent.size)
            assertFalse(writer.own.value.offline)
            assertEquals(listOf(TAGGED_COFFEE, OFFLINE_MESSAGE), messages, "said once")
        }

    @Test
    fun flushSendsAtOnceWhenThePhoneIsOnlineAgain() =
        runTest {
            signedIn()
            val writer = controller()
            writes.failures += CoreFailure.Network("offline")
            writer.post(target, "Best espresso in town.")
            runCurrent()
            assertEquals(1, store.outbox.size)

            writer.flush()
            runCurrent()

            assertEquals(emptyList(), store.outbox)
            assertEquals(
                listOf("Best espresso in town."),
                writer.own.value.posts
                    .map { it.text },
            )
            assertEquals(
                "pubky://$ME/pub/social/v1/posts/P1",
                writer.own.value.posts
                    .single()
                    .uri,
            )
            // Sent again to the same path: a retry after a lost answer makes no second post.
            assertEquals(listOf("put(/pub/social/v1/posts/P1/P1.json)", "put(/pub/social/v1/posts/P1/P1.json)"), sent)
        }

    @Test
    fun aWriteTheHomeserverRefusesIsTakenBackWithAMessage() =
        runTest {
            signedIn()
            val writer = controller()
            writes.failures += CoreFailure.Other("label: at most 20 characters")

            writer.tag(target, "coffee")
            runCurrent()

            assertEquals(emptyList(), writer.own.value.tags)
            assertEquals(emptyList(), store.outbox)
            assertEquals(listOf(TAGGED_COFFEE, "Couldn't add the tag “coffee”: label: at most 20 characters"), messages)
        }

    @Test
    fun anEndedSessionDropsTheWriteAndChecksTheAccount() =
        runTest {
            signedIn()
            val writer = controller()
            writes.failures += CoreFailure.Auth("grant expired")

            writer.save(target)
            runCurrent()

            assertEquals(emptyList(), store.outbox)
            assertEquals(1, accountRefreshes)
            assertEquals(listOf(SESSION_ENDED_MESSAGE), messages)
            assertFalse(
                writer.own.value
                    .forPlace(setOf(OSM))
                    .isSaved,
            )
        }

    @Test
    fun writesWaitWhileTheHomeserverIsUnreachable() =
        runTest {
            signedIn(Connection.Offline)
            val writer = controller()

            writer.tag(target, "coffee")
            runCurrent()
            assertEquals(emptyList(), sent)
            assertEquals(1, writer.own.value.pending)

            signedIn(Connection.Online)
            runCurrent()

            assertEquals(listOf("tag($OSM, coffee)"), sent)
            assertEquals(0, writer.own.value.pending)
        }

    @Test
    fun waitingIsUnknownUntilTheAccountAndItsOutboxAreRead() =
        runTest {
            store.enqueue(WriteOp.Tag(OSM, "cake"))
            val writer = controller()
            assertNull(writer.waiting.value, "a start that still restores the session knows nothing yet")

            signedIn(Connection.Offline)
            runCurrent()
            assertEquals(true, writer.waiting.value, "the outbox read at start waits")

            signedIn(Connection.Online)
            runCurrent()
            assertEquals(listOf("tag($OSM, cake)"), sent)
            assertEquals(false, writer.waiting.value)
        }

    @Test
    fun waitingFollowsTheOutboxAndEndsWithASignOut() =
        runTest {
            signedIn()
            val writer = controller()
            assertEquals(false, writer.waiting.value)
            writes.failures += CoreFailure.Network("offline")

            writer.tag(target, "coffee")
            runCurrent()
            assertEquals(true, writer.waiting.value, "a write without a connection waits")

            account.value = (account.value as AccountState.SignedIn).copy(signingOut = true)
            runCurrent()
            assertEquals(false, writer.waiting.value, "a signed-out outbox is never sent")

            account.value = AccountState.SignedOut()
            runCurrent()
            assertEquals(false, writer.waiting.value)
        }

    @Test
    fun theOutboxIsSentAfterTheAppStartsAgain() =
        runTest {
            store.enqueue(WriteOp.Tag(OSM, "cake"))
            store.enqueue(WriteOp.Unsave(GOOGLE))
            signedIn()

            controller()

            assertEquals(listOf("tag($OSM, cake)", "unsave($GOOGLE)"), sent)
            assertEquals(emptyList(), store.outbox)
        }

    @Test
    fun savedPlacesKeptOnThePhoneNeverGoToTheHomeserverAndNeedNoSignIn() =
        runTest {
            settings = AppSettings().withStorage(DataKind.SavedPlaces, StorageChoice.PhoneOnly)
            account.value = AccountState.SignedOut()
            val writer = controller()
            assertFalse(writer.needsSignIn(WriteIntent.Save))

            writer.save(target)
            runCurrent()

            assertEquals(listOf(PhoneSaved(OSM, "Conditorei Schober", 1_000L, SCHOBER)), phone.data.saved)
            assertEquals(listOf(SavedEntry(OSM, Kept.Phone, "Conditorei Schober", point = SCHOBER)), writer.own.value.saved)
            assertEquals(emptyList(), writes.calls)

            writer.unsave(target, setOf(OSM))
            runCurrent()
            assertEquals(emptyList(), phone.data.saved)
            assertEquals(emptyList(), writes.calls)
        }

    @Test
    fun savedPlacesGoPrivatelyToTheHomeserverByDefault() =
        runTest {
            account.value = AccountState.SignedOut()
            val writer = controller()
            assertTrue(writer.needsSignIn(WriteIntent.Save), "the default keeps saved places on the homeserver")

            signedIn()
            runCurrent()
            writer.save(target)
            runCurrent()

            assertEquals(listOf("save($OSM)"), sent)
            assertEquals(listOf(OSM), cached.bookmarks.map { it.place })
            assertEquals(
                Kept.Private,
                writer.own.value.saved
                    .single()
                    .kept,
            )
            assertEquals(emptyList(), phone.data.saved)
        }

    @Test
    fun aPointSavedFromItsOwnPageIsKeptAsOneOnThePhoneUntilSavedFromAnotherPage() =
        runTest {
            signedIn()
            val writer = controller()
            runCurrent()
            val pin = GeoPoint(47.37031, 8.55601)
            val home = WriteTarget("geo:47.37031,8.55601", "Home", pin, pointPage = true)

            // A dropped pin saved on the homeserver: the phone keeps that it is the user's own point.
            writer.save(home)
            runCurrent()
            assertEquals(listOf("save(geo:47.37031,8.55601)"), sent)
            assertEquals(setOf("geo:47.37031,8.55601"), phone.data.pointPlaces)
            assertEquals(setOf("geo:47.37031,8.55601"), writer.own.value.pointPlaces)
            assertEquals(
                SavedEntry("geo:47.37031,8.55601", Kept.Private, "Home", point = pin, pointPage = true),
                writer.own.value.saved
                    .single(),
            )
            // Put in a phone list from its page too, and added to it; also after a sign-out and the next start.
            writer.createList(WriteTarget("geo:47.36,8.55", null, pointPage = true), "Walks", Kept.Phone)
            runCurrent()
            val walks =
                writer.own.value.lists
                    .single()
            writer.addToList(WriteTarget("geo:47.35,8.54", null, pointPage = true), walks)
            runCurrent()
            assertEquals(setOf("geo:47.37031,8.55601", "geo:47.36,8.55", "geo:47.35,8.54"), phone.data.pointPlaces)
            writer.removeFromList(WriteTarget("geo:47.35,8.54", null, pointPage = true), setOf("geo:47.35,8.54"), walks)
            runCurrent()
            account.value = AccountState.SignedOut()
            runCurrent()
            assertEquals(
                setOf("geo:47.37031,8.55601", "geo:47.36,8.55", "geo:47.35,8.54"),
                controller()
                    .also { runCurrent() }
                    .own.value.pointPlaces,
                "a place taken out of a list keeps its mark: it may still be saved",
            )

            // The same link saved again from a public page (a link from another app, Pinakes): read as a link again.
            signedIn()
            runCurrent()
            writer.save(WriteTarget("geo:47.37031,8.55601", "Kunsthalle", pin))
            runCurrent()
            assertEquals(setOf("geo:47.36,8.55", "geo:47.35,8.54"), phone.data.pointPlaces)
            assertEquals(
                false,
                writer.own.value.saved
                    .single()
                    .pointPage,
            )
            // Places that are no geo: link are never kept as points.
            writer.save(target.copy(pointPage = true))
            runCurrent()
            assertEquals(setOf("geo:47.36,8.55", "geo:47.35,8.54"), phone.data.pointPlaces)
        }

    @Test
    fun theSavedPageOpensOnlyAPointSavedFromItsOwnPageWithoutAskingAnyServer() =
        runTest {
            signedIn()
            val writer = controller()
            runCurrent()
            // A dropped pin saved from its page, and a geo: link from another app saved from its public page.
            writer.save(WriteTarget("geo:47.37031,8.55601", "Home", pointPage = true))
            writer.save(WriteTarget("geo:47.36,8.55", "Kunsthalle"))
            writer.createList(WriteTarget("geo:47.35,8.54", null, pointPage = true), "Walks", Kept.Phone)
            runCurrent()
            val own = writer.own.value

            val home = savedPagePoint("geo:47.37031,8.55601", "Home", own)
            assertEquals(PlaceSource.Coordinates, home?.source)
            assertEquals("Home", home?.name)
            assertEquals(GeoPoint(47.37031, 8.55601), home?.point)
            assertEquals(PlaceSource.Coordinates, savedPagePoint("geo:47.35,8.54", "Bench", own)?.source, "a list entry too")
            assertNull(savedPagePoint("geo:47.36,8.55", "Kunsthalle", own), "a public geo: place is read as its link is")
            assertNull(savedPagePoint(target.uri, target.name, own))
        }

    @Test
    fun aHomeserverSaveKeepsWhereThePlaceIsForItsPinAndReadsItBackAtTheNextStart() =
        runTest {
            signedIn()
            val writer = controller()
            runCurrent()

            // Opened from a link the core spells otherwise; the homeserver's bookmark comes back as the core spells it.
            writer.save(WriteTarget("https://osm.org/node/270803095", "Conditorei Schober", SCHOBER))
            runCurrent()

            assertEquals(listOf(OSM), cached.bookmarks.map { it.place })
            assertEquals(mapOf(OSM to SCHOBER), store.points())
            assertEquals(
                SCHOBER,
                writer.own.value.saved
                    .single()
                    .point,
            )

            // The next start reads the stored point and name for the bookmark the cache now has.
            val next = controller()
            runCurrent()
            assertEquals(
                SavedEntry(OSM, Kept.Private, "Conditorei Schober", point = SCHOBER),
                next.own.value.saved
                    .single(),
            )
        }

    @Test
    fun aSavedPlaceWithNoNameOrPointKeepsThoseItsPageShows() =
        runTest {
            // Saved and tagged on another device: the homeserver's bookmark and tag alone.
            val tagged = "https://www.openstreetmap.org/node/2"
            account.value =
                AccountState.SignedIn(
                    AccountUser(ME, "Ada"),
                    CachedAccount(ME, "Ada", 500, listOf(CachedTag("/t/1", tagged, "view", 1)), listOf(CachedBookmark("/b/1", OSM))),
                    Connection.Online,
                )
            val writer = controller()
            assertEquals(listOf(SavedEntry(OSM, Kept.Private, null)), writer.own.value.saved)

            writer.remember(target, setOf(OSM))
            writer.remember(WriteTarget(tagged, "Lindenhof", GeoPoint(47.373, 8.541)), setOf(tagged))
            writer.remember(WriteTarget(GOOGLE, "Not mine", SCHOBER), setOf(GOOGLE))
            runCurrent()

            assertEquals(listOf(SavedEntry(OSM, Kept.Private, "Conditorei Schober", point = SCHOBER)), writer.own.value.saved)
            assertEquals("Lindenhof", writer.own.value.names[tagged], "the Account page names the tagged place")
            assertEquals(mapOf(OSM to "Conditorei Schober", tagged to "Lindenhof"), store.names, "a place not the user's keeps nothing")
            assertEquals(mapOf(OSM to SCHOBER), store.points(), "only saved places keep a point")
            assertEquals(emptyList(), sent, "nothing goes to the homeserver")

            // A name and point already kept stay.
            writer.remember(WriteTarget(OSM, "Schober", GeoPoint(47.0, 8.0)), setOf(OSM))
            runCurrent()
            assertEquals(
                SavedEntry(OSM, Kept.Private, "Conditorei Schober", point = SCHOBER),
                writer.own.value.saved
                    .single(),
            )

            val next = controller()
            runCurrent()
            assertEquals(
                SavedEntry(OSM, Kept.Private, "Conditorei Schober", point = SCHOBER),
                next.own.value.saved
                    .single(),
            )
        }

    @Test
    fun aPlaceSavedOnThePhoneWithNoPointKeepsTheOneItsPageShows() =
        runTest {
            phone.data = PhoneData(saved = listOf(PhoneSaved(OSM, "Schober", 7L)))
            account.value = AccountState.SignedOut()
            val writer = controller()

            writer.remember(target, setOf(OSM))
            runCurrent()

            assertEquals(listOf(PhoneSaved(OSM, "Schober", 7L, SCHOBER)), phone.data.saved, "its own name and save time stay")
            assertEquals(listOf(SavedEntry(OSM, Kept.Phone, "Schober", point = SCHOBER)), writer.own.value.saved)
            assertEquals(emptyMap(), store.names, "signed out, the account's store keeps nothing")
        }

    @Test
    fun aPlaceSavedOnThePhoneWithNoNameKeepsTheOneItsPageShows() =
        runTest {
            phone.data = PhoneData(saved = listOf(PhoneSaved(OSM, null, 7L, SCHOBER)))
            account.value = AccountState.SignedOut()
            val writer = controller()

            writer.remember(WriteTarget(OSM, null, null), setOf(OSM))
            runCurrent()
            assertEquals(listOf(PhoneSaved(OSM, null, 7L, SCHOBER)), phone.data.saved, "a page with no name or point changes nothing")

            writer.remember(WriteTarget(OSM, "Conditorei Schober", GeoPoint(47.0, 8.0)), setOf(OSM))
            runCurrent()
            assertEquals(listOf(PhoneSaved(OSM, "Conditorei Schober", 7L, SCHOBER)), phone.data.saved, "the kept point stays")
        }

    @Test
    fun aPageWithNoNameOrPointKeepsNothingForASavedPlace() =
        runTest {
            account.value =
                AccountState.SignedIn(
                    AccountUser(ME, "Ada"),
                    CachedAccount(ME, "Ada", 500, emptyList(), listOf(CachedBookmark("/b/1", OSM))),
                    Connection.Online,
                )
            val writer = controller()

            writer.remember(WriteTarget(OSM, null, null), setOf(OSM))
            runCurrent()

            assertEquals(emptyMap(), store.names)
            assertEquals(emptyMap(), store.points())
            assertEquals(listOf(SavedEntry(OSM, Kept.Private, null)), writer.own.value.saved)
        }

    @Test
    fun aPageOpenWhileSigningOutKeepsNothingInTheAccountsStore() =
        runTest {
            val onPhone = "https://www.openstreetmap.org/node/3"
            phone.data = PhoneData(saved = listOf(PhoneSaved(onPhone, "Lindenhof", 7L)))
            account.value =
                AccountState.SignedIn(
                    AccountUser(ME, "Ada"),
                    CachedAccount(ME, "Ada", 500, emptyList(), listOf(CachedBookmark("/b/1", OSM))),
                    Connection.Online,
                )
            val writer = controller()

            assertEquals(
                listOf(SavedEntry(OSM, Kept.Private, null)),
                writer.own.value.saved
                    .filter { it.place == OSM },
            )

            // The sign-out has begun (the account's store is about to be cleared) and the writer has
            // not seen it yet: the homeserver bookmark is still the user's. The bookmark's page comes
            // first, as the phone place's publish() would drop the bookmark from what the next one reads.
            account.value = (account.value as AccountState.SignedIn).copy(signingOut = true)
            writer.remember(target, setOf(OSM))
            writer.remember(WriteTarget(onPhone, "Lindenhof", SCHOBER), setOf(onPhone))
            runCurrent()

            assertEquals(emptyMap(), store.names, "the account's store keeps no name")
            assertEquals(emptyMap(), store.points(), "the account's store keeps no point")
            assertEquals(
                listOf(PhoneSaved(onPhone, "Lindenhof", 7L, SCHOBER)),
                phone.data.saved,
                "a place saved on the phone still gets the page's point",
            )
        }

    @Test
    fun signedOutWritesAskForTheSignInFirstAndKeepWhatTheUserWanted() =
        runTest {
            account.value = AccountState.SignedOut()
            val writer = controller()

            assertTrue(writer.needsSignIn(WriteIntent.Tag))
            assertTrue(writer.needsSignIn(WriteIntent.Post))
            assertTrue(writer.needsSignIn(WriteIntent.Photo))
            assertTrue(writer.needsSignIn(WriteIntent.AddToList), "lists are kept privately on the homeserver by default")
            settings = AppSettings().withStorage(DataKind.Lists, StorageChoice.PhoneOnly)
            assertFalse(writer.needsSignIn(WriteIntent.AddToList))

            writer.signInFirst(target, WriteIntent.Tag)
            assertEquals(SignInFirst(target, WriteIntent.Tag), writer.signInFirst.value)
            assertEquals(SignInFirst(target, WriteIntent.Tag), writer.takeSignInFirst())
            assertNull(writer.signInFirst.value)
            assertNull(writer.takeSignInFirst())

            // A write that slips through while signed out goes nowhere.
            writer.tag(target, "coffee")
            runCurrent()
            assertEquals(emptyList(), store.outbox)
            assertEquals(listOf("Sign in to change this."), messages)

            signedIn()
            runCurrent()
            assertFalse(writer.needsSignIn(WriteIntent.Tag))
        }

    @Test
    fun aPhotoWaitsOnThePhoneAndItsBytesAreUploaded() =
        runTest {
            signedIn()
            val writer = controller()
            val homeserver = CompletableDeferred<Unit>()
            writes.gate = homeserver
            val bytes = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 1, 2, 3)

            writer.photo(target, PreparedPhoto(bytes, "image/jpeg", 4, 3, keptLocation = false, removedLocation = true), " A bench ", "  ")
            runCurrent()

            val file = files.files.keys.single()
            assertEquals(
                listOf(file),
                writer.own.value
                    .forPlace(setOf(OSM))
                    .photos
                    .map { it.photoFile },
            )
            assertNull(
                writer.own.value.posts
                    .single()
                    .uri,
                "not sent yet",
            )

            homeserver.complete(Unit)
            runCurrent()

            assertEquals(listOf("photo($OSM, image/jpeg, A bench, null)"), writes.builds)
            assertEquals(listOf("putFile(/pub/social/v1/files/F6.jpg)", "put(/pub/social/v1/posts/I1/I1.json)"), sent, "the file first")
            assertContentEquals(bytes, writes.photos.single())
            assertEquals(
                "pubky://$ME/pub/social/v1/posts/I1",
                writer.own.value.posts
                    .single()
                    .uri,
            )
        }

    @Test
    fun signingOutForgetsTheOutboxAndItsPhotos() =
        runTest {
            signedIn()
            val writer = controller()
            writes.failures += CoreFailure.Network("offline")
            writer.photo(
                target,
                PreparedPhoto(byteArrayOf(1), "image/jpeg", 1, 1, keptLocation = false, removedLocation = false),
                null,
                null,
            )
            runCurrent()
            assertEquals(1, files.files.size)

            // Signing out: first the account is marked signing out, then the cache (and the outbox in
            // it) is cleared and it is signed out.
            account.value = (account.value as AccountState.SignedIn).copy(signingOut = true)
            runCurrent()
            store.outbox.clear()
            account.value = AccountState.SignedOut()
            runCurrent()

            assertEquals(emptyMap(), files.files)
            assertEquals(0, writer.own.value.pending)
            assertEquals(emptyList(), writer.own.value.posts)
        }

    private val coffeeList = OwnList("pubky://me/pub/social/v1/posts/L1", Kept.Public, "Coffee", listOf("geo:1,2"))

    @Test
    fun listsAreReadFromTheHomeserverAndChangedThroughTheCore() =
        runTest {
            writes.scans =
                mapOf(false to ListScan(listOf(coffeeList), otherPostIds = listOf("N1")), true to ListScan(emptyList(), emptyList()))
            signedIn()
            val writer = controller()

            assertEquals(listOf(coffeeList), writer.own.value.lists)
            assertTrue(writer.own.value.listsLoaded)

            writer.addToList(target, coffeeList)
            runCurrent()
            assertEquals(listOf("editList(${coffeeList.uri}, $OSM, remove=false)"), sent)
            val added =
                writer.own.value.lists
                    .single()
            assertEquals(listOf("geo:1,2", OSM), added.items)
            assertEquals(
                listOf(added),
                writer.own.value
                    .forPlace(setOf(OSM))
                    .lists,
            )

            writer.refreshLists()
            runCurrent()
            assertEquals(listOf("N1"), writes.skipped.last { it.isNotEmpty() }, "a post known not to be a list is not read again")
        }

    @Test
    fun aFullListRefusesAnotherPlace() =
        runTest {
            signedIn()
            val writer = controller()
            val full = coffeeList.copy(items = (1..100).map { "geo:$it,1" })

            writer.addToList(target, full)
            runCurrent()

            assertEquals(emptyList(), sent)
            assertEquals(listOf("“Coffee” already holds 100 places, the most a list can hold."), messages)
        }

    @Test
    fun aNewListGoesWhereTheUserChose() =
        runTest {
            signedIn()
            val writer = controller()

            writer.createList(target, " Coffee ", Kept.Public)
            writer.createList(target, "Secret", Kept.Private)
            writer.createList(target, "Picnic", Kept.Phone)
            runCurrent()

            assertEquals(listOf("list(Coffee, [$OSM], private=false)", "list(Secret, [$OSM], private=true)"), writes.builds)
            assertEquals(listOf("put(/pub/social/v1/posts/LCoffee/LCoffee.json)", "put(/priv/social/v1/posts/LSecret/LSecret.json)"), sent)
            val onPhone = phone.data.lists.single()
            assertEquals("Picnic", onPhone.name)
            assertEquals(Kept.Phone, onPhone.kept)
            assertEquals(mapOf(OSM to "Conditorei Schober"), onPhone.names)
            assertEquals(
                setOf("Coffee", "Secret", "Picnic"),
                writer.own.value.lists
                    .map { it.name }
                    .toSet(),
            )
        }

    @Test
    fun aPlaceOnAPhoneListIsAddedAndRemovedOnThePhone() =
        runTest {
            account.value = AccountState.SignedOut()
            val writer = controller()
            val picnic = OwnList("phone:1", Kept.Phone, "Picnic", emptyList())
            phone.putList(picnic)

            writer.addToList(target, picnic)
            runCurrent()
            assertEquals(
                listOf(OSM),
                phone.data.lists
                    .single()
                    .items,
            )

            writer.removeFromList(target, setOf(OSM), phone.data.lists.single())
            runCurrent()
            assertEquals(
                emptyList(),
                phone.data.lists
                    .single()
                    .items,
            )
            assertEquals(emptyList(), writes.calls)
        }

    @Test
    fun aListWrittenWhileTheListsAreReadStays() =
        runTest {
            signedIn()
            val writer = controller()
            val reading = CompletableDeferred<Unit>()
            writes.listGate = reading

            writer.refreshLists()
            runCurrent()
            writer.createList(target, "New", Kept.Public)
            runCurrent()
            reading.complete(Unit)
            runCurrent()

            assertEquals(
                listOf("New"),
                writer.own.value.lists
                    .map { it.name },
                "the older scan does not remove it",
            )
            assertEquals(listOf("New"), store.stored.map { it.name })
        }

    @Test
    fun aHomeserverRefusingPrivateListsStillListsThePublicOnes() =
        runTest {
            writes.scans = mapOf(false to ListScan(listOf(coffeeList), emptyList()), true to ListScan(emptyList(), emptyList()))
            writes.privateListsFailure = CoreFailure.Other("forbidden")
            signedIn()
            val writer = controller()

            assertEquals(listOf(coffeeList), writer.own.value.lists)
            assertFalse(writer.own.value.listsLoaded, "the page says the lists may be out of date")
            // Not read again on every change of the account; only when asked.
            val reads = writes.calls.count { it.startsWith("lists(") }
            signedIn(tags = listOf(CachedTag("/t", OSM, "x", 1)))
            runCurrent()
            assertEquals(reads, writes.calls.count { it.startsWith("lists(") })
        }

    @Test
    fun aCoreThatDoesNotLoadLeavesTheAppRunning() =
        runTest {
            settings = AppSettings().withStorage(DataKind.SavedPlaces, StorageChoice.PhoneOnly)
            account.value = AccountState.SignedOut()
            val writer =
                WriteController(
                    writes = { throw NotImplementedError("libpubky_maps_core.so not found") },
                    store = store,
                    phone = phone,
                    files = files,
                    account = account,
                    updateAccount = {},
                    refreshAccount = {},
                    settings = { settings },
                    scope = backgroundScope,
                    io = StandardTestDispatcher(testScheduler),
                    now = { 1_000L },
                )
            runCurrent()

            writer.save(target)
            runCurrent()

            assertEquals(listOf(OSM), phone.data.saved.map { it.place })
        }

    @Test
    fun undoTakesATagBackAndPutsARemovedOneBack() =
        runTest {
            signedIn()
            val writer = controller()

            writer.tag(target, "coffee")
            runCurrent()
            assertEquals("Undo", actions.last().actionLabel)
            actions.last().action!!.invoke()
            runCurrent()
            assertEquals(listOf("tag($OSM, coffee)", "untag($OSM, coffee)"), sent)
            assertEquals(emptyList(), writer.own.value.tags)

            writer.untag(target, setOf(OSM), "cake")
            runCurrent()
            assertEquals("Removed your tag “cake”.", messages.last())
            actions.last().action!!.invoke()
            runCurrent()
            assertEquals(listOf("untag($OSM, cake)", "tag($OSM, cake)"), sent.takeLast(2))
        }

    @Test
    fun undoTakesAListChangeBack() =
        runTest {
            writes.scans = mapOf(false to ListScan(listOf(coffeeList), emptyList()), true to ListScan(emptyList(), emptyList()))
            signedIn()
            val writer = controller()

            writer.addToList(target, coffeeList)
            runCurrent()
            assertEquals("Added to “Coffee”.", messages.last())
            actions.last().action!!.invoke()
            runCurrent()

            assertEquals(
                listOf("editList(${coffeeList.uri}, $OSM, remove=false)", "editList(${coffeeList.uri}, $OSM, remove=true)"),
                sent,
            )
            assertEquals(
                listOf("geo:1,2"),
                writer.own.value.lists
                    .single()
                    .items,
            )
        }

    @Test
    fun aBusyHomeserverIsNotCalledOfflineAndTryNowSendsAgain() =
        runTest {
            signedIn()
            val writer = controller()
            writes.failures += CoreFailure.Network("429 Too Many Requests", busy = true)

            writer.save(target)
            runCurrent()

            assertEquals(listOf(BUSY_MESSAGE), messages)
            assertTrue(writer.own.value.busy)
            assertEquals("Try now", actions.last().actionLabel)
            actions.last().action!!.invoke()
            runCurrent()
            assertEquals(listOf("save($OSM)", "save($OSM)"), sent, "sent again at once, not after the wait")
            assertEquals(emptyList(), store.outbox)
            assertFalse(writer.own.value.busy)
        }

    @Test
    fun theWaitingWriteStaysUntilTheUserGoesBackFromTheSignIn() =
        runTest {
            account.value = AccountState.SignedOut()
            val writer = controller()
            writer.signInFirst(target, WriteIntent.Post)

            // The account page leaving the screen (activity recreated) changes nothing here; only Back does.
            account.value = AccountState.SigningIn(link = "pubkyauth://x")
            runCurrent()
            assertEquals(SignInFirst(target, WriteIntent.Post), writer.signInFirst.value)

            writer.leftSignIn()
            assertNull(writer.signInFirst.value)
        }

    @Test
    fun goingBackAfterSigningInKeepsTheWriteForTheApp() =
        runTest {
            account.value = AccountState.SignedOut()
            val writer = controller()
            writer.signInFirst(target, WriteIntent.Tag)
            signedIn()
            runCurrent()

            writer.leftSignIn()

            assertEquals(SignInFirst(target, WriteIntent.Tag), writer.takeSignInFirst())
        }

    @Test
    fun theUserIsToldOnceOnThisPhoneThatTagsArePublic() =
        runTest {
            signedIn()
            val first = controller()
            assertFalse(first.publicTagsExplained.value)

            first.markPublicTagsExplained()
            runCurrent()

            assertTrue(first.publicTagsExplained.value)
            assertEquals(setOf(PUBLIC_TAGS_EXPLAINED), phone.flags)
            assertTrue(controller().publicTagsExplained.value, "kept on the phone for the next start")
        }

    @Test
    fun aListWhosePostCouldNotBeReadStaysAsLastKnown() =
        runTest {
            store.stored = listOf(coffeeList)
            writes.scans =
                mapOf(
                    false to ListScan(emptyList(), emptyList(), unreadPostIds = listOf("L1")),
                    true to ListScan(emptyList(), emptyList()),
                )
            signedIn()
            val writer = controller()

            assertEquals(listOf(coffeeList), writer.own.value.lists)
            assertEquals(listOf(coffeeList), store.stored)
            assertFalse(writer.own.value.listsLoaded, "the page says the lists may be out of date")
        }

    @Test
    fun theCoreIsBuiltOnTheIoDispatcherNeverOnTheCallers() =
        runTest {
            val io = StandardTestDispatcher(testScheduler, name = "io")
            val builtOn = mutableListOf<ContinuationInterceptor?>()
            signedIn()
            val writer =
                WriteController(
                    writes = {
                        builtOn += currentCoroutineContext()[ContinuationInterceptor]
                        writes
                    },
                    store = store,
                    phone = phone,
                    files = files,
                    account = account,
                    updateAccount = {},
                    refreshAccount = {},
                    settings = { settings },
                    scope = backgroundScope,
                    io = io,
                    now = { 1_000L },
                )
            runCurrent()
            writer.save(target)
            runCurrent()

            assertTrue(builtOn.isNotEmpty())
            assertTrue(builtOn.all { it === io }, "built on $builtOn")
        }

    @Test
    fun undoPutsAPlaceBackIntoTheFullListItWasTakenFrom() =
        runTest {
            val full = coffeeList.copy(items = (1..99).map { "geo:$it,1" } + OSM)
            writes.scans = mapOf(false to ListScan(listOf(full), emptyList()), true to ListScan(emptyList(), emptyList()))
            signedIn()
            val writer = controller()

            writer.removeFromList(target, setOf(OSM), full)
            runCurrent()
            assertEquals("Removed from “Coffee”.", messages.last())
            actions.last().action!!.invoke()
            runCurrent()

            assertEquals(
                listOf("editList(${full.uri}, $OSM, remove=true)", "editList(${full.uri}, $OSM, remove=false)"),
                sent,
            )
            assertFalse(messages.any { it.contains("already holds") })
        }
}
