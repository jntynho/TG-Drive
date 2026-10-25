package app.tgdrive.library

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import java.util.UUID

val LibraryJson = Json { ignoreUnknownKeys = true; encodeDefaults = true }
inline fun <reified T> T.json() = LibraryJson.encodeToString(this)
inline fun <reified T> String.model(): T = LibraryJson.decodeFromString(this)
fun newId() = UUID.randomUUID().toString()
fun now() = System.currentTimeMillis()

@Serializable enum class EntityKind { ITEM, FOLDER, VERSION, VAULT }
@Serializable data class Envelope(
    val schema: Int = 2, val operationId: String = newId(), val kind: EntityKind,
    val entityId: String, val parentOperationId: String = "", val deviceId: String,
    val timestamp: Long = now(), val vaultId: String = "", val body: String,
    val tombstone: Boolean = false, val resolves: List<String> = emptyList(),
)
@Serializable data class Folder(val id: String = newId(), val name: String, val parentId: String = "", val deletedAt: Long = 0)
@Serializable data class Item(
    val id: String = newId(), val name: String, val folderId: String = "", val mime: String = "application/octet-stream",
    val size: Long = 0, val sha256: String = "", val versionId: String = "", val tags: List<String> = emptyList(),
    val favorite: Boolean = false, val createdAt: Long = now(), val modifiedAt: Long = now(), val deletedAt: Long = 0,
)
@Serializable data class Part(
    val messageId: Long, val index: Int, val plainSize: Long, val storedSize: Long,
    val storedSha256: String, val encrypted: Boolean = false,
)
@Serializable data class PartCaption(val itemId: String, val versionId: String, val index: Int, val count: Int, val plainSize: Long, val storedSha256: String, val encrypted: Boolean)
@Serializable data class Version(
    val id: String = newId(), val itemId: String, val size: Long, val sha256: String,
    val parts: List<Part>, val createdAt: Long = now(), val imported: Boolean = false, val thumbnail: Part? = null,
)
@Serializable data class LocalItem(
    val itemId: String, val pinnedVersion: String = "", val verifiedVersion: String = "", val verifiedAt: Long = 0,
    val sourceUri: String = "", val sourceHash: String = "", val ocrText: String = "", val missing: Boolean = false,
)
@Serializable enum class TaskKind { UPLOAD, DOWNLOAD, PIN, VERIFY, EXPORT, WEBDAV, SHARE, OCR }
@Serializable enum class TaskState { QUEUED, PREPARING, TRANSFERRING, PAUSED, WAITING_FOR_WIFI, WAITING_FOR_CHARGING, WAITING_FOR_UNLOCK, WAITING_FOR_NETWORK, WAITING_FOR_DEVICE, SYNCING_METADATA, RETRY_WAIT, COMPLETED, FAILED, CANCELLED }
@Serializable data class TransferTask(
    val id: String = newId(), val kind: TaskKind, val title: String, val state: TaskState = TaskState.QUEUED,
    val accountId: Long = 0, val profileId: String = "legacy",
    val source: String = "", val destination: String = "", val itemId: String = "", val folderId: String = "", val vaultId: String = "",
    val versionId: String = newId(), val total: Long = 0, val done: Long = 0, val attempts: Int = 0,
    val nextAttemptAt: Long = 0, val createdAt: Long = now(), val updatedAt: Long = now(),
    val error: String = "", val payload: String = "{}", val pendingMessageId: Long = 0,
    val pendingPartPath: String = "", val sourceSha256: String = "", val parts: List<Part> = emptyList(),
    val ruleId: String = "", val allowDuplicate: Boolean = false,
    val speedBytesPerSecond: Long = 0, val priority: Int = 0, val completionNotified:Boolean=false,
)
@Serializable data class UploadSpec(val mime: String = "application/octet-stream", val chunkBytes: Long = 0, val currentPlainSize: Long = 0, val currentStoredHash: String = "", val thumbnail: Part? = null, val thumbnailAttempted: Boolean = false, val paceUntil: Long = 0, val preparedSource:Boolean=false, val preparedHash:String="", val preparedSize:Long=0)
@Serializable data class SourceReceipt(val uri: String, val size: Long, val modifiedAt: Long, val hash: String, val itemId: String, val uploadedAt: Long = now())
@Serializable data class BackupRule(
    val id: String = newId(), val name: String, val treeUri: String, val folderId: String = "", val vaultId: String = "",
    val enabled: Boolean = true, val since: Long = 0, val recursive: Boolean = true,
    val lastScanAt: Long = 0, val lastSuccessAt: Long = 0, val error: String = "",
)
@Serializable data class Preferences(
    val wifiOnly: Boolean = false, val chargingOnly: Boolean = false, val autoRetry: Boolean = true,
    val uploadPaceKiB: Int = 0, val chunkMiB: Int = 512, val cacheMiB: Int = 512, val trashDays: Int = 30,
    val lockEnabled: Boolean = false, val hideNotifications: Boolean = true, val secureScreen: Boolean = false,
    val showGrid: Boolean = false, val detectDuplicates: Boolean = true, val autoSync: Boolean = true,
    val webDavUrl: String = "", val webDavUser: String = "", val sharingUrl: String = "",
    val backupNotifications: Boolean = true, val scanIntervalHours: Int = 6,
)
@Serializable data class Conflict(val id: String = newId(), val entityId: String, val kept: Envelope, val incoming: Envelope, val createdAt: Long = now())
@Serializable data class JournalEntry(val envelope: Envelope, val messageId: Long = 0, val pending: Boolean = false)
@Serializable data class Snapshot(val schema: Int = 2, val accountId: Long, val createdAt: Long = now(), val entities: List<Envelope>)
@Serializable data class ShareResult(val id: String, val url: String, val expiresAt: Long)
@Serializable data class VaultDescriptor(
    val id: String = newId(), val label: String, val salt: String, val iterations: Int = 600_000,
    val passwordWrap: String, val recoveryWrap: String, val createdAt: Long = now(),
)

data class LibraryState(
    val accountId: Long = 0, val accountName: String = "", val loaded: Boolean = false,
    val items: List<Item> = emptyList(), val folders: List<Folder> = emptyList(), val versions: List<Version> = emptyList(),
    val tasks: List<TransferTask> = emptyList(), val rules: List<BackupRule> = emptyList(), val local: Map<String, LocalItem> = emptyMap(),
    val preferences: Preferences = Preferences(), val lockedVaults: Int = 0, val unlockedVaultIds: Set<String> = emptySet(),
    val vaults: List<VaultDescriptor> = emptyList(), val conflicts: List<Conflict> = emptyList(),
    val syncing: Boolean = false, val lastSyncAt: Long = 0, val error: String? = null, val notice: String? = null,
    val cacheBytes: Long = 0, val offlineBytes: Long = 0, val pendingOperations: Int = 0,
    val incoming: List<ImportCandidate> = emptyList(), val organization: List<OrganizationChange> = emptyList(),
    val lastShare: ShareResult? = null,
    val healthProblems: List<String> = emptyList(),
    val sendCooldownUntil: Long = 0,
    val itemVaults: Map<String, String> = emptyMap(), val folderVaults: Map<String, String> = emptyMap(),
    val shares: List<ShareResult> = emptyList(),
)
data class ImportCandidate(val messageId: Long, val name: String, val mime: String, val size: Long, val date: Long)
data class OrganizationChange(val itemId: String, val name: String, val fromFolderId: String, val destination: String)

fun cleanName(value: String): String {
    val result = value.trim()
    require(result.isNotEmpty() && result.length <= 180 && result != "." && result != ".." && result.none { it == '/' || it == '\\' || it.isISOControl() }) { "Use a name of 1–180 characters without slashes or control characters." }
    return result
}
fun normalizeTags(value: String) = value.split(',').map { it.trim().take(32) }.filter { it.isNotEmpty() }.distinct().take(16)
fun matches(item: Item, query: String, type: String = "all", minBytes: Long = 0, favorites: Boolean = false): Boolean =
    item.deletedAt == 0L && (!favorites || item.favorite) && item.size >= minBytes &&
        (type == "all" || (type == "document" && !item.mime.startsWith("image/") && !item.mime.startsWith("video/") && !item.mime.startsWith("audio/")) || item.mime.startsWith("$type/")) &&
        (query.isBlank() || (item.name + " " + item.tags.joinToString(" ")).contains(query.trim(), true))
