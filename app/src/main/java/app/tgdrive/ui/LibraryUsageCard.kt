package app.tgdrive.ui

import androidx.compose.foundation.Canvas
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
    val colors=listOf(Color(0xFF62A9FF),Color(0xFF9C8FFF),Color(0xFF70D8C1),Color(0xFFFFCF82))
    val track=MaterialTheme.colorScheme.outlineVariant
    DriveCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(20.dp),verticalArrangement=Arrangement.spacedBy(16.dp)) {
            BoxWithConstraints(Modifier.fillMaxWidth()){
                val compact=maxWidth<320.dp||androidx.compose.ui.platform.LocalDensity.current.fontScale>1.3f
                val ring: @Composable ()->Unit = {
                    Box(Modifier.size(84.dp),contentAlignment=Alignment.Center){
                        Canvas(Modifier.fillMaxSize()){
                            val width=8.dp.toPx();drawArc(track,0f,360f,false,style=Stroke(width));var start=-90f
                            if(usage.bytes>0)usage.groups.forEachIndexed{index,bytes->val sweep=bytes.toFloat()/usage.bytes*360f;if(sweep>0)drawArc(colors[index],start,(sweep-3).coerceAtLeast(0f),false,style=Stroke(width));start+=sweep}
                        };Icon(DriveIcons.CloudQueue,null,Modifier.size(30.dp))
                    }
                }
                val summary: @Composable ()->Unit = {Column(verticalArrangement=Arrangement.spacedBy(4.dp)){
                    Text("Library storage",style=MaterialTheme.typography.labelMedium,color=MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(formatBytes(usage.bytes),style=MaterialTheme.typography.headlineMedium)
                    Text("${usage.count} files · active versions",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
                }}
                if(compact)Column(verticalArrangement=Arrangement.spacedBy(16.dp)){
                    Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically){ring();Spacer(Modifier.weight(1f));IconButton({details=true}){Icon(DriveIcons.Info,"About library storage")}}
                    summary()
                }else Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(18.dp)){
                    ring();Box(Modifier.weight(1f)){summary()};IconButton({details=true}){Icon(DriveIcons.Info,"About library storage")}
                }
            }
            BoxWithConstraints(Modifier.fillMaxWidth()){
                if(maxWidth<320.dp||androidx.compose.ui.platform.LocalDensity.current.fontScale>1.3f)Column(verticalArrangement=Arrangement.spacedBy(12.dp)){
                    Text("Offline copies: ${formatBytes(state.offlineBytes)}",style=MaterialTheme.typography.bodyMedium)
                    DriveButton(onAdd,Modifier.fillMaxWidth()){Icon(DriveIcons.Add,null,Modifier.size(20.dp));Spacer(Modifier.width(6.dp));Text("Add files")}
                }else Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically){
                    Column(Modifier.weight(1f)){Text("Offline copies",style=MaterialTheme.typography.labelMedium,color=MaterialTheme.colorScheme.onSurfaceVariant);Text(formatBytes(state.offlineBytes),style=MaterialTheme.typography.titleSmall)}
                    DriveButton(onAdd){Icon(DriveIcons.Add,null,Modifier.size(20.dp));Spacer(Modifier.width(6.dp));Text("Add files")}
                }
            }

            if(state.lockedVaults>0)Text("${state.lockedVaults} locked files are hidden",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
    if(details)AlertDialog(onDismissRequest={details=false},title={Text("Your indexed library")},text={Text("The total covers visible active file versions in TG Drive. Locked vaults, Trash, previous versions and other Telegram messages are excluded. The ring shows file types, not a storage quota. Offline copies are stored separately on this device.\n\nImages: ${formatBytes(usage.groups[0])}\nVideos: ${formatBytes(usage.groups[1])}\nAudio: ${formatBytes(usage.groups[2])}\nOther files: ${formatBytes(usage.groups[3])}")},confirmButton={TextButton({details=false}){Text("Done")}})
}
