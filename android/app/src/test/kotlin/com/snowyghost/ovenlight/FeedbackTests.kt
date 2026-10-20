package com.snowyghost.ovenlight

import java.net.URI
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * The image tests need Android's Bitmap, so they run under Robolectric:
 * testShrinksLargeScreenshotsUnderTheLimit in FeedbackSheetTests, testScreenshotsAreDecodedSmall
 * in SharingViewTests.
 */
class FeedbackTests {
    private fun json(payload: FeedbackPayload) = ovenlightJSON.encodeToJsonElement(FeedbackPayload.serializer(), payload)

    private fun strings(vararg pairs: Pair<String, String>) = JsonObject(pairs.associate { it.first to JsonPrimitive(it.second) })

    @Test
    fun testBuildsThePayloadTheConnectorExpects() {
        val png = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47)
        val payload = FeedbackPayload.make(note = "  the button overlaps \n", pageURL = URI("https://e.ts.net/page"), png = png)
        assertEquals(strings("note" to "the button overlaps", "pageUrl" to "https://e.ts.net/page", "screenshotPngBase64" to "iVBORw=="), json(payload))

        val noteOnly = FeedbackPayload.make(note = "hi", pageURL = null, png = null)
        assertEquals("no screenshot key at all", strings("note" to "hi", "pageUrl" to ""), json(noteOnly))
    }

    @Test
    fun testRefusesWhatTheConnectorWould() {
        assertEquals(FeedbackPayload.Problem.Empty, assertThrows(FeedbackPayload.Problem::class.java) {
            FeedbackPayload.make(note = "  ", pageURL = null, png = null)
        })
        assertEquals(FeedbackPayload.Problem.NoteTooLong, assertThrows(FeedbackPayload.Problem::class.java) {
            FeedbackPayload.make(note = "é".repeat(4001), pageURL = null, png = null)
        })
        assertEquals(FeedbackPayload.Problem.ScreenshotTooLarge, assertThrows(FeedbackPayload.Problem::class.java) {
            FeedbackPayload.make(note = "", pageURL = null, png = ByteArray(FeedbackPayload.maxScreenshot + 1))
        })
        // A screenshot alone is fine.
        FeedbackPayload.make(note = "", pageURL = null, png = ByteArray(10))
        val long = URI("https://e.ts.net/" + "a".repeat(3000))
        assertEquals("an overlong page URL is dropped", "", FeedbackPayload.make(note = "x", pageURL = long, png = null).pageUrl)
    }

    @Test
    fun testAnswers() {
        assertEquals(FeedbackResult.Sent, FeedbackResult.interpret(200, """{"id":"a1"}""".toByteArray()))
        assertEquals(FeedbackResult.Failed(ConnectionCopy.feedbackTooMany), FeedbackResult.interpret(429, ByteArray(0)))
        assertEquals(
            FeedbackResult.Failed("The screenshot must be a PNG."),
            FeedbackResult.interpret(400, """{"error":"the screenshot must be a PNG"}""".toByteArray()),
        )
    }
}
