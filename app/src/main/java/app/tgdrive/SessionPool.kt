package app.tgdrive

import android.content.Context
import app.tgdrive.data.TelegramDrive
import app.tgdrive.library.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import java.util.concurrent.ConcurrentHashMap

@Serializable data class AccountProfile(val id: String = "legacy", val label: String = "My Telegram", val userId: Long = 0)

class SessionBundle(context: Context, val profile: AccountProfile, allowAccount:(Long)->Boolean={true}) {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    val drive = TelegramDrive(context, profile.id)
    val library = LibraryRepository(context, drive, scope, allowAccount=allowAccount)
    val transfers = TransferEngine(library, scope)
    val actions = LibraryActions(library, transfers)
    val backups = BackupManager(library, transfers)
    val connections = OptionalConnections(library)
    private var wakeJob: Job? = null
    init {
        val wake = { wakeJob?.cancel(); wakeJob = scope.launch {
            delay(500)
            val state=library.state.value
            val readyRead=state.tasks.any{it.kind!=TaskKind.UPLOAD && it.state==TaskState.QUEUED}
            val pending=state.tasks.filter{it.state !in setOf(TaskState.COMPLETED,TaskState.CANCELLED,TaskState.FAILED,TaskState.PAUSED,TaskState.WAITING_FOR_UNLOCK)}
            val next=if(readyRead)0 else maxOf(state.sendCooldownUntil,pending.minOfOrNull{it.nextAttemptAt}?:0)
            BackgroundWork.enqueue(context,profile.id,(next-now()).coerceAtLeast(1000))
        }; Unit }
        library.changed = wake
        transfers.wake = wake
        transfers.external = { task -> if (task.kind == TaskKind.OCR) DocumentTools(library).recognize(task) else connections.execute(task) }
        transfers.completed = { task -> backups.onCompleted(task); connections.forget(task); library.maintainCache() }
    }
}

class SessionPool(private val context: Context) {
    private val preferences = context.getSharedPreferences("account-profiles", Context.MODE_PRIVATE)
    private val mutableProfiles = MutableStateFlow(runCatching { preferences.getString("profiles", null)?.model<List<AccountProfile>>() }.getOrNull() ?: listOf(AccountProfile()))
    val profiles = mutableProfiles.asStateFlow()
    private val mutableActive = MutableStateFlow(preferences.getString("active", "legacy") ?: "legacy")
    val activeId = mutableActive.asStateFlow()
    private val bundles = ConcurrentHashMap<String, SessionBundle>()
    fun active() = bundle(activeId.value)
    fun bundle(id: String): SessionBundle {
        require(id == "legacy" || id.matches(Regex("[a-zA-Z0-9-]{1,80}")))
        val profile = profiles.value.firstOrNull { it.id == id } ?: error("Account profile is unavailable.")
        return bundles.computeIfAbsent(id) {
            SessionBundle(context, profile) { account -> mutableProfiles.value.none { it.id!=id && it.userId==account } }.also { b ->
                b.scope.launch { b.drive.state.collect { auth ->
                    if(auth.login==app.tgdrive.data.LoginStep.CLOSED){mutableProfiles.value=mutableProfiles.value.map{if(it.id==id)it.copy(userId=0,label="Signed out")else it};save()}
                } }
                b.scope.launch {
                    b.library.state.collect { state ->
                        if (state.loaded && state.accountId != 0L) {
                            val updated = mutableProfiles.value.map { if(it.id == id)it.copy(label = state.accountName.ifBlank { "Telegram account" }, userId = state.accountId) else it }
                            if(updated != mutableProfiles.value) { mutableProfiles.value = updated; save() }
                        }
                    }
                }
            }
        }
    }
    fun add() {
        require(profiles.value.size < 5) { "At most five local profiles are supported." }
        val original=active()
        original.scope.launch {
            try {
                val profile=AccountProfile(newId(),"New account")
                val id=original.drive.secret("api-id");val hash=original.drive.secret("api-hash")
                if(!id.isNullOrBlank()&&!hash.isNullOrBlank())withContext(Dispatchers.IO){
                    val settings=app.tgdrive.data.SecureSettings(context,profile.id);settings.put("api-id",id);settings.put("api-hash",hash)
                }
                mutableProfiles.value+=profile;switch(profile.id)
            }catch(e:Exception){original.library.report(e)}
        }
    }
    fun switch(id: String) { require(profiles.value.any { it.id == id }); if(id!=activeId.value)bundles[activeId.value]?.let{previous->previous.scope.launch{PlaybackService.stopProtected(context);previous.transfers.pauseProtected();previous.library.lockVaults()}}; mutableActive.value = id; save() }
    fun lockEnabled(id: String) = preferences.getBoolean("lock:$id", false)
    fun setLockEnabled(id: String, enabled: Boolean) { preferences.edit().putBoolean("lock:$id", enabled).apply() }
    private fun save() { preferences.edit().putString("profiles", mutableProfiles.value.json()).putString("active", mutableActive.value).apply() }
}
