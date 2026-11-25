package to.pubky.maps.account

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.alexzhirkevich.qrose.rememberQrCodePainter
import to.pubky.maps.ui.AppIcons
import to.pubky.maps.ui.rememberSafeLinkOpener

// Where people get Pubky Ring: links to open, not servers the app talks to.
private const val RING_PLAY_URL = "https://play.google.com/store/apps/details?id=to.pubky.ring"
private const val RING_APP_STORE_URL = "https://apps.apple.com/app/pubky-ring/id6739356756"
private const val RING_GITHUB_URL = "https://github.com/pubky/pubky-ring/releases"

/** At most this many tags and saved places are listed; the count says how many there are. */
private const val LISTED_ITEMS = 50

/**
 * The account page: sign in with Pubky Ring, wait for its approval (Ring on this phone, or a QR code
 * for Ring on another one), and once signed in the user and what this phone has of their data.
 * [ringOnThisPhone] says whether an app here handles Ring's `pubkyauth://` links.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AccountScreen(
    state: AccountState,
    ringOnThisPhone: Boolean,
    onSignIn: () -> Unit,
    onOpenRing: (String) -> Unit,
    onCancelSignIn: () -> Unit,
    onRefresh: () -> Unit,
    onSignOut: () -> Unit,
    onBack: () -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Account") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(AppIcons.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    if (state is AccountState.SignedIn && !state.signingOut) {
                        IconButton(onClick = onRefresh, enabled = !state.refreshing) {
                            Icon(AppIcons.Refresh, contentDescription = "Refresh")
                        }
                    }
                },
            )
        },
    ) { padding ->
        Box(
            Modifier
                .fillMaxSize()
                .padding(padding),
            contentAlignment = Alignment.TopCenter,
        ) {
            Column(
                Modifier
                    .widthIn(max = 720.dp)
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
            ) {
                when (state) {
                    AccountState.Loading -> Loading()
                    is AccountState.SignedOut -> SignedOut(state, onSignIn)
                    is AccountState.SigningIn -> SigningIn(state, ringOnThisPhone, onOpenRing, onCancelSignIn)
                    is AccountState.SignedIn -> SignedIn(state, onSignOut)
                    is AccountState.Unavailable -> Paragraph(state.reason)
                }
            }
        }
    }
}

@Composable
private fun Loading() {
    Row(
        Modifier.padding(24.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 3.dp)
        Text("Signing you in…", style = MaterialTheme.typography.bodyLarge)
    }
}

@Composable
private fun SignedOut(
    state: AccountState.SignedOut,
    onSignIn: () -> Unit,
) {
    Column(Modifier.padding(horizontal = 24.dp, vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text("Sign in with Pubky", style = MaterialTheme.typography.headlineSmall)
        Text(
            "Sign in to tag places and save them with your Pubky identity. Pubky Ring holds your keys and " +
                "approves the sign-in; Pubky Maps never sees them.",
            style = MaterialTheme.typography.bodyLarge,
        )
        state.notice?.let {
            Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
        }
        Button(onClick = onSignIn) { Text("Sign in with Pubky Ring") }
        Text(
            "No Pubky identity yet? Pubky Ring creates one.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun SigningIn(
    state: AccountState.SigningIn,
    ringOnThisPhone: Boolean,
    onOpenRing: (String) -> Unit,
    onCancel: () -> Unit,
) {
    var showQr by rememberSaveable { mutableStateOf(false) }
    Column(Modifier.padding(horizontal = 24.dp, vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text("Approve in Pubky Ring", style = MaterialTheme.typography.headlineSmall)
        when {
            state.returned -> {
                Waiting("Finishing the sign-in…")
            }

            ringOnThisPhone && !showQr -> {
                Text(
                    if (state.relayChanged) {
                        "The sign-in moved to another relay. Open Pubky Ring again to approve it."
                    } else {
                        "Approve the sign-in in Pubky Ring, then come back to Pubky Maps."
                    },
                    style = MaterialTheme.typography.bodyLarge,
                )
                Button(onClick = { onOpenRing(state.link) }) { Text("Open Pubky Ring") }
                Waiting("Waiting for your approval…")
                TextButton(onClick = { showQr = true }) { Text("Use Pubky Ring on another device") }
            }

            else -> {
                Text(
                    if (ringOnThisPhone) {
                        "Scan this code with Pubky Ring on your other device."
                    } else {
                        "Scan this code with Pubky Ring on another device. Pubky Ring is not on this phone."
                    },
                    style = MaterialTheme.typography.bodyLarge,
                )
                SignInQrCode(state.link, Modifier.align(Alignment.CenterHorizontally))
                Waiting("Waiting for your approval…")
                if (!ringOnThisPhone) GetRing()
            }
        }
        OutlinedButton(onClick = onCancel) { Text("Cancel") }
    }
}

@Composable
private fun Waiting(text: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
        Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** The link as a QR code, dark on white in both themes (scanners expect that), with a quiet zone. */
@Composable
private fun SignInQrCode(
    link: String,
    modifier: Modifier = Modifier,
) {
    val painter = rememberQrCodePainter(link)
    Surface(modifier = modifier, shape = RoundedCornerShape(12.dp), color = Color.White) {
        Image(
            painter = painter,
            contentDescription = "QR code of the sign-in link",
            modifier =
                Modifier
                    .padding(16.dp)
                    .size(240.dp),
        )
    }
}

@Composable
private fun GetRing() {
    val openLink = rememberSafeLinkOpener()
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text("Get Pubky Ring", style = MaterialTheme.typography.titleSmall)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { openLink(RING_PLAY_URL) }) { Text("Google Play") }
            OutlinedButton(onClick = { openLink(RING_APP_STORE_URL) }) { Text("App Store") }
            OutlinedButton(onClick = { openLink(RING_GITHUB_URL) }) { Text("GitHub") }
        }
    }
}

@Composable
private fun SignedIn(
    state: AccountState.SignedIn,
    onSignOut: () -> Unit,
) {
    var confirmSignOut by rememberSaveable { mutableStateOf(false) }
    if (state.refreshing || state.signingOut) {
        LinearProgressIndicator(Modifier.fillMaxWidth())
    }
    ListItem(
        leadingContent = { Avatar(state.user) },
        headlineContent = {
            Text(
                state.user.name ?: "No profile name",
                style = MaterialTheme.typography.titleLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        },
        supportingContent = {
            Text(
                shortPubkyId(state.user.publicKey),
                fontFamily = FontFamily.Monospace,
                modifier = Modifier.semantics { contentDescription = "Pubky ID ${state.user.publicKey}" },
            )
        },
    )
    ConnectionLine(state)
    HorizontalDivider(Modifier.padding(vertical = 8.dp))
    ItemSection(
        title = "Your tags",
        count = state.cached.tags.size,
        empty = "No tags yet.",
        items = state.cached.tags.map { it.label to placeLabel(it.place) },
    )
    ItemSection(
        title = "Saved places",
        count = state.cached.bookmarks.size,
        empty = "No saved places yet.",
        items = state.cached.bookmarks.map { placeLabel(it.place) to null },
    )
    OutlinedButton(
        onClick = { confirmSignOut = true },
        enabled = !state.signingOut,
        modifier = Modifier.padding(horizontal = 24.dp, vertical = 16.dp),
    ) { Text("Sign out") }
    if (confirmSignOut) {
        AlertDialog(
            onDismissRequest = { confirmSignOut = false },
            title = { Text("Sign out?") },
            text = { Text("This phone forgets your Pubky data. It stays on your homeserver.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmSignOut = false
                    onSignOut()
                }) { Text("Sign out") }
            },
            dismissButton = { TextButton(onClick = { confirmSignOut = false }) { Text("Keep signed in") } },
        )
    }
}

@Composable
private fun Avatar(user: AccountUser) {
    val initial = user.name?.firstOrNull()?.uppercaseChar()
    Box(
        Modifier
            .size(48.dp)
            .background(MaterialTheme.colorScheme.primaryContainer, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        if (initial != null) {
            Text(initial.toString(), style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onPrimaryContainer)
        } else {
            Icon(AppIcons.AccountCircle, contentDescription = null, tint = MaterialTheme.colorScheme.onPrimaryContainer)
        }
    }
}

@Composable
private fun ConnectionLine(state: AccountState.SignedIn) {
    val (text, offline) =
        when (state.connection) {
            Connection.Connecting -> {
                "Connecting to your homeserver…" to false
            }

            Connection.Online -> {
                (if (state.refreshing) "Reading your data from your homeserver…" else "Up to date with your homeserver") to
                    false
            }

            Connection.Offline -> {
                "Your homeserver can't be reached. Showing what this phone saved." to true
            }
        }
    Row(
        Modifier.padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (offline) Icon(AppIcons.CloudOff, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun ItemSection(
    title: String,
    count: Int,
    empty: String,
    items: List<Pair<String, String?>>,
) {
    Text(
        text = if (count > 0) "$title ($count)" else title,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 4.dp),
    )
    if (items.isEmpty()) {
        Paragraph(empty)
        return
    }
    items.take(LISTED_ITEMS).forEach { (headline, supporting) ->
        ListItem(
            headlineContent = { Text(headline, maxLines = 1, overflow = TextOverflow.Ellipsis) },
            supportingContent = supporting?.let { { Text(it, maxLines = 1, overflow = TextOverflow.Ellipsis) } },
        )
    }
    if (count > LISTED_ITEMS) Paragraph("and ${count - LISTED_ITEMS} more")
}

@Composable
private fun Paragraph(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
    )
}

/** The Settings row of the account: who is signed in, or the way to sign in. */
@Composable
fun AccountSettingsRow(
    state: AccountState,
    onOpen: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val (headline, supporting) =
        when (state) {
            is AccountState.SignedIn -> {
                (state.user.name ?: shortPubkyId(state.user.publicKey)) to
                    if (state.connection == Connection.Offline) {
                        "${shortPubkyId(state.user.publicKey)} · homeserver unreachable"
                    } else {
                        shortPubkyId(state.user.publicKey)
                    }
            }

            is AccountState.SigningIn -> {
                "Signing in…" to "Waiting for Pubky Ring"
            }

            AccountState.Loading -> {
                "Account" to "Signing you in…"
            }

            is AccountState.SignedOut -> {
                "Sign in with Pubky Ring" to "Tag and save places with your Pubky identity"
            }

            is AccountState.Unavailable -> {
                "Account" to "Sign-in is not available on this phone"
            }
        }
    ListItem(
        leadingContent = {
            if (state is AccountState.SignedIn) {
                Avatar(state.user)
            } else {
                Icon(AppIcons.AccountCircle, contentDescription = null)
            }
        },
        headlineContent = { Text(headline, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        supportingContent = {
            Text(supporting, maxLines = 1, overflow = TextOverflow.Ellipsis)
        },
        modifier = modifier.clickable(onClick = onOpen),
    )
}
