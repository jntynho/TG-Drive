package app.tgdrive.data

import android.content.Context
import android.util.AtomicFile
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** Account-scoped cache, never the only copy of uploaded-file metadata. */
class IndexStore(context: Context, userId: Long) {
    private val file = AtomicFile(File(context.filesDir, "index-$userId.json"))
    fun read(): Pair<List<DriveFile>, Set<String>> = runCatching {
        val json = JSONObject(file.openRead().bufferedReader().use { it.readText() })
        val rows = json.getJSONArray("files")
        val files = (0 until rows.length()).mapNotNull { i ->
            runCatching {
                val row = rows.getJSONObject(i)
                DriveFile(FileMetadata.parse(row.getString("caption")) ?: return@mapNotNull null,
                    row.getLong("messageId"), row.getInt("fileId"), row.getLong("size"), row.getInt("date"), row.getString("mime"))
            }.getOrNull()
        }
        val folders = json.optJSONArray("folders") ?: JSONArray()
        files to (0 until folders.length()).map { folders.getString(it) }.filter { FileMetadata.validFolder(it) }.toSet()
    }.getOrDefault(emptyList<DriveFile>() to emptySet())

    fun write(files: List<DriveFile>, folders: Set<String>) {
        val rows = JSONArray()
        files.forEach { f -> rows.put(JSONObject().put("caption", f.metadata.caption()).put("messageId", f.messageId)
            .put("fileId", f.fileId).put("size", f.size).put("date", f.date).put("mime", f.mimeType)) }
        val json = JSONObject().put("files", rows).put("folders", JSONArray(folders.sorted())).toString()
        val stream = file.startWrite()
        try { stream.write(json.toByteArray(Charsets.UTF_8)); file.finishWrite(stream) }
        catch (e: Exception) { file.failWrite(stream); throw e }
    }
}
