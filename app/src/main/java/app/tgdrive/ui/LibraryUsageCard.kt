package app.tgdrive.ui

import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import app.tgdrive.data.formatBytes
import app.tgdrive.library.*

internal data class LibraryUsage(val count:Int,val bytes:Long,val groups:List<Long>)
internal fun libraryUsage(state:LibraryState):LibraryUsage {
    val files=state.items.filter{it.deletedAt==0L&&it.versionId.isNotEmpty()&&(state.itemVaults[it.id].orEmpty().isEmpty()||state.itemVaults[it.id] in state.unlockedVaultIds)}
    val groups=LongArray(4)
    files.forEach{val index=when{it.mime.startsWith("image/")->0;it.mime.startsWith("video/")->1;it.mime.startsWith("audio/")->2;else->3};groups[index]+=it.size.coerceAtLeast(0)}
    return LibraryUsage(files.size,groups.sum(),groups.toList())
}

@Composable
internal fun LibraryUsageCard(state:LibraryState,onAdd:()->Unit) {
    val usage=remember(state.items,state.itemVaults,state.unlockedVaultIds){libraryUsage(state)}
    var details by remember{mutableStateOf(false)}
    DriveCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(18.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
            Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically) {
                Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(4.dp)) {
                    Text("Library storage",style=MaterialTheme.typography.labelMedium,color=MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(formatBytes(usage.bytes),style=MaterialTheme.typography.headlineLarge)
                }
                IconButton({details=true}){Icon(DriveIcons.Info,"About library storage")}
            }
            BoxWithConstraints(Modifier.fillMaxWidth()) {
                val offline: @Composable ()->Unit = {Column {
                    Text("${usage.count} files",style=MaterialTheme.typography.titleSmall)
                    Text("${formatBytes(state.offlineBytes)} offline",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
                }}
                if(maxWidth<280.dp||androidx.compose.ui.platform.LocalDensity.current.fontScale>1.3f)Column(verticalArrangement=Arrangement.spacedBy(12.dp)) {
                    offline()
                    DriveButton(onAdd,Modifier.fillMaxWidth()){Icon(DriveIcons.Add,null,Modifier.size(20.dp));Spacer(Modifier.width(8.dp));Text("Add files")}
                } else Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(12.dp)) {
                    Box(Modifier.weight(1f)){offline()}
                    DriveButton(onAdd){Icon(DriveIcons.Add,null,Modifier.size(20.dp));Spacer(Modifier.width(8.dp));Text("Add files")}
                }
            }
            if(state.lockedVaults>0)Text("${state.lockedVaults} locked files are hidden",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
    if(details)AlertDialog(onDismissRequest={details=false},title={Text("Your indexed library")},text={Text("The total covers visible active file versions in TG Drive. Locked vaults, Trash, previous versions and other Telegram messages are excluded. No storage quota is inferred from this total. Offline copies are stored separately on this device.\n\nImages: ${formatBytes(usage.groups[0])}\nVideos: ${formatBytes(usage.groups[1])}\nAudio: ${formatBytes(usage.groups[2])}\nOther files: ${formatBytes(usage.groups[3])}",Modifier.verticalScroll(rememberScrollState()))},confirmButton={TextButton({details=false}){Text("Done")}})
}
