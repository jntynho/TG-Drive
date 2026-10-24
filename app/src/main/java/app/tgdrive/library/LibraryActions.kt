package app.tgdrive.library

import android.net.Uri
import android.os.Build
import app.tgdrive.BuildConfig
import kotlinx.coroutines.*
import org.drinkless.tdlib.TdApi
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class LibraryActions(val repository: LibraryRepository, val transfers: TransferEngine) {
    fun run(block: suspend () -> Unit) {
        val account = repository.state.value.accountId
        repository.scope.launch(Dispatchers.IO) { try { repository.checkAccount(account); block() } catch (e: Exception) { repository.report(e) } }
    }
    suspend fun rename(id: String, name: String) = repository.updateItem(repository.item(id).copy(name = cleanName(name)))
    suspend fun move(ids: List<String>, folderId: String) {
        if (folderId.isNotEmpty()) require(repository.state.value.folders.any { it.id == folderId && it.deletedAt == 0L }) { "Destination folder is unavailable." }
        for (id in ids) {
            val targetVault = if (folderId.isEmpty()) repository.itemVault(id) else repository.db().entity(EntityKind.FOLDER, folderId)?.vaultId ?: ""
            require(targetVault == repository.itemVault(id)) { "Moving does not change encryption. Re-upload the file to change its vault." }
            repository.updateItem(repository.item(id).copy(folderId = folderId))
        }
    }
    suspend fun tags(id: String, value: String) = repository.updateItem(repository.item(id).copy(tags = normalizeTags(value)))
    suspend fun favorite(id: String) { val item = repository.item(id); repository.updateItem(item.copy(favorite = !item.favorite)) }
    suspend fun trash(ids: List<String>) { ids.forEach { repository.updateItem(repository.item(it).copy(deletedAt = now())) }; repository.notice("Moved to Trash. Original Telegram messages are retained until permanent deletion.") }
    suspend fun restore(ids: List<String>) { ids.forEach { repository.updateItem(repository.item(it).copy(deletedAt = 0)) } }
    suspend fun purge(ids: List<String>) {
        require(ids.all { repository.item(it).deletedAt > 0L }) { "Move items to Trash before deleting permanently." }
        require(repository.state.value.tasks.none { it.itemId in ids && it.state !in setOf(TaskState.COMPLETED,TaskState.CANCELLED,TaskState.FAILED) }) { "Cancel transfers for these files before permanent deletion." }
        for (id in ids) {
            val item = repository.item(id)
            val versions = repository.state.value.versions.filter { it.itemId == id }
            val metadataMessages = repository.db().all("journal").map { it.model<JournalEntry>() }
                .filter { it.envelope.entityId == id || versions.any { v -> v.id == it.envelope.entityId } }.map { it.messageId }.filter { it != 0L }
            val contentMessages = versions.flatMap { (it.parts + listOfNotNull(it.thumbnail)).map { part -> part.messageId } }
            repository.remote.delete((metadataMessages + contentMessages).distinct())
            repository.mutate { db ->
                val entityIds = versions.map { it.id }.toSet() + id
                db.entries("journal").filter { it.second.model<JournalEntry>().envelope.entityId in entityIds }.forEach { db.remove("journal",it.first) }
                db.entries("conflicts").filter { it.second.model<Conflict>().entityId in entityIds }.forEach { db.remove("conflicts",it.first) }
                db.entries("sources").filter { row -> versions.any { row.first.startsWith("cloud-part:${it.id}:") || row.first=="cloud-thumb:${it.id}" } }.forEach { db.remove("sources",it.first) }
            }
            repository.forgetVersions(versions)
            repository.updateItem(item.copy(name = "Deleted file", sha256 = "", size = 0, versionId = "", tags = emptyList(), favorite = false))
            repository.mutate { db -> db.remove("local", id) }
            versions.forEach { repository.cache?.removeVersion(it.id) }
        }
        repository.flush(); repository.notice("Original messages and content versions deleted. A minimal deletion marker prevents accidental backup resurrection.")
    }
    suspend fun restoreVersion(itemId: String, versionId: String) {
        val version = repository.version(versionId); require(version.itemId == itemId)
        repository.updateItem(repository.item(itemId).copy(versionId = version.id, sha256 = version.sha256, size = version.size))
        repository.notice("Previous content version restored. Other versions were retained.")
    }
    suspend fun unpin(id: String) {
        val local = repository.db().local(id)
        if (local.pinnedVersion.isNotEmpty()) repository.cache?.unpin(local.pinnedVersion)
        repository.setLocal(local.copy(pinnedVersion = "")); repository.updateStorage()
    }
    suspend fun clearCache() {
        require(repository.state.value.tasks.none { it.state in setOf(TaskState.PREPARING, TaskState.TRANSFERRING) }) { "Pause running transfers before cleaning the cache." }
        repository.cache?.clearUnpinned(); repository.updateStorage(); repository.notice("Unpinned app cache cleared. Offline files, upload sources and cloud messages were retained.")
    }
    suspend fun previewOrganization(byDate: Boolean) {
        val changes = repository.state.value.items.filter { it.deletedAt == 0L && it.versionId.isNotEmpty() }.mapNotNull { item ->
            val destination = if (byDate) SimpleDateFormat("yyyy-MM", Locale.ROOT).format(Date(item.createdAt)) else when {
                item.mime.startsWith("image/") -> "Images"; item.mime.startsWith("video/") -> "Videos"; item.mime.startsWith("audio/") -> "Audio"
                item.mime == "application/pdf" || item.mime.startsWith("text/") -> "Documents"; else -> "Other"
            }
            if (repository.state.value.folders.firstOrNull { it.id == item.folderId }?.name == destination) null else OrganizationChange(item.id, item.name, item.folderId, destination)
        }
        repository.setOrganization(changes)
    }
    suspend fun applyOrganization() {
        val changes = repository.state.value.organization
        val undo = changes.associate { it.itemId to it.fromFolderId }
        val folders = mutableMapOf<Pair<String, String>, String>()
        for (change in changes) {
            val vault = repository.itemVault(change.itemId)
            val key = vault to change.destination
            val folder = folders.getOrPut(key) { repository.state.value.folders.firstOrNull { it.name == change.destination && it.parentId.isEmpty() && repository.db().entity(EntityKind.FOLDER, it.id)?.vaultId == vault }?.id ?: "" }
            val destination = if (folder.isEmpty()) repository.createFolder(change.destination, vault = vault).also { folders[key] = it } else folder
            repository.updateItem(repository.item(change.itemId).copy(folderId = destination))
        }
        repository.mutate { it.put("settings", "organizationUndo", undo.json()) }
        repository.setOrganization(emptyList()); repository.notice("Organization applied. You can undo the last batch from Settings.")
    }
    suspend fun undoOrganization() {
        val undo = repository.db().get("settings", "organizationUndo")?.model<Map<String, String>>() ?: error("No organization batch to undo.")
        require(undo.keys.all { id -> repository.state.value.items.any { it.id == id } }) { "Unlock all affected vaults before undoing this batch." }
        for ((id, folder) in undo) repository.updateItem(repository.item(id).copy(folderId = folder))
        repository.mutate { it.remove("settings", "organizationUndo") }; repository.notice("Organization batch undone.")
    }
    suspend fun checkAvailability() {
        var missing = 0; var uncertain = 0
        for (item in repository.state.value.items.filter { it.deletedAt == 0L && it.versionId.isNotEmpty() }) {
            var absent = false
            for (part in repository.version(item.versionId).parts) {
                try { repository.remote.document(part.messageId) }
                catch (e: CancellationException) { throw e }
                catch (e: Exception) {
                    val text = e.message ?: ""
                    if (text.contains("not found", true) || text.contains("MESSAGE_ID_INVALID")) absent = true else uncertain++
                }
                delay(120)
            }
            if (absent) missing++
            repository.setLocal(repository.db().local(item.id).copy(missing = absent))
        }
        repository.notice("Availability scan: $missing missing references; $uncertain requests inconclusive. No files or index records were deleted.")
    }
    suspend fun cleanupCandidates(): List<Item> = repository.state.value.items.filter {
        val local = repository.state.value.local[it.id]
        it.deletedAt == 0L && it.sha256.isNotEmpty() && local?.sourceUri?.isNotEmpty() == true && local.verifiedVersion == it.versionId && local.verifiedAt > 0
    }
    /** Revalidate the selected copies immediately before handing them to Android's deletion confirmation. */
    suspend fun validateLocalDeletion(ids: List<String>): List<Uri> = withContext(Dispatchers.IO) {
        val candidates = cleanupCandidates().associateBy { it.id }
        ids.map { id ->
            val item = candidates[id] ?: error("Verify this file's downloaded content before deleting its local copy.")
            val version = repository.version(item.versionId)
            version.parts.forEach { repository.remote.document(it.messageId) }
            val uri = Uri.parse(repository.db().local(id).sourceUri)
            val digest = MessageDigest.getInstance("SHA-256")
            repository.context.contentResolver.openInputStream(uri)?.use { input ->
                val buffer = ByteArray(128 * 1024)
                while (true) { ensureActive(); val n = input.read(buffer); if (n < 0) break; digest.update(buffer, 0, n) }
            } ?: error("The original source permission is unavailable.")
            check(digest.digest().joinToString("") { "%02x".format(it) } == item.sha256) { "The local source changed after backup. It was not approved for deletion." }
            uri
        }
    }
    fun diagnosticText(): String = buildString {
        appendLine("TG Drive ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
        appendLine("Android ${Build.VERSION.SDK_INT}; ABI ${Build.SUPPORTED_ABIS.firstOrNull()}")
        val state = repository.state.value
        appendLine("Library loaded: ${state.loaded}; indexed items: ${state.items.size}; locked vaults: ${state.lockedVaults}")
        appendLine("Pending metadata: ${state.pendingOperations}; conflicts: ${state.conflicts.size}")
        TaskState.entries.forEach { appendLine("${it.name}: ${state.tasks.count { task -> task.state == it }}") }
        appendLine("No login codes, API credentials, sessions, source URIs, filenames or file content included.")
    }
}
