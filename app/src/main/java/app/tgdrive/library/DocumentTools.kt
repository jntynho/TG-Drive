package app.tgdrive.library

import android.graphics.*
import android.graphics.pdf.PdfDocument
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.*
import java.io.File
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Native PDF creation and bundled on-device Latin OCR. No cloud document processing. */
class DocumentTools(private val repository: LibraryRepository) {
    suspend fun recognize(task: TransferTask) = withContext(Dispatchers.IO) {
        val item = repository.item(task.itemId)
        val source = repository.cache!!.materialize(item, repository.version(item.versionId), task.vaultId)
        repository.setTask(task.copy(state = TaskState.TRANSFERRING))
        val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
        val text = StringBuilder()
        suspend fun read(bitmap: Bitmap) {
            val result = suspendCancellableCoroutine<com.google.mlkit.vision.text.Text> { c ->
                recognizer.process(InputImage.fromBitmap(bitmap,0)).addOnSuccessListener { if(c.isActive)c.resume(it) }
                    .addOnFailureListener { if(c.isActive)c.resumeWithException(it) }
            }
            text.append(result.text).append("\n\n")
            require(text.length <= 500_000) { "OCR text exceeds the local safety limit." }
        }
        try {
            if(item.mime=="application/pdf") {
                ParcelFileDescriptor.open(source,ParcelFileDescriptor.MODE_READ_ONLY).use { fd ->
                    PdfRenderer(fd).use { renderer ->
                        require(renderer.pageCount<=50) { "Split this PDF first: on-device OCR currently supports up to 50 pages per task." }
                        for(index in 0 until renderer.pageCount){
                            ensureActive()
                            renderer.openPage(index).use { page ->
                                val ratio=minOf(2f,2400f/maxOf(page.width,page.height))
                                val bitmap=Bitmap.createBitmap((page.width*ratio).toInt().coerceAtLeast(1),(page.height*ratio).toInt().coerceAtLeast(1),Bitmap.Config.ARGB_8888)
                                try { bitmap.eraseColor(Color.WHITE);page.render(bitmap,null,null,PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY);read(bitmap) } finally { bitmap.recycle() }
                            }
                            repository.setTask(repository.db().get("tasks",task.id)!!.model<TransferTask>().copy(done=(index+1).toLong(),total=renderer.pageCount.toLong()))
                        }
                    }
                }
            } else {
                require(item.mime.startsWith("image/")) { "OCR supports images and image-based PDFs." }
                val bitmap=decode(source)
                try { read(bitmap) } finally { bitmap.recycle() }
            }
            repository.checkAccount(task.accountId)
            repository.setLocal(repository.db().local(item.id).copy(ocrText=text.toString().trim()))
            repository.notice("Text extracted locally. Check recognition accuracy before relying on it.")
        } finally { recognizer.close() }
    }
    suspend fun createPdf(images: List<Uri>, trim: Float, rotate: Int, grayscale: Boolean): File = withContext(Dispatchers.IO) {
        require(images.isNotEmpty()&&images.size<=50)
        val output=File(repository.context.filesDir,"captures/scan-${newId()}.pdf").apply { parentFile!!.mkdirs() }
        val document=PdfDocument()
        try {
            images.forEachIndexed { index,uri ->
                ensureActive()
                val options=BitmapFactory.Options().apply { inJustDecodeBounds=true }
                repository.context.contentResolver.openInputStream(uri)!!.use { BitmapFactory.decodeStream(it,null,options) }
                var sample=1;while(maxOf(options.outWidth,options.outHeight)/sample>2400)sample*=2
                val decoded=repository.context.contentResolver.openInputStream(uri)!!.use { BitmapFactory.decodeStream(it,null,BitmapFactory.Options().apply { inSampleSize=sample }) } ?: error("Selected image cannot be decoded.")
                val orientation=repository.context.contentResolver.openInputStream(uri)!!.use{runCatching{androidx.exifinterface.media.ExifInterface(it).getAttributeInt(androidx.exifinterface.media.ExifInterface.TAG_ORIENTATION,1)}.getOrDefault(1)}
                val source=orientBitmap(decoded,orientation)
                var bitmap=source
                try {
                    val t=trim.coerceIn(0f,0.2f)
                    val matrix=Matrix().apply { postRotate(rotate.toFloat()) }
                    bitmap=Bitmap.createBitmap(source,(source.width*t).toInt(),(source.height*t).toInt(),(source.width*(1-2*t)).toInt().coerceAtLeast(1),(source.height*(1-2*t)).toInt().coerceAtLeast(1),matrix,true)
                    val page=document.startPage(PdfDocument.PageInfo.Builder(595,842,index+1).create())
                    page.canvas.drawColor(Color.WHITE)
                    val ratio=minOf(555f/bitmap.width,802f/bitmap.height)
                    val width=bitmap.width*ratio;val height=bitmap.height*ratio
                    val paint=Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
                    if(grayscale)paint.colorFilter=ColorMatrixColorFilter(ColorMatrix().apply { setSaturation(0f) })
                    page.canvas.drawBitmap(bitmap,null,RectF((595-width)/2,(842-height)/2,(595+width)/2,(842+height)/2),paint)
                    document.finishPage(page)
                }finally { if(bitmap!==source)bitmap.recycle();source.recycle() }
            }
            output.outputStream().use { document.writeTo(it) }
            output
        }catch(e:Exception){output.delete();throw e}finally{document.close()}
    }
    fun decode(file: File, max: Int = 1800): Bitmap {
        val bounds=BitmapFactory.Options().apply { inJustDecodeBounds=true };BitmapFactory.decodeFile(file.absolutePath,bounds)
        var sample=1;while(maxOf(bounds.outWidth,bounds.outHeight)/sample>max)sample*=2
        val bitmap=BitmapFactory.decodeFile(file.absolutePath,BitmapFactory.Options().apply { inSampleSize=sample }) ?: error("Image format is not supported.")
        val orientation=runCatching{androidx.exifinterface.media.ExifInterface(file).getAttributeInt(androidx.exifinterface.media.ExifInterface.TAG_ORIENTATION,1)}.getOrDefault(1)
        return orientBitmap(bitmap,orientation)
    }
}
