@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package app.tgdrive.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.ui.semantics.selected
import androidx.compose.foundation.lazy.grid.GridItemSpan
import java.text.DateFormat
import java.util.Date
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.tgdrive.SessionBundle
import app.tgdrive.library.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Excludes unfinished uploads, Trash, and locked vaults in every landing collection. */
internal fun libraryCollection(state: LibraryState, kind: String = "all", query: String = ""): List<Item> {
    val uploadedAt = state.versions.associate { it.id to it.createdAt }
    return state.items.asSequence().filter {
        val vault = state.itemVaults[it.id].orEmpty()
        it.versionId.isNotEmpty() && it.deletedAt == 0L && (vault.isEmpty() || vault in state.unlockedVaultIds) &&
            (kind == "all" || kind == "gallery" && (it.mime.startsWith("image/") || it.mime.startsWith("video/")) || kind == "audio" && it.mime.startsWith("audio/")) &&
            (query.isBlank() || (it.name + " " + it.tags.joinToString(" ")+" "+state.local[it.id]?.ocrText.orEmpty()).contains(query.trim(), true))
    }.sortedByDescending { uploadedAt[it.versionId] ?: it.createdAt }.toList()
}

@Composable
fun HomePage(state: LibraryState, bundle: SessionBundle, system: SystemActions, onPreview: (String) -> Unit, page: String, onPage: (String) -> Unit, onFolderTitle: (String) -> Unit, onFolderBack: ((()->Unit)?)->Unit,homeQuery:String="",filesQuery:String="",onFilters:((()->Unit)->Unit)={},recentView:Boolean=false,onRecent:()->Unit={},onAdd:()->Unit={},addRequested:Boolean=false,onAddConsumed:()->Unit={},homeSearch:HeaderSearch?=null,filesSearch:HeaderSearch?=null) {
    var actionId by rememberSaveable { mutableStateOf("") }
    val saved = androidx.compose.runtime.saveable.rememberSaveableStateHolder()
    BackHandler(page != "home") { onPage("home") }
    AnimatedContent(page, transitionSpec = { horizontalNavigation(targetState != "home") }, label = "home-detail") { route ->
        saved.SaveableStateProvider(route) {
            if (route == "home") {
                Column(Modifier.fillMaxSize()){
                homeSearch?.let{HeaderSearchField(it)}
                HomeContent(state, bundle, onOpen = onPage, onPreview = onPreview, onMenu = { actionId = it },
                    onUpload = onAdd,query=homeQuery,onRecent=onRecent)
                }
            } else Column(Modifier.fillMaxSize()) {
                when(route) {
                    "files" -> Column(Modifier.fillMaxSize()){filesSearch?.let{HeaderSearchField(it)};FilesPage(state, bundle, system, onPreview, onFolderTitle,onFolderBack,query=filesQuery,onFilters=onFilters,recentView=recentView,addRequested=addRequested,onAddConsumed=onAddConsumed)}
                    "activity" -> TransfersPage(state, bundle, system)
                    "backups" -> BackupsPage(state, bundle, system)
                }
            }
        }
    }
    state.items.firstOrNull { it.id == actionId }?.let { item ->
        FileActionsSheet(item,state,bundle,system,onDismiss={actionId=""},onPreview={actionId="";onPreview(item.id)})
    }
}

@Composable
internal fun HomeContent(state: LibraryState, bundle: SessionBundle? = null, onOpen: (String) -> Unit,
                         onPreview: (String) -> Unit, onMenu: (String) -> Unit, onUpload: () -> Unit,
                         query:String="",onRecent:()->Unit={onOpen("files")}) {
    val rows=computedList(state.items,state.versions,state.itemVaults,state.unlockedVaultIds,query,state.local){libraryCollection(state,query=query).let{if(query.isBlank())it.take(6)else it}}
    val allowedIds=remember(state.items,state.itemVaults,state.unlockedVaultIds){state.items.filter{it.deletedAt==0L&&(state.itemVaults[it.id].orEmpty().isEmpty()||state.itemVaults[it.id] in state.unlockedVaultIds)}.mapTo(hashSetOf()){it.id}}
    val visible=rows.orEmpty().filter{it.id in allowedIds}
    LazyColumn(Modifier.fillMaxSize(),contentPadding=PaddingValues(16.dp),verticalArrangement=Arrangement.spacedBy(16.dp)) {
        if(query.isBlank()) {
            item{LibraryUsageCard(state,onUpload)}
            item {BoxWithConstraints(Modifier.fillMaxWidth()){
                val links=listOf(Triple("All files","files",DriveIcons.Folder),Triple("Activity","activity",DriveIcons.SwapVert),Triple("Backups","backups",DriveIcons.Backup))
                if(maxWidth<320.dp||androidx.compose.ui.platform.LocalDensity.current.fontScale>1.3f)Column(verticalArrangement=Arrangement.spacedBy(8.dp)){
                    links.forEach{(title,route,icon)->ActionRow(title,icon=icon){onOpen(route)}}
                }else Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(8.dp)){
                    links.forEach{(title,route,icon)->DriveCard(onClick={onOpen(route)},modifier=Modifier.weight(1f)){
                        Row(Modifier.fillMaxWidth().heightIn(min=64.dp).padding(10.dp),horizontalArrangement=Arrangement.spacedBy(6.dp),verticalAlignment=Alignment.CenterVertically){
                            Icon(icon,null,Modifier.size(20.dp));Text(title,Modifier.weight(1f),style=MaterialTheme.typography.labelMedium)
                        }
                    }}
                }
            }}
        }
        item { Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically) {
            Text(if(query.isBlank())"Recent uploads"else"Search results",Modifier.weight(1f),style=MaterialTheme.typography.titleMedium)
            if(query.isBlank())TextButton(onRecent){Text("See all")}
        } }
        if(rows==null)item{LinearProgressIndicator(Modifier.fillMaxWidth())}
        if(rows!=null&&visible.isEmpty())item{InfoCard(if(query.isBlank())"Your library starts here"else"No matching files",if(query.isBlank())"Add files to see them across Home, Gallery and Audio."else"Try another name, tag or word from extracted text.",DriveIcons.Folder)}
        items(visible,key={it.id}) { item -> NativeFileCard(item,state.local[item.id],state.itemVaults[item.id].orEmpty().isNotEmpty(),false,false,
            onClick={onPreview(item.id)},onLongClick={onMenu(item.id)},onMenu={onMenu(item.id)},bundle=bundle) }
        if(query.isBlank()&&state.rules.none{it.enabled})item{ActionRow("Back up a folder","Choose the folders you want to keep",DriveIcons.Backup){onOpen("backups")}}
    }
}

@Composable
fun MediaPage(state: LibraryState, bundle: SessionBundle, system: SystemActions, gallery: Boolean, onPreview: (String) -> Unit,query:String="",selecting:Boolean=false,selected:Set<String> = emptySet(),onSelection:(Set<String>)->Unit={},onSelectMode:()->Unit={},search:HeaderSearch?=null,sort:MediaSort=MediaSort.NEWEST,kind:String="All",onKind:(String)->Unit={}) {
    var actionId by rememberSaveable { mutableStateOf("") }
    val selectionScope=listOf(query,kind)
    var savedSelectionScope by rememberSaveable{mutableStateOf(selectionScope)}
    LaunchedEffect(selectionScope){if(savedSelectionScope!=selectionScope){onSelection(emptySet());savedSelectionScope=selectionScope}}
    var move by rememberSaveable{mutableStateOf(false)}
    var trash by rememberSaveable{mutableStateOf(false)}
    val media = computedList(state.items, state.versions, state.local, state.itemVaults, state.unlockedVaultIds, gallery, query, sort) {
        sortMedia(libraryCollection(state, if(gallery) "gallery" else "audio", query),sort,state.versions)
    }
    val visibleIds = state.items.filter { it.deletedAt==0L && (state.itemVaults[it.id].orEmpty().isEmpty() || state.itemVaults[it.id] in state.unlockedVaultIds) }.map{it.id}.toSet()
    val safeSelected=if(savedSelectionScope==selectionScope)selected.intersect(visibleIds)else emptySet()
    val shown=media.orEmpty().filter{it.id in visibleIds&&(!gallery||kind=="All"||kind=="Photos"&&it.mime.startsWith("image/")||kind=="Videos"&&it.mime.startsWith("video/"))}
    Column(Modifier.fillMaxSize()) {
        search?.let{HeaderSearchField(it)}
        if(gallery&&!selecting)GalleryFilters(kind,onKind)
        if(!gallery||selecting)Text(if(selecting)"${safeSelected.size} selected"else"${shown.size} audio files",Modifier.padding(horizontal=16.dp,vertical=8.dp),style=MaterialTheme.typography.labelMedium,color=MaterialTheme.colorScheme.onSurfaceVariant)
        val shownIds=shown.mapTo(hashSetOf()){it.id}
        LaunchedEffect(shownIds,media!=null){if(media!=null)onSelection(safeSelected.intersect(shownIds))}
        Box(Modifier.weight(1f)) {if(media==null)CircularProgressIndicator(Modifier.align(Alignment.Center))else MediaContent(shown,gallery,bundle,{id->if(selecting)onSelection(if(id in safeSelected)safeSelected-id else safeSelected+id)else onPreview(id)},{actionId=it},state=state,selected=safeSelected,selecting=selecting,onLongSelect={id->onSelectMode();onSelection(safeSelected+id)},groupDates=sort==MediaSort.NEWEST||sort==MediaSort.OLDEST,searching=query.isNotBlank())}
        if(gallery&&selecting) {
            androidx.compose.foundation.layout.FlowRow(Modifier.fillMaxWidth().padding(8.dp),horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                TextButton({onSelection(shown.map{it.id}.toSet())}){Text("Select all")}
                TextButton({system.exportZip(safeSelected.toList())},enabled=safeSelected.isNotEmpty()){Text("Save ZIP")}
                TextButton({move=true},enabled=safeSelected.isNotEmpty()&&safeSelected.map{state.itemVaults[it].orEmpty()}.distinct().size==1){Text("Move")}
                TextButton({trash=true},enabled=safeSelected.isNotEmpty()){Text("Trash (${safeSelected.size})")}
            }
        }
    }
    val form=rememberFormAction()
    if(move)FolderPicker(state,"",{move=false},allowedVault=safeSelected.firstOrNull()?.let{state.itemVaults[it].orEmpty()},busy=form.busy,error=form.error){target->val ids=safeSelected.toList();form.run({onSelection(emptySet());move=false}){bundle.actions.move(ids,target)}}
    if(trash)ConfirmDialog("Move ${safeSelected.size} files to Trash?","You can restore them from Trash.","Move to Trash",{trash=false},busy=form.busy,error=form.error){val ids=safeSelected.toList();form.run({onSelection(emptySet());trash=false}){bundle.actions.trash(ids)}}
    state.items.firstOrNull { it.id == actionId }?.let { item ->
        FileActionsSheet(item,state,bundle,system,onDismiss={actionId=""},onPreview={actionId="";onPreview(item.id)})
    }
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
internal fun MediaContent(items: List<Item>, gallery: Boolean, bundle: SessionBundle? = null, onPreview: (String) -> Unit, onMenu: (String) -> Unit,state:LibraryState=LibraryState(),selected:Set<String> = emptySet(),selecting:Boolean=false,onLongSelect:(String)->Unit={},groupDates:Boolean=true,searching:Boolean=false) {
    if(items.isEmpty()) Box(Modifier.fillMaxSize().padding(24.dp),contentAlignment=Alignment.Center) {
        InfoCard(if(searching)"No matching files"else if(gallery) "No photos or videos" else "No audio files", if(searching)"Try another name, tag or word from extracted text."else if(gallery)"Add photos and videos from Home to see them here."else"Add audio from Home to see it here.", if(gallery) DriveIcons.PhotoLibrary else DriveIcons.AudioFile)
    } else if(gallery) LazyVerticalGrid(GridCells.Adaptive(100.dp),Modifier.fillMaxSize(),contentPadding=PaddingValues(bottom=24.dp),
        horizontalArrangement=Arrangement.spacedBy(2.dp),verticalArrangement=Arrangement.spacedBy(2.dp)) {
        val dates=state.versions.associate{it.id to it.createdAt}
        (if(groupDates)items.groupBy{DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(dates[it.versionId]?:it.createdAt))}else linkedMapOf("" to items)).forEach{(date,group)->
        if(date.isNotEmpty())item(span={GridItemSpan(maxLineSpan)}){Text("Uploaded $date",Modifier.padding(horizontal=16.dp,vertical=10.dp),style=MaterialTheme.typography.labelMedium,color=MaterialTheme.colorScheme.onSurfaceVariant)}
        items(group,key={it.id}) { item ->
            androidx.compose.foundation.layout.Box(Modifier.fillMaxWidth().aspectRatio(4f/5f).background(MaterialTheme.colorScheme.surfaceContainerLow)
                .combinedClickable(role=androidx.compose.ui.semantics.Role.Button,onClickLabel=if(selecting)"Select ${item.name}"else"Open ${item.name}",onClick={onPreview(item.id)},onLongClick={onLongSelect(item.id)},onLongClickLabel="Select file")
                .semantics { contentDescription = item.name;this.selected=item.id in selected }) {
                FileThumbnail(item,bundle,Modifier.fillMaxSize(),square=true)
                if(selecting)Checkbox(item.id in selected,null,Modifier.align(Alignment.TopEnd))
                if(item.mime.startsWith("video/")) Icon(DriveIcons.PlayCircle,"Video",Modifier.align(Alignment.BottomStart).padding(8.dp).size(20.dp).background(Color.Black.copy(alpha=.45f),DriveStyle.PillShape),tint=Color.White)
            }
        }
        }
    } else LazyColumn(Modifier.fillMaxSize(),contentPadding=PaddingValues(start=16.dp,end=16.dp,bottom=24.dp),verticalArrangement=Arrangement.spacedBy(10.dp)) {
        items(items,key={it.id}) { item -> NativeFileCard(item,state.local[item.id],state.itemVaults[item.id].orEmpty().isNotEmpty(),false,false,onClick={onPreview(item.id)},onLongClick={onMenu(item.id)},onMenu={onMenu(item.id)},bundle=bundle) }
    }
}

@Composable internal fun GalleryFilters(kind:String,onKind:(String)->Unit) {
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal=16.dp),horizontalArrangement=Arrangement.spacedBy(8.dp)) {
        listOf("All","Photos","Videos").forEach{label->DriveFilterChip(kind==label,{onKind(label)},label={Text(label)})}
    }
}
