package app.tgdrive

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.tgdrive.library.Item
import app.tgdrive.library.LocalItem
import app.tgdrive.ui.DriveTheme
import app.tgdrive.ui.NativeFileCard
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class NativeFileCardTest {
    @get:Rule val compose=createComposeRule()
    @Test fun nativeGridCardOpensAndKeepsActionsSeparate() {
        var opened=false;var menu=false
        compose.setContent { DriveTheme { NativeFileCard(Item(name="receipt.pdf",mime="application/pdf"),null,false,false,true,onClick={opened=true},onLongClick={},onMenu={menu=true}) } }
        compose.onNodeWithContentDescription("File actions").performClick()
        assertTrue(menu);assertFalse(opened)
        compose.onNodeWithText("receipt.pdf").performClick()
        assertTrue(opened)
    }
    @Test fun selectionIsAnnouncedAndLongPressSelectsWithoutOpening() {
        var selected=false;var opened=false
        compose.setContent { DriveTheme { NativeFileCard(Item(id="test",name="photo.jpg",mime="image/jpeg",versionId="v1"),LocalItem("test",pinnedVersion="v1"),true,true,false,onClick={opened=true},onLongClick={selected=true},onMenu={}) } }
        compose.onNode(isSelected()).assertExists().performTouchInput{longClick()}
        assertTrue(selected);assertFalse(opened)
    }
}
