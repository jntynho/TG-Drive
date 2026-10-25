package app.tgdrive.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import app.tgdrive.BuildConfig
import app.tgdrive.data.formatBytes
import app.tgdrive.library.LibraryState

internal val visibleSettingsRoutes=setOf("home","transfers","storage","index","privacy","vaults","diagnostics","about")
internal fun settingsTitle(page:String):String=when(page.substringBefore("#")){
    "transfers"->"Transfers";"storage"->"Storage & offline";"index"->"Library & recovery";"privacy"->"Privacy & security";"vaults"->"Encrypted vaults";"diagnostics"->"Diagnostics";"about"->"About TG Drive";else->"Settings"
}
internal data class SettingDestination(val title:String,val subtitle:String,val route:String,val icon:ImageVector)
internal fun settingDestinations(state:LibraryState)=listOf(
    SettingDestination("Telegram accounts",state.accountName.ifBlank{"Add or switch accounts"},"accounts",DriveIcons.AccountCircle),
    SettingDestination("Transfers","Network rules, retries and background work","transfers",DriveIcons.SwapVert),
    SettingDestination("Storage & offline","${formatBytes(state.cacheBytes)} cache · ${formatBytes(state.offlineBytes)} offline","storage",DriveIcons.Storage),
    SettingDestination("Library & recovery","Sync, export and restore library information","index",DriveIcons.Restore),
    SettingDestination("Privacy & security","${state.vaults.size} vaults · app lock and privacy","privacy",DriveIcons.Lock),
    SettingDestination("Diagnostics","Check availability and view a local report","diagnostics",DriveIcons.HealthAndSafety),
    SettingDestination("About TG Drive","Version ${BuildConfig.VERSION_NAME}","about",DriveIcons.Info))

@Composable
fun SettingsHome(state:LibraryState,query:String="",onOpen:(String)->Unit) {
    val entries=if(query.isBlank())settingDestinations(state)else (settingDestinations(state)+searchableSettings()).filter{(it.title+" "+it.subtitle).contains(query.trim(),true)}
    LazyColumn(Modifier.fillMaxSize(),contentPadding=PaddingValues(start=16.dp,end=16.dp,top=12.dp,bottom=24.dp),verticalArrangement=Arrangement.spacedBy(20.dp)) {
        if(entries.isEmpty())item{InfoCard("No matching settings","Try searching for storage, privacy or accounts.")}
        val groups=if(query.isNotBlank())listOf(entries)else listOf(entries.take(1),entries.drop(1).take(4),entries.takeLast(2))
        groups.filter{it.isNotEmpty()}.forEach{group->item{DriveGroup{group.forEachIndexed{index,entry->if(index>0)DriveDivider();ActionRow(entry.title,entry.subtitle,entry.icon){onOpen(entry.route)}}}}}
    }
}

internal fun searchableSettings():List<SettingDestination> = listOf(
    Triple("Unmetered network only","Wi-Fi · mobile data · network rules","transfers"),
    Triple("Transfer while charging","Battery · power","transfers"),
    Triple("Bounded automatic retries","Retry failed uploads · backoff","transfers"),
    Triple("Detect identical content","Duplicates · compare file contents","transfers"),
    Triple("Sync metadata when opening","Automatic library sync","transfers"),
    Triple("Average upload pace","Speed limit · KiB/s","transfers"),
    Triple("Part size","Chunks · MiB","transfers"),
    Triple("Background scan interval","Automatic folder backups · hours","transfers"),
    Triple("Cache budget","Storage · temporary files","storage"),
    Triple("Trash retention","Deleted files · days","storage"),
    Triple("Free verified local originals","Cleanup · free device space","storage"),
    Triple("Sync library","Refresh · latest metadata","index"),
    Triple("Back up library information","Cloud index · folders · tags","index"),
    Triple("Restore library information","Cloud checkpoint · recovery","index"),
    Triple("Export library information","Index file · backup","index"),
    Triple("Import library information","Index file · restore","index"),
    Triple("Encrypted vaults","Password · recovery key · lock vault","privacy"),
    Triple("App lock","Biometric · device credential · password","privacy"),
    Triple("Hide notification details","Private notifications","privacy"),
    Triple("Protect screenshots and recents","Secure screen","privacy"),
    Triple("Backup health notifications","Alerts · failures","privacy")
).map{(title,description,route)->SettingDestination(title,description,"$route#$title",DriveIcons.ChevronRight)}
internal val LocalSettingFocus=androidx.compose.runtime.compositionLocalOf { "" }
