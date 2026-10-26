package app.tgdrive.ui

import android.net.Uri
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.tgdrive.SessionBundle
import app.tgdrive.data.formatBytes
import app.tgdrive.library.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private fun transferCallbacks(bundle:SessionBundle,system:SystemActions,onCancel:(String)->Unit={})=TransferCallbacks(
    pause={id->bundle.actions.run{bundle.transfers.pause(id)}},
    retry={id->bundle.actions.run{bundle.transfers.retry(id);system.startTransfers()}},
    prioritize={id->bundle.actions.run{bundle.transfers.prioritize(id)}},cancel=onCancel,
    start={bundle.actions.run{bundle.library.state.value.tasks.filter{it.state==TaskState.PAUSED}.forEach{bundle.transfers.retry(it.id)};system.startTransfers()}},
    pauseAll={bundle.actions.run{bundle.library.state.value.tasks.filter{it.state !in finishedTransferStates&&it.state !in setOf(TaskState.PAUSED,TaskState.FAILED)}.forEach{bundle.transfers.pause(it.id)}}},
    clear={bundle.actions.run{bundle.transfers.clearFinished()}},rules=system.transferSettings,vaults=system.vaultSettings,
)

@Composable fun TransfersPage(state:LibraryState,bundle:SessionBundle,system:SystemActions) {
    var cancelId by rememberSaveable{mutableStateOf("")}
    TransfersContent(state,transferCallbacks(bundle,system){cancelId=it},bundle)
    if(cancelId.isNotEmpty())ConfirmDialog("Cancel this operation?","Your original source is retained. Parts already acknowledged by Telegram may remain until a later cleanup; cancellation does not erase a completed file.","Cancel operation",{cancelId=""}){
        val id=cancelId;cancelId="";bundle.actions.run{bundle.transfers.cancel(id)}
    }
}

@Composable internal fun TransferPageMenu(state:LibraryState,bundle:SessionBundle,system:SystemActions) {
    TransferGlobalMenu(state,transferCallbacks(bundle,system))
}

@Composable internal fun TransferGlobalMenu(state:LibraryState,actions:TransferCallbacks) {
    var expanded by remember{mutableStateOf(false)}
    var info by remember{mutableStateOf(false)}
    Box {
        IconButton({expanded=true}){Icon(DriveIcons.MoreVert,"Transfer options")}
        DropdownMenu(expanded,{expanded=false},tonalElevation=0.dp){
            DropdownMenuItem(text={Text("Start / resume transfers")},enabled=canStartTransfers(state),leadingIcon={Icon(DriveIcons.PlayCircle,null)},onClick={expanded=false;actions.start()})
            DropdownMenuItem(text={Text("Pause all")},enabled=state.tasks.any{it.state !in finishedTransferStates&&it.state !in setOf(TaskState.FAILED,TaskState.PAUSED)},leadingIcon={Icon(DriveIcons.PauseCircle,null)},onClick={expanded=false;actions.pauseAll()})
            DropdownMenuItem(text={Text("Clear finished")},enabled=state.tasks.any{it.state in finishedTransferStates},leadingIcon={Icon(DriveIcons.CleaningServices,null)},onClick={expanded=false;actions.clear()})
            HorizontalDivider()
            DropdownMenuItem(text={Text("Transfer settings")},leadingIcon={Icon(DriveIcons.Settings,null)},onClick={expanded=false;actions.rules()})
            DropdownMenuItem(text={Text("About transfers")},leadingIcon={Icon(DriveIcons.Info,null)},onClick={expanded=false;info=true})
        }
    }
    if(info)AlertDialog(onDismissRequest={info=false},title={Text("Saved transfers")},text={Text("Downloads can resume through TDLib. Uploads retain completed parts; an interrupted active part may restart. A file is saved only after Telegram acknowledges it and its library information is recorded. Telegram can impose a sending countdown. Network, charging, vault and Android background rules still apply.")},confirmButton={TextButton({info=false}){Text("Done")}})
}

@Composable internal fun TransfersContent(state:LibraryState,actions:TransferCallbacks=TransferCallbacks(),bundle:SessionBundle?=null) {
    var filter by rememberSaveable{mutableStateOf(TransferFilter.ALL)}
    var expanded by remember{mutableStateOf(false)}
    val remaining by produceState(0L,state.sendCooldownUntil){
        while(true){value=((state.sendCooldownUntil-now()+999)/1000).coerceAtLeast(0);if(value==0L)break;kotlinx.coroutines.delay(1000)}
    }
    val sections=remember(state.tasks,filter){transferSections(state.tasks,filter)}
    LazyColumn(Modifier.fillMaxSize(),contentPadding=PaddingValues(start=16.dp,end=16.dp,top=4.dp,bottom=32.dp)) {
        item("filter") {
            Box {
                TextButton({expanded=true},contentPadding=PaddingValues(horizontal=8.dp),modifier=Modifier.semantics{contentDescription="Filter transfers";stateDescription="${filter.label}, ${if(expanded)"expanded"else"collapsed"}"}) {
                    Text(filter.label,color=MaterialTheme.colorScheme.onSurfaceVariant);Spacer(Modifier.width(6.dp));Icon(DriveIcons.ChevronDown,null,Modifier.size(20.dp),tint=MaterialTheme.colorScheme.onSurfaceVariant)
                }
                DropdownMenu(expanded,{expanded=false},tonalElevation=0.dp){
                    TransferFilter.entries.forEach{choice->DropdownMenuItem(modifier=Modifier.semantics{selected=choice==filter},text={Text(choice.label)},trailingIcon={if(choice==filter)Icon(DriveIcons.Check,null)},onClick={filter=choice;expanded=false})}
                }
            }
        }
        if(remaining>0)item("cooldown") {
            Box(Modifier.padding(top=12.dp)){InfoCard("Sending resumes in ${remaining}s","Telegram asked this account to wait. Your uploaded parts and queue are saved.",DriveIcons.CloudSync)}
        }
        if(state.pendingOperations>0&&state.tasks.none{it.state==TaskState.SYNCING_METADATA})item("metadata") {
            TextButton(actions.start,Modifier.padding(top=8.dp)){Icon(DriveIcons.CloudSync,null);Spacer(Modifier.width(8.dp));Text("Finish library sync")}
        }
        if(sections.isEmpty())item("empty") {
            Box(Modifier.padding(top=24.dp)){InfoCard(if(filter==TransferFilter.ALL)"Nothing here yet"else"No ${filter.label.lowercase()} transfers",if(filter==TransferFilter.ALL)"Add files or choose a download. Your transfers will appear here."else"Try another transfer filter.",DriveIcons.SwapVert)}
        }
        sections.forEach{section->
            item("heading:${section.key}"){
                TransferSectionHeader(section,section.key=="today"||(section.key=="earlier"&&sections.none{it.key=="today"}),actions.clear)
            }
            itemsIndexed(section.tasks,key={_,task->"transfer:${task.id}"}){index,task->
                val first=index==0;val last=index==section.tasks.lastIndex
                val shape=RoundedCornerShape(topStart=if(first)24.dp else 0.dp,topEnd=if(first)24.dp else 0.dp,bottomStart=if(last)24.dp else 0.dp,bottomEnd=if(last)24.dp else 0.dp)
                Surface(Modifier.fillMaxWidth(),shape=shape,color=MaterialTheme.colorScheme.surface){
                    Column {
                        TransferTaskRow(task,state,actions,bundle,remaining>0)
                        if(!last)HorizontalDivider(Modifier.padding(horizontal=16.dp),color=MaterialTheme.colorScheme.outlineVariant)
                    }
                }
            }
        }
    }
}

@Composable private fun TransferSectionHeader(section:TransferSection,clear:Boolean,onClear:()->Unit) {
    val large=LocalDensity.current.fontScale>1.3f
    Column(Modifier.fillMaxWidth().padding(top=16.dp,bottom=8.dp)) {
        Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(10.dp)) {
            Row(Modifier.weight(1f),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(10.dp)){
                Text(section.title,Modifier.weight(1f,false).semantics{heading()},style=MaterialTheme.typography.titleMedium)
                Text("${section.tasks.size}",style=MaterialTheme.typography.bodyMedium,color=MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if(clear&&!large)TextButton(onClear,contentPadding=PaddingValues(horizontal=4.dp)){Text("Clear finished",style=MaterialTheme.typography.labelMedium)}
        }
        if(clear&&large)TextButton(onClear,contentPadding=PaddingValues(horizontal=0.dp)){Text("Clear finished",style=MaterialTheme.typography.labelMedium)}
    }
}

@Composable private fun TransferTaskRow(task:TransferTask,state:LibraryState,actions:TransferCallbacks,bundle:SessionBundle?,cooldownActive:Boolean) {
    var menu by remember(task.id){mutableStateOf(false)}
    var details by remember(task.id){mutableStateOf(false)}
    val protected=task.vaultId.isNotEmpty()&&task.vaultId !in state.unlockedVaultIds
    val title=if(protected)"Protected file"else task.title
    val running=task.state in setOf(TaskState.PREPARING,TaskState.TRANSFERRING)
    val completed=task.state==TaskState.COMPLETED
    val completion=completedTransferLabel(task.kind)
    val wideText=LocalDensity.current.fontScale>1.3f
    val progress by animateFloatAsState(if(task.total>0)(task.done.toDouble()/task.total).toFloat().coerceIn(0f,1f)else 0f,tween(180),label="transfer-progress")
    Row(Modifier.fillMaxWidth().combinedClickable(onClickLabel="Transfer actions",onClick={menu=true},onLongClick={menu=true}).padding(start=14.dp,end=8.dp,top=if(running)16.dp else 12.dp,bottom=if(running)16.dp else 12.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(12.dp)){
        TransferThumbnail(task,state,bundle,Modifier.size(if(wideText)40.dp else if(running)56.dp else 44.dp))
        Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(6.dp)) {
            Text(title,maxLines=2,overflow=TextOverflow.Ellipsis,style=MaterialTheme.typography.titleSmall)
            val confirming=task.kind==TaskKind.UPLOAD&&task.state==TaskState.TRANSFERRING&&task.total>0&&task.done>=task.total
            val label=when{completed->completion;confirming->"Confirming saved message";task.state==TaskState.TRANSFERRING->when(task.kind){TaskKind.UPLOAD->"Uploading";TaskKind.DOWNLOAD,TaskKind.PIN->"Downloading";else->transferLabel(task.state)};else->transferLabel(task.state)}
            if(!completed&&task.state!=TaskState.QUEUED)Text(label,style=MaterialTheme.typography.bodySmall,color=if(task.state==TaskState.FAILED)MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
            if(running){
                if(task.total<=0||confirming)LinearProgressIndicator(Modifier.fillMaxWidth().height(3.dp),color=DriveStyle.ChartBlue,trackColor=MaterialTheme.colorScheme.outlineVariant)
                else LinearProgressIndicator(progress={progress},modifier=Modifier.fillMaxWidth().height(3.dp),color=DriveStyle.ChartBlue,trackColor=MaterialTheme.colorScheme.outlineVariant,gapSize=0.dp,drawStopIndicator={})
            }
            val bytes=when {
                task.total<=0->""
                task.kind in setOf(TaskKind.OCR,TaskKind.EXPORT)->"${task.done} / ${task.total}"
                completed->"${formatBytes(task.total)} · $completion"
                task.state==TaskState.QUEUED->formatBytes(task.total)
                else->"${formatBytes(task.done)} / ${formatBytes(task.total)}"
            }
            val speed=if(running&&task.speedBytesPerSecond>0)"${formatBytes(task.speedBytesPerSecond)}/s"else""
            if(bytes.isNotEmpty()||speed.isNotEmpty()) {
                if(wideText)Column{if(bytes.isNotEmpty())TransferMetric(bytes);if(speed.isNotEmpty())TransferMetric(speed)}
                else Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(8.dp)){if(bytes.isNotEmpty())Box(Modifier.weight(1f)){TransferMetric(bytes)};if(speed.isNotEmpty())TransferMetric(speed)}
            }
            if(completed&&bytes.isEmpty())TransferMetric(completion)
            if(task.error.isNotBlank()&&!completed&&task.state!=TaskState.CANCELLED)Text(if(protected)"Unlock the vault to view transfer details."else task.error,style=MaterialTheme.typography.bodySmall,maxLines=3,overflow=TextOverflow.Ellipsis,color=if(task.state==TaskState.FAILED)MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Box {
            when {
                running->IconButton({actions.pause(task.id)}){Icon(DriveIcons.PauseCircle,"Pause $title",Modifier.size(28.dp),tint=MaterialTheme.colorScheme.primary)}
                completed->IconButton({menu=true}){Icon(DriveIcons.CheckCircle,"Actions for $title, ${completion.lowercase()}",Modifier.size(24.dp),tint=DriveStyle.Success)}
                else->IconButton({menu=true}){Icon(DriveIcons.MoreVert,"Actions for $title")}
            }
            DropdownMenu(menu,{menu=false},tonalElevation=0.dp){
                DropdownMenuItem(text={Text("Transfer details")},leadingIcon={Icon(DriveIcons.Info,null)},onClick={menu=false;details=true})
                if(task.state !in finishedTransferStates){
                    if(running)DropdownMenuItem(text={Text("Pause")},leadingIcon={Icon(DriveIcons.PauseCircle,null)},onClick={menu=false;actions.pause(task.id)})
                    if(task.state in setOf(TaskState.PAUSED,TaskState.FAILED,TaskState.RETRY_WAIT,TaskState.WAITING_FOR_DEVICE))DropdownMenuItem(text={Text(if(task.state==TaskState.FAILED)"Try again"else"Resume")},enabled=(task.kind!=TaskKind.UPLOAD||!cooldownActive)&&transferCanResume(task,state.sendCooldownUntil),leadingIcon={Icon(DriveIcons.PlayCircle,null)},onClick={menu=false;actions.retry(task.id)})
                    if(task.priority==0&&task.state in setOf(TaskState.QUEUED,TaskState.PAUSED,TaskState.RETRY_WAIT))DropdownMenuItem(text={Text("Do this next")},onClick={menu=false;actions.prioritize(task.id)})
                    if(task.state in setOf(TaskState.WAITING_FOR_WIFI,TaskState.WAITING_FOR_CHARGING,TaskState.WAITING_FOR_NETWORK))DropdownMenuItem(text={Text("Network & power rules")},onClick={menu=false;actions.rules()})
                    if(task.state==TaskState.WAITING_FOR_UNLOCK)DropdownMenuItem(text={Text("Unlock vault")},leadingIcon={Icon(DriveIcons.LockOpen,null)},onClick={menu=false;actions.vaults()})
                    DropdownMenuItem(text={Text("Cancel transfer")},leadingIcon={Icon(DriveIcons.Close,null)},onClick={menu=false;actions.cancel(task.id)})
                }else DropdownMenuItem(text={Text("Clear finished")},onClick={menu=false;actions.clear()})
            }
        }
    }
    if(details)AlertDialog(onDismissRequest={details=false},title={Text(title)},text={
        Column(Modifier.heightIn(max=360.dp).verticalScroll(androidx.compose.foundation.rememberScrollState()),verticalArrangement=Arrangement.spacedBy(12.dp)){
            Text(task.kind.name.lowercase().replaceFirstChar{it.uppercase()}+" · "+if(completed)completion else transferLabel(task.state))
            if(task.total>0)Text(if(task.kind in setOf(TaskKind.OCR,TaskKind.EXPORT))"${task.done} / ${task.total}"else"${formatBytes(task.done)} / ${formatBytes(task.total)}")
            if(task.error.isNotBlank())Text(if(protected)"Unlock the vault to view transfer details."else task.error)
        }
    },confirmButton={TextButton({details=false}){Text("Done")}})
}

@Composable private fun TransferMetric(text:String)=Text(text,style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.onSurfaceVariant)

@Composable private fun TransferThumbnail(task:TransferTask,state:LibraryState,bundle:SessionBundle?,modifier:Modifier) {
    val protected=task.vaultId.isNotEmpty()&&task.vaultId !in state.unlockedVaultIds
    val item=if(protected)null else state.items.firstOrNull{it.id==task.itemId&&it.deletedAt==0L&&(task.versionId.isEmpty()||it.versionId==task.versionId)}
    val mime=remember(task.payload,item?.mime){item?.mime?:runCatching{task.payload.model<UploadSpec>().mime}.getOrDefault("application/octet-stream")}
    if(item!=null){FileThumbnail(item,bundle,modifier.clip(RoundedCornerShape(10.dp)));return}
    val request=remember(task.id,task.source,protected,bundle){Any()}
    val bitmap by produceState<Pair<Any,android.graphics.Bitmap?>?>(null,request){
        value=null
        if(!protected&&bundle!=null&&task.kind==TaskKind.UPLOAD&&listOf("image/","video/","audio/").any{mime.startsWith(it)}){
            try{
                val uri=Uri.parse(task.source)
                if(uri.scheme in setOf("file","content"))value=request to withContext(Dispatchers.IO){ThumbnailTools(bundle.library).create(uri,mime)?.let{android.graphics.BitmapFactory.decodeByteArray(it,0,it.size)}}
            }catch(e:CancellationException){throw e}catch(_:Exception){/* A thumbnail never controls transfer success. */}
        }
    }
    val visible=bitmap?.takeIf{it.first===request&&!protected}?.second
    if(visible!=null)Image(visible.asImageBitmap(),null,modifier.clip(RoundedCornerShape(10.dp)),contentScale=androidx.compose.ui.layout.ContentScale.Crop)
    else Box(modifier,contentAlignment=Alignment.Center){Icon(if(protected)DriveIcons.Lock else fileIcon(mime),null,Modifier.size(30.dp),tint=MaterialTheme.colorScheme.onSurfaceVariant)}
}
