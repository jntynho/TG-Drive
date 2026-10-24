package app.tgdrive

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.tgdrive.data.*
import app.tgdrive.ui.DriveScreen
import app.tgdrive.ui.DriveTheme
import org.drinkless.tdlib.Client
import org.drinkless.tdlib.TdApi
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DeviceSmokeTest {
    @get:Rule val compose = createComposeRule()
    @Test fun nativeTdlibLoadsAndExecutes() {
        System.loadLibrary("tdjni")
        val entities = Client.execute(TdApi.GetTextEntities("Visit https://telegram.org"))
        assertTrue(entities.entities.isNotEmpty())
    }
    @Test fun credentialsScreenExplainsStorageAndDoesNotPretendToBeConnected() {
        render(DriveState())
        compose.onNodeWithText("Connect your own app").assertIsDisplayed()
        compose.onNodeWithText("Continue").assertIsNotEnabled()
        compose.onNodeWithContentDescription("Privacy and storage").performClick()
        compose.onNodeWithText("Privacy and storage").assertIsDisplayed()
        compose.onNodeWithText("Close").performClick()
    }
    @Test fun deletingFileRequiresExplicitRemoteDeletionConfirmation() {
        val file = DriveFile(FileMetadata.create("sample.pdf", ""), 100, 2, 1234, 100, "application/pdf")
        var deleted = false
        render(DriveState(login = LoginStep.READY, files = listOf(file)), onDelete = { deleted = true })
        compose.onNodeWithContentDescription("File actions").performScrollTo().performClick()
        compose.onNodeWithText("Delete").performClick()
        compose.onNodeWithText("Delete permanently?").assertIsDisplayed()
        assertFalse(deleted)
        compose.onNodeWithText("Cancel").performClick()
        assertFalse(deleted)
    }
    private fun render(state: DriveState, onDelete: (DriveFile) -> Unit = {}) {
        compose.setContent { DriveTheme { DriveScreen(state, "", {}, { _, _ -> }, {}, {}, {}, onDelete, {}, {}, {}, {}, {}) } }
    }
}
