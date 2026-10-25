package app.tgdrive.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.tgdrive.BuildConfig
import app.tgdrive.data.formatBytes
import app.tgdrive.library.LibraryState

internal fun settingsTitle(page: String): String = when (page) {
    "transfers" -> "Transfers and background"; "storage" -> "Storage and offline"
    "index" -> "Index and recovery"; "privacy" -> "Vaults and privacy"; "vaults" -> "Encrypted vaults"
    "connections" -> "Optional connections"; "organize" -> "Organize by rules"
    "conflicts" -> "Resolve conflicts"; "diagnostics" -> "Diagnostics"; "about" -> "About TG Drive"
    else -> "Settings"
}

@Composable
fun SettingsHome(state: LibraryState, onOpen: (String) -> Unit) {
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(24.dp)) {
        item { DriveGroup {
            ActionRow("Transfers and background", "Network rules, retries and schedule", Icons.Outlined.SwapVert) { onOpen("transfers") }
            DriveDivider()
            ActionRow("Storage and offline", "${formatBytes(state.cacheBytes)} cache · ${formatBytes(state.offlineBytes)} offline", Icons.Outlined.Storage) { onOpen("storage") }
            DriveDivider()
            ActionRow("Index and recovery", "Sync, export and restore your library", Icons.Outlined.Restore) { onOpen("index") }
        } }
        item { DriveGroup {
            ActionRow("Vaults and privacy", "${state.vaults.size} vaults · app lock and privacy", Icons.Outlined.Lock) { onOpen("privacy") }
            DriveDivider()
            ActionRow("Optional connections", "WebDAV and public-copy links", Icons.Outlined.Link) { onOpen("connections") }
        } }
        item { DriveGroup {
            ActionRow("Organize by rules", "Review suggestions before applying", Icons.Outlined.AutoAwesome) { onOpen("organize") }
            DriveDivider()
            ActionRow("Resolve conflicts", "${state.conflicts.size} edits need review", Icons.Outlined.Merge) { onOpen("conflicts") }
        } }
        item { DriveGroup {
            ActionRow("Diagnostics", "Check availability and view a local report", Icons.Outlined.HealthAndSafety) { onOpen("diagnostics") }
            DriveDivider()
            ActionRow("About and update identity", "TG Drive ${BuildConfig.VERSION_NAME}", Icons.Outlined.Info) { onOpen("about") }
        } }
    }
}
