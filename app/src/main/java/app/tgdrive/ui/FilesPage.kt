@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package app.tgdrive.ui

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
    var selected by remember { mutableStateOf(setOf<String>()) }
    var addSheet by remember { mutableStateOf(false) };var filterSheet by remember { mutableStateOf(false) }
    var actionId by remember { mutableStateOf("") };var folderAction by remember { mutableStateOf<Folder?>(null) }
    var newFolder by remember { mutableStateOf(false) };var moveSelection by remember { mutableStateOf(false) }
    var confirmTrash by remember { mutableStateOf(false) };var confirmPurge by remember { mutableStateOf(false) }
    LaunchedEffect(Unit){onFilters{filterSheet=true}}
    LaunchedEffect(addRequested){if(addRequested){addSheet=true;onAddConsumed()}}
    val current = state.folders.firstOrNull{it.id==folder}
    LaunchedEffect(folder,current?.name,recentView){onTitle(if(recentView)"Recent uploads"else if(folder.isEmpty())"All files" else current?.name?:"Folder")}
    LaunchedEffect(folder,current?.parentId,selected){onFolderBack(if(selected.isNotEmpty())({selected=emptySet()})else if(folder.isEmpty())null else ({folder=current?.parentId?:""}))}
    BackHandler(enabled=folder.isNotEmpty()||selected.isNotEmpty()){if(selected.isNotEmpty())selected=emptySet() else folder=current?.parentId?:""}
    LaunchedEffect(state.items) { selected=selected.filter{ id->state.items.any{it.id==id} }.toSet() }
    val visible by produceState(emptyList<Item>(),state.items,state.local,folder,vault,query,collection,type,minimum,days,order,recentView,state.unlockedVaultIds){
        value=withContext(Dispatchers.Default){
            state.items.filter { item->
                item.versionId.isNotEmpty() && (state.itemVaults[item.id].orEmpty().isEmpty()||state.itemVaults[item.id] in state.unlockedVaultIds) && (if(collection=="Trash")item.deletedAt>0 else item.deletedAt==0L) &&
                    (collection!="Favorites"||item.favorite) && (collection!="Offline"||state.local[item.id]?.pinnedVersion==item.versionId) &&
                    (recentView||query.isNotBlank()||collection!="All"||(item.folderId==folder&&state.itemVaults[item.id].orEmpty()==vault)) &&
                    (query.isBlank()||(item.name+" "+item.tags.joinToString(" ")+" "+state.local[item.id]?.ocrText.orEmpty()).contains(query.trim(),true)) &&
                    (type=="all"||(type=="document"&&!item.mime.startsWith("image/")&&!item.mime.startsWith("video/")&&!item.mime.startsWith("audio/"))||item.mime.startsWith("$type/")) &&
                    item.size>=minimum && (days==0||item.createdAt>=now()-days*86_400_000L)
            }.let { rows->when(order){"Name"->rows.sortedBy{it.name.lowercase()};"Largest"->rows.sortedByDescending{it.size};else->rows.sortedByDescending{it.createdAt}} }
        }
    }
    val folders=remember(state.folders,folder,vault,query,collection,recentView){state.folders.filter{it.parentId==folder&&it.deletedAt==0L&&state.folderVaults[it.id].orEmpty()==vault&&collection=="All"&&query.isBlank()&&!recentView}.sortedBy{it.name.lowercase()}}
    Box(Modifier.fillMaxSize()){
        LazyColumn(Modifier.fillMaxSize(),contentPadding=PaddingValues(start=16.dp,end=16.dp,top=16.dp,bottom=160.dp),verticalArrangement=Arrangement.spacedBy(13.dp)){
            if(folder.isNotEmpty()) item {Text(folderPath(folder,state.folders),style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)}
            item {Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(10.dp)){
                MiniStat(state.items.count{it.deletedAt==0L&&it.versionId.isNotEmpty()}.toString(),"Files",Modifier.weight(1f))
                MiniStat(formatBytes(state.items.filter{it.deletedAt==0L}.sumOf{it.size}),"Indexed content",Modifier.weight(1f))
            }}
            if(state.pendingOperations>0)item {InfoCard("Metadata waiting to sync","${state.pendingOperations} changes are saved on this phone. Your original files remain in Telegram.",DriveIcons.CloudSync)}

            item {LazyRow(horizontalArrangement=Arrangement.spacedBy(8.dp)){
                items(listOf("All","Favorites","Offline","Trash")){label->DriveFilterChip(collection==label,{collection=label;selected=emptySet()},label={Text(label)})}
                item{DriveAssistChip(onClick={val value=!state.preferences.showGrid;bundle.actions.run{bundle.library.updatePreferences{it.copy(showGrid=value)}}},label={Text(if(state.preferences.showGrid)"List" else "Grid")},leadingIcon={Icon(if(state.preferences.showGrid)DriveIcons.ViewList else DriveIcons.GridView,null,Modifier.size(18.dp))})}
            }}
            if(state.vaults.isNotEmpty()&&collection=="All")item{LazyRow(horizontalArrangement=Arrangement.spacedBy(8.dp)){
                item{DriveFilterChip(vault.isEmpty(),{vault="";folder=""},label={Text("Personal")})}
                items(state.vaults,key={it.id}){v->DriveFilterChip(vault==v.id,{vault=v.id;folder=""},label={Text(v.label)},leadingIcon={Icon(if(v.id in state.unlockedVaultIds)DriveIcons.LockOpen else DriveIcons.Lock,null,Modifier.size(16.dp))})}
            }}
            if(vault.isNotEmpty()&&vault !in state.unlockedVaultIds)item{InfoCard("Vault is locked","Unlock it from Settings → Vaults. File names and contents stay hidden while locked.",DriveIcons.Lock)}
            if(folder.isNotEmpty())item{TextButton(onClick={folder=current?.parentId?:"";selected=emptySet()}){Icon(DriveIcons.ArrowBack,null);Spacer(Modifier.width(6.dp));Text("Parent folder")}}
            if(state.syncing)item{LinearProgressIndicator(Modifier.fillMaxWidth());Text("Reconciling your Telegram index…",style=MaterialTheme.typography.labelSmall)}
            items(folders,key={"folder:${it.id}"}){f->Surface(shape=DriveStyle.CardShape,color=MaterialTheme.colorScheme.surface,modifier=Modifier.animateItem().combinedClickable(onClick={folder=f.id},onLongClick={folderAction=f})){
                Row(Modifier.fillMaxWidth().padding(16.dp),verticalAlignment=Alignment.CenterVertically){Icon(DriveIcons.Folder,null,tint=MaterialTheme.colorScheme.primary);Spacer(Modifier.width(14.dp));Text(f.name,Modifier.weight(1f),fontWeight=FontWeight.Medium);IconButton(onClick={folderAction=f}){Icon(DriveIcons.MoreHoriz,"Folder actions")}}
            }}
            if(visible.isEmpty()&&folders.isEmpty()&&!state.syncing)item{Column(Modifier.fillMaxWidth().padding(vertical=38.dp),horizontalAlignment=Alignment.CenterHorizontally,verticalArrangement=Arrangement.spacedBy(12.dp)){
                Icon(if(collection=="Trash")DriveIcons.DeleteOutline else DriveIcons.CloudUpload,null,Modifier.size(58.dp),tint=MaterialTheme.colorScheme.primary)
                Text(if(query.isNotEmpty())"No matches yet" else if(collection=="Trash")"Trash is empty" else "A fresh place to start",style=MaterialTheme.typography.titleLarge)
                Text(if(collection=="All")"Upload files or create a folder." else "Files in this collection will appear here.",style=MaterialTheme.typography.bodyMedium,color=MaterialTheme.colorScheme.onSurfaceVariant)
            }}
            if(state.preferences.showGrid){
                items(visible.chunked(2),key={"grid:${it.first().id}"}){row->Row(horizontalArrangement=Arrangement.spacedBy(10.dp)){
                    row.forEach{item->NativeFileCard(item,state.local[item.id],state.itemVaults[item.id].orEmpty().isNotEmpty(),item.id in selected,true,Modifier.weight(1f),onClick={if(selected.isNotEmpty())selected=toggle(selected,item.id) else onPreview(item.id)},onLongClick={selected=toggle(selected,item.id)},onMenu={actionId=item.id},bundle=bundle)}
                    if(row.size==1)Spacer(Modifier.weight(1f))
                }}
            }else items(visible,key={it.id}){item->NativeFileCard(item,state.local[item.id],state.itemVaults[item.id].orEmpty().isNotEmpty(),item.id in selected,false,Modifier.animateItem(),onClick={if(selected.isNotEmpty())selected=toggle(selected,item.id) else onPreview(item.id)},onLongClick={selected=toggle(selected,item.id)},onMenu={actionId=item.id},bundle=bundle)}
            item{Text("Stored in your own Saved Messages. Telegram account limits apply.",style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.onSurfaceVariant)}
        }
        if(selected.isEmpty())ExtendedFloatingActionButton(onClick={addSheet=true},icon={Icon(DriveIcons.Add,null)},text={Text("Add files")},
            containerColor=MaterialTheme.colorScheme.primary,contentColor=MaterialTheme.colorScheme.onPrimary,
            shape=DriveStyle.PillShape,elevation=FloatingActionButtonDefaults.elevation(0.dp),
            modifier=Modifier.align(Alignment.BottomEnd).padding(20.dp))
        AnimatedVisibility(selected.isNotEmpty(),modifier=Modifier.align(Alignment.BottomCenter)){
            Surface(color=MaterialTheme.colorScheme.surface,shape=RoundedCornerShape(topStart=24.dp,topEnd=24.dp)){
                Column(Modifier.fillMaxWidth().padding(12.dp)){
                    Row(verticalAlignment=Alignment.CenterVertically){IconButton(onClick={selected=emptySet()}){Icon(DriveIcons.Close,"Clear selection")};Text("${selected.size} selected",Modifier.weight(1f));TextButton(onClick={selected=visible.map{it.id}.toSet()}){Text("Select all")}}
                    androidx.compose.foundation.layout.FlowRow(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(8.dp)){
                        TextButton(onClick={system.exportZip(selected.toList())}){Text("Save ZIP")}
                        TextButton(onClick={moveSelection=true}){Text("Move")}
                        if(collection=="Trash"){
                            TextButton(onClick={val ids=selected.toList();bundle.actions.run{bundle.actions.restore(ids)};selected=emptySet()}){Text("Restore")}
                            TextButton(onClick={confirmPurge=true}){Text("Delete",color=MaterialTheme.colorScheme.error)}
                        }else TextButton(onClick={confirmTrash=true}){Text("Trash")}
                    }
                }
            }
        }
    }
    if(addSheet)ModalBottomSheet(onDismissRequest={addSheet=false}){Column(Modifier.fillMaxWidth().padding(20.dp),verticalArrangement=Arrangement.spacedBy(10.dp)){
        SectionTitle("Add to your space")
        ActionRow("Upload files","Select multiple files",DriveIcons.Upload){addSheet=false;system.upload(folder,vault,"")}
        ActionRow("Scan a document","Capture or choose images, then create a PDF",DriveIcons.DocumentScanner){addSheet=false;system.scan(folder,vault)}
        ActionRow("New folder",icon=DriveIcons.CreateNewFolder){addSheet=false;newFolder=true}
        ActionRow("Import Saved Messages","Choose existing documents without rewriting their captions",DriveIcons.MoveToInbox){addSheet=false;bundle.actions.run{bundle.library.sync(full=true,collectImports=true)}}
        Spacer(Modifier.height(12.dp))
    }}
    if(newFolder)TextInputDialog("New folder","Folder name",onDismiss={newFolder=false},validate=::nameError){name->bundle.actions.run{bundle.library.createFolder(name,folder,vault)};newFolder=false}
    if(moveSelection)FolderPicker(state,folder,onDismiss={moveSelection=false},allowedVault=selected.firstOrNull()?.let{state.itemVaults[it].orEmpty()}){target->val ids=selected.toList();bundle.actions.run{bundle.actions.move(ids,target);withContext(Dispatchers.Main){selected=emptySet();moveSelection=false}}}
    if(confirmTrash)ConfirmDialog("Move ${selected.size} files to Trash?","Original messages are retained until permanent deletion or the configured retention cleanup.","Move to Trash",{confirmTrash=false}){val ids=selected.toList();bundle.actions.run{bundle.actions.trash(ids);withContext(Dispatchers.Main){selected=emptySet()}};confirmTrash=false}
    if(confirmPurge)ConfirmDialog("Delete permanently?","All content versions and original Telegram messages, including imported documents, will be deleted. Downloaded copies elsewhere cannot be recalled.","Delete permanently",{confirmPurge=false}){val ids=selected.toList();bundle.actions.run{bundle.actions.purge(ids);withContext(Dispatchers.Main){selected=emptySet()}};confirmPurge=false}
    state.items.firstOrNull{it.id==actionId}?.let{item->FileActionsSheet(item,state,bundle,system,onDismiss={actionId=""},onPreview={actionId="";onPreview(item.id)})}
    folderAction?.let{f->FolderActionsDialog(f,state,bundle,onDismiss={folderAction=null})}
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
@Composable fun NativeFileCard(item:Item,local:LocalItem?,encrypted:Boolean,selected:Boolean,grid:Boolean,modifier:Modifier=Modifier,onClick:()->Unit,onLongClick:()->Unit,onMenu:()->Unit,bundle:SessionBundle?=null){
    Surface(modifier=modifier.fillMaxWidth().semantics{this.selected=selected}.combinedClickable(onClick=onClick,onLongClick=onLongClick,onLongClickLabel="Select file"),shape=DriveStyle.CardShape,color=if(selected)MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surface){
        if(grid)Column(Modifier.padding(14.dp),verticalArrangement=Arrangement.spacedBy(9.dp)){
            Row(verticalAlignment=Alignment.CenterVertically){FileThumbnail(item,bundle,Modifier.size(72.dp));Spacer(Modifier.weight(1f));IconButton(onClick=onMenu){Icon(DriveIcons.MoreHoriz,"File actions")}}
            Text(item.name,maxLines=2,overflow=TextOverflow.Ellipsis,style=MaterialTheme.typography.titleSmall);Text(formatBytes(item.size),style=MaterialTheme.typography.labelSmall);FileIndicators(item,local,encrypted)
        }else Row(Modifier.fillMaxWidth().padding(start=15.dp,end=6.dp,top=11.dp,bottom=11.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(13.dp)){
            if(selected)Icon(DriveIcons.CheckCircle,null,Modifier.size(38.dp),tint=MaterialTheme.colorScheme.primary) else FileThumbnail(item,bundle,Modifier.size(44.dp))
            Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(4.dp)){Text(item.name,maxLines=2,overflow=TextOverflow.Ellipsis,style=MaterialTheme.typography.titleSmall);Text("${formatBytes(item.size)} · ${DateFormat.getDateInstance(DateFormat.SHORT).format(Date(item.createdAt))}",style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.onSurfaceVariant);FileIndicators(item,local,encrypted)}
            IconButton(onClick=onMenu){Icon(DriveIcons.MoreHoriz,"File actions")}
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
fun fileIcon(mime:String)=when{mime.startsWith("image/")->DriveIcons.Image;mime.startsWith("video/")->DriveIcons.Videocam;mime.startsWith("audio/")->DriveIcons.AudioFile;mime=="application/pdf"->DriveIcons.PictureAsPdf;else->DriveIcons.InsertDriveFile}

@Composable fun FileThumbnail(item:Item,bundle:SessionBundle?,modifier:Modifier,square:Boolean=false) {
    var retry by remember(item.id,item.versionId){mutableIntStateOf(0)}
    var failed by remember(item.id,item.versionId){mutableStateOf(false)}
    var loading by remember(item.id,item.versionId){mutableStateOf(bundle!=null)}
    val network=if(bundle!=null)bundle.drive.state.collectAsState().value.network else app.tgdrive.data.TelegramNetwork.UNKNOWN
    LaunchedEffect(network){if(network==app.tgdrive.data.TelegramNetwork.READY&&failed&&retry<2)retry++}
    val bitmap by produceState<android.graphics.Bitmap?>(null,item.id,item.versionId,bundle,retry) {
        failed=false;loading=bundle!=null
        try {if(bundle!=null)value=ThumbnailTools(bundle.library).read(item)}
        catch(e:kotlinx.coroutines.CancellationException){throw e}catch(_:Exception){failed=true}
        finally{loading=false}
    }
    val thumb=bitmap
    if(thumb!=null)androidx.compose.foundation.Image(thumb.asImageBitmap(),null,if(square)modifier else modifier.clip(androidx.compose.foundation.shape.RoundedCornerShape(8.dp)),contentScale=androidx.compose.ui.layout.ContentScale.Crop)
    else Box(modifier,contentAlignment=Alignment.Center) {
        when {
            loading->CircularProgressIndicator(Modifier.size(20.dp),strokeWidth=2.dp)
            failed&&!square->IconButton({retry++}){Icon(DriveIcons.Refresh,"Retry preview",tint=MaterialTheme.colorScheme.onSurfaceVariant)}
            else->Icon(fileIcon(item.mime),if(failed)"Preview unavailable"else null,Modifier.size(32.dp),tint=MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
