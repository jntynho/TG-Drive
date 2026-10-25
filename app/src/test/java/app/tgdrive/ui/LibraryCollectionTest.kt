package app.tgdrive.ui

import app.tgdrive.library.*
import org.junit.Assert.*
import org.junit.Test

class LibraryCollectionTest {
    @Test fun mediaCollectionsExcludeOtherTypesTrashPendingAndLockedVaults() {
        val photo=Item(id="photo",name="trip.jpg",mime="image/jpeg",versionId="v1",folderId="nested")
        val video=photo.copy(id="video",name="trip.mp4",mime="video/mp4",versionId="v2")
        val audio=photo.copy(id="audio",name="song.mp3",mime="audio/mpeg",versionId="v3")
        val state=LibraryState(items=listOf(photo,video,audio,photo.copy(id="trash",deletedAt=1),photo.copy(id="pending",versionId=""),photo.copy(id="secret"),photo.copy(id="pdf",mime="application/pdf")),itemVaults=mapOf("secret" to "vault"))
        assertEquals(setOf("photo","video"),libraryCollection(state,"gallery").map{it.id}.toSet())
        assertEquals(listOf("audio"),libraryCollection(state,"audio").map{it.id})
        assertTrue(libraryCollection(state,"gallery","song").isEmpty())
        assertTrue(libraryCollection(state.copy(unlockedVaultIds=setOf("vault")),"gallery").any{it.id=="secret"})
    }
    @Test fun homeOrdersByCurrentVersionUploadTimeNotRenameTime() {
        val a=Item(id="a",name="new version",versionId="va",createdAt=1,modifiedAt=2)
        val b=Item(id="b",name="renamed",versionId="vb",createdAt=2,modifiedAt=999)
        val state=LibraryState(items=listOf(b,a),versions=listOf(Version("va","a",0,"",emptyList(),createdAt=100),Version("vb","b",0,"",emptyList(),createdAt=3)))
        assertEquals(listOf("a","b"),libraryCollection(state).map{it.id})
    }
}
