package com.snowyghost.ovenlight

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.net.URI
import java.util.Base64
import kotlinx.coroutines.CompletableDeferred
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
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

    private val recipes = WebApp(name = "Recipe Box", startURL = URI("https://recipe-box.tail1.ts.net/"), discovered = true)
    private val guide = WebApp(name = "House Guide", startURL = URI("https://house-guide.tail2.ts.net/"), membershipID = "m1", sharedBy = "Riley")

    private val look = mutableStateOf(Look.Light)
    private var dismissed = 0
    private val sent = mutableListOf<FeedbackPayload>()
    private var answer = CompletableDeferred<FeedbackResult>()

    private fun screenshot(): Bitmap = Bitmap.createBitmap(90, 160, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.rgb(200, 120, 60)) }

    /** Whether the sheet is composed: the lock takes it away, and its draft brings it back. */
    private var showing by mutableStateOf(true)

    private fun show(app: WebApp, image: Bitmap? = null, draft: FeedbackDraft = FeedbackDraft(image, URI("https://recipe-box.tail1.ts.net/soup"))) {
        composeRule.setContent {
            // The app's scope, which outlives the sheet.
            val scope = rememberCoroutineScope()
            Looked(look.value) {
                if (showing) {
                    FeedbackSheet(
                        app, draft, density = 1f,
                        send = { payload -> sent += payload; answer.await() },
                        onDismiss = { dismissed += 1 },
                        scope = scope,
                    )
                }
            }
        }
    }

    private fun inEveryLook(check: () -> Unit) = composeRule.inEveryLook(look) { check(); composeRule.checkAccessibility() }

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

    /** Closing with a note asks before it's lost; with none, it closes at once. */
    @Test
    fun testClosingWithANoteAsksFirst() {
        show(recipes)
        val close = composeRule.onNodeWithContentDescription("Close")
        close.performClick()
        assertEquals("nothing to lose", 1, dismissed)
        field.performTextInput("The soup page overlaps")
        close.performClick()
        text("Discard feedback?").assertIsDisplayed()
        text("Keep editing").performClick()
        text("Discard feedback?").assertDoesNotExist()
        assertEquals(1, dismissed)
        close.performClick()
        text("Discard").performClick()
        assertEquals(2, dismissed)
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
        val png = Base64.getDecoder().decode(sent.single().screenshotPngBase64!!)
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
    fun testTheDraftOutlastsTheSheet() {
        show(guide, screenshot())
        field.performTextInput("Half a note")
        text("Include screenshot").performScrollTo().performClick()
        showing = false
        composeRule.waitForIdle()
        field.assertDoesNotExist()
        showing = true
        composeRule.waitForIdle()
        field.assertTextContains("Half a note")
        text("Include screenshot").performScrollTo().assertIsOff()

        // Taken away while it sends, it comes back saying it was sent, and closes.
        sendButton.performClick()
        composeRule.waitUntil { sent.isNotEmpty() }
        showing = false
        composeRule.waitForIdle()
        answer.complete(FeedbackResult.Sent)
        composeRule.mainClock.advanceTimeBy(2_000)
        assertEquals("nothing closes while it's away", 0, dismissed)
        showing = true
        composeRule.mainClock.advanceTimeBy(200)
        text(ConnectionCopy.feedbackSent).assertIsDisplayed()
        composeRule.mainClock.advanceTimeBy(1_500)
        composeRule.waitForIdle()
        assertEquals(1, dismissed)
        assertEquals("sent once", 1, sent.size)
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
        val full = FeedbackPayload.png(image, density = 3f, limit = Int.MAX_VALUE)!!
        val limited = FeedbackPayload.png(image, density = 3f, limit = full.size - 1)!!
        assertTrue(limited.size < full.size)
        assertNull("nothing fits", FeedbackPayload.png(image, density = 3f, limit = 10))
        assertFalse("the snapshot itself is kept", image.isRecycled)
    }
}
