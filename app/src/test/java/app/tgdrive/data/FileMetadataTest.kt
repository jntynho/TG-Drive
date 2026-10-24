package app.tgdrive.data

import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class FileMetadataTest {
    @Test fun `Arabic and unicode metadata survive remote caption round trip`() {
        val original = FileMetadata.create("صورة العائلة 📷.jpg", "صور")
        assertEquals(original, FileMetadata.parse(original.caption()))
    }
    @Test fun `ordinary Telegram messages and future versions are ignored`() {
        assertNull(FileMetadata.parse("a private message"))
        assertNull(FileMetadata.parse("TGDRIVE/2\n{}"))
        assertNull(FileMetadata.parse("TGDRIVE/1\n{broken"))
    }
    @Test fun `malicious paths and invalid metadata are rejected`() {
        val id = java.util.UUID.randomUUID()
        assertNull(FileMetadata.parse("TGDRIVE/1\n{\"id\":\"$id\",\"name\":\"../../password\",\"folder\":\"\"}"))
        assertNull(FileMetadata.parse("TGDRIVE/1\n{\"id\":\"not-a-uuid\",\"name\":\"ok\",\"folder\":\"\"}"))
        assertFalse(FileMetadata.validFolder("../private"))
        assertFalse(FileMetadata.validFolder(".."))
        assertFalse(FileMetadata.validFolder("a\nb"))
        assertTrue(FileMetadata.validFolder("مستندات"))
    }
    @Test fun `provider file names cannot escape staging directory`() {
        assertEquals(".._.._file", FileMetadata.safeName("../../file"))
        assertEquals("file", FileMetadata.safeName("  "))
        assertEquals(180, FileMetadata.safeName("a".repeat(500)).length)
    }
}
