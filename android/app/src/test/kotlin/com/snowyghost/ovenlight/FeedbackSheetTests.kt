package com.snowyghost.ovenlight

import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.accessibility.enableAccessibilityChecks
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.unit.Density
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.net.URI
import java.util.Base64
import kotlinx.coroutines.CompletableDeferred
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Send feedback in each state it has, and the screenshot's PNG, which needs Android's Bitmap. */
@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "w412dp-h915dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class FeedbackSheetTests {
    @get:Rule
    val composeRule = createComposeRule()

    @Before
    fun checks() {
        composeRule.enableAccessibilityChecks()
    }

    private val recipes = WebApp(name = "Recipe Box", startURL = URI("https://recipe-box.tail1.ts.net/"), discovered = true)
    private val guide = WebApp(name = "House Guide", startURL = URI("https://house-guide.tail2.ts.net/"), membershipID = "m1", sharedBy = "Riley")

    private enum class Look(val dark: Boolean, val fontScale: Float) { Light(false, 1f), Dark(true, 1f), LargeText(false, 2f) }

    private var look by mutableStateOf(Look.Light)
    private var dismissed = 0
    private val sent = mutableListOf<FeedbackPayload>()
    private var answer = CompletableDeferred<FeedbackResult>()

    private fun screenshot(): Bitmap = Bitmap.createBitmap(90, 160, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.rgb(200, 120, 60)) }

    private fun show(app: WebApp, image: Bitmap? = null) {
        composeRule.setContent {
            val configuration = Configuration(LocalConfiguration.current).apply {
                uiMode = (uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or
                    (if (look.dark) Configuration.UI_MODE_NIGHT_YES else Configuration.UI_MODE_NIGHT_NO)
                fontScale = look.fontScale
            }
            CompositionLocalProvider(LocalConfiguration provides configuration, LocalDensity provides Density(LocalDensity.current.density, look.fontScale)) {
                OvenlightTheme {
                    FeedbackSheet(
                        app, image, URI("https://recipe-box.tail1.ts.net/soup"), density = 1f,
                        send = { payload -> sent += payload; answer.await() },
                        onDismiss = { dismissed += 1 },
                    )
                }
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

    private fun text(text: String) = composeRule.onNodeWithText(text)
    private val sendButton get() = text("Send")
    private val field get() = composeRule.onNode(hasSetTextAction())

    @Test
    fun testANoteToTheOwnersOwnApp() {
        show(recipes)
        inEveryLook {
            text("Send feedback").assertIsDisplayed()
            text("What's working, or what isn't?").assertIsDisplayed()
            text("Goes to your feedback in People & sharing.").assertIsDisplayed()
            composeRule.onNodeWithContentDescription("Close").assertIsDisplayed()
            text("Include screenshot").assertDoesNotExist()
            sendButton.assertIsNotEnabled()
        }
        field.performTextInput("   ")
        sendButton.assertIsNotEnabled()
        field.performTextInput("The soup page overlaps")
        sendButton.assertIsEnabled().performClick()
        composeRule.waitUntil { sent.isNotEmpty() }
        inEveryLook {
            composeRule.onNodeWithContentDescription("Sending").assertIsDisplayed()
            sendButton.assertDoesNotExist()
        }
        assertEquals(FeedbackPayload(note = "The soup page overlaps", pageUrl = "https://recipe-box.tail1.ts.net/soup"), sent.single())

        answer.complete(FeedbackResult.Sent)
        composeRule.mainClock.advanceTimeBy(200)
        inEveryLook {
            text(ConnectionCopy.feedbackSent).assertIsDisplayed()
            text(ConnectionCopy.feedbackSentDetail(to = null)).assertIsDisplayed()
        }
        assertEquals("still showing it was sent", 0, dismissed)
        composeRule.mainClock.advanceTimeBy(1_500)
        composeRule.waitForIdle()
        assertEquals("closes 1.4 s after", 1, dismissed)
    }

    @Test
    fun testAScreenshotToTheOwnerOfASharedApp() {
        show(guide, screenshot())
        inEveryLook {
            text("Goes to Riley, who runs House Guide.").assertIsDisplayed()
            text("Include screenshot").performScrollTo().assertIsDisplayed().assertIsOn()
            composeRule.onNodeWithContentDescription("Screenshot of House Guide").performScrollTo().assertIsDisplayed()
            // The screenshot alone is enough to send.
            sendButton.assertIsEnabled()
        }
        text("Include screenshot").performClick()
        composeRule.waitForIdle()
        text("Include screenshot").assertIsOff()
        composeRule.onNodeWithContentDescription("Screenshot of House Guide").assertDoesNotExist()
        sendButton.assertIsNotEnabled()
        text("Include screenshot").performClick()
        composeRule.waitForIdle()
        sendButton.performClick()
        composeRule.waitUntil(timeoutMillis = 5_000) { sent.isNotEmpty() }
        val png = Base64.getDecoder().decode(required(sent.single().screenshotPngBase64))
        assertEquals("a PNG", listOf(0x89, 0x50, 0x4E, 0x47), png.take(4).map { it.toInt() and 0xFF })
        assertEquals("", sent.single().note)

        answer.complete(FeedbackResult.Sent)
        composeRule.mainClock.advanceTimeBy(200)
        inEveryLook { text("Riley will see it.").assertIsDisplayed() }
    }

    @Test
    fun testAFailureShowsUnderTheNoteAndSendingAgainWorks() {
        show(guide)
        field.performTextInput("hello")
        sendButton.performClick()
        composeRule.waitUntil { sent.isNotEmpty() }
        answer.complete(FeedbackResult.Failed(ConnectionCopy.feedbackTooMany))
        composeRule.waitForIdle()
        inEveryLook {
            text(ConnectionCopy.feedbackTooMany).assertIsDisplayed()
            text("Goes to Riley, who runs House Guide.").assertDoesNotExist()
            sendButton.assertIsEnabled()
        }
        assertEquals(0, dismissed)
        answer = CompletableDeferred()
        sendButton.performClick()
        composeRule.waitUntil { sent.size == 2 }
        // The failure clears while it sends again.
        text(ConnectionCopy.feedbackTooMany).assertDoesNotExist()
    }

    @Test
    fun testANoteTooLongIsRefusedBeforeSending() {
        show(recipes)
        field.performTextInput("é".repeat(FeedbackPayload.maxNote + 1))
        sendButton.performClick()
        composeRule.waitForIdle()
        inEveryLook { text("Keep the note under 4,000 characters.").assertIsDisplayed() }
        assertTrue("nothing sent", sent.isEmpty())
    }

    @Test
    fun testShrinksLargeScreenshotsUnderTheLimit() {
        // 400 by 800 points at 3 pixels a point, as the iOS test draws it: stripes of every hue.
        val image = Bitmap.createBitmap(1200, 2400, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(image)
        val paint = Paint()
        for (y in 0 until 800 step 2) {
            paint.color = Color.HSVToColor(floatArrayOf(360f * y / 800, 1f, 1f))
            canvas.drawRect(3f * (y % 7), 3f * y, 1200f, 3f * (y + 1), paint)
        }
        val full = required(FeedbackPayload.png(image, density = 3f, limit = Int.MAX_VALUE))
        val limited = required(FeedbackPayload.png(image, density = 3f, limit = full.size - 1))
        assertTrue(limited.size < full.size)
        assertNull("nothing fits", FeedbackPayload.png(image, density = 3f, limit = 10))
        assertFalse("the snapshot itself is kept", image.isRecycled)
    }

    private fun <T : Any> required(value: T?): T {
        assertNotNull(value)
        return value!!
    }
}
