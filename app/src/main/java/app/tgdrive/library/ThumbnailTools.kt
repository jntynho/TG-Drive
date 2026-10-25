package app.tgdrive.library

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.media.MediaMetadataRetriever
import android.net.Uri
import kotlinx.coroutines.*
import java.io.ByteArrayOutputStream

/** Small, optional cloud thumbnails. Vault thumbnails are authenticated and encrypted too. */
class ThumbnailTools(private val repository: LibraryRepository) {
    suspend fun create(uri: Uri, mime: String): ByteArray? = withContext(Dispatchers.IO) {
        val resolver = repository.context.contentResolver
        val bitmap: Bitmap = when {
            mime.startsWith("image/") -> {
                val bounds=BitmapFactory.Options().apply{inJustDecodeBounds=true}
                resolver.openInputStream(uri)?.use{BitmapFactory.decodeStream(it,null,bounds)}
                var sample=1;while(maxOf(bounds.outWidth,bounds.outHeight)/sample>320)sample*=2
                val decoded=resolver.openInputStream(uri)?.use{BitmapFactory.decodeStream(it,null,BitmapFactory.Options().apply{inSampleSize=sample})}?:return@withContext null
                val orientation=resolver.openInputStream(uri)?.use{runCatching{androidx.exifinterface.media.ExifInterface(it).getAttributeInt(androidx.exifinterface.media.ExifInterface.TAG_ORIENTATION,1)}.getOrDefault(1)}?:1
                orientBitmap(decoded,orientation)
            }
            mime.startsWith("video/") -> {
                val retriever=MediaMetadataRetriever()
                try {
                retriever.setDataSource(repository.context,uri)
                if(android.os.Build.VERSION.SDK_INT>=27)retriever.getScaledFrameAtTime(0,MediaMetadataRetriever.OPTION_CLOSEST_SYNC,320,320)?:return@withContext null else return@withContext null
                } finally { retriever.release() }
            }
            mime.startsWith("audio/") -> {
                val retriever=MediaMetadataRetriever()
                try {
                    retriever.setDataSource(repository.context,uri)
                    val picture=retriever.embeddedPicture?:return@withContext null
                    if(picture.size>16*1024*1024)return@withContext null
                    decodeBounded(picture)?:return@withContext null
                } finally {retriever.release()}
            }
            mime=="application/pdf" -> resolver.openFileDescriptor(uri,"r")?.use{fd->PdfRenderer(fd).use{pdf->
                if(pdf.pageCount==0)return@withContext null
                pdf.openPage(0).use{page->
                    val ratio=320f/maxOf(page.width,page.height)
                    Bitmap.createBitmap((page.width*ratio).toInt().coerceAtLeast(1),(page.height*ratio).toInt().coerceAtLeast(1),Bitmap.Config.ARGB_8888).also{it.eraseColor(Color.WHITE);page.render(it,null,null,PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)}
                }
            }}?:return@withContext null
            else -> return@withContext null
        }
        try { ByteArrayOutputStream().also{bitmap.compress(Bitmap.CompressFormat.JPEG,75,it)}.toByteArray().also{require(it.size<=512*1024)} } finally { bitmap.recycle() }
    }
    suspend fun read(item: Item): Bitmap? = withContext(Dispatchers.IO) {
        val version=repository.version(item.versionId)
        val part=version.thumbnail
        val vault=repository.itemVault(item.id)
        if(part==null && item.mime.startsWith("audio/")) {
            repository.cache!!.lease(version.id).use {
                check(repository.vaults.isUnlocked(vault))
                val local=repository.cache!!.plaintextPath(item)
                val original=repository.state.value.local[item.id]?.sourceUri.orEmpty()
                val candidates=buildList {
                    if(local.exists()&&local.length()==item.size)add(Uri.fromFile(local))
                    if(original.isNotBlank()&&item.sha256.isNotBlank()&&item.size<=32L*1024*1024) {
                        val sourceUri=Uri.parse(original)
                        val verified=try {
                            repository.context.contentResolver.openInputStream(sourceUri)?.use{input->
                                val digest=java.security.MessageDigest.getInstance("SHA-256");val buffer=ByteArray(64*1024);var count=0L
                                while(true){currentCoroutineContext().ensureActive();val n=input.read(buffer);if(n<0)break;count+=n;if(count>32L*1024*1024)break;digest.update(buffer,0,n)}
                                count==item.size&&digest.digest().joinToString(""){"%02x".format(it)}==item.sha256
                            }?:false
                        }catch(e:CancellationException){throw e}catch(_:Exception){false}
                        if(verified)add(sourceUri)
                    }
                }
                for(uri in candidates) {
                    val bytes=try{create(uri,item.mime)}catch(e:CancellationException){throw e}catch(_:Exception){null}
                    ensureActive();check(repository.vaults.isUnlocked(vault))
                    if(bytes!=null)return@withContext decodeBounded(bytes)
                }
            }
        }
        if(part == null) {
            // Imported/older documents may have Telegram's own small preview. Never fetch
            // the original photo or video just to populate a scrolling gallery.
            if(!version.imported || version.parts.size != 1 || repository.itemVault(item.id).isNotEmpty()) return@withContext null
            val document = repository.remote.document(version.parts.single().messageId).document
            val mini = document.minithumbnail?.data
            if(mini != null && mini.size <= 512*1024) return@withContext decodeBounded(mini)
            val thumbnail = document.thumbnail?.file ?: return@withContext null
            if(thumbnail.size !in 1..524_288) return@withContext null
            val downloaded = repository.session.call(org.drinkless.tdlib.TdApi.DownloadFile(thumbnail.id, 1, 0, 0, true), 30_000) as org.drinkless.tdlib.TdApi.File
            if(!downloaded.local.isDownloadingCompleted) return@withContext null
            val file = java.io.File(downloaded.local.path)
            if(file.length() !in 1..524_288) return@withContext null
            return@withContext decodeBounded(file.readBytes())
        }
        require(part.storedSize in 1..524_400)
        repository.cache!!.lease(version.id).use {
            val raw=repository.cache!!.raw(version,part)
            val bytes=if(part.encrypted)ByteArrayOutputStream().also{out->raw.inputStream().use{repository.vaults.decrypt(vault,CheckedInput(it,currentCoroutineContext()[Job]){repository.vaults.isUnlocked(vault)},out,"${item.id}/${version.id}/-1")}}.toByteArray() else raw.readBytes()
            ensureActive();check(repository.vaults.isUnlocked(vault))
            decodeBounded(bytes)
        }
    }
    internal fun decodeBounded(bytes: ByteArray): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes,0,bytes.size,bounds)
        if(bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while(maxOf(bounds.outWidth,bounds.outHeight) / sample > 320) sample *= 2
        return BitmapFactory.decodeByteArray(bytes,0,bytes.size,BitmapFactory.Options().apply { inSampleSize = sample })
    }

}

fun orientBitmap(source:Bitmap,orientation:Int):Bitmap {
    val matrix=android.graphics.Matrix().apply{when(orientation){
        2->postScale(-1f,1f);3->postRotate(180f);4->postScale(1f,-1f)
        5->{postRotate(90f);postScale(-1f,1f)};6->postRotate(90f)
        7->{postRotate(270f);postScale(-1f,1f)};8->postRotate(270f)
    }}
    if(matrix.isIdentity)return source
    return Bitmap.createBitmap(source,0,0,source.width,source.height,matrix,true).also{if(it!==source)source.recycle()}
}
