package app.tgdrive.library

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.BatteryManager
import android.os.Build
import android.os.PowerManager
import android.provider.OpenableColumns
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import java.util.concurrent.ConcurrentHashMap
import java.security.DigestOutputStream
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.drinkless.tdlib.TdApi
import java.io.File
import java.io.InputStream
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class TransferEngine(val repository: LibraryRepository, val scope: CoroutineScope, private val gateOverride: ((TransferTask) -> Pair<TaskState, String>?)? = null) {
    private val context get() = repository.context
    private val runLock = Mutex()
    private class ActiveTransfer {
        var job:Job?=null
        @Volatile var fileId=0
        var progressAt=0L
        var progressBytes=0L
    }
    private val activeTransfers=ConcurrentHashMap<String,ActiveTransfer>()
    private val contentLocks=Array(64){Mutex()}
    private val snapshotReservations=ConcurrentHashMap<String,Long>()
    var external: suspend (TransferTask) -> Unit = { error("This optional connection is not configured.") }
    var completed: suspend (TransferTask) -> Unit = {}
    var wake: (() -> Unit)? = null
    private val terminal = setOf(TaskState.COMPLETED, TaskState.CANCELLED)

    init {
        scope.launch {
            repository.session.events.collect { update ->
                if (update is TdApi.UpdateFile) {
                    val entry=activeTransfers.entries.firstOrNull{it.value.fileId==update.file.id}?:return@collect
                    val active=entry.value
                    val time = now()
                    if (time - active.progressAt >= 800) {
                        val file = update.file
                        val current = repository.database?.get("tasks", entry.key)?.model<TransferTask>()
                        if (current != null && current.state == TaskState.TRANSFERRING) {
                            val base = current.parts.sumOf { it.plainSize }
                            val spec = runCatching { current.payload.model<UploadSpec>() }.getOrDefault(UploadSpec())
                            val total = maxOf(file.size, file.expectedSize, 1)
                            val raw = if (current.kind == TaskKind.UPLOAD) file.remote.uploadedSize else file.local.downloadedSize
                            val bytes = if (current.kind == TaskKind.UPLOAD) base + (raw.toDouble() / total * spec.currentPlainSize).toLong() else raw
                            val speed = if (active.progressAt > 0 && bytes >= active.progressBytes) (bytes - active.progressBytes) * 1000 / maxOf(1, time - active.progressAt) else 0
                            active.progressAt = time; active.progressBytes = bytes
                            runCatching { repository.updateTask(current.id,current.accountId){latest->
                                if(latest.state==TaskState.TRANSFERRING&&active.fileId==file.id&&latest.parts==current.parts&&latest.payload==current.payload)
                                    latest.copy(done=bytes.coerceAtMost(latest.total),speedBytesPerSecond=speed)else latest
                            } }
                        }
                    }
                }
            }
        }
    }
    suspend fun enqueueUpload(uris: List<Uri>, folder: String = "", vault: String = "", replaceId: String = "", allowDuplicate: Boolean = false, ruleId: String = "") = withContext(Dispatchers.IO) {
        repository.awaitReady()
        require(repository.vaults.isUnlocked(vault)) { "Unlock the destination vault first." }
        if(folder.isNotEmpty())require(repository.state.value.folders.any{it.id==folder&&it.deletedAt==0L}&&repository.db().entity(EntityKind.FOLDER,folder)?.vaultId.orEmpty()==vault){"Destination folder is unavailable or has different encryption."}
        if(replaceId.isNotEmpty())require(repository.item(replaceId).deletedAt==0L&&repository.itemVault(replaceId)==vault&&uris.size==1){"Choose one file to replace an existing content version in the same vault."}
        require(uris.size <= 1000) { "Select at most 1,000 files per batch." }
        for (uri in uris) {
            val name = context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { if (it.moveToFirst()) it.getString(0) else null } ?: "file"
            val safe = app.tgdrive.data.FileMetadata.safeName(name).takeUnless{it=="."||it==".."}?:"file"
            val task = TransferTask(kind = TaskKind.UPLOAD, title = safe, source = uri.toString(), folderId = folder, vaultId = vault,
                itemId = replaceId.ifEmpty { newId() }, accountId = repository.state.value.accountId, profileId = repository.session.profileId,
                allowDuplicate = allowDuplicate, ruleId = ruleId, payload = UploadSpec(context.contentResolver.getType(uri) ?: "application/octet-stream").json())
            repository.setTask(task)
        }
        wake?.invoke()
    }
    suspend fun enqueue(kind: TaskKind, item: Item, destination: Uri? = null, payload: String = "{}", sharePassword: String? = null): String {
        val task = TransferTask(kind = kind, title = item.name, itemId = item.id, destination = destination?.toString() ?: "",
            total = item.size, vaultId = if(kind==TaskKind.EXPORT)payload.model<List<String>>().map{repository.itemVault(it)}.firstOrNull{it.isNotEmpty()}.orEmpty() else repository.itemVault(item.id), accountId = repository.state.value.accountId,
            profileId = repository.session.profileId, versionId = item.versionId, payload = payload)
        if(kind==TaskKind.SHARE)repository.saveAccountSecret("share-password:${task.id}",sharePassword?:"")
        if (kind == TaskKind.WEBDAV || kind == TaskKind.SHARE) OptionalConnections(repository).bind(task)
        repository.setTask(task)
        wake?.invoke()
        return task.id
    }
    suspend fun pause(id:String) {
        val before=task(id);if(before.state in terminal)return
        val active=activeTransfers[id]
        val nativeId=active?.fileId?:0
        active?.job?.cancelAndJoin()
        var current=task(id);if(current.state in terminal)return
        current=current.copy(state=TaskState.PAUSED,speedBytesPerSecond=0,error=if(current.kind==TaskKind.UPLOAD)"Completed parts are retained; the active upload part may restart." else "Paused by you.")
        repository.setTask(current)
        if(current.kind==TaskKind.UPLOAD&&current.pendingMessageId!=0L) {
            // Delete only an unsent temporary message, never an acknowledged content part.
            runCatching {
                withTimeout(5_000) {
                    val message=repository.session.call(TdApi.GetMessage(repository.session.savedMessagesId,current.pendingMessageId)) as TdApi.Message
                    if(message.sendingState!=null){repository.remote.delete(listOf(message.id));repository.setTask(task(id).copy(pendingMessageId=0))}
                    else repository.setTask(task(id).copy(pendingMessageId=message.id))
                }
            }
        } else if(nativeId!=0)runCatching{repository.session.call(TdApi.CancelDownloadFile(nativeId,false))}
    }
    suspend fun prioritize(id:String){val task=task(id);repository.setTask(task.copy(priority=1));wake?.invoke()}
    suspend fun retry(id: String) {
        val task = task(id); require(task.state != TaskState.COMPLETED)
        repository.setTask(task.copy(state = TaskState.QUEUED, attempts = 0, nextAttemptAt = 0, error = "")); wake?.invoke()
    }
    suspend fun cancel(id: String) { pause(id); val current=task(id); repository.setTask(current.copy(state = TaskState.CANCELLED, error = "Cancelled by you.")); OptionalConnections(repository).forget(current) }
    suspend fun clearFinished() = repository.mutate { db -> db.tasks().filter { it.state in terminal }.forEach { db.remove("tasks", it.id) } }
    fun task(id: String): TransferTask = repository.db().get("tasks", id)?.model() ?: error("Transfer no longer exists.")
    suspend fun pauseProtected() {
        repository.state.value.tasks.filter { it.vaultId.isNotEmpty() && it.state !in terminal && it.state !in setOf(TaskState.PAUSED,TaskState.FAILED) }.forEach {
            pause(it.id);val current=task(it.id)
            if(current.state !in terminal)repository.setTask(current.copy(state=TaskState.WAITING_FOR_UNLOCK,nextAttemptAt=0,error="Unlock the vault to continue protected work."))
        }
    }

    suspend fun drain(deadlineMillis: Long = 50 * 60 * 1000L) = runLock.withLock {
        val finished=withTimeoutOrNull(deadlineMillis) {
        repository.awaitReady()
        val account = repository.state.value.accountId
        val end = now() + deadlineMillis
        // Reconcile acknowledged metadata before retrying persistent writes.
        runCatching { repository.sync() }.onFailure { if (it is CancellationException) throw it }
        supervisorScope {
            val finishedJobs=Channel<String>(Channel.UNLIMITED)
            val running=linkedMapOf<String,Pair<TransferTask,Job>>()
            while(now()<end) {
                ensureActive();repository.checkAccount(account)
                while(running.size<uploadParallelism()) {
                    val next=repository.db().tasks().sortedWith(compareByDescending<TransferTask>{it.priority}.thenBy{it.createdAt}.thenBy{it.id}).firstOrNull {
                        it.id !in running&&it.profileId==repository.session.profileId&&it.state !in terminal&&
                        it.state !in setOf(TaskState.PAUSED,TaskState.FAILED,TaskState.SYNCING_METADATA)&&
                        (it.kind!=TaskKind.UPLOAD||repository.sender.until<=now())&&it.nextAttemptAt<=now()
                    }?:break
                    val reason=gate(next)
                    if(reason!=null){repository.setTask(next.copy(state=reason.first,error=reason.second,speedBytesPerSecond=0,nextAttemptAt=now()+60_000));continue}
                    // Vaults, explicit pacing and non-upload work remain exclusive. Replacements for one item cannot overlap.
                    val parallel=next.kind==TaskKind.UPLOAD&&next.vaultId.isEmpty()&&repository.state.value.preferences.uploadPaceKiB==0&&
                        running.values.all{it.first.kind==TaskKind.UPLOAD&&it.first.vaultId.isEmpty()&&it.first.itemId!=next.itemId}
                    if(running.isNotEmpty()&&!parallel)break
                    val job=launch(start=CoroutineStart.LAZY){try{execute(next,account)}finally{finishedJobs.trySend(next.id)}}
                    running[next.id]=next to job;job.start()
                    if(!parallel)break
                }
                if(running.isEmpty())break
                val id=finishedJobs.receive();running.remove(id)?.second?.join()
            }
        }
        try{repository.flush()}catch(e:SendCooldownException){repository.notice("Telegram paused sending. Your queue and uploaded parts are saved; it will resume automatically.")}
        repository.finalizeSyncedUploads()
        repository.state.value.tasks.filter{it.state==TaskState.COMPLETED&&!it.completionNotified}.forEach{notifyCompleted(it)}
        repository.maintainCache()
        if(repository.db().tasks().any{it.state !in terminal && it.state !in setOf(TaskState.PAUSED,TaskState.FAILED,TaskState.WAITING_FOR_UNLOCK)})wake?.invoke()
        true
        }
        if(finished!=true)wake?.invoke()
    }
    private fun uploadParallelism():Int {
        if(repository.state.value.preferences.uploadPaceKiB>0)return 1
        if(Build.VERSION.SDK_INT>=29&&context.getSystemService(PowerManager::class.java).currentThermalStatus>=PowerManager.THERMAL_STATUS_MODERATE)return 1
        return 2
    }
    private suspend fun execute(next:TransferTask,account:Long)=supervisorScope {
        if(task(next.id).state in setOf(TaskState.PAUSED,TaskState.CANCELLED,TaskState.COMPLETED))return@supervisorScope
        val active=ActiveTransfer()
        activeTransfers[next.id]=active
        val job=launch(start=CoroutineStart.LAZY) {
                    try {
                        repository.cache!!.lease(next.versionId).use {
                        withContext(NativeFileObserver{active.fileId=it}) {
                        when (next.kind) {
                            TaskKind.UPLOAD -> upload(next)
                            TaskKind.DOWNLOAD, TaskKind.PIN, TaskKind.VERIFY -> download(next)
                            TaskKind.EXPORT -> exportZip(next)
                            else -> external(next)
                        }
                        }
                        }
                        repository.checkAccount(account)
                        val current = task(next.id)
                        if (current.state == TaskState.COMPLETED) notifyCompleted(current)
                        else if (current.state in setOf(TaskState.QUEUED,TaskState.PREPARING,TaskState.TRANSFERRING)) {
                            val result = current.copy(state = TaskState.COMPLETED, done = current.total, error = "", speedBytesPerSecond = 0)
                            repository.setTask(result); notifyCompleted(result)
                        }
                    } catch (e: CancellationException) {
                        withContext(NonCancellable) {
                            if(active.fileId!=0&&next.kind!=TaskKind.UPLOAD)runCatching{withTimeout(5_000){repository.session.call(TdApi.CancelDownloadFile(active.fileId,false))}}
                            if (repository.state.value.accountId == account) {
                                val current = task(next.id)
                                if (current.state in setOf(TaskState.QUEUED,TaskState.PREPARING,TaskState.TRANSFERRING)) repository.setTask(current.copy(state = if(e is TimeoutCancellationException) TaskState.RETRY_WAIT else TaskState.PAUSED, nextAttemptAt = now() + 60_000, speedBytesPerSecond = 0, error = "Interrupted. Saved completed parts will be reconciled on the next attempt."))
                            }
                        }
                        throw e
                    }
                    catch (e: Exception) {
                        repository.checkAccount(account)
                        val current = task(next.id)
                        if(e is SendCooldownException) {
                            repository.setTask(current.copy(state=TaskState.RETRY_WAIT,nextAttemptAt=e.until,error="Telegram send limit. Waiting for the account countdown.",speedBytesPerSecond=0))
                            return@launch
                        }
                        val attempts = current.attempts + 1
                        val retry = repository.state.value.preferences.autoRetry && attempts < 3 && !permanent(e)
                        val wait = floodWait(e.message ?: "") ?: (30_000L * (1L shl attempts))
                        repository.setTask(current.copy(state = if (retry) TaskState.RETRY_WAIT else TaskState.FAILED, attempts = attempts,
                            nextAttemptAt = if (retry) now() + wait else 0, error = e.message?.take(240) ?: "Transfer failed.", speedBytesPerSecond = 0))
                    }

        }
        active.job=job;job.start()
        val monitor=launch {
            while(isActive){delay(1000);val reason=gate(task(next.id));if(reason!=null){
                withContext(NonCancellable){pause(next.id);val current=task(next.id)
                    if(current.state !in terminal)repository.setTask(current.copy(state=reason.first,error=reason.second,nextAttemptAt=now()+60_000))}
                break
            }}
        }
        try{job.join()}finally{monitor.cancelAndJoin();activeTransfers.remove(next.id,active);snapshotReservations.remove(next.id)}
    }
    private fun snapshotFile(task:TransferTask):File {
        val directory=File(context.filesDir,"transfer-staging/${task.profileId}/${task.id}")
        val legacy=File(directory,"source.snapshot")
        return if(legacy.exists())legacy else File(File(directory,"source"),task.title)
    }
    /** One pass creates and hashes a private snapshot, at most 64 MiB per active plain upload. */
    private suspend fun prepareSource(initial:TransferTask):Pair<Uri,Pair<String,Long>> {
        var current=task(initial.id);var spec=current.payload.model<UploadSpec>()
        val snapshot=snapshotFile(current)
        if(spec.preparedSource&&snapshot.exists()) {
            require(snapshot.length()==spec.preparedSize&&snapshot.sha256()==spec.preparedHash){"Prepared upload snapshot changed."}
            return Uri.fromFile(snapshot) to (spec.preparedHash to spec.preparedSize)
        }
        val uri=Uri.parse(current.source)
        val expected=if(uri.scheme=="file")File(uri.path!!).length()else context.contentResolver.query(uri,arrayOf(OpenableColumns.SIZE),null,null,null)?.use{if(it.moveToFirst()&&!it.isNull(0))it.getLong(0)else -1}?:-1
        val keep=synchronized(snapshotReservations) {
            val needed=expected.coerceAtLeast(0)*2
            val allowed=current.vaultId.isEmpty()&&expected in 1..64L*1024*1024&&
                context.filesDir.usableSpace>snapshotReservations.values.sum()+needed+128L*1024*1024
            if(allowed)snapshotReservations[current.id]=needed
            allowed
        }
        val digest=MessageDigest.getInstance("SHA-256");var size=0L
        val partial=File(snapshot.parentFile,"${snapshot.name}.partial")
        if(keep)snapshot.parentFile!!.mkdirs()
        try {
            val output=if(keep)partial.outputStream()else null
            output.use{out->context.contentResolver.openInputStream(uri)?.use{input->
                val buffer=ByteArray(128*1024)
                while(true){currentCoroutineContext().ensureActive();val n=input.read(buffer);if(n<0)break;size+=n;digest.update(buffer,0,n);out?.write(buffer,0,n)
                    if(keep)require(size<=expected){"Source changed during preparation."}
                }
            }?:error("Source permission is unavailable. Select the file again.")}
            val hash=digest.digest().joinToString(""){"%02x".format(it)}
            if(keep){
                require(size==expected){"Source changed during preparation."}
                check(partial.renameTo(snapshot)){"Could not finalize upload snapshot."}
                repository.checkAccount(current.accountId)
                current=task(initial.id);spec=current.payload.model<UploadSpec>()
                repository.setTask(current.copy(payload=spec.copy(preparedSource=true,preparedHash=hash,preparedSize=size).json()))
            }
            return (if(keep)Uri.fromFile(snapshot)else uri) to (hash to size)
        }finally{partial.delete()}
    }
    private suspend fun notifyCompleted(task:TransferTask) {
        try { completed(task);repository.setTask(task.copy(completionNotified=true)) }
        catch(e:CancellationException){throw e}catch(e:Exception){repository.report(e)}
    }
    private fun gate(task: TransferTask): Pair<TaskState, String>? {
        gateOverride?.let { return it(task) }
        if (task.kind==TaskKind.EXPORT && task.payload.model<List<String>>().any{!repository.vaults.isUnlocked(repository.itemVault(it))})return TaskState.WAITING_FOR_UNLOCK to "Unlock all selected vaults to continue this export."
        if (!repository.vaults.isUnlocked(task.vaultId)) return TaskState.WAITING_FOR_UNLOCK to "Unlock the vault to continue."
        if (task.kind in setOf(TaskKind.DOWNLOAD, TaskKind.PIN, TaskKind.VERIFY)) {
            val item = repository.state.value.items.firstOrNull { it.id == task.itemId }
            val version = repository.state.value.versions.firstOrNull { it.id == item?.versionId }
            if (version != null && repository.cache?.isOffline(version) == true) return null
        }
        val prefs = repository.state.value.preferences
        if(task.kind==TaskKind.UPLOAD&&prefs.uploadPaceKiB>0&&(repository.db().get("settings","uploadPaceUntil")?.toLongOrNull()?:0)>now())return TaskState.WAITING_FOR_DEVICE to "Waiting for the configured average upload pace. Individual parts may transfer in bursts."
        val cm = context.getSystemService(ConnectivityManager::class.java)
        val caps = cm.getNetworkCapabilities(cm.activeNetwork)
        if (caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) != true) return TaskState.WAITING_FOR_NETWORK to "Waiting for a network connection."
        if (prefs.wifiOnly && cm.isActiveNetworkMetered) return TaskState.WAITING_FOR_WIFI to "Waiting for an unmetered network."
        if (prefs.chargingOnly) {
            val battery = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            val status = battery?.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
            if (status != BatteryManager.BATTERY_STATUS_CHARGING && status != BatteryManager.BATTERY_STATUS_FULL) return TaskState.WAITING_FOR_CHARGING to "Waiting for charging."
        }
        if (Build.VERSION.SDK_INT >= 29 && context.getSystemService(PowerManager::class.java).currentThermalStatus >= PowerManager.THERMAL_STATUS_SEVERE) return TaskState.WAITING_FOR_DEVICE to "Device is too warm; waiting before another attempt."
        return null
    }
    private fun permanent(e: Exception) = e is IllegalArgumentException || listOf("checksum", "permission", "not found", "no longer", "Empty", "Unsupported", "space", "different").any { e.message?.contains(it, true) == true }
    private fun floodWait(message: String): Long? = Regex("(?:FLOOD_WAIT[_ ]|retry after )(\\d+)", RegexOption.IGNORE_CASE).find(message)?.groupValues?.get(1)?.toLongOrNull()?.times(1000)

    private suspend fun upload(initial: TransferTask) = withContext(Dispatchers.IO) {
        repository.setTask(task(initial.id).copy(state=TaskState.PREPARING,error=""))
        val current=task(initial.id)
        val finished=current.sourceSha256.isNotEmpty()&&current.parts.sumOf{it.plainSize}==current.total
        val prepared=if(finished)(Uri.parse(current.source) to (current.sourceSha256 to current.total))else prepareSource(initial)
        val key=current.vaultId+":"+prepared.second.first
        contentLocks[(key.hashCode().ushr(1))%contentLocks.size].withLock{uploadPrepared(initial,prepared.first,prepared.second.first,prepared.second.second)}
    }
    private suspend fun uploadPrepared(initial:TransferTask,sourceUri:Uri,hash:String,length:Long) {
        var current=task(initial.id)
        var spec=current.payload.model<UploadSpec>()
        val chunk=spec.chunkBytes.takeIf{it>0}?:repository.state.value.preferences.chunkMiB.coerceIn(1,1900)*1024L*1024
        val finishedContent=current.sourceSha256.isNotEmpty()&&current.parts.sumOf{it.plainSize}==current.total
        if(!finishedContent) {
            require(current.sourceSha256.isEmpty()||hash==current.sourceSha256){"Source content changed since the interrupted upload. Start a new upload."}
            val duplicate = repository.state.value.items.firstOrNull { it.sha256 == hash && repository.itemVault(it.id) == current.vaultId && it.deletedAt == 0L && it.versionId.isNotEmpty() && repository.state.value.versions.any { version -> version.id == it.versionId && version.itemId == it.id } }
            if (!current.allowDuplicate && repository.state.value.preferences.detectDuplicates && duplicate != null && duplicate.id != current.itemId && usableDuplicate(duplicate)) {
                repository.setTask(current.copy(state = TaskState.COMPLETED, itemId = duplicate.id, sourceSha256 = hash, total = length, done = length, error = "Identical content was already backed up; no duplicate was uploaded."))
                return
            }
            val count = if (length == 0L) 0 else ((length + chunk - 1) / chunk).toInt()
            require(count <= 128) { "This upload exceeds the 128-part safety limit. Choose a larger supported part size or divide the source." }
            spec = spec.copy(chunkBytes = chunk)
            current = current.copy(total = length, sourceSha256 = hash, payload = spec.json())
            repository.setTask(current)
        }
        val count = if (current.total == 0L) 0 else ((current.total + chunk - 1) / chunk).toInt()
        val directory = File(context.filesDir, "transfer-staging/${current.profileId}/${current.id}").apply { mkdirs() }
        if(count==1&&current.vaultId.isEmpty()&&current.parts.isEmpty()&&spec.embeddedCaption.isEmpty()&&current.pendingMessageId==0L) {
            spec=spec.copy(embeddedCaption=repository.prepareEmbeddedUpload(current,spec))
            current=current.copy(payload=spec.json());repository.setTask(current)
        }
        while (current.parts.size < count) {
            currentCoroutineContext().ensureActive(); repository.checkAccount(current.accountId)
            val waitUntil=current.payload.model<UploadSpec>().paceUntil
            if(repository.state.value.preferences.uploadPaceKiB>0&&waitUntil>now()) {repository.setTask(task(initial.id).copy(state=TaskState.RETRY_WAIT,nextAttemptAt=waitUntil,error="Waiting for the configured average upload pace."));return}
            val sendStarted=now()
            val index = current.parts.size
            val bytes = minOf(chunk, current.total - index * chunk)
            val direct=spec.preparedSource&&count==1&&current.vaultId.isEmpty()&&sourceUri.scheme=="file"&&File(sourceUri.path!!).exists()
            val sourceFile = if(direct)File(sourceUri.path!!)else File(if(spec.embeddedCaption.isNotEmpty())File(directory,"original").apply{mkdirs()}else directory, if(spec.embeddedCaption.isNotEmpty())current.title.replace(Regex("[\\\\/:]"),"_").take(180)else "part-$index.bin")
            var recovered = if (current.pendingMessageId != 0L) repository.remote.recoverPending(current.pendingMessageId) else null
            if (recovered == null) {
                val receipt = repository.db().get("sources", "cloud-part:${current.versionId}:$index")?.model<Part>()
                if (receipt != null) recovered = repository.remote.recoverPending(receipt.messageId)
            }
            if (recovered == null) {
                var stagedHash:String?=if(direct)current.sourceSha256 else null
                if (!sourceFile.exists()) {
                    val temp = File(directory, "part-$index.partial")
                    try {
                        context.contentResolver.openInputStream(sourceUri)!!.use { input ->
                            skipExactly(input, index * chunk)
                            val bounded = BoundedInput(input, bytes, currentCoroutineContext()[Job])
                            val digest=MessageDigest.getInstance("SHA-256")
                            DigestOutputStream(temp.outputStream(),digest).use { output ->
                                if (current.vaultId.isEmpty()) check(copyChecked(bounded, output) == bytes) { "Source became shorter during upload." }
                                else repository.vaults.encrypt(current.vaultId, bounded, output, "${current.itemId}/${current.versionId}/$index")
                            }
                            stagedHash=digest.digest().joinToString(""){"%02x".format(it)}
                            require(bounded.remaining == 0L) { "Source became shorter during upload." }
                        }
                        check(temp.renameTo(sourceFile)) { "Could not prepare upload part." }
                    } finally { temp.delete() }
                }
                val hash = stagedHash?:sourceFile.sha256()
                require(current.vaultId.isNotEmpty()||count!=1||hash==current.sourceSha256){"Source content changed while preparing the upload. Start a new upload."}
                spec = spec.copy(currentPlainSize = bytes, currentStoredHash = hash)
                current = current.copy(state = TaskState.TRANSFERRING, pendingPartPath = sourceFile.absolutePath, payload = spec.json(), error = "")
                repository.setTask(current)
                val caption = spec.embeddedCaption.ifEmpty{CloudCodec.PART + PartCaption(current.itemId, current.versionId, index, count, bytes, hash, current.vaultId.isNotEmpty()).json()}
                val attached=if(spec.embeddedCaption.isNotEmpty()) {
                    val preview=try{ThumbnailTools(repository).create(sourceUri,spec.mime)}catch(e:CancellationException){throw e}catch(_:Exception){null}
                    preview?.takeIf{it.size<=204_800}?.let{data->File(directory,"attached-preview.jpg").apply{writeBytes(data)}}
                }else null
                recovered = repository.remote.sendDocument(sourceFile, caption, pending = { pending ->
                    current = task(initial.id).copy(pendingMessageId = pending.id)
                    repository.setTask(current)
                },thumbnail=attached)
                attached?.delete()
            }
            val content = recovered.content as? TdApi.MessageDocument ?: error("Telegram returned no uploaded document.")
            val caption = if(spec.embeddedCaption.isNotEmpty()) {
                val ops=EmbeddedUpload.resolve(content.caption.text,recovered.id,content.document.document.size)
                val version=ops.first().body.model<Version>()
                require(content.caption.text==spec.embeddedCaption&&version.sha256==current.sourceSha256)
                PartCaption(current.itemId,version.id,0,1,version.size,version.sha256,false)
            }else content.caption.text.removePrefix(CloudCodec.PART).model<PartCaption>()
            require(caption.versionId == current.versionId && caption.index == index && caption.itemId == current.itemId) { "Pending message does not match this upload." }
            val part = Part(recovered.id, index, bytes, content.document.document.size, caption.storedSha256, current.vaultId.isNotEmpty())
            val pace=repository.state.value.preferences.uploadPaceKiB
            spec=task(initial.id).payload.model<UploadSpec>().copy(paceUntil=if(pace>0)now()+maxOf(0,part.storedSize*1000/(pace*1024L)-(now()-sendStarted))else 0)
            current = task(initial.id).copy(parts = current.parts + part, pendingMessageId = 0, pendingPartPath = "", payload=spec.json(), done = (current.parts + part).sumOf { it.plainSize })
            repository.setTask(current); if(!direct)sourceFile.delete()
        }
        spec = current.payload.model<UploadSpec>()
        if(repository.state.value.preferences.uploadPaceKiB>0&&spec.paceUntil>now()){repository.setTask(current.copy(state=TaskState.RETRY_WAIT,nextAttemptAt=spec.paceUntil,error="Waiting for the configured average upload pace."));return}
        if(!spec.thumbnailAttempted&&spec.embeddedCaption.isEmpty()) {
            val thumbFile=File(directory,"thumbnail.bin")
            var thumb = if(current.pendingMessageId!=0L) repository.remote.recoverPending(current.pendingMessageId) else null
            if(thumb==null) {
                val receipt=repository.db().get("sources","cloud-thumb:${current.versionId}")?.model<Part>()
                if(receipt!=null)thumb=repository.remote.recoverPending(receipt.messageId)
            }
            val bytes = if(thumb==null) try{ThumbnailTools(repository).create(sourceUri,spec.mime)}catch(e:CancellationException){throw e}catch(_:Exception){null} else null
            if(thumb==null && bytes!=null) {
                thumbFile.outputStream().use{out->if(current.vaultId.isEmpty())out.write(bytes) else repository.vaults.encrypt(current.vaultId,bytes.inputStream(),out,"${current.itemId}/${current.versionId}/-1")}
                val caption=CloudCodec.THUMB+PartCaption(current.itemId,current.versionId,-1,1,bytes.size.toLong(),thumbFile.sha256(),current.vaultId.isNotEmpty()).json()
                thumb=repository.remote.sendDocument(thumbFile,caption){pending->current=task(initial.id).copy(pendingMessageId=pending.id);repository.setTask(current)}
            }
            val thumbnail=thumb?.let { message->
                val doc=message.content as? TdApi.MessageDocument?:error("Thumbnail document is unavailable.")
                val caption=doc.caption.text.removePrefix(CloudCodec.THUMB).model<PartCaption>()
                require(caption.itemId==current.itemId&&caption.versionId==current.versionId&&caption.index == -1)
                Part(message.id,-1,caption.plainSize,doc.document.document.size,caption.storedSha256,caption.encrypted)
            }
            spec=spec.copy(thumbnail=thumbnail,thumbnailAttempted=true)
            current=task(initial.id).copy(payload=spec.json(),pendingMessageId=0)
            repository.setTask(current);thumbFile.delete()
        }
        val previous = repository.state.value.items.firstOrNull { it.id == current.itemId }
        val item = (previous ?: Item(id = current.itemId, name = current.title, folderId = current.folderId, mime = spec.mime)).copy(
            versionId = current.versionId, size = current.total, sha256 = current.sourceSha256, modifiedAt = now(), deletedAt = 0)
        val version = Version(current.versionId, item.id, current.total, current.sourceSha256, current.parts, thumbnail=spec.thumbnail)
        if(spec.embeddedCaption.isNotEmpty())repository.saveEmbeddedUpload(spec.embeddedCaption,current.parts.single(),current.source,current.accountId)
        else if (previous?.versionId != version.id) repository.saveUploaded(item, version, current.vaultId, current.source, current.accountId)
        repository.setTask(task(initial.id).copy(state=TaskState.SYNCING_METADATA,done=current.total,error="Content uploaded. Saving library information…",speedBytesPerSecond=0))
        if(repository.db().pending().size>=24)repository.flush()
        directory.deleteRecursively()
    }
    private suspend fun usableDuplicate(item:Item):Boolean {
        val local=repository.db().local(item.id)
        if(local.missing)return false
        for(part in repository.version(item.versionId).parts) {
            try {
                if(repository.remote.document(part.messageId).document.document.size!=part.storedSize){repository.setLocal(local.copy(missing=true));return false}
            } catch(e:CancellationException){throw e}
            catch(e:Exception){
                // A network failure is inconclusive, never a successful duplicate backup.
                if(Regex("not[ _]found|MESSAGE_ID_INVALID",RegexOption.IGNORE_CASE).containsMatchIn(e.message.orEmpty())){repository.setLocal(local.copy(missing=true));return false}
                throw e
            }
        }
        return true
    }
    private suspend fun download(initial: TransferTask) = withContext(Dispatchers.IO) {
        val item = repository.item(initial.itemId); val version = repository.version(initial.versionId)
        val boundItem = item.copy(versionId = version.id, size = version.size, sha256 = version.sha256)
        repository.setTask(initial.copy(state = TaskState.TRANSFERRING, total = version.size, error = ""))
        val cache = repository.cache ?: error("Cache unavailable")
        var last = 0L
        val result = cache.materialize(boundItem, version, initial.vaultId, initial.kind == TaskKind.PIN) { done ->
            if (now() - last > 1000) { last = now(); scope.launch { runCatching { repository.updateTask(initial.id,initial.accountId){if(it.state==TaskState.TRANSFERRING)it.copy(done=done)else it} } } }
        }
        repository.checkAccount(initial.accountId)
        var local = repository.db().local(item.id)
        if (version.sha256.isNotEmpty()) local = local.copy(verifiedVersion = version.id, verifiedAt = now(), missing = false)
        if (initial.kind == TaskKind.PIN) local = local.copy(pinnedVersion = version.id)
        repository.setLocal(local)
        if (initial.kind == TaskKind.DOWNLOAD) context.contentResolver.openOutputStream(Uri.parse(initial.destination), "wt")?.use { out -> result.inputStream().use { copyChecked(it, out) } } ?: error("Destination permission is unavailable.")
    }
    private suspend fun exportZip(initial: TransferTask) = withContext(Dispatchers.IO) {
        repository.setTask(initial.copy(state = TaskState.TRANSFERRING))
        val ids = initial.payload.model<List<String>>()
        context.contentResolver.openOutputStream(Uri.parse(initial.destination), "wt")!!.use { output ->
            ZipOutputStream(output).use { zip ->
                val names = mutableSetOf<String>()
                for ((index, id) in ids.withIndex()) {
                    val item = repository.item(id); val version = repository.version(item.versionId)
                    repository.cache!!.lease(version.id).use {
                    val file = repository.cache!!.materialize(item, version, repository.itemVault(id))
                    var name = cleanName(item.name)
                    if (!names.add(name)) { name = "${index + 1}-${item.name}"; names.add(name) }
                    zip.putNextEntry(ZipEntry(name).apply { time = item.modifiedAt }); file.inputStream().use { copyChecked(it, zip) }; zip.closeEntry()
                    repository.setTask(task(initial.id).copy(done = (index + 1).toLong(), total = ids.size.toLong()))
                    }
                }
            }
        }
    }
    private suspend fun skipExactly(input: InputStream, count: Long) {
        var left = count; val scratch = ByteArray(128 * 1024)
        while (left > 0) { currentCoroutineContext().ensureActive(); val n = input.read(scratch, 0, minOf(left, scratch.size.toLong()).toInt()); require(n > 0) { "Upload source changed." }; left -= n }
    }
    private class BoundedInput(input: InputStream, var remaining: Long, val job: Job?) : java.io.FilterInputStream(input) {
        override fun read(): Int { job?.ensureActive(); if (remaining == 0L) return -1; val b = super.read(); if (b >= 0) remaining--; return b }
        override fun read(b: ByteArray, off: Int, len: Int): Int {
            job?.ensureActive(); if (remaining == 0L) return -1
            val n = `in`.read(b, off, minOf(remaining, len.toLong()).toInt()); if (n > 0) remaining -= n; return n
        }
    }
}
