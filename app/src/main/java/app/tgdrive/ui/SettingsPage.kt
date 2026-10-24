package app.tgdrive.ui

import androidx.compose.animation.*
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.tgdrive.*
import app.tgdrive.data.formatBytes
import app.tgdrive.library.*

@Composable fun SettingsPage(state:LibraryState,bundle:SessionBundle,pool:SessionPool,system:SystemActions){
    var page by rememberSaveable{mutableStateOf("home")};var confirmCache by remember{mutableStateOf(false)}
    var info by remember{mutableStateOf<String?>(null)};var cleanup by remember{mutableStateOf<List<Item>?>(null)}
    BackHandler(enabled=page!="home"){page="home"}
    fun prefs(update:(Preferences)->Preferences){bundle.actions.run{bundle.library.updatePreferences(update)}}
    AnimatedContent(page,transitionSpec={fadeIn(tween(180)) togetherWith fadeOut(tween(100))},label="settings-detail"){current->
        Column(Modifier.fillMaxSize()){
            if(current!="home")TextButton(onClick={page="home"}){Icon(Icons.AutoMirrored.Outlined.ArrowBack,null);Text("Settings")}
            when(current){
                "vaults"->VaultsPage(state,bundle)
                "connections"->ConnectionsPage(state,bundle)
                else->LazyColumn(Modifier.fillMaxSize(),contentPadding=PaddingValues(20.dp),verticalArrangement=Arrangement.spacedBy(12.dp)){
                    when(current){
                        "home"->{
                            item{SectionTitle("Make it yours.","Private by choice. Clear by design.")}
                            item{ActionRow("Transfers and background","Network rules, retries, parts and schedule",Icons.Outlined.SwapVert){page="transfers"}}
                            item{ActionRow("Storage and offline","${formatBytes(state.cacheBytes)} cache · ${formatBytes(state.offlineBytes)} offline",Icons.Outlined.Storage){page="storage";bundle.actions.run{bundle.library.updateStorage()}}}
                            item{ActionRow("Index and recovery","Checkpoints, export and restore",Icons.Outlined.Restore){page="index"}}
                            item{ActionRow("Vaults and privacy","${state.vaults.size} vaults · app lock and private notifications",Icons.Outlined.Lock){page="privacy"}}
                            item{ActionRow("Optional connections","WebDAV and public-copy links",Icons.Outlined.Link){page="connections"}}
                            item{ActionRow("Organize by rules","Preview changes before applying them",Icons.Outlined.AutoAwesome){page="organize"}}
                            item{ActionRow("Resolve conflicts","${state.conflicts.size} preserved concurrent edits",Icons.Outlined.Merge){page="conflicts"}}
                            item{ActionRow("Diagnostics","Availability check and a redacted local report",Icons.Outlined.HealthAndSafety){page="diagnostics"}}
                            item{ActionRow("About and update identity","TG Drive ${BuildConfig.VERSION_NAME}",Icons.Outlined.Info){page="about"}}
                        }
                        "transfers"->{
                            item{SectionTitle("Transfer preferences")}
                            item{SettingSwitch("Unmetered network only","Paused work explains what it is waiting for.",state.preferences.wifiOnly){v->prefs{it.copy(wifiOnly=v)}}}
                            item{SettingSwitch("Transfer while charging","Applies to network transfers.",state.preferences.chargingOnly){v->prefs{it.copy(chargingOnly=v)}}}
                            item{SettingSwitch("Bounded automatic retries","At most three attempts, with backoff and provider wait handling.",state.preferences.autoRetry){v->prefs{it.copy(autoRetry=v)}}}
                            item{SettingSwitch("Detect identical content","Compare file contents, not just names.",state.preferences.detectDuplicates){v->prefs{it.copy(detectDuplicates=v)}}}
                            item{SettingSwitch("Sync metadata when opening","Changes from other devices are reconciled through the journal.",state.preferences.autoSync){v->prefs{it.copy(autoSync=v)}}}
                            item{Text("Average upload pace",style=MaterialTheme.typography.titleMedium);LazyRow(horizontalArrangement=Arrangement.spacedBy(8.dp)){items(listOf(0,256,1024,4096)){rate->FilterChip(state.preferences.uploadPaceKiB==rate,{prefs{it.copy(uploadPaceKiB=rate)}},label={Text(if(rate==0)"Unrestricted"else "$rate KiB/s")})}}}
                            item{Text("Pacing waits between completed parts. A part can transfer in a faster burst; this is not an instantaneous network speed cap. It applies to queued file uploads, not playback or previews.",style=MaterialTheme.typography.bodySmall)}
                            item{Text("Part size",style=MaterialTheme.typography.titleMedium);LazyRow(horizontalArrangement=Arrangement.spacedBy(8.dp)){items(listOf(64,256,512,1024)){m->FilterChip(state.preferences.chunkMiB==m,{prefs{it.copy(chunkMiB=m)}},label={Text("$m MiB")})}}}
                            item{InfoCard("No promise to bypass Telegram limits","Part uploads retain completed pieces. The active part may restart after pause. At most 128 parts per logical file are supported. Account and API limits still apply.")}
                            item{Text("Background scan interval",style=MaterialTheme.typography.titleMedium);Row(horizontalArrangement=Arrangement.spacedBy(8.dp)){listOf(1,6,12,24).forEach{h->FilterChip(state.preferences.scanIntervalHours==h,{prefs{it.copy(scanIntervalHours=h)};BackgroundWork.schedule(bundle.library.context,bundle.profile.id,h)},label={Text("${h}h")})}}}
                        }
                        "storage"->{
                            item{SectionTitle("Space, explained.")}
                            item{InfoCard("App cache: ${formatBytes(state.cacheBytes)}","Offline copies: ${formatBytes(state.offlineBytes)}. Pending upload sources are kept separately and are not removed by cache cleaning.",Icons.Outlined.Storage)}
                            item{Button(onClick={confirmCache=true}){Text("Clear unpinned app cache")}}
                            item{Text("Cache budget",style=MaterialTheme.typography.titleMedium);LazyRow(horizontalArrangement=Arrangement.spacedBy(8.dp)){items(listOf(128,256,512,1024)){m->FilterChip(state.preferences.cacheMiB==m,{prefs{it.copy(cacheMiB=m)}},label={Text("$m MiB")})}}}
                            item{ActionRow("Inspect Telegram native cache","Separate from the app's verified offline copies",Icons.Outlined.Folder){bundle.actions.run{
                                val stats=bundle.drive.call(org.drinkless.tdlib.TdApi.GetStorageStatisticsFast()) as org.drinkless.tdlib.TdApi.StorageStatisticsFast
                                info="Telegram files: ${formatBytes(stats.filesSize)}\nTelegram database: ${formatBytes(stats.databaseSize)}\nNative cache is managed independently. Your original cloud messages are not a cache."
                            }}}
                            item{ActionRow("Free verified local originals","Review copies whose downloaded content was verified",Icons.Outlined.CleaningServices){bundle.actions.run{cleanup=bundle.actions.cleanupCandidates()}}}
                            item{Text("Trash retention",style=MaterialTheme.typography.titleMedium);Row(horizontalArrangement=Arrangement.spacedBy(8.dp)){listOf(7,30,90).forEach{d->FilterChip(state.preferences.trashDays==d,{prefs{it.copy(trashDays=d)}},label={Text("$d days")})}}}
                            item{InfoCard("Retention is not a remote timer","Expired Trash is cleaned when eligible background work runs or the app performs cleanup. Vaults may need unlocking. Independent copies of important files are still recommended.")}
                        }
                        "index"->{
                            item{SectionTitle("Recovery starts here.")}
                            item{ActionRow("Sync latest metadata",icon=Icons.Outlined.Sync){bundle.actions.run{bundle.library.sync()}}}
                            item{ActionRow("Full metadata reconciliation","Does not erase the existing local library",Icons.Outlined.ManageSearch){bundle.actions.run{bundle.library.sync(full=true)}}}
                            item{ActionRow("Save cloud index checkpoint","Includes folders, tags, versions and encrypted vault descriptors",Icons.Outlined.CloudUpload){bundle.actions.run{bundle.library.saveCloudIndex()}}}
                            item{ActionRow("Restore newest cloud checkpoint","Merge and preserve conflicts; never reset login",Icons.Outlined.Restore){bundle.actions.run{bundle.library.restoreCloudIndex()}}}
                            item{ActionRow("Export index to a file","No Telegram sessions or device-only source URIs",Icons.Outlined.SaveAlt,onClick=system.exportIndex)}
                            item{ActionRow("Import index file","Only a matching Telegram account is accepted",Icons.Outlined.FileOpen,onClick=system.importIndex)}
                            item{InfoCard("Metadata is not file content","An index backup cannot recreate deleted Telegram documents. Public metadata names remain readable in an exported index; vault bodies remain encrypted and require your separate recovery credentials.")}
                        }
                        "privacy"->{
                            item{SectionTitle("Your privacy controls")}
                            item{ActionRow("Encrypted vaults","Create, unlock or lock protected content",Icons.Outlined.EnhancedEncryption){page="vaults"}}
                            item{SettingSwitch("App lock","Use device credentials or biometrics after 30 seconds away.",state.preferences.lockEnabled,system.setAppLock)}
                            item{SettingSwitch("Hide notification details","Use a generic transfer notification.",state.preferences.hideNotifications){v->prefs{it.copy(hideNotifications=v)}}}
                            item{SettingSwitch("Protect screenshots and recents","Screenshots are also protected while vault content is open.",state.preferences.secureScreen){v->prefs{it.copy(secureScreen=v)}}}
                            item{SettingSwitch("Backup health notifications","Only if Android notification permission is granted.",state.preferences.backupNotifications){v->prefs{it.copy(backupNotifications=v)}}}
                            item{InfoCard("App lock is not cloud encryption","Files outside a vault are Telegram cloud documents. They can still be accessed from your Telegram account. Vault keys, index encryption and screen lock are separate protections.",Icons.Outlined.Shield)}
                        }
                        "organize"->{
                            item{SectionTitle("Organize with a preview")}
                            item{Button(onClick={bundle.actions.run{bundle.actions.previewOrganization(false)}}){Text("Suggest folders by file type")}}
                            item{OutlinedButton(onClick={bundle.actions.run{bundle.actions.previewOrganization(true)}}){Text("Suggest folders by added month")}}
                            item{Text("Uses local rules and added dates, not an external AI service. Originals are not uploaded again.",style=MaterialTheme.typography.bodySmall)}
                            if(state.organization.isNotEmpty())item{Button(onClick={bundle.actions.run{bundle.actions.applyOrganization()}}){Text("Apply ${state.organization.size} reviewed changes")}}
                            items(state.organization.take(500),key={it.itemId}){change->OutlinedCard(Modifier.fillMaxWidth()){Column(Modifier.padding(12.dp)){Text(change.name);Text("${folderPath(change.fromFolderId,state.folders)} → ${change.destination}",style=MaterialTheme.typography.bodySmall)}}}
                            item{TextButton(onClick={bundle.actions.run{bundle.actions.undoOrganization()}}){Text("Undo last organization batch")}}
                        }
                        "conflicts"->{
                            item{SectionTitle("Nothing is silently lost.","Concurrent metadata candidates are preserved for explicit resolution.")}
                            if(state.conflicts.isEmpty())item{InfoCard("No unresolved edits","Changes reconcile through operation identities, not a destructive reset.")}
                            items(state.conflicts,key={it.id}){conflict->OutlinedCard(Modifier.fillMaxWidth()){Column(Modifier.padding(16.dp)){
                                Text("${conflict.kept.kind.name.lowercase()} · ${conflict.entityId.takeLast(8)}")
                                Text("Kept candidate: ${conflict.kept.deviceId.take(8)}\nIncoming candidate: ${conflict.incoming.deviceId.take(8)}",style=MaterialTheme.typography.bodySmall)
                                Row{TextButton(onClick={bundle.actions.run{bundle.library.resolveConflict(conflict.id,false)}}){Text("Use kept")};TextButton(onClick={bundle.actions.run{bundle.library.resolveConflict(conflict.id,true)}}){Text("Use incoming")}}
                            }}}
                        }
                        "diagnostics"->{
                            item{SectionTitle("Understand, then repair.")}
                            item{ActionRow("Check message availability","Does not delete records for a temporary network failure",Icons.Outlined.FactCheck){bundle.actions.run{bundle.actions.checkAvailability()}}}
                            item{ActionRow("Reconcile metadata from Telegram","Keep valid local and concurrent changes",Icons.Outlined.Sync){bundle.actions.run{bundle.library.sync(full=true)}}}
                            item{SelectionContainer{Text(bundle.actions.diagnosticText(),style=MaterialTheme.typography.bodySmall)}}
                            items(state.healthProblems){p->Text(p,color=MaterialTheme.colorScheme.error,style=MaterialTheme.typography.bodySmall)}
                            item{InfoCard("Redacted by default","This report excludes login codes, API credentials, sessions, filenames and source URIs. Select and copy only the information you intend to share.")}
                        }
                        "about"->{
                            item{SectionTitle("TG Drive","${BuildConfig.VERSION_NAME} · build ${BuildConfig.VERSION_CODE}")}
                            item{InfoCard("Independent Telegram client","Your files use your own account. No claim of unlimited storage, guaranteed SMS or permanent provider availability is made.",Icons.Outlined.CloudQueue)}
                            item{SelectionContainer{Text("Signing certificate SHA-256:\n${signingFingerprint(bundle.library.context)}",style=MaterialTheme.typography.bodySmall)}}
                            item{InfoCard("Updates preserve identity","Install updates signed with the same certificate. Cache-only signing-key storage is a test distribution convenience; keep a secure long-lived key backup for production.")}
                            item{InfoCard("Encryption implementation","Vault files use Tink authenticated streaming encryption with password and separate recovery wraps. The application format and native-library provenance still need independent security review before production assurances.")}
                        }
                    }
                    item{Spacer(Modifier.height(20.dp))}
                }
            }
        }
    }
    if(confirmCache)ConfirmDialog("Clear app cache?","Verified offline copies, upload sources and Telegram messages will remain.","Clear cache",{confirmCache=false}){bundle.actions.run{bundle.actions.clearCache()};confirmCache=false}
    info?.let{text->AlertDialog(onDismissRequest={info=null},title={Text("Storage details")},text={Text(text)},confirmButton={TextButton(onClick={info=null}){Text("Done")}})}
    cleanup?.let{items->ConfirmDialog("Review local cleanup","${items.size} original copies have a verified downloaded version. Each selected source will be rechecked before Android asks you to confirm deletion. No cloud files are deleted.","Review in Android",{cleanup=null}){system.cleanup(items.map{it.id});cleanup=null}}
}

@Composable fun SettingSwitch(title:String,description:String,value:Boolean,onChange:(Boolean)->Unit){Surface(shape=MaterialTheme.shapes.medium,color=MaterialTheme.colorScheme.surface){Row(Modifier.fillMaxWidth().padding(15.dp),horizontalArrangement=Arrangement.spacedBy(12.dp)){
    Column(Modifier.weight(1f)){Text(title,style=MaterialTheme.typography.titleSmall);Text(description,style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)};Switch(value,onChange)
}}}

fun signingFingerprint(context:android.content.Context):String=runCatching{
    val info=context.packageManager.getPackageInfo(context.packageName,if(android.os.Build.VERSION.SDK_INT>=28)android.content.pm.PackageManager.GET_SIGNING_CERTIFICATES else android.content.pm.PackageManager.GET_SIGNATURES)
    val cert=if(android.os.Build.VERSION.SDK_INT>=28)info.signingInfo?.apkContentsSigners?.firstOrNull() else @Suppress("DEPRECATION") info.signatures?.firstOrNull()
    java.security.MessageDigest.getInstance("SHA-256").digest(cert!!.toByteArray()).joinToString(":"){"%02X".format(it)}
}.getOrDefault("Unavailable")
