package app.tgdrive.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
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
            (query.isBlank() || (it.name + " " + it.tags.joinToString(" ")).contains(query.trim(), true))
    }.sortedByDescending { uploadedAt[it.versionId] ?: it.createdAt }.toList()
}

@Composable
fun HomePage(state: LibraryState, bundle: SessionBundle, system: SystemActions, onPreview: (String) -> Unit) {
    var page by rememberSaveable { mutableStateOf("home") }
    var actionId by remember { mutableStateOf("") }
    val saved = androidx.compose.runtime.saveable.rememberSaveableStateHolder()
    BackHandler(page != "home") { page = "home" }
    AnimatedContent(page, transitionSpec = { horizontalNavigation(targetState != "home") }, label = "home-detail") { route ->
        saved.SaveableStateProvider(route) {
            if (route == "home") {
                HomeContent(state, bundle, onOpen = { page = it }, onPreview = onPreview, onMenu = { actionId = it },
                    onUpload = { system.upload("", "", "") })
            } else Column(Modifier.fillMaxSize()) {
                DrivePageHeader(when(route) { "files" -> "All files"; "activity" -> "Activity"; else -> "Backups" }, { page = "home" }, backDescription = "Back to Home")
                when(route) {
                    "files" -> FilesPage(state, bundle, system, onPreview)
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
                         onPreview: (String) -> Unit, onMenu: (String) -> Unit, onUpload: () -> Unit) {
    val recent by produceState(emptyList<Item>(), state.items, state.versions, state.itemVaults, state.unlockedVaultIds) {
        value = withContext(Dispatchers.Default) { libraryCollection(state).take(20) }
    }
    LazyColumn(Modifier.fillMaxSize(), contentPadding=PaddingValues(16.dp), verticalArrangement=Arrangement.spacedBy(16.dp)) {
        item { SectionTitle("Your files", "Everything you keep, in one place") }
        item { DriveButton(onUpload, Modifier.fillMaxWidth()) { Icon(Icons.Outlined.Add,null); Spacer(Modifier.width(8.dp)); Text("Add files") } }
        item { Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(8.dp)) {
            listOf(Triple("Browse all files","files",Icons.Outlined.Folder),Triple("Activity","activity",Icons.Outlined.SwapVert),Triple("Backups","backups",Icons.Outlined.Backup)).forEach { (title,route,icon) ->
                DriveCard(onClick={onOpen(route)},modifier=Modifier.weight(1f)) {
                    Column(Modifier.fillMaxWidth().heightIn(min=88.dp).padding(12.dp),horizontalAlignment=Alignment.CenterHorizontally,verticalArrangement=Arrangement.spacedBy(8.dp)) {
                        Icon(icon,null)
                        Text(title,style=MaterialTheme.typography.labelMedium,textAlign=androidx.compose.ui.text.style.TextAlign.Center)
                    }
                }
            }
        } }
        item { Text("Suggested for you",style=MaterialTheme.typography.titleMedium) }
        item {
            if(state.rules.none { it.enabled }) ActionRow("Keep a folder backed up", "Choose a folder and review its backup rules", Icons.Outlined.Backup) { onOpen("backups") }
            else ActionRow("Review your transfers", "Check progress and resume paused work", Icons.Outlined.SwapVert) { onOpen("activity") }
        }
        item { Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically) {
            Text("Recently uploaded",Modifier.weight(1f),style=MaterialTheme.typography.titleMedium)
            TextButton({onOpen("files")}) { Text("See all") }
        } }
        if(recent.isEmpty()) item { InfoCard("Your space is ready", "Add your first files. Your latest uploads will appear here.", Icons.Outlined.CloudUpload) }
        items(recent,key={it.id}) { item -> NativeFileCard(item,state.local[item.id],state.itemVaults[item.id].orEmpty().isNotEmpty(),false,false,
            onClick={onPreview(item.id)},onLongClick={onMenu(item.id)},onMenu={onMenu(item.id)},bundle=bundle) }
        item { Spacer(Modifier.height(16.dp)) }
    }
}

@Composable
fun MediaPage(state: LibraryState, bundle: SessionBundle, system: SystemActions, gallery: Boolean, onPreview: (String) -> Unit) {
    var query by rememberSaveable { mutableStateOf("") }
    var actionId by remember { mutableStateOf("") }
    val media by produceState(emptyList<Item>(), state.items, state.versions, state.itemVaults, state.unlockedVaultIds, gallery, query) {
        value = withContext(Dispatchers.Default) { libraryCollection(state, if(gallery) "gallery" else "audio", query) }
    }
    Column(Modifier.fillMaxSize()) {
        Column(Modifier.padding(16.dp),verticalArrangement=Arrangement.spacedBy(14.dp)) {
            SectionTitle(if(gallery) "Gallery" else "Audio",if(gallery) "Your photos and videos" else "Your songs and recordings")
            DriveTextField(query,{query=it},Modifier.fillMaxWidth(),singleLine=true,placeholder={Text(if(gallery) "Search photos and videos" else "Search audio")},leadingIcon={Icon(Icons.Outlined.Search,null)})
            Text("${media.size} files",style=MaterialTheme.typography.labelMedium,color=MaterialTheme.colorScheme.onSurfaceVariant)
        }
        MediaContent(media,gallery,bundle,onPreview,{actionId=it})
    }
    state.items.firstOrNull { it.id == actionId }?.let { item ->
        FileActionsSheet(item,state,bundle,system,onDismiss={actionId=""},onPreview={actionId="";onPreview(item.id)})
    }
}

@Composable
internal fun MediaContent(items: List<Item>, gallery: Boolean, bundle: SessionBundle? = null, onPreview: (String) -> Unit, onMenu: (String) -> Unit) {
    if(items.isEmpty()) Box(Modifier.fillMaxSize().padding(24.dp),contentAlignment=Alignment.Center) {
        InfoCard(if(gallery) "No photos or videos" else "No audio files", "Upload files from Home, or try another search.", if(gallery) Icons.Outlined.PhotoLibrary else Icons.Outlined.AudioFile)
    } else if(gallery) LazyVerticalGrid(GridCells.Adaptive(112.dp),Modifier.fillMaxSize(),contentPadding=PaddingValues(start=16.dp,end=16.dp,bottom=24.dp),
        horizontalArrangement=Arrangement.spacedBy(8.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
        items(items,key={it.id}) { item ->
            DriveCard(onClick={onPreview(item.id)}) {
                Box(Modifier.fillMaxWidth().aspectRatio(1f).background(MaterialTheme.colorScheme.surfaceContainerLow)) {
                    FileThumbnail(item,bundle,Modifier.fillMaxSize())
                    if(item.mime.startsWith("video/")) Icon(Icons.Outlined.PlayCircle,"Video",Modifier.align(Alignment.BottomStart).padding(8.dp).size(24.dp).background(Color.Black.copy(alpha=.55f),DriveStyle.PillShape),tint=Color.White)
                    IconButton({onMenu(item.id)},Modifier.align(Alignment.TopEnd).padding(4.dp).size(48.dp).background(Color.Black.copy(alpha=.55f),DriveStyle.PillShape)) { Icon(Icons.Outlined.MoreHoriz,"Actions for ${item.name}",tint=Color.White) }
                }
                Text(item.name,Modifier.padding(10.dp),maxLines=2,overflow=TextOverflow.Ellipsis,style=MaterialTheme.typography.labelMedium)
            }
        }
    } else LazyColumn(Modifier.fillMaxSize(),contentPadding=PaddingValues(start=16.dp,end=16.dp,bottom=24.dp),verticalArrangement=Arrangement.spacedBy(10.dp)) {
        items(items,key={it.id}) { item -> NativeFileCard(item,null,false,false,false,onClick={onPreview(item.id)},onLongClick={onMenu(item.id)},onMenu={onMenu(item.id)},bundle=bundle) }
    }
}
