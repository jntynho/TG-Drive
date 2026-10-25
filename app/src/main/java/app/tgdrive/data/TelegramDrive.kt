package app.tgdrive.data

import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.OpenableColumns
import app.tgdrive.BuildConfig
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.drinkless.tdlib.TdApi
import java.io.File
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class TelegramRequestException(val code:Int,message:String):IllegalStateException(message)

enum class LoginStep { CONFIGURE, CONNECTING, CONNECTION_ERROR, PHONE, CODE, PASSWORD, OTHER_DEVICE, READY, UNSUPPORTED, CLOSED }
enum class TelegramNetwork { UNKNOWN, WAITING_FOR_NETWORK, CONNECTING, CONNECTING_TO_PROXY, UPDATING, READY }
data class DriveState(
    val login: LoginStep = LoginStep.CONFIGURE,
    val busy: Boolean = false,
    val error: String? = null,
    val info: String? = null,
    val files: List<DriveFile> = emptyList(),
    val folders: Set<String> = emptySet(),
    val syncing: Boolean = false,
    val account: String = "",
    val confirmationLink: String = "",
    val transfer: String? = null,
    val progress: Float? = null,
    val authError: String? = null,
    val network: TelegramNetwork = TelegramNetwork.UNKNOWN,
    val accountId: Long = 0,
    val avatarFileId: Int = 0,
    val resendAt:Long=0,val canResend:Boolean=false,
)

class TelegramDrive internal constructor(
    private val context: Context,
    private val secure: SessionSettings,
    private val clients: TdConnectionFactory,
    private val scope: CoroutineScope,
    private val timeoutMillis: Long = 30_000,
    val profileId: String = "legacy",
) {
    constructor(context: Context, profileId: String = "legacy") : this(context, SecureSettings(context, profileId), NativeTdConnectionFactory,
        CoroutineScope(SupervisorJob() + Dispatchers.Main), profileId = profileId)
    private val mutable = MutableStateFlow(DriveState())
    val state = mutable.asStateFlow()
    private val eventStream = MutableSharedFlow<TdApi.Object>(extraBufferCapacity = 256, onBufferOverflow = kotlinx.coroutines.channels.BufferOverflow.DROP_OLDEST)
    val events = eventStream.asSharedFlow()
    var libraryManaged = false
    val savedMessagesId get() = chatId
    private var client: TdConnection? = null
    private var apiId = 0
    private var apiHash = ""
    private var chatId = 0L
    private var store: IndexStore? = null
    private val sent = mutableMapOf<Long, CompletableDeferred<TdApi.Message>>()
    private val earlyResults = mutableMapOf<Long, Result<TdApi.Message>>()
    private var activeFileId = 0
    private var loggingOut = false
    private var syncJob: Job? = null
    private var initializeJob: Job? = null
    private val persistMutex = Mutex()
    private data class Update(val generation: Long, val value: TdApi.Object)
    private val updates = Channel<Update>(Channel.UNLIMITED)
    private var generation = 0L
    private var initializationWatchdog: Job? = null
    private var closed = CompletableDeferred<Unit>()
    private var editingCredentials = false

    init {
        // TDLib requires updates to be applied in receive order. In particular,
        // SetTdlibParameters may emit WaitPhoneNumber BEFORE its Ok response.
        scope.launch {
            for (update in updates) {
                if (update.generation != generation) continue
                try { onUpdate(update.value) }
                catch (e: Exception) {
                    if (e is CancellationException) throw e
                    fail(safeReason(e.message ?: "Could not process a Telegram update."))
                }
            }
        }
        scope.launch { runCatching {
            val id = secure.get("api-id") ?: BuildConfig.TELEGRAM_API_ID
            val hash = secure.get("api-hash") ?: BuildConfig.TELEGRAM_API_HASH
            if (id.toIntOrNull() != null && hash.matches(Regex("[a-fA-F0-9]{32}"))) connect(id.toInt(), hash)
        }.onFailure { connectionFailed("Could not open local secure settings. You can review your API configuration.") } }
    }

    fun configure(id: String, hash: String) {
        val number = id.toIntOrNull()
        if (number == null || number <= 0 || !hash.matches(Regex("[a-fA-F0-9]{32}"))) {
            fail("Enter a valid Telegram API ID and 32-character API hash."); return
        }
        if (state.value.busy) return
        scope.launch {
            mutable.update { it.copy(busy = true, error = null, authError = null) }
            try {
                if (client != null) {
                    // Close, never LogOut/Destroy: editing app credentials must not delete a session or files.
                    request(TdApi.Close())
                    try { withTimeout(timeoutMillis) { closed.await() } }
                    catch (_: TimeoutCancellationException) { error("Telegram did not close in time. Restart the app before editing credentials again.") }
                }
                secure.put("api-id", number.toString()); secure.put("api-hash", hash)
                connect(number, hash)
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                mutable.update { it.copy(login = LoginStep.CONFIGURE, busy = false, authError = safeReason(e.message ?: "Could not initialize Telegram on this device.")) }
            }
        }
    }

    private fun connect(id: Int, hash: String) {
        if (client != null) return
        apiId = id; apiHash = hash; loggingOut = false
        editingCredentials = false
        closed = CompletableDeferred()
        val current = ++generation
        startInitializationWatchdog()
        try {
            client = clients.create({ update -> updates.trySend(Update(current, update)) }, {
                scope.launch {
                    if (current == generation) fail("Telegram could not process a connection update. Retry or review your API configuration.")
                }
            })
        } catch (e: Throwable) {
            if (e is CancellationException) throw e
            connectionFailed("Could not load Telegram on this device (${e.javaClass.simpleName}).")
        }
    }

    private fun startInitializationWatchdog() {
        initializationWatchdog?.cancel()
        mutable.update { it.copy(login = LoginStep.CONNECTING, busy = false, error = null, authError = null) }
        initializationWatchdog = scope.launch {
            delay(timeoutMillis)
            if (state.value.login == LoginStep.CONNECTING) {
                connectionFailed("Telegram initialization timed out. Check connectivity, then retry or review your API configuration.")
            }
        }
    }

    private suspend fun request(function: TdApi.Function<*>, timeout: Long = timeoutMillis): TdApi.Object = try {
        withTimeout(timeout) { sendRequest(function) }
    } catch (_: TimeoutCancellationException) {
        currentCoroutineContext().ensureActive() // Preserve a worker/service deadline from its parent.
        error("Telegram did not respond in time. Check connectivity before retrying. A pending sign-in request may still complete.")
    }

    private suspend fun sendRequest(function: TdApi.Function<*>): TdApi.Object = suspendCancellableCoroutine { c ->
        val td = client
        if (td == null) { c.resumeWithException(IllegalStateException("Telegram is not connected.")); return@suspendCancellableCoroutine }
        td.send(function) { result ->
            if (c.isActive) {
                if (result is TdApi.Error) c.resumeWithException(TelegramRequestException(result.code,safeReason(readableError(result))))
                else c.resume(result)
            }
        }
    }

    private suspend fun onUpdate(update: TdApi.Object) {
        eventStream.tryEmit(update)
        when (update) {
            is TdApi.UpdateAuthorizationState -> authorization(update.authorizationState)
            is TdApi.UpdateConnectionState -> mutable.update { it.copy(network = when (update.state) {
                is TdApi.ConnectionStateWaitingForNetwork -> TelegramNetwork.WAITING_FOR_NETWORK
                is TdApi.ConnectionStateConnectingToProxy -> TelegramNetwork.CONNECTING_TO_PROXY
                is TdApi.ConnectionStateConnecting -> TelegramNetwork.CONNECTING
                is TdApi.ConnectionStateUpdating -> TelegramNetwork.UPDATING
                is TdApi.ConnectionStateReady -> TelegramNetwork.READY
                else -> TelegramNetwork.UNKNOWN
            }) }
            is TdApi.UpdateMessageSendSucceeded -> {
                upsert(update.message)
                val waiter = sent.remove(update.oldMessageId)
                if (waiter != null) waiter.complete(update.message)
                else if (update.message.chatId == chatId) earlyResults[update.oldMessageId] = Result.success(update.message)
            }
            is TdApi.UpdateMessageSendFailed -> {
                val error = TelegramRequestException(update.error.code,readableError(update.error))
                sent.remove(update.oldMessageId)?.completeExceptionally(error)
                    ?: run { if (update.message.chatId == chatId) earlyResults[update.oldMessageId] = Result.failure(error) }
            }
            is TdApi.UpdateNewMessage -> if (update.message.sendingState == null) upsert(update.message)
            is TdApi.UpdateDeleteMessages -> if (update.chatId == chatId && update.isPermanent) {
                mutable.update { it.copy(files = it.files.filterNot { f -> f.messageId in update.messageIds }) }; persist()
            }
            is TdApi.UpdateUser -> if(update.user.id==state.value.accountId) mutable.update{it.copy(account=update.user.firstName,avatarFileId=update.user.profilePhoto?.small?.id?:0)}
            is TdApi.UpdateFile -> if (update.file.id == activeFileId) {
                val file = update.file
                val total = maxOf(file.size, file.expectedSize)
                val done = maxOf(file.local.downloadedSize, file.remote.uploadedSize)
                mutable.update { it.copy(progress = if (total > 0) (done.toFloat() / total).coerceIn(0f, 1f) else null) }
            }
        }
    }

    private suspend fun authorization(auth: TdApi.AuthorizationState) {
        if (auth is TdApi.AuthorizationStateClosed) {
            ++generation // Ignore late responses/updates from the closed native client.
            closed.complete(Unit)
            initializationWatchdog?.cancel(); syncJob?.cancel(); initializeJob?.cancel()
            client = null; chatId = 0; store = null
            sent.values.forEach { it.completeExceptionally(IllegalStateException("Telegram connection was closed.")) }
            sent.clear(); earlyResults.clear()
            if (editingCredentials) return
            if (loggingOut) mutable.value = DriveState(login = LoginStep.CLOSED, info = "Signed out. Your cloud files were not deleted.")
            else connectionFailed("Telegram closed its connection. Retry to reconnect; your cloud files were not deleted.")
            return
        }
        if (editingCredentials) return
        val step = when (auth) {
            is TdApi.AuthorizationStateWaitTdlibParameters -> {
                try {
                    request(TdApi.SetTdlibParameters().apply {
                        databaseDirectory = File(context.noBackupFilesDir, if (profileId == "legacy") "telegram-db" else "sessions/$profileId/db").absolutePath
                        filesDirectory = File(context.filesDir, if (profileId == "legacy") "telegram-files" else "sessions/$profileId/files").absolutePath
                        databaseEncryptionKey = secure.databaseKey()
                        useFileDatabase = true; useChatInfoDatabase = true; useMessageDatabase = true; useSecretChats = false
                        apiId = this@TelegramDrive.apiId; apiHash = this@TelegramDrive.apiHash
                        systemLanguageCode = java.util.Locale.getDefault().language
                        deviceModel = Build.MODEL; systemVersion = Build.VERSION.RELEASE; applicationVersion = BuildConfig.VERSION_NAME
                    })
                } catch (e: Exception) {
                    if (e is CancellationException) throw e
                    if (!editingCredentials) connectionFailed(safeReason(e.message ?: "Telegram initialization failed."))
                }
                // Never write CONNECTING here: this suspended operation must not
                // overwrite a newer auth state or hide an initialization error.
                return
            }
            is TdApi.AuthorizationStateWaitPhoneNumber -> LoginStep.PHONE
            is TdApi.AuthorizationStateWaitCode -> {mutable.update{it.copy(resendAt=System.currentTimeMillis()+auth.codeInfo.timeout.coerceAtLeast(0)*1000L,canResend=auth.codeInfo.nextType!=null)};LoginStep.CODE}
            is TdApi.AuthorizationStateWaitPassword -> LoginStep.PASSWORD
            is TdApi.AuthorizationStateWaitOtherDeviceConfirmation -> {
                mutable.update { it.copy(confirmationLink = auth.link) }; LoginStep.OTHER_DEVICE
            }
            is TdApi.AuthorizationStateReady -> {
                initializationWatchdog?.cancel()
                mutable.update { it.copy(login = LoginStep.READY, busy = false, error = null, authError = null) }
                initializeJob?.cancel()
                initializeJob = scope.launch { initializeAccount() }
                return
            }
            is TdApi.AuthorizationStateLoggingOut, is TdApi.AuthorizationStateClosing -> LoginStep.CONNECTING
            else -> LoginStep.UNSUPPORTED
        }
        if (step == LoginStep.CONNECTING) startInitializationWatchdog()
        else {
            initializationWatchdog?.cancel()
            mutable.update { it.copy(login = step, busy = false, authError = null) }
        }
    }

    fun login(value: String) = action {
        request(when (state.value.login) {
            LoginStep.PHONE -> TdApi.SetAuthenticationPhoneNumber(value.trim(), null)
            LoginStep.CODE -> TdApi.CheckAuthenticationCode(value.trim())
            LoginStep.PASSWORD -> TdApi.CheckAuthenticationPassword(value)
            else -> error("This sign-in step is not supported. Use an existing Telegram account.")
        })
    }

    suspend fun closeInactiveProfile(){require(state.value.login!=LoginStep.READY){"Sign out before removing this profile."};if(client!=null)request(TdApi.Close())}
    fun changePhoneNumber(){if(state.value.login==LoginStep.CODE&&!state.value.busy)mutable.update{it.copy(login=LoginStep.PHONE,authError=null)}}
    fun resendCode()=action{
        require(state.value.login==LoginStep.CODE&&state.value.canResend&&System.currentTimeMillis()>=state.value.resendAt){"Wait for Telegram to allow another code."}
        request(TdApi.ResendAuthenticationCode(null))
    }
    fun reconnect() {
        if (state.value.busy) return
        if (client == null) {
            if (apiId <= 0 || apiHash.isBlank()) { editConfiguration(); return }
            connect(apiId, apiHash)
        } else {
            startInitializationWatchdog()
            val current = generation
            scope.launch {
                try {
                    val auth = request(TdApi.GetAuthorizationState()) as TdApi.AuthorizationState
                    updates.send(Update(current, TdApi.UpdateAuthorizationState(auth)))
                } catch (e: Exception) {
                    if (e is CancellationException) throw e
                    connectionFailed(safeReason(e.message ?: "Unable to reconnect."))
                }
            }
        }
    }

    fun editConfiguration() {
        if (state.value.login == LoginStep.READY || state.value.busy) return
        editingCredentials = true
        initializationWatchdog?.cancel()
        mutable.update { it.copy(login = LoginStep.CONFIGURE, busy = false, authError = null, error = null) }
    }

    private suspend fun initializeAccount() {
        runCatching {
            val me = request(TdApi.GetMe()) as TdApi.User
            val chat = request(TdApi.CreatePrivateChat(me.id, false)) as TdApi.Chat
            chatId = chat.id
            store = IndexStore(context, me.id)
            val (files, folders) = withContext(Dispatchers.IO) { store!!.read() }
            mutable.update { it.copy(accountId = me.id, account = me.firstName, avatarFileId = me.profilePhoto?.small?.id ?: 0, files = files, folders = folders) }
            if (!libraryManaged) refresh()
        }.onFailure { if (it is CancellationException) throw it; fail(it.message ?: "Could not open Saved Messages.") }
    }

    fun refresh() {
        if (state.value.syncing || chatId == 0L || loggingOut) return
        syncJob = scope.launch {
            mutable.update { it.copy(syncing = true, error = null) }
            try {
                val cachedIds = state.value.files.map { it.messageId }.toSet()
                var from = 0L
                val seen = mutableSetOf<Long>()
                val found = mutableMapOf<Long, DriveFile>()
                do {
                    val page = request(TdApi.GetChatHistory(chatId, from, 0, 100, false)) as TdApi.Messages
                    if (page.messages.isEmpty()) break
                    page.messages.forEach { message -> metadata(message)?.let { if (message.sendingState == null) found[message.id] = it } }
                    val next = page.messages.last().id
                    if (next == from || !seen.add(next)) break
                    from = next
                    // Merge rather than clear: files may be uploaded while history is being scanned.
                    mutable.update { it.copy(files = (it.files + found.values).associateBy { f -> f.messageId }.values.toList()) }
                    delay(120)
                } while (true)
                // Keep newly received messages; remove stale cache entries verified absent from history.
                mutable.update { current -> current.copy(files = current.files.filter { it.messageId in found || it.messageId !in cachedIds } ) }
                persist()
            } catch (e: Exception) { if (e is CancellationException) throw e; fail(e.message ?: "Index refresh failed. Cached files remain available.") }
            finally { mutable.update { it.copy(syncing = false) } }
        }
    }

    fun addFolder(name: String) {
        val normalized = name.trim()
        if (!FileMetadata.validFolder(normalized)) { fail("Use 1–60 characters without slashes or control characters."); return }
        mutable.update { it.copy(folders = it.folders + normalized) }; persist()
    }

    private fun metadata(message: TdApi.Message): DriveFile? {
        if (message.chatId != chatId) return null
        val content = message.content as? TdApi.MessageDocument ?: return null
        val metadata = FileMetadata.parse(content.caption.text) ?: return null
        return DriveFile(metadata, message.id, content.document.document.id, content.document.document.size, message.date, content.document.mimeType)
    }

    private fun upsert(message: TdApi.Message) {
        val file = metadata(message) ?: return
        mutable.update { it.copy(files = it.files.filterNot { f -> f.messageId == file.messageId || f.metadata.id == file.metadata.id } + file) }
        persist()
    }

    private fun persist() {
        val target = store ?: return
        scope.launch {
            persistMutex.withLock {
                if (store !== target) return@withLock
                val snapshot = state.value
                runCatching { withContext(Dispatchers.IO) { target.write(snapshot.files, snapshot.folders) } }
                    .onFailure { fail("Could not save the local index. Refresh can rebuild uploaded files.") }
            }
        }
    }

    suspend fun upload(uri: Uri, folder: String) {
        check(chatId != 0L && !loggingOut) { "Sign in before uploading." }
        require(folder.isEmpty() || FileMetadata.validFolder(folder))
        mutable.update { it.copy(transfer = "Preparing upload…", progress = null, error = null) }
        var staging: File? = null
        try {
            val info = withContext(Dispatchers.IO) {
                context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
                    if (it.moveToFirst()) it.getString(0) else null
                } ?: "file"
            }
            val meta = FileMetadata.create(info, folder)
            staging = withContext(Dispatchers.IO) {
                val directory = File(context.filesDir, "upload-staging/${meta.id}").apply { mkdirs() }
                val output = File(directory, meta.name)
                try {
                    context.contentResolver.openInputStream(uri)?.use { source -> output.outputStream().use { source.copyTo(it) } }
                        ?: error("Cannot read the selected file.")
                    output
                } catch (e: Exception) { output.delete(); directory.delete(); throw e }
            }
            mutable.update { it.copy(transfer = "Uploading ${meta.name}") }
            val message = request(TdApi.SendMessage().apply {
                chatId = this@TelegramDrive.chatId
                inputMessageContent = TdApi.InputMessageDocument(TdApi.InputDocument(TdApi.InputFileLocal(staging.absolutePath), null, true), TdApi.FormattedText(meta.caption(), emptyArray()))
            }) as TdApi.Message
            activeFileId = (message.content as? TdApi.MessageDocument)?.document?.document?.id ?: 0
            val completed = if (message.sendingState == null) message else {
                val prior = earlyResults.remove(message.id)
                if (prior != null) prior.getOrThrow() else {
                    val wait = CompletableDeferred<TdApi.Message>()
                    sent[message.id] = wait
                    try { withTimeout(60 * 60 * 1000L) { wait.await() } } finally { sent.remove(message.id) }
                }
            }
            upsert(completed)
            staging.delete(); staging.parentFile?.delete()
            mutable.update { it.copy(info = "Uploaded ${meta.name}") }
        } finally {
            // Failed/interrupted upload sources stay available for TDLib's pending transfers.
            activeFileId = 0
            mutable.update { it.copy(transfer = null, progress = null) }
        }
    }

    suspend fun download(file: DriveFile, destination: Uri) {
        check(chatId != 0L && !loggingOut) { "Sign in before downloading." }
        mutable.update { it.copy(transfer = "Downloading ${file.name}", progress = null, error = null) }
        try {
            val message = request(TdApi.GetMessage(chatId, file.messageId)) as TdApi.Message
            val content = message.content as? TdApi.MessageDocument ?: error("This file is no longer available.")
            activeFileId = content.document.document.id
            val result = request(TdApi.DownloadFile(activeFileId, 16, 0, 0, true), 60 * 60 * 1000L) as TdApi.File
            check(result.local.isDownloadingCompleted) { "Download did not finish. Please retry." }
            mutable.update { it.copy(transfer = "Saving ${file.name}", progress = null) }
            withContext(Dispatchers.IO) {
                context.contentResolver.openOutputStream(destination, "wt")?.use { out -> File(result.local.path).inputStream().use { it.copyTo(out) } }
                    ?: error("Cannot write to the selected destination.")
            }
            mutable.update { it.copy(info = "Saved ${file.name}") }
        } finally { activeFileId = 0; mutable.update { it.copy(transfer = null, progress = null) } }
    }

    fun delete(file: DriveFile) = action {
        check(state.value.transfer == null) { "Wait for the transfer to finish." }
        request(TdApi.DeleteMessages(chatId, longArrayOf(file.messageId), true))
        mutable.update { it.copy(files = it.files.filterNot { f -> f.messageId == file.messageId }, info = "Deleted ${file.name} from Telegram.") }
        persist()
    }

    fun logout() = action {
        check(state.value.transfer == null) { "Wait for the transfer to finish before signing out." }
        loggingOut = true
        initializeJob?.cancel()
        syncJob?.cancel()
        try { request(TdApi.LogOut()) } catch (e: Exception) { loggingOut = false; throw e }
    }
    fun clearNotice() { mutable.update { it.copy(error = null, info = null) } }
    suspend fun call(function: TdApi.Function<*>, timeout: Long = timeoutMillis): TdApi.Object = withContext(Dispatchers.Main) { request(function, timeout) }
    suspend fun secret(name: String): String? = withContext(Dispatchers.IO) { secure.get(name) }
    suspend fun saveSecret(name: String, value: String) = withContext(Dispatchers.IO) { secure.put(name, value) }
    /** Release an acknowledged preliminary-upload lease without waiting on a disposable Ok callback. */
    internal suspend fun releaseCompletedUpload(fileId:Int,owner:Long) = withContext(Dispatchers.Main) {
        if(state.value.accountId==owner&&owner!=0L)client?.send(TdApi.CancelPreliminaryUploadFile(fileId)) { }
        Unit
    }
    suspend fun resolvedSend(id: Long): TdApi.Message? = withContext(Dispatchers.Main) { earlyResults.remove(id)?.getOrThrow() }
    suspend fun awaitSent(message: TdApi.Message): TdApi.Message = withContext(Dispatchers.Main) {
        if (message.sendingState == null) return@withContext message
        earlyResults.remove(message.id)?.let { return@withContext it.getOrThrow() }
        val wait = sent.getOrPut(message.id) { CompletableDeferred() }
        try { withTimeout(60 * 60 * 1000L) { wait.await() } } finally { sent.remove(message.id) }
    }
    private fun connectionFailed(message: String) {
        initializationWatchdog?.cancel()
        mutable.update { it.copy(login = LoginStep.CONNECTION_ERROR, authError = safeReason(message), busy = false) }
    }
    private fun safeReason(message: String): String = (if (apiHash.isBlank()) message else message.replace(apiHash, "[redacted]", ignoreCase = true))
        .replace(Regex("[\\r\\n\\p{Cntrl}]"), " ").take(300)
    fun fail(message: String) {
        if (state.value.login == LoginStep.CONNECTING || state.value.login == LoginStep.CONNECTION_ERROR) connectionFailed(message)
        else mutable.update { it.copy(error = safeReason(message), authError = if (it.login == LoginStep.READY) it.authError else safeReason(message), busy = false) }
    }
    private fun action(block: suspend () -> Unit) {
        if (state.value.busy) return
        scope.launch {
            mutable.update { it.copy(busy = true, error = null, authError = null) }
            try { block() } catch (e: Exception) { if (e is CancellationException) throw e; fail(e.message ?: "The operation failed.") }
            finally { mutable.update { it.copy(busy = false) } }
        }
    }
    private fun readableError(error: TdApi.Error): String = when {
        error.message.startsWith("FLOOD_WAIT") || error.code == 429 -> "Telegram requires a wait before retrying. ${error.message}"
        error.message == "PHONE_CODE_INVALID" -> "The sign-in code is incorrect. Please try again."
        error.message == "PASSWORD_HASH_INVALID" -> "The two-step verification password is incorrect."
        else -> "Telegram: ${error.message} (${error.code})"
    }
}
