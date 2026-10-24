package app.tgdrive.library

import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import kotlinx.coroutines.*
import java.security.MessageDigest

class BackupManager(private val repository: LibraryRepository, private val transfers: TransferEngine) {
    suspend fun add(tree: Uri, name: String, folder: String, vault: String, since: Long) {
        require(DocumentFile.fromTreeUri(repository.context, tree)?.isDirectory == true) { "Choose a folder supported by Android's document picker." }
        repository.setRule(BackupRule(name = cleanName(name), treeUri = tree.toString(), folderId = folder, vaultId = vault, since = since))
        repository.notice("Backup rule saved. Only this selected folder is monitored; private app data is not accessible.")
    }
    suspend fun scan(ruleId: String? = null, includePreviouslyBackedUp: Boolean = false) = withContext(Dispatchers.IO) {
        val account = repository.state.value.accountId
        val rules = repository.state.value.rules.filter { it.enabled && (ruleId == null || it.id == ruleId) }
        for (rule in rules) {
            repository.checkAccount(account)
            if (!repository.vaults.isUnlocked(rule.vaultId)) { repository.setRule(rule.copy(error = "Unlock the destination vault before scanning protected backups.")); continue }
            try {
                val root = DocumentFile.fromTreeUri(repository.context, Uri.parse(rule.treeUri)) ?: error("Folder permission is unavailable. Choose the folder again.")
                require(root.canRead()) { "Folder permission is unavailable. Choose the folder again." }
                val remaining = ArrayDeque<DocumentFile>().apply { add(root) }; var count = 0; var queued = 0
                val existingSources = repository.db().tasks().filter { it.state !in setOf(TaskState.CANCELLED, TaskState.COMPLETED, TaskState.FAILED) }.map { it.source }.toMutableSet()
                while (remaining.isNotEmpty()) {
                    ensureActive(); repository.checkAccount(account)
                    val directory = remaining.removeFirst()
                    for (file in directory.listFiles()) {
                        ensureActive()
                        if (file.isDirectory && rule.recursive) { remaining.add(file); continue }
                        if (!file.isFile || file.lastModified() < rule.since) continue
                        require(++count <= 100_000) { "This folder exceeds the 100,000-file scan limit. Select smaller subfolders as separate rules." }
                        if (queued >= 1000) break
                        val uri = file.uri.toString(); val key = sourceKey(uri)
                        val receipt = repository.db().get("sources", key)?.model<SourceReceipt>()
                        if (!includePreviouslyBackedUp && receipt != null && receipt.size == file.length() && receipt.modifiedAt == file.lastModified()) continue
                        if (!existingSources.add(uri)) continue
                        transfers.enqueueUpload(listOf(file.uri), rule.folderId, rule.vaultId, ruleId = rule.id, allowDuplicate = includePreviouslyBackedUp)
                        queued++
                    }
                    if (count > 100_000 || queued >= 1000) break
                }
                repository.setRule(rule.copy(lastScanAt = now(), error = if(queued >= 1000) "Batch limit reached. Run another scan after current transfers complete." else ""))
                repository.notice("${rule.name}: queued $queued changed or previously unrecorded files. Upload receipts are retained separately from rules.")
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { repository.setRule(rule.copy(lastScanAt = now(), error = e.message?.take(200) ?: "Folder scan failed.")) }
        }
    }
    suspend fun onCompleted(task: TransferTask) {
        if (task.kind != TaskKind.UPLOAD || task.ruleId.isEmpty()) return
        val uri = Uri.parse(task.source)
        val source = DocumentFile.fromSingleUri(repository.context, uri)
        val item = repository.state.value.items.firstOrNull { it.id == task.itemId } ?: repository.state.value.items.firstOrNull { it.sha256 == task.sourceSha256 }
        val receipt = SourceReceipt(task.source, source?.length() ?: task.total, source?.lastModified() ?: 0, task.sourceSha256, item?.id ?: task.itemId)
        repository.mutate { it.put("sources", sourceKey(task.source), receipt.json()) }
        repository.state.value.rules.firstOrNull { it.id == task.ruleId }?.let { repository.setRule(it.copy(lastSuccessAt = now(), error = "")) }
    }
    suspend fun purgeExpiredTrash() {
        val cutoff = now() - repository.state.value.preferences.trashDays.coerceIn(1,365) * 86_400_000L
        val ids = repository.state.value.items.filter { it.deletedAt in 1..cutoff && it.versionId.isNotEmpty() }.map { it.id }
        if (ids.isNotEmpty()) LibraryActions(repository, transfers).purge(ids)
    }
    companion object { fun sourceKey(uri: String) = "local-source:" + MessageDigest.getInstance("SHA-256").digest(uri.toByteArray()).joinToString("") { "%02x".format(it) } }
}
