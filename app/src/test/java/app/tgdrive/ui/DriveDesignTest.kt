package app.tgdrive.ui

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.unit.dp
import app.tgdrive.data.DriveState
import app.tgdrive.data.LoginStep
import app.tgdrive.library.LibraryState
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.LooperMode
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class, qualifiers = "w393dp-h852dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
class DriveDesignTest {
    @get:Rule val compose = createEmptyComposeRule()
    private lateinit var controller: ActivityController<ComponentActivity>
    @Before fun start() { controller = Robolectric.buildActivity(ComponentActivity::class.java).setup().visible() }
    @After fun stop() { controller.pause().stop().destroy() }

    private fun render(content: @Composable () -> Unit) {
        controller.get().setContent { DriveTheme { Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) { content() } } }
    }
    private fun screenshot(name: String) {
        val file = File("build/design-previews/$name.png").apply { parentFile?.mkdirs() }
        compose.waitForIdle()
        compose.runOnIdle {
            val view = controller.get().window.decorView
            val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
            view.draw(Canvas(bitmap))
            file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
        }
    }

    @Test fun everySettingsDestinationRemainsReachableInGroupedList() {
        var opened = ""
        render { Column { DrivePageHeader("Settings", {}, close = true); SettingsHome(LibraryState()) { opened = it } } }
        screenshot("settings")
        val destinations = linkedMapOf(
            "Transfers and background" to "transfers", "Storage and offline" to "storage",
            "Index and recovery" to "index", "Vaults and privacy" to "privacy",
            "Optional connections" to "connections", "Organize by rules" to "organize",
            "Resolve conflicts" to "conflicts", "Diagnostics" to "diagnostics",
            "About and update identity" to "about",
        )
        destinations.forEach { (label, route) ->
            compose.onNode(hasScrollToNodeAction()).performScrollToNode(hasText(label))
            compose.onNodeWithText(label).performClick()
            assertEquals(route, opened)
        }
    }

    @Test fun switchRowHasOneLabelledToggleAndRetainsState() {
        render { Column {
            DrivePageHeader("Vaults and privacy", {})
            Column(Modifier.padding(16.dp)) { DriveGroup {
                var locked by remember { mutableStateOf(false) }
                SettingSwitch("App lock", "Use your device credential or biometrics.", locked) { locked = it }
                DriveDivider()
                SettingSwitch("Hide notification details", "Use a generic transfer notification.", true) {}
            } }
        } }
        compose.onAllNodes(isToggleable()).assertCountEquals(2)
        compose.onNodeWithText("App lock").assertIsOff().performClick().assertIsOn()
        screenshot("privacy")
        compose.onNodeWithText("App lock").performClick().assertIsOff()
        screenshot("privacy-off")
    }

    @Test fun bottomBarHasFourDestinationsAndChangesSelection() {
        render { Column(Modifier.fillMaxSize(),verticalArrangement=Arrangement.Bottom) {
            var selected by remember { mutableStateOf(NativeTab.HOME) }
            DriveNavigationBar(selected,{selected=it})
        } }
        listOf("Home","Gallery","Audio","Settings").forEach { title ->
            compose.onNodeWithText(title).performClick().assertIsSelected()
        }
    }

    @Test fun galleryTileOpensPreviewAndItsMenuOnlyOpensActions() {
        val photo=app.tgdrive.library.Item(id="photo",name="Holiday.jpg",mime="image/jpeg",versionId="v1")
        val video=photo.copy(id="video",name="Walk.mp4",mime="video/mp4",versionId="v2")
        var preview="";var menu=""
        render { Column {
            SectionTitle("Gallery","Your photos and videos")
            MediaContent(listOf(photo,video),true,onPreview={preview=it},onMenu={menu=it})
        } }
        compose.onNodeWithText("Holiday.jpg").performClick()
        assertEquals("photo",preview)
        compose.onNodeWithContentDescription("Actions for Walk.mp4").performClick()
        assertEquals("video",menu);assertEquals("photo",preview)
        screenshot("gallery")
    }

    @Test fun homeKeepsLibraryActivityAndBackupsReachable() {
        var route=""
        render { Column {
            Box(Modifier.weight(1f)) { HomeContent(LibraryState(),onOpen={route=it},onPreview={},onMenu={},onUpload={}) }
            DriveNavigationBar(NativeTab.HOME,{})
        } }
        screenshot("home")
        mapOf("Browse all files" to "files", "Activity" to "activity", "Backups" to "backups").forEach { (label,target) ->
            compose.onNodeWithText(label).performClick();assertEquals(target,route)
        }
    }

    @Test fun phoneSignInKeepsContinueDisabledUntilInputAndPassesNumberUnchanged() {
        var submitted = ""
        render { AuthPanel(DriveState(login = LoginStep.PHONE), { _, _ -> }, { submitted = it }, {}, {}) }
        compose.onNodeWithText("Continue").assertIsNotEnabled()
        screenshot("sign-in")
        compose.onNodeWithText("Phone number").performTextInput("+212600000000")
        compose.onNodeWithText("Continue").performScrollTo().assertIsEnabled().performClick()
        assertEquals("+212600000000", submitted)
    }
}
