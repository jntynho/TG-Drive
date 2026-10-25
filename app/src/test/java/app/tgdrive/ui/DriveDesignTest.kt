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
        render { Column { DriveAppHeader("Settings",null,HeaderSearch("",{},"Search settings")); SettingsHome(LibraryState()) { opened = it } } }
        screenshot("settings")
        val destinations = linkedMapOf(
            "Telegram accounts" to "accounts", "Transfers" to "transfers", "Storage & offline" to "storage",
            "Library & recovery" to "index", "Privacy & security" to "privacy", "Diagnostics" to "diagnostics", "About TG Drive" to "about",
        )
        destinations.forEach { (label, route) ->
            compose.onNode(hasScrollToNodeAction()).performScrollToNode(hasText(label))
            compose.onNodeWithText(label).performClick()
            assertEquals(route, opened)
        }
    }

    @Test fun settingsSearchFindsTheActualOptionAndItsSection() {
        var opened=""
        render{SettingsHome(LibraryState(),"Unmetered"){opened=it}}
        compose.onNodeWithText("Unmetered network only").assertIsDisplayed().performClick()
        assertEquals("transfers#Unmetered network only",opened)
        compose.onNodeWithText("No matching settings").assertDoesNotExist()
    }
    @Test fun pendingCollectionNeverPaintsResultsFromThePreviousPrivacyScope() {
        var locked by mutableStateOf(false)
        val gate=java.util.concurrent.CountDownLatch(1)
        try{
            render{
                val current=locked
                val rows=computedList(current){if(current){gate.await(4,java.util.concurrent.TimeUnit.SECONDS);emptyList()}else listOf("Private file")}
                Column{rows?.forEach{Text(it)};if(rows==null)Text("Updating")}
            }
            compose.waitUntil(5000){compose.onAllNodesWithText("Private file").fetchSemanticsNodes().isNotEmpty()}
            compose.runOnIdle{locked=true}
            compose.onNodeWithText("Private file").assertDoesNotExist()
            compose.onNodeWithText("Updating").assertIsDisplayed()
        }finally{gate.countDown()}
    }
    @Test fun codeSignInExposesRecoveryButHonorsTheServerWait() {
        var changed=false
        render{AuthPanel(DriveState(login=LoginStep.CODE,canResend=true,resendAt=System.currentTimeMillis()+160_000),{_,_->},{},{},{},changePhone={changed=true})}
        compose.onNode(hasText("Request another code in",substring=true)).performScrollTo().assertIsNotEnabled()
        compose.onNodeWithText("Change phone number").performScrollTo().performClick()
        assertTrue(changed)
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

    @Test fun galleryTileIsOneAccessibleTargetWithoutFilenameOrOverflow() {
        val photo=app.tgdrive.library.Item(id="photo",name="Holiday.jpg",mime="image/jpeg",versionId="v1")
        val video=photo.copy(id="video",name="Walk.mp4",mime="video/mp4",versionId="v2")
        var preview=""
        render { Column {
            DriveAppHeader("Gallery",null)
            MediaContent(listOf(photo,video),true,onPreview={preview=it},onMenu={error("No gallery tile menu")})
        } }
        val bounds=compose.onNodeWithContentDescription("Holiday.jpg").fetchSemanticsNode().boundsInRoot
        assertEquals(.8f,bounds.width/bounds.height,.01f)
        compose.onNodeWithContentDescription("Holiday.jpg").performClick()
        assertEquals("photo",preview)
        compose.onNodeWithText("Holiday.jpg").assertDoesNotExist()
        compose.onNodeWithContentDescription("Actions for Walk.mp4").assertDoesNotExist()
        compose.onNodeWithContentDescription("Walk.mp4").performClick()
        assertEquals("video",preview)
        screenshot("gallery")
    }
    @Test fun adaptiveHeaderSearchEditsQueryAndHasNoAccountAvatar() {
        var value=""
        render {var query by remember{mutableStateOf("")};DriveAppHeader("Audio",null,HeaderSearch(query,{query=it;value=it},"Search audio"))}
        compose.onNodeWithText("Audio").assertIsDisplayed()
        compose.onNodeWithText("Search audio").performTextInput("Piano")
        assertEquals("Piano",value)
        compose.onNodeWithContentDescription("Clear search").performClick()
        assertEquals("",value)
        compose.onNodeWithContentDescription("Manage accounts").assertDoesNotExist()
    }
    @Test fun galleryHeaderSearchIsAvailableAndRemovedSettingsAreNotListed() {
        render{Column{DriveAppHeader("Gallery",null,HeaderSearch("",{},"Search photos and videos"));SettingsHome(LibraryState()) {}}}
        compose.onAllNodes(hasSetTextAction()).assertCountEquals(1)
        listOf("Organize by rules","Resolve conflicts","Optional connections").forEach{compose.onNodeWithText(it).assertDoesNotExist()}
        compose.onNodeWithText("Telegram accounts").assertIsDisplayed()
    }

    @Test fun homeKeepsLibraryActivityAndBackupsReachable() {
        var route=""
        render { Column {
            Box(Modifier.weight(1f)) { HomeContent(LibraryState(),onOpen={route=it},onPreview={},onMenu={},onUpload={}) }
            DriveNavigationBar(NativeTab.HOME,{})
        } }
        screenshot("home")
        mapOf("All files" to "files", "Activity" to "activity", "Backups" to "backups").forEach { (label,target) ->
            compose.onNodeWithText(label).performClick();assertEquals(target,route)
        }
    }

    @Test fun galleryLongPressStartsSelectionWithoutOpeningMedia() {
        val photo=app.tgdrive.library.Item(id="photo",name="Holiday.jpg",mime="image/jpeg",versionId="v1")
        var opened=false
        render {
            var selecting by remember{mutableStateOf(false)}
            var selected by remember{mutableStateOf(emptySet<String>())}
            MediaContent(listOf(photo),true,onPreview={if(selecting)selected=if(it in selected)selected-it else selected+it else opened=true},onMenu={},selected=selected,selecting=selecting,onLongSelect={selecting=true;selected=setOf(it)})
        }
        compose.onNodeWithContentDescription("Holiday.jpg").performTouchInput{longClick()}
        assertFalse(opened)
        compose.onNodeWithContentDescription("Holiday.jpg").assertIsSelected()
        compose.onNodeWithContentDescription("Holiday.jpg").performClick().assertIsNotSelected()
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
