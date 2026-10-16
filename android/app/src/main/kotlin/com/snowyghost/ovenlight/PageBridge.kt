package com.snowyghost.ovenlight

import android.annotation.SuppressLint
import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.webkit.WebView
import androidx.webkit.WebMessagePortCompat
import androidx.webkit.WebMessageCompat
import androidx.webkit.WebViewCompat
import java.net.URI
import java.nio.ByteBuffer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Between a page and Ovenlight: the page script (`assets/page.js`), added at document start to
 * every frame of every origin, and a MessagePort that only the main frame's document gets. For
 * each new document Ovenlight makes a channel and posts one end to the main frame, addressed to
 * the app's origin; the page script takes it before any page script can listen, and answers on
 * it. Until it hears that answer, Ovenlight offers again, each time with a new channel, closing
 * the one before, so an offer that landed before the script was listening, or in a document
 * that has gone since, costs nothing. No frame ever holds a raw binding (never
 * `addJavascriptInterface`, nor a WebMessageListener, whose object a parent could take from a
 * frame before its script runs). What arrives is only ever a report: Ovenlight decides each
 * download and stream by its own rules, and nothing a page sends is passed back into a script.
 * Main thread only.
 */
@SuppressLint("RequiresFeature") // WebViewSetup is Ready only once it found every feature used here.
class PageBridge(
    private val view: WebView,
    appURL: URI,
    private val capture: PageCapture,
    private val downloads: PageDownloads,
    private val scope: CoroutineScope,
    /** Sees each message as it arrives, before anything reads it; for tests. */
    private val heard: (WebMessageCompat) -> Unit = {},
) {
    private val origin = originRule(appURL)?.let(Uri::parse)
    private val host = appURL.host.orEmpty()
    private val main = Handler(Looper.getMainLooper())

    /** Ovenlight's end of the channel it offered last, and whether the page script took it. */
    private var port: WebMessagePortCompat? = null
    private var answered = false
    private var offering: Job? = null
    private var closed = false

    init {
        WebViewCompat.addDocumentStartJavaScript(view, script(view.context), setOf("*"))
        if (origin == null) Log.w(TAG, "${appURL.host}: no origin for the page bridge")
    }

    /** A new document in the main frame: the page script there gets a channel of its own. */
    fun documentStarted() {
        if (closed) return
        answered = false
        offer()
    }

    /** The document finished loading; a channel it never took is offered again. */
    fun documentFinished() {
        if (!answered && !closed) offer()
    }

    /** The view is going. */
    fun close() {
        closed = true
        offering?.cancel()
        port?.close()
        port = null
    }

    /** Offers a channel now, then again until the page script answers, for [OFFER_MILLIS]. */
    private fun offer() {
        offerOnce()
        offering?.cancel()
        offering = scope.launch {
            val until = SystemClock.uptimeMillis() + OFFER_MILLIS
            while (!answered && SystemClock.uptimeMillis() < until) {
                delay(RETRY_MILLIS)
                if (!answered) offerOnce()
            }
        }
    }

    private fun offerOnce() {
        val target = origin ?: return
        if (closed) return
        port?.close()
        val (mine, theirs) = WebViewCompat.createWebMessageChannel(view)
        port = mine
        mine.setWebMessageCallback(main, object : WebMessagePortCompat.WebMessageCallbackCompat() {
            override fun onMessage(port: WebMessagePortCompat, message: WebMessageCompat?) {
                // Only the channel offered last speaks for the page.
                if (mine === this@PageBridge.port && message != null) receive(mine, message)
            }
        })
        // Delivered only if the main frame's document is of the app's origin.
        WebViewCompat.postWebMessage(view, WebMessageCompat(OFFER, arrayOf(theirs)), target)
    }

    private fun receive(port: WebMessagePortCompat, message: WebMessageCompat) {
        heard(message)
        // Download reports count only while the page may start one; off screen or locked, a page
        // sending them over and over costs nothing but the dropping.
        when (message.type) {
            WebMessageCompat.TYPE_STRING -> {
                val data = message.data ?: return
                if (data == ANSWER) {
                    answered = true
                    offering?.cancel()
                    return
                }
                if (data.length > SMALL && !downloads.accepting) return
                when (val parsed = PageMessage.parse(data)) {
                    PageMessage.Capture -> capture.capturing { runCatching { port.postMessage(WebMessageCompat(STOP)) } }
                    is PageMessage.Click -> downloads.clicked(parsed.href, parsed.name, host)
                    null -> Unit
                }
            }
            WebMessageCompat.TYPE_ARRAY_BUFFER -> if (downloads.accepting) downloads.blobMessage(message.arrayBuffer, host)
        }
    }

    companion object {
        private const val TAG = "Ovenlight"

        /** Ovenlight's offer of a port, the page script's answer, and the word that stops its tracks. */
        const val OFFER = "ovenlight-offer"
        const val ANSWER = "ovenlight-answer"
        const val STOP = "ovenlight-stop"

        /** How often, and for how long after each new document, an unanswered offer is made again. */
        const val RETRY_MILLIS = 250L
        const val OFFER_MILLIS = 15_000L

        /** Longer than any message but a download report. */
        private const val SMALL = 64

        private var source: String? = null

        /** The page script, with its caps and words filled in. */
        fun script(context: Context): String = source ?: context.applicationContext.assets.open("page.js")
            .bufferedReader().use { it.readText() }
            .replace("__LIMIT__", PageDownloads.BLOB_LIMIT.toString())
            .replace("__HREF_LIMIT__", PageDownloads.HREF_LIMIT.toString())
            .replace("__HEADER_LIMIT__", PageMessage.MAX_HEADER.toString())
            .replace("__OFFER__", OFFER)
            .replace("__ANSWER__", ANSWER)
            .replace("__STOP__", STOP)
            .also { source = it }

        /** `https://host[:port]`, the one origin the offer goes to. */
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
        /** The most a message carries besides a URL or a blob's bytes; the page script holds to it too. */
        const val MAX_HEADER = 64 * 1024

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
