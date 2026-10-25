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
    private var preparation: Deferred<Unit>? = null
    private var preparationId = ""
    private var active: Job? = null
    private var activeId = ""
    private var activeFileId = 0
    private var progressAt = 0L
    private var progressBytes = 0L
    var external: suspend (TransferTask) -> Unit = { error("This optional connection is not configured.") }
    var completed: suspend (TransferTask) -> Unit = {}
    var wake: (() -> Unit)? = null
    private val terminal = setOf(TaskState.COMPLETED, TaskState.CANCELLED)

    init {
        scope.launch {
            repository.session.events.collect { update ->
                if (update is TdApi.UpdateFile && update.file.id == activeFileId && activeId.isNotEmpty()) {
                    val time = now()
                    if (time - progressAt >= 800) {
                        val file = update.file
                        val current = repository.database?.get("tasks", activeId)?.model<TransferTask>()
                        if (current != null && current.state == TaskState.TRANSFERRING) {
                            val base = current.parts.sumOf { it.plainSize }
                            val spec = runCatching { current.payload.model<UploadSpec>() }.getOrDefault(UploadSpec())
                            val total = maxOf(file.size, file.expectedSize, 1)
                            val raw = if (current.kind == TaskKind.UPLOAD) file.remote.uploadedSize else file.local.downloadedSize
                            val bytes = if (current.kind == TaskKind.UPLOAD) base + (raw.toDouble() / total * spec.currentPlainSize).toLong() else raw
                            val speed = if (progressAt > 0 && bytes >= progressBytes) (bytes - progressBytes) * 1000 / maxOf(1, time - progressAt) else 0
                            progressAt = time; progressBytes = bytes
                            runCatching { repository.setTask(current.copy(done = bytes.coerceAtMost(current.total), speedBytesPerSecond = speed)) }
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
        val nativeId=activeFileId
        if(activeId==id)active?.cancelAndJoin()
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
        while (now() < end) {
            currentCoroutineContext().ensureActive(); repository.checkAccount(account)
            val next = repository.db().tasks().sortedByDescending{it.priority}.firstOrNull {
                it.profileId == repository.session.profileId && it.state !in terminal && it.state !in setOf(TaskState.PAUSED, TaskState.FAILED,TaskState.SYNCING_METADATA) && (it.kind!=TaskKind.UPLOAD || repository.sender.until<=now()) && it.nextAttemptAt <= now()
            } ?: break
            if(preparationId==next.id){preparation?.await();preparation=null;preparationId=""}
            val gate = gate(next)
            if (gate != null) {
                repository.setTask(next.copy(state = gate.first, error = gate.second, speedBytesPerSecond = 0))
                // Do not spin on the same constrained task; other locked tasks remain inspectable.
                val eligible = repository.db().tasks().any { it.id != next.id && it.state == TaskState.QUEUED && gate(it) == null }
                if (!eligible) break
                repository.setTask(task(next.id).copy(nextAttemptAt = now() + 60_000)); continue
            }
            if(preparation==null) {
                val following=repository.state.value.tasks.firstOrNull{it.id!=next.id&&it.kind==TaskKind.UPLOAD&&it.state==TaskState.QUEUED&&it.vaultId.isEmpty()&&gate(it)==null}
                if(following!=null){preparationId=following.id;preparation=scope.async(Dispatchers.IO){prepareNext(following)}}
            }
            activeId = next.id; progressAt = 0; progressBytes = 0
            supervisorScope {
                active = launch {
                    try {
                        repository.cache!!.lease(next.versionId).use {
                        withContext(NativeFileObserver{activeFileId=it}) {
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
                            if(activeFileId!=0&&next.kind!=TaskKind.UPLOAD)runCatching{withTimeout(5_000){repository.session.call(TdApi.CancelDownloadFile(activeFileId,false))}}
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
                val monitor=launch {
                    while(isActive){delay(1000);val reason=gate(task(next.id));if(reason!=null){
                        withContext(NonCancellable){pause(next.id);val current=task(next.id)
                        if(current.state !in terminal)repository.setTask(current.copy(state=reason.first,error=reason.second,nextAttemptAt=now()+60_000))}
                        break
                    }}
                }
                try { active!!.join() } finally { monitor.cancelAndJoin(); active = null; activeId = ""; activeFileId = 0 }
            }
        }
        try{repository.flush()}catch(e:SendCooldownException){repository.notice("Telegram paused sending. Your queue and uploaded parts are saved; it will resume automatically.")}
        repository.finalizeSyncedUploads()
        repository.state.value.tasks.filter{it.state==TaskState.COMPLETED&&!it.completionNotified}.forEach{notifyCompleted(it)}
        repository.maintainCache()
        if(repository.db().tasks().any{it.state !in terminal && it.state !in setOf(TaskState.PAUSED,TaskState.FAILED,TaskState.WAITING_FOR_UNLOCK)})wake?.invoke()
        true
        }
        preparation?.cancelAndJoin();preparation=null;preparationId=""
        if(finished!=true)wake?.invoke()
    }
    /** One bounded, private snapshot ahead. Vault sources stay on the encrypted path. */
    private suspend fun prepareNext(initial:TransferTask) {
        val limit=16L*1024*1024
        val uri=Uri.parse(initial.source)
        val directory=File(context.filesDir,"transfer-staging/${initial.profileId}/${initial.id}")
        val output=File(directory,"source.snapshot")
        val partial=File(directory,"source.partial")
        try {
            val size=if(uri.scheme=="file")File(uri.path!!).length()else context.contentResolver.query(uri,arrayOf(OpenableColumns.SIZE),null,null,null)?.use{if(it.moveToFirst()&&!it.isNull(0))it.getLong(0)else -1}?:-1
            if(size !in 1..limit || context.filesDir.usableSpace<size*2+64L*1024*1024)return
            val before=task(initial.id)
            if(before.state!=TaskState.QUEUED||before.sourceSha256.isNotEmpty()||before.payload.model<UploadSpec>().preparedSource)return
            directory.mkdirs()
            context.contentResolver.openInputStream(uri)?.use{input->partial.outputStream().use{out->
                check(copyChecked(input,out,limit)==size){"Source changed during preparation"}
            }}?:return
            val hash=partial.sha256()
            currentCoroutineContext().ensureActive();repository.checkAccount(initial.accountId)
            if(task(initial.id).state!=TaskState.QUEUED)return
            check(partial.renameTo(output))
            val current=task(initial.id)
            repository.setTask(current.copy(payload=current.payload.model<UploadSpec>().copy(preparedSource=true,preparedHash=hash,preparedSize=size).json()))
        } catch(e:CancellationException){throw e}catch(_:Exception){output.delete()}
        finally{partial.delete()}
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
        var current = task(initial.id)
        repository.setTask(current.copy(state = TaskState.PREPARING, error = ""))
        var spec = current.payload.model<UploadSpec>()
        val snapshot=File(context.filesDir,"transfer-staging/${current.profileId}/${current.id}/source.snapshot")
        val sourceUri=if(spec.preparedSource&&snapshot.exists())Uri.fromFile(snapshot)else Uri.parse(current.source)
        val chunk = spec.chunkBytes.takeIf { it > 0 } ?: repository.state.value.preferences.chunkMiB.coerceIn(1, 1900) * 1024L * 1024
        val finishedContent = current.sourceSha256.isNotEmpty() && current.parts.sumOf { it.plainSize } == current.total
        if (!finishedContent) {
            val digest = MessageDigest.getInstance("SHA-256"); var length = 0L
            context.contentResolver.openInputStream(sourceUri)?.use { input ->
                val buffer = ByteArray(128 * 1024)
                while (true) { ensureActive(); val n = input.read(buffer); if (n < 0) break; digest.update(buffer, 0, n); length += n }
            } ?: error("Source permission is unavailable. Select the file again.")
            val hash = digest.digest().joinToString("") { "%02x".format(it) }
            if(sourceUri.scheme=="file" && sourceUri.path==snapshot.path)require(hash==spec.preparedHash&&length==spec.preparedSize){"Prepared upload snapshot changed."}
            require(current.sourceSha256.isEmpty() || hash == current.sourceSha256) { "Source content changed since the interrupted upload. Start a new upload." }
            val duplicate = repository.state.value.items.firstOrNull { it.sha256 == hash && repository.itemVault(it.id) == current.vaultId && it.deletedAt == 0L && it.versionId.isNotEmpty() && repository.state.value.versions.any { version -> version.id == it.versionId && version.itemId == it.id } }
            if (!current.allowDuplicate && repository.state.value.preferences.detectDuplicates && duplicate != null && duplicate.id != current.itemId && usableDuplicate(duplicate)) {
                repository.setTask(current.copy(state = TaskState.COMPLETED, itemId = duplicate.id, sourceSha256 = hash, total = length, done = length, error = "Identical content was already backed up; no duplicate was uploaded."))
                return@withContext
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
            ensureActive(); repository.checkAccount(current.accountId)
            val waitUntil=current.payload.model<UploadSpec>().paceUntil
            if(repository.state.value.preferences.uploadPaceKiB>0&&waitUntil>now()) {repository.setTask(task(initial.id).copy(state=TaskState.RETRY_WAIT,nextAttemptAt=waitUntil,error="Waiting for the configured average upload pace."));return@withContext}
            val sendStarted=now()
            val index = current.parts.size
            val bytes = minOf(chunk, current.total - index * chunk)
            val sourceFile = File(if(spec.embeddedCaption.isNotEmpty())File(directory,"original").apply{mkdirs()}else directory, if(spec.embeddedCaption.isNotEmpty())current.title.replace(Regex("[\\\\/:]"),"_").take(180)else "part-$index.bin")
            var recovered = if (current.pendingMessageId != 0L) repository.remote.recoverPending(current.pendingMessageId) else null
            if (recovered == null) {
                val receipt = repository.db().get("sources", "cloud-part:${current.versionId}:$index")?.model<Part>()
                if (receipt != null) recovered = repository.remote.recoverPending(receipt.messageId)
            }
            if (recovered == null) {
                if (!sourceFile.exists()) {
                    val temp = File(directory, "part-$index.partial")
                    try {
                        context.contentResolver.openInputStream(sourceUri)!!.use { input ->
                            skipExactly(input, index * chunk)
                            val bounded = BoundedInput(input, bytes, currentCoroutineContext()[Job])
                            temp.outputStream().use { output ->
                                if (current.vaultId.isEmpty()) check(copyChecked(bounded, output) == bytes) { "Source became shorter during upload." }
                                else repository.vaults.encrypt(current.vaultId, bounded, output, "${current.itemId}/${current.versionId}/$index")
                            }
                            require(bounded.remaining == 0L) { "Source became shorter during upload." }
                        }
                        check(temp.renameTo(sourceFile)) { "Could not prepare upload part." }
                    } finally { temp.delete() }
                }
                val hash = sourceFile.sha256()
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
                    activeFileId = (pending.content as? TdApi.MessageDocument)?.document?.document?.id ?: 0
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
            repository.setTask(current); sourceFile.delete(); activeFileId = 0
        }
        spec = current.payload.model<UploadSpec>()
        if(repository.state.value.preferences.uploadPaceKiB>0&&spec.paceUntil>now()){repository.setTask(current.copy(state=TaskState.RETRY_WAIT,nextAttemptAt=spec.paceUntil,error="Waiting for the configured average upload pace."));return@withContext}
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
            if (now() - last > 1000) { last = now(); scope.launch { runCatching { repository.setTask(task(initial.id).copy(done = done)) } } }
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
