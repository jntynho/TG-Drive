package app.tgdrive.data

import org.junit.Assert.*
import org.junit.Test

class FileSelectionTest {
    private fun file(id: Long, name: String, folder: String, size: Long, date: Int) =
        DriveFile(FileMetadata("id-$id", name, folder), id, id.toInt(), size, date, "text/plain")
    private val files = listOf(file(1, "Notes.txt", "", 10, 20), file(2, "رحلة.jpg", "صور", 50, 30), file(3, "notes.pdf", "عمل", 100, 10))

    @Test fun `home contains only root files`() {
        assertEquals(listOf(1L), selectFiles(files, "", "", SortOrder.NEWEST).map { it.messageId })
    }
    @Test fun `search spans folders and ignores case`() {
        assertEquals(listOf(3L, 1L), selectFiles(files, "صور", " NOTES ", SortOrder.SIZE).map { it.messageId })
    }
    @Test fun `Arabic search returns matching file`() {
        assertEquals(listOf(2L), selectFiles(files, "", "رحلة", SortOrder.NEWEST).map { it.messageId })
    }
    @Test fun `folder and sort selection do not mutate the source`() {
        assertEquals(listOf(2L), selectFiles(files, "صور", "", SortOrder.NAME).map { it.messageId })
        assertEquals(3, files.size)
    }
    @Test fun `byte formatting uses binary size boundaries`() {
        assertEquals("0 B", formatBytes(0))
        assertEquals("1023 B", formatBytes(1023))
        assertEquals("1.0 KB", formatBytes(1024))
        assertEquals("1.0 GB", formatBytes(1073741824))
    }
}
