@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package app.tgdrive.ui

import androidx.compose.ui.graphics.graphicsLayer

import androidx.compose.animation.AnimatedVisibility
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.Role
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.tgdrive.SessionBundle
import app.tgdrive.data.formatBytes
import app.tgdrive.library.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.DateFormat
import java.util.Date

@OptIn(ExperimentalMaterial3Api::class,ExperimentalFoundationApi::class)
@Composable
fun FilesPage(state: LibraryState,bundle: SessionBundle,system: SystemActions,onPreview:(String)->Unit,onTitle:(String)->Unit={},onFolderBack:((()->Unit)?)->Unit={},query:String="",onFilters:((()->Unit)->Unit)={},recentView:Boolean=false,addRequested:Boolean=false,onAddConsumed:()->Unit={}) {
    var folder by rememberSaveable { mutableStateOf("") };var vault by rememberSaveable { mutableStateOf("") }
    var collection by rememberSaveable { mutableStateOf("All") }
    var type by rememberSaveable { mutableStateOf("all") };var minimum by rememberSaveable { mutableLongStateOf(0) }
    var days by rememberSaveable { mutableIntStateOf(0) };var order by rememberSaveable { mutableStateOf("Newest") }
    var storedSelection by rememberSaveable { mutableStateOf(setOf<String>()) }
    val allowedIds=state.items.filter{it.versionId.isNotEmpty()&&(if(collection=="Trash")it.deletedAt>0 else it.deletedAt==0L)&&(state.itemVaults[it.id].orEmpty().isEmpty()||state.itemVaults[it.id] in state.unlockedVaultIds)}.mapTo(hashSetOf()){it.id}
    val selectionScope=listOf(query,collection,type,minimum.toString(),days.toString(),folder,vault)
    var savedSelectionScope by rememberSaveable{mutableStateOf(selectionScope)}
    val selected=if(savedSelectionScope==selectionScope)storedSelection.intersect(allowedIds)else emptySet()
    var addSheet by rememberSaveable { mutableStateOf(false) };var filterSheet by rememberSaveable { mutableStateOf(false) }
    var actionId by rememberSaveable { mutableStateOf("") };var folderActionId by rememberSaveable { mutableStateOf("") }
    var newFolder by rememberSaveable { mutableStateOf(false) };var newFolderId by rememberSaveable{mutableStateOf(newId())};var moveSelection by rememberSaveable { mutableStateOf(false) }
    var confirmTrash by rememberSaveable { mutableStateOf(false) };var confirmPurge by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(Unit){onFilters{filterSheet=true}}
    LaunchedEffect(addRequested){if(addRequested){addSheet=true;onAddConsumed()}}
    val current = state.folders.firstOrNull{it.id==folder}
    LaunchedEffect(folder,current?.name,recentView){onTitle(if(recentView)"Recent uploads"else if(folder.isEmpty())"All files" else current?.name?:"Folder")}
    LaunchedEffect(folder,current?.parentId,selected){onFolderBack(if(selected.isNotEmpty())({storedSelection=emptySet()})else if(folder.isEmpty())null else ({folder=current?.parentId?:""}))}
    BackHandler(enabled=folder.isNotEmpty()||selected.isNotEmpty()){if(selected.isNotEmpty())storedSelection=emptySet() else folder=current?.parentId?:""}
    LaunchedEffect(selectionScope){if(savedSelectionScope!=selectionScope){storedSelection=emptySet();savedSelectionScope=selectionScope}}
    LaunchedEffect(state.items,state.unlockedVaultIds,state.itemVaults) { storedSelection=selected.filter{ id->state.items.any{it.id==id&&it.versionId.isNotEmpty()&&(if(collection=="Trash")it.deletedAt>0 else it.deletedAt==0L)&&(state.itemVaults[id].orEmpty().isEmpty()||state.itemVaults[id] in state.unlockedVaultIds)} }.toSet() }
    val computed = computedList(state.items,state.versions,state.itemVaults,state.local,folder,vault,query,collection,type,minimum,days,order,recentView,state.unlockedVaultIds){
            val uploadedAt=state.versions.associate{it.id to it.createdAt}
            state.items.filter { item->
                item.versionId.isNotEmpty() && (state.itemVaults[item.id].orEmpty().isEmpty()||state.itemVaults[item.id] in state.unlockedVaultIds) && (if(collection=="Trash")item.deletedAt>0 else item.deletedAt==0L) &&
                    (collection!="Favorites"||item.favorite) && (collection!="Offline"||state.local[item.id]?.pinnedVersion==item.versionId) &&
                    (recentView||query.isNotBlank()||collection!="All"||(item.folderId==folder&&state.itemVaults[item.id].orEmpty()==vault)) &&
                    (query.isBlank()||(item.name+" "+item.tags.joinToString(" ")+" "+state.local[item.id]?.ocrText.orEmpty()).contains(query.trim(),true)) &&
                    (type=="all"||(type=="document"&&!item.mime.startsWith("image/")&&!item.mime.startsWith("video/")&&!item.mime.startsWith("audio/"))||item.mime.startsWith("$type/")) &&
                    item.size>=minimum && (days==0||(uploadedAt[item.versionId]?:item.createdAt)>=now()-days*86_400_000L)
            }.let { rows->when(order){"Name"->rows.sortedBy{it.name.lowercase()};"Largest"->rows.sortedByDescending{it.size};else->rows.sortedWith(compareByDescending<Item>{uploadedAt[it.versionId]?:it.createdAt}.thenBy{it.id})} }
    }
    val visible=computed.orEmpty().filter{it.id in allowedIds}
    LaunchedEffect(visible,computed!=null){if(computed!=null)storedSelection=selected.intersect(visible.mapTo(hashSetOf()){it.id})}
    val unlocked=vault.isEmpty()||vault in state.unlockedVaultIds
    val folders=remember(state.folders,folder,vault,query,collection,recentView,unlocked){state.folders.filter{unlocked&&it.parentId==folder&&it.deletedAt==0L&&state.folderVaults[it.id].orEmpty()==vault&&collection=="All"&&query.isBlank()&&!recentView}.sortedBy{it.name.lowercase()}}
    val usage=remember(state.items,state.itemVaults,state.unlockedVaultIds){libraryUsage(state)}
    val bulk=rememberFormAction()
    BoxWithConstraints(Modifier.fillMaxSize()){
        val columns=(maxWidth.value/(180*androidx.compose.ui.platform.LocalDensity.current.fontScale.coerceAtLeast(1f))).toInt().coerceIn(1,6)
        LazyColumn(Modifier.fillMaxSize(),contentPadding=PaddingValues(start=16.dp,end=16.dp,top=16.dp,bottom=160.dp),verticalArrangement=Arrangement.spacedBy(13.dp)){
            if(folder.isNotEmpty()) item {Text(folderPath(folder,state.folders),style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)}
            item {Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(10.dp)){
                MiniStat(usage.count.toString(),"Files",Modifier.weight(1f))
                MiniStat(formatBytes(usage.bytes),"Indexed content",Modifier.weight(1f))
            }}
            if(state.pendingOperations>0)item {InfoCard("Metadata waiting to sync","${state.pendingOperations} changes are saved on this phone. Your original files remain in Telegram.",DriveIcons.CloudSync)}

            item {LazyRow(horizontalArrangement=Arrangement.spacedBy(8.dp)){
                items(listOf("All","Favorites","Offline","Trash")){label->DriveFilterChip(collection==label,{collection=label;storedSelection=emptySet()},label={Text(label)})}
                item{DriveAssistChip(onClick={val value=!state.preferences.showGrid;bundle.actions.run{bundle.library.updatePreferences{it.copy(showGrid=value)}}},label={Text(if(state.preferences.showGrid)"List" else "Grid")},leadingIcon={Icon(if(state.preferences.showGrid)DriveIcons.ViewList else DriveIcons.GridView,null,Modifier.size(18.dp))})}
            }}
            if(state.vaults.isNotEmpty()&&collection=="All")item{LazyRow(horizontalArrangement=Arrangement.spacedBy(8.dp)){
                item{DriveFilterChip(vault.isEmpty(),{vault="";folder=""},label={Text("Personal")})}
                items(state.vaults,key={it.id}){v->DriveFilterChip(vault==v.id,{vault=v.id;folder=""},label={Text(v.label)},leadingIcon={Icon(if(v.id in state.unlockedVaultIds)DriveIcons.LockOpen else DriveIcons.Lock,null,Modifier.size(16.dp))})}
            }}
            if(vault.isNotEmpty()&&vault !in state.unlockedVaultIds)item{InfoCard("Vault is locked","Unlock it from Settings → Vaults. File names and contents stay hidden while locked.",DriveIcons.Lock)}
            if(folder.isNotEmpty())item{TextButton(onClick={folder=current?.parentId?:"";storedSelection=emptySet()}){Icon(DriveIcons.ArrowBack,null);Spacer(Modifier.width(6.dp));Text("Parent folder")}}
            if(state.syncing)item{LinearProgressIndicator(Modifier.fillMaxWidth());Text("Reconciling your Telegram index…",style=MaterialTheme.typography.labelSmall)}
            items(folders,key={"folder:${it.id}"}){f->Surface(shape=DriveStyle.CardShape,color=MaterialTheme.colorScheme.surface,modifier=Modifier.animateItem().combinedClickable(role=Role.Button,onClickLabel="Open folder ${f.name}",onClick={folder=f.id},onLongClick={folderActionId=f.id},onLongClickLabel="Folder actions")){
                Row(Modifier.fillMaxWidth().padding(16.dp),verticalAlignment=Alignment.CenterVertically){Icon(DriveIcons.Folder,null,tint=MaterialTheme.colorScheme.primary);Spacer(Modifier.width(14.dp));Text(f.name,Modifier.weight(1f),fontWeight=FontWeight.Medium);IconButton(onClick={folderActionId=f.id}){Icon(DriveIcons.MoreHoriz,"Actions for folder ${f.name}")}}
            }}
            if(computed==null)item{LinearProgressIndicator(Modifier.fillMaxWidth())}
            if(query.isNotBlank())item{Text("Searching all unlocked files",style=MaterialTheme.typography.labelSmall)}
            if(type!="all"||minimum>0||days>0)item{TextButton(onClick={filterSheet=true}){Text("Filters: $type · ${if(minimum>0)formatBytes(minimum)+"+" else "Any size"} · ${if(days>0)"Last $days days"else"Any time"}")}}
            if(computed!=null&&visible.isEmpty()&&folders.isEmpty()&&!state.syncing)item{Column(Modifier.fillMaxWidth().padding(vertical=38.dp),horizontalAlignment=Alignment.CenterHorizontally,verticalArrangement=Arrangement.spacedBy(12.dp)){
                Icon(if(collection=="Trash")DriveIcons.DeleteOutline else DriveIcons.CloudUpload,null,Modifier.size(58.dp),tint=MaterialTheme.colorScheme.primary)
                Text(if(query.isNotEmpty())"No matches yet" else if(collection=="Trash")"Trash is empty" else "A fresh place to start",style=MaterialTheme.typography.titleLarge)
                Text(if(query.isNotBlank())"Try another name, tag or extracted word."else if(collection=="All")"Upload files or create a folder." else "Files in this collection will appear here.",style=MaterialTheme.typography.bodyMedium,color=MaterialTheme.colorScheme.onSurfaceVariant)
            }}
            if(state.preferences.showGrid){
                items(visible.chunked(columns),key={"grid:${it.first().id}"}){row->Row(horizontalArrangement=Arrangement.spacedBy(10.dp)){
                    row.forEach{item->NativeFileCard(item,state.local[item.id],state.itemVaults[item.id].orEmpty().isNotEmpty(),item.id in selected,true,Modifier.weight(1f),onClick={if(selected.isNotEmpty())storedSelection=toggle(selected,item.id) else if(collection=="Trash")actionId=item.id else onPreview(item.id)},onLongClick={storedSelection=toggle(selected,item.id)},onMenu={actionId=item.id},bundle=bundle,selecting=selected.isNotEmpty())}
                    repeat(columns-row.size){Spacer(Modifier.weight(1f))}
                }}
            }else items(visible,key={it.id}){item->NativeFileCard(item,state.local[item.id],state.itemVaults[item.id].orEmpty().isNotEmpty(),item.id in selected,false,Modifier.animateItem(),onClick={if(selected.isNotEmpty())storedSelection=toggle(selected,item.id) else if(collection=="Trash")actionId=item.id else onPreview(item.id)},onLongClick={storedSelection=toggle(selected,item.id)},onMenu={actionId=item.id},bundle=bundle,selecting=selected.isNotEmpty())}
            item{Text("Stored in your own Saved Messages. Telegram account limits apply.",style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.onSurfaceVariant)}
        }
        if(selected.isEmpty())ExtendedFloatingActionButton(onClick={addSheet=true},icon={Icon(DriveIcons.Add,null)},text={Text("Add files")},
            containerColor=DriveStyle.ActionBlue,contentColor=DriveStyle.OnAction,
            shape=DriveStyle.PillShape,elevation=FloatingActionButtonDefaults.elevation(0.dp),
            modifier=Modifier.align(Alignment.BottomEnd).padding(20.dp))
        AnimatedVisibility(selected.isNotEmpty(),modifier=Modifier.align(Alignment.BottomCenter)){
            Surface(color=MaterialTheme.colorScheme.surface,shape=RoundedCornerShape(topStart=24.dp,topEnd=24.dp)){
                Column(Modifier.fillMaxWidth().padding(12.dp)){
                    bulk.error?.let{Text(it,color=MaterialTheme.colorScheme.error)}
                    Row(verticalAlignment=Alignment.CenterVertically){IconButton(onClick={storedSelection=emptySet()}){Icon(DriveIcons.Close,"Clear selection")};Text("${selected.size} selected",Modifier.weight(1f));TextButton(onClick={storedSelection=visible.map{it.id}.toSet()}){Text("Select all")}}
                    androidx.compose.foundation.layout.FlowRow(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(8.dp)){
                        TextButton(enabled=!bulk.busy,onClick={system.exportZip(selected.toList())}){Text("Save ZIP")}
                        TextButton(onClick={moveSelection=true},enabled=!bulk.busy&&selected.map{state.itemVaults[it].orEmpty()}.distinct().size==1&&collection!="Trash"){Text("Move")}
                        if(collection=="Trash"){
                            TextButton(enabled=!bulk.busy,onClick={val ids=selected.toList();bulk.run({storedSelection=emptySet()}){bundle.actions.restore(ids)}}){Text("Restore")}
                            TextButton(enabled=!bulk.busy,onClick={confirmPurge=true}){Text("Delete",color=MaterialTheme.colorScheme.error)}
                        }else TextButton(enabled=!bulk.busy,onClick={confirmTrash=true}){Text("Trash")}
                    }
                }
            }
        }
    }
    if(addSheet)ModalBottomSheet(onDismissRequest={addSheet=false}){Column(Modifier.fillMaxWidth().padding(20.dp),verticalArrangement=Arrangement.spacedBy(10.dp)){
        SectionTitle("Add to your space")
        ActionRow("Upload files","Select multiple files",DriveIcons.Upload){addSheet=false;system.upload(folder,vault,"")}
        ActionRow("Scan a document","Capture or choose images, then create a PDF",DriveIcons.DocumentScanner){addSheet=false;system.scan(folder,vault)}
        ActionRow("New folder",icon=DriveIcons.CreateNewFolder){addSheet=false;newFolderId=newId();newFolder=true}
        if(vault.isEmpty())ActionRow("Import Saved Messages","Choose existing documents without rewriting their captions",DriveIcons.MoveToInbox){addSheet=false;bundle.actions.run{bundle.library.sync(full=true,collectImports=true)}}
        Spacer(Modifier.height(12.dp))
    }}
    val form=rememberFormAction()
    if(newFolder)TextInputDialog("New folder","Folder name",onDismiss={newFolder=false},busy=form.busy,error=form.error,validate=::nameError){name->form.run({newFolder=false}){bundle.library.createFolder(name,folder,vault,newFolderId)}}
    if(moveSelection)FolderPicker(state,folder,onDismiss={moveSelection=false},allowedVault=selected.firstOrNull()?.let{state.itemVaults[it].orEmpty()},busy=form.busy,error=form.error){target->val ids=selected.toList();form.run({storedSelection=emptySet();moveSelection=false}){bundle.actions.move(ids,target)}}
    if(confirmTrash)ConfirmDialog("Move ${selected.size} files to Trash?","Original messages are retained until permanent deletion or the configured retention cleanup.","Move to Trash",{confirmTrash=false},busy=form.busy,error=form.error){val ids=selected.toList();form.run({storedSelection=emptySet();confirmTrash=false}){bundle.actions.trash(ids)}}
    if(confirmPurge)ConfirmDialog("Delete permanently?","All content versions and original Telegram messages, including imported documents, will be deleted. Downloaded copies elsewhere cannot be recalled.","Delete permanently",{confirmPurge=false},busy=form.busy,error=form.error){val ids=selected.toList();form.run({storedSelection=emptySet();confirmPurge=false}){bundle.actions.purge(ids)}}
    state.items.firstOrNull{it.id==actionId}?.let{item->FileActionsSheet(item,state,bundle,system,onDismiss={actionId=""},onPreview={actionId="";onPreview(item.id)})}
    state.folders.firstOrNull{it.id==folderActionId}?.let{f->FolderActionsDialog(f,state,bundle,onDismiss={folderActionId=""})}
    if(filterSheet)FilterSheet(type,minimum,days,order,onDismiss={filterSheet=false}){t,m,d,o->type=t;minimum=m;days=d;order=o;filterSheet=false}
    if(state.incoming.isNotEmpty())ImportSheet(state,bundle,folder,onDismiss={bundle.library.clearImports()})
}

private fun toggle(set:Set<String>,id:String)=if(id in set)set-id else set+id
fun folderPath(id:String,folders:List<Folder>):String {
    val path=mutableListOf<String>();val seen=mutableSetOf<String>();var current=id
    while(current.isNotEmpty()&&seen.add(current)){val f=folders.firstOrNull{it.id==current}?:break;path.add(f.name);current=f.parentId}
    return (listOf("Home")+path.reversed()).joinToString(" / ")
}
@Composable private fun MiniStat(value:String,label:String,modifier:Modifier){Card(modifier,shape=DriveStyle.CardShape,colors=CardDefaults.cardColors(containerColor=MaterialTheme.colorScheme.surfaceContainerLow)){Column(Modifier.padding(17.dp)){Text(value,style=MaterialTheme.typography.titleLarge,fontWeight=FontWeight.SemiBold);Text(label,style=MaterialTheme.typography.labelMedium,color=MaterialTheme.colorScheme.onSurfaceVariant)}}}
@OptIn(ExperimentalFoundationApi::class)
@Composable fun NativeFileCard(item:Item,local:LocalItem?,encrypted:Boolean,selected:Boolean,grid:Boolean,modifier:Modifier=Modifier,onClick:()->Unit,onLongClick:()->Unit,onMenu:()->Unit,bundle:SessionBundle?=null,selecting:Boolean=false){
    Surface(modifier=modifier.fillMaxWidth().semantics{this.selected=selected}.combinedClickable(role=Role.Button,onClickLabel=if(selecting)if(selected)"Deselect ${item.name}"else"Select ${item.name}"else"Open ${item.name}",onClick=onClick,onLongClick=onLongClick,onLongClickLabel="Select file"),shape=DriveStyle.CardShape,color=if(selected)MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surface){
        if(grid)Column(Modifier.padding(14.dp),verticalArrangement=Arrangement.spacedBy(9.dp)){
            Row(verticalAlignment=Alignment.CenterVertically){FileThumbnail(item,bundle,Modifier.size(72.dp));Spacer(Modifier.weight(1f));IconButton(onClick=onMenu){Icon(DriveIcons.MoreHoriz,"Actions for ${item.name}")}}
            Text(item.name,maxLines=2,overflow=TextOverflow.Ellipsis,style=MaterialTheme.typography.titleSmall);Text(formatBytes(item.size),style=MaterialTheme.typography.labelSmall);FileIndicators(item,local,encrypted)
        }else Row(Modifier.fillMaxWidth().padding(start=15.dp,end=6.dp,top=11.dp,bottom=11.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(13.dp)){
            if(selected)Icon(DriveIcons.CheckCircle,null,Modifier.size(38.dp),tint=MaterialTheme.colorScheme.primary) else FileThumbnail(item,bundle,Modifier.size(44.dp))
            Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(4.dp)){Text(item.name,maxLines=2,overflow=TextOverflow.Ellipsis,style=MaterialTheme.typography.titleSmall);Text("${formatBytes(item.size)} · ${DateFormat.getDateInstance(DateFormat.SHORT).format(Date(item.createdAt))}",style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.onSurfaceVariant);FileIndicators(item,local,encrypted)}
            IconButton(onClick=onMenu){Icon(DriveIcons.MoreHoriz,"Actions for ${item.name}")}
        }
    }
}
@Composable private fun FileIndicators(item:Item,local:LocalItem?,encrypted:Boolean){FlowRow(horizontalArrangement=Arrangement.spacedBy(6.dp)){
    if(item.favorite)Icon(DriveIcons.Star,"Favorite",Modifier.size(14.dp),tint=MaterialTheme.colorScheme.primary)
    if(encrypted)Icon(DriveIcons.Lock,"Encrypted vault file",Modifier.size(14.dp))
    if(local?.pinnedVersion==item.versionId)Icon(DriveIcons.OfflinePin,"Available offline",Modifier.size(14.dp))
    if(local?.verifiedVersion==item.versionId&&item.sha256.isNotEmpty())Icon(DriveIcons.Verified,"Verified download",Modifier.size(14.dp),tint=MaterialTheme.colorScheme.primary)
    if(local?.missing==true)Text("Reference unavailable",color=MaterialTheme.colorScheme.error,style=MaterialTheme.typography.labelSmall)
    if(item.tags.isNotEmpty())Text(item.tags.take(2).joinToString(" · "),style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.primary)
}}
fun fileIcon(mime:String)=when{mime.startsWith("image/")->DriveIcons.Image;mime.startsWith("video/")->DriveIcons.Videocam;mime.startsWith("audio/")->DriveIcons.AudioFile;mime=="application/pdf"->DriveIcons.PictureAsPdf;mime in setOf("application/zip","application/x-zip-compressed","application/x-7z-compressed","application/x-rar-compressed","application/vnd.rar")->DriveIcons.Archive;else->DriveIcons.InsertDriveFile}

@Composable fun FileThumbnail(item:Item,bundle:SessionBundle?,modifier:Modifier,square:Boolean=false) {
    var retry by remember(item.id,item.versionId){mutableIntStateOf(0)}
    var failed by remember(item.id,item.versionId){mutableStateOf(false)}
    var loading by remember(item.id,item.versionId){mutableStateOf(bundle!=null)}
    val network=if(bundle!=null)bundle.drive.state.collectAsStateWithLifecycle().value.network else app.tgdrive.data.TelegramNetwork.UNKNOWN
    LaunchedEffect(network){if(network==app.tgdrive.data.TelegramNetwork.READY&&failed&&retry<2)retry++}
    val request=remember(item.id,item.versionId,bundle,retry){Any()}
    val bitmap by produceState<Pair<Any,android.graphics.Bitmap?>?>(null,request) {
        value=null;failed=false;loading=bundle!=null
        try {if(bundle!=null)value=request to bundle.library.thumbnails.read(item,refresh=retry>0)}
        catch(e:kotlinx.coroutines.CancellationException){throw e}catch(_:Exception){failed=true}
        finally{loading=false}
    }
    val thumb=bitmap?.takeIf{it.first===request}?.second
    val reveal by androidx.compose.animation.core.animateFloatAsState(if(thumb!=null)1f else 0f,androidx.compose.animation.core.tween(180),label="thumbnail-reveal")
    if(thumb!=null)androidx.compose.foundation.Image(thumb.asImageBitmap(),null,if(square)modifier.then(Modifier.graphicsLayer { alpha=reveal }) else modifier.clip(androidx.compose.foundation.shape.RoundedCornerShape(8.dp)),contentScale=androidx.compose.ui.layout.ContentScale.Crop)
    else Box(modifier,contentAlignment=Alignment.Center) {
        when {
            loading->CircularProgressIndicator(Modifier.size(20.dp),strokeWidth=2.dp)
            failed&&!square->IconButton({retry++}){Icon(DriveIcons.Refresh,"Retry preview",tint=MaterialTheme.colorScheme.onSurfaceVariant)}
            else->Icon(fileIcon(item.mime),if(failed)"Preview unavailable"else null,Modifier.size(32.dp),tint=MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
