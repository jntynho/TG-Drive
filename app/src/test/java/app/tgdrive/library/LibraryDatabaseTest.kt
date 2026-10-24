package app.tgdrive.library

import org.junit.Assert.*
import org.junit.Test
import org.junit.Before
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class LibraryDatabaseTest {
    @Before fun clean() { for (id in 910..921) RuntimeEnvironment.getApplication().deleteDatabase("library-v2-$id.db") }
    private fun db(id: Long = 910) = LibraryDatabase(RuntimeEnvironment.getApplication(), id, PlainRows)
    private fun op(id: String, parent: String = "", name: String = "file", stamp: Long = 1, deleted: Boolean = false) = Envelope(operationId = id, kind = EntityKind.ITEM, entityId = "item", parentOperationId = parent, deviceId = id, timestamp = stamp, body = Item("item", name, deletedAt = if (deleted) stamp else 0).json(), tombstone = deleted)
    @Test fun `task and metadata survive database reopening`() {
        val first = db(); val task = TransferTask(kind = TaskKind.UPLOAD, title = "report.pdf", accountId = 910)
        first.transaction { first.putTask(task); first.apply(JournalEntry(op("a"), pending = true)) }; first.close()
        db().use { reopened -> assertEquals(task, reopened.tasks().single()); assertEquals("a", reopened.entity(EntityKind.ITEM, "item")!!.operationId); assertEquals(1, reopened.pending().size) }
    }
    @Test fun `acknowledgement is idempotent and clears pending write`() {
        db().use { d -> val e = op("a"); d.apply(JournalEntry(e, pending = true)); d.apply(JournalEntry(e, 123)); d.apply(JournalEntry(e, 123)); assertEquals(1, d.all("journal").size); assertTrue(d.pending().isEmpty()) }
    }
    @Test fun `concurrent edits converge while preserving both candidates`() {
        val a = op("a", name = "original"); val b = op("b", "a", "phone", 2); val c = op("c", "a", "tablet", 3)
        db(911).use { d -> listOf(a,b,c).forEach { d.apply(JournalEntry(it)) }; assertEquals("c", d.entity(EntityKind.ITEM, "item")!!.operationId); assertEquals(1, d.all("conflicts").size) }
        db(912).use { d -> listOf(a,c,b).forEach { d.apply(JournalEntry(it)) }; assertEquals("c", d.entity(EntityKind.ITEM, "item")!!.operationId); assertEquals(1, d.all("conflicts").size) }
    }
    @Test fun `stale rename does not resurrect a trashed item`() {
        db().use { d -> d.apply(JournalEntry(op("a"))); d.apply(JournalEntry(op("delete", "a", stamp = 2, deleted = true))); d.apply(JournalEntry(op("rename", "a", "renamed", 999))); assertTrue(d.entity(EntityKind.ITEM, "item")!!.tombstone) }
    }
    @Test fun `explicit restore can reference current deletion`() {
        db().use { d -> d.apply(JournalEntry(op("a"))); d.apply(JournalEntry(op("delete", "a", stamp = 2, deleted = true))); d.apply(JournalEntry(op("restore", "delete", stamp = 3))); assertFalse(d.entity(EntityKind.ITEM, "item")!!.tombstone) }
    }
    @Test fun `failed transaction does not leave half a queue operation`() {
        db().use { d -> runCatching { d.transaction { d.putTask(TransferTask(kind = TaskKind.PIN, title = "x")); error("simulated crash") } }; assertTrue(d.tasks().isEmpty()) }
    }
    @Test fun `account scoped databases never show another account tasks`() {
        db(920).use { it.putTask(TransferTask(kind = TaskKind.VERIFY, title = "private")) }
        db(921).use { assertTrue(it.tasks().isEmpty()) }
    }
}
