package app.tgdrive.library

import java.io.ByteArrayOutputStream
import java.util.Base64
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

object CloudCodec {
    const val PREFIX = "TGDRIVE-META/2\n"
    const val BATCH = "TGDRIVE-BATCH/2\n"
    const val DOCUMENT = "TGDRIVE-INDEX/2 "
    const val PART = "TGDRIVE-PART/2\n"
    const val THUMB = "TGDRIVE-THUMB/2\n"
    const val BACKUP = "TGDRIVE-BACKUP/2 "
    fun encode(value: Envelope): String {
        val raw = value.json().toByteArray()
        require(raw.size <= 1_048_576) { "Metadata operation is too large" }
        val out = ByteArrayOutputStream()
        GZIPOutputStream(out).use { it.write(raw) }
        return PREFIX + Base64.getEncoder().encodeToString(out.toByteArray())
    }
    fun encodeBatch(values:List<Envelope>):String {
        require(values.size in 1..64)
        val raw=values.json().toByteArray();require(raw.size<=1_048_576)
        val out=ByteArrayOutputStream();GZIPOutputStream(out).use{it.write(raw)}
        return BATCH+Base64.getEncoder().encodeToString(out.toByteArray())
    }
    fun decodeMany(text:String):List<Envelope> {
        if(!text.startsWith(BATCH))return listOfNotNull(decode(text))
        require(text.length<=1_500_000)
        val compressed=Base64.getDecoder().decode(text.removePrefix(BATCH))
        val bytes=GZIPInputStream(compressed.inputStream()).use{input->
            val output=ByteArrayOutputStream();val buffer=ByteArray(8192)
            while(true){val count=input.read(buffer);if(count<0)break;require(output.size()+count<=1_048_576);output.write(buffer,0,count)}
            output.toByteArray()
        }
        return bytes.decodeToString().model<List<Envelope>>().also{rows->require(rows.size in 1..64);rows.forEach{require(it.schema==2);validateOperation(it)}}
    }
    fun decode(text: String): Envelope? {
        if (!text.startsWith(PREFIX)) return null
        require(text.length <= 1_500_000) { "Metadata envelope too large" }
        val compressed = Base64.getDecoder().decode(text.removePrefix(PREFIX))
        val bytes = GZIPInputStream(compressed.inputStream()).use { input ->
            val out = ByteArrayOutputStream(); val buffer = ByteArray(8192)
            while (true) { val n = input.read(buffer); if (n < 0) break; require(out.size() + n <= 1_048_576) { "Decompressed metadata too large" }; out.write(buffer, 0, n) }
            out.toByteArray()
        }
        return bytes.decodeToString().model<Envelope>().also { require(it.schema == 2) }
    }
}
