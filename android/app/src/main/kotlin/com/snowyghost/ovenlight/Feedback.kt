package com.snowyghost.ovenlight

import java.net.URI
import java.util.Base64
import kotlinx.serialization.Serializable

/**
 * What Ovenlight posts to the app's connector at `/__ovenlight/feedback`: a note, the page
 * it's about, and a screenshot of the web view. Encoded with [ovenlightJSON], which leaves
 * out a null screenshot.
 */
@Serializable
data class FeedbackPayload(
    val note: String,
    val pageUrl: String,
    val screenshotPngBase64: String? = null,
) {
    sealed class Problem : Exception() {
        data object Empty : Problem()
        data object NoteTooLong : Problem()
        data object ScreenshotTooLarge : Problem()
    }

    companion object {
        /** The connector's limits (connector/feedback.go). */
        const val maxNote = 4000
        const val maxScreenshot = 5 shl 20
        const val maxPageURL = 2048

        /** @throws Problem */
        fun make(note: String, pageURL: URI?, png: ByteArray?): FeedbackPayload {
            val trimmed = note.trimmingWhitespaceAndNewlines()
            if (trimmed.isEmpty() && png == null) throw Problem.Empty
            // The connector counts code points, not what the eye sees as characters.
            if (trimmed.codePointCount(0, trimmed.length) > maxNote) throw Problem.NoteTooLong
            if (png != null && png.size > maxScreenshot) throw Problem.ScreenshotTooLarge
            val page = pageURL?.toString() ?: ""
            // The connector counts the URL's bytes. iOS counts characters, the same for the
            // ASCII URLs a web view reports.
            return FeedbackPayload(
                note = trimmed,
                pageUrl = if (page.toByteArray(Charsets.UTF_8).size <= maxPageURL) page else "",
                screenshotPngBase64 = png?.let { Base64.getEncoder().encodeToString(it) },
            )
        }
    }
}

sealed class FeedbackResult {
    data object Sent : FeedbackResult()
    data class Failed(val message: String) : FeedbackResult()

    companion object {
        fun interpret(status: Int, body: ByteArray): FeedbackResult = when (status) {
            200 -> Sent
            429 -> Failed(ConnectionCopy.feedbackTooMany)
            else -> Failed(errorMessage(body)?.let(::sentence) ?: ConnectionCopy.feedbackNetwork)
        }

        /** The connector's words as a sentence: the first character capitalized, and a period. */
        private fun sentence(words: String): String {
            val first = words.graphemePrefix(1)
            return first.uppercase() + words.substring(first.length) + "."
        }

        /** The connector's `{"error": "..."}`, as iOS's AdminAPI.errorMessage reads it. */
        private fun errorMessage(body: ByteArray): String? = try {
            ovenlightJSON.decodeFromString(FeedbackError.serializer(), strictUTF8(body)).error
        } catch (_: Exception) {
            null
        }
    }
}

@Serializable
private class FeedbackError(val error: String? = null)
