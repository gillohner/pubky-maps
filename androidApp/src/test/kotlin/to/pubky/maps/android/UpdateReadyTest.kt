package to.pubky.maps.android

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import to.pubky.maps.AppInfo
import to.pubky.maps.config.AppSettings
import to.pubky.maps.config.ServerDefaults
import to.pubky.maps.map.MapScreen
import to.pubky.maps.settings.SettingsScreen
import to.pubky.maps.ui.PubkyMapsTheme
import to.pubky.maps.update.AvailableUpdate
import to.pubky.maps.update.UpdateChannel
import to.pubky.maps.update.UpdateDialog
import to.pubky.maps.update.UpdateStatus
import to.pubky.maps.update.UpdatesUiState

/**
 * An update ready to install shows outside Settings > About: a dot on the map's Settings button (named
 * to TalkBack) and a card at the top of Settings whose Install starts the install.
 */
@RunWith(RobolectricTestRunner::class)
class UpdateReadyTest {
    @get:Rule
    val compose = createComposeRule()

    private val update = AvailableUpdate(versionCode = 1_000_012, versionName = "0.1.0-edge.12", sizeBytes = 14_236_512)
    private var installs = 0
    private var settingsOpened = 0

    private fun map(updateReady: Boolean) {
        compose.setContent {
            PubkyMapsTheme {
                MapScreen(
                    attribution =
                        ServerDefaults.lists
                            .mapStyles(darkTheme = false)
                            .first()
                            .attribution,
                    mapUnavailable = false,
                    onRetryMap = {},
                    onMyLocation = {},
                    onOpenSettings = { settingsOpened++ },
                    updateReady = updateReady,
                ) { Box(Modifier.fillMaxSize()) }
            }
        }
    }

    private var status: UpdateStatus by mutableStateOf(UpdateStatus.NotChecked)

    private fun settings(
        first: UpdateStatus,
        dialog: UpdateDialog? = null,
    ) {
        status = first
        compose.setContent {
            PubkyMapsTheme {
                SettingsScreen(
                    appInfo = AppInfo(versionName = "0.1.0-edge.11", versionCode = 1_000_011),
                    settings = AppSettings(),
                    onOpenServers = {},
                    onOpenStoredOnline = {},
                    onOpenOpenIn = {},
                    onFollowLocalRulesChange = {},
                    onOpenLicences = {},
                    onBack = {},
                    updates = UpdatesUiState(UpdateChannel.Edge, autoCheck = true, status = status, dialog = dialog),
                    onInstallUpdate = { installs++ },
                )
            }
        }
    }

    @Test
    fun theMapsSettingsButtonSaysAnUpdateIsReady() {
        map(updateReady = true)
        compose.onNodeWithContentDescription("Settings, update ready").performClick()
        assertEquals(1, settingsOpened)
    }

    @Test
    fun theMapsSettingsButtonIsPlainWithoutAnUpdate() {
        map(updateReady = false)
        compose.onNodeWithContentDescription("Settings").assertIsDisplayed()
        compose.onNodeWithContentDescription("Settings, update ready").assertDoesNotExist()
    }

    @Test
    fun settingsOpensWithTheUpdateAndInstallStartsIt() {
        settings(UpdateStatus.Available(update))
        compose.onNodeWithText("Pubky Maps 0.1.0-edge.12 is ready").assertIsDisplayed()
        compose.onNodeWithText("14.2 MB. You have 0.1.0-edge.11.").assertIsDisplayed()
        compose.onNode(hasText("Install") and hasClickAction()).assertIsEnabled().performClick()
        assertEquals(1, installs)
    }

    @Test
    fun installWaitsWhileTheUpdateDownloads() {
        settings(UpdateStatus.Available(update), UpdateDialog.Downloading(update, downloadedBytes = 1_000_000))
        compose.onNode(hasText("Install") and hasClickAction()).assertIsNotEnabled()
    }

    @Test
    fun noCardWithoutAnUpdate() {
        settings(UpdateStatus.NotChecked)
        for (each in listOf(UpdateStatus.NotChecked, UpdateStatus.Checking, UpdateStatus.UpToDate("0.1.0-edge.11"))) {
            status = each
            compose.waitForIdle()
            compose.onNodeWithText("is ready", substring = true).assertDoesNotExist()
            compose.onNode(hasText("Install") and hasClickAction()).assertDoesNotExist()
        }
    }
}
