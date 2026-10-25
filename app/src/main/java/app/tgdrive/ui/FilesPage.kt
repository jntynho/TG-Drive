package app.tgdrive.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.InsertDriveFile
import androidx.compose.material.icons.outlined.*
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
fun FilesPage(state: LibraryState,bundle: SessionBundle,system: SystemActions,onPreview:(String)->Unit) {
    var folder by rememberSaveable { mutableStateOf("") };var vault by rememberSaveable { mutableStateOf("") }
    var query by rememberSaveable { mutableStateOf("") };var collection by rememberSaveable { mutableStateOf("All") }
    var type by rememberSaveable { mutableStateOf("all") };var minimum by rememberSaveable { mutableLongStateOf(0) }
    var days by rememberSaveable { mutableIntStateOf(0) };var order by rememberSaveable { mutableStateOf("Newest") }
    var selected by remember { mutableStateOf(setOf<String>()) }
    var addSheet by remember { mutableStateOf(false) };var filterSheet by remember { mutableStateOf(false) }
    var actionId by remember { mutableStateOf("") };var folderAction by remember { mutableStateOf<Folder?>(null) }
    var newFolder by remember { mutableStateOf(false) };var moveSelection by remember { mutableStateOf(false) }
    var confirmTrash by remember { mutableStateOf(false) };var confirmPurge by remember { mutableStateOf(false) }
    val current = state.folders.firstOrNull{it.id==folder}
    BackHandler(enabled=folder.isNotEmpty()||selected.isNotEmpty()){if(selected.isNotEmpty())selected=emptySet() else folder=current?.parentId?:""}
    LaunchedEffect(state.items) { selected=selected.filter{ id->state.items.any{it.id==id} }.toSet() }
    val visible by produceState(emptyList<Item>(),state.items,state.local,folder,vault,query,collection,type,minimum,days,order){
        value=withContext(Dispatchers.Default){
            state.items.filter { item->
                item.versionId.isNotEmpty() && (if(collection=="Trash")item.deletedAt>0 else item.deletedAt==0L) &&
                    (collection!="Favorites"||item.favorite) && (collection!="Offline"||state.local[item.id]?.pinnedVersion==item.versionId) &&
                    (query.isNotBlank()||collection!="All"||(item.folderId==folder&&state.itemVaults[item.id].orEmpty()==vault)) &&
                    (query.isBlank()||(item.name+" "+item.tags.joinToString(" ")+" "+state.local[item.id]?.ocrText.orEmpty()).contains(query.trim(),true)) &&
                    (type=="all"||(type=="document"&&!item.mime.startsWith("image/")&&!item.mime.startsWith("video/")&&!item.mime.startsWith("audio/"))||item.mime.startsWith("$type/")) &&
                    item.size>=minimum && (days==0||item.createdAt>=now()-days*86_400_000L)
            }.let { rows->when(order){"Name"->rows.sortedBy{it.name.lowercase()};"Largest"->rows.sortedByDescending{it.size};else->rows.sortedByDescending{it.createdAt}} }
        }
    }
    val folders=remember(state.folders,folder,vault,query,collection){state.folders.filter{it.parentId==folder&&it.deletedAt==0L&&state.folderVaults[it.id].orEmpty()==vault&&collection=="All"&&query.isBlank()}.sortedBy{it.name.lowercase()}}
    Box(Modifier.fillMaxSize()){
        LazyColumn(Modifier.fillMaxSize(),contentPadding=PaddingValues(start=16.dp,end=16.dp,top=16.dp,bottom=160.dp),verticalArrangement=Arrangement.spacedBy(13.dp)){
            item {SectionTitle(if(folder.isEmpty())"Your files" else current?.name?:"Folder",if(folder.isEmpty())"Everything you keep, in one place." else folderPath(folder,state.folders))}
            item {Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(10.dp)){
                MiniStat(state.items.count{it.deletedAt==0L&&it.versionId.isNotEmpty()}.toString(),"Files",Modifier.weight(1f))
                MiniStat(formatBytes(state.items.filter{it.deletedAt==0L}.sumOf{it.size}),"Indexed content",Modifier.weight(1f))
            }}
            if(state.pendingOperations>0)item {InfoCard("Metadata waiting to sync","${state.pendingOperations} changes are saved on this phone. Your original files remain in Telegram.",Icons.Outlined.CloudSync)}
            item {DriveTextField(query,{query=it},Modifier.fillMaxWidth(),singleLine=true,shape=DriveStyle.CardShape,placeholder={Text("Search files, tags and extracted text")},leadingIcon={Icon(Icons.Outlined.Search,null)},trailingIcon={IconButton(onClick={filterSheet=true}){Icon(Icons.Outlined.Tune,"Search filters")}})}
            item {LazyRow(horizontalArrangement=Arrangement.spacedBy(8.dp)){
                items(listOf("All","Favorites","Offline","Trash")){label->DriveFilterChip(collection==label,{collection=label;selected=emptySet()},label={Text(label)})}
                item{DriveAssistChip(onClick={val value=!state.preferences.showGrid;bundle.actions.run{bundle.library.updatePreferences{it.copy(showGrid=value)}}},label={Text(if(state.preferences.showGrid)"List" else "Grid")},leadingIcon={Icon(if(state.preferences.showGrid)Icons.Outlined.ViewList else Icons.Outlined.GridView,null,Modifier.size(18.dp))})}
            }}
            if(state.vaults.isNotEmpty()&&collection=="All")item{LazyRow(horizontalArrangement=Arrangement.spacedBy(8.dp)){
                item{DriveFilterChip(vault.isEmpty(),{vault="";folder=""},label={Text("Personal")})}
                items(state.vaults,key={it.id}){v->DriveFilterChip(vault==v.id,{vault=v.id;folder=""},label={Text(v.label)},leadingIcon={Icon(if(v.id in state.unlockedVaultIds)Icons.Outlined.LockOpen else Icons.Outlined.Lock,null,Modifier.size(16.dp))})}
            }}
            if(vault.isNotEmpty()&&vault !in state.unlockedVaultIds)item{InfoCard("Vault is locked","Unlock it from Settings → Vaults. File names and contents stay hidden while locked.",Icons.Outlined.Lock)}
            if(folder.isNotEmpty())item{TextButton(onClick={folder=current?.parentId?:"";selected=emptySet()}){Icon(Icons.AutoMirrored.Outlined.ArrowBack,null);Spacer(Modifier.width(6.dp));Text("Parent folder")}}
            if(state.syncing)item{LinearProgressIndicator(Modifier.fillMaxWidth());Text("Reconciling your Telegram index…",style=MaterialTheme.typography.labelSmall)}
            items(folders,key={"folder:${it.id}"}){f->Surface(shape=DriveStyle.CardShape,color=MaterialTheme.colorScheme.surface,modifier=Modifier.animateItem().combinedClickable(onClick={folder=f.id},onLongClick={folderAction=f})){
                Row(Modifier.fillMaxWidth().padding(16.dp),verticalAlignment=Alignment.CenterVertically){Icon(Icons.Outlined.Folder,null,tint=MaterialTheme.colorScheme.primary);Spacer(Modifier.width(14.dp));Text(f.name,Modifier.weight(1f),fontWeight=FontWeight.Medium);IconButton(onClick={folderAction=f}){Icon(Icons.Outlined.MoreHoriz,"Folder actions")}}
            }}
            if(visible.isEmpty()&&folders.isEmpty()&&!state.syncing)item{Column(Modifier.fillMaxWidth().padding(vertical=38.dp),horizontalAlignment=Alignment.CenterHorizontally,verticalArrangement=Arrangement.spacedBy(12.dp)){
                Icon(if(collection=="Trash")Icons.Outlined.DeleteOutline else Icons.Outlined.CloudUpload,null,Modifier.size(58.dp),tint=MaterialTheme.colorScheme.primary)
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
        if(selected.isEmpty())ExtendedFloatingActionButton(onClick={addSheet=true},icon={Icon(Icons.Outlined.Add,null)},text={Text("Add files")},
            containerColor=MaterialTheme.colorScheme.primary,contentColor=MaterialTheme.colorScheme.onPrimary,
            shape=DriveStyle.PillShape,elevation=FloatingActionButtonDefaults.elevation(0.dp),
            modifier=Modifier.align(Alignment.BottomEnd).padding(20.dp))
        AnimatedVisibility(selected.isNotEmpty(),modifier=Modifier.align(Alignment.BottomCenter)){
            Surface(color=MaterialTheme.colorScheme.surface,shape=RoundedCornerShape(topStart=24.dp,topEnd=24.dp)){
                Column(Modifier.fillMaxWidth().padding(12.dp)){
                    Row(verticalAlignment=Alignment.CenterVertically){IconButton(onClick={selected=emptySet()}){Icon(Icons.Outlined.Close,"Clear selection")};Text("${selected.size} selected",Modifier.weight(1f));TextButton(onClick={selected=visible.map{it.id}.toSet()}){Text("Select all")}}
                    Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceEvenly){
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
        ActionRow("Upload files","Select multiple files",Icons.Outlined.Upload){addSheet=false;system.upload(folder,vault,"")}
        ActionRow("Scan a document","Capture or choose images, then create a PDF",Icons.Outlined.DocumentScanner){addSheet=false;system.scan(folder,vault)}
        ActionRow("New folder",icon=Icons.Outlined.CreateNewFolder){addSheet=false;newFolder=true}
        ActionRow("Import Saved Messages","Choose existing documents without rewriting their captions",Icons.Outlined.MoveToInbox){addSheet=false;bundle.actions.run{bundle.library.sync(full=true,collectImports=true)}}
        Spacer(Modifier.height(12.dp))
    }}
    if(newFolder)TextInputDialog("New folder","Folder name",onDismiss={newFolder=false}){name->bundle.actions.run{bundle.library.createFolder(name,folder,vault)};newFolder=false}
    if(moveSelection)FolderPicker(state,folder,onDismiss={moveSelection=false}){target->val ids=selected.toList();bundle.actions.run{bundle.actions.move(ids,target)};selected=emptySet();moveSelection=false}
    if(confirmTrash)ConfirmDialog("Move ${selected.size} files to Trash?","Original messages are retained until permanent deletion or the configured retention cleanup.","Move to Trash",{confirmTrash=false}){val ids=selected.toList();bundle.actions.run{bundle.actions.trash(ids)};selected=emptySet();confirmTrash=false}
    if(confirmPurge)ConfirmDialog("Delete permanently?","All content versions and original Telegram messages, including imported documents, will be deleted. Downloaded copies elsewhere cannot be recalled.","Delete permanently",{confirmPurge=false}){val ids=selected.toList();bundle.actions.run{bundle.actions.purge(ids)};selected=emptySet();confirmPurge=false}
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
            Row(verticalAlignment=Alignment.CenterVertically){FileThumbnail(item,bundle,Modifier.size(72.dp));Spacer(Modifier.weight(1f));IconButton(onClick=onMenu){Icon(Icons.Outlined.MoreHoriz,"File actions")}}
            Text(item.name,maxLines=2,overflow=TextOverflow.Ellipsis,style=MaterialTheme.typography.titleSmall);Text(formatBytes(item.size),style=MaterialTheme.typography.labelSmall);FileIndicators(item,local,encrypted)
        }else Row(Modifier.fillMaxWidth().padding(start=15.dp,end=6.dp,top=11.dp,bottom=11.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(13.dp)){
            if(selected)Icon(Icons.Outlined.CheckCircle,null,Modifier.size(38.dp),tint=MaterialTheme.colorScheme.primary) else FileThumbnail(item,bundle,Modifier.size(44.dp))
            Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(4.dp)){Text(item.name,maxLines=2,overflow=TextOverflow.Ellipsis,style=MaterialTheme.typography.titleSmall);Text("${formatBytes(item.size)} · ${DateFormat.getDateInstance(DateFormat.SHORT).format(Date(item.createdAt))}",style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.onSurfaceVariant);FileIndicators(item,local,encrypted)}
            IconButton(onClick=onMenu){Icon(Icons.Outlined.MoreHoriz,"File actions")}
        }
    }
}
@Composable private fun FileIndicators(item:Item,local:LocalItem?,encrypted:Boolean){Row(horizontalArrangement=Arrangement.spacedBy(6.dp)){
    if(item.favorite)Icon(Icons.Outlined.Star,null,Modifier.size(14.dp),tint=MaterialTheme.colorScheme.primary)
    if(encrypted)Icon(Icons.Outlined.Lock,null,Modifier.size(14.dp))
    if(local?.pinnedVersion==item.versionId)Icon(Icons.Outlined.OfflinePin,null,Modifier.size(14.dp))
    if(local?.verifiedVersion==item.versionId&&item.sha256.isNotEmpty())Icon(Icons.Outlined.Verified,null,Modifier.size(14.dp),tint=MaterialTheme.colorScheme.primary)
    if(local?.missing==true)Text("Reference unavailable",color=MaterialTheme.colorScheme.error,style=MaterialTheme.typography.labelSmall)
    if(item.tags.isNotEmpty())Text(item.tags.take(2).joinToString(" · "),style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.primary)
}}
fun fileIcon(mime:String)=when{mime.startsWith("image/")->Icons.Outlined.Image;mime.startsWith("video/")->Icons.Outlined.Videocam;mime.startsWith("audio/")->Icons.Outlined.AudioFile;mime=="application/pdf"->Icons.Outlined.PictureAsPdf;else->Icons.AutoMirrored.Outlined.InsertDriveFile}

@Composable fun FileThumbnail(item:Item,bundle:SessionBundle?,modifier:Modifier) {
    val bitmap by produceState<android.graphics.Bitmap?>(null,item.id,item.versionId,bundle) {
        if(bundle!=null)value=try{ThumbnailTools(bundle.library).read(item)}catch(e:kotlinx.coroutines.CancellationException){throw e}catch(_:Exception){null}
    }
    val thumb=bitmap
    if(thumb!=null)androidx.compose.foundation.Image(thumb.asImageBitmap(),null,modifier.clip(androidx.compose.foundation.shape.RoundedCornerShape(8.dp)),contentScale=androidx.compose.ui.layout.ContentScale.Crop)
    else Box(modifier,contentAlignment=Alignment.Center) { Icon(fileIcon(item.mime),null,Modifier.size(36.dp),tint=MaterialTheme.colorScheme.primary) }
}
