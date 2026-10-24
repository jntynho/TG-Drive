package app.tgdrive.data

import android.content.Context
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class IndexStoreTest {
    private val context: Context get() = RuntimeEnvironment.getApplication()
    @Test fun `saved index survives reopening with remote identity and Arabic folder`() {
        val file = DriveFile(FileMetadata.create("عقد.pdf", "عمل"), 444, 12, 2048, 100, "application/pdf")
        IndexStore(context, 10).write(listOf(file), setOf("عمل", "Empty"))
        val (files, folders) = IndexStore(context, 10).read()
        assertEquals(listOf(file), files)
        assertEquals(setOf("عمل", "Empty"), folders)
    }
    @Test fun `accounts never reuse each others index`() {
        val file = DriveFile(FileMetadata.create("secret.txt", ""), 1, 2, 100, 100, "text/plain")
        IndexStore(context, 20).write(listOf(file), emptySet())
        assertTrue(IndexStore(context, 21).read().first.isEmpty())
    }
    @Test fun `corrupted index is recoverable without crashing`() {
        context.openFileOutput("index-30.json", Context.MODE_PRIVATE).use { it.write("broken".toByteArray()) }
        assertTrue(IndexStore(context, 30).read().first.isEmpty())
    }
}
