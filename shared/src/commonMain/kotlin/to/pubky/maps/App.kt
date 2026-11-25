package to.pubky.maps

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import kotlinx.serialization.Serializable
import to.pubky.maps.config.ServerConfigRepository
import to.pubky.maps.map.MapRoute
import to.pubky.maps.settings.LicencesRoute
import to.pubky.maps.settings.SettingsScreen
import to.pubky.maps.ui.PubkyMapsTheme

@Serializable
private data object MapDestination

@Serializable
private data object SettingsDestination

@Serializable
private data object LicencesDestination

/**
 * The whole app: the map, and the settings on top of it. [loadLicences] returns the AboutLibraries
 * JSON of the bundled libraries.
 */
@Composable
fun App(
    appInfo: AppInfo,
    serverConfigRepository: ServerConfigRepository,
    loadLicences: suspend () -> String,
) {
    val serverConfig by serverConfigRepository.config.collectAsStateWithLifecycle()
    PubkyMapsTheme {
        val navController = rememberNavController()
        NavHost(navController = navController, startDestination = MapDestination) {
            composable<MapDestination> {
                MapRoute(
                    serverConfig = serverConfig,
                    onOpenSettings = { navController.navigate(SettingsDestination) { launchSingleTop = true } },
                )
            }
            composable<SettingsDestination> {
                SettingsScreen(
                    appInfo = appInfo,
                    serverConfig = serverConfig,
                    onOpenLicences = { navController.navigate(LicencesDestination) { launchSingleTop = true } },
                    onBack = { navController.navigateUp() },
                )
            }
            composable<LicencesDestination> {
                LicencesRoute(loadLicences = loadLicences, onBack = { navController.navigateUp() })
            }
        }
    }
}
