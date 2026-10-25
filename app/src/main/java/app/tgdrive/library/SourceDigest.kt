package app.tgdrive.library

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest

internal data class SourceDigest(val sha256:String,val size:Long,val parts:List<String>)

/** Whole-source and per-part hashes in the same I/O pass; no plaintext vault staging. */
internal suspend fun digestSource(input:InputStream,chunkBytes:Long,copy:OutputStream?=null,onBytes:suspend(Long)->Unit={}):SourceDigest {
    require(chunkBytes in 1..Long.MAX_VALUE/128)
    val full=MessageDigest.getInstance("SHA-256")
    var part=MessageDigest.getInstance("SHA-256")
    val hashes=mutableListOf<String>()
    val buffer=ByteArray(128*1024)
    var total=0L;var inPart=0L
    while(true){
        currentCoroutineContext().ensureActive()
        val read=input.read(buffer)
        if(read<0)break
        if(read==0)continue
        require(total<=chunkBytes*128-read){"This upload exceeds the 128-part safety limit."}
        full.update(buffer,0,read);copy?.write(buffer,0,read)
        var offset=0
        while(offset<read){
            val n=minOf((chunkBytes-inPart), (read-offset).toLong()).toInt()
            part.update(buffer,offset,n);inPart+=n;offset+=n
            if(inPart==chunkBytes){hashes.add(part.digest().hexDigest());part=MessageDigest.getInstance("SHA-256");inPart=0}
        }
        total+=read;onBytes(total)
    }
    if(inPart>0)hashes.add(part.digest().hexDigest())
    return SourceDigest(full.digest().hexDigest(),total,hashes)
}

internal fun ByteArray.hexDigest():String=joinToString(""){"%02x".format(it)}
