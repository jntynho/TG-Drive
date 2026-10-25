@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package app.tgdrive.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.tgdrive.BackgroundWork
import app.tgdrive.SessionBundle
import app.tgdrive.data.formatBytes
import app.tgdrive.library.*
import java.text.DateFormat
import java.util.Date

@Composable fun TransfersPage(state:LibraryState,bundle:SessionBundle,system:SystemActions){
    var filter by rememberSaveable{mutableStateOf("All")};var cancelId by remember{mutableStateOf("")}
    val remaining by produceState(0L,state.sendCooldownUntil){
        while(true){value=((state.sendCooldownUntil-now()+999)/1000).coerceAtLeast(0);if(value==0L)break;kotlinx.coroutines.delay(1000)}
    }
    val tasks=remember(state.tasks,filter){state.tasks.filter{when(filter){"Failed"->it.state==TaskState.FAILED;"Done"->it.state==TaskState.COMPLETED;"Active"->it.state !in setOf(TaskState.COMPLETED,TaskState.CANCELLED,TaskState.FAILED);else->true}}.sortedByDescending{it.createdAt}}
    LazyColumn(Modifier.fillMaxSize(),contentPadding=PaddingValues(start=16.dp,end=16.dp,top=16.dp,bottom=32.dp),verticalArrangement=Arrangement.spacedBy(14.dp)){
        item{Text("Uploads, downloads and saved work",color=MaterialTheme.colorScheme.onSurfaceVariant)}
        item{TransferSummary(state)}
        item{FlowRow(horizontalArrangement=Arrangement.spacedBy(8.dp)){
            val running=state.tasks.any{it.state in setOf(TaskState.PREPARING,TaskState.TRANSFERRING)}
            val paused=state.tasks.filter{it.state==TaskState.PAUSED}
            DriveButton(enabled=paused.isNotEmpty()||!running||state.pendingOperations>0,onClick={bundle.actions.run{paused.forEach{bundle.transfers.retry(it.id)};system.startTransfers()}}){Icon(DriveIcons.PlayArrow,null);Text(if(paused.isNotEmpty())"Resume paused"else if(running)"Transfers running"else if(state.pendingOperations>0)"Finish syncing"else"Start transfers")}
            TextButton(onClick={bundle.actions.run{bundle.transfers.clearFinished()}}){Text("Clear finished")}
        }}
        if(remaining>0)item{InfoCard("Sending resumes in ${remaining}s","Telegram asked this account to wait. Your uploaded parts and queue are saved.",DriveIcons.CloudSync)}
        item{LazyRow(horizontalArrangement=Arrangement.spacedBy(8.dp)){items(listOf("All","Active","Failed","Done")){label->DriveFilterChip(filter==label,{filter=label},label={Text(label)})}}}
        if(tasks.isEmpty())item{InfoCard("Nothing here yet","Add files or choose a download. Each operation will appear here.",DriveIcons.SwapVert)}
        items(tasks,key={it.id}){task->DriveCard(Modifier.fillMaxWidth()){
            Column(Modifier.padding(16.dp),verticalArrangement=Arrangement.spacedBy(10.dp)){
                Row(verticalAlignment=Alignment.CenterVertically){Icon(if(task.kind==TaskKind.UPLOAD)DriveIcons.CloudUpload else DriveIcons.Download,null,tint=MaterialTheme.colorScheme.primary);Spacer(Modifier.width(10.dp));Text(if(task.vaultId.isNotEmpty()&&task.vaultId !in state.unlockedVaultIds)"Protected file" else task.title,Modifier.weight(1f),style=MaterialTheme.typography.titleSmall);Text(task.kind.name.lowercase().replaceFirstChar{it.uppercase()},style=MaterialTheme.typography.labelSmall)}
                Text(if(task.kind==TaskKind.UPLOAD&&task.state==TaskState.TRANSFERRING&&task.total>0&&task.done>=task.total)"Confirming saved message"else transferLabel(task.state),color=if(task.state==TaskState.FAILED)MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,style=MaterialTheme.typography.labelLarge)
                if(task.state in setOf(TaskState.PREPARING,TaskState.TRANSFERRING)){
                    if(task.kind==TaskKind.UPLOAD&&task.done>=task.total)LinearProgressIndicator(Modifier.fillMaxWidth())else if(task.total>0)LinearProgressIndicator(progress={(task.done.toFloat()/task.total).coerceIn(0f,1f)},modifier=Modifier.fillMaxWidth())else LinearProgressIndicator(Modifier.fillMaxWidth())
                }
                if(task.total>0)Text(if(task.kind in setOf(TaskKind.OCR,TaskKind.EXPORT))"${task.done} / ${task.total}" else "${formatBytes(task.done)} / ${formatBytes(task.total)}"+(if(task.speedBytesPerSecond>0)" · ${formatBytes(task.speedBytesPerSecond)}/s" else ""),style=MaterialTheme.typography.bodySmall)
                if(task.error.isNotBlank())Text(if(task.vaultId.isNotEmpty()&&task.vaultId !in state.unlockedVaultIds)"Unlock the vault to view transfer details." else task.error,style=MaterialTheme.typography.bodySmall,color=if(task.state==TaskState.FAILED)MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
                FlowRow(horizontalArrangement=Arrangement.spacedBy(10.dp)){
                    if(task.state !in setOf(TaskState.COMPLETED,TaskState.CANCELLED)){
                        if(task.state in setOf(TaskState.WAITING_FOR_WIFI,TaskState.WAITING_FOR_CHARGING))TextButton(system.transferSettings){Text("Network & power rules")}
                        if(task.state==TaskState.WAITING_FOR_UNLOCK)TextButton(system.vaultSettings){Text("Unlock vault")}
                        if(task.state==TaskState.WAITING_FOR_NETWORK)Text("Connect to a network to continue.",style=MaterialTheme.typography.bodySmall)

                        if(task.state in setOf(TaskState.PAUSED,TaskState.FAILED,TaskState.RETRY_WAIT,TaskState.WAITING_FOR_DEVICE))TextButton(enabled=remaining==0L || task.kind!=TaskKind.UPLOAD,onClick={bundle.actions.run{bundle.transfers.retry(task.id);system.startTransfers()}}){Text(if(task.state==TaskState.FAILED)"Try again"else"Resume")}
                        else TextButton(onClick={bundle.actions.run{bundle.transfers.pause(task.id)}}){Text("Pause")}
                        if(task.priority==0&&task.state in setOf(TaskState.QUEUED,TaskState.PAUSED,TaskState.RETRY_WAIT))TextButton(onClick={bundle.actions.run{bundle.transfers.prioritize(task.id)}}){Text("Do this next")}
                        TextButton(onClick={cancelId=task.id}){Text("Cancel")}
                    }
                }
            }
        }}
        item{InfoCard("Resume has real limits","Downloads can resume through TDLib. Uploads retain completed parts; an interrupted active part may restart. Failed operations are never counted as successful.",DriveIcons.Info)}
    }
    if(cancelId.isNotEmpty())ConfirmDialog("Cancel this operation?","Your original source is retained. Parts already acknowledged by Telegram may remain until a later cleanup; cancellation does not erase a completed file.","Cancel operation",{cancelId=""}){val id=cancelId;bundle.actions.run{bundle.transfers.cancel(id)};cancelId=""}
}

@Composable fun BackupsPage(state:LibraryState,bundle:SessionBundle,system:SystemActions){
    var destination by rememberSaveable{mutableStateOf("")};var vault by rememberSaveable{mutableStateOf("")};var pickFolder by remember{mutableStateOf(false)}
    var removeId by remember{mutableStateOf("")};var rescanId by remember{mutableStateOf<String?>(null)}
    val last=state.rules.maxOfOrNull{it.lastSuccessAt}?:0
    val failed=state.tasks.count{it.ruleId.isNotEmpty()&&it.state==TaskState.FAILED}
    LazyColumn(Modifier.fillMaxSize(),contentPadding=PaddingValues(start=16.dp,end=16.dp,top=16.dp,bottom=32.dp),verticalArrangement=Arrangement.spacedBy(14.dp)){
        item{Text("Keep a copy of the folders you choose.",color=MaterialTheme.colorScheme.onSurfaceVariant)}
        item{InfoCard(if(last==0L)"No successful automatic backup yet" else "Last successful file backup",if(last==0L)"Create a rule and verify a downloaded sample before relying on it." else DateFormat.getDateTimeInstance().format(Date(last)),DriveIcons.CloudDone)}
        if(failed>0)item{InfoCard("$failed files need attention","Open Activity to inspect reasons and retry only failed files.",DriveIcons.WarningAmber)}
        item{Text("Destination",style=MaterialTheme.typography.titleMedium);TextButton(onClick={pickFolder=true}){Icon(DriveIcons.Folder,null);Spacer(Modifier.width(8.dp));Text(folderPath(destination,state.folders))}}
        if(state.vaults.isNotEmpty())item{LazyRow(horizontalArrangement=Arrangement.spacedBy(8.dp)){
            item{DriveFilterChip(vault.isEmpty(),{vault="";destination=""},label={Text("Personal")})}
            items(state.vaults,key={it.id}){v->DriveFilterChip(vault==v.id,{vault=v.id;destination=""},label={Text(v.label)})}
        }}
        item{DriveButton(onClick={system.backupFolder(destination,vault)},modifier=Modifier.fillMaxWidth()){Icon(DriveIcons.CreateNewFolder,null);Spacer(Modifier.width(8.dp));Text("Choose a folder to back up")}}
        if(state.rules.isNotEmpty())item{TextButton(onClick={system.scanBackups(null,false)}){Text("Scan all enabled rules now")}}
        items(state.rules,key={it.id}){rule->
            val toggle=remember(rule.id,bundle){PreferenceEditor(Preferences(wifiOnly=rule.enabled),bundle.library.scope,
                read={Preferences(wifiOnly=bundle.library.state.value.rules.firstOrNull{it.id==rule.id}?.enabled?:false)},
                save={change->bundle.library.setRuleEnabled(rule.id,change(Preferences(wifiOnly=bundle.library.state.value.rules.firstOrNull{it.id==rule.id}?.enabled?:false)).wifiOnly);BackgroundWork.schedule(bundle.library.context,bundle.profile.id,state.preferences.scanIntervalHours)},report=bundle.library::report)}
            LaunchedEffect(rule.enabled){toggle.observe(Preferences(wifiOnly=rule.enabled))}
            DriveCard(Modifier.fillMaxWidth()){
            Column(Modifier.padding(16.dp),verticalArrangement=Arrangement.spacedBy(9.dp)){
                Row(verticalAlignment=Alignment.CenterVertically){Text(if(rule.vaultId.isNotEmpty()&&rule.vaultId !in state.unlockedVaultIds)"Protected backup" else rule.name,Modifier.weight(1f),fontWeight=FontWeight.SemiBold);DriveSwitch(toggle.value.wifiOnly,{enabled->toggle.update{it.copy(wifiOnly=enabled)}})}
                Text("Destination: ${folderPath(rule.folderId,state.folders)}",style=MaterialTheme.typography.bodySmall)
                Text(if(rule.lastScanAt==0L)"Not scanned yet" else "Last scan: ${DateFormat.getDateTimeInstance().format(Date(rule.lastScanAt))}",style=MaterialTheme.typography.bodySmall)
                Text(if(rule.lastSuccessAt==0L)"No file success recorded yet" else "Last file success: ${DateFormat.getDateTimeInstance().format(Date(rule.lastSuccessAt))}",style=MaterialTheme.typography.bodySmall)
                if(rule.error.isNotBlank())Text(if(rule.vaultId.isNotEmpty()&&rule.vaultId !in state.unlockedVaultIds)"Unlock the vault to inspect this backup." else rule.error,color=MaterialTheme.colorScheme.error,style=MaterialTheme.typography.bodySmall)
                val work=state.tasks.filter{it.ruleId==rule.id&&it.state!=TaskState.CANCELLED}
                if(rule.lastScanAt>0)Text("Last scan: ${rule.lastScanDiscovered} checked · ${rule.lastScanQueued} queued · ${if(rule.lastScanComplete)"scan complete"else"more work or attention needed"}",style=MaterialTheme.typography.bodySmall)
                Text("${work.count{it.state==TaskState.COMPLETED}} saved · ${work.count{it.state !in setOf(TaskState.COMPLETED,TaskState.FAILED)}} waiting · ${work.count{it.state==TaskState.FAILED}} need attention",style=MaterialTheme.typography.bodySmall)
                if(rule.error.contains("permission",true)||rule.error.contains("folder",true))TextButton({system.reconnectBackup(rule.id)}){Text("Reconnect folder")}
                FlowRow{TextButton(onClick={system.scanBackups(rule.id,false)}){Text(if(rule.lastScanComplete||rule.lastScanAt==0L)if(rule.enabled)"Scan now"else"Run once"else"Continue scan")};TextButton(onClick={rescanId=rule.id}){Text("Rescan all")};TextButton(onClick={removeId=rule.id}){Text("Remove")}}
            }
        }}
        item{InfoCard("Upload-only by default","Deleting a local original does not delete the cloud copy. Removing a rule keeps uploaded files and receipts. Android and device battery policies can delay background work.",DriveIcons.Shield)}
        item{InfoCard("Folders you explicitly grant","This is not a full-phone or private-app-data backup. Use Android's folder picker to grant access, and check the backup health after permission or device changes.",DriveIcons.Folder)}
    }
    if(pickFolder)FolderPicker(state,destination,{pickFolder=false}){id->destination=id;vault=state.folderVaults[id].orEmpty();pickFolder=false}
    val form=rememberFormAction()
    if(removeId.isNotEmpty())ConfirmDialog("Remove backup rule?","Uploaded files and per-file receipts are retained. No cloud content is deleted.","Remove rule",{removeId=""},busy=form.busy,error=form.error){val id=removeId;form.run({removeId=""}){bundle.library.removeRule(id)}}
    rescanId?.let{id->ConfirmDialog("Rescan including old content?","This ignores the upload receipt and can bring back content you deliberately removed. It may create additional copies. Normal Scan now is safer.","Rescan all",{rescanId=null}){system.scanBackups(id,true);rescanId=null}}
}

internal fun transferLabel(state:TaskState):String=when(state){
    TaskState.SYNCING_METADATA->"Finishing library sync"
    TaskState.WAITING_FOR_WIFI->"Waiting for Wi-Fi"
    TaskState.WAITING_FOR_CHARGING->"Waiting for charging"
    TaskState.WAITING_FOR_UNLOCK->"Waiting for vault unlock"
    TaskState.WAITING_FOR_NETWORK->"Waiting for connection"
    TaskState.RETRY_WAIT->"Waiting to retry"
    TaskState.COMPLETED->"Saved successfully"
    TaskState.PREPARING->"Preparing file"
    TaskState.TRANSFERRING->"Transferring"
    else->state.name.lowercase().replace('_',' ').replaceFirstChar{it.uppercase()}
}
@Composable internal fun TransferSummary(state:LibraryState) {
    val total=state.tasks.count{it.state!=TaskState.CANCELLED}
    val done=state.tasks.count{it.state==TaskState.COMPLETED}
    val failed=state.tasks.count{it.state==TaskState.FAILED}
    DriveCard(Modifier.fillMaxWidth()){Column(Modifier.padding(18.dp),verticalArrangement=Arrangement.spacedBy(8.dp)){
        Text("$done of $total operations complete",style=MaterialTheme.typography.titleMedium)
        if(total>0)LinearProgressIndicator(progress={done.toFloat()/total},modifier=Modifier.fillMaxWidth())
        if(failed>0)Text("$failed need attention",color=MaterialTheme.colorScheme.error)
        if(state.pendingOperations>0)Text("Library changes are waiting to sync",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
    }}
}
