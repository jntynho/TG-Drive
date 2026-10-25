package app.tgdrive

import android.graphics.Bitmap
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.tgdrive.ui.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class GalleryGesturesTest {
    @get:Rule val compose=createComposeRule()
    @Test fun doubleTapAnimatesZoomAndReturnsToFit() {
        val bitmap=Bitmap.createBitmap(400,800,Bitmap.Config.ARGB_8888).apply{eraseColor(android.graphics.Color.BLUE)}
        var zoomed=false
        compose.setContent{DriveTheme{Surface(Modifier.fillMaxSize()){GalleryZoomImage(bitmap,Modifier.fillMaxSize(),{}, {zoomed=it})}}}
        compose.onNodeWithContentDescription("Image preview").performTouchInput{doubleClick()}
        compose.waitForIdle();compose.runOnIdle{assertTrue(zoomed)}
        compose.onNodeWithContentDescription("Image preview").performTouchInput{doubleClick()}
        compose.waitForIdle();compose.runOnIdle{assertFalse(zoomed)}
    }
    @Test fun progressivePhotoDecodeRetainsZoomAndExposesAccessibleFitAction() {
        var bitmap by mutableStateOf(Bitmap.createBitmap(200,400,Bitmap.Config.ARGB_8888))
        var zoomed=false
        compose.setContent{DriveTheme{Surface(Modifier.fillMaxSize()){GalleryZoomImage(bitmap,Modifier.fillMaxSize(),{}, {zoomed=it},contentKey="same-original")}}}
        compose.onNodeWithContentDescription("Image preview").performTouchInput{doubleClick()}
        compose.waitForIdle();compose.runOnIdle{assertTrue(zoomed);bitmap=Bitmap.createBitmap(400,800,Bitmap.Config.ARGB_8888)}
        compose.waitForIdle();compose.runOnIdle{assertTrue(zoomed)}
        val semantics=compose.onNode(SemanticsMatcher.keyIsDefined(androidx.compose.ui.semantics.SemanticsActions.CustomActions)).fetchSemanticsNode()
        compose.runOnIdle{assertTrue(semantics.config[androidx.compose.ui.semantics.SemanticsActions.CustomActions].first{it.label=="Fit image"}.action())}
        compose.waitForIdle();compose.runOnIdle{assertFalse(zoomed)}
    }
    @Test fun sortPopupSelectsAndDismissesWithoutChangingSearch() {
        var selected=MediaSort.NEWEST
        compose.setContent{DriveTheme{var query by remember{mutableStateOf("Piano")};androidx.compose.foundation.layout.Column{
            DriveAppHeader("Audio",null,HeaderSearch(query,{query=it},"Search audio"),actions={MediaSortMenu(false,selected){selected=it}})
        }}}
        compose.onNodeWithContentDescription("Sort audio").performClick()
        compose.onNodeWithText("Name: A to Z").performClick()
        compose.runOnIdle{assertEquals(MediaSort.NAME_ASC,selected)}
        compose.onNodeWithText("Piano").assertIsDisplayed()
        compose.onNodeWithText("Name: A to Z").assertDoesNotExist()
    }
}
