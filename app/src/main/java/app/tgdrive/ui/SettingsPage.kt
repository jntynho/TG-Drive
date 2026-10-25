package app.tgdrive.ui

import androidx.compose.animation.*
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.semantics.Role
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.relocation.*
import kotlinx.coroutines.delay
import androidx.compose.ui.unit.dp
import app.tgdrive.*
import app.tgdrive.data.formatBytes
import app.tgdrive.library.*

@Composable fun SettingsPage(state:LibraryState,bundle:SessionBundle,pool:SessionPool,system:SystemActions,onClose:()->Unit={},page:String,onPage:(String)->Unit,onAccounts:()->Unit={},query:String="",search:HeaderSearch?=null){
    var confirmCache by rememberSaveable{mutableStateOf(false)}
    var info by remember{mutableStateOf<String?>(null)};var cleanup by remember{mutableStateOf<List<Item>?>(null)}
    BackHandler { if(page!="home")onPage(settingsParent(page)) else onClose() }
    val editor = remember(bundle, state.accountId) { PreferenceEditor(state.preferences, bundle.library.scope,
        read={bundle.library.state.value.preferences}, save={update->
            bundle.library.checkAccount(state.accountId)
            val previous=bundle.library.state.value.preferences.scanIntervalHours
            bundle.library.updatePreferences(update)
            val interval=bundle.library.state.value.preferences.scanIntervalHours
            if(interval!=previous) BackgroundWork.schedule(bundle.library.context,bundle.profile.id,interval)
        }, report={bundle.library.report(it)}) }
    LaunchedEffect(state.preferences) { editor.observe(state.preferences) }
    fun prefs(update:(Preferences)->Preferences) { editor.update(update) }
    AnimatedContent(page,transitionSpec={horizontalNavigation(settingsForward(initialState,targetState))},label="settings-detail"){destination->
        val current=destination.substringBefore("#");val focus=destination.substringAfter("#","")
        val listState=rememberLazyListState()
        val indices=when(current){"transfers"->mapOf("Average upload pace" to 1,"Part size" to 3,"Background scan interval" to 5);"storage"->mapOf("Cache budget" to 2,"Free verified local originals" to 4,"Trash retention" to 5);"index"->mapOf("Export library information" to 1,"Import library information" to 2);"privacy"->mapOf("App lock" to 1,"Hide notification details" to 2,"Protect screenshots and recents" to 2,"Backup health notifications" to 2);else->emptyMap()}
        LaunchedEffect(destination){if(focus.isNotEmpty()){delay(320);listState.animateScrollToItem(indices[focus]?:0)}}
        CompositionLocalProvider(LocalSettingFocus provides focus){
        Column(Modifier.fillMaxSize()){

            when(current){
                "home"->{search?.let{HeaderSearchField(it)};SettingsHome(state,query){destination->if(destination=="accounts")onAccounts()else onPage(destination);if(destination=="storage")bundle.actions.run{bundle.library.updateStorage()}}}
                "vaults"->VaultsPage(state,bundle)
                else->LazyColumn(Modifier.fillMaxSize(),state=listState,contentPadding=PaddingValues(start=16.dp,end=16.dp,top=16.dp,bottom=32.dp),verticalArrangement=Arrangement.spacedBy(12.dp)){
                    when(current){
                        "transfers"->{
                            item{DriveGroup{
                                SettingSwitch("Unmetered network only","Paused work explains what it is waiting for.",editor.value.wifiOnly){v->prefs{it.copy(wifiOnly=v)}}
                                DriveDivider()
                                SettingSwitch("Transfer while charging","Applies to network transfers.",editor.value.chargingOnly){v->prefs{it.copy(chargingOnly=v)}}
                                DriveDivider()
                                SettingSwitch("Bounded automatic retries","At most three attempts, with backoff and provider wait handling.",editor.value.autoRetry){v->prefs{it.copy(autoRetry=v)}}
                                DriveDivider()
                                SettingSwitch("Detect identical content","Compare file contents, not just names.",editor.value.detectDuplicates){v->prefs{it.copy(detectDuplicates=v)}}
                                DriveDivider()
                                SettingSwitch("Sync metadata when opening","Changes from other devices are reconciled through the journal.",editor.value.autoSync){v->prefs{it.copy(autoSync=v)}}
                            }}
                            item{Text("Average upload pace",style=MaterialTheme.typography.titleMedium);LazyRow(horizontalArrangement=Arrangement.spacedBy(8.dp)){items(listOf(0,256,1024,4096)){rate->DriveFilterChip(editor.value.uploadPaceKiB==rate,{prefs{it.copy(uploadPaceKiB=rate)}},label={Text(if(rate==0)"Unrestricted"else "$rate KiB/s")})}}}
                            item{Text("Pacing waits between completed parts. A part can transfer in a faster burst; this is not an instantaneous network speed cap. It applies to queued file uploads, not playback or previews.",style=MaterialTheme.typography.bodySmall)}
                            item{Text("Part size",style=MaterialTheme.typography.titleMedium);LazyRow(horizontalArrangement=Arrangement.spacedBy(8.dp)){items(listOf(64,256,512,1024)){m->DriveFilterChip(editor.value.chunkMiB==m,{prefs{it.copy(chunkMiB=m)}},label={Text("$m MiB")})}}}
                            item{InfoCard("No promise to bypass Telegram limits","Part uploads retain completed pieces. The active part may restart after pause. At most 128 parts per logical file are supported. Account and API limits still apply.")}
                            item{Text("Background scan interval",style=MaterialTheme.typography.titleMedium);LazyRow(horizontalArrangement=Arrangement.spacedBy(8.dp)){items(listOf(1,6,12,24)){h->DriveFilterChip(editor.value.scanIntervalHours==h,{prefs{it.copy(scanIntervalHours=h)}},label={Text("${h}h")})}}}
                        }
                        "storage"->{
                            item{InfoCard("App cache: ${formatBytes(state.cacheBytes)}","Offline copies: ${formatBytes(state.offlineBytes)}. Retained source copies: ${formatBytes(state.retainedSourceBytes)}. Upload preparation: ${formatBytes(state.stagingBytes)}. Shared copies: ${formatBytes(state.exportBytes)}. Referenced sources are preserved; unused temporary and shared copies expire after one day.",DriveIcons.Storage)}
                            item{DriveButton(onClick={confirmCache=true}){Text("Clear unpinned app cache")}}
                            item{Text("Cache budget",style=MaterialTheme.typography.titleMedium);LazyRow(horizontalArrangement=Arrangement.spacedBy(8.dp)){items(listOf(128,256,512,1024)){m->DriveFilterChip(editor.value.cacheMiB==m,{prefs{it.copy(cacheMiB=m)}},label={Text("$m MiB")})}}}
                            item{ActionRow("Inspect Telegram native cache","Separate from the app's verified offline copies",DriveIcons.Folder){bundle.actions.run{
                                val stats=bundle.drive.call(org.drinkless.tdlib.TdApi.GetStorageStatisticsFast()) as org.drinkless.tdlib.TdApi.StorageStatisticsFast
                                info="Telegram files: ${formatBytes(stats.filesSize)}\nTelegram database: ${formatBytes(stats.databaseSize)}\nNative cache is managed independently. Your original cloud messages are not a cache."
                            }}}
                            item{ActionRow("Free verified local originals","Review copies whose downloaded content was verified",DriveIcons.CleaningServices){bundle.actions.run{cleanup=bundle.actions.cleanupCandidates()}}}
                            item{Text("Trash retention",style=MaterialTheme.typography.titleMedium);LazyRow(horizontalArrangement=Arrangement.spacedBy(8.dp)){items(listOf(7,30,90)){d->DriveFilterChip(editor.value.trashDays==d,{prefs{it.copy(trashDays=d)}},label={Text("$d days")})}}}
                            item{InfoCard("Retention is not a remote timer","Expired Trash is cleaned when eligible background work runs or the app performs cleanup. Vaults may need unlocking. Independent copies of important files are still recommended.")}
                        }
                        "index"->{
                            item{DriveGroup{
                                ActionRow("Sync library",icon=DriveIcons.Sync){bundle.actions.run{bundle.library.sync()}}
                                DriveDivider()
                                ActionRow("Refresh entire library","Does not erase the existing local library",DriveIcons.ManageSearch){bundle.actions.run{bundle.library.sync(full=true)}}
                                DriveDivider()
                                ActionRow("Back up library information","Includes folders, tags, versions and encrypted vault descriptors",DriveIcons.CloudUpload){bundle.actions.run{bundle.library.saveCloudIndex()}}
                                DriveDivider()
                                ActionRow("Restore library information","Merge and preserve conflicts; never reset login",DriveIcons.Restore){bundle.actions.run{bundle.library.restoreCloudIndex()}}
                            }}
                            item{ActionRow("Export library information","No Telegram sessions or device-only source URIs",DriveIcons.SaveAlt,onClick=system.exportIndex)}
                            item{ActionRow("Import library information","Only a matching Telegram account is accepted",DriveIcons.FileOpen,onClick=system.importIndex)}
                            item{InfoCard("Metadata is not file content","An index backup cannot recreate deleted Telegram documents. Public metadata names remain readable in an exported index; vault bodies remain encrypted and require your separate recovery credentials.")}
                        }
                        "privacy"->{
                            item{ActionRow("Encrypted vaults","Create, unlock or lock protected content",DriveIcons.EnhancedEncryption){onPage("vaults")}}
                            item{SettingSwitch("App lock","Use device credentials or biometrics after 30 seconds away.",state.preferences.lockEnabled,system.setAppLock)}
                            item{DriveGroup{
                                SettingSwitch("Hide notification details","Use a generic transfer notification.",editor.value.hideNotifications){v->prefs{it.copy(hideNotifications=v)}}
                                DriveDivider()
                                SettingSwitch("Protect screenshots and recents","Screenshots are also protected while vault content is open.",editor.value.secureScreen){v->prefs{it.copy(secureScreen=v)}}
                                DriveDivider()
                                SettingSwitch("Backup health notifications","Only if Android notification permission is granted.",editor.value.backupNotifications){v->prefs{it.copy(backupNotifications=v)}}
                            }}
                            item{InfoCard("App lock is not cloud encryption","Files outside a vault are Telegram cloud documents. They can still be accessed from your Telegram account. Vault keys, index encryption and screen lock are separate protections.",DriveIcons.Shield)}
                        }
                        "diagnostics"->{
                            item{DriveGroup{
                                ActionRow("Check message availability","Does not delete records for a temporary network failure",DriveIcons.FactCheck){bundle.actions.run{bundle.actions.checkAvailability()}}
                                DriveDivider()
                                ActionRow("Refresh library from Telegram","Keep valid local and concurrent changes",DriveIcons.Sync){bundle.actions.run{bundle.library.sync(full=true)}}
                            }}
                            item{SelectionContainer{Text(bundle.actions.diagnosticText(),style=MaterialTheme.typography.bodySmall)}}
                            items(state.healthProblems){p->Text(p,color=MaterialTheme.colorScheme.error,style=MaterialTheme.typography.bodySmall)}
                            item{InfoCard("Redacted by default","This report excludes login codes, API credentials, sessions, filenames and source URIs. Select and copy only the information you intend to share.")}
                        }
                        "about"->{
                            item{SectionTitle("TG Drive","${BuildConfig.VERSION_NAME} · build ${BuildConfig.VERSION_CODE}")}
                            item{InfoCard("Independent Telegram client","Your files use your own account. No claim of unlimited storage, guaranteed SMS or permanent provider availability is made.",DriveIcons.CloudQueue)}
                            item{SelectionContainer{Text("Signing certificate SHA-256:\n${signingFingerprint(bundle.library.context)}",style=MaterialTheme.typography.bodySmall)}}
                            item{InfoCard("Updates preserve identity","Install updates signed with the same certificate. Keep your existing app installed when updating to preserve its local settings.")}
                            item{InfoCard("Encryption implementation","Vault files use Tink authenticated streaming encryption with password and separate recovery wraps. The application format and native-library provenance still need independent security review before production assurances.")}
                        }
                    }
                    item{Spacer(Modifier.height(20.dp))}
                }
            }
        }
    }
    }
    val form=rememberFormAction()
    if(confirmCache)ConfirmDialog("Clear app cache?","Verified offline copies, upload sources and Telegram messages will remain.","Clear cache",{confirmCache=false},busy=form.busy,error=form.error){form.run({confirmCache=false}){bundle.actions.clearCache()}}
    info?.let{text->AlertDialog(onDismissRequest={info=null},title={Text("Storage details")},text={Text(text,Modifier.verticalScroll(rememberScrollState()))},confirmButton={TextButton(onClick={info=null}){Text("Done")}})}
    cleanup?.let{items->ConfirmDialog("Review local cleanup","${items.size} original copies have a verified downloaded version. Each selected source will be rechecked before Android asks you to confirm deletion. No cloud files are deleted.","Review in Android",{cleanup=null}){system.cleanup(items.map{it.id});cleanup=null}}
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable fun SettingSwitch(title:String,description:String,value:Boolean,onChange:(Boolean)->Unit){
    val request=remember{BringIntoViewRequester()}
    val focus=LocalSettingFocus.current
    var highlighted by remember(focus){mutableStateOf(false)}
    LaunchedEffect(focus){if(focus==title){highlighted=true;delay(500);request.bringIntoView();delay(1800);highlighted=false}}
    Surface(modifier=Modifier.bringIntoViewRequester(request),shape=if(LocalDriveGroup.current)RectangleShape else DriveStyle.CardShape,color=if(highlighted)MaterialTheme.colorScheme.primary.copy(alpha=.14f)else MaterialTheme.colorScheme.surface){
        Row(Modifier.fillMaxWidth().toggleable(value=value,role=Role.Switch,onValueChange=onChange)
            .padding(horizontal=20.dp,vertical=17.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(14.dp)){
            Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(5.dp)){
                Text(title,style=MaterialTheme.typography.titleSmall)
                Text(description,style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
            }
            DriveSwitch(value,onCheckedChange=null)
        }
    }
}

fun signingFingerprint(context:android.content.Context):String=runCatching{
    val info=context.packageManager.getPackageInfo(context.packageName,if(android.os.Build.VERSION.SDK_INT>=28)android.content.pm.PackageManager.GET_SIGNING_CERTIFICATES else android.content.pm.PackageManager.GET_SIGNATURES)
    val cert=if(android.os.Build.VERSION.SDK_INT>=28)info.signingInfo?.apkContentsSigners?.firstOrNull() else @Suppress("DEPRECATION") info.signatures?.firstOrNull()
    java.security.MessageDigest.getInstance("SHA-256").digest(cert!!.toByteArray()).joinToString(":"){"%02X".format(it)}
}.getOrDefault("Unavailable")

internal fun settingsParent(page:String) = if(page.substringBefore("#")=="vaults") "privacy" else "home"

internal fun settingsForward(from:String,to:String):Boolean {
    fun depth(route:String)=when(route.substringBefore("#")){"home"->0;"vaults"->2;else->1}
    return depth(to)>=depth(from)
}
