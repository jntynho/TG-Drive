package app.tgdrive.library

import org.junit.Assert.*
import org.junit.Test

class MetadataValidationTest {
    private fun operation(body:String)=Envelope(kind=EntityKind.ITEM,entityId="item",deviceId="test",body=body)
    @Test fun `remote names cannot create unsafe archive paths`() {
        assertTrue(runCatching{validateOperation(operation(Item(id="item",name="../escape.txt").json()))}.isFailure)
        validateOperation(operation(Item(id="item",name="旅程.pdf").json()))
    }
    @Test fun `body identity cannot redirect an envelope to another file`() {
        assertTrue(runCatching{validateOperation(operation(Item(id="other",name="safe.txt").json()))}.isFailure)
    }
    @Test fun `missing or mismatched parts are rejected before content access`() {
        val v=Version(id="v",itemId="item",size=10,sha256="",parts=listOf(Part(1,1,10,10,"")))
        assertTrue(runCatching{validateOperation(Envelope(kind=EntityKind.VERSION,entityId="v",deviceId="test",body=v.json()))}.isFailure)
    }
}
