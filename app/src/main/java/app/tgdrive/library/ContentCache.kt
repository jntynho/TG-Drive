package app.tgdrive.library

import android.content.Context
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.security.DigestOutputStream
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

class ContentCache(context: Context, accountId: Long, private val remote: TelegramStorage, private val vaults: VaultManager) {
    val root = File(context.cacheDir, "library/$accountId").apply { mkdirs() }
    val offline = File(context.filesDir, "offline/$accountId").apply { mkdirs() }
    val previews = File(root, "previews").apply { mkdirs() }
    private val leases = mutableMapOf<String,Int>()
    fun lease(versionId:String): java.io.Closeable {
        synchronized(leases) { leases[versionId] = (leases[versionId] ?: 0) + 1 }
        return java.io.Closeable { synchronized(leases) { val n=(leases[versionId] ?: 1)-1; if(n<=0)leases.remove(versionId) else leases[versionId]=n } }
    }
    private fun protected(file:File)=synchronized(leases) { leases.keys.any { file.name.startsWith(it) || file.parentFile?.name==it } }
    private val locks = ConcurrentHashMap<String, Mutex>()
    fun segment(value: String): String { require(value.matches(Regex("[a-zA-Z0-9_-]{1,120}"))) { "Invalid content identifier" }; return value }
    private fun rawPath(versionId: String, part: Part, pinned: Boolean) = File(if (pinned) offline else File(root, "parts"), "${segment(versionId)}/${part.index}.bin")
    fun isOffline(version: Version) = (version.size == 0L || version.parts.isNotEmpty()) && version.parts.all { rawPath(version.id, it, true).let { f -> f.exists() && f.length() == it.storedSize } }
    fun cachedRaw(version: Version, part: Part): File? = listOf(rawPath(version.id,part,true),rawPath(version.id,part,false)).firstOrNull { it.exists()&&it.length()==part.storedSize }
    suspend fun raw(version: Version, part: Part, pin: Boolean = false): File = withContext(Dispatchers.IO) {
        val pinned = rawPath(version.id, part, true)
        if (pinned.exists() && pinned.length() == part.storedSize) return@withContext pinned
        val target = rawPath(version.id, part, pin)
        locks.getOrPut("part:${version.id}:${part.index}") { Mutex() }.withLock {
            if (target.exists() && target.length() == part.storedSize) return@withLock target
            val source = remote.readAll(part.messageId)
            check(source.length() == part.storedSize) { "Stored part size does not match its manifest." }
            if (part.storedSha256.isNotEmpty()) check(source.sha256() == part.storedSha256) { "Stored part checksum mismatch. The download was not accepted." }
            target.parentFile!!.mkdirs()
            val temp = File(target.parentFile, "${target.name}.partial")
            try {
                source.inputStream().use { input -> temp.outputStream().use { copyChecked(input, it) } }
                check(temp.renameTo(target)) { "Could not commit cached content." }
            } finally { temp.delete() }
            target
        }
    }
    fun plaintextPath(item: Item): File {
        val ext = item.name.substringAfterLast('.', "bin").takeIf { it.matches(Regex("[a-zA-Z0-9]{1,8}")) } ?: "bin"
        return File(previews, "${segment(item.versionId)}.$ext")
    }
    suspend fun materialize(item: Item, version: Version, vaultId: String, pin: Boolean = false, progress: (Long) -> Unit = {}): File = withContext(Dispatchers.IO) {
        lease(version.id).use {
        require(version.itemId == item.id && version.id == item.versionId)
        require((version.size == 0L || version.parts.isNotEmpty()) && version.parts.map { it.index } == version.parts.indices.toList()) { "Incomplete or unordered file manifest." }
        require(vaults.isUnlocked(vaultId)) { "Unlock the vault to open this file." }
        locks.getOrPut("content:${version.id}") { Mutex() }.withLock {
            val output = plaintextPath(item)
            if (!pin && output.exists() && output.length() == version.size && version.sha256.isNotEmpty() && output.sha256() == version.sha256) return@withLock output
            val temp = File(output.parentFile, "${output.name}.partial")
            val digest = MessageDigest.getInstance("SHA-256")
            try {
                DigestOutputStream(temp.outputStream(), digest).use { destination ->
                    var completed = 0L
                    for (part in version.parts) {
                        ensureActive()
                        val bytes = raw(version, part, pin)
                        if (part.encrypted) {
                            check(vaultId.isNotEmpty()) { "Encrypted content is missing a vault reference." }
                            bytes.inputStream().use { input -> vaults.decrypt(vaultId, CheckedInput(input, currentCoroutineContext()[Job]) { vaults.isUnlocked(vaultId) }, destination, "${item.id}/${version.id}/${part.index}") }
                        } else bytes.inputStream().use { copyChecked(it, destination) }
                        completed += part.plainSize; progress(completed)
                    }
                }
                check(temp.length() == version.size) { "Downloaded size does not match the original." }
                val hash = digest.digest().joinToString("") { "%02x".format(it) }
                if (version.sha256.isNotEmpty()) check(hash == version.sha256) { "Content checksum mismatch. The file was not marked verified." }
                require(vaults.isUnlocked(vaultId)) { "The vault was locked while downloading. No decrypted preview was committed." }
                check(temp.renameTo(output)) { "Could not finalize the downloaded file." }
                output
            } finally { temp.delete() }
        }
        }
    }
    fun removeVersion(versionId:String) {
        unpin(versionId)
        File(root,"parts/${segment(versionId)}").deleteRecursively()
        previews.listFiles()?.filter { it.name.startsWith(versionId+".") || it.name.startsWith(versionId+"-") }?.forEach { it.delete() }
    }
    fun unpin(versionId: String) { File(offline, segment(versionId)).deleteRecursively() }
    fun clearSensitive() { previews.deleteRecursively(); previews.mkdirs() }
    fun clearUnpinned() { root.deleteRecursively(); root.mkdirs(); previews.mkdirs() }
    fun bytes(file: File = root): Long = file.walkTopDown().filter { it.isFile }.sumOf { it.length() }
    fun trim(budget: Long) {
        var total = bytes()
        if (total <= budget) return
        root.walkTopDown().filter { it.isFile && !it.name.endsWith(".partial") && !protected(it) }.sortedBy { it.lastModified() }.forEach {
            if (total > budget) { val size = it.length(); if (it.delete()) total -= size }
        }
    }
}

suspend fun copyChecked(input: InputStream, output: OutputStream, limit: Long = Long.MAX_VALUE, progress: (Long) -> Unit = {}): Long {
    val buffer = ByteArray(128 * 1024); var copied = 0L
    while (copied < limit) {
        currentCoroutineContext().ensureActive()
        val n = input.read(buffer, 0, minOf(buffer.size.toLong(), limit - copied).toInt())
        if (n < 0) break
        output.write(buffer, 0, n); copied += n; progress(copied)
    }
    return copied
}
fun readLimited(input: InputStream, maxBytes: Int): ByteArray {
    val output = java.io.ByteArrayOutputStream(); val buffer = ByteArray(8192)
    while (true) { val n = input.read(buffer); if (n < 0) break; require(output.size() + n <= maxBytes) { "Input exceeds the allowed size." }; output.write(buffer, 0, n) }
    return output.toByteArray()
}

class CheckedInput(input:InputStream,private val job:Job?,private val permitted:()->Boolean={true}):java.io.FilterInputStream(input) {
    private fun checkRead(){job?.ensureActive();check(permitted()){ "Unlock the vault to continue." }}
    override fun read():Int{checkRead();return `in`.read()}
    override fun read(b:ByteArray,off:Int,len:Int):Int{checkRead();return `in`.read(b,off,len)}
}
