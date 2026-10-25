package app.tgdrive.ui

import app.tgdrive.library.*
import org.junit.Assert.*
import org.junit.Test

class LibraryUsageTest {
    @Test fun usageCountsCurrentVisibleFilesWithoutInventingQuotaOrExposingVaultContents() {
        val photo=Item(id="photo",name="Photo.jpg",versionId="v1",mime="image/jpeg",size=100)
        val song=photo.copy(id="song",mime="audio/mpeg",size=200)
        val state=LibraryState(items=listOf(photo,song,photo.copy(id="trash",deletedAt=1,size=999),photo.copy(id="pending",versionId="",size=999),photo.copy(id="locked",size=300)),itemVaults=mapOf("locked" to "vault"))
        val usage=libraryUsage(state)
        assertEquals(2,usage.count);assertEquals(300L,usage.bytes);assertEquals(listOf(100L,0L,200L,0L),usage.groups)
        assertEquals(600L,libraryUsage(state.copy(unlockedVaultIds=setOf("vault"))).bytes)
    }
    @Test fun pickerExcludesDescendantsAndSettingsDoNotOfferRemovedRoutes() {
        val folders=listOf(Folder("a","A"),Folder("b","B","a"),Folder("c","C","b"),Folder("other","Other"))
        assertEquals(setOf("a","b","c"),folderDescendants("a",folders))
        val routes=settingDestinations(LibraryState()).map{it.route}.toSet()
        assertTrue("accounts" in routes)
        assertTrue(routes.intersect(setOf("organize","conflicts","connections")).isEmpty())
    }
    @Test fun transferStartRequiresWorkAndCanResumePausedAlongsideActiveUploads() {
        assertFalse(canStartTransfers(LibraryState()))
        val finished=TransferTask(kind=TaskKind.UPLOAD,title="Done",state=TaskState.COMPLETED)
        assertFalse(canStartTransfers(LibraryState(tasks=listOf(finished))))
        assertFalse(canStartTransfers(LibraryState(tasks=listOf(finished.copy(state=TaskState.FAILED)))))
        assertTrue(canStartTransfers(LibraryState(tasks=listOf(finished.copy(state=TaskState.QUEUED)))))
        assertTrue(canStartTransfers(LibraryState(tasks=listOf(finished.copy(state=TaskState.TRANSFERRING),finished.copy(id="paused",state=TaskState.PAUSED)))))
        assertTrue(canStartTransfers(LibraryState(pendingOperations=1)))
        assertFalse(canStartTransfers(LibraryState(pendingOperations=1,tasks=listOf(finished.copy(state=TaskState.TRANSFERRING)))))
    }

    @Test fun backwardAuthAndSettingsTransitionsFollowTheHierarchy() {
        assertFalse(authStepForward(app.tgdrive.data.LoginStep.CODE,app.tgdrive.data.LoginStep.PHONE))
        assertFalse(authStepForward(app.tgdrive.data.LoginStep.PASSWORD,app.tgdrive.data.LoginStep.CODE))
        assertTrue(authStepForward(app.tgdrive.data.LoginStep.PHONE,app.tgdrive.data.LoginStep.CODE))
        assertTrue(settingsForward("privacy#Encrypted vaults","vaults"))
        assertFalse(settingsForward("vaults","privacy"))
        assertFalse(settingsForward("transfers#Part size","home"))
    }

}
