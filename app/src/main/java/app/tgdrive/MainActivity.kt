package app.tgdrive

import android.Manifest
import android.app.KeyguardManager
import android.content.ClipData
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.view.WindowManager
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.SystemBarStyle
import androidx.activity.result.contract.ActivityResultContracts
import androidx.biometric.BiometricPrompt
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.tgdrive.library.*
import app.tgdrive.data.FileMetadata
import app.tgdrive.data.formatBytes
import app.tgdrive.ui.*
import kotlinx.coroutines.*
import java.io.File

@kotlinx.serialization.Serializable private data class PendingTarget(val profile:String="legacy",val folder:String="",val vault:String="",val replacement:String="")
@kotlinx.serialization.Serializable private data class SaveTarget(val profile:String="legacy",val kind:String="file",val ids:List<String> = emptyList())

class MainActivity : FragmentActivity() {
    private val app get()=application as DriveApp
    private val preparation by lazy{androidx.lifecycle.ViewModelProvider(this)[PreparationModel::class.java]}
    private var reconnectRuleId:String?=null
    private var notificationRoute by mutableStateOf("")
    private var target=PendingTarget()
    private var saveTarget=SaveTarget()
    private var pickedTree by mutableStateOf<Uri?>(null)
    private var scanImages by mutableStateOf<List<Uri>>(emptyList())
    private var scannerOpen by mutableStateOf(false)
    private var cameraUri:Uri?=null
    private var cleanupIds by mutableStateOf<List<String>>(emptyList())
    private var locked by mutableStateOf(false)
    private var pendingShared by mutableStateOf<List<Uri>>(emptyList())
    private var sharedFolder by mutableStateOf("")
    private var sharedFolderPicker by mutableStateOf(false)
    private var unlockThen:(()->Unit)?=null
    private var lockJob:Job?=null
    private var backgroundAt=0L
    private val notices=registerForActivityResult(ActivityResultContracts.RequestPermission()){}
    private val uploads=registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()){uris->if(uris.isNotEmpty())enqueueInputs(uris,target.copy())}
    private val scanPicker=registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()){uris->if(uris.isNotEmpty()){uris.forEach{runCatching{contentResolver.takePersistableUriPermission(it,Intent.FLAG_GRANT_READ_URI_PERMISSION)}};scanImages=uris;scannerOpen=true}}
    private val capture=registerForActivityResult(ActivityResultContracts.TakePicture()){ok->if(ok&&cameraUri!=null){scanImages=scanImages+cameraUri!!;scannerOpen=true}}
    private val tree=registerForActivityResult(ActivityResultContracts.OpenDocumentTree()){uri->
        if(uri!=null){runCatching{contentResolver.takePersistableUriPermission(uri,Intent.FLAG_GRANT_READ_URI_PERMISSION)}.onFailure{app.sessions.bundle(target.profile).library.report(it)};val reconnect=reconnectRuleId
            if(reconnect!=null){reconnectRuleId=null;val b=app.sessions.bundle(target.profile);b.actions.run{b.library.reconnectRule(reconnect,uri);b.library.notice("Folder access restored.")}}else pickedTree=uri}
        else reconnectRuleId=null
    }
    private val save=registerForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")){uri->if(uri!=null)saveResult(uri,saveTarget.copy())}
    private val indexInput=registerForActivityResult(ActivityResultContracts.OpenDocument()){uri->if(uri!=null){val b=app.sessions.bundle(target.profile);b.actions.run{b.library.importIndex(uri);startQueue(b.profile.id)}}}
    private val deletion=registerForActivityResult(ActivityResultContracts.StartIntentSenderForResult()){result->app.sessions.active().library.notice(if(result.resultCode==RESULT_OK)"Android confirmed deletion of selected local copies. Cloud content was not deleted." else "Local deletion was cancelled.")}
    private val deviceCredential=registerForActivityResult(ActivityResultContracts.StartActivityForResult()){result->if(result.resultCode==RESULT_OK)unlocked() else app.sessions.active().library.notice("Device authentication was not completed.")}

    override fun onCreate(savedInstanceState:Bundle?){
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(statusBarStyle=SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle=SystemBarStyle.dark(android.graphics.Color.TRANSPARENT))
        target=runCatching{savedInstanceState?.getString("target")?.model<PendingTarget>()}.getOrNull()?:PendingTarget(app.sessions.activeId.value)
        saveTarget=runCatching{savedInstanceState?.getString("save")?.model<SaveTarget>()}.getOrNull()?:SaveTarget(app.sessions.activeId.value)
        cameraUri=savedInstanceState?.getString("camera")?.let(Uri::parse)
        pickedTree=savedInstanceState?.getString("pickedTree")?.let(Uri::parse)
        reconnectRuleId=savedInstanceState?.getString("reconnectRule")
        scannerOpen=savedInstanceState?.getBoolean("scannerOpen")?:false
        cleanupIds=savedInstanceState?.getStringArrayList("cleanupIds")?:emptyList()
        scanImages=savedInstanceState?.getStringArrayList("scanImages")?.map(Uri::parse)?:emptyList()
        notificationRoute=savedInstanceState?.getString("notificationRoute").orEmpty()
        handleNotification(intent)
        pendingShared=savedInstanceState?.getStringArrayList("shared")?.map(Uri::parse)?:emptyList()
        setContent{
            val active by app.sessions.activeId.collectAsStateWithLifecycle()
            val bundle=remember(active){app.sessions.bundle(active)}
            val state by bundle.library.state.collectAsStateWithLifecycle()
            LaunchedEffect(active){sharedFolder="";locked=app.sessions.lockEnabled(active)&&!app.authorized(active);if(!locked&&app.sessions.lockEnabled(active))app.authorize(active)}
            LaunchedEffect(state.preferences.secureScreen,state.preferences.lockEnabled,state.unlockedVaultIds){
                if(state.preferences.secureScreen||state.preferences.lockEnabled||state.unlockedVaultIds.isNotEmpty())window.addFlags(WindowManager.LayoutParams.FLAG_SECURE) else window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
            }
            val system=remember(active){SystemActions(
                upload={folder,vault,replacement->target=PendingTarget(active,folder,vault,replacement);uploads.launch(arrayOf("*/*"))},
                save={item->saveTarget=SaveTarget(active,"file",listOf(item.id));save.launch(item.name)},
                exportZip={ids->saveTarget=SaveTarget(active,"zip",ids);save.launch("TG-Drive-export.zip")},
                exportIndex={saveTarget=SaveTarget(active,"index");save.launch("TG-Drive-index.tgindex")},
                importIndex={target=PendingTarget(active);indexInput.launch(arrayOf("application/json","application/octet-stream","*/*"))},
                backupFolder={folder,vault->target=PendingTarget(active,folder,vault);tree.launch(null)},
                scan={folder,vault->target=PendingTarget(active,folder,vault);scanImages=emptyList();scannerOpen=true},
                shareExternal={item->exportToApp(bundle,item,true)},openExternal={item->exportToApp(bundle,item,false)},
                cleanup={ids->cleanupIds=ids},
                play={item->play(bundle,item)},
                setAppLock={enabled->changeAppLock(bundle,enabled)},
                startTransfers={startQueue(active)},
                scanBackups={rule,force->startQueue(active,rule,force,true)},
                transferSettings={notificationRoute="transferSettings"},vaultSettings={notificationRoute="vaultSettings"},
                reconnectBackup={id->target=PendingTarget(active);reconnectRuleId=id;tree.launch(bundle.library.state.value.rules.firstOrNull{it.id==id}?.treeUri?.let(Uri::parse))},
            )}
            DriveTheme{
                if(locked)Surface(Modifier.fillMaxSize()){Column(Modifier.fillMaxSize().padding(32.dp),verticalArrangement=Arrangement.Center,horizontalAlignment=Alignment.CenterHorizontally){
                    Text("Your space is locked",style=MaterialTheme.typography.headlineMedium);Spacer(Modifier.height(14.dp));Text("Use your device credential or biometrics to continue.");Spacer(Modifier.height(22.dp));DriveButton(onClick={authenticate()}){Text("Unlock TG Drive")}
                }}else key(active){NativeApp(bundle,app.sessions,system,notificationRoute,{notificationRoute=""})}
                if(!locked){
                    LaunchedEffect(preparation.export,active){preparation.export?.takeIf{it.profile==active}?.let{result->
                        preparation.export=null
                        val command=Intent(if(result.share)Intent.ACTION_SEND else Intent.ACTION_VIEW).apply{
                            if(result.share){type=result.mime;putExtra(Intent.EXTRA_STREAM,result.uri)}else setDataAndType(result.uri,result.mime)
                            clipData=ClipData.newRawUri(result.name,result.uri);addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        }
                        runCatching{startActivity(Intent.createChooser(command,if(result.share)"Share a copy"else"Open file"))}.onFailure{bundle.library.notice("No compatible application is installed.")}
                    }}
                    preparation.message?.let{message->AlertDialog(onDismissRequest={},title={Text("Preparing files")},text={Column{Text(message);LinearProgressIndicator(Modifier.fillMaxWidth())}},confirmButton={TextButton({preparation.job?.cancel()}){Text("Cancel")}})}
                    preparation.error?.let{message->AlertDialog(onDismissRequest={preparation.error=null},title={Text("File preparation")},text={Text(message,Modifier.heightIn(max=400.dp).verticalScroll(androidx.compose.foundation.rememberScrollState()))},confirmButton={TextButton({preparation.error=null}){Text("Done")}})}
                    pickedTree?.let{uri->BackupWizard(uri,bundle)}
                    if(scannerOpen)ScannerWizard(bundle)
                    if(cleanupIds.isNotEmpty())CleanupWizard(state.items.filter{it.id in cleanupIds},bundle)
                    if(pendingShared.isNotEmpty()&&state.loaded)AlertDialog(onDismissRequest={pendingShared=emptyList()},title={Text("Save shared files?")},text={Column{
                        Text("${pendingShared.size} files will be added to ${folderPath(sharedFolder,state.folders)}. No upload starts until you approve.")
                        TextButton(onClick={sharedFolderPicker=true}){Text("Choose destination")}
                    }},confirmButton={TextButton(onClick={val rows=pendingShared;pendingShared=emptyList();enqueueInputs(rows,PendingTarget(active,sharedFolder,state.folderVaults[sharedFolder].orEmpty()))}){Text("Add to TG Drive")}},dismissButton={TextButton(onClick={pendingShared=emptyList()}){Text("Cancel")}})
                    if(sharedFolderPicker)FolderPicker(state,sharedFolder,{sharedFolderPicker=false}){id->sharedFolder=id;sharedFolderPicker=false}
                }
            }
        }
        handleShare(intent)
    }
    override fun onNewIntent(intent:Intent){super.onNewIntent(intent);setIntent(intent);handleNotification(intent);handleShare(intent)}
    override fun onSaveInstanceState(out:Bundle){out.putString("target",target.json());out.putString("save",saveTarget.json());out.putStringArrayList("shared",ArrayList(pendingShared.map{it.toString()}));cameraUri?.let{out.putString("camera",it.toString())};out.putString("pickedTree",pickedTree?.toString());out.putString("reconnectRule",reconnectRuleId);out.putBoolean("scannerOpen",scannerOpen);out.putStringArrayList("scanImages",ArrayList(scanImages.map{it.toString()}));out.putString("notificationRoute",notificationRoute);out.putStringArrayList("cleanupIds",ArrayList(cleanupIds));super.onSaveInstanceState(out)}
    override fun onStart(){super.onStart();lockJob?.cancel();val id=app.sessions.activeId.value;if(app.sessions.lockEnabled(id)){if(!app.authorized(id))locked=true else app.authorize(id)}}
    override fun onStop(){
        super.onStop();if(isChangingConfigurations)return
        backgroundAt=SystemClock.elapsedRealtime();app.backgrounded()
        val bundle=app.sessions.active()
        lockJob=bundle.scope.launch{delay(30_000);if(bundle.library.state.value.unlockedVaultIds.isNotEmpty()){
            PlaybackService.stopProtected(this@MainActivity)
            bundle.transfers.pauseProtected();bundle.library.lockVaults()
        }}
    }
    private fun unlocked(){val id=app.sessions.activeId.value;app.authorize(id);locked=false;unlockThen?.invoke();unlockThen=null}
    private fun authenticate(){
        val manager=getSystemService(KeyguardManager::class.java)
        if(!manager.isDeviceSecure){app.sessions.active().library.notice("Configure a secure screen lock in Android settings first.");return}
        if(Build.VERSION.SDK_INT<30){deviceCredential.launch(manager.createConfirmDeviceCredentialIntent("Unlock TG Drive","Confirm your device screen lock."));return}
        val prompt=BiometricPrompt(this,ContextCompat.getMainExecutor(this),object:BiometricPrompt.AuthenticationCallback(){
            override fun onAuthenticationSucceeded(result:BiometricPrompt.AuthenticationResult){unlocked()}
            override fun onAuthenticationError(code:Int,message:CharSequence){app.sessions.active().library.notice("Device authentication was not completed.")}
        })
        prompt.authenticate(BiometricPrompt.PromptInfo.Builder().setTitle("Unlock TG Drive")
            .setAllowedAuthenticators(androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_STRONG or androidx.biometric.BiometricManager.Authenticators.DEVICE_CREDENTIAL).build())
    }
    private fun changeAppLock(bundle:SessionBundle,enabled:Boolean){
        unlockThen={bundle.actions.run{bundle.library.updatePreferences{it.copy(lockEnabled=enabled)};app.sessions.setLockEnabled(bundle.profile.id,enabled)}}
        authenticate()
    }
    private fun startQueue(profile:String,rule:String?=null,force:Boolean=false,scan:Boolean=false){
        runOnUiThread{
            if(Build.VERSION.SDK_INT>=33)notices.launch(Manifest.permission.POST_NOTIFICATIONS)
            val command=Intent(this,TransferService::class.java).putExtra("profile",profile).putExtra("rule",rule).putExtra("force",force).setAction(if(scan)TransferService.SCAN else TransferService.PROCESS)
            runCatching{ContextCompat.startForegroundService(this,command)}.onFailure{BackgroundWork.enqueue(this,profile);app.sessions.bundle(profile).library.notice("Android deferred background work. Open Activity and retry while the app is visible.")}
        }
    }
    private fun enqueueInputs(uris:List<Uri>,destination:PendingTarget){
        val bundle=app.sessions.bundle(destination.profile)
        if(preparation.job?.isActive==true){bundle.library.notice("Wait for the current file preparation or cancel it first.");return}
        if(uris.size>1000 || destination.replacement.isNotEmpty()&&uris.size!=1){bundle.library.notice("Choose at most 1,000 files, or one file when replacing content.");return}
        preparation.message="Preparing 0 of ${uris.size} files…";preparation.error=null
        preparation.job=bundle.scope.launch(Dispatchers.IO){var accepted=0;var failures=0;val failedDetails=mutableListOf<String>()
            try{
                bundle.library.awaitReady()
                val account=bundle.library.state.value.accountId
                for((index,uri) in uris.withIndex()){
                    ensureActive();bundle.library.checkAccount(account)
                    preparation.message="Preparing ${index+1} of ${uris.size} · $accepted queued"
                    try{
                        val durable=durableInput(uri,destination.profile)
                        bundle.library.checkAccount(account)
                        bundle.transfers.enqueueUpload(listOf(durable),destination.folder,destination.vault,destination.replacement)
                        accepted++;startQueue(destination.profile)
                    }catch(e:CancellationException){throw e}catch(e:Exception){failures++;if(failedDetails.size<20){val name=runCatching{contentResolver.query(uri,arrayOf(OpenableColumns.DISPLAY_NAME),null,null,null)?.use{if(it.moveToFirst())it.getString(0)else null}}.getOrNull()?:"File ${index+1}";failedDetails+="$name: ${e.message?:"Could not read this file."}"}}
                }
                if(failures>0)preparation.error="$accepted files queued. $failures could not be prepared; select those files again to retry.\n\n"+failedDetails.joinToString("\n")+(if(failures>20)"\nAnd ${failures-20} more files."else"")
            }catch(e:CancellationException){bundle.library.notice("Preparation stopped. $accepted files already queued remain in Activity.");throw e}
            catch(e:Exception){preparation.error=e.message?:"Could not prepare files."}
            finally{preparation.message=null}
        }
    }
    private suspend fun durableInput(uri:Uri,profile:String):Uri=withContext(Dispatchers.IO){
        if(uri.authority=="$packageName.files")return@withContext uri
        if(runCatching{contentResolver.takePersistableUriPermission(uri,Intent.FLAG_GRANT_READ_URI_PERMISSION)}.isSuccess)return@withContext uri
        val display=contentResolver.query(uri,arrayOf(OpenableColumns.DISPLAY_NAME),null,null,null)?.use{if(it.moveToFirst())it.getString(0)else null}?:"shared-file"
        val name=FileMetadata.safeName(display).takeUnless{it=="."||it==".."}?:"file"
        val directory=File(filesDir,"owned-inputs/$profile/${newId()}").apply{mkdirs()};val output=File(directory,name)
        try{
            contentResolver.openInputStream(uri)?.use{input->output.outputStream().use{out->
                val limit=(filesDir.usableSpace-32*1024*1024).coerceAtLeast(0)
                val bytes=copyChecked(input,out,limit)
                check(bytes<limit||input.read()==-1){"Not enough space to retain this provider's temporary source permission."}
            }}?:error("The shared source could not be opened.")
            FileProvider.getUriForFile(this@MainActivity,"$packageName.files",output)
        }catch(e:Exception){directory.deleteRecursively();throw e}
    }
    private fun saveResult(uri:Uri,destination:SaveTarget){
        runCatching{contentResolver.takePersistableUriPermission(uri,Intent.FLAG_GRANT_WRITE_URI_PERMISSION)}
        val bundle=app.sessions.bundle(destination.profile)
        bundle.actions.run{
            when(destination.kind){
                "file"->bundle.transfers.enqueue(TaskKind.DOWNLOAD,bundle.library.item(destination.ids.single()),uri)
                "zip"->{val first=destination.ids.firstOrNull()?.let{bundle.library.item(it)}?:error("Choose files first.");bundle.transfers.enqueue(TaskKind.EXPORT,first,uri,destination.ids.json())}
                "index"->bundle.library.exportIndex(uri)
            }
            if(destination.kind!="index")startQueue(destination.profile)
        }
    }
    private fun handleShare(intent:Intent?){
        if(intent==null||intent.action !in listOf(Intent.ACTION_SEND,Intent.ACTION_SEND_MULTIPLE))return
        val uris=buildList<Uri>{
            @Suppress("DEPRECATION") when(intent.action){
                Intent.ACTION_SEND->intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)?.let{add(it)}
                Intent.ACTION_SEND_MULTIPLE->intent.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM)?.let{addAll(it)}
            }
            intent.clipData?.let{clip->for(i in 0 until clip.itemCount)clip.getItemAt(i).uri?.let{add(it)}}
        }.distinct().take(1000)
        if(uris.isNotEmpty())pendingShared=uris
        intent.action=Intent.ACTION_MAIN // Avoid submitting the same share after activity recreation.
    }
    private fun exportToApp(bundle:SessionBundle,item:Item,share:Boolean){
        if(preparation.job?.isActive==true){bundle.library.notice("Wait for file preparation or cancel it first.");return}
        preparation.message="Downloading and preparing a copy…";preparation.error=null
        val account=bundle.library.state.value.accountId
        preparation.job=bundle.scope.launch(Dispatchers.IO){
            var export:File?=null
            try{
                bundle.library.checkAccount(account)
                val file=bundle.library.cache!!.lease(item.versionId).use {
                    val source=bundle.library.cache!!.materialize(item,bundle.library.version(item.versionId),bundle.library.itemVault(item.id))
                    val output=File(cacheDir,"exports/${newId()}/${item.name}").apply{parentFile!!.mkdirs()};export=output
                    source.inputStream().use{input->output.outputStream().use{out->copyChecked(input,out){copied->preparation.message="Preparing copy · ${formatBytes(copied)}"}}};output
                }
                bundle.library.checkAccount(account);check(bundle.library.vaults.isUnlocked(bundle.library.itemVault(item.id)))
                val uri=FileProvider.getUriForFile(applicationContext,"$packageName.files",file)
                preparation.export=PreparedExport(uri,item.mime,item.name,share,bundle.profile.id)
                export=null
            }catch(e:CancellationException){throw e}
            catch(e:Exception){preparation.error=e.message?:"Could not prepare this copy."}
            finally{export?.parentFile?.deleteRecursively();preparation.message=null}
        }
    }
    private fun handleNotification(intent:Intent?){
        val profile=intent?.getStringExtra("openProfile")?:return
        if(app.sessions.profiles.value.any{it.id==profile}){app.sessions.switch(profile);notificationRoute=intent.getStringExtra("openRoute")?:"activity"}
        intent.removeExtra("openProfile");intent.removeExtra("openRoute")
    }
    private fun play(bundle:SessionBundle,item:Item){
        val command=Intent(this,PlaybackService::class.java).setAction(PlaybackService.PLAY).putExtra("profile",bundle.profile.id).putExtra("item",item.id)
        runCatching{ContextCompat.startForegroundService(this,command)}.onFailure{bundle.library.report(it)}
    }
    @Composable private fun BackupWizard(uri:Uri,bundle:SessionBundle){
        var name by androidx.compose.runtime.saveable.rememberSaveable(uri.toString()){mutableStateOf("Selected folder")};var fromToday by androidx.compose.runtime.saveable.rememberSaveable{mutableStateOf(false)};val form=rememberFormAction()
        AlertDialog(onDismissRequest={if(!form.busy)pickedTree=null},title={Text("Back up this folder")},text={Column{
            form.error?.let{Text(it,color=MaterialTheme.colorScheme.error)};if(form.busy)LinearProgressIndicator(Modifier.fillMaxWidth())
            DriveTextField(name,{name=it},label={Text("Rule name")});DriveCheckboxRow("Only files modified from today",fromToday,{fromToday=it})
            Text("This is upload-only. Background scheduling depends on Android, network rules and destination vault availability.",style=MaterialTheme.typography.bodySmall)
        }},confirmButton={TextButton(onClick={val dest=target.copy();val chosen=uri;form.run({pickedTree=null}){
            val b=app.sessions.bundle(dest.profile);b.backups.add(chosen,name,dest.folder,dest.vault,if(fromToday)now()else 0);BackgroundWork.schedule(this@MainActivity,dest.profile,b.library.state.value.preferences.scanIntervalHours);startQueue(dest.profile,scan=true)
        }},enabled=name.isNotBlank()&&!form.busy){Text("Save and scan")}},dismissButton={TextButton(onClick={pickedTree=null},enabled=!form.busy){Text("Cancel")}})
    }
    @Composable private fun ScannerWizard(bundle:SessionBundle){
        var trim by androidx.compose.runtime.saveable.rememberSaveable{mutableFloatStateOf(0f)};var rotation by androidx.compose.runtime.saveable.rememberSaveable{mutableIntStateOf(0)};var grayscale by androidx.compose.runtime.saveable.rememberSaveable{mutableStateOf(true)};var busy by remember{mutableStateOf(false)};var localError by remember{mutableStateOf<String?>(null)}
        val ui=rememberCoroutineScope()
        AlertDialog(onDismissRequest={if(!busy)scannerOpen=false},title={Text("Scan to PDF")},text={Column{
            Text("${scanImages.size} selected pages. Processing stays on this device.")
            Row{TextButton(onClick={
                val file=File(filesDir,"captures/${bundle.profile.id}/camera-${newId()}.jpg").apply{parentFile!!.mkdirs()};cameraUri=FileProvider.getUriForFile(this@MainActivity,"$packageName.files",file);capture.launch(cameraUri!!)
            },enabled=!busy){Text("Camera")};TextButton(onClick={scanPicker.launch(arrayOf("image/*"))},enabled=!busy){Text("Choose images")}}
            Text("Trim edges: ${(trim*100).toInt()}%");Slider(trim,{trim=it},valueRange=0f..0.2f,enabled=!busy)
            TextButton(onClick={rotation=(rotation+90)%360},enabled=!busy){Text("Rotate pages: $rotation°")}
            DriveCheckboxRow("Grayscale",grayscale,{grayscale=it},enabled=!busy)
            Text("Manual edge trim; automatic document-edge detection is not implied.",style=MaterialTheme.typography.bodySmall)
            localError?.let{Text(it,color=MaterialTheme.colorScheme.error)};if(busy)LinearProgressIndicator(Modifier.fillMaxWidth())
        }},confirmButton={TextButton(onClick={ui.launch{busy=true;try{
            val dest=target.copy();val b=app.sessions.bundle(dest.profile)
            val file=DocumentTools(b.library).createPdf(scanImages,trim,rotation,grayscale)
            val source=FileProvider.getUriForFile(this@MainActivity,"$packageName.files",file)
            enqueueInputs(listOf(source),dest);scannerOpen=false;scanImages=emptyList()
        }catch(e:CancellationException){throw e}catch(e:Exception){localError=e.message?:"Please try again."}finally{busy=false}}},enabled=scanImages.isNotEmpty()&&!busy){Text("Create PDF and upload")}},dismissButton={TextButton(onClick={scannerOpen=false},enabled=!busy){Text("Cancel")}})
    }
    @Composable private fun CleanupWizard(rows:List<Item>,bundle:SessionBundle){
        var selected by androidx.compose.runtime.saveable.rememberSaveable{mutableStateOf(setOf<String>())};var busy by remember{mutableStateOf(false)};var localError by remember{mutableStateOf<String?>(null)};val ui=rememberCoroutineScope()
        AlertDialog(onDismissRequest={if(!busy)cleanupIds=emptyList()},title={Text("Choose local originals")},text={Column{
            Text("Selected originals will be hashed again and their cloud references checked before deletion.")
            LazyColumn(Modifier.heightIn(max=300.dp)){items(rows,key={it.id}){item->DriveCheckboxRow(item.name,item.id in selected,{value->selected=if(value)selected+item.id else selected-item.id},enabled=!busy)}}
            localError?.let{Text(it,color=MaterialTheme.colorScheme.error)};if(busy)LinearProgressIndicator(Modifier.fillMaxWidth())
        }},confirmButton={TextButton(onClick={ui.launch{busy=true;try{
            val uris=bundle.actions.validateLocalDeletion(selected.toList())
            if(Build.VERSION.SDK_INT>=30&&uris.all{it.authority==MediaStore.AUTHORITY})deletion.launch(ActivityResultContracts.StartIntentSenderForResult().let{androidx.activity.result.IntentSenderRequest.Builder(MediaStore.createDeleteRequest(contentResolver,uris).intentSender).build()})
            else{
                // The user has explicitly confirmed these exact validated document sources.
                withContext(Dispatchers.IO){uris.forEach{uri->require(DocumentsContract.isDocumentUri(this@MainActivity,uri)){"This provider requires deletion through its own app."};check(DocumentsContract.deleteDocument(contentResolver,uri)){"Provider did not delete the selected source."}}}
                bundle.library.notice("Selected local originals deleted. Cloud copies were retained.")
            }
            cleanupIds=emptyList()
        }catch(e:CancellationException){throw e}catch(e:Exception){localError=e.message?:"Please try again."}finally{busy=false}}},enabled=selected.isNotEmpty()&&!busy){Text("Verify and confirm deletion")}},dismissButton={TextButton(onClick={cleanupIds=emptyList()},enabled=!busy){Text("Cancel")}})
    }
}
