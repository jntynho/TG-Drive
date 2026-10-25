package app.tgdrive.ui

import app.tgdrive.library.Preferences
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class PreferenceEditorTest {
    @Test fun rapidEditsRespondImmediatelyPersistInOrderAndKeepOtherFields() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            var saved=Preferences()
            val editor=PreferenceEditor(saved,this,{saved},{update->delay(100);saved=update(saved)},{throw AssertionError(it)})
            editor.update{it.copy(wifiOnly=true)}
            assertTrue(editor.value.wifiOnly)
            editor.update{it.copy(chargingOnly=true)}
            editor.update{it.copy(wifiOnly=false)}
            assertFalse(editor.value.wifiOnly);assertTrue(editor.value.chargingOnly)
            editor.observe(saved) // An older flow emission must not undo pending taps.
            assertTrue(editor.value.chargingOnly)
            advanceUntilIdle()
            assertFalse(saved.wifiOnly);assertTrue(saved.chargingOnly);assertEquals(saved,editor.value)
        } finally { Dispatchers.resetMain() }
    }
    @Test fun failedSaveRollsBackAndReportsError() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            val saved=Preferences();var errors=0
            val editor=PreferenceEditor(saved,this,{saved},{delay(100);error("Disk unavailable")},{errors++})
            editor.update{it.copy(wifiOnly=true)};assertTrue(editor.value.wifiOnly)
            advanceUntilIdle()
            assertFalse(editor.value.wifiOnly);assertEquals(1,errors)
        } finally { Dispatchers.resetMain() }
    }
}
