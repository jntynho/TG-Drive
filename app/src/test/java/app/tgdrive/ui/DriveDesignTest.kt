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
    @Test fun phoneDraftRestoresButLoginCodesAreNotRetained() {
        var shown by mutableStateOf(true)
        var step by mutableStateOf(LoginStep.PHONE)
        render {
            val holder=androidx.compose.runtime.saveable.rememberSaveableStateHolder()
            if(shown)holder.SaveableStateProvider("auth") { AuthPanel(DriveState(login=step),{_,_->},{},{},{}) }
        }
        compose.onNodeWithText("Phone number").performTextInput("+212600000000")
        compose.runOnIdle{shown=false};compose.waitForIdle()
        compose.runOnIdle{shown=true};compose.waitForIdle()
        compose.onNodeWithText("+212600000000").assertExists()
        compose.runOnIdle{step=LoginStep.CODE}
        compose.onNode(hasSetTextAction()).performTextInput("12345")
        compose.runOnIdle{shown=false};compose.waitForIdle()
        compose.runOnIdle{shown=true};compose.waitForIdle()
        compose.onNodeWithText("12345").assertDoesNotExist()
    }

    @Test fun busySignInDisablesInputAndImeSubmitsOnlyOnce() {
        var busy by mutableStateOf(false)
        var calls=0
        render{AuthPanel(DriveState(login=LoginStep.PHONE,busy=busy),{_,_->},{calls++;busy=true},{},{})}
        compose.onNodeWithText("Phone number").performTextInput("+212600000000")
        compose.onNodeWithText("Phone number").performImeAction()
        compose.onNodeWithText("Phone number").assertIsNotEnabled()
        compose.onNodeWithText("Continue").assertIsNotEnabled()
        assertEquals(1,calls)
    }

    @Test fun malformedApiCredentialsExplainWhyContinueIsDisabled() {
        render{AuthPanel(DriveState(login=LoginStep.CONFIGURE),{_,_->},{},{},{})}
        compose.onNodeWithText("API ID").performTextInput("-1")
        compose.onNodeWithText("API hash").performTextInput("g".repeat(32))
        compose.onNodeWithText("Continue").assertIsNotEnabled()
        compose.onNodeWithText("Enter a positive numeric API ID.").assertExists()
        compose.onNodeWithText("Enter the 32-character API hash from my.telegram.org.").assertExists()
        compose.onNodeWithText("API ID").performTextReplacement("12345")
        compose.onNodeWithText("API hash").performTextReplacement("a".repeat(32))
        compose.onNodeWithText("Continue").assertIsEnabled()
    }

    @Test fun folderPickerHidesLockedDestinationsAndNamesVaultRoot() {
        val folders=listOf(app.tgdrive.library.Folder("public","Public"),app.tgdrive.library.Folder("private","Secret folder"))
        val state=LibraryState(folders=folders,folderVaults=mapOf("private" to "locked"))
        render{FolderPicker(state,"",{}){}}
        compose.onNodeWithText("Public",substring=true).assertExists()
        compose.onNodeWithText("Secret folder",substring=true).assertDoesNotExist()
        compose.onNodeWithText("All files").assertExists()
    }

    @Test fun busyActionRowsAreSemanticallyDisabled() {
        var clicked=false
        render{CompositionLocalProvider(LocalDriveActionsEnabled provides false){ActionRow("Rename"){clicked=true}}}
        compose.onNodeWithText("Rename").assertIsNotEnabled()
        compose.onNodeWithText("Rename").performTouchInput{click()}
        assertFalse(clicked)
    }

    @Test fun audioTransportClampsSeekAndCanPauseWhileBuffering() {
        var seek=-1L
        var toggles=0
        render{Column(Modifier.padding(24.dp)){
            Text("Morning light.mp3")
            AudioTransportControls(95_000,100_000,true,true,buffering=true,onToggle={toggles++},onSeek={seek=it})
        }}
        compose.onNodeWithContentDescription("Forward 10 seconds").performClick()
        assertEquals(100_000L,seek)
        compose.onNodeWithContentDescription("Back 10 seconds").performClick()
        assertEquals(85_000L,seek)
        compose.onNodeWithContentDescription("Pause audio").performClick()
        assertEquals(1,toggles)
        compose.onNodeWithContentDescription("Seek audio").assertIsEnabled()
        screenshot("audio-transport")
    }

    @Test fun largeTextGalleryFiltersStayReachableByScrolling() {
        var selected="All"
        render{
            val density=androidx.compose.ui.platform.LocalDensity.current
            CompositionLocalProvider(androidx.compose.ui.platform.LocalDensity provides androidx.compose.ui.unit.Density(density.density,2f)){
                Box(Modifier.width(320.dp)){GalleryFilters("All"){selected=it}}
            }
        }
        compose.onNodeWithText("Videos").performScrollTo().assertIsDisplayed().performClick()
        assertEquals("Videos",selected)
    }

    @Test fun largeTextHomeKeepsTheAddActionAndDetailsReachable() {
        render{
            val density=androidx.compose.ui.platform.LocalDensity.current
            CompositionLocalProvider(androidx.compose.ui.platform.LocalDensity provides androidx.compose.ui.unit.Density(density.density,2f)){
                Box(Modifier.width(320.dp)){HomeContent(LibraryState(),onOpen={},onPreview={},onMenu={},onUpload={})}
            }
        }
        compose.onNodeWithText("Add files").performScrollTo().assertIsDisplayed()
        compose.onNodeWithContentDescription("About library storage").performScrollTo().performClick()
        compose.onNodeWithText("Your indexed library").assertIsDisplayed()
    }

    @Test fun activityUsesOneFilterAndKeepsPerFileActionsReachable() {
        var paused="";var retried=""
        val active=app.tgdrive.library.TransferTask(id="active",kind=app.tgdrive.library.TaskKind.UPLOAD,title="Weekend.jpg",state=app.tgdrive.library.TaskState.TRANSFERRING,total=24_000_000,done=18_000_000)
        val failed=active.copy(id="failed",title="Retry.pdf",state=app.tgdrive.library.TaskState.FAILED,error="Connection interrupted")
        val saved=active.copy(id="saved",title="Coast.jpg",state=app.tgdrive.library.TaskState.COMPLETED,updatedAt=System.currentTimeMillis())
        render{TransfersContent(LibraryState(tasks=listOf(active,failed,saved)),TransferCallbacks(pause={paused=it},retry={retried=it}))}
        compose.onNodeWithText("All transfers").assertIsDisplayed()
        compose.onNodeWithText("Pause all").assertDoesNotExist()
        compose.onNodeWithContentDescription("Pause Weekend.jpg").performClick();assertEquals("active",paused)
        compose.onNodeWithContentDescription("Filter transfers").performClick()
        compose.onNode(hasText("Failed") and SemanticsMatcher.keyIsDefined(androidx.compose.ui.semantics.SemanticsProperties.Selected)).performClick()
        compose.onNodeWithText("Weekend.jpg").assertDoesNotExist()
        compose.onNodeWithContentDescription("Actions for Retry.pdf").performClick()
        compose.onNodeWithText("Try again").performClick();assertEquals("failed",retried)
        compose.onNodeWithContentDescription("Filter transfers").performClick()
        compose.onNodeWithText("Done").performClick()
        compose.onNodeWithText("Coast.jpg").assertIsDisplayed()
        compose.onNodeWithText("Retry.pdf").assertDoesNotExist()
        screenshot("activity-done")
    }

    @Test fun lockedTransferRowsHideTheOriginalNameAndError() {
        val task=app.tgdrive.library.TransferTask(kind=app.tgdrive.library.TaskKind.UPLOAD,title="Secret photo.jpg",vaultId="locked",state=app.tgdrive.library.TaskState.FAILED,error="Secret photo.jpg could not be read")
        render{TransfersContent(LibraryState(tasks=listOf(task)))}
        compose.onNodeWithText("Protected file").assertIsDisplayed()
        compose.onAllNodes(hasText("Secret photo.jpg",substring=true),useUnmergedTree=true).assertCountEquals(0)
        compose.onNodeWithText("Unlock the vault to view transfer details.").assertIsDisplayed()
    }

    @Test fun accountWaitDisablesUploadRetryButKeepsTheQueueVisible() {
        val task=app.tgdrive.library.TransferTask(kind=app.tgdrive.library.TaskKind.UPLOAD,title="Waiting.jpg",state=app.tgdrive.library.TaskState.RETRY_WAIT)
        render{TransfersContent(LibraryState(tasks=listOf(task),sendCooldownUntil=System.currentTimeMillis()+160_000))}
        compose.onNodeWithContentDescription("Actions for Waiting.jpg").performClick()
        compose.onNodeWithText("Resume").assertIsNotEnabled()
        compose.onNodeWithText("Cancel transfer").assertIsEnabled()
    }

    @Test fun globalTransferActionsLiveInTheHeaderMenu() {
        var pauses=0
        val task=app.tgdrive.library.TransferTask(kind=app.tgdrive.library.TaskKind.UPLOAD,title="Active.jpg",state=app.tgdrive.library.TaskState.TRANSFERRING)
        render{TransferGlobalMenu(LibraryState(tasks=listOf(task)),TransferCallbacks(pauseAll={pauses++}))}
        compose.onNodeWithText("Pause all").assertDoesNotExist()
        compose.onNodeWithContentDescription("Transfer options").performClick()
        compose.onNodeWithText("Pause all").performClick()
        assertEquals(1,pauses)
    }

    @Test fun largeTextActivityKeepsWaitingActionsAndCompletionHistoryReachable() {
        val task=app.tgdrive.library.TransferTask(kind=app.tgdrive.library.TaskKind.UPLOAD,title="A long queued filename.pdf",total=12_000_000)
        render{
            val density=androidx.compose.ui.platform.LocalDensity.current
            CompositionLocalProvider(androidx.compose.ui.platform.LocalDensity provides androidx.compose.ui.unit.Density(density.density,2f)){
                Box(Modifier.width(320.dp)){TransfersContent(LibraryState(tasks=listOf(task,task.copy(id="saved",title="Saved.pdf",state=app.tgdrive.library.TaskState.COMPLETED,updatedAt=System.currentTimeMillis()))))}
            }
        }
        compose.onNodeWithContentDescription("Actions for A long queued filename.pdf").performScrollTo().assertIsDisplayed().performClick()
        compose.onNodeWithText("Cancel transfer").assertIsDisplayed()
        compose.onNodeWithText("Cancel transfer").performClick()
        compose.onNodeWithText("Clear finished").performScrollTo().assertIsDisplayed()
    }

    @Test fun lockedScreenDisplaysAuthenticationFailureAndKeepsRecoveryReachable() {
        var unlocks=0
        var settings=0
        render{LockedScreen("Device authentication was not completed. Try again.",{unlocks++},{settings++})}
        compose.onNodeWithText("Device authentication was not completed. Try again.").assertIsDisplayed()
        compose.onNodeWithText("Unlock TG Drive").performScrollTo().performClick()
        compose.onNodeWithText("Open Android security settings").performScrollTo().performClick()
        assertEquals(1,unlocks);assertEquals(1,settings)
        screenshot("locked")
    }

    @Test @Config(qualifiers="w640dp-h360dp-xhdpi")
    fun landscapeLargeTextAuthenticationKeepsInputsAndContinueReachable() {
        render{
            val density=androidx.compose.ui.platform.LocalDensity.current
            CompositionLocalProvider(androidx.compose.ui.platform.LocalDensity provides androidx.compose.ui.unit.Density(density.density,2f)){
                AuthPanel(DriveState(login=LoginStep.CONFIGURE),{_,_->},{},{},{})
            }
        }
        compose.onNodeWithText("API hash").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Continue").performScrollTo().assertIsDisplayed()
        screenshot("sign-in-landscape-large-text")
    }

}
