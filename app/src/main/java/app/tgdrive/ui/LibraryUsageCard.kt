package app.tgdrive.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import app.tgdrive.data.formatBytes
import app.tgdrive.library.*
import kotlin.math.*

internal data class LibraryUsage(val count:Int,val bytes:Long,val groups:List<Long>)
internal fun libraryUsage(state:LibraryState):LibraryUsage {
    val files=state.items.filter{it.deletedAt==0L&&it.versionId.isNotEmpty()&&(state.itemVaults[it.id].orEmpty().isEmpty()||state.itemVaults[it.id] in state.unlockedVaultIds)}
    val groups=LongArray(4)
    files.forEach{val index=when{it.mime.startsWith("image/")->0;it.mime.startsWith("video/")->1;it.mime.startsWith("audio/")->2;else->3};groups[index]+=it.size.coerceAtLeast(0)}
    return LibraryUsage(files.size,groups.sum(),groups.toList())
}

private val usageLabels=listOf("Photos","Videos","Audio","Files")
private val usageColors=listOf(Color(0xFF37C8F0),Color(0xFF0087FF),Color(0xFFC49AF5),Color(0xFF969FA8))

/** Each angle is a share of indexed bytes, never an inferred Telegram storage quota. */
internal fun libraryArcShares(groups:List<Long>):List<Float> {
    val total=groups.sumOf{it.coerceAtLeast(0).toDouble()}
    return groups.map{if(total==0.0)0f else (it.coerceAtLeast(0)/total*180).toFloat()}
}

@Composable
internal fun LibraryUsageCard(state:LibraryState,onAdd:()->Unit) {
    val usage=remember(state.items,state.itemVaults,state.unlockedVaultIds){libraryUsage(state)}
    var details by remember{mutableStateOf(false)}
    Column(verticalArrangement=Arrangement.spacedBy(12.dp)) {
        DriveCard(onClick={details=true},modifier=Modifier.fillMaxWidth()) {
            Column(Modifier.padding(18.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
                Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically){
                    Text("Your library",Modifier.weight(1f),style=MaterialTheme.typography.titleLarge)
                    Icon(DriveIcons.Info,"About library storage",Modifier.size(22.dp))
                }
                LibrarySemicircle(usage)
                BoxWithConstraints {
                    val singleColumn=maxWidth<230.dp||LocalDensity.current.fontScale>1.5f
                    if(singleColumn)Column(verticalArrangement=Arrangement.spacedBy(12.dp)) {
                        usageLabels.indices.forEach{UsageLegend(it,usage.groups[it])}
                    }else Column(verticalArrangement=Arrangement.spacedBy(8.dp)) {
                        listOf(0 to 1,2 to 3).forEach{(left,right)->
                            Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(16.dp)) {
                                Box(Modifier.weight(1f)){UsageLegend(left,usage.groups[left])}
                                Box(Modifier.weight(1f)){UsageLegend(right,usage.groups[right])}
                            }
                        }
                    }
                }
                DriveDivider()
                BoxWithConstraints(Modifier.fillMaxWidth()) {
                    val stacked=maxWidth<260.dp||LocalDensity.current.fontScale>1.3f
                    val count:@Composable ()->Unit={UsageStat(DriveIcons.Description,"${usage.count} files")}
                    val offline:@Composable ()->Unit={UsageStat(DriveIcons.Download,"${formatBytes(state.offlineBytes)} offline")}
                    if(stacked)Column(verticalArrangement=Arrangement.spacedBy(12.dp)){count();offline()}
                    else Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween,verticalAlignment=Alignment.CenterVertically){count();offline()}
                }
                if(state.lockedVaults>0)Text("${state.lockedVaults} locked files are hidden",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        DriveButton(onAdd,Modifier.fillMaxWidth()){Icon(DriveIcons.Add,null,Modifier.size(24.dp));Spacer(Modifier.width(8.dp));Text("Add files")}
    }
    if(details)AlertDialog(onDismissRequest={details=false},title={Text("Your indexed library")},text={Text("The chart shows the types of data in your visible active file versions, not a storage limit. Locked vaults, Trash, previous versions and other Telegram messages are excluded. Offline copies are stored separately on this device.\n\nPhotos: ${formatBytes(usage.groups[0])}\nVideos: ${formatBytes(usage.groups[1])}\nAudio: ${formatBytes(usage.groups[2])}\nOther files: ${formatBytes(usage.groups[3])}",Modifier.verticalScroll(rememberScrollState()))},confirmButton={TextButton({details=false}){Text("Done")}})
}

@Composable private fun UsageLegend(index:Int,bytes:Long) {
    Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(10.dp)) {
        Box(Modifier.size(12.dp).background(usageColors[index],CircleShape))
        Column(verticalArrangement=Arrangement.spacedBy(2.dp)) {
            Text(usageLabels[index],style=MaterialTheme.typography.labelMedium)
            Text(formatBytes(bytes),style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable private fun UsageStat(icon:androidx.compose.ui.graphics.vector.ImageVector,text:String) {
    Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(8.dp)){
        Icon(icon,null,Modifier.size(22.dp));Text(text,style=MaterialTheme.typography.labelMedium)
    }
}

@Composable private fun LibrarySemicircle(usage:LibraryUsage) {
    val shares=remember(usage.groups){libraryArcShares(usage.groups)}
    val emptyColor=MaterialTheme.colorScheme.outlineVariant
    val largeText=LocalDensity.current.fontScale>1.3f
    Column(horizontalAlignment=Alignment.CenterHorizontally) {
        BoxWithConstraints(Modifier.fillMaxWidth().padding(horizontal=24.dp)) {
            val height=maxWidth/2+8.dp
            Box(Modifier.fillMaxWidth().height(height)) {
                Canvas(Modifier.fillMaxSize().semantics{contentDescription="Library composition by file size"}) {
                    val thickness=20.dp.toPx().coerceAtMost(size.width/10)
                    val center=Offset(size.width/2,size.height-thickness/2)
                    val radius=(size.width-thickness)/2
                    if(usage.bytes==0L)drawPath(roundedArc(center,radius,thickness,180f,180f),emptyColor)
                    else {
                        var start=180f
                        // Blue video share first, as in the approved dashboard; legend order remains Photos / Videos.
                        listOf(1,0,2,3).filter{shares[it]>0}.forEach{index->
                            val sweep=shares[index]
                            val gap=minOf(2f,sweep*.2f)
                            drawPath(roundedArc(center,radius,thickness,start+gap/2,sweep-gap),usageColors[index])
                            start+=sweep
                        }
                    }
                }
                if(!largeText)UsageTotal(usage,Modifier.align(Alignment.BottomCenter).padding(bottom=8.dp))
            }
        }
        if(largeText)UsageTotal(usage,Modifier.padding(top=12.dp))
    }
}

@Composable private fun UsageTotal(usage:LibraryUsage,modifier:Modifier=Modifier) {
    Column(modifier,horizontalAlignment=Alignment.CenterHorizontally,verticalArrangement=Arrangement.spacedBy(2.dp)) {
        Text(formatBytes(usage.bytes),style=MaterialTheme.typography.headlineLarge)
        Text("Stored in your library",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** Rounded annular segment: small shares stay proportional instead of expanding to a stroke cap. */
private fun roundedArc(center:Offset,radius:Float,width:Float,start:Float,sweep:Float):Path {
    val outer=radius+width/2;val inner=radius-width/2
    val end=start+sweep
    val corner=minOf(width*.18f,inner*(sweep*PI.toFloat()/180)/4)
    val outDelta=corner/outer*180/PI.toFloat();val inDelta=corner/inner*180/PI.toFloat()
    fun point(r:Float,degrees:Float):Offset{val angle=degrees*PI.toFloat()/180;return center+Offset(cos(angle)*r,sin(angle)*r)}
    fun bounds(r:Float)=Rect(center.x-r,center.y-r,center.x+r,center.y+r)
    return Path().apply {
        val first=point(outer,start+outDelta);moveTo(first.x,first.y)
        arcTo(bounds(outer),start+outDelta,sweep-outDelta*2,false)
        var control=point(outer,end);var next=point(outer-corner,end);quadraticTo(control.x,control.y,next.x,next.y)
        next=point(inner+corner,end);lineTo(next.x,next.y)
        control=point(inner,end);next=point(inner,end-inDelta);quadraticTo(control.x,control.y,next.x,next.y)
        arcTo(bounds(inner),end-inDelta,-sweep+inDelta*2,false)
        control=point(inner,start);next=point(inner+corner,start);quadraticTo(control.x,control.y,next.x,next.y)
        next=point(outer-corner,start);lineTo(next.x,next.y)
        control=point(outer,start);quadraticTo(control.x,control.y,first.x,first.y);close()
    }
}
