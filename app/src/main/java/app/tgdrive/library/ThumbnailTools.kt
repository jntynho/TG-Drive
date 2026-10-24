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
        val part=version.thumbnail?:return@withContext null
        require(part.storedSize in 1..524_400)
        val vault=repository.itemVault(item.id)
        repository.cache!!.lease(version.id).use {
            val raw=repository.cache!!.raw(version,part)
            val bytes=if(part.encrypted)ByteArrayOutputStream().also{out->raw.inputStream().use{repository.vaults.decrypt(vault,CheckedInput(it,currentCoroutineContext()[Job]){repository.vaults.isUnlocked(vault)},out,"${item.id}/${version.id}/-1")}}.toByteArray() else raw.readBytes()
            ensureActive();check(repository.vaults.isUnlocked(vault))
            BitmapFactory.decodeByteArray(bytes,0,bytes.size)
        }
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
