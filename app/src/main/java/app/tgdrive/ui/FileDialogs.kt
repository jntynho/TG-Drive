package app.tgdrive.ui

import androidx.compose.foundation.layout.*
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

@Composable fun TextInputDialog(title:String,label:String,initial:String="",onDismiss:()->Unit,onConfirm:(String)->Unit){
    var value by remember{mutableStateOf(initial)}
    AlertDialog(onDismissRequest=onDismiss,title={Text(title)},text={DriveTextField(value,{value=it},label={Text(label)},singleLine=true,modifier=Modifier.fillMaxWidth())},confirmButton={TextButton(onClick={onConfirm(value)},enabled=value.isNotBlank()){Text("Save")}},dismissButton={TextButton(onClick=onDismiss){Text("Cancel")}})
}
@Composable fun ConfirmDialog(title:String,text:String,confirm:String,onDismiss:()->Unit,onConfirm:()->Unit){AlertDialog(onDismissRequest=onDismiss,title={Text(title)},text={Text(text,Modifier.verticalScroll(rememberScrollState()))},confirmButton={TextButton(onClick=onConfirm){Text(confirm)}},dismissButton={TextButton(onClick=onDismiss){Text("Cancel")}})}
@Composable fun FolderPicker(state:LibraryState,current:String,onDismiss:()->Unit,onPick:(String)->Unit){
    AlertDialog(onDismissRequest=onDismiss,title={Text("Choose destination")},text={LazyColumn(Modifier.heightIn(max=420.dp)){
        item{TextButton(onClick={onPick("")}){Icon(DriveIcons.Home,null);Text("Home")}}
        items(state.folders.filter{it.deletedAt==0L&&it.id!=current}.sortedBy{it.name.lowercase()},key={it.id}){folder->TextButton(onClick={onPick(folder.id)}){Icon(DriveIcons.Folder,null);Spacer(Modifier.width(8.dp));Text(folderPath(folder.id,state.folders))}}
    }},confirmButton={TextButton(onClick=onDismiss){Text("Cancel")}})
}
@OptIn(ExperimentalMaterial3Api::class)
@Composable fun FilterSheet(type:String,minimum:Long,days:Int,order:String,onDismiss:()->Unit,onApply:(String,Long,Int,String)->Unit){
    var t by remember{mutableStateOf(type)};var m by remember{mutableLongStateOf(minimum)};var d by remember{mutableIntStateOf(days)};var o by remember{mutableStateOf(order)}
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
    var mode by remember{mutableStateOf("actions")}
    when(mode){
        "rename"->TextInputDialog("Rename folder","Name",folder.name,onDismiss){name->bundle.actions.run{bundle.library.updateFolder(folder.copy(name=cleanName(name)))};onDismiss()}
        "move"->FolderPicker(state,folder.id,onDismiss){id->bundle.actions.run{bundle.library.updateFolder(folder.copy(parentId=id))};onDismiss()}
        "remove"->ConfirmDialog("Remove folder?","Only an empty folder can be removed. Files are not automatically deleted.","Remove",onDismiss){bundle.actions.run{
            require(state.items.none{it.folderId==folder.id&&it.deletedAt==0L}&&state.folders.none{it.parentId==folder.id&&it.deletedAt==0L}){"Move files and subfolders before removing this folder."}
            bundle.library.updateFolder(folder.copy(deletedAt=now()))
        };onDismiss()}
        else->AlertDialog(onDismissRequest=onDismiss,title={Text(folder.name)},text={Column{TextButton(onClick={mode="rename"}){Text("Rename")};TextButton(onClick={mode="move"}){Text("Move folder")};TextButton(onClick={mode="remove"}){Text("Remove empty folder")}}},confirmButton={TextButton(onClick=onDismiss){Text("Done")}})
    }
}
@OptIn(ExperimentalMaterial3Api::class)
@Composable fun FileActionsSheet(item:Item,state:LibraryState,bundle:SessionBundle,system:SystemActions,onDismiss:()->Unit,onPreview:()->Unit){
    var mode by remember{mutableStateOf("menu")}
    fun perform(block:suspend()->Unit){bundle.actions.run(block);onDismiss()}
    when(mode){
        "rename"->TextInputDialog("Rename file","File name",item.name,onDismiss){v->perform{bundle.actions.rename(item.id,v)}}
        "tags"->TextInputDialog("Edit tags","Comma-separated tags",item.tags.joinToString(", "),onDismiss){v->perform{bundle.actions.tags(item.id,v)}}
        "move"->FolderPicker(state,item.folderId,onDismiss){target->perform{bundle.actions.move(listOf(item.id),target)}}
        "trash"->ConfirmDialog("Move to Trash?","The original Telegram message is retained until permanent deletion or retention cleanup.","Move to Trash",onDismiss){perform{bundle.actions.trash(listOf(item.id))}}
        "purge"->ConfirmDialog("Delete permanently?","Original Telegram messages and all versions will be deleted, including an imported original. This cannot be undone.","Delete permanently",onDismiss){perform{bundle.actions.purge(listOf(item.id))}}
        "versions"->VersionSheet(item,state,bundle,onDismiss)
        "share"->PublicShareDialog(item,state,bundle,onDismiss,system.startTransfers)
        "webdav"->ConfirmDialog("Export to WebDAV?","This uploads a separate, decrypted copy to your configured server, then downloads it to verify its checksum. It consumes network data.","Export copy",onDismiss){perform{bundle.transfers.enqueue(TaskKind.WEBDAV,item);system.startTransfers()}}
        "info"->AlertDialog(onDismissRequest=onDismiss,title={Text("File details")},text={Column(Modifier.verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(8.dp)){
            Text(item.name);Text("${formatBytes(item.size)} · ${item.mime}");Text("Version: ${item.versionId}",style=MaterialTheme.typography.bodySmall)
            Text(if(item.sha256.isEmpty())"Original checksum unavailable for this imported or legacy document." else "Original SHA-256:")
            if(item.sha256.isNotEmpty())SelectionContainer{Text(item.sha256,style=MaterialTheme.typography.bodySmall)}
            Text(if(state.local[item.id]?.verifiedVersion==item.versionId&&item.sha256.isNotEmpty())"Downloaded content was verified against the original checksum." else "An uploaded/indexed reference is not the same as a fully verified downloaded copy.",style=MaterialTheme.typography.bodySmall)
            Text(if(state.itemVaults[item.id].orEmpty().isEmpty())"Telegram cloud storage is not end-to-end encrypted by this app for this file." else "Content and metadata belong to an encrypted vault.",style=MaterialTheme.typography.bodySmall)
        }},confirmButton={TextButton(onClick=onDismiss){Text("Done")}})
        else->ModalBottomSheet(onDismissRequest=onDismiss){Column(Modifier.fillMaxWidth().heightIn(max=650.dp).verticalScroll(rememberScrollState()).padding(20.dp),verticalArrangement=Arrangement.spacedBy(8.dp)){
            Text(item.name,style=MaterialTheme.typography.titleLarge,maxLines=2,overflow=androidx.compose.ui.text.style.TextOverflow.Ellipsis)
            if(item.deletedAt>0){
                ActionRow("Restore",icon=DriveIcons.Restore){perform{bundle.actions.restore(listOf(item.id))}}
                ActionRow("Delete permanently",icon=DriveIcons.DeleteForever){mode="purge"}
            }else{
                DriveGroup {
                ActionRow("Preview",icon=fileIcon(item.mime),onClick=onPreview)
                ActionRow("Save to device",icon=DriveIcons.Download){onDismiss();system.save(item)}
                ActionRow("Share a copy",icon=DriveIcons.Share){onDismiss();system.shareExternal(item)}
                ActionRow(if(item.favorite)"Remove from favorites" else "Add to favorites",icon=DriveIcons.StarOutline){perform{bundle.actions.favorite(item.id)}}
                }
                DriveGroup {
                ActionRow(if(state.local[item.id]?.pinnedVersion==item.versionId)"Remove offline copy" else "Keep offline",icon=DriveIcons.OfflinePin){perform{if(state.local[item.id]?.pinnedVersion==item.versionId)bundle.actions.unpin(item.id) else {bundle.transfers.enqueue(TaskKind.PIN,item);system.startTransfers()}}}
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
                ActionRow("Create public copy link","Requires your optional sharing server",DriveIcons.Link){mode="share"}
                ActionRow("Copy to WebDAV","Requires your optional HTTPS endpoint",DriveIcons.CloudUpload){mode="webdav"}
                }
                ActionRow("Move to Trash",icon=DriveIcons.DeleteOutline){mode="trash"}
            }
            ActionRow("Details and integrity",icon=DriveIcons.Info){mode="info"};Spacer(Modifier.height(12.dp))
        }}
    }
}
@OptIn(ExperimentalMaterial3Api::class)
@Composable fun VersionSheet(item:Item,state:LibraryState,bundle:SessionBundle,onDismiss:()->Unit){
    ModalBottomSheet(onDismissRequest=onDismiss){LazyColumn(Modifier.fillMaxWidth().heightIn(max=600.dp),contentPadding=PaddingValues(24.dp),verticalArrangement=Arrangement.spacedBy(12.dp)){
        item{SectionTitle("Content versions","Restoring retains the other content versions.")}
        items(state.versions.filter{it.itemId==item.id}.sortedByDescending{it.createdAt},key={it.id}){version->DriveCard(Modifier.fillMaxWidth()){
            Column(Modifier.padding(16.dp)){Text(DateFormat.getDateTimeInstance().format(Date(version.createdAt)));Text(formatBytes(version.size),style=MaterialTheme.typography.bodySmall);if(version.id==item.versionId)Text("Current version",color=MaterialTheme.colorScheme.primary)else TextButton(onClick={bundle.actions.run{bundle.actions.restoreVersion(item.id,version.id)};onDismiss()}){Text("Restore this version")}}
        }}
    }}
}
@Composable private fun PublicShareDialog(item:Item,state:LibraryState,bundle:SessionBundle,onDismiss:()->Unit,start:()->Unit){
    var hours by remember{mutableFloatStateOf(24f)};var password by remember{mutableStateOf("")};var consent by remember{mutableStateOf(false)}
    AlertDialog(onDismissRequest=onDismiss,title={Text("Create a public copy")},text={Column(Modifier.verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(10.dp)){
        if(state.preferences.sharingUrl.isBlank())Text("Configure an HTTPS sharing server in Settings → Connections. No public link can be created without it.",color=MaterialTheme.colorScheme.error)
        Text("This exports a decrypted copy to your server, even for vault files. The server operator can read that copy. Revocation cannot recall downloaded copies.")
        Text("Expires in ${hours.toInt()} hours");Slider(hours,{hours=it},valueRange=1f..168f)
        DriveTextField(password,{password=it},label={Text("Optional link password")},visualTransformation=PasswordVisualTransformation(),singleLine=true)
        DriveCheckboxRow("I approve exporting this copy.",consent,{consent=it})
    }},confirmButton={TextButton(onClick={bundle.actions.run{bundle.transfers.enqueue(TaskKind.SHARE,item,payload=ShareOptions(hours.toInt()).json(),sharePassword=password);start()};onDismiss()},enabled=consent&&state.preferences.sharingUrl.isNotBlank()){Text("Create link")}},dismissButton={TextButton(onClick=onDismiss){Text("Cancel")}})
}
@OptIn(ExperimentalMaterial3Api::class)
@Composable fun ImportSheet(state:LibraryState,bundle:SessionBundle,folder:String,onDismiss:()->Unit){
    var selected by remember{mutableStateOf(setOf<Long>())}
    ModalBottomSheet(onDismissRequest=onDismiss){Column(Modifier.fillMaxWidth().padding(20.dp)){
        SectionTitle("Existing Saved Messages","Only selected references are imported. Original captions are retained.")
        DriveButton(onClick={val rows=state.incoming.filter{it.messageId in selected};bundle.actions.run{rows.forEach{bundle.library.importMessage(it,folder)}};onDismiss()},enabled=selected.isNotEmpty()){Text("Import ${selected.size} selected")}
        LazyColumn(Modifier.heightIn(max=450.dp)){items(state.incoming,key={it.messageId}){candidate->Row(Modifier.fillMaxWidth().padding(vertical=8.dp)){
            Checkbox(candidate.messageId in selected,{selected=if(candidate.messageId in selected)selected-candidate.messageId else selected+candidate.messageId});Column(Modifier.weight(1f)){Text(candidate.name);Text(formatBytes(candidate.size),style=MaterialTheme.typography.bodySmall)}
        }}}
    }}
}
