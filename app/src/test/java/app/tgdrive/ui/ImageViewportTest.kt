package app.tgdrive.ui

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.IntSize
import org.junit.Assert.*
import org.junit.Test

class ImageViewportTest {
    @Test fun fittedImageCannotBeDraggedOffscreen() {
        val view=IntSize(400,800);val wide=IntSize(1600,800)
        assertEquals(Offset.Zero,boundImagePan(Offset(500f,500f),1f,view,wide))
        assertEquals(Offset(200f,0f),boundImagePan(Offset(500f,500f),2f,view,wide))
        assertEquals(Offset(-200f,0f),boundImagePan(Offset(-500f,-500f),2f,view,wide))
    }
    @Test fun portraitPanUsesImageHeightAndResetsAtFit() {
        val view=IntSize(400,800);val photo=IntSize(400,800)
        assertEquals(Offset(200f,400f),boundImagePan(Offset(900f,900f),2f,view,photo))
        assertEquals(Offset.Zero,boundImagePan(Offset(900f,900f),1f,view,photo))
        assertEquals("privacy",settingsParent("vaults"))
    }
}
