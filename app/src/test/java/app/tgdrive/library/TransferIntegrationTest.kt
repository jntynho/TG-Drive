package app.tgdrive.library

import android.net.Uri
import app.tgdrive.data.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.test.resetMain
import org.drinkless.tdlib.TdApi
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File

/** Real repository/SQLite/queue/cache with a fake Telegram wire; never contacts a real account. */
@OptIn(ExperimentalCoroutinesApi::class,DelicateCoroutinesApi::class,ObsoleteCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28],manifest=Config.NONE)
class TransferIntegrationTest {
    private val context get()=RuntimeEnvironment.getApplication()
    private lateinit var dispatcher:ExecutorCoroutineDispatcher
    private lateinit var scope:CoroutineScope
    private lateinit var wire:Wire
    private lateinit var drive:TelegramDrive
    private lateinit var repo:LibraryRepository
    private lateinit var engine:TransferEngine
    @Before fun prepare() {
        dispatcher=newSingleThreadContext("library-main");Dispatchers.setMain(dispatcher)
        scope=CoroutineScope(SupervisorJob()+dispatcher)
        context.deleteDatabase("library-v2-1701.db");context.deleteDatabase("library-v2-1702.db")
        File(context.filesDir,"index-1701.json").delete()
        File(context.cacheDir,"library/1701").deleteRecursively()
        File(context.filesDir,"offline/1701").deleteRecursively()
        wire=Wire(File(context.cacheDir,"test-wire").apply{deleteRecursively();mkdirs()})
        drive=TelegramDrive(context,MemorySettings(),wire,scope)
        repo=LibraryRepository(context,drive,scope,{PlainRows})
        engine=TransferEngine(repo,scope,gateOverride={null})
    }
    private suspend fun open() {
        drive.configure("12345","a".repeat(32))
        withTimeout(5_000){while(wire.update==null)delay(5)}
        wire.update!!(TdApi.UpdateAuthorizationState(TdApi.AuthorizationStateReady()))
        withTimeout(10_000){repo.awaitReady()}
        repo.setPreferences(repo.state.value.preferences.copy(autoSync=false,chunkMiB=1))
    }
    @After fun close()=runBlocking {scope.cancel();scope.coroutineContext[Job]?.join();repo.database?.close();Dispatchers.resetMain();dispatcher.close()}
    private fun source(bytes:ByteArray,name:String="payload.bin")=Uri.fromFile(File(context.cacheDir,name).apply{writeBytes(bytes)})
    @Test fun `preference edits persist without republishing library or trimming cache`()=runBlocking {
        open()
        val snapshot=repo.state.value.items
        val cacheFile=File(repo.cache!!.root,"retained.tmp").apply{writeText("retained")}
        repo.updatePreferences{it.copy(wifiOnly=true)}
        repo.updatePreferences{it.copy(chargingOnly=true)}
        repo.updatePreferences{it.copy(wifiOnly=false)}
        assertSame(snapshot,repo.state.value.items)
        assertFalse(repo.db().preferences().wifiOnly)
        assertTrue(repo.db().preferences().chargingOnly)
        assertTrue(cacheFile.exists())
    }
    @Test fun `single document uploads embed metadata and preserve completion callbacks`()=runBlocking {
        open();var completed=0;engine.completed={completed++}
        engine.enqueueUpload((1..5).map{source("content-$it".toByteArray(),"file-$it.bin")});engine.drain(20_000)
        assertEquals(5,repo.state.value.tasks.count{it.state==TaskState.COMPLETED})
        assertEquals(5,completed)
        assertTrue(repo.state.value.tasks.drop(1).any{it.payload.model<UploadSpec>().preparedSource})
        val messages=wire.messages.values.mapNotNull{(it.content as? TdApi.MessageText)?.text?.text}
        assertTrue(messages.isEmpty())
        val documents=wire.messages.values.mapNotNull{it.content as? TdApi.MessageDocument}
        assertEquals(5,documents.size)
        assertTrue(documents.all{it.caption.text.startsWith(EmbeddedUpload.PREFIX)})
        assertEquals(5,wire.sendCalls)
        engine.drain(20_000);assertEquals(5,completed)
    }
    @Test fun `purging one embedded file retains sibling messages and deletion marker`()=runBlocking {
        open()
        engine.enqueueUpload(listOf(source("one".toByteArray(),"one.bin"),source("two".toByteArray(),"two.bin")));engine.drain(20_000)
        val first=repo.state.value.items.first()
        val survivor=repo.state.value.items.first{it.id!=first.id}
        val actions=LibraryActions(repo,engine);actions.trash(listOf(first.id));actions.purge(listOf(first.id))
        val cloud=wire.messages.values.flatMap{m->
            val text=(m.content as? TdApi.MessageText)?.text?.text
            val doc=m.content as? TdApi.MessageDocument
            if(text!=null)CloudCodec.decodeMany(text)else if(doc?.caption?.text?.startsWith(EmbeddedUpload.PREFIX)==true)EmbeddedUpload.resolve(doc.caption.text,m.id,doc.document.document.size)else emptyList()
        }
        assertTrue(cloud.any{it.entityId==survivor.id})
        assertTrue(cloud.filter{it.entityId==first.id}.all{it.tombstone})
    }
    @Test fun `legacy multipart metadata batch relocates surviving sibling operations before purge`()=runBlocking {
        open()
        engine.enqueueUpload(listOf(source(ByteArray(1_100_000){1},"legacy-one.bin"),source(ByteArray(1_100_000){2},"legacy-two.bin")))
        engine.drain(20_000)
        val original=repo.item(repo.state.value.tasks.single{it.source.endsWith("/legacy-one.bin")}.itemId);val sibling=repo.state.value.items.first{it.id!=original.id}
        assertEquals(1,wire.messages.values.count{(it.content as? TdApi.MessageText)?.text?.text?.startsWith(CloudCodec.BATCH)==true})
        val actions=LibraryActions(repo,engine);actions.trash(listOf(original.id));actions.purge(listOf(original.id))
        val ops=wire.messages.values.flatMap{(it.content as? TdApi.MessageText)?.text?.text?.let(CloudCodec::decodeMany)?:emptyList()}
        assertTrue(ops.any{it.entityId==sibling.id});assertTrue(ops.filter{it.entityId==original.id}.all{it.tombstone})
        assertArrayEquals(ByteArray(1_100_000){2},repo.cache!!.materialize(sibling,repo.version(sibling.versionId),"").readBytes())
    }
    @Test fun `metadata failure retains content and retries without uploading it again`()=runBlocking {
        open();wire.failMetadata=true
        engine.enqueueUpload(listOf(source(ByteArray(1_100_000){42})))
        assertTrue(runCatching{engine.drain(20_000)}.isFailure)
        assertEquals(TaskState.SYNCING_METADATA,repo.state.value.tasks.single().state)
        assertEquals(2,wire.uploadedIndices.size)
        wire.failMetadata=false;engine.drain(20_000)
        assertEquals(TaskState.COMPLETED,repo.state.value.tasks.single().state)
        assertEquals(2,wire.uploadedIndices.size)
    }
    @Test fun `server flood wait applies to the account and manual retry cannot bypass it`()=runBlocking {
        open();wire.sendWait=160
        engine.enqueueUpload(listOf(source("first".toByteArray()),source("second".toByteArray())))
        engine.drain(20_000)
        assertEquals(1,wire.sendCalls)
        assertTrue(repo.state.value.sendCooldownUntil>now()+150_000)
        assertEquals(repo.state.value.sendCooldownUntil,repo.db().get("settings","sendCooldownUntil")!!.toLong())
        engine.retry(repo.state.value.tasks.first().id);engine.drain(20_000)
        assertEquals(1,wire.sendCalls)
        assertTrue(repo.state.value.tasks.none{it.state==TaskState.COMPLETED})
    }
    @Test fun `acknowledging batched ancestors does not invent edit conflicts`()=runBlocking {
        open()
        val id=repo.createFolder("First")
        repo.updateFolder(repo.state.value.folders.single{it.id==id}.copy(name="Renamed"))
        repo.flush()
        assertEquals("Renamed",repo.state.value.folders.single{it.id==id}.name)
        assertTrue(repo.state.value.conflicts.isEmpty())
        assertTrue(repo.db().pending().isEmpty())
    }
    @Test fun `multipart upload commits once and restored bytes match original`()=runBlocking {
        open();val bytes=ByteArray(2_200_123){(it%251).toByte()}
        engine.enqueueUpload(listOf(source(bytes)));engine.drain(20_000)
        assertEquals(TaskState.COMPLETED,repo.state.value.tasks.single().state)
        val item=repo.state.value.items.single();val version=repo.version(item.versionId)
        assertEquals(3,version.parts.size);assertEquals(bytes.size.toLong(),version.parts.sumOf{it.plainSize})
        val restored=repo.cache!!.materialize(item,version,"")
        assertArrayEquals(bytes,restored.readBytes());assertEquals(item.sha256,restored.sha256())
        assertTrue(repo.db().pending().isEmpty())
    }
    @Test fun `interruption retains completed parts and retry sends only remaining parts`()=runBlocking {
        open();wire.failPart=1
        engine.enqueueUpload(listOf(source(ByteArray(2_100_123){7})));engine.drain(20_000)
        var task=repo.state.value.tasks.single()
        assertEquals(TaskState.RETRY_WAIT,task.state);assertEquals(1,task.parts.size)
        val firstMessage=task.parts.first().messageId
        wire.failPart=null;engine.retry(task.id);engine.drain(20_000)
        task=repo.state.value.tasks.single();assertEquals(TaskState.COMPLETED,task.state)
        assertEquals(firstMessage,repo.state.value.versions.single().parts.first().messageId)
        assertEquals(1,wire.uploadedIndices.count{it==0})
    }
    @Test fun `source mutation after interruption fails without publishing a corrupt item`()=runBlocking {
        open();wire.failPart=1;val uri=source(ByteArray(1_100_000){1})
        engine.enqueueUpload(listOf(uri));engine.drain(20_000)
        val task=repo.state.value.tasks.single()
        File(context.filesDir,"transfer-staging/${task.profileId}/${task.id}/source").deleteRecursively()
        File(uri.path!!).writeBytes(ByteArray(1_100_000){2})
        wire.failPart=null;engine.retry(repo.state.value.tasks.single().id);engine.drain(20_000)
        assertEquals(TaskState.FAILED,repo.state.value.tasks.single().state)
        assertTrue(repo.state.value.items.isEmpty());assertEquals(1,wire.uploadedIndices.count{it==0})
    }
    @Test fun `duplicate skip retains source hash and original item identity`()=runBlocking {
        open();val uri=source("same content".toByteArray())
        engine.enqueueUpload(listOf(uri));engine.drain(20_000)
        val original=repo.state.value.items.single()
        engine.enqueueUpload(listOf(uri));engine.drain(20_000)
        assertEquals(1,repo.state.value.items.size)
        val skipped=repo.state.value.tasks.last()
        assertEquals(TaskState.COMPLETED,skipped.state);assertEquals(original.id,skipped.itemId);assertEquals(original.sha256,skipped.sourceSha256)
        assertEquals(1,wire.uploadedIndices.size)
    }
    @Test fun `legacy metadata migrates without reupload and never claims unknown original hash`()=runBlocking {
        val legacy=DriveFile(FileMetadata.create("old.pdf","Archive"),900,12,123,100,"application/pdf")
        IndexStore(context,1701).write(listOf(legacy),setOf("Archive","Empty"));open()
        val item=repo.state.value.items.single();val version=repo.version(item.versionId)
        assertEquals(900L,version.parts.single().messageId);assertTrue(version.imported);assertEquals("",version.sha256)
        assertEquals(setOf("Archive","Empty"),repo.state.value.folders.map{it.name}.toSet());assertTrue(wire.uploadedIndices.isEmpty())
    }
    @Test fun `bad cloud metadata cannot poison valid records in the same scan`()=runBlocking {
        open();val item=Item(id="valid-item",name="valid.txt")
        wire.history=listOf(wire.text(9001,CloudCodec.PREFIX+"not-base64"),wire.text(9000,CloudCodec.encode(Envelope(kind=EntityKind.ITEM,entityId=item.id,deviceId="test",body=item.json()))))
        repo.sync(full=true)
        assertEquals("valid.txt",repo.state.value.items.single().name);assertNotNull(repo.state.value.error)
    }
    @Test fun `changed destination settings do not redirect a queued export`()=runBlocking {
        open();repo.setPreferences(repo.state.value.preferences.copy(webDavUrl="https://original.example/dav",webDavUser="user"))
        repo.saveAccountSecret("webdav-password","test-only-password")
        val item=Item(name="x",versionId="version");repo.saveUploaded(item,Version(id=item.versionId,itemId=item.id,size=0,sha256="",parts=emptyList()),"","")
        val id=engine.enqueue(TaskKind.WEBDAV,item)
        repo.updatePreferences{it.copy(webDavUrl="https://different.example/")}
        val binding=repo.accountSecret("connection:$id")!!
        assertTrue(binding.contains("original.example"));assertFalse(binding.contains("different.example"))
    }
    @Test fun `permanent delete refuses a live transfer and preserves the original`()=runBlocking {
        open();engine.enqueueUpload(listOf(source("original".toByteArray())));engine.drain(20_000)
        val actions=LibraryActions(repo,engine);val item=repo.state.value.items.single()
        actions.trash(listOf(item.id));engine.enqueue(TaskKind.VERIFY,repo.item(item.id))
        assertTrue(runCatching{actions.purge(listOf(item.id))}.isFailure)
        assertTrue(wire.deleted.isEmpty());assertEquals(item.versionId,repo.item(item.id).versionId)
    }
    @Test fun `corrupt downloaded bytes never produce a verified preview`()=runBlocking {
        open();engine.enqueueUpload(listOf(source("known bytes".toByteArray())));engine.drain(20_000)
        val item=repo.state.value.items.single();val version=repo.version(item.versionId)
        val remote=wire.files[version.parts.single().messageId.toInt()]!!
        File(remote.local.path).writeBytes("wrong bytes".toByteArray())
        assertTrue(runCatching{repo.cache!!.materialize(item,version,"")}.isFailure)
        assertFalse(repo.cache!!.plaintextPath(item).exists());assertEquals("",repo.db().local(item.id).verifiedVersion)
    }
    @Test fun `cache trim preserves leased previews and independent offline data`()=runBlocking {
        open();engine.enqueueUpload(listOf(source("pinned bytes".toByteArray())));engine.drain(20_000)
        val item=repo.state.value.items.single();val version=repo.version(item.versionId);val cache=repo.cache!!
        val file=cache.materialize(item,version,"",pin=true)
        cache.lease(version.id).use{cache.trim(0);assertTrue(file.exists());assertTrue(cache.isOffline(version))}
        cache.trim(0);assertFalse(file.exists());assertTrue(cache.isOffline(version))
    }
    @Test fun `permanent delete writes tombstones that reject old checkpoints`()=runBlocking {
        open();engine.enqueueUpload(listOf(source("delete sample".toByteArray())));engine.drain(20_000)
        val old=repo.db().entities();val item=repo.state.value.items.single();val actions=LibraryActions(repo,engine)
        actions.trash(listOf(item.id));actions.purge(listOf(item.id))
        repo.mutate{db->old.forEach{db.apply(JournalEntry(it,pending=true))}}
        assertEquals("",repo.item(item.id).versionId);assertTrue(repo.state.value.versions.isEmpty())
        assertFalse(repo.db().all("journal").any{it.contains("delete sample")})
    }
    @Test fun `encrypted multipart restore needs the vault and returns exact original bytes`()=runBlocking {
        open();repo.createVault("Private","long integration password".toCharArray())
        val vault=repo.state.value.vaults.single().id;val bytes=ByteArray(1_200_123){(it%199).toByte()}
        engine.enqueueUpload(listOf(source(bytes)),vault=vault);engine.drain(20_000)
        val item=repo.state.value.items.single();val version=repo.version(item.versionId)
        assertTrue(version.parts.all{it.encrypted})
        assertArrayEquals(bytes,repo.cache!!.materialize(item,version,vault).readBytes())
        repo.lockVaults();assertTrue(repo.state.value.items.isEmpty());assertFalse(repo.cache!!.plaintextPath(item).exists())
        assertTrue(runCatching{repo.cache!!.materialize(item,version,vault)}.isFailure)
    }
    @Test fun `priority starts the urgent queued file before ordinary queued files`()=runBlocking {
        open();repo.updatePreferences{it.copy(uploadPaceKiB=1024)}
        engine.enqueueUpload(listOf(source("first".toByteArray(),"first.bin"),source("urgent".toByteArray(),"urgent.bin")))
        val urgent=repo.state.value.tasks.last();engine.prioritize(urgent.id);engine.drain(20_000)
        val first=wire.messages.values.first{it.content is TdApi.MessageDocument}
        val doc=first.content as TdApi.MessageDocument
        assertEquals(urgent.itemId,EmbeddedUpload.resolve(doc.caption.text,first.id,doc.document.document.size).last().entityId)
    }
    @Test fun `average pacing checkpoints a wait without falsely completing and can be disabled`()=runBlocking {
        open();repo.updatePreferences{it.copy(uploadPaceKiB=1)}
        engine.enqueueUpload(listOf(source(ByteArray(3000){9})));engine.drain(20_000)
        val task=repo.state.value.tasks.single();assertEquals(TaskState.RETRY_WAIT,task.state);assertTrue(task.nextAttemptAt>now());assertTrue(repo.state.value.items.isEmpty())
        repo.updatePreferences{it.copy(uploadPaceKiB=0)};engine.retry(task.id);engine.drain(20_000)
        assertEquals(TaskState.COMPLETED,repo.state.value.tasks.single().state);assertEquals(1,wire.uploadedIndices.size)
    }
    @Test fun `drain deadline bounds an unresponsive native request and retains retry state`()=runBlocking {
        open();wire.stall=true;engine.enqueueUpload(listOf(source("timeout".toByteArray())))
        val start=now();engine.drain(400)
        assertTrue(now()-start<3_000);assertEquals(TaskState.RETRY_WAIT,repo.state.value.tasks.single().state);assertTrue(repo.state.value.items.isEmpty())
    }
    @Test fun `library initialization can retry without another auth update or data reset`()=runBlocking {
        var fail=true
        val candidate=LibraryRepository(context,drive,scope,rowCodec={if(fail)error("simulated local key access failure")else PlainRows})
        open();withTimeout(5_000){while(candidate.state.value.error==null)delay(5)}
        assertFalse(candidate.state.value.loaded)
        fail=false;candidate.retryInitialization();candidate.awaitReady()
        assertTrue(candidate.state.value.loaded);assertEquals(1701L,candidate.state.value.accountId)
        candidate.database?.close();Unit
    }
    @Test fun `pausing a download cancels the matching native transfer`()=runBlocking {
        open();engine.enqueueUpload(listOf(source("download sample".toByteArray())));engine.drain(20_000)
        val item=repo.state.value.items.single();wire.stallDownload=true
        val id=engine.enqueue(TaskKind.VERIFY,item)
        val running=launch{engine.drain(20_000)}
        withTimeout(5_000){while(!wire.downloading)delay(5)}
        engine.pause(id);running.join()
        assertEquals(TaskState.PAUSED,engine.task(id).state)
        assertTrue(wire.cancelledDownloads.contains(repo.version(item.versionId).parts.single().messageId.toInt()))
    }
    @Test fun `backup duplicate detection does not treat a trashed version as a live backup`()=runBlocking {
        open();val bytes="backup bytes".toByteArray()
        engine.enqueueUpload(listOf(source(bytes,"original.bin")));engine.drain(20_000)
        val original=repo.state.value.items.single();LibraryActions(repo,engine).trash(listOf(original.id))
        engine.enqueueUpload(listOf(source(bytes,"new-source.bin")),ruleId="backup-rule");engine.drain(20_000)
        val result=repo.state.value.tasks.last()
        assertEquals(TaskState.COMPLETED,result.state);assertNotEquals(original.id,result.itemId)
        assertEquals(2,wire.uploadedIndices.size);assertEquals(0L,repo.item(result.itemId).deletedAt)
    }
    @Test fun `purged content cannot cause a later source to be falsely marked backed up`()=runBlocking {
        open();val bytes="purge backup".toByteArray()
        engine.enqueueUpload(listOf(source(bytes,"removed.bin")));engine.drain(20_000)
        val original=repo.state.value.items.single();val actions=LibraryActions(repo,engine)
        actions.trash(listOf(original.id));actions.purge(listOf(original.id))
        assertEquals("",repo.item(original.id).sha256)
        engine.enqueueUpload(listOf(source(bytes,"different-source.bin")),ruleId="backup-rule");engine.drain(20_000)
        val result=repo.state.value.tasks.last();assertNotEquals(original.id,result.itemId)
        assertEquals(2,wire.uploadedIndices.size)
        val restored=repo.cache!!.materialize(repo.item(result.itemId),repo.version(repo.item(result.itemId).versionId),"")
        assertArrayEquals(bytes,restored.readBytes())
    }
    @Test fun `missing remote messages cannot cause false duplicate completion`()=runBlocking {
        open();val bytes="missing duplicate".toByteArray()
        engine.enqueueUpload(listOf(source(bytes,"missing.bin")));engine.drain(20_000)
        val original=repo.state.value.items.single()
        repo.version(original.versionId).parts.forEach{wire.messages.remove(it.messageId)}
        engine.enqueueUpload(listOf(source(bytes,"replacement-source.bin")));engine.drain(20_000)
        val result=repo.state.value.tasks.last()
        assertEquals(TaskState.COMPLETED,result.state);assertNotEquals(original.id,result.itemId)
        assertTrue(repo.db().local(original.id).missing);assertEquals(2,wire.uploadedIndices.size)
    }
    @Test fun `local cleanup refuses a cloud copy changed after earlier verification`()=runBlocking {
        open();val uri=source("original".toByteArray(),"keep-original.bin")
        engine.enqueueUpload(listOf(uri));engine.drain(20_000)
        val item=repo.state.value.items.single();engine.enqueue(TaskKind.VERIFY,item);engine.drain(20_000)
        assertEquals(item.versionId,repo.db().local(item.id).verifiedVersion)
        val part=repo.version(item.versionId).parts.single()
        File(wire.files[part.messageId.toInt()]!!.local.path).writeBytes("modified".toByteArray())
        assertTrue(runCatching{LibraryActions(repo,engine).validateLocalDeletion(listOf(item.id))}.isFailure)
        assertEquals("original",File(uri.path!!).readText())
    }
    @Test fun `connection credentials stay with their Telegram user when a profile signs in to another account`()=runBlocking {
        open();repo.saveAccountSecret("webdav-password","first-account-password")
        repo.saveAccountSecret("sharing-token","first-account-owner-token")
        suspend fun switchTo(id:Long) {
            wire.userId=id;wire.update!!(TdApi.UpdateAuthorizationState(TdApi.AuthorizationStateReady()))
            withTimeout(5_000){while(repo.state.value.accountId!=id||!repo.state.value.loaded)delay(5)}
        }
        switchTo(1702)
        assertNull(repo.accountSecret("webdav-password"));assertNull(repo.accountSecret("sharing-token"))
        repo.saveAccountSecret("webdav-password","second-account-password")
        switchTo(1701)
        assertEquals("first-account-password",repo.accountSecret("webdav-password"))
        assertEquals("first-account-owner-token",repo.accountSecret("sharing-token"))
    }
    @Test fun `embedded message alone restores library and repeated scan is idempotent`()=runBlocking {
        open();val bytes="self-contained original".toByteArray()
        engine.enqueueUpload(listOf(source(bytes,"photo.jpg")));engine.drain(20_000)
        val item=repo.state.value.items.single()
        LibraryActions(repo,engine).rename(item.id,"Renamed.jpg");repo.flush()
        val messages=wire.messages.values.sortedByDescending{it.id}
        repo.mutate{db->db.entries("journal").forEach{db.remove("journal",it.first)};db.entries("entities").forEach{db.remove("entities",it.first)}}
        assertTrue(repo.state.value.items.isEmpty())
        wire.history=messages;repo.sync(full=true);repo.sync(full=true)
        assertEquals(item.id,repo.state.value.items.single().id)
        assertEquals("Renamed.jpg",repo.item(item.id).name)
        assertTrue(repo.state.value.conflicts.isEmpty());assertTrue(repo.db().pending().isEmpty())
        assertArrayEquals(bytes,repo.cache!!.materialize(repo.item(item.id),repo.version(item.versionId),"").readBytes())
    }
    @Test fun `embedded acknowledgement is recovered without another content send`()=runBlocking {
        open();engine.enqueueUpload(listOf(source("recover me".toByteArray())));engine.drain(20_000)
        val complete=repo.state.value.tasks.single();val caption=complete.payload.model<UploadSpec>().embeddedCaption
        assertTrue(caption.isNotEmpty());val messageId=complete.parts.single().messageId
        repo.setTask(complete.copy(state=TaskState.PAUSED,parts=emptyList(),pendingMessageId=messageId,done=0,completionNotified=false))
        engine.retry(complete.id);engine.drain(20_000)
        assertEquals(1,wire.uploadedIndices.size);assertEquals(messageId,repo.state.value.tasks.single().parts.single().messageId)
        assertTrue(repo.state.value.conflicts.isEmpty())
    }
    @Test fun `two contents overlap and a paused upload does not cancel its sibling`()=runBlocking {
        open();wire.holdUploads=true
        engine.enqueueUpload(listOf(source("first native upload".toByteArray(),"a.jpg"),source("second native upload".toByteArray(),"b.jpg"),source("third native upload".toByteArray(),"c.jpg")))
        val running=launch{engine.drain(20_000)}
        withTimeout(5_000){while(wire.inFlightUploads.size<2)delay(10)}
        assertEquals(2,wire.maxConcurrentUploads);assertEquals(0,wire.sendCalls)
        val first=repo.state.value.tasks.first{it.state==TaskState.TRANSFERRING}
        val originalText=File(Uri.parse(first.source).path!!).readText()
        val firstNative=wire.files.values.first{File(it.local.path).readText()==originalText}.id
        engine.pause(first.id)
        assertTrue(firstNative in wire.cancelledUploads)
        assertEquals(TaskState.PAUSED,engine.task(first.id).state)
        wire.holdUploads=false;wire.readyUploads.addAll(wire.inFlightUploads)
        running.join()
        assertEquals(2,repo.state.value.tasks.count{it.state==TaskState.COMPLETED})
        assertEquals(TaskState.PAUSED,engine.task(first.id).state)
        assertTrue(wire.maxConcurrentUploads<=2)
        engine.retry(first.id);engine.drain(20_000)
        assertEquals(3,repo.state.value.tasks.count{it.state==TaskState.COMPLETED})
        assertEquals(3,wire.uploadedIndices.size)
        repo.state.value.items.forEach{item->assertEquals(item.sha256,repo.cache!!.materialize(item,repo.version(item.versionId),"").sha256())}
    }
    @Test fun `simultaneous identical sources publish only one content message`()=runBlocking {
        open();engine.enqueueUpload(listOf(source("same simultaneous bytes".toByteArray(),"one.dat"),source("same simultaneous bytes".toByteArray(),"two.dat")))
        engine.drain(20_000)
        assertEquals(1,wire.uploadedIndices.size);assertEquals(1,repo.state.value.items.size)
        assertEquals(2,repo.state.value.tasks.count{it.state==TaskState.COMPLETED})
    }
    @Test fun `a content stage flood wait freezes publishing and survives retry`()=runBlocking {
        open();wire.preliminaryWait=160
        engine.enqueueUpload(listOf(source("one".toByteArray(),"one.dat"),source("two".toByteArray(),"two.dat")))
        engine.drain(20_000)
        assertEquals(0,wire.sendCalls);assertTrue(repo.state.value.sendCooldownUntil>now()+150_000)
        wire.preliminaryWait=0
        repo.state.value.tasks.forEach{engine.retry(it.id)};engine.drain(20_000)
        assertEquals(0,wire.sendCalls);assertTrue(repo.state.value.items.isEmpty())
    }
    @Test fun `changed original does not alter a retained immutable snapshot`()=runBlocking {
        open();wire.failPart=1;val original=ByteArray(1_100_000){3};val uri=source(original)
        engine.enqueueUpload(listOf(uri));engine.drain(20_000)
        File(uri.path!!).writeBytes(ByteArray(original.size){4})
        wire.failPart=null;engine.retry(repo.state.value.tasks.single().id);engine.drain(20_000)
        val item=repo.state.value.items.single()
        assertArrayEquals(original,repo.cache!!.materialize(item,repo.version(item.versionId),"").readBytes())
        assertEquals(1,wire.uploadedIndices.count{it==0})
    }
    @Test fun `tampered private snapshot is rejected before another part is sent`()=runBlocking {
        open();wire.failPart=1
        engine.enqueueUpload(listOf(source(ByteArray(1_100_000){1})));engine.drain(20_000)
        val task=repo.state.value.tasks.single()
        File(context.filesDir,"transfer-staging/${task.profileId}/${task.id}/source/${task.title}").writeBytes(ByteArray(1_100_000){2})
        wire.failPart=null;engine.retry(task.id);engine.drain(20_000)
        assertEquals(TaskState.FAILED,engine.task(task.id).state)
        assertTrue(repo.state.value.items.isEmpty());assertEquals(1,wire.uploadedIndices.size)
    }
    @Test fun `account switch revokes both uploads without publishing into the new account`()=runBlocking {
        open();wire.holdUploads=true
        engine.enqueueUpload(listOf(source("account first".toByteArray(),"account-a.bin"),source("account second".toByteArray(),"account-b.bin")))
        val running=launch{engine.drain(20_000)}
        withTimeout(5_000){while(wire.inFlightUploads.size<2)delay(10)}
        wire.userId=1702;wire.update!!(TdApi.UpdateAuthorizationState(TdApi.AuthorizationStateReady()))
        withTimeout(5_000){while(repo.state.value.accountId!=1702L||!repo.state.value.loaded)delay(10)}
        running.join()
        assertEquals(0,wire.sendCalls);assertTrue(repo.state.value.items.isEmpty())
        wire.holdUploads=false
        wire.userId=1701;wire.update!!(TdApi.UpdateAuthorizationState(TdApi.AuthorizationStateReady()))
        withTimeout(5_000){while(repo.state.value.accountId!=1701L||!repo.state.value.loaded)delay(10)}
        repo.state.value.tasks.forEach{engine.retry(it.id)};engine.drain(20_000)
        assertEquals(2,repo.state.value.items.size);assertEquals(2,wire.sendCalls)
    }
    @Test fun backupReceiptAcknowledgesOnlyTheRevisionActuallyUploaded()=runBlocking {
        open()
        val file=File(context.cacheDir,"audited-source.bin").apply{writeBytes(ByteArray(128){1});setLastModified(System.currentTimeMillis()-60_000)}
        val provider=AuditDocumentsProvider(file)
        provider.attachInfo(context,android.content.pm.ProviderInfo().apply{authority="audit.documents"})
        org.robolectric.shadows.ShadowContentResolver.registerProviderInternal("audit.documents",provider)
        val uri=Uri.parse("content://audit.documents/document/source")
        repo.setRule(BackupRule(id="audit-rule",name="Audit backup",treeUri="content://audit.documents/tree/root"))
        val backups=BackupManager(repo,engine);engine.completed={backups.onCompleted(it)}
        wire.holdUploads=true
        engine.enqueueUpload(listOf(uri),ruleId="audit-rule")
        val running=launch{engine.drain(20_000)}
        withTimeout(5_000){while(wire.inFlightUploads.isEmpty())delay(10)}
        file.writeBytes(ByteArray(128){2});file.setLastModified(System.currentTimeMillis()+5_000)
        wire.holdUploads=false;wire.readyUploads.addAll(wire.inFlightUploads)
        running.join()
        val receipt=repo.db().get("sources",BackupManager.sourceKey(uri.toString()))!!.model<SourceReceipt>()
        assertEquals(TaskState.COMPLETED,repo.state.value.tasks.single().state)
        assertEquals(file.length(),receipt.size)
        assertNotEquals(file.lastModified(),receipt.modifiedAt)
        assertNotEquals(file.sha256(),receipt.hash)
        // The next scan must not skip the edited revision, even when its byte count is unchanged.
        assertFalse(receipt.size==file.length()&&receipt.modifiedAt==file.lastModified())
    }
    private class AuditDocumentsProvider(val source:File):android.content.ContentProvider() {
        override fun onCreate()=true
        override fun getType(uri:Uri)="application/octet-stream"
        override fun query(uri:Uri,projection:Array<out String>?,selection:String?,selectionArgs:Array<out String>?,sortOrder:String?):android.database.Cursor {
            val columns=projection?:arrayOf("_display_name","_size","last_modified","mime_type","document_id")
            return android.database.MatrixCursor(columns).apply{addRow(columns.map{column->when(column){"_display_name"->source.name;"_size"->source.length();"last_modified"->source.lastModified();"mime_type"->"application/octet-stream";"document_id"->"source";else->null}})}
        }
        override fun openFile(uri:Uri,mode:String)=android.os.ParcelFileDescriptor.open(source,android.os.ParcelFileDescriptor.MODE_READ_ONLY)
        override fun insert(uri:Uri,values:android.content.ContentValues?):Uri?=null
        override fun update(uri:Uri,values:android.content.ContentValues?,selection:String?,selectionArgs:Array<out String>?)=0
        override fun delete(uri:Uri,selection:String?,selectionArgs:Array<out String>?)=0
    }
    @Test fun recoveryKeyRemainsAvailableUntilExplicitAcknowledgement()=runBlocking {
        open();val key=repo.createVault("Personal vault","a long test password".toCharArray())
        val vault=repo.state.value.vaults.single()
        assertEquals(key,repo.accountSecret("pending-recovery:${vault.id}"))
        repo.lockVaults()
        assertEquals(key,repo.accountSecret("pending-recovery:${vault.id}"))
        assertTrue(repo.db().entities().none{it.body.contains(key)})
        repo.saveAccountSecret("pending-recovery:${vault.id}","")
        assertEquals("",repo.accountSecret("pending-recovery:${vault.id}"))
    }
    @Test fun acknowledgementCannotDismissANewerIdenticalNoticeOrPublicLink()=runBlocking {
        open();repo.notice("Saved");val displayed=repo.state.value
        repo.shareResult(ShareResult("link","https://example.invalid/link",123))
        repo.notice("Saved")
        repo.acknowledgeNotice(displayed.messageRevision,displayed.error,displayed.notice)
        assertEquals("Saved",repo.state.value.notice)
        assertNotNull(repo.state.value.lastShare)
        val latest=repo.state.value;repo.acknowledgeNotice(latest.messageRevision,latest.error,latest.notice)
        assertNull(repo.state.value.notice);assertNotNull(repo.state.value.lastShare)
    }
    @Test fun scanUpdatesCannotOverwriteANewRuleToggle()=runBlocking {
        open();val rule=BackupRule(name="Folder",treeUri="content://test/tree/root")
        repo.setRule(rule);repo.setRuleEnabled(rule.id,false)
        repo.updateRule(rule.id){it.copy(lastScanAt=123,error="Folder permission unavailable")}
        assertFalse(repo.state.value.rules.single().enabled)
        assertEquals(123L,repo.state.value.rules.single().lastScanAt)
        repo.setRuleEnabled(rule.id,true)
        assertEquals("Folder permission unavailable",repo.state.value.rules.single().error)
    }
    @Test fun restoredFolderDraftDoesNotCreateTwoFolders()=runBlocking {
        open();repo.createFolder("Documents",id="same-draft");repo.createFolder("Documents",id="same-draft")
        assertEquals(1,repo.state.value.folders.size)
    }
    @Test fun importingTheSameMessageTwiceDoesNotCreateDuplicateRows()=runBlocking {
        open();val candidate=ImportCandidate(123,"existing.txt","text/plain",25,1000)
        repo.importMessage(candidate,"");repo.importMessage(candidate,"")
        assertEquals(1,repo.state.value.items.size)
    }
    private class MemorySettings:SessionSettings {
        private val values=java.util.concurrent.ConcurrentHashMap<String,String>()
        override fun get(name:String)=values[name]
        override fun put(name:String,value:String){values[name]=value}
        override fun databaseKey()=ByteArray(32)
    }
    private class Wire(val directory:File):TdConnectionFactory {
        @Volatile var update:((TdApi.Object)->Unit)?=null
        var failMetadata=false;var sendWait=0;var sendCalls=0
        var userId=1701L;var next=100L;var failPart:Int?=null;var stall=false;var stallDownload=false
        @Volatile var downloading=false
        val cancelledDownloads=mutableListOf<Int>()
        val cancelledUploads=mutableListOf<Int>()
        var holdUploads=false;var preliminaryWait=0
        var maxConcurrentUploads=0
        val inFlightUploads=java.util.concurrent.ConcurrentHashMap.newKeySet<Int>()
        val readyUploads=java.util.concurrent.ConcurrentHashMap.newKeySet<Int>()
        val messages=mutableMapOf<Long,TdApi.Message>();val files=mutableMapOf<Int,TdApi.File>()
        val uploadedIndices=mutableListOf<Int>();val deleted=mutableListOf<Long>();var history=emptyList<TdApi.Message>()
        override fun create(onUpdate:(TdApi.Object)->Unit,onError:()->Unit):TdConnection {
            update=onUpdate
            return object:TdConnection {override fun send(function:TdApi.Function<*>,result:(TdApi.Object)->Unit) {
                if(function is TdApi.SendMessage){sendCalls++;if(sendWait>0){result(TdApi.Error(429,"Too Many Requests: retry after $sendWait"));return}}
                if(failMetadata && function is TdApi.SendMessage && function.inputMessageContent is TdApi.InputMessageText){result(TdApi.Error(500,"metadata temporary failure"));return}
                if(function is TdApi.DownloadFile&&stallDownload){downloading=true;return}
                if(stall&&function is TdApi.SendMessage&&function.inputMessageContent is TdApi.InputMessageDocument)return
                val answer:TdApi.Object=when(function){
                    is TdApi.GetMe->TdApi.User().apply{id=this@Wire.userId;firstName="Test"}
                    is TdApi.CreatePrivateChat->TdApi.Chat().apply{id=this@Wire.userId}
                    is TdApi.GetChatHistory->TdApi.Messages().apply{messages=if(function.fromMessageId==0L)history.toTypedArray() else emptyArray()}
                    is TdApi.GetMessage->messages[function.messageId]?:TdApi.Error(404,"message not found")
                    is TdApi.PreliminaryUploadFile->{
                        if(preliminaryWait>0)TdApi.Error(429,"Too Many Requests: retry after $preliminaryWait")else {
                            val source=File((function.file as TdApi.InputFileLocal).path)
                            val id=(++next).toInt();val copy=File(directory,"upload-$id.bin");source.copyTo(copy,true)
                            TdApi.File().apply{this.id=id;size=copy.length();expectedSize=size;local=TdApi.LocalFile().apply{path=copy.absolutePath;isDownloadingCompleted=true;downloadedSize=size};remote=TdApi.RemoteFile().apply{isUploadingActive=holdUploads;isUploadingCompleted=!holdUploads;uploadedSize=if(holdUploads)0 else size}}.also{
                                files[id]=it
                                if(holdUploads){inFlightUploads.add(id);maxConcurrentUploads=maxOf(maxConcurrentUploads,inFlightUploads.size)}
                            }
                        }
                    }
                    is TdApi.GetFile->files[function.fileId]?.also{file->
                        if(function.fileId in readyUploads){file.remote.isUploadingCompleted=true;file.remote.isUploadingActive=false;file.remote.uploadedSize=file.size;inFlightUploads.remove(function.fileId)}
                    }?:TdApi.Error(404,"file not found")
                    is TdApi.CancelPreliminaryUploadFile->{cancelledUploads.add(function.fileId);inFlightUploads.remove(function.fileId);TdApi.Ok()}
                    is TdApi.DownloadFile->files[function.fileId]!!
                    is TdApi.CancelDownloadFile->{cancelledDownloads.add(function.fileId);TdApi.Ok()}
                    is TdApi.DeleteMessages->{deleted.addAll(function.messageIds.toList());function.messageIds.forEach{messages.remove(it)};TdApi.Ok()}
                    is TdApi.SendMessage->when(val content=function.inputMessageContent){
                        is TdApi.InputMessageText->text(++next,content.text.text).also{messages[it.id]=it}
                        is TdApi.InputMessageDocument->{
                            val caption=content.caption.text
                            val part=if(caption.startsWith(CloudCodec.PART))caption.removePrefix(CloudCodec.PART).model<PartCaption>().index else if(caption.startsWith(EmbeddedUpload.PREFIX))0 else null
                            if(part==failPart&&part!=null)TdApi.Error(500,"simulated temporary failure") else {
                                if(part!=null)uploadedIndices.add(part)
                                val id=++next;val source=when(val input=content.document.document){is TdApi.InputFileId->File(files[input.id]!!.local.path);is TdApi.InputFileLocal->File(input.path);else->error("Unexpected input file") };val target=File(directory,"$id.bin");source.copyTo(target,true)
                                val native=TdApi.File().apply{this.id=id.toInt();size=target.length();expectedSize=size;local=TdApi.LocalFile().apply{path=target.absolutePath;isDownloadingCompleted=true;downloadedSize=size};remote=TdApi.RemoteFile()}
                                files[native.id]=native
                                TdApi.Message().apply{this.id=id;chatId=1701;this.content=TdApi.MessageDocument().apply{document=TdApi.Document().apply{fileName=target.name;mimeType="application/octet-stream";document=native};this.caption=TdApi.FormattedText(caption,emptyArray())}}.also{messages[id]=it}
                            }
                        }
                        else->TdApi.Error(400,"unsupported test content")
                    }
                    else->TdApi.Ok()
                }
                result(answer)
            }}
        }
        fun text(id:Long,text:String)=TdApi.Message().apply{this.id=id;chatId=1701;content=TdApi.MessageText().apply{this.text=TdApi.FormattedText(text,emptyArray())}}
    }
}
