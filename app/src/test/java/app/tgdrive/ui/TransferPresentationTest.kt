package app.tgdrive.ui

import app.tgdrive.library.*
import org.junit.Assert.*
import org.junit.Test
import java.util.Calendar

class TransferPresentationTest {
    private fun task(state:TaskState,id:String=state.name)=TransferTask(id=id,kind=TaskKind.UPLOAD,title=id,state=state,createdAt=1,updatedAt=1)
    @Test fun everyStateAppearsOnceAndOnlyAcknowledgedFilesAreCompleted() {
        val tasks=TaskState.entries.map{task(it)}
        val sections=transferSections(tasks,TransferFilter.ALL)
        assertEquals(tasks.map{it.id}.toSet(),sections.flatMap{it.tasks}.map{it.id}.toSet())
        assertEquals(tasks.size,sections.sumOf{it.tasks.size})
        assertEquals(setOf(TaskState.PREPARING,TaskState.TRANSFERRING,TaskState.SYNCING_METADATA),sections.single{it.key=="active"}.tasks.map{it.state}.toSet())
        assertEquals(listOf(TaskState.COMPLETED),transferSections(tasks,TransferFilter.DONE).flatMap{it.tasks}.map{it.state})
        assertEquals(listOf(TaskState.FAILED),transferSections(tasks,TransferFilter.FAILED).flatMap{it.tasks}.map{it.state})
        assertFalse(transferSections(tasks,TransferFilter.ACTIVE).flatMap{it.tasks}.any{it.state in setOf(TaskState.COMPLETED,TaskState.FAILED,TaskState.CANCELLED)})
    }
    @Test fun queueOrderMatchesPriorityAndHistoryUsesTheLocalCompletionDay() {
        val noon=Calendar.getInstance().apply{set(2026,Calendar.OCTOBER,26,12,0,0);set(Calendar.MILLISECOND,0)}.timeInMillis
        val state=listOf(task(TaskState.QUEUED,"ordinary"),task(TaskState.QUEUED,"priority").copy(priority=5),task(TaskState.COMPLETED,"today").copy(updatedAt=noon),task(TaskState.COMPLETED,"yesterday").copy(updatedAt=noon-86_400_000))
        val sections=transferSections(state,TransferFilter.ALL,noon)
        assertEquals(listOf("priority","ordinary"),sections.single{it.key=="waiting"}.tasks.map{it.id})
        assertEquals("today",sections.single{it.key=="today"}.tasks.single().id)
        assertEquals("yesterday",sections.single{it.key=="earlier"}.tasks.single().id)
    }
    @Test fun resumeHonorsTheAccountWaitAndTheCurrentTaskState() {
        val paused=task(TaskState.PAUSED)
        assertFalse(transferCanResume(paused,161_000,1000))
        assertTrue(transferCanResume(paused,161_000,161_000))
        assertTrue(transferCanResume(paused.copy(kind=TaskKind.DOWNLOAD),161_000,1000))
        assertFalse(transferCanResume(task(TaskState.COMPLETED),0,1000))
        assertFalse(transferCanResume(task(TaskState.TRANSFERRING),0,1000))
    }
    @Test fun semicircleRepresentsCompositionAndHasNoInventedEmptyCapacity() {
        assertEquals(listOf(0f,0f,0f,0f),libraryArcShares(listOf(0,0,0,0)))
        val shares=libraryArcShares(listOf(42,168,16,8))
        assertEquals(180f,shares.sum(),0.0001f)
        assertEquals(168f/234*180,shares[1],0.0001f)
        assertEquals(listOf(0f,180f),libraryArcShares(listOf(-1,10)))
    }
}
