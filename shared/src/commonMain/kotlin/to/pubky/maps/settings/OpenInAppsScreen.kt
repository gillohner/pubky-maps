package to.pubky.maps.settings

import androidx.compose.runtime.Composable
import to.pubky.maps.config.AppSettings
import to.pubky.maps.config.OpenInApp

/** A switch per app that a place can be opened in. */
@Composable
fun OpenInAppsScreen(
    settings: AppSettings,
    onUpdate: ((AppSettings) -> AppSettings) -> Unit,
    onBack: () -> Unit,
) {
    SettingsPage(title = "Open in other apps", onBack = onBack) {
        item { PageIntro("The apps a place's Open in menu offers. Switch off the ones you never use.") }
        for (app in OpenInApp.entries) {
            item {
                SwitchRow(
                    title = app.title,
                    summary = app.note,
                    checked = settings.isOpenInEnabled(app),
                    onCheckedChange = { on -> onUpdate { it.withOpenIn(app, on) } },
                )
            }
        }
    }
}
