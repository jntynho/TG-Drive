package app.tgdrive.library

import app.tgdrive.data.TelegramDrive
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.drinkless.tdlib.TdApi
import java.io.File
import java.io.RandomAccessFile
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

class TelegramStorage(val session: TelegramDrive,private val sender:TelegramSendCoordinator=TelegramSendCoordinator(),private val validateAccount:(Long)->Unit={}) {
    private fun account():Long=session.state.value.accountId.also{check(it!=0L){"Open your Telegram account first."};verify(it)}
    private fun verify(account:Long) {
        validateAccount(account)
        if(session.state.value.accountId!=account)throw CancellationException("Telegram account changed")
    }
    private suspend fun <T> bound(account:Long,block:suspend()->T):T {
        verify(account)
        return try{block().also{verify(account)}}catch(e:Exception){verify(account);throw e}
    }
    private val fileLocks = ConcurrentHashMap<Int, Mutex>()
    suspend fun document(messageId: Long): TdApi.MessageDocument {
        val owner=account()
        val message = bound(owner){session.call(TdApi.GetMessage(session.savedMessagesId, messageId))} as TdApi.Message
        return message.content as? TdApi.MessageDocument ?: error("The original document is no longer available.")
    }
    suspend fun sendDocument(source: File, caption: String, thumbnail:File?=null, pending: suspend (TdApi.Message) -> Unit = {}): TdApi.Message {
        val account=account()
        var nativeId=0
        var acknowledged=false
        try {
        val uploaded=sender.content {
            var file=bound(account){session.call(TdApi.PreliminaryUploadFile(TdApi.InputFileLocal(source.absolutePath),TdApi.FileTypeDocument(),16))} as TdApi.File
            nativeId=file.id
            currentCoroutineContext()[NativeFileObserver]?.started?.invoke(file.id)
            if(!file.remote.isUploadingCompleted)coroutineScope {
                val changes=Channel<TdApi.File>(Channel.CONFLATED)
                val subscription=launch(start=CoroutineStart.UNDISPATCHED){session.events.collect{update->
                    if(update is TdApi.UpdateFile&&update.file.id==nativeId)changes.trySend(update.file)
                }}
                try{
                    // Subscribe before refreshing to cover an update received before PreliminaryUploadFile returned.
                    file=bound(account){session.call(TdApi.GetFile(nativeId))} as TdApi.File
                    withTimeout(60*60*1000L){
                        var inactiveSince=0L
                        while(!file.remote.isUploadingCompleted){
                            ensureActive();sender.checkAllowed();verify(account)
                            if(file.remote.isUploadingActive)inactiveSince=0L else {
                                if(inactiveSince==0L)inactiveSince=android.os.SystemClock.elapsedRealtime()
                                check(android.os.SystemClock.elapsedRealtime()-inactiveSince<30_000){"Telegram stopped the content upload. Retry to reconcile it."}
                            }
                            // Events are immediate; the local GetFile fallback only repairs dropped/missing updates.
                            file=withTimeoutOrNull(1000){changes.receive()}?:bound(account){session.call(TdApi.GetFile(nativeId))} as TdApi.File
                        }
                    }
                }finally{subscription.cancelAndJoin();changes.close()}
            }
            file
        }
        return sender.send {
        val preview=thumbnail?.takeIf{it.exists()&&it.length() in 1..204_800}?.let {
            val bounds=android.graphics.BitmapFactory.Options().apply{inJustDecodeBounds=true}
            android.graphics.BitmapFactory.decodeFile(it.absolutePath,bounds)
            if(bounds.outWidth in 1..320&&bounds.outHeight in 1..320)TdApi.InputThumbnail(TdApi.InputFileLocal(it.absolutePath),bounds.outWidth,bounds.outHeight)else null
        }
        val first = bound(account){session.call(TdApi.SendMessage().apply {
            chatId = session.savedMessagesId
            inputMessageContent = TdApi.InputMessageDocument(TdApi.InputDocument(TdApi.InputFileId(uploaded.id), preview, true), TdApi.FormattedText(caption, emptyArray()))
        })} as TdApi.Message
        bound(account){pending(first);session.awaitSent(first)}.also{acknowledged=true}
        }
        } finally {
            // Release only this preliminary upload. Never delete acknowledged messages or another account's native ID.
            if(nativeId!=0&&session.state.value.accountId==account)withContext(NonCancellable){runCatching{
                if(acknowledged)session.releaseCompletedUpload(nativeId,account)
                else withTimeout(5_000){session.call(TdApi.CancelPreliminaryUploadFile(nativeId))}
            }}
        }
    }
    suspend fun sendText(text: String, pending: suspend (TdApi.Message) -> Unit = {}): TdApi.Message {
        val owner=account()
        return sender.send {
        verify(owner)
        require(text.length <= 4000) { "Metadata text is too large" }
        val first = bound(owner){session.call(TdApi.SendMessage().apply {
            chatId = session.savedMessagesId
            inputMessageContent = TdApi.InputMessageText(TdApi.FormattedText(text, emptyArray()), null, false)
        })} as TdApi.Message
        bound(owner){pending(first);session.awaitSent(first)}
        }
    }
    suspend fun recoverPending(id: Long): TdApi.Message? {
        if (id == 0L) return null
        val owner=account()
        bound(owner){session.resolvedSend(id)}?.let { return it }
        return try {
            val m = bound(owner){session.call(TdApi.GetMessage(session.savedMessagesId, id))} as TdApi.Message
            if (m.sendingState is TdApi.MessageSendingStateFailed) null else bound(owner){session.awaitSent(m)}
        } catch (e: CancellationException) { throw e } catch (e: Exception) {
            if(e.message?.contains("not found",true)==true || e.message?.contains("MESSAGE_ID_INVALID")==true) null else throw e
        }
    }
    suspend fun delete(ids: List<Long>) {
        val owner=account()
        ids.distinct().chunked(100).forEach { ids->sender.send { bound(owner){session.call(TdApi.DeleteMessages(session.savedMessagesId, ids.toLongArray(), true))} } }
    }
    suspend fun readAll(messageId: Long): File {
        val document = document(messageId).document.document
        currentCoroutineContext().ensureActive()
        currentCoroutineContext()[NativeFileObserver]?.started?.invoke(document.id)
        return fileLocks.getOrPut(document.id) { Mutex() }.withLock {
            val result = session.call(TdApi.DownloadFile(document.id, 16, 0, 0, true), 60 * 60 * 1000L) as TdApi.File
            check(result.local.isDownloadingCompleted) { "Telegram did not finish downloading the document." }
            File(result.local.path).also { require(it.exists() && (result.size == 0L || it.length() == result.size)) { "Downloaded file size does not match." } }
        }
    }
    suspend fun range(messageId: Long, position: Long, bytes: Int, knownFile: TdApi.File? = null): ByteArray {
        require(position >= 0 && bytes in 1..1_048_576)
        val document = knownFile ?: document(messageId).document.document
        if (position >= document.size) return byteArrayOf()
        val count = minOf(bytes.toLong(), document.size - position).toInt()
        return fileLocks.getOrPut(document.id) { Mutex() }.withLock {
            val existing = session.call(TdApi.GetFileDownloadedPrefixSize(document.id, position)) as TdApi.FileDownloadedPrefixSize
            val file = if (existing.size >= count) session.call(TdApi.GetFile(document.id)) as TdApi.File
                else session.call(TdApi.DownloadFile(document.id, 20, position, count.toLong(), true), 120_000) as TdApi.File
            val prefix = session.call(TdApi.GetFileDownloadedPrefixSize(document.id, position)) as TdApi.FileDownloadedPrefixSize
            check(prefix.size >= count) { "The requested media range is not available yet. Check the connection." }
            withContext(Dispatchers.IO) { RandomAccessFile(file.local.path, "r").use { r -> r.seek(position); ByteArray(count).also { r.readFully(it) } } }
        }
    }
    suspend fun history(from: Long = 0): List<TdApi.Message> {
        val owner=account()
        return (bound(owner){session.call(TdApi.GetChatHistory(session.savedMessagesId, from, 0, 100, false))} as TdApi.Messages).messages.toList()
    }
}

fun File.sha256(): String = inputStream().use { source ->
    val digest = MessageDigest.getInstance("SHA-256"); val buffer = ByteArray(128 * 1024)
    while (true) { val count = source.read(buffer); if (count < 0) break; digest.update(buffer, 0, count) }
    digest.digest().joinToString("") { "%02x".format(it) }
}

/** Per-transfer observer; manual previews/provider reads cannot overwrite a queue's active native file. */
class NativeFileObserver(val started:(Int)->Unit):kotlin.coroutines.AbstractCoroutineContextElement(Key) {
    companion object Key:kotlin.coroutines.CoroutineContext.Key<NativeFileObserver>
}
