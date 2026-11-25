package to.pubky.maps.android.update

import android.app.Activity
import android.app.Application
import android.content.ActivityNotFoundException
import android.content.Context
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import to.pubky.maps.config.InMemoryServerConfigRepository
import to.pubky.maps.config.ServerDefaults
import to.pubky.maps.update.InstalledBuild
import to.pubky.maps.update.SignatureVerifier
import to.pubky.maps.update.UpdateChannel
import to.pubky.maps.update.UpdateChecker
import to.pubky.maps.update.UpdateDialog
import to.pubky.maps.update.UpdateStatus
import java.io.File
import java.security.MessageDigest

/**
 * The updater's flow on Robolectric with a fake network and installer: when it checks, what it
 * offers, and that only the file the feed names reaches the installer. The signature check itself is
 * SignedFeedTest's; here every feed counts as signed.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
@OptIn(ExperimentalCoroutinesApi::class)
class ApkUpdaterTest {
    private val app: Application = RuntimeEnvironment.getApplication()
    private val apk = "the APK of version 1001".encodeToByteArray()
    private val installedBuild = InstalledBuild("to.pubky.maps", 1000, "0.0.1-probe.1", 35, UpdateChannel.Edge)
    private val day = 24L * 60 * 60 * 1000

    private class FakePlatform(
        var served: ByteArray,
    ) : UpdatePlatform {
        var canInstall = false
        var archive: ArchiveFacts? = ArchiveFacts("to.pubky.maps", 1001)
        var installerMissing = false
        val handedToInstaller = mutableListOf<ByteArray>()
        var settingsOpened = 0
        var clock = 100 * 24L * 60 * 60 * 1000

        override fun download(
            url: String,
            target: File,
            expectedSize: Long,
            onProgress: (Long) -> Unit,
        ): Long {
            target.writeBytes(served)
            onProgress(served.size.toLong())
            return served.size.toLong()
        }

        override fun readArchive(file: File) = archive

        override fun canInstallPackages() = canInstall

        override fun launchInstaller(
            activity: Activity,
            file: File,
        ) {
            if (installerMissing) throw ActivityNotFoundException("no installer")
            handedToInstaller += file.readBytes()
        }

        override fun openInstallPermissionSettings(activity: Activity) {
            settingsOpened++
        }

        override fun now() = clock
    }

    private var fetches = 0
    private var serverDown = false

    private fun feedFile(): ByteArray {
        val sha = MessageDigest.getInstance("SHA-256").digest(apk).joinToString("") { "%02x".format(it) }
        val feed =
            """{"format":1,"app":"to.pubky.maps","channel":"edge","versionCode":1001,"versionName":"0.0.1-probe.2",""" +
                """"minSdk":26,"apk":{"url":"https://example.org/pubky-maps.apk","size":${apk.size},"sha256":"$sha"}}"""
        val signature =
            java.util.Base64
                .getEncoder()
                .encodeToString(ByteArray(64))
        return """{"feed":"${feed.replace("\"", "\\\"")}","signature":"$signature"}""".encodeToByteArray()
    }

    private fun TestScope.updater(platform: FakePlatform): ApkUpdater {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val checker =
            UpdateChecker(
                fetch = {
                    fetches++
                    if (serverDown) error("unreachable") else feedFile()
                },
                trustedKeys = listOf(ByteArray(32)),
                verifier = SignatureVerifier { _, _, _ -> true },
            )
        return ApkUpdater(
            app,
            installedBuild,
            InMemoryServerConfigRepository(ServerDefaults.config),
            checker,
            platform,
            dispatcher,
            dispatcher,
        )
    }

    private fun prefs() = app.getSharedPreferences("updates", Context.MODE_PRIVATE)

    private fun updatesDir() = File(app.cacheDir, "updates")

    @Test
    fun checksAtStartAndOffersTheNewerBuild() =
        runTest {
            val updater = updater(FakePlatform(apk))
            updater.onAppStart()
            advanceUntilIdle()
            assertEquals(1, fetches)
            val offer = updater.state.value.dialog as UpdateDialog.Offer
            assertEquals("0.0.1-probe.2", offer.update.versionName)
            assertEquals(apk.size.toLong(), offer.update.sizeBytes)
            assertEquals("0.0.1-probe.1", offer.installedVersionName)
        }

    @Test
    fun checksAutomaticallyAtMostOnceADay() =
        runTest {
            val platform = FakePlatform(apk)
            updater(platform).onAppStart()
            advanceUntilIdle()
            platform.clock += day - 1
            updater(platform).onAppStart()
            advanceUntilIdle()
            assertEquals(1, fetches)
            platform.clock += 1
            updater(platform).onAppStart()
            advanceUntilIdle()
            assertEquals(2, fetches)
        }

    @Test
    fun noAutomaticCheckWhenTurnedOff() =
        runTest {
            val platform = FakePlatform(apk)
            val first = updater(platform)
            first.onAppStart()
            advanceUntilIdle()
            first.setAutoCheck(false)
            advanceUntilIdle()
            platform.clock += 2 * day
            val next = updater(platform)
            next.onAppStart()
            advanceUntilIdle()
            assertEquals(1, fetches)
            assertFalse(next.state.value.autoCheck)
            assertNull(next.state.value.dialog)
            // "Check for updates" still works.
            next.checkNow()
            advanceUntilIdle()
            assertEquals(2, fetches)
        }

    @Test
    fun anUnreachableServerIsNotACheck() =
        runTest {
            serverDown = true
            val platform = FakePlatform(apk)
            val updater = updater(platform)
            updater.onAppStart()
            advanceUntilIdle()
            assertTrue(updater.state.value.status is UpdateStatus.Failed)
            assertNull(updater.state.value.dialog)
            assertEquals(0L, prefs().getLong("last_check_ms", 0L))
            updater(platform).onAppStart()
            advanceUntilIdle()
            assertEquals(2, fetches)
        }

    @Test
    fun installsAfterTheUserAllowsUpdates() =
        runTest {
            val platform = FakePlatform(apk)
            val updater = updater(platform)
            val activity = Robolectric.buildActivity(Activity::class.java).setup()
            updater.onAppStart()
            advanceUntilIdle()
            updater.install()
            advanceUntilIdle()
            assertTrue(updater.state.value.dialog is UpdateDialog.NeedsPermission)
            assertTrue(platform.handedToInstaller.isEmpty())

            updater.openInstallPermissionSettings()
            advanceUntilIdle()
            assertEquals(1, platform.settingsOpened)
            assertEquals(1001L, prefs().getLong("pending_version_code", 0L))
            platform.canInstall = true
            activity.pause().resume()
            advanceUntilIdle()
            assertEquals(1, platform.handedToInstaller.size)
            assertTrue(platform.handedToInstaller.single().contentEquals(apk))
            assertEquals(0L, prefs().getLong("pending_version_code", 0L))
            assertEquals(listOf("pubky-maps-1001.apk"), updatesDir().list()!!.toList())
        }

    @Test
    fun refusesATamperedDownload() =
        runTest {
            val tampered = apk.copyOf().also { it[3] = (it[3].toInt() xor 0xFF).toByte() }
            val platform = FakePlatform(tampered).apply { canInstall = true }
            val updater = updater(platform)
            Robolectric.buildActivity(Activity::class.java).setup()
            updater.onAppStart()
            advanceUntilIdle()
            updater.install()
            advanceUntilIdle()
            val refused = updater.state.value.dialog as UpdateDialog.NotInstalled
            assertTrue(refused.message, refused.message.contains("SHA-256"))
            assertTrue(platform.handedToInstaller.isEmpty())
            assertTrue(updatesDir().list()!!.isEmpty())
        }

    @Test
    fun refusesAnotherPackageOrVersion() =
        runTest {
            val platform = FakePlatform(apk).apply { canInstall = true }
            platform.archive = ArchiveFacts("to.pubky.other", 1001)
            val updater = updater(platform)
            Robolectric.buildActivity(Activity::class.java).setup()
            updater.onAppStart()
            advanceUntilIdle()
            updater.install()
            advanceUntilIdle()
            assertTrue((updater.state.value.dialog as UpdateDialog.NotInstalled).message.contains("not Pubky Maps"))
            assertTrue(platform.handedToInstaller.isEmpty())
        }

    @Test
    fun checksTheFileAgainBeforeTheInstaller() =
        runTest {
            val platform = FakePlatform(apk)
            val updater = updater(platform)
            val activity = Robolectric.buildActivity(Activity::class.java).setup()
            updater.onAppStart()
            advanceUntilIdle()
            updater.install()
            advanceUntilIdle()
            updater.openInstallPermissionSettings()
            advanceUntilIdle()
            // The checked file changes while the user is in Android's settings.
            File(updatesDir(), "pubky-maps-1001.apk").writeBytes("something else entirely".encodeToByteArray())
            platform.canInstall = true
            activity.pause().resume()
            advanceUntilIdle()
            assertTrue(updater.state.value.dialog is UpdateDialog.NotInstalled)
            assertTrue(platform.handedToInstaller.isEmpty())
        }

    @Test
    fun offersThePendingUpdateAgainAfterARestart() =
        runTest {
            val platform = FakePlatform(apk)
            val updater = updater(platform)
            Robolectric.buildActivity(Activity::class.java).setup()
            updater.onAppStart()
            advanceUntilIdle()
            updater.install()
            advanceUntilIdle()
            updater.openInstallPermissionSettings()
            advanceUntilIdle()
            // Android stops the app; the next start is within the day, but the update is offered again.
            platform.clock += 60_000
            val restarted = updater(platform)
            restarted.onAppStart()
            advanceUntilIdle()
            assertEquals(2, fetches)
            assertTrue(restarted.state.value.dialog is UpdateDialog.Offer)
            assertTrue(updatesDir().list()!!.isEmpty())
        }

    @Test
    fun aMissingInstallerIsReportedNotCrashed() =
        runTest {
            val platform = FakePlatform(apk).apply { canInstall = true }
            platform.installerMissing = true
            val updater = updater(platform)
            Robolectric.buildActivity(Activity::class.java).setup()
            updater.onAppStart()
            advanceUntilIdle()
            updater.install()
            advanceUntilIdle()
            assertTrue((updater.state.value.dialog as UpdateDialog.NotInstalled).message.contains("no app installer"))
        }

    @Test
    fun laterClosesTheOfferAndForgetsAPendingInstall() =
        runTest {
            val updater = updater(FakePlatform(apk))
            updater.onAppStart()
            advanceUntilIdle()
            updater.dismissDialog()
            advanceUntilIdle()
            assertNull(updater.state.value.dialog)
            assertTrue(updater.state.value.status is UpdateStatus.Available)
        }
}
