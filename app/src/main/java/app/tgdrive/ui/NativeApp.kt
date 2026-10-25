package app.tgdrive.ui

import androidx.compose.animation.*
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.tween
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.tgdrive.*
import app.tgdrive.data.LoginStep
import app.tgdrive.library.*

data class SystemActions(
    val upload: (String,String,String) -> Unit, val save: (Item) -> Unit,
    val exportZip: (List<String>) -> Unit, val exportIndex: () -> Unit, val importIndex: () -> Unit,
    val backupFolder: (String,String) -> Unit, val scan: (String,String) -> Unit,
    val shareExternal: (Item) -> Unit, val openExternal: (Item) -> Unit,
    val cleanup: (List<String>) -> Unit, val play: (Item) -> Unit, val setAppLock: (Boolean) -> Unit,
    val startTransfers: () -> Unit,
    val scanBackups: (String?,Boolean) -> Unit,
    val reconnectBackup:(String)->Unit={},
    val transferSettings:()->Unit={},val vaultSettings:()->Unit={},
)
enum class NativeTab(val title: String, val icon: ImageVector) {
    HOME("Home",DriveIcons.Home), GALLERY("Gallery",DriveIcons.PhotoLibrary), AUDIO("Audio",DriveIcons.AudioFile), SETTINGS("Settings",DriveIcons.Tune),
    // Keep serialized enum names readable when Android restores a 0.3.0 activity.
    FILES("Home",DriveIcons.Home), TRANSFERS("Activity",DriveIcons.SwapVert), BACKUPS("Backups",DriveIcons.Backup)
}
internal val navigationTabs = listOf(NativeTab.HOME,NativeTab.GALLERY,NativeTab.AUDIO,NativeTab.SETTINGS)
internal val nativeTabSaver = Saver<NativeTab,Any>(save={it.name},restore={saved->
    val name = if(saved is NativeTab) saved.name else saved as? String
    navigationTabs.firstOrNull{it.name==name} ?: NativeTab.HOME
})

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NativeApp(bundle: SessionBundle, pool: SessionPool, system: SystemActions,requestedRoute:String="",onRouteConsumed:()->Unit={}) {
    val auth by bundle.drive.state.collectAsStateWithLifecycle()
    val state by bundle.library.state.collectAsStateWithLifecycle()
    val profiles by pool.profiles.collectAsStateWithLifecycle()
    var tab by rememberSaveable(bundle.profile.id, stateSaver=nativeTabSaver) { mutableStateOf(NativeTab.HOME) }
    var homeRoute by rememberSaveable(bundle.profile.id) { mutableStateOf("home") }
    var settingsRoute by rememberSaveable(bundle.profile.id) { mutableStateOf("home") }
    var fileBack by remember { mutableStateOf<(() -> Unit)?>(null) }
    var folderTitle by remember { mutableStateOf("All files") }
    var homeQuery by rememberSaveable(bundle.profile.id){mutableStateOf("")}
    var filesQuery by rememberSaveable(bundle.profile.id){mutableStateOf("")}
    var galleryQuery by rememberSaveable(bundle.profile.id){mutableStateOf("")}
    var galleryKind by rememberSaveable(bundle.profile.id){mutableStateOf("All")}
    var gallerySort by rememberSaveable(bundle.profile.id){mutableStateOf(MediaSort.NEWEST)}
    var audioSort by rememberSaveable(bundle.profile.id){mutableStateOf(MediaSort.NEWEST)}
    var audioQuery by rememberSaveable(bundle.profile.id){mutableStateOf("")}
    var settingsQuery by rememberSaveable(bundle.profile.id){mutableStateOf("")}
    var gallerySelecting by rememberSaveable(bundle.profile.id){mutableStateOf(false)}
    var gallerySelected by rememberSaveable(bundle.profile.id){mutableStateOf(emptyList<String>())}
    var filesFilters by remember{mutableStateOf<(() -> Unit)?>(null)}
    var addRequested by rememberSaveable(bundle.profile.id){mutableStateOf(false)}
    var recentView by rememberSaveable(bundle.profile.id){mutableStateOf(false)}
    var accountSheet by rememberSaveable { mutableStateOf(false) }
    var removeProfileId by rememberSaveable{mutableStateOf("")}
    var previewId by rememberSaveable(bundle.profile.id) { mutableStateOf("") }
    LaunchedEffect(requestedRoute,state.loaded){if(state.loaded&&requestedRoute.isNotEmpty()){
        if(requestedRoute in setOf("transferSettings","vaultSettings")){tab=NativeTab.SETTINGS;settingsRoute=if(requestedRoute=="vaultSettings")"vaults"else"transfers"}else{tab=NativeTab.HOME;homeRoute=if(requestedRoute=="backups")"backups"else"activity"};onRouteConsumed()
    }}
    val pageState = rememberSaveableStateHolder()
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(state.messageRevision,state.notice,state.error,auth.error) {
        val displayed=state
        val message = displayed.error ?: displayed.notice ?: auth.error
        if(message!=null){val undo=if(displayed.notice?.startsWith("Moved ")==true)displayed.undoTrashIds else emptyList();val result=snackbar.showSnackbar(message,actionLabel=if(undo.isNotEmpty()&&displayed.error==null)"Undo"else null);if(result==SnackbarResult.ActionPerformed&&undo.isNotEmpty())bundle.actions.run{bundle.actions.restore(undo)};if(state.loaded)bundle.library.acknowledgeNotice(displayed.messageRevision,displayed.error,displayed.notice);if(state.loaded||auth.login!=LoginStep.READY)bundle.drive.clearNotice()}
    }
    LaunchedEffect(settingsRoute){if(settingsRoute.substringBefore("#") !in visibleSettingsRoutes)settingsRoute="home"}
    val preview = remember(state.items,previewId){state.items.firstOrNull { it.id == previewId&&it.deletedAt==0L }}
    var lastPreview by remember{mutableStateOf<Item?>(null)}
    SideEffect{if(preview!=null)lastPreview=preview}
    val galleryResult=if(preview!=null&&tab==NativeTab.GALLERY) computedList(state.items,state.versions,state.local,state.unlockedVaultIds,state.itemVaults,galleryQuery,gallerySort,galleryKind){
        sortMedia(libraryCollection(state,"gallery",galleryQuery).filter{galleryKind=="All"||galleryKind=="Photos"&&it.mime.startsWith("image/")||galleryKind=="Videos"&&it.mime.startsWith("video/")},gallerySort,state.versions)
    }else emptyList()
    var previousGallery by remember(galleryQuery,galleryKind,gallerySort){mutableStateOf(emptyList<Item>())}
    SideEffect{if(preview!=null&&galleryResult!=null)previousGallery=galleryResult}
    val allowedGalleryIds=state.items.filter{it.deletedAt==0L&&(state.itemVaults[it.id].orEmpty().isEmpty()||state.itemVaults[it.id] in state.unlockedVaultIds)}.mapTo(hashSetOf()){it.id}
    val viewerGallery=(if(preview==null)previousGallery else galleryResult?:previousGallery).filter{it.id in allowedGalleryIds}
    BackHandler(enabled=tab!=NativeTab.HOME && preview==null){tab=NativeTab.HOME}
    BackHandler(enabled=tab==NativeTab.GALLERY&&gallerySelecting&&preview==null){gallerySelected=emptyList();gallerySelecting=false}
    BackHandler(enabled=preview!=null){previewId=""}
    LaunchedEffect(state.items,previewId) { if(previewId.isNotEmpty()&&preview==null)previewId="" }
    AnimatedContent(targetState=preview!=null,transitionSpec={ horizontalNavigation(targetState) },label="file-preview") { showingPreview ->
        if(showingPreview) {
            val item=preview?:lastPreview
            if(galleryResult==null&&viewerGallery.isEmpty())Box(Modifier.fillMaxSize(),contentAlignment=Alignment.Center){CircularProgressIndicator()}
            else if(item!=null&&state.items.any{it.id==item.id&&it.deletedAt==0L})NativePreview(item,bundle,system,onBack={previewId=""},snackbar=snackbar,gallery=if(tab==NativeTab.GALLERY)viewerGallery else emptyList(),onNavigate={previewId=it})
        }else{
            val signingIn = auth.login != LoginStep.READY
            val headerTitle = when {
                signingIn -> "Sign in"
                !state.loaded -> "Your library"
                tab==NativeTab.HOME -> when(homeRoute){"files"->folderTitle;"activity"->"Activity";"backups"->"Backups";else->"Home"}
                tab==NativeTab.SETTINGS -> settingsTitle(settingsRoute)
                else -> tab.title
            }
            val back: (() -> Unit)? = when {
                !signingIn && tab==NativeTab.HOME && homeRoute!="home" -> if(homeRoute=="files"&&fileBack!=null)fileBack else {{homeRoute="home"}}
                !signingIn && tab==NativeTab.SETTINGS && settingsRoute!="home" -> {{settingsRoute=settingsParent(settingsRoute)}}
                else -> null
            }
            Scaffold(containerColor=if(signingIn)DriveStyle.SignInCanvas else MaterialTheme.colorScheme.background,
                snackbarHost={SnackbarHost(snackbar)}, topBar={
                    DriveAppHeader(headerTitle,back,actions={
                        if(!signingIn&&state.loaded&&(tab==NativeTab.GALLERY||tab==NativeTab.AUDIO)) {
                            MediaSortMenu(tab==NativeTab.GALLERY,if(tab==NativeTab.GALLERY)gallerySort else audioSort){if(tab==NativeTab.GALLERY)gallerySort=it else audioSort=it}
                            if(tab==NativeTab.GALLERY)TextButton({gallerySelecting=!gallerySelecting;gallerySelected=emptyList()}){Text(if(gallerySelecting)"Done"else"Select")}
                        }
                    })
            },bottomBar={if(auth.login==LoginStep.READY&&state.loaded)Column{AudioMiniPlayer(bundle){previewId=it};DriveNavigationBar(tab,{selected->if(selected==tab){if(selected==NativeTab.HOME){homeRoute="home";homeQuery=""}else if(selected==NativeTab.SETTINGS){settingsRoute="home";settingsQuery=""}};tab=selected},state.tasks.any{it.state==TaskState.FAILED})}}){padding->
                when {
                    auth.login!=LoginStep.READY->Column(Modifier.fillMaxSize().padding(padding)){
                        TextButton({accountSheet=true},Modifier.align(Alignment.End).padding(end=12.dp)){Text("Manage accounts")}
                        AuthPanel(auth,bundle.drive::configure,bundle.drive::login,bundle.drive::reconnect,bundle.drive::editConfiguration,Modifier.weight(1f),bundle.drive::changePhoneNumber,bundle.drive::resendCode)
                    }
                    !state.loaded->Column(Modifier.fillMaxSize().padding(padding).padding(24.dp),verticalArrangement=Arrangement.Center,horizontalAlignment=Alignment.CenterHorizontally){
                        val openError=state.error?:auth.error
                        if(openError==null){CircularProgressIndicator();Spacer(Modifier.height(18.dp));Text("Opening your private library…")}else{Text(openError,color=MaterialTheme.colorScheme.error);Text("Your Telegram files have not been deleted.");DriveButton(onClick={bundle.library.retryInitialization()}){Text("Retry opening library")}}
                    }
                    else->AnimatedContent(tab,Modifier.padding(padding),transitionSpec={horizontalNavigation(targetState.ordinal > initialState.ordinal)},label="navigation") { page ->
                        pageState.SaveableStateProvider(page){when(page){
                            NativeTab.HOME,NativeTab.FILES,NativeTab.TRANSFERS,NativeTab.BACKUPS->HomePage(state,bundle,system,onPreview={previewId=it},page=homeRoute,onPage={homeRoute=it;if(it=="files")recentView=false},onFolderTitle={folderTitle=it},onFolderBack={fileBack=it},homeQuery=homeQuery,filesQuery=filesQuery,onFilters={filesFilters=it},recentView=recentView,onRecent={recentView=true;filesQuery="";homeRoute="files"},onAdd={recentView=false;addRequested=true;homeRoute="files"},addRequested=addRequested,onAddConsumed={addRequested=false},homeSearch=HeaderSearch(homeQuery,{homeQuery=it},"Search your library"),filesSearch=HeaderSearch(filesQuery,{filesQuery=it},"Search files, tags and text",filesFilters))
                            NativeTab.GALLERY->MediaPage(state,bundle,system,gallery=true,onPreview={previousGallery=emptyList();previewId=it},query=galleryQuery,search=HeaderSearch(galleryQuery,{galleryQuery=it},"Search photos and videos"),sort=gallerySort,kind=galleryKind,onKind={galleryKind=it},selecting=gallerySelecting,selected=gallerySelected.toSet(),onSelection={gallerySelected=it.toList()},onSelectMode={gallerySelecting=true})
                            NativeTab.AUDIO->MediaPage(state,bundle,system,gallery=false,onPreview={previewId=it},query=audioQuery,search=HeaderSearch(audioQuery,{audioQuery=it},"Search audio"),sort=audioSort)
                            NativeTab.SETTINGS->SettingsPage(state,bundle,pool,system,onClose={tab=NativeTab.HOME},page=settingsRoute,onPage={settingsRoute=it},onAccounts={accountSheet=true},query=settingsQuery,search=HeaderSearch(settingsQuery,{settingsQuery=it},"Search settings"))
                        }}
                    }
                }
            }
        }
    }
    if(accountSheet)ModalBottomSheet(onDismissRequest={accountSheet=false}){
        Column(Modifier.fillMaxWidth().padding(24.dp),verticalArrangement=Arrangement.spacedBy(12.dp)){
            Text("Your accounts",style=MaterialTheme.typography.headlineSmall)
            profiles.forEach { profile->DriveCard(onClick={pool.switch(profile.id);accountSheet=false},modifier=Modifier.fillMaxWidth()){
                Row(Modifier.fillMaxWidth().padding(16.dp),verticalAlignment=Alignment.CenterVertically){AccountAvatar(pool.bundle(profile.id),profile.label);Spacer(Modifier.width(12.dp));Column(Modifier.weight(1f)){Text(profile.label);Text(if(profile.userId==0L)"Not signed in"else"Account · ${profile.userId.toString().takeLast(4)}",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)};if(profile.id==bundle.profile.id)Icon(DriveIcons.Check,null,tint=MaterialTheme.colorScheme.primary)}
            }}
            profiles.filter{it.userId==0L&&it.id!=bundle.profile.id}.forEach{profile->
                TextButton({removeProfileId=profile.id}){Text("Remove signed-out profile: ${profile.label}")}
            }
            TextButton(enabled=profiles.size<5,onClick={runCatching{pool.add()}.onFailure{bundle.library.report(it)};accountSheet=false}){Icon(DriveIcons.PersonAdd,null);Spacer(Modifier.width(8.dp));Text("Add Telegram account")}
            if(profiles.size>=5)Text("Five profiles are already saved. Remove a signed-out profile to add another.",style=MaterialTheme.typography.bodySmall)
            if(auth.login==LoginStep.READY)TextButton(onClick={bundle.actions.run{
                require(state.tasks.none{it.state in setOf(TaskState.PREPARING,TaskState.TRANSFERRING)}){"Pause transfers before signing out."}
                bundle.library.context.stopService(android.content.Intent(bundle.library.context,PlaybackService::class.java))
                bundle.drive.logout()
            };accountSheet=false}){Text("Sign out of this account",color=MaterialTheme.colorScheme.error)}
            Text("Each profile has a separate Telegram session. Switching accounts does not move or share your files.",style=MaterialTheme.typography.bodySmall)
            Spacer(Modifier.height(12.dp))
        }
    }
    val accountForm=rememberFormAction()
    profiles.firstOrNull{it.id==removeProfileId}?.let{profile->ConfirmDialog("Remove local profile?","${profile.label} will be removed from the account switcher. Telegram messages are not deleted. Profiles with pending or failed work must be reviewed first.","Remove profile",{removeProfileId=""},busy=accountForm.busy,error=accountForm.error){accountForm.run({removeProfileId=""}){pool.removeSignedOut(profile.id)}}}
    state.lastShare?.let { result->AlertDialog(onDismissRequest=bundle.library::clearShare,title={Text("Public copy created")},text={Column{
        androidx.compose.foundation.text.selection.SelectionContainer { Text(result.url) };Text("This link grants access to an exported copy. Revoking cannot recall downloaded copies.",style=MaterialTheme.typography.bodySmall)
    }},confirmButton={TextButton(onClick=bundle.library::clearShare){Text("Done")}}) }
}

@Composable fun SectionTitle(title: String, subtitle: String? = null) {
    Column(verticalArrangement=Arrangement.spacedBy(8.dp)){Text(title,style=MaterialTheme.typography.headlineMedium);if(subtitle!=null)Text(subtitle,style=MaterialTheme.typography.bodyMedium,color=MaterialTheme.colorScheme.onSurfaceVariant)}
}
@Composable fun InfoCard(title: String, text: String, icon: ImageVector = DriveIcons.Info) {
    Card(shape=DriveStyle.CardShape,colors=CardDefaults.cardColors(containerColor=MaterialTheme.colorScheme.surfaceContainerLow)){
        Row(Modifier.fillMaxWidth().padding(20.dp),horizontalArrangement=Arrangement.spacedBy(12.dp)) {Icon(icon,null,tint=MaterialTheme.colorScheme.onSurfaceVariant);Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(6.dp)){Text(title,style=MaterialTheme.typography.titleSmall);Text(text,style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)}}
    }
}
@Composable fun ActionRow(title: String, subtitle: String = "", icon: ImageVector=DriveIcons.ChevronRight, onClick: ()->Unit) {
    Surface(onClick=onClick,shape=if(LocalDriveGroup.current)RectangleShape else DriveStyle.CardShape,color=MaterialTheme.colorScheme.surface){Row(Modifier.fillMaxWidth().heightIn(min=if(LocalDriveGroup.current)56.dp else 64.dp).padding(horizontal=20.dp,vertical=if(LocalDriveGroup.current)14.dp else 17.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(16.dp)){
        Icon(icon,null,tint=MaterialTheme.colorScheme.onSurface,modifier=Modifier.size(24.dp));Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(4.dp)){Text(title,style=MaterialTheme.typography.titleSmall);if(subtitle.isNotBlank())Text(subtitle,style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)};Icon(DriveIcons.KeyboardArrowRight,null,Modifier.size(20.dp),tint=MaterialTheme.colorScheme.onSurfaceVariant)
    }}
}

@Composable
internal fun DriveNavigationBar(tab: NativeTab, onSelect: (NativeTab) -> Unit, failedTransfers: Boolean = false) {
    NavigationBar(containerColor=MaterialTheme.colorScheme.background,tonalElevation=0.dp) {
        navigationTabs.forEach { page -> NavigationBarItem(selected=tab==page,onClick={onSelect(page)},icon={
            BadgedBox(badge={if(page==NativeTab.HOME && failedTransfers) Badge()}){Icon(page.icon,null)}
        },label={Text(page.title,style=MaterialTheme.typography.labelSmall)},colors=NavigationBarItemDefaults.colors(
            selectedIconColor=MaterialTheme.colorScheme.onSurface,selectedTextColor=MaterialTheme.colorScheme.onSurface,
            indicatorColor=MaterialTheme.colorScheme.surface,unselectedIconColor=MaterialTheme.colorScheme.onSurfaceVariant,
            unselectedTextColor=MaterialTheme.colorScheme.onSurfaceVariant)) }
    }
}
