package app.tgdrive.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import app.tgdrive.library.Preferences
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Immediate feedback, ordered writes, and a rollback to the saved value on failure. */
internal class PreferenceEditor(
    initial: Preferences,
    private val scope: CoroutineScope,
    private val read: () -> Preferences,
    private val save: suspend ((Preferences) -> Preferences) -> Unit,
    private val report: (Throwable) -> Unit,
) {
    var value by mutableStateOf(initial); private set
    private val writes = Mutex()
    private var pending = 0
    fun observe(saved: Preferences) { if(pending == 0) value = saved }
    fun update(transform: (Preferences) -> Preferences) {
        value = transform(value)
        pending++
        // Start on Main so rapid taps enqueue in their original order. The repository owns IO.
        scope.launch(Dispatchers.Main.immediate) {
            try { writes.withLock { save(transform) } }
            catch(e: CancellationException) { throw e }
            catch(e: Exception) { report(e) }
            finally { pending--; if(pending == 0) value = read() }
        }
    }
}
