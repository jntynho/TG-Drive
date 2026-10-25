package app.tgdrive

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.tgdrive.ui.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DialogValidationTest {
    @get:Rule val compose=createComposeRule()
    @Test fun invalidFolderNameStaysInDialogAndDoesNotSubmit() {
        var submitted=false
        compose.setContent{DriveTheme{TextInputDialog("New folder","Folder name",onDismiss={},validate=::nameError){submitted=true}}}
        compose.onNodeWithText("Folder name").performTextInput("../bad")
        compose.onNodeWithText("Save").performClick()
        assertFalse(submitted)
        compose.onNodeWithText("../bad").assertIsDisplayed()
        compose.onNode(hasText("without slashes",substring=true)).assertIsDisplayed()
    }
}
