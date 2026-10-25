package app.tgdrive.library

import android.content.Context
import android.net.Uri
import app.tgdrive.data.FileMetadata
import app.tgdrive.data.IndexStore
import app.tgdrive.data.LoginStep
import app.tgdrive.data.TelegramDrive
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.drinkless.tdlib.TdApi
import java.io.File
import java.util.UUID

class LibraryRepository(val context: Context, val session: TelegramDrive, val scope: CoroutineScope, private val rowCodec: (Long) -> RowCodec = { LocalRowCipher(it) }, private val allowAccount: (Long)->Boolean = {true}) {
    private val mutable = MutableStateFlow(LibraryState())
    private val initializationRevision = MutableStateFlow(0)
    val state = mutable.asStateFlow()
    val vaults = VaultManager()
    val thumbnails = ThumbnailLoader(this)
    val sender = TelegramSendCoordinator(persist={until->
        mutate{it.put("settings","sendCooldownUntil",until.toString())}
        mutable.update{it.copy(sendCooldownUntil=until)}
    })
    val remote = TelegramStorage(session,sender,::checkAccount)
    private val flushing = Mutex()
    private var maintainedAt=0L
    var cache: ContentCache? = null; private set
    var database: LibraryDatabase? = null; private set
    private val writes = Mutex()
    private val syncing = Mutex()
    private var deviceId = ""
    var changed: (() -> Unit)? = null

    init {
        session.libraryManaged = true
        scope.launch {
            combine(session.state,initializationRevision){auth,_->auth}.collect { auth ->
                try {
                if (auth.login == LoginStep.READY && auth.accountId != 0L && (state.value.accountId != auth.accountId || !state.value.loaded)) {
                    withContext(Dispatchers.IO) {
                        writes.withLock {
                            require(allowAccount(auth.accountId)) { "This Telegram account is already open in another profile. Switch to that profile; sign out of this duplicate profile to use a different account." }
                            thumbnails.clear();database?.close(); vaults.lock()
                            database = LibraryDatabase(context, auth.accountId, rowCodec(auth.accountId))
                            mutable.value = LibraryState(accountId = auth.accountId, accountName = auth.account)
                            deviceId = db().get("settings", "deviceId") ?: newId().also { db().put("settings", "deviceId", it) }
                            cache = ContentCache(context, auth.accountId, remote, vaults)
                            migrateLegacy()
                            // Interrupted work is retained; uploads reconcile their pending message before retrying.
                            db().tasks().filter { it.state in listOf(TaskState.PREPARING, TaskState.TRANSFERRING) }.forEach {
                                db().putTask(it.copy(state = TaskState.PAUSED, error = "Interrupted by Android. Resume to reconcile the saved transfer."))
                            }
                            sender.restore(db().get("settings","sendCooldownUntil")?.toLongOrNull()?:0)
                            publish()
                            mutable.update { it.copy(loaded = true) }
                        }
                        maintainCache()
                        if (state.value.preferences.autoSync) runCatching { sync() }.onFailure { report(it) }
                    }
                } else if (auth.login == LoginStep.CLOSED || (auth.login != LoginStep.READY && auth.accountId == 0L)) {
                    if (state.value.accountId != 0L) { vaults.lock(); thumbnails.clear(); cache?.clearSensitive(); mutable.value = LibraryState() }
                }
                } catch (e: Exception) { report(e) }
            }
        }
    }
    fun db(): LibraryDatabase = database?.takeIf { state.value.accountId != 0L } ?: error("Open and unlock your Telegram account first.")
    suspend fun awaitReady() { check(withTimeoutOrNull(45_000) { while (!state.value.loaded) delay(100);true }==true) { "Open TG Drive and finish signing in before processing this library." } }
    private fun envelope(kind: EntityKind, id: String, body: String, vault: String = "", deleted: Boolean = false, resolves: List<String> = emptyList()): Envelope {
        val previous = db().entity(kind, id)
        return vaults.protect(Envelope(kind = kind, entityId = id, parentOperationId = previous?.operationId ?: "", deviceId = deviceId,
            timestamp = maxOf(now(), (previous?.timestamp ?: 0) + 1), vaultId = vault, body = body, tombstone = deleted, resolves = resolves))
    }
    suspend fun accountSecret(name:String):String? {
        val account=state.value.accountId;checkAccount(account)
        return session.secret("account:$account:$name")
    }
    suspend fun saveAccountSecret(name:String,value:String) {
        val account=state.value.accountId;checkAccount(account)
        session.saveSecret("account:$account:$name",value)
    }
    fun checkAccount(accountId: Long) { if (state.value.accountId != accountId || !state.value.loaded) throw CancellationException("Account changed") }
    suspend fun mutate(block: (LibraryDatabase) -> Unit) {
        val accountId = state.value.accountId
        withContext(Dispatchers.IO) { writes.withLock { checkAccount(accountId); block(db()); publish() } }
    }
    private fun commit(value: Envelope) { db().apply(JournalEntry(value, pending = true)) }
    private fun migrateLegacy() {
        if (db().get("settings", "legacyMigrated") == "true") return
        val (files, folders) = IndexStore(context, state.value.accountId).read()
        for (folder in folders) migrateFolder(folder)
        for (file in files) migrateLegacyMessage(file.messageId, file.metadata, file.size, file.date.toLong() * 1000, file.mimeType)
        db().put("settings", "legacyMigrated", "true")
    }
    private fun migrateFolder(name: String): String {
        if (name.isEmpty()) return ""
        val id = UUID.nameUUIDFromBytes("legacy-folder:$name".toByteArray()).toString()
        if (db().entity(EntityKind.FOLDER, id) == null) commit(Envelope(operationId = "legacy-folder-$id", kind = EntityKind.FOLDER, entityId = id, deviceId = "legacy", timestamp = 1, body = Folder(id, name).json()))
        return id
    }
    private fun migrateLegacyMessage(messageId: Long, meta: FileMetadata, size: Long, date: Long, mime: String) {
        if (db().entity(EntityKind.ITEM, meta.id) != null) return
        val versionId = "legacy-$messageId"
        val version = Version(versionId, meta.id, size, "", listOf(Part(messageId, 0, size, size, "")), date, imported = true)
        val item = Item(meta.id, meta.name.takeUnless{it=="."||it==".."}?:"file", migrateFolder(meta.folder), mime, size, versionId = versionId, createdAt = date, modifiedAt = date)
        commit(Envelope(operationId = "legacy-version-$messageId", kind = EntityKind.VERSION, entityId = versionId, deviceId = "legacy", timestamp = date, body = version.json()))
        commit(Envelope(operationId = "legacy-item-$messageId", kind = EntityKind.ITEM, entityId = item.id, deviceId = "legacy", timestamp = date, body = item.json()))
    }
    private fun publish() {
        val entities = db().entities()
        val items = mutableListOf<Item>(); val folders = mutableListOf<Folder>(); val versions = mutableListOf<Version>()
        val descriptors = mutableListOf<VaultDescriptor>(); var locked = 0
        val problems = mutableListOf<String>()
        for (op in entities) {
            if (!vaults.isUnlocked(op.vaultId)) { if (op.kind == EntityKind.ITEM) locked++; continue }
            try {
                val body = vaults.body(op)
                validateOperation(op,body)
                when (op.kind) {
                    EntityKind.ITEM -> items.add(body.model())
                    EntityKind.FOLDER -> folders.add(body.model())
                    EntityKind.VERSION -> if(!op.tombstone) versions.add(body.model())
                    EntityKind.VAULT -> descriptors.add(body.model())
                }
            } catch (_: Exception) { problems.add("Metadata could not be opened: ${op.kind.name.lowercase()} ${op.entityId.takeLast(8)}") }
        }
        mutable.update { it.copy(items = items, folders = folders, versions = versions, tasks = db().tasks(),
            local = db().all("local").map { row -> row.model<LocalItem>() }.associateBy { row -> row.itemId },
            rules = db().all("rules").map { row -> row.model() }, preferences = db().preferences(), lockedVaults = locked,
            unlockedVaultIds = vaults.unlockedIds, vaults = descriptors, conflicts = db().all("conflicts").map { row -> row.model() },
            pendingOperations = db().pending().size, sendCooldownUntil=sender.until, healthProblems = problems,
            itemVaults = entities.filter { e -> e.kind == EntityKind.ITEM }.associate { e -> e.entityId to e.vaultId }, folderVaults = entities.filter { e -> e.kind == EntityKind.FOLDER }.associate { e -> e.entityId to e.vaultId },
            shares = db().entries("settings").filter { row->row.first.startsWith("share:") }.map { row->row.second.model() }) }
    }
    fun itemVault(id: String) = db().entity(EntityKind.ITEM, id)?.vaultId ?: ""
    fun version(id: String): Version = state.value.versions.firstOrNull { it.id == id } ?: error("File version is locked or unavailable.")
    fun item(id: String): Item = state.value.items.firstOrNull { it.id == id } ?: error("File is locked or unavailable.")
    fun retryInitialization() {
        mutable.update{it.copy(error=null)}
        if(session.state.value.accountId==0L)session.reconnect() else initializationRevision.value++
    }
    suspend fun createFolder(name: String, parent: String = "", vault: String = "",id:String=newId()): String {
        val f = Folder(id=id,name = cleanName(name), parentId = parent)
        mutate { db->
            require(parent.isEmpty() || state.value.folders.any { it.id == parent && it.deletedAt == 0L }&&db.entity(EntityKind.FOLDER,parent)?.vaultId.orEmpty()==vault) { "Parent folder is unavailable or belongs to another vault." }
            val existing=db.entity(EntityKind.FOLDER,id)
            if(existing==null)commit(envelope(EntityKind.FOLDER, f.id, f.json(), vault))
            else require(existing.vaultId==vault&&vaults.body(existing).model<Folder>()==f){"This folder draft was already saved with different details."}
        }
        changed?.invoke(); return f.id
    }
    suspend fun updateItem(item: Item) {
        cleanName(item.name)
        mutate {
            require(db().entity(EntityKind.ITEM,item.id)!=null) { "File no longer exists in this account." }
            if(item.folderId.isNotEmpty()) require(state.value.folders.any{it.id==item.folderId&&it.deletedAt==0L}&&db().entity(EntityKind.FOLDER,item.folderId)?.vaultId.orEmpty()==itemVault(item.id)) { "Destination folder has different encryption or is unavailable." }
            commit(envelope(EntityKind.ITEM, item.id, item.copy(modifiedAt = now()).json(), itemVault(item.id), item.deletedAt > 0))
        }
        changed?.invoke()
    }
    suspend fun updateFolder(folder: Folder) {
        cleanName(folder.name)
        mutate {
            val ownVault = db().entity(EntityKind.FOLDER, folder.id)?.vaultId ?: ""
            if(folder.parentId.isNotEmpty())require(state.value.folders.any{it.id==folder.parentId&&it.deletedAt==0L}&&db().entity(EntityKind.FOLDER,folder.parentId)?.vaultId.orEmpty()==ownVault) { "Parent folder is unavailable or belongs to another vault." }
            var parent = folder.parentId; val seen = mutableSetOf(folder.id)
            while (parent.isNotEmpty()) { require(seen.add(parent)) { "A folder cannot contain itself." }; parent = state.value.folders.firstOrNull { it.id == parent }?.parentId ?: "" }
            val vault = db().entity(EntityKind.FOLDER, folder.id)?.vaultId ?: ""
            commit(envelope(EntityKind.FOLDER, folder.id, folder.json(), vault, folder.deletedAt > 0))
        }; changed?.invoke()
    }
    suspend fun saveUploaded(item: Item, version: Version, vault: String, origin: String, accountId: Long = state.value.accountId) {
        mutate {
            checkAccount(accountId)
            commit(envelope(EntityKind.VERSION, version.id, version.json(), vault))
            commit(envelope(EntityKind.ITEM, item.id, item.json(), vault))
            if (origin.isNotEmpty()) db().putLocal(db().local(item.id).copy(sourceUri = origin, sourceHash = item.sha256))
        }; changed?.invoke()
    }
    /** Capture stable operation IDs before sending; the task persists the resulting caption first. */
    suspend fun prepareEmbeddedUpload(task:TransferTask,spec:UploadSpec):String = withContext(Dispatchers.IO) {
        writes.withLock {
            checkAccount(task.accountId)
            val previous=state.value.items.firstOrNull{it.id==task.itemId}
            val item=(previous?:Item(id=task.itemId,name=task.title,folderId=task.folderId,mime=spec.mime)).copy(
                versionId=task.versionId,size=task.total,sha256=task.sourceSha256,modifiedAt=now(),deletedAt=0)
            val version=Version(task.versionId,item.id,task.total,task.sourceSha256,listOf(Part(0,0,task.total,task.total,task.sourceSha256)))
            val caption=EmbeddedUpload.encode(listOf(envelope(EntityKind.VERSION,version.id,version.json()),envelope(EntityKind.ITEM,item.id,item.json())))?:""
            if(caption.isNotEmpty())EmbeddedUpload.resolve(caption,1,task.total)
            caption
        }
    }
    suspend fun saveEmbeddedUpload(caption:String,part:Part,origin:String,accountId:Long) {
        val operations=EmbeddedUpload.resolve(caption,part.messageId,part.storedSize)
        mutate { database->
            checkAccount(accountId)
            database.transaction {
                operations.forEach{database.apply(JournalEntry(it,part.messageId))}
                if(origin.isNotEmpty()) {
                    val item=operations.last().body.model<Item>()
                    database.putLocal(database.local(item.id).copy(sourceUri=origin,sourceHash=item.sha256))
                }
            }
        };changed?.invoke()
    }
    suspend fun setTask(task: TransferTask) = withContext(Dispatchers.IO) {
        writes.withLock {
            checkAccount(task.accountId); db().putTask(task.copy(updatedAt = now()))
            mutable.update { it.copy(tasks = db().tasks()) }
        }
    }
    /** Progress must never overwrite a newer state, manifest, or retry receipt. */
    suspend fun updateTask(id:String,account:Long,update:(TransferTask)->TransferTask)=withContext(Dispatchers.IO) {
        writes.withLock {
            checkAccount(account)
            val current=db().get("tasks",id)?.model<TransferTask>()?:return@withLock
            db().putTask(update(current).copy(updatedAt=now()))
            mutable.update{it.copy(tasks=db().tasks())}
        }
    }
    suspend fun setLocal(value: LocalItem) = withContext(Dispatchers.IO) {
        writes.withLock { db().putLocal(value); mutable.update { it.copy(local = it.local + (value.itemId to value)) } }
    }
    suspend fun updateStorage() = withContext(Dispatchers.IO) {
        val account=state.value.accountId
        val storage=cache?:return@withContext
        storage.trim(state.value.preferences.cacheMiB*1024L*1024)
        val cached=storage.bytes();val offline=storage.bytes(storage.offline)
        val sources=listOf("owned-inputs","captures").sumOf{TemporaryFiles.bytes(File(context.filesDir,"$it/${session.profileId}"))}
        val staging=TemporaryFiles.bytes(File(context.filesDir,"transfer-staging/${session.profileId}"))
        val exports=TemporaryFiles.bytes(File(context.cacheDir,"exports"))
        checkAccount(account)
        mutable.update{it.copy(cacheBytes=cached,offlineBytes=offline,retainedSourceBytes=sources,stagingBytes=staging,exportBytes=exports)}
    }
    suspend fun setPreferences(value: Preferences) = updatePreferences { value }
    suspend fun updatePreferences(update: (Preferences)->Preferences) {
        val account = state.value.accountId
        val budgetChanged = withContext(Dispatchers.IO) {
            writes.withLock {
                checkAccount(account)
                val old = db().preferences()
                val next = update(old)
                db().put("settings", "preferences", next.json())
                // A toggle must not decrypt every metadata row or walk the entire disk cache.
                mutable.update { it.copy(preferences = next) }
                old.cacheMiB != next.cacheMiB
            }
        }
        changed?.invoke()
        if(budgetChanged) updateStorage()
    }
    suspend fun setRule(value: BackupRule) = mutate { it.put("rules", value.id, value.json()) }
    suspend fun updateRule(id:String,transform:(BackupRule)->BackupRule)=mutate{db->val rule=db.get("rules",id)?.model<BackupRule>()?:return@mutate;db.put("rules",id,transform(rule).json())}
    suspend fun setRuleEnabled(id:String,enabled:Boolean)=mutate{db->val rule=db.get("rules",id)?.model<BackupRule>()?:error("Backup rule was removed.");db.put("rules",id,rule.copy(enabled=enabled).json())}
    suspend fun reconnectRule(id:String,tree:Uri)=mutate{db->
        require(androidx.documentfile.provider.DocumentFile.fromTreeUri(context,tree)?.canRead()==true){"This folder is not readable."}
        val rule=db.get("rules",id)?.model<BackupRule>()?:error("Backup rule was removed.")
        require(Uri.parse(rule.treeUri).authority==tree.authority&&Uri.parse(rule.treeUri).let{android.provider.DocumentsContract.getTreeDocumentId(it)}==android.provider.DocumentsContract.getTreeDocumentId(tree)){"Choose the original folder to retain its backup receipts."}
        db.put("rules",id,rule.copy(treeUri=tree.toString(),error="").json())
    }
    suspend fun removeRule(id: String) = mutate { it.remove("rules", id) }

    suspend fun sync(full: Boolean = false, collectImports: Boolean = false) = withContext(Dispatchers.IO) {
        syncing.withLock {
            awaitReady(); mutable.update { it.copy(syncing = true) }
            val accountId = state.value.accountId
            try {
                val known = if (full) 0L else db().get("settings", "lastScanMessage")?.toLongOrNull() ?: 0L
                var cursor = 0L; var newest = known
                val received = mutableListOf<Pair<Long, Envelope>>()
                val imports = mutableListOf<ImportCandidate>()
                val backups = mutableListOf<Long>()
                val partReceipts = mutableListOf<Pair<String, Part>>()
                val seen = mutableSetOf<Long>()
                scan@ while (true) {
                    currentCoroutineContext().ensureActive()
                    checkAccount(accountId)
                    val page = remote.history(cursor)
                    if (page.isEmpty()) break
                    for (m in page) {
                        try {
                        newest = maxOf(newest, m.id)
                        if (!full && !collectImports && m.id <= known) break@scan
                        val text = (m.content as? TdApi.MessageText)?.text?.text
                        val doc = m.content as? TdApi.MessageDocument
                        when {
                            (text?.startsWith(CloudCodec.PREFIX) == true || text?.startsWith(CloudCodec.BATCH) == true) -> CloudCodec.decodeMany(text!!).forEach { received.add(m.id to it) }
                            doc?.caption?.text?.startsWith(CloudCodec.DOCUMENT) == true -> {
                                require(doc.document.document.size <= 1_500_000) { "Metadata document is too large." }
                                CloudCodec.decodeMany(remote.readAll(m.id).readText()).forEach { received.add(m.id to it) }
                            }
                            doc?.caption?.text?.startsWith(EmbeddedUpload.PREFIX) == true -> {
                                val ops=EmbeddedUpload.resolve(doc.caption.text,m.id,doc.document.document.size)
                                ops.forEach{received.add(m.id to it)}
                                val version=ops.first().body.model<Version>()
                                partReceipts.add("cloud-part:${version.id}:0" to version.parts.single())
                            }
                            doc?.caption?.text?.startsWith(CloudCodec.BACKUP) == true -> backups.add(m.id)
                            doc?.caption?.text?.startsWith(CloudCodec.THUMB) == true -> {
                                val part=doc.caption.text.removePrefix(CloudCodec.THUMB).model<PartCaption>()
                                require(part.index == -1 && doc.document.document.size<=524_400)
                                partReceipts.add("cloud-thumb:${part.versionId}" to Part(m.id,-1,part.plainSize,doc.document.document.size,part.storedSha256,part.encrypted))
                            }
                            doc?.caption?.text?.startsWith(CloudCodec.PART) == true -> {
                                val part = doc.caption.text.removePrefix(CloudCodec.PART).model<PartCaption>()
                                require(part.index in 0..127 && part.count in 1..128 && part.plainSize >= 0)
                                partReceipts.add("cloud-part:${part.versionId}:${part.index}" to Part(m.id, part.index, part.plainSize, doc.document.document.size, part.storedSha256, part.encrypted))
                            }
                            doc != null -> {
                                val legacy = FileMetadata.parse(doc.caption.text)
                                if (legacy != null) mutate { migrateLegacyMessage(m.id, legacy, doc.document.document.size, m.date * 1000L, doc.document.mimeType) }
                                else if (collectImports && !doc.caption.text.startsWith(CloudCodec.PART)) imports.add(ImportCandidate(m.id, doc.document.fileName, doc.document.mimeType, doc.document.document.size, m.date * 1000L))
                            }
                        }
                        }
                        catch (e: CancellationException) { throw e }
                        catch (e: Exception) {
                            // Keep valid operations from the same scan, and surface the skipped message.
                            mutable.update { it.copy(error = "Some metadata messages could not be read. Valid records were retained; run a full refresh after checking connectivity.") }
                        }
                    }
                    val next = page.last().id
                    if (next == cursor || !seen.add(next)) break
                    cursor = next; delay(120)
                }
                mutate { database ->
                    checkAccount(accountId)
                    received.sortedBy { it.first }.forEach { (id, op) ->
                        runCatching { database.apply(JournalEntry(op, id)) }.onFailure { mutable.update { state -> state.copy(error = "An unsupported metadata record was skipped. Valid records were retained.") } }
                    }
                    database.put("settings", "lastScanMessage", newest.toString())
                    if (backups.isNotEmpty()) database.put("settings", "cloudBackups", backups.take(20).json())
                    partReceipts.forEach { (id, receipt) -> database.put("sources", id, receipt.json()) }
                }
                mutable.update { it.copy(lastSyncAt = now(), incoming = if (collectImports) imports else it.incoming) }
            } finally { mutable.update { it.copy(syncing = false) } }
        }
    }
    suspend fun flush() = withContext(Dispatchers.IO) {
        flushing.withLock {
            if(!state.value.loaded)return@withLock
            while(true) {
                // Keep transport bounded. Individual operation IDs and encrypted bodies remain intact.
                val pending=db().pending()
                if(pending.isEmpty())break
                var rows=pending.take(32)
                while(rows.size>1 && rows.sumOf{it.envelope.json().toByteArray().size}>800_000)rows=rows.take(rows.size/2)
                val wire=if(rows.size==1)CloudCodec.encode(rows.single().envelope)else CloudCodec.encodeBatch(rows.map{it.envelope})
                val ack=if(wire.length<=3500)remote.sendText(wire)else {
                    val path=File(context.cacheDir,"metadata-${rows.first().envelope.operationId}.txt")
                    try{path.writeText(wire);remote.sendDocument(path,CloudCodec.DOCUMENT+rows.first().envelope.operationId)}finally{path.delete()}
                }
                mutate{database->rows.forEach{database.apply(it.copy(messageId=ack.id,pending=false))}}
            }
            finalizeSyncedUploads()
        }
    }
    /** Relocate surviving operations before deleting a shared cloud batch. */
    suspend fun deleteCloudMetadata(entityIds:Set<String>) = withContext(Dispatchers.IO) {
        flushing.withLock {
            val all=db().all("journal").map{it.model<JournalEntry>()}
            val targets=all.filter{it.envelope.entityId in entityIds}.map{it.messageId}.filter{it!=0L}.toSet()
            for(messageId in targets) {
                val survivors=all.filter{it.messageId==messageId&&it.envelope.entityId !in entityIds}
                if(survivors.isNotEmpty()) {
                    val wire=if(survivors.size==1)CloudCodec.encode(survivors.single().envelope)else CloudCodec.encodeBatch(survivors.map{it.envelope})
                    val ack=if(wire.length<=3500)remote.sendText(wire)else {
                        val file=File(context.cacheDir,"relocate-${newId()}.txt")
                        try{file.writeText(wire);remote.sendDocument(file,CloudCodec.DOCUMENT+"relocated")}finally{file.delete()}
                    }
                    mutate{database->survivors.forEach{database.put("journal",it.envelope.operationId,it.copy(messageId=ack.id).json())}}
                }
            }
            remote.delete(targets.toList())
        }
    }
    suspend fun finalizeSyncedUploads() {
        val candidates=state.value.tasks.filter{it.state==TaskState.SYNCING_METADATA}
        if(candidates.isEmpty())return
        mutate{database->
            val pendingIds=database.pending().map{it.envelope.entityId}.toSet()
            candidates.filter{it.itemId !in pendingIds && it.versionId !in pendingIds}.forEach{
                database.putTask(it.copy(state=TaskState.COMPLETED,done=it.total,error="",speedBytesPerSecond=0))
            }
        }
    }
    suspend fun maintainCache() {
        if(now()-maintainedAt<30_000 && context.cacheDir.usableSpace>64L*1024*1024)return
        maintainedAt=now();withContext(Dispatchers.IO){runCatching{TemporaryFiles.prune(this@LibraryRepository)}};updateStorage()
    }
    suspend fun createVault(label: String, password: CharArray): String = withContext(Dispatchers.IO) {
        val (descriptor, recovery) = vaults.create(label, password)
        saveAccountSecret("pending-recovery:${descriptor.id}",recovery)
        mutate { commit(envelope(EntityKind.VAULT, descriptor.id, descriptor.json())) }
        changed?.invoke(); recovery
    }
    suspend fun unlockVault(id: String, credential: CharArray, recovery: Boolean) = withContext(Dispatchers.IO) {
        val desc = state.value.vaults.firstOrNull { it.id == id } ?: error("Vault descriptor unavailable. Refresh the index first.")
        vaults.unlock(desc, credential, recovery); mutate { db->db.tasks().filter{it.vaultId==id&&it.state==TaskState.WAITING_FOR_UNLOCK}.forEach{db.putTask(it.copy(state=TaskState.QUEUED,nextAttemptAt=0,error=""))} }; changed?.invoke()
    }
    suspend fun lockVaults() { vaults.lock(); thumbnails.clear(); withContext(Dispatchers.IO) { cache?.clearSensitive(); mutate { } } }
    suspend fun resolveConflict(id: String, useIncoming: Boolean) {
        mutate { database ->
            val conflict = database.get("conflicts", id)?.model<Conflict>() ?: return@mutate
            val chosen = if (useIncoming) conflict.incoming else conflict.kept
            val op = envelope(chosen.kind, chosen.entityId, vaults.body(chosen), chosen.vaultId, chosen.tombstone,
                listOf(conflict.kept.operationId, conflict.incoming.operationId))
            commit(op); database.remove("conflicts", id)
        }; changed?.invoke()
    }
    suspend fun exportIndex(uri: Uri) = withContext(Dispatchers.IO) {
        val snapshot = Snapshot(accountId = state.value.accountId, entities = db().entities())
        context.contentResolver.openOutputStream(uri, "wt")!!.bufferedWriter().use { it.write(snapshot.json()) }
        notice("Index exported. It contains metadata and encrypted vault descriptors, never login sessions.")
    }
    suspend fun importIndex(uri: Uri) = withContext(Dispatchers.IO) {
        val text = context.contentResolver.openInputStream(uri)!!.use { input -> readLimited(input, 32 * 1024 * 1024) }
        require(text.size <= 32 * 1024 * 1024) { "Index backup is too large." }
        val snapshot = text.decodeToString().model<Snapshot>()
        require(snapshot.schema == 2 && snapshot.accountId == state.value.accountId) { "This index belongs to a different Telegram account or format." }
        mutate { db -> snapshot.entities.forEach { db.apply(JournalEntry(it, pending = true)) } }
        changed?.invoke(); notice("Index merged. Existing changes and conflicts were preserved.")
    }
    suspend fun saveCloudIndex() = withContext(Dispatchers.IO) {
        val file = File(context.cacheDir, "index-backup-${state.value.accountId}.json")
        try {
            file.writeText(Snapshot(accountId = state.value.accountId, entities = db().entities()).json())
            val message = remote.sendDocument(file, CloudCodec.BACKUP + now())
            mutate { it.put("settings", "cloudBackups", (listOf(message.id) + (it.get("settings", "cloudBackups")?.model<List<Long>>() ?: emptyList())).distinct().take(20).json()) }
        } finally { file.delete() }
        notice("Cloud index checkpoint saved. File content is stored separately in your messages.")
    }
    suspend fun forgetVersions(versions: List<Version>) = mutate { db ->
        versions.forEach { version ->
            val vault = db.entity(EntityKind.VERSION, version.id)?.vaultId.orEmpty()
            commit(envelope(EntityKind.VERSION, version.id, version.copy(parts=emptyList(), size=0, sha256="", thumbnail=null).json(), vault, deleted=true))
        }
    }
    suspend fun restoreCloudIndex() = withContext(Dispatchers.IO) {
        sync(full = true)
        val id = db().get("settings", "cloudBackups")?.model<List<Long>>()?.firstOrNull() ?: error("No cloud index checkpoint was found.")
        val file = remote.readAll(id); require(file.length() <= 32 * 1024 * 1024) { "Index checkpoint is too large." }
        val snapshot = file.readText().model<Snapshot>()
        require(snapshot.schema == 2 && snapshot.accountId == state.value.accountId)
        mutate { db -> snapshot.entities.forEach { db.apply(JournalEntry(it, pending = true)) } }
        changed?.invoke(); notice("Checkpoint restored. Conflicts remain available for review.")
    }
    suspend fun importMessage(candidate: ImportCandidate, folder: String) {
        require(folder.isEmpty()||db().entity(EntityKind.FOLDER,folder)?.vaultId.orEmpty().isEmpty()) { "Import references into Personal first; encrypting their content requires a protected upload." }
        if(state.value.versions.any{it.imported&&it.parts.any{part->part.messageId==candidate.messageId}}){notice("This message is already in your library.");return}
        val meta = FileMetadata.create(candidate.name, "")
        val version = Version(itemId = meta.id, size = candidate.size, sha256 = "", parts = listOf(Part(candidate.messageId, 0, candidate.size, candidate.size, "")), imported = true)
        saveUploaded(Item(meta.id, meta.name, folder, candidate.mime, candidate.size, versionId = version.id, createdAt = candidate.date), version, "", "")
        notice("Imported the selected reference. The original message and caption were not modified.")
    }
    fun offerTrashUndo(ids:List<String>){mutable.update{it.copy(messageRevision=it.messageRevision+1,notice="Moved ${ids.size} files to Trash",undoTrashIds=ids)}}
    fun notice(message: String) { mutable.update { it.copy(messageRevision=it.messageRevision+1,notice = message) } }
    fun report(error: Throwable) { if (error is CancellationException) throw error; mutable.update { it.copy(messageRevision=it.messageRevision+1,error = error.message?.take(300) ?: "Operation failed.") } }
    fun clearNotice() { mutable.update { it.copy(error = null, notice = null, undoTrashIds=emptyList()) } }
    fun acknowledgeNotice(revision:Long,error:String?,notice:String?) { mutable.update {
        if(it.messageRevision!=revision)return@update it
        it.copy(error=if(it.error==error)null else it.error,notice=if(it.notice==notice)null else it.notice,
            undoTrashIds=if(it.notice==notice)emptyList()else it.undoTrashIds)
    } }
    fun clearShare() { mutable.update{it.copy(lastShare=null)} }
    fun shareResult(result: ShareResult) { mutable.update { it.copy(lastShare = result) } }
    fun setOrganization(value: List<OrganizationChange>) { mutable.update { it.copy(organization = value) } }
    fun clearImports() { mutable.update { it.copy(incoming = emptyList()) } }
}
