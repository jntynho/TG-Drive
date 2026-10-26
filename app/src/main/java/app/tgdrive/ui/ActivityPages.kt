@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package app.tgdrive.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.toggleable
import androidx.compose.ui.semantics.Role
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
                Row(Modifier.fillMaxWidth().toggleable(toggle.value.wifiOnly,role=Role.Switch){enabled->toggle.update{it.copy(wifiOnly=enabled)}},verticalAlignment=Alignment.CenterVertically){Text(if(rule.vaultId.isNotEmpty()&&rule.vaultId !in state.unlockedVaultIds)"Protected backup" else rule.name,Modifier.weight(1f),fontWeight=FontWeight.SemiBold);DriveSwitch(toggle.value.wifiOnly,null)}
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

internal fun canStartTransfers(state:LibraryState):Boolean {
    val running=state.tasks.any{it.state in setOf(TaskState.PREPARING,TaskState.TRANSFERRING)}
    return state.tasks.any{it.state==TaskState.PAUSED}||(!running&&(state.pendingOperations>0||state.tasks.any{it.state !in setOf(TaskState.COMPLETED,TaskState.CANCELLED,TaskState.FAILED)}))
}

internal fun transferLabel(state:TaskState):String=when(state){
    TaskState.SYNCING_METADATA->"Finishing library sync"
    TaskState.WAITING_FOR_WIFI->"Waiting for an unmetered network"
    TaskState.WAITING_FOR_CHARGING->"Waiting for charging"
    TaskState.WAITING_FOR_UNLOCK->"Waiting for vault unlock"
    TaskState.WAITING_FOR_NETWORK->"Waiting for connection"
    TaskState.RETRY_WAIT->"Waiting to retry"
    TaskState.COMPLETED->"Saved successfully"
    TaskState.PREPARING->"Preparing file"
    TaskState.TRANSFERRING->"Transferring"
    else->state.name.lowercase().replace('_',' ').replaceFirstChar{it.uppercase()}
}
