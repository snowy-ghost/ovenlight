package com.snowyghost.ovenlight

import android.graphics.Bitmap
import androidx.core.graphics.scale
import java.io.ByteArrayOutputStream
import java.net.URI
import java.util.Base64
import kotlin.math.roundToInt
import kotlinx.serialization.Serializable
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

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

        /**
         * A PNG of the snapshot under the connector's limit, smaller if it has to be. [image] is
         * in the screen's pixels, [density] of them to a dp: as iOS steps down from the screen's
         * scale to 2 and then 1, this tries the full size, then 2 and 1 pixels to a dp.
         */
        fun png(image: Bitmap, density: Float, limit: Int = maxScreenshot - (256 shl 10)): ByteArray? {
            for (scale in listOf(density, 2f, 1f).filter { it <= density }.distinct()) {
                val sized = if (scale == density) image else image.scale(
                    (image.width * scale / density).roundToInt().coerceAtLeast(1), (image.height * scale / density).roundToInt().coerceAtLeast(1),
                )
                val out = ByteArrayOutputStream()
                val encoded = sized.compress(Bitmap.CompressFormat.PNG, 100, out)
                if (sized !== image) sized.recycle()
                if (encoded && out.size() <= limit) return out.toByteArray()
            }
            return null
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
            else -> Failed(AdminAPI.errorMessage(body)?.let(::sentence) ?: ConnectionCopy.feedbackNetwork)
        }

        /** The connector's words as a sentence: the first character capitalized, and a period. */
        private fun sentence(words: String): String {
            val first = words.graphemePrefix(1)
            return first.uppercase() + words.substring(first.length) + "."
        }
    }
}

object FeedbackSender {
    /** Posts the feedback through the app's own node, as the app's pages reach it. */
    suspend fun send(payload: FeedbackPayload, app: WebApp, nodes: NodeManager, guests: GuestManager): FeedbackResult {
        val network = FeedbackResult.Failed(ConnectionCopy.feedbackNetwork)
        val session = nodes.uploadSession(app, timeoutMillis = 30_000) ?: return network
        val body = ovenlightJSON.encodeToString(FeedbackPayload.serializer(), payload).toRequestBody("application/json".toMediaType())
        val request = runCatching { Request.Builder().url("https://${app.host}/__ovenlight/feedback").post(body).build() }.getOrNull() ?: return network
        val response = session(request) ?: return network
        // A shared app answering 403 may have been unshared: ask its connector.
        if (response.status == 403 && app.isShared) guests.verifyAccess(app)
        return FeedbackResult.interpret(response.status, response.body)
    }
}
