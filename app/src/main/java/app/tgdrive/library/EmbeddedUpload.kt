package app.tgdrive.library

import java.io.ByteArrayOutputStream
import java.util.Base64
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

/** Immutable creation records carried by their own single, unencrypted document.
 * A zero message ID is a wire-only self-reference, resolved before normal validation/persistence.
 * Later edits continue to use the existing journal and conflict model.
 */
object EmbeddedUpload {
    const val PREFIX="TGDRIVE-FILE/3\n"
    private const val MAX_RAW=65_536
    fun encode(operations:List<Envelope>,limit:Int=1024):String? {
        val raw=operations.json().toByteArray()
        if(raw.size>MAX_RAW)return null
        val output=ByteArrayOutputStream()
        GZIPOutputStream(output).use{it.write(raw)}
        val text=PREFIX+Base64.getEncoder().encodeToString(output.toByteArray())
        return text.takeIf{it.length<=limit.coerceIn(0,1024)}
    }
    fun resolve(text:String,messageId:Long,storedSize:Long):List<Envelope> {
        require(text.startsWith(PREFIX)&&text.length<=1024&&messageId>0&&storedSize>=0)
        val compressed=Base64.getDecoder().decode(text.removePrefix(PREFIX))
        val raw=GZIPInputStream(compressed.inputStream()).use{input->
            val output=ByteArrayOutputStream();val buffer=ByteArray(4096)
            while(true){val n=input.read(buffer);if(n<0)break;require(output.size()+n<=MAX_RAW);output.write(buffer,0,n)}
            output.toByteArray()
        }
        val ops=raw.decodeToString().model<List<Envelope>>()
        require(ops.size==2&&ops.map{it.operationId}.distinct().size==2&&ops.all{it.schema==2&&it.vaultId.isEmpty()&&!it.tombstone})
        val versionOp=ops.single{it.kind==EntityKind.VERSION}
        val itemOp=ops.single{it.kind==EntityKind.ITEM}
        val version=versionOp.body.model<Version>();val item=itemOp.body.model<Item>()
        require(version.id==versionOp.entityId&&item.id==itemOp.entityId&&version.itemId==item.id&&item.versionId==version.id)
        require(version.parts.size==1&&version.thumbnail==null&&version.size==storedSize&&item.size==storedSize&&item.sha256==version.sha256&&version.sha256.length==64)
        val part=version.parts.single()
        require(part.messageId==0L&&part.index==0&&!part.encrypted&&part.plainSize==storedSize&&part.storedSize==storedSize&&part.storedSha256==version.sha256)
        val resolved=version.copy(parts=listOf(part.copy(messageId=messageId)))
        return listOf(versionOp.copy(body=resolved.json()),itemOp).also{values->values.forEach{validateOperation(it)}}
    }
}
