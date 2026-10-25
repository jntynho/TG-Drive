package app.tgdrive.library

import org.junit.Assert.*
import org.junit.Test

class CloudCodecTest {
    @Test fun `batched journal preserves every operation identity and legacy decoding`() {
        val rows=(1..20).map{Envelope(kind=EntityKind.ITEM,entityId="file-$it",deviceId="phone",body=Item("file-$it","Photo $it.jpg").json())}
        assertEquals(rows,CloudCodec.decodeMany(CloudCodec.encodeBatch(rows)))
        assertEquals(listOf(rows.first()),CloudCodec.decodeMany(CloudCodec.encode(rows.first())))
        assertTrue(runCatching{CloudCodec.encodeBatch(emptyList())}.isFailure)
        assertTrue(runCatching{CloudCodec.decodeMany(CloudCodec.BATCH+"invalid")}.isFailure)
    }

    @Test fun `cloud metadata roundtrips unicode and content identities`() {
        val op = Envelope(kind = EntityKind.ITEM, entityId = "uuid", deviceId = "phone", body = Item("uuid", "Café résumé.pdf", tags = listOf("work")).json())
        assertEquals(op, CloudCodec.decode(CloudCodec.encode(op)))
    }
    @Test fun `unrelated messages are not treated as application metadata`() { assertNull(CloudCodec.decode("personal message")) }
    @Test fun `damaged or newer metadata is rejected without silently changing schema`() {
        assertTrue(runCatching { CloudCodec.decode(CloudCodec.PREFIX + "broken data") }.isFailure)
        assertTrue(runCatching { CloudCodec.decode(CloudCodec.encode(Envelope(schema = 3, kind = EntityKind.ITEM, entityId = "x", deviceId = "x", body = "{}"))) }.isFailure)
    }
    @Test fun `names and tags cannot become traversal paths`() {
        assertTrue(runCatching { cleanName("../file") }.isFailure); assertTrue(runCatching { cleanName("..") }.isFailure)
        assertEquals(listOf("work","travel"), normalizeTags("work, travel, work, "))
    }
}
