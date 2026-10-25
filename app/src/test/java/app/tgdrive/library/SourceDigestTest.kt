package app.tgdrive.library

import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.security.MessageDigest

class SourceDigestTest {
    @Test fun samePassCopiesAndVerifiesPartsAcrossReadBoundaries()=runTest {
        val bytes=ByteArray(340_123){(it%251).toByte()}
        val copy=ByteArrayOutputStream()
        val result=digestSource(bytes.inputStream(),32_768,copy)
        assertArrayEquals(bytes,copy.toByteArray());assertEquals(bytes.size.toLong(),result.size)
        assertEquals(MessageDigest.getInstance("SHA-256").digest(bytes).hexDigest(),result.sha256)
        val expected=bytes.asList().chunked(32_768).map{MessageDigest.getInstance("SHA-256").digest(it.toByteArray()).hexDigest()}
        assertEquals(expected,result.parts)
    }
    @Test fun emptySourceHasNoInventedPartAndTooManyPartsAreRejected()=runTest {
        val empty=digestSource(byteArrayOf().inputStream(),1)
        assertEquals(0L,empty.size);assertTrue(empty.parts.isEmpty())
        try{digestSource(ByteArray(129).inputStream(),1);fail("Must reject more than 128 parts")}catch(_:IllegalArgumentException){}
    }
}
