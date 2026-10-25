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
internal fun settingsTitle(page:String):String=when(page){
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
    val entries=settingDestinations(state).filter{query.isBlank()||(it.title+" "+it.subtitle).contains(query.trim(),true)}
    LazyColumn(Modifier.fillMaxSize(),contentPadding=PaddingValues(start=16.dp,end=16.dp,top=12.dp,bottom=24.dp),verticalArrangement=Arrangement.spacedBy(20.dp)) {
        if(entries.isEmpty())item{InfoCard("No matching settings","Try searching for storage, privacy or accounts.")}
        val groups=if(query.isNotBlank())listOf(entries)else listOf(entries.take(1),entries.drop(1).take(4),entries.takeLast(2))
        groups.filter{it.isNotEmpty()}.forEach{group->item{DriveGroup{group.forEachIndexed{index,entry->if(index>0)DriveDivider();ActionRow(entry.title,entry.subtitle,entry.icon){onOpen(entry.route)}}}}}
    }
}
