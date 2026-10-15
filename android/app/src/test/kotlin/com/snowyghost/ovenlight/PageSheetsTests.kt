package com.snowyghost.ovenlight

import android.Manifest
import android.content.res.Configuration
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.accessibility.enableAccessibilityChecks
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.Density
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.snowyghost.ovenlight.MediaCapturePolicy.MediaCaptureType
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** The download sheet and the capture prompt, in light, dark and at twice the text size. */
@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "w411dp-h891dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PageSheetsTests {
    @get:Rule
    val composeRule = createComposeRule()

    @Before
    fun enableChecks() {
        composeRule.enableAccessibilityChecks()
        // The manifest's normal permission, which Robolectric doesn't grant by itself.
        shadowOf(RuntimeEnvironment.getApplication()).grantPermissions(Manifest.permission.HIDE_OVERLAY_WINDOWS)
    }

    private enum class Look(val dark: Boolean, val fontScale: Float) { Light(false, 1f), Dark(true, 1f), LargeText(false, 2f) }

    private var look by mutableStateOf(Look.Light)

    private fun show(content: @Composable () -> Unit) {
        composeRule.setContent {
            val configuration = Configuration(LocalConfiguration.current).apply {
                uiMode = (uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or
                    (if (look.dark) Configuration.UI_MODE_NIGHT_YES else Configuration.UI_MODE_NIGHT_NO)
                fontScale = look.fontScale
            }
            CompositionLocalProvider(LocalConfiguration provides configuration, LocalDensity provides Density(LocalDensity.current.density, look.fontScale)) {
                OvenlightTheme { content() }
            }
        }
    }

    private fun inEveryLook(check: () -> Unit) {
        for (each in Look.entries) {
            look = each
            composeRule.waitForIdle()
            check()
            composeRule.checkAccessibility()
        }
    }

    private fun assertShows(vararg texts: String) = texts.forEach {
        composeRule.onNodeWithText(it).assertIsDisplayed()
        CopyRules.assertNeutral(it)
    }

    @Test
    fun testAFileOffersSaveShareAndOpen() {
        val chosen = mutableListOf<String>()
        show { DownloadSheetContent("Recipes export.csv", "2.1 kB", canOpen = true, { chosen += "save" }, { chosen += "share" }, { chosen += "open" }) }
        inEveryLook { assertShows("Recipes export.csv", "2.1 kB", "Save", "Share", "Open") }
        composeRule.onNodeWithText("Save").performClick()
        composeRule.onNodeWithText("Share").performClick()
        composeRule.onNodeWithText("Open").performClick()
        assertEquals(listOf("save", "share", "open"), chosen)
    }

    @Test
    fun testAFileNothingOpensOffersSaveAndShare() {
        show { DownloadSheetContent("page.html", "312 B", canOpen = false, {}, {}, {}) }
        inEveryLook {
            assertShows("page.html", "Save", "Share")
            composeRule.onNodeWithText("Open").assertDoesNotExist()
        }
    }

    @Test
    fun testThePromptNamesTheAppOrTheHostAsking() {
        val answers = mutableListOf<Boolean>()
        var prompt by mutableStateOf(PageCapture.Prompt(MediaCaptureType.Camera, null, DialogAnswer(false) { answers += it }))
        show { CapturePromptDialog(capturePromptTitle(prompt, "Recipe Box"), prompt.answer::invoke) }
        inEveryLook { assertShows("Allow Recipe Box to use the camera?", "Allow", "Don't allow") }
        composeRule.onNodeWithText("Allow").performClick()
        assertEquals(listOf(true), answers)

        val expected = mapOf(
            PageCapture.Prompt(MediaCaptureType.Microphone, null, DialogAnswer(false) {}) to "Allow Recipe Box to use the microphone?",
            PageCapture.Prompt(MediaCaptureType.CameraAndMicrophone, null, DialogAnswer(false) {}) to "Allow Recipe Box to use the camera and microphone?",
            PageCapture.Prompt(MediaCaptureType.Camera, "meet.example.com", DialogAnswer(false) {}) to "meet.example.com, inside Recipe Box, wants to use the camera",
            PageCapture.Prompt(MediaCaptureType.Microphone, "meet.example.com", DialogAnswer(false) {}) to "meet.example.com, inside Recipe Box, wants to use the microphone",
            PageCapture.Prompt(MediaCaptureType.CameraAndMicrophone, "meet.example.com", DialogAnswer(false) {}) to
                "meet.example.com, inside Recipe Box, wants to use the camera and microphone",
        )
        for ((each, title) in expected) {
            prompt = each
            inEveryLook { assertShows(title) }
        }
    }
}
