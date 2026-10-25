package app.tgdrive.library

import org.junit.Assert.*
import org.junit.Test

class EmbeddedUploadTest {
    private val hash="a".repeat(64)
    private fun operations():List<Envelope> {
        val item=Item(id="image",name="Image.jpg",size=25,sha256=hash,versionId="version")
        val version=Version("version",item.id,25,hash,listOf(Part(0,0,25,25,hash)))
        return listOf(Envelope(kind=EntityKind.VERSION,entityId=version.id,deviceId="test",body=version.json()),Envelope(kind=EntityKind.ITEM,entityId=item.id,deviceId="test",body=item.json()))
    }
    @Test fun selfReferenceResolvesBeforeValidationAndPreservesOperationIds() {
        val original=operations();val caption=EmbeddedUpload.encode(original)!!
        assertTrue(caption.length<=1024)
        val restored=EmbeddedUpload.resolve(caption,123,25)
        assertEquals(original.map{it.operationId},restored.map{it.operationId})
        assertEquals(123L,restored.first().body.model<Version>().parts.single().messageId)
        restored.forEach{validateOperation(it)}
    }
    @Test fun overflowFallsBackAndMalformedOrMismatchedMessagesAreRejected() {
        assertNull(EmbeddedUpload.encode(operations(),40))
        val caption=EmbeddedUpload.encode(operations())!!
        assertTrue(runCatching{EmbeddedUpload.resolve(caption,123,26)}.isFailure)
        assertTrue(runCatching{EmbeddedUpload.resolve(caption,0,25)}.isFailure)
        assertTrue(runCatching{EmbeddedUpload.resolve(EmbeddedUpload.PREFIX+"broken",123,25)}.isFailure)
    }
    @Test fun encryptedRecordsCannotEnterPublicCaptionPath() {
        val ops=operations().map{it.copy(vaultId="private")}
        val caption=EmbeddedUpload.encode(ops)!!
        assertTrue(runCatching{EmbeddedUpload.resolve(caption,123,25)}.isFailure)
    }
}
