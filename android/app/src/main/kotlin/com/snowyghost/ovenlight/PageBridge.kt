package com.snowyghost.ovenlight

import android.annotation.SuppressLint
import android.content.Context
import android.util.Log
import android.webkit.WebView
import androidx.webkit.JavaScriptReplyProxy
import androidx.webkit.WebMessageCompat
import androidx.webkit.WebViewCompat
import java.net.URI
import java.nio.ByteBuffer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Between a page and Ovenlight: the page script (`assets/page.js`), added at document start to
 * every frame of every origin, and the bridge it talks over, a WebMessageListener that only
 * frames of the app's own origin get (never `addJavascriptInterface`). What arrives is only
 * ever a report: Ovenlight decides each download and stream by its own rules, so no message
 * grants anything, and nothing a page sends is passed back into a script. Main thread only.
 */
@SuppressLint("RequiresFeature") // WebViewSetup is Ready only once it found every feature used here.
class PageBridge(view: WebView, appURL: URI, private val capture: PageCapture, private val downloads: PageDownloads) {
    init {
        WebViewCompat.addDocumentStartJavaScript(view, script(view.context), setOf("*"))
        val origin = originRule(appURL)
        if (origin == null) {
            Log.w(TAG, "${appURL.host}: no origin for the page bridge")
        } else {
            WebViewCompat.addWebMessageListener(view, OBJECT, setOf(origin)) { _, message, sourceOrigin, _, reply ->
                receive(message, sourceOrigin.host ?: return@addWebMessageListener, reply)
            }
        }
    }

    private fun receive(message: WebMessageCompat, fromHost: String, reply: JavaScriptReplyProxy) {
        // Download reports count only while the page may start one; off screen or locked, a page
        // sending them over and over costs nothing but the dropping.
        when (message.type) {
            WebMessageCompat.TYPE_STRING -> {
                val data = message.data ?: return
                if (data.length > SMALL && !downloads.accepting) return
                when (val parsed = PageMessage.parse(data)) {
                    PageMessage.Capture -> capture.capturing(reply)
                    is PageMessage.Click -> downloads.clicked(parsed.href, parsed.name, fromHost)
                    null -> Unit
                }
            }
            WebMessageCompat.TYPE_ARRAY_BUFFER -> if (downloads.accepting) downloads.blobMessage(message.arrayBuffer, fromHost)
        }
    }

    companion object {
        private const val TAG = "Ovenlight"

        /** The name the bridge has in the page, which the page script takes away from it. */
        const val OBJECT = "__ovenlight"

        /** Longer than any message but a download report. */
        private const val SMALL = 64

        /** Sent to a frame that captures, to stop its tracks. */
        const val STOP = "stop"

        private var source: String? = null

        /** The page script, with the blob size cap filled in. */
        fun script(context: Context): String = source ?: context.applicationContext.assets.open("page.js")
            .bufferedReader().use { it.readText() }
            .replace("__LIMIT__", PageDownloads.BLOB_LIMIT.toString())
            .replace("__HREF_LIMIT__", PageDownloads.HREF_LIMIT.toString())
            .also { source = it }

        /** `https://host[:port]`, the one origin whose frames get the bridge. */
        fun originRule(url: URI): String? {
            val scheme = url.scheme?.lowercase() ?: return null
            val host = url.host?.lowercase()?.takeIf { it.isNotEmpty() } ?: return null
            if (scheme != "https" && scheme != "http") return null
            return "$scheme://$host" + if (url.port > 0) ":${url.port}" else ""
        }
    }
}

/** What the page script sends, read strictly: anything else is dropped. */
sealed interface PageMessage {
    data object Capture : PageMessage

    data class Click(val href: String, val name: String) : PageMessage

    class Blob(val href: String, val name: String, val type: String, val bytes: ByteArray)

    companion object {
        private const val MAX_HEADER = 64 * 1024

        fun parse(text: String): PageMessage? {
            if (text.length > PageDownloads.HREF_LIMIT + MAX_HEADER) return null
            val fields = runCatching { Json.parseToJsonElement(text) as? JsonObject }.getOrNull() ?: return null
            return when (fields.string("t")) {
                "capture" -> Capture
                "click" -> Click(fields.string("href")?.takeIf { it.length <= PageDownloads.HREF_LIMIT } ?: return null, fields.string("name") ?: return null)
                else -> null
            }
        }

        /** The header's length (4 bytes, big-endian), the header as JSON, then the blob's bytes. */
        fun blob(message: ByteArray): Blob? {
            if (message.size < 4 || message.size > PageDownloads.BLOB_LIMIT + MAX_HEADER + 4) return null
            val length = ByteBuffer.wrap(message, 0, 4).int
            if (length !in 2..MAX_HEADER || length > message.size - 4) return null
            val text = message.decodeToString(4, 4 + length)
            val fields = runCatching { Json.parseToJsonElement(text) as? JsonObject }.getOrNull() ?: return null
            val href = fields.string("href")?.takeIf { it.startsWith("blob:") } ?: return null
            val bytes = message.copyOfRange(4 + length, message.size)
            if (bytes.size > PageDownloads.BLOB_LIMIT) return null
            return Blob(href, fields.string("name") ?: "", fields.string("type") ?: "", bytes)
        }

        private fun JsonObject.string(key: String): String? = (get(key) as? JsonPrimitive)?.takeIf { it.isString }?.content
    }
}
