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
        item{FlowRow(horizontalArrangement=Arrangement.spacedBy(8.dp)){DriveButton(onClick=system.startTransfers){Icon(DriveIcons.PlayArrow,null);Text("Process queue")};TextButton(onClick={bundle.actions.run{bundle.transfers.clearFinished()}}){Text("Clear finished")}}}
        if(remaining>0)item{InfoCard("Sending resumes in ${remaining}s","Telegram asked this account to wait. Your uploaded parts and queue are saved.",DriveIcons.CloudSync)}
        item{LazyRow(horizontalArrangement=Arrangement.spacedBy(8.dp)){items(listOf("All","Active","Failed","Done")){label->DriveFilterChip(filter==label,{filter=label},label={Text(label)})}}}
        if(tasks.isEmpty())item{InfoCard("Nothing here yet","Add files or choose a download. Each operation will appear here.",DriveIcons.SwapVert)}
        items(tasks,key={it.id}){task->DriveCard(Modifier.fillMaxWidth()){
            Column(Modifier.padding(16.dp),verticalArrangement=Arrangement.spacedBy(10.dp)){
                Row(verticalAlignment=Alignment.CenterVertically){Icon(if(task.kind==TaskKind.UPLOAD)DriveIcons.CloudUpload else DriveIcons.Download,null,tint=MaterialTheme.colorScheme.primary);Spacer(Modifier.width(10.dp));Text(if(task.vaultId.isNotEmpty()&&task.vaultId !in state.unlockedVaultIds)"Protected file" else task.title,Modifier.weight(1f),style=MaterialTheme.typography.titleSmall);Text(task.kind.name.lowercase().replaceFirstChar{it.uppercase()},style=MaterialTheme.typography.labelSmall)}
                Text(if(task.state==TaskState.SYNCING_METADATA)"Saving library information" else task.state.name.lowercase().replace('_',' ').replaceFirstChar{it.uppercase()},color=if(task.state==TaskState.FAILED)MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,style=MaterialTheme.typography.labelLarge)
                if(task.state in setOf(TaskState.PREPARING,TaskState.TRANSFERRING)){
                    if(task.total>0)LinearProgressIndicator(progress={(task.done.toFloat()/task.total).coerceIn(0f,1f)},modifier=Modifier.fillMaxWidth())else LinearProgressIndicator(Modifier.fillMaxWidth())
                }
                if(task.total>0)Text(if(task.kind in setOf(TaskKind.OCR,TaskKind.EXPORT))"${task.done} / ${task.total}" else "${formatBytes(task.done)} / ${formatBytes(task.total)}"+(if(task.speedBytesPerSecond>0)" · ${formatBytes(task.speedBytesPerSecond)}/s" else ""),style=MaterialTheme.typography.bodySmall)
                if(task.error.isNotBlank())Text(if(task.vaultId.isNotEmpty()&&task.vaultId !in state.unlockedVaultIds)"Unlock the vault to view transfer details." else task.error,style=MaterialTheme.typography.bodySmall,color=if(task.state==TaskState.FAILED)MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
                FlowRow(horizontalArrangement=Arrangement.spacedBy(10.dp)){
                    if(task.state !in setOf(TaskState.COMPLETED,TaskState.CANCELLED)){
                        if(task.state in setOf(TaskState.PAUSED,TaskState.FAILED,TaskState.RETRY_WAIT,TaskState.WAITING_FOR_UNLOCK,TaskState.WAITING_FOR_WIFI,TaskState.WAITING_FOR_CHARGING,TaskState.WAITING_FOR_NETWORK,TaskState.WAITING_FOR_DEVICE))TextButton(enabled=remaining==0L || task.kind!=TaskKind.UPLOAD,onClick={bundle.actions.run{bundle.transfers.retry(task.id);system.startTransfers()}}){Text("Resume / retry")}
                        else TextButton(onClick={bundle.actions.run{bundle.transfers.pause(task.id)}}){Text("Pause")}
                        if(task.priority==0)TextButton(onClick={bundle.actions.run{bundle.transfers.prioritize(task.id)}}){Text("Prioritize")}
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
        items(state.rules,key={it.id}){rule->DriveCard(Modifier.fillMaxWidth()){
            Column(Modifier.padding(16.dp),verticalArrangement=Arrangement.spacedBy(9.dp)){
                Row(verticalAlignment=Alignment.CenterVertically){Text(if(rule.vaultId.isNotEmpty()&&rule.vaultId !in state.unlockedVaultIds)"Protected backup" else rule.name,Modifier.weight(1f),fontWeight=FontWeight.SemiBold);DriveSwitch(rule.enabled,{enabled->bundle.actions.run{bundle.library.setRule(rule.copy(enabled=enabled));BackgroundWork.schedule(bundle.library.context,bundle.profile.id,state.preferences.scanIntervalHours)}})}
                Text("Destination: ${folderPath(rule.folderId,state.folders)}",style=MaterialTheme.typography.bodySmall)
                Text(if(rule.lastScanAt==0L)"Not scanned yet" else "Last scan: ${DateFormat.getDateTimeInstance().format(Date(rule.lastScanAt))}",style=MaterialTheme.typography.bodySmall)
                Text(if(rule.lastSuccessAt==0L)"No file success recorded yet" else "Last file success: ${DateFormat.getDateTimeInstance().format(Date(rule.lastSuccessAt))}",style=MaterialTheme.typography.bodySmall)
                if(rule.error.isNotBlank())Text(if(rule.vaultId.isNotEmpty()&&rule.vaultId !in state.unlockedVaultIds)"Unlock the vault to inspect this backup." else rule.error,color=MaterialTheme.colorScheme.error,style=MaterialTheme.typography.bodySmall)
                FlowRow{TextButton(onClick={system.scanBackups(rule.id,false)}){Text("Scan now")};TextButton(onClick={rescanId=rule.id}){Text("Rescan all")};TextButton(onClick={removeId=rule.id}){Text("Remove")}}
            }
        }}
        item{InfoCard("Upload-only by default","Deleting a local original does not delete the cloud copy. Removing a rule keeps uploaded files and receipts. Android and device battery policies can delay background work.",DriveIcons.Shield)}
        item{InfoCard("Folders you explicitly grant","This is not a full-phone or private-app-data backup. Use Android's folder picker to grant access, and check the backup health after permission or device changes.",DriveIcons.Folder)}
    }
    if(pickFolder)FolderPicker(state,destination,{pickFolder=false}){id->destination=id;vault=state.folderVaults[id].orEmpty();pickFolder=false}
    if(removeId.isNotEmpty())ConfirmDialog("Remove backup rule?","Uploaded files and per-file receipts are retained. No cloud content is deleted.","Remove rule",{removeId=""}){val id=removeId;bundle.actions.run{bundle.library.removeRule(id)};removeId=""}
    rescanId?.let{id->ConfirmDialog("Rescan including old content?","This ignores the upload receipt and can bring back content you deliberately removed. It may create additional copies. Normal Scan now is safer.","Rescan all",{rescanId=null}){system.scanBackups(id,true);rescanId=null}}
}
