package app.tgdrive.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.toggleable
import androidx.compose.ui.semantics.Role
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import app.tgdrive.SessionBundle
import app.tgdrive.data.formatBytes
import app.tgdrive.library.*
import java.text.DateFormat
import java.util.Date
import kotlinx.coroutines.*

internal fun nameError(value:String):String?=runCatching{cleanName(value)}.exceptionOrNull()?.message

@Composable fun TextInputDialog(title:String,label:String,initial:String="",onDismiss:()->Unit,busy:Boolean=false,error:String?=null,validate:(String)->String?={null},allowEmpty:Boolean=false,onConfirm:(String)->Unit){
    var value by androidx.compose.runtime.saveable.rememberSaveable(title,initial){mutableStateOf(initial)}
    var inputError by remember{mutableStateOf<String?>(null)}
    AlertDialog(onDismissRequest={if(!busy)onDismiss()},title={Text(title)},text={Column(Modifier.verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(8.dp)){
        DriveTextField(value,{value=it;inputError=null},enabled=!busy,label={Text(label)},singleLine=true,modifier=Modifier.fillMaxWidth())
        if(busy)LinearProgressIndicator(Modifier.fillMaxWidth())
        (inputError?:error)?.let{Text(it,color=MaterialTheme.colorScheme.error,style=MaterialTheme.typography.bodySmall)}
    }},confirmButton={TextButton(onClick={inputError=validate(value);if(inputError==null)onConfirm(value)},enabled=(allowEmpty||value.isNotBlank())&&!busy){Text(if(busy)"Saving…"else"Save")}},dismissButton={TextButton(onClick=onDismiss,enabled=!busy){Text("Cancel")}})
}
@Composable fun ConfirmDialog(title:String,text:String,confirm:String,onDismiss:()->Unit,busy:Boolean=false,error:String?=null,onConfirm:()->Unit){
    AlertDialog(onDismissRequest={if(!busy)onDismiss()},title={Text(title)},text={Column(Modifier.verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(8.dp)){
        Text(text);if(busy)LinearProgressIndicator(Modifier.fillMaxWidth());error?.let{Text(it,color=MaterialTheme.colorScheme.error)}
    }},confirmButton={TextButton(onClick=onConfirm,enabled=!busy){Text(if(busy)"Working…"else confirm)}},dismissButton={TextButton(onClick=onDismiss,enabled=!busy){Text("Cancel")}})
}
@Composable fun FolderPicker(state:LibraryState,current:String,onDismiss:()->Unit,allowedVault:String?=null,excluded:Set<String> = emptySet(),busy:Boolean=false,error:String?=null,onPick:(String)->Unit){
    AlertDialog(onDismissRequest={if(!busy)onDismiss()},title={Text("Choose destination")},text={Column(verticalArrangement=Arrangement.spacedBy(8.dp)){
        if(busy)LinearProgressIndicator(Modifier.fillMaxWidth());error?.let{Text(it,color=MaterialTheme.colorScheme.error)}
        LazyColumn(Modifier.heightIn(max=420.dp)){
            item{TextButton(onClick={onPick("")},enabled=!busy){Icon(DriveIcons.Folder,null);Text(if(allowedVault.isNullOrEmpty())"All files"else "${state.vaults.firstOrNull{it.id==allowedVault}?.label?:"Vault"} root")}}
            items(state.folders.filter{it.deletedAt==0L&&it.id!=current&&it.id !in excluded&&(state.folderVaults[it.id].orEmpty().isEmpty()||state.folderVaults[it.id] in state.unlockedVaultIds)&&(allowedVault==null||state.folderVaults[it.id].orEmpty()==allowedVault)}.sortedBy{it.name.lowercase()},key={it.id}){folder->TextButton(onClick={onPick(folder.id)},enabled=!busy){Icon(DriveIcons.Folder,null);Spacer(Modifier.width(8.dp));Text(folderPath(folder.id,state.folders))}}
        }
    }},confirmButton={TextButton(onClick=onDismiss,enabled=!busy){Text("Cancel")}})
}
@OptIn(ExperimentalMaterial3Api::class)
@Composable fun FilterSheet(type:String,minimum:Long,days:Int,order:String,onDismiss:()->Unit,onApply:(String,Long,Int,String)->Unit){
    var t by androidx.compose.runtime.saveable.rememberSaveable{mutableStateOf(type)};var m by androidx.compose.runtime.saveable.rememberSaveable{mutableLongStateOf(minimum)};var d by androidx.compose.runtime.saveable.rememberSaveable{mutableIntStateOf(days)};var o by androidx.compose.runtime.saveable.rememberSaveable{mutableStateOf(order)}
    ModalBottomSheet(onDismissRequest=onDismiss){Column(Modifier.fillMaxWidth().padding(24.dp).verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(12.dp)){
        SectionTitle("Find the right file")
        Text("Type");LazyRow(horizontalArrangement=Arrangement.spacedBy(8.dp)){items(listOf("all","image","video","audio","document")){v->DriveFilterChip(t==v,{t=v},label={Text(v.replaceFirstChar{it.uppercase()})})}}
        Text("Minimum size");LazyRow(horizontalArrangement=Arrangement.spacedBy(8.dp)){items(listOf(0L,10L*1024*1024,100L*1024*1024)){v->DriveFilterChip(m==v,{m=v},label={Text(if(v==0L)"Any" else formatBytes(v))})}}
        Text("Added");LazyRow(horizontalArrangement=Arrangement.spacedBy(8.dp)){items(listOf(0,7,30)){v->DriveFilterChip(d==v,{d=v},label={Text(if(v==0)"Any time" else "$v days")})}}
        Text("Sort");LazyRow(horizontalArrangement=Arrangement.spacedBy(8.dp)){items(listOf("Newest","Name","Largest")){v->DriveFilterChip(o==v,{o=v},label={Text(v)})}}
        DriveButton(onClick={onApply(t,m,d,o)},Modifier.fillMaxWidth()){Text("Apply filters")}
        TextButton(onClick={onApply("all",0,0,"Newest")}){Text("Reset all filters")};Spacer(Modifier.height(16.dp))
    }}
}
@Composable fun FolderActionsDialog(folder:Folder,state:LibraryState,bundle:SessionBundle,onDismiss:()->Unit){
    var mode by androidx.compose.runtime.saveable.rememberSaveable{mutableStateOf("actions")}
    val form=rememberFormAction()
    when(mode){
        "rename"->TextInputDialog("Rename folder","Name",folder.name,onDismiss,busy=form.busy,error=form.error,validate=::nameError){name->form.run(onDismiss){bundle.library.updateFolder(folder.copy(name=cleanName(name)))}}
        "move"->FolderPicker(state,folder.id,onDismiss,allowedVault=state.folderVaults[folder.id].orEmpty(),excluded=folderDescendants(folder.id,state.folders),busy=form.busy,error=form.error){id->form.run(onDismiss){bundle.library.updateFolder(folder.copy(parentId=id))}}
        "remove"->ConfirmDialog("Remove folder?","Only an empty folder can be removed. Files are not automatically deleted.","Remove",onDismiss,busy=form.busy,error=form.error){form.run(onDismiss){
            require(state.items.none{it.folderId==folder.id&&it.deletedAt==0L}&&state.folders.none{it.parentId==folder.id&&it.deletedAt==0L}){"Move files and subfolders before removing this folder."}
            bundle.library.updateFolder(folder.copy(deletedAt=now()))
        }}
        else->AlertDialog(onDismissRequest=onDismiss,title={Text(folder.name)},text={Column{TextButton(onClick={mode="rename"}){Text("Rename")};TextButton(onClick={mode="move"}){Text("Move folder")};TextButton(onClick={mode="remove"}){Text("Remove empty folder")}}},confirmButton={TextButton(onClick=onDismiss){Text("Done")}})
    }
}
@OptIn(ExperimentalMaterial3Api::class)
@Composable fun FileActionsSheet(item:Item,state:LibraryState,bundle:SessionBundle,system:SystemActions,onDismiss:()->Unit,onPreview:()->Unit){
    var mode by androidx.compose.runtime.saveable.rememberSaveable{mutableStateOf("menu")}
    var more by androidx.compose.runtime.saveable.rememberSaveable{mutableStateOf(false)}
    var busy by remember{mutableStateOf(false)}
    var error by remember{mutableStateOf<String?>(null)}
    val ui=rememberCoroutineScope()
    fun perform(block:suspend()->Unit){
        if(busy)return
        busy=true;error=null
        val account=state.accountId
        ui.launch{try{withContext(Dispatchers.IO){bundle.library.checkAccount(account);block()};onDismiss()}
            catch(e:CancellationException){throw e}catch(e:Exception){error=e.message?:"Could not save changes.";bundle.library.report(e)}finally{busy=false}}
    }
    CompositionLocalProvider(LocalDriveActionsEnabled provides !busy){
    when(mode){
        "rename"->TextInputDialog("Rename file","File name",item.name,onDismiss,busy,error,validate=::nameError){v->perform{bundle.actions.rename(item.id,v)}}
        "tags"->TextInputDialog("Edit tags","Comma-separated tags",item.tags.joinToString(", "),onDismiss,busy,error,allowEmpty=true){v->perform{bundle.actions.tags(item.id,v)}}
        "move"->FolderPicker(state,item.folderId,onDismiss,allowedVault=state.itemVaults[item.id].orEmpty(),busy=busy,error=error){target->perform{bundle.actions.move(listOf(item.id),target)}}
        "trash"->ConfirmDialog("Move to Trash?","The original Telegram message is retained until permanent deletion or retention cleanup.","Move to Trash",onDismiss,busy=busy,error=error){perform{bundle.actions.trash(listOf(item.id))}}
        "purge"->ConfirmDialog("Delete permanently?","Original Telegram messages and all versions will be deleted, including an imported original. This cannot be undone.","Delete permanently",onDismiss,busy=busy,error=error){perform{bundle.actions.purge(listOf(item.id))}}
        "text"->ExtractedTextDialog(item,state,bundle,onDismiss)
        "conflicts"->FileConflictDialog(item,state,bundle,onDismiss)
        "links"->FileLinksDialog(item,state,bundle,onDismiss)
        "versions"->VersionSheet(item,state,bundle,onDismiss)
        "share"->PublicShareDialog(item,state,bundle,onDismiss,system.startTransfers)
        "webdav"->ConfirmDialog("Export to WebDAV?","This uploads a separate, decrypted copy to your configured server, then downloads it to verify its checksum. It consumes network data.","Export copy",onDismiss,busy=busy,error=error){perform{bundle.transfers.enqueue(TaskKind.WEBDAV,item);system.startTransfers()}}
        "info"->AlertDialog(onDismissRequest=onDismiss,title={Text("File details")},text={Column(Modifier.verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(8.dp)){
            Text(item.name);Text("${formatBytes(item.size)} · ${item.mime}");Text("Version: ${item.versionId}",style=MaterialTheme.typography.bodySmall)
            Text(if(item.sha256.isEmpty())"Original checksum unavailable for this imported or legacy document." else "Original SHA-256:")
            if(item.sha256.isNotEmpty())SelectionContainer{Text(item.sha256,style=MaterialTheme.typography.bodySmall)}
            Text(if(state.local[item.id]?.verifiedVersion==item.versionId&&item.sha256.isNotEmpty())"Downloaded content was verified against the original checksum." else "An uploaded/indexed reference is not the same as a fully verified downloaded copy.",style=MaterialTheme.typography.bodySmall)
            Text(if(state.itemVaults[item.id].orEmpty().isEmpty())"Telegram cloud storage is not end-to-end encrypted by this app for this file." else "Content and metadata belong to an encrypted vault.",style=MaterialTheme.typography.bodySmall)
        }},confirmButton={TextButton(onClick=onDismiss){Text("Done")}})
        else->ModalBottomSheet(onDismissRequest={if(!busy)onDismiss()}){Column(Modifier.fillMaxWidth().heightIn(max=650.dp).verticalScroll(rememberScrollState()).padding(20.dp),verticalArrangement=Arrangement.spacedBy(8.dp)){
            Text(item.name,style=MaterialTheme.typography.titleLarge,maxLines=2,overflow=androidx.compose.ui.text.style.TextOverflow.Ellipsis)
            if(busy)LinearProgressIndicator(Modifier.fillMaxWidth())
            error?.let{Text(it,color=MaterialTheme.colorScheme.error)}
            if(item.deletedAt>0){
                ActionRow("Restore",icon=DriveIcons.Restore){perform{bundle.actions.restore(listOf(item.id))}}
                ActionRow("Delete permanently",icon=DriveIcons.DeleteForever){mode="purge"}
            }else{
                DriveGroup {
                ActionRow("Save to device",icon=DriveIcons.Download){onDismiss();system.save(item)}
                ActionRow("Share a copy",icon=DriveIcons.Share){onDismiss();system.shareExternal(item)}
                ActionRow("Open in another app",icon=DriveIcons.FileOpen){onDismiss();system.openExternal(item)}
                ActionRow(if(item.favorite)"Remove from favorites" else "Add to favorites",icon=DriveIcons.StarOutline){perform{bundle.actions.favorite(item.id)}}
                }
                DriveGroup {
                ActionRow(if(state.local[item.id]?.pinnedVersion==item.versionId)"Remove offline copy" else "Keep offline",icon=DriveIcons.OfflinePin){perform{if(state.local[item.id]?.pinnedVersion==item.versionId)bundle.actions.unpin(item.id) else {bundle.transfers.enqueue(TaskKind.PIN,item);system.startTransfers()}}}
                }
                if(state.local[item.id]?.ocrText?.isNotBlank()==true)ActionRow("Extracted text",icon=DriveIcons.DocumentScanner){mode="text"}
                if(state.conflicts.any{it.entityId==item.id||it.entityId==item.versionId})ActionRow("Review conflicting changes",icon=DriveIcons.History){mode="conflicts"}
                if(state.shares.isNotEmpty())ActionRow("Manage exported links",icon=DriveIcons.Link){mode="links"}
                TextButton({more=!more},enabled=!busy){Text(if(more)"Fewer options"else"More options")}
                if(more) {
                DriveGroup {
                ActionRow("Verify downloaded content",icon=DriveIcons.Verified){perform{bundle.transfers.enqueue(TaskKind.VERIFY,item);system.startTransfers()}}
                }
                DriveGroup {
                ActionRow("Rename",icon=DriveIcons.Edit){mode="rename"}
                ActionRow("Move",icon=DriveIcons.DriveFileMove){mode="move"}
                ActionRow("Tags",icon=DriveIcons.Label){mode="tags"}
                ActionRow("Versions / replace content",icon=DriveIcons.History){mode="versions"}
                ActionRow("Upload new content version",icon=DriveIcons.Upload){onDismiss();system.upload(item.folderId,state.itemVaults[item.id].orEmpty(),item.id)}
                }
                DriveGroup {
                if(item.mime.startsWith("image/")||item.mime=="application/pdf")ActionRow("Extract text on this device","Latin text · up to 50 PDF pages",DriveIcons.DocumentScanner){perform{bundle.transfers.enqueue(TaskKind.OCR,item);system.startTransfers()}}
                if(state.preferences.sharingUrl.isNotBlank())ActionRow("Create public copy link","Requires your optional sharing server",DriveIcons.Link){mode="share"}
                if(state.preferences.webDavUrl.isNotBlank())ActionRow("Copy to WebDAV","Requires your optional HTTPS endpoint",DriveIcons.CloudUpload){mode="webdav"}
                }
                }
                ActionRow("Move to Trash",icon=DriveIcons.DeleteOutline){mode="trash"}
            }
            ActionRow("Details and integrity",icon=DriveIcons.Info){mode="info"};Spacer(Modifier.height(12.dp))
        }}
    }
    }
}
@OptIn(ExperimentalMaterial3Api::class)
@Composable fun VersionSheet(item:Item,state:LibraryState,bundle:SessionBundle,onDismiss:()->Unit){
    val form=rememberFormAction()
    ModalBottomSheet(onDismissRequest={if(!form.busy)onDismiss()}){LazyColumn(Modifier.fillMaxWidth().heightIn(max=600.dp),contentPadding=PaddingValues(24.dp),verticalArrangement=Arrangement.spacedBy(12.dp)){
        item{SectionTitle("Content versions","Restoring retains the other content versions.");if(form.busy)LinearProgressIndicator(Modifier.fillMaxWidth());form.error?.let{Text(it,color=MaterialTheme.colorScheme.error)}}
        items(state.versions.filter{it.itemId==item.id}.sortedByDescending{it.createdAt},key={it.id}){version->DriveCard(Modifier.fillMaxWidth()){
            Column(Modifier.padding(16.dp)){Text(DateFormat.getDateTimeInstance().format(Date(version.createdAt)));Text(formatBytes(version.size),style=MaterialTheme.typography.bodySmall);if(version.id==item.versionId)Text("Current version",color=MaterialTheme.colorScheme.primary)else TextButton(enabled=!form.busy,onClick={form.run(onDismiss){bundle.actions.restoreVersion(item.id,version.id)}}){Text("Restore this version")}}
        }}
    }}
}
@Composable private fun PublicShareDialog(item:Item,state:LibraryState,bundle:SessionBundle,onDismiss:()->Unit,start:()->Unit){
    var hours by remember{mutableFloatStateOf(24f)};var password by remember{mutableStateOf("")};var consent by remember{mutableStateOf(false)}
    AlertDialog(onDismissRequest=onDismiss,title={Text("Create a public copy")},text={Column(Modifier.verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(10.dp)){
        if(state.preferences.sharingUrl.isBlank())Text("A sharing server must already be configured to create a public link.",color=MaterialTheme.colorScheme.error)
        Text("This exports a decrypted copy to your server, even for vault files. The server operator can read that copy. Revocation cannot recall downloaded copies.")
        Text("Expires in ${hours.toInt()} hours");Slider(hours,{hours=it},valueRange=1f..168f)
        DriveTextField(password,{password=it},label={Text("Optional link password")},visualTransformation=PasswordVisualTransformation(),singleLine=true)
        DriveCheckboxRow("I approve exporting this copy.",consent,{consent=it})
    }},confirmButton={TextButton(onClick={bundle.actions.run{bundle.transfers.enqueue(TaskKind.SHARE,item,payload=ShareOptions(hours.toInt()).json(),sharePassword=password);start()};onDismiss()},enabled=consent&&state.preferences.sharingUrl.isNotBlank()){Text("Create link")}},dismissButton={TextButton(onClick=onDismiss){Text("Cancel")}})
}
@OptIn(ExperimentalMaterial3Api::class)
@Composable fun ImportSheet(state:LibraryState,bundle:SessionBundle,folder:String,onDismiss:()->Unit){
    var selected by androidx.compose.runtime.saveable.rememberSaveable{mutableStateOf(setOf<Long>())};val form=rememberFormAction()
    ModalBottomSheet(onDismissRequest={if(!form.busy)onDismiss()}){Column(Modifier.fillMaxWidth().padding(20.dp)){
        if(form.busy)LinearProgressIndicator(Modifier.fillMaxWidth());form.error?.let{Text(it,color=MaterialTheme.colorScheme.error)}
        SectionTitle("Existing Saved Messages","Only selected references are imported. Original captions are retained.")
        DriveButton(onClick={val rows=state.incoming.filter{it.messageId in selected};form.run(onDismiss){rows.forEach{bundle.library.importMessage(it,folder)}}},enabled=selected.isNotEmpty()&&!form.busy){Text("Import ${selected.size} selected")}
        LazyColumn(Modifier.heightIn(max=450.dp)){items(state.incoming,key={it.messageId}){candidate->Row(Modifier.fillMaxWidth().heightIn(min=56.dp).toggleable(candidate.messageId in selected,enabled=!form.busy,role=Role.Checkbox){value->selected=if(value)selected+candidate.messageId else selected-candidate.messageId}.padding(vertical=8.dp)){
            Checkbox(candidate.messageId in selected,null,enabled=!form.busy);Column(Modifier.weight(1f)){Text(candidate.name);Text(formatBytes(candidate.size),style=MaterialTheme.typography.bodySmall)}
        }}}
    }}
}

internal fun folderDescendants(id:String,folders:List<Folder>):Set<String> {
    val found=mutableSetOf(id);var changed=true
    while(changed){changed=false;folders.forEach{if(it.parentId in found&&found.add(it.id))changed=true}}
    return found
}

@Composable private fun ExtractedTextDialog(item:Item,state:LibraryState,bundle:SessionBundle,onDismiss:()->Unit){
    var text by androidx.compose.runtime.saveable.rememberSaveable(item.id){mutableStateOf(state.local[item.id]?.ocrText.orEmpty())}
    val form=rememberFormAction()
    AlertDialog(onDismissRequest={if(!form.busy)onDismiss()},title={Text("Extracted text")},text={Column {
        SelectionContainer{Text("Select text to copy, or correct it below.",style=MaterialTheme.typography.bodySmall)}
        DriveTextField(text,{if(it.length<=500_000)text=it},enabled=!form.busy,modifier=Modifier.fillMaxWidth().heightIn(min=180.dp,max=360.dp),label={Text("Extracted text")})
        form.error?.let{Text(it,color=MaterialTheme.colorScheme.error)}
    }},confirmButton={TextButton(enabled=!form.busy,onClick={form.run(onDismiss){bundle.library.setLocal(bundle.library.db().local(item.id).copy(ocrText=text))}}){Text("Save")}},dismissButton={TextButton(enabled=!form.busy,onClick=onDismiss){Text("Done")}})
}
@Composable private fun FileConflictDialog(item:Item,state:LibraryState,bundle:SessionBundle,onDismiss:()->Unit){
    val form=rememberFormAction()
    val conflicts=state.conflicts.filter{it.entityId==item.id||it.entityId==item.versionId}
    AlertDialog(onDismissRequest={if(!form.busy)onDismiss()},title={Text("Review changes")},text={Column(Modifier.verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(12.dp)){
        Text("Choose which library information to keep. Other uploaded content versions remain available in Versions.")
        if(conflicts.isEmpty())Text("All changes have been reviewed.")
        conflicts.forEach{conflict->
            fun description(op:Envelope):String=runCatching{val body=bundle.library.vaults.body(op);when(op.kind){EntityKind.ITEM->body.model<Item>().let{"${it.name} · Tags: ${it.tags.joinToString()} · ${if(it.deletedAt>0)"In Trash"else"Active"}"};EntityKind.VERSION->body.model<Version>().let{"${formatBytes(it.size)} · ${DateFormat.getDateTimeInstance().format(Date(it.createdAt))}"};else->"Library change"}}.getOrDefault("Unlock the vault to compare this change.")
            Text("Current: ${description(conflict.kept)}")
            Text("Other: ${description(conflict.incoming)}")
            Row{TextButton(enabled=!form.busy,onClick={form.run{bundle.library.resolveConflict(conflict.id,false)}}){Text("Keep current")};TextButton(enabled=!form.busy,onClick={form.run{bundle.library.resolveConflict(conflict.id,true)}}){Text("Use other")}}
        }
        form.error?.let{Text(it,color=MaterialTheme.colorScheme.error)}
    }},confirmButton={TextButton(enabled=!form.busy,onClick=onDismiss){Text("Done")}})
}
@Composable private fun FileLinksDialog(item:Item,state:LibraryState,bundle:SessionBundle,onDismiss:()->Unit){
    val form=rememberFormAction()
    AlertDialog(onDismissRequest={if(!form.busy)onDismiss()},title={Text("Exported public links")},text={Column(Modifier.verticalScroll(rememberScrollState())){
        Text("All links created by this profile. Older records do not identify their source file. Revoking prevents future downloads; copies already saved cannot be recalled.")
        state.shares.forEach{share->SelectionContainer{Text(share.url,style=MaterialTheme.typography.bodySmall)};TextButton(enabled=!form.busy,onClick={form.run{bundle.connections.revoke(share.id)}}){Text("Revoke link")}}
        form.error?.let{Text(it,color=MaterialTheme.colorScheme.error)}
    }},confirmButton={TextButton(enabled=!form.busy,onClick=onDismiss){Text("Done")}})
}
