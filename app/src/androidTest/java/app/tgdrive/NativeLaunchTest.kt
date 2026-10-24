package app.tgdrive

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class NativeLaunchTest {
    @get:Rule val compose=createAndroidComposeRule<MainActivity>()
    @Test fun actualActivityStartsWithNativeAccountConfiguration() {
        compose.onNodeWithText("TG Drive").assertIsDisplayed()
        compose.onNodeWithText("Connect your own app").assertIsDisplayed()
        compose.onNodeWithText("Continue").assertIsNotEnabled()
        compose.onNodeWithText("Account").performClick()
        compose.onNodeWithText("Your accounts").assertIsDisplayed()
    }
}
