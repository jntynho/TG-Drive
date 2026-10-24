package app.tgdrive.ui

import androidx.compose.animation.*
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.tween
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
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
)
enum class NativeTab(val title: String, val icon: ImageVector) {
    FILES("Files",Icons.Outlined.Folder), TRANSFERS("Activity",Icons.Outlined.SwapVert), BACKUPS("Backups",Icons.Outlined.Backup), SETTINGS("Settings",Icons.Outlined.Tune)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NativeApp(bundle: SessionBundle, pool: SessionPool, system: SystemActions) {
    val auth by bundle.drive.state.collectAsStateWithLifecycle()
    val state by bundle.library.state.collectAsStateWithLifecycle()
    val profiles by pool.profiles.collectAsStateWithLifecycle()
    var tab by rememberSaveable(bundle.profile.id) { mutableStateOf(NativeTab.FILES) }
    var accountSheet by remember { mutableStateOf(false) }
    var previewId by rememberSaveable(bundle.profile.id) { mutableStateOf("") }
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(state.notice,state.error,auth.error) {
        val message = state.error ?: state.notice ?: auth.error
        if(message!=null){snackbar.showSnackbar(message);if(state.loaded)bundle.library.clearNotice();if(state.loaded||auth.login!=LoginStep.READY)bundle.drive.clearNotice()}
    }
    val preview = state.items.firstOrNull { it.id == previewId }
    BackHandler(enabled=preview!=null){previewId=""}
    LaunchedEffect(state.items,previewId) { if(previewId.isNotEmpty()&&preview==null)previewId="" }
    AnimatedContent(targetState=preview,transitionSpec={ (fadeIn(tween(180))+slideInHorizontally(tween(180)){it/12}) togetherWith fadeOut(tween(130)) },label="file-preview") { item ->
        if(item!=null) {
            NativePreview(item,bundle,system,onBack={previewId=""})
        }else{
            Scaffold(snackbarHost={SnackbarHost(snackbar)}, topBar={
                TopAppBar(title={Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(10.dp)){
                    Icon(Icons.Outlined.CloudQueue,null,tint=MaterialTheme.colorScheme.primary)
                    Text("TG Drive",fontWeight=FontWeight.SemiBold)
                }},actions={
                    if(auth.login==LoginStep.READY)IconButton(onClick={bundle.actions.run{bundle.library.sync()}}){Icon(Icons.Outlined.Refresh,"Sync metadata")}
                    TextButton(onClick={accountSheet=true}){Text(if(state.accountName.isBlank())"Account" else state.accountName.take(16));Icon(Icons.Outlined.ExpandMore,null,Modifier.size(18.dp))}
                },colors=TopAppBarDefaults.topAppBarColors(containerColor=MaterialTheme.colorScheme.background))
            },bottomBar={if(auth.login==LoginStep.READY&&state.loaded)NavigationBar{
                NativeTab.entries.forEach { page -> NavigationBarItem(selected=tab==page,onClick={tab=page},icon={
                    BadgedBox(badge={if(page==NativeTab.TRANSFERS&&state.tasks.any{it.state==TaskState.FAILED})Badge()}){Icon(page.icon,page.title)}
                },label={Text(page.title)}) }
            }}){padding->
                when {
                    auth.login!=LoginStep.READY->AuthPanel(auth,bundle.drive::configure,bundle.drive::login,bundle.drive::reconnect,bundle.drive::editConfiguration,Modifier.padding(padding))
                    !state.loaded->Column(Modifier.fillMaxSize().padding(padding).padding(24.dp),verticalArrangement=Arrangement.Center,horizontalAlignment=Alignment.CenterHorizontally){
                        val openError=state.error?:auth.error
                        if(openError==null){CircularProgressIndicator();Spacer(Modifier.height(18.dp));Text("Opening your private library…")}else{Text(openError,color=MaterialTheme.colorScheme.error);Text("Your Telegram files have not been deleted.");Button(onClick={bundle.library.retryInitialization()}){Text("Retry opening library")}}
                    }
                    else->AnimatedContent(tab,Modifier.padding(padding),transitionSpec={fadeIn(tween(180)) togetherWith fadeOut(tween(110))},label="navigation") { page ->
                        when(page){
                            NativeTab.FILES->FilesPage(state,bundle,system,onPreview={previewId=it})
                            NativeTab.TRANSFERS->TransfersPage(state,bundle,system)
                            NativeTab.BACKUPS->BackupsPage(state,bundle,system)
                            NativeTab.SETTINGS->SettingsPage(state,bundle,pool,system)
                        }
                    }
                }
            }
        }
    }
    if(accountSheet)ModalBottomSheet(onDismissRequest={accountSheet=false}){
        Column(Modifier.fillMaxWidth().padding(24.dp),verticalArrangement=Arrangement.spacedBy(12.dp)){
            Text("Your accounts",style=MaterialTheme.typography.headlineSmall)
            profiles.forEach { profile->OutlinedCard(onClick={pool.switch(profile.id);accountSheet=false},modifier=Modifier.fillMaxWidth()){
                Row(Modifier.fillMaxWidth().padding(16.dp),verticalAlignment=Alignment.CenterVertically){Icon(Icons.Outlined.AccountCircle,null);Spacer(Modifier.width(12.dp));Text(profile.label,Modifier.weight(1f));if(profile.id==bundle.profile.id)Icon(Icons.Outlined.Check,null,tint=MaterialTheme.colorScheme.primary)}
            }}
            TextButton(onClick={runCatching{pool.add()}.onFailure{bundle.library.report(it)};accountSheet=false}){Icon(Icons.Outlined.PersonAdd,null);Spacer(Modifier.width(8.dp));Text("Add Telegram account")}
            if(auth.login==LoginStep.READY)TextButton(onClick={bundle.actions.run{
                require(state.tasks.none{it.state in setOf(TaskState.PREPARING,TaskState.TRANSFERRING)}){"Pause transfers before signing out."}
                bundle.library.context.stopService(android.content.Intent(bundle.library.context,PlaybackService::class.java))
                bundle.drive.logout()
            };accountSheet=false}){Text("Sign out of this account",color=MaterialTheme.colorScheme.error)}
            Text("Each profile has a separate Telegram session. Switching accounts does not move or share your files.",style=MaterialTheme.typography.bodySmall)
            Spacer(Modifier.height(12.dp))
        }
    }
    state.lastShare?.let { result->AlertDialog(onDismissRequest=bundle.library::clearNotice,title={Text("Public copy created")},text={Column{
        androidx.compose.foundation.text.selection.SelectionContainer { Text(result.url) };Text("This link grants access to an exported copy. Revoking cannot recall downloaded copies.",style=MaterialTheme.typography.bodySmall)
    }},confirmButton={TextButton(onClick=bundle.library::clearNotice){Text("Done")}}) }
}

@Composable fun SectionTitle(title: String, subtitle: String? = null) {
    Column(verticalArrangement=Arrangement.spacedBy(5.dp)){Text(title,style=MaterialTheme.typography.headlineMedium,fontWeight=FontWeight.SemiBold);if(subtitle!=null)Text(subtitle,style=MaterialTheme.typography.bodyMedium,color=MaterialTheme.colorScheme.onSurfaceVariant)}
}
@Composable fun InfoCard(title: String, text: String, icon: ImageVector = Icons.Outlined.Info) {
    Card(shape=RoundedCornerShape(18.dp),colors=CardDefaults.cardColors(containerColor=MaterialTheme.colorScheme.surfaceContainerLow)){
        Row(Modifier.fillMaxWidth().padding(18.dp),horizontalArrangement=Arrangement.spacedBy(12.dp)) {Icon(icon,null,tint=MaterialTheme.colorScheme.primary);Column{Text(title,fontWeight=FontWeight.SemiBold);Text(text,style=MaterialTheme.typography.bodySmall)}}
    }
}
@Composable fun ActionRow(title: String, subtitle: String = "", icon: ImageVector=Icons.Outlined.ChevronRight, onClick: ()->Unit) {
    Surface(onClick=onClick,shape=RoundedCornerShape(14.dp),color=MaterialTheme.colorScheme.surface){Row(Modifier.fillMaxWidth().padding(16.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(12.dp)){
        Icon(icon,null,tint=MaterialTheme.colorScheme.primary);Column(Modifier.weight(1f)){Text(title,style=MaterialTheme.typography.titleSmall);if(subtitle.isNotBlank())Text(subtitle,style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)};Icon(Icons.AutoMirrored.Outlined.ArrowForward,null,Modifier.size(18.dp))
    }}
}
