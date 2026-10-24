package app.tgdrive.data

import org.json.JSONObject
import java.util.UUID

data class FileMetadata(val id: String, val name: String, val folder: String) {
    fun caption(): String = PREFIX + JSONObject().put("id", id).put("name", name).put("folder", folder).toString()

    companion object {
        const val PREFIX = "TGDRIVE/1\n"
        fun create(name: String, folder: String) = FileMetadata(UUID.randomUUID().toString(), safeName(name), folder)
        fun parse(caption: String): FileMetadata? = runCatching {
            if (!caption.startsWith(PREFIX) || caption.length > 2048) return null
            val json = JSONObject(caption.removePrefix(PREFIX))
            val id = json.getString("id")
            UUID.fromString(id)
            val name = json.getString("name")
            val folder = json.getString("folder")
            require(name == safeName(name) && name.isNotBlank())
            require(folder.isEmpty() || validFolder(folder))
            FileMetadata(id, name, folder)
        }.getOrNull()
        fun validFolder(name: String) = name.isNotBlank() && name.length <= 60 &&
            name == name.trim() && name != "." && name != ".." && name.none { it == '/' || it == '\\' || it.isISOControl() }
        fun safeName(name: String): String = name.replace(Regex("[\\p{Cntrl}/\\\\]"), "_")
            .trim().take(180).ifBlank { "file" }
    }
}

data class DriveFile(
    val metadata: FileMetadata,
    val messageId: Long,
    val fileId: Int,
    val size: Long,
    val date: Int,
    val mimeType: String,
) {
    val name get() = metadata.name
    val folder get() = metadata.folder
}

enum class SortOrder { NEWEST, NAME, SIZE }

fun selectFiles(files: List<DriveFile>, folder: String, query: String, order: SortOrder): List<DriveFile> {
    val selected = files.filter { (query.isNotBlank() || it.folder == folder) &&
        (query.isBlank() || it.name.contains(query.trim(), ignoreCase = true)) }
    return when (order) {
        SortOrder.NEWEST -> selected.sortedByDescending { it.date }
        SortOrder.NAME -> selected.sortedBy { it.name.lowercase() }
        SortOrder.SIZE -> selected.sortedByDescending { it.size }
    }
}

fun formatBytes(bytes: Long): String {
    if (bytes < 1024) return "$bytes B"
    val unit = kotlin.math.min(4, (kotlin.math.ln(bytes.toDouble()) / kotlin.math.ln(1024.0)).toInt())
    return java.lang.String.format(java.util.Locale.ROOT, "%.1f %s", bytes / Math.pow(1024.0, unit.toDouble()), arrayOf("B", "KB", "MB", "GB", "TB")[unit])
}
