package app.tgdrive.library

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

/** Account-scoped, transactional durable state. Call from IO, never from composable bodies. */
class LibraryDatabase(context: Context, val accountId: Long, private val codec: RowCodec) : SQLiteOpenHelper(context, "library-v2-$accountId.db", null, 1), java.io.Closeable {
    override fun onConfigure(db: SQLiteDatabase) { db.setForeignKeyConstraintsEnabled(true) }
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE entities (id TEXT PRIMARY KEY, kind TEXT NOT NULL, payload TEXT NOT NULL)")
        for (table in listOf("journal", "tasks", "rules", "local", "conflicts", "settings", "sources")) {
            db.execSQL("CREATE TABLE $table (id TEXT PRIMARY KEY, payload TEXT NOT NULL)")
        }
        db.execSQL("CREATE INDEX entities_kind ON entities(kind)")
    }
    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = error("Unsupported database migration: $oldVersion → $newVersion")
    private fun table(name: String): String {
        require(name in setOf("entities", "journal", "tasks", "rules", "local", "conflicts", "settings", "sources")); return name
    }
    fun put(table: String, id: String, payload: String, kind: String? = null) {
        val values = ContentValues().apply { put("id", id); put("payload", codec.encode(table, id, payload)); if (kind != null) put("kind", kind) }
        writableDatabase.insertWithOnConflict(table(table), null, values, SQLiteDatabase.CONFLICT_REPLACE).also { check(it != -1L) { "Could not persist library data" } }
    }
    fun get(table: String, id: String): String? = readableDatabase.query(table(table), arrayOf("payload"), "id = ?", arrayOf(id), null, null, null).use { if (it.moveToFirst()) codec.decode(table, id, it.getString(0)) else null }
    fun all(table: String): List<String> = readableDatabase.query(table(table), arrayOf("id", "payload"), null, null, null, null, "rowid ASC").use { c -> buildList { while (c.moveToNext()) add(codec.decode(table, c.getString(0), c.getString(1))) } }
    fun entries(table: String): List<Pair<String,String>> = readableDatabase.query(table(table),arrayOf("id","payload"),null,null,null,null,"rowid ASC").use { c->buildList{while(c.moveToNext())add(c.getString(0) to codec.decode(table,c.getString(0),c.getString(1)))} }
    fun remove(table: String, id: String) { writableDatabase.delete(table(table), "id = ?", arrayOf(id)) }
    fun transaction(block: () -> Unit) {
        writableDatabase.beginTransaction()
        try { block(); writableDatabase.setTransactionSuccessful() } finally { writableDatabase.endTransaction() }
    }
    fun entity(kind: EntityKind, id: String): Envelope? = get("entities", "${kind.name}:$id")?.model()
    fun entities(): List<Envelope> = all("entities").map { it.model() }
    fun putEntity(value: Envelope) = put("entities", "${value.kind.name}:${value.entityId}", value.json(), value.kind.name)
    fun tasks(): List<TransferTask> = all("tasks").map { it.model() }
    fun putTask(task: TransferTask) = put("tasks", task.id, task.json())
    fun local(id: String) = get("local", id)?.model<LocalItem>() ?: LocalItem(id)
    fun putLocal(value: LocalItem) = put("local", value.itemId, value.json())
    fun preferences() = get("settings", "preferences")?.model<Preferences>() ?: Preferences()
    fun pending() = all("journal").map { it.model<JournalEntry>() }.filter { it.pending }

    /** Preserve concurrent edits; deletion wins until an explicit restore references it. */
    fun apply(entry: JournalEntry): Boolean {
        val op = entry.envelope
        validateOperation(op)
        var changed = false
        transaction {
            val seen = get("journal", op.operationId)?.model<JournalEntry>()
            if (seen != null) {
                // Acknowledging an already applied operation must not replay an ancestor
                // over later local edits or invent a concurrent-edit conflict.
                if(seen.pending && !entry.pending)put("journal",op.operationId,seen.copy(messageId=entry.messageId,pending=false).json())
                return@transaction
            }
            val previous = entity(op.kind, op.entityId)
            if (previous == null || previous.operationId == op.operationId || previous.operationId == op.parentOperationId || previous.operationId in op.resolves) {
                putEntity(op); changed = true
            } else {
                val conflict = Conflict(entityId = op.entityId, kept = previous, incoming = op)
                put("conflicts", conflict.id, conflict.json())
                val incomingWins = if (op.tombstone != previous.tombstone) op.tombstone
                    else compareValuesBy(op, previous, { it.timestamp }, { it.operationId }) > 0
                if (incomingWins) { putEntity(op); changed = true }
                // Deterministic convergence, with both candidates retained for explicit resolution.
            }
            put("journal", op.operationId, entry.json())
        }
        return changed
    }
}
