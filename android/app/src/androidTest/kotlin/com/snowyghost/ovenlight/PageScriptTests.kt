package com.snowyghost.ovenlight

import android.content.Context
import android.webkit.PermissionRequest
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.webkit.WebViewCompat
import java.io.File
import java.net.URI
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.json.JSONTokener
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The page script and its bridge in a real WebView, on a local page at an https origin
 * (loadDataWithBaseURL; its subresources come from shouldInterceptRequest, so nothing reaches
 * the network): what a page sees, the downloads it starts, and the capture rules.
 */
@RunWith(AndroidJUnit4::class)
class PageScriptTests {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val base = "https://recipes.example.test/"
    private val app = WebApp(name = "Recipe Box", startURL = URI(base))
    private val folder = File(context.cacheDir, "test-downloads-${app.id}")
    private val scope = MainScope()
    private lateinit var view: WebView
    private lateinit var capture: PageCapture
    private lateinit var downloads: PageDownloads
    private var onScreen = true
    private val asked = mutableListOf<List<String>>()
    private var refetchAgent: String? = null

    /** Answers every download's refetch, as the proxy would, with a server-given name. */
    private val refetch = OkHttpClient.Builder().addInterceptor { chain ->
        refetchAgent = chain.request().header("User-Agent")
        Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
            .header("Content-Disposition", "attachment; filename=\"server.csv\"").body("flour,500\n".toResponseBody()).build()
    }.build()

    private val page = """
        <!doctype html><meta charset="utf-8"><title>page</title>
        <iframe src="frame.html"></iframe>
        <a id="file" download="Recipes export.csv" href="export.csv">file</a>
        <a id="data" download="d.txt" href="data:text/plain,from%20a%20click">data</a>
        <script>
        function blobDownload() {
          const a = document.createElement("a");
          a.href = URL.createObjectURL(new Blob(["hello"], { type: "text/plain" }));
          a.download = "note.txt";
          a.click();
          URL.revokeObjectURL(a.href);
        }
        </script>
    """.trimIndent()

    private fun <T> main(block: () -> T): T {
        var result: Result<T>? = null
        InstrumentationRegistry.getInstrumentation().runOnMainSync { result = runCatching(block) }
        return result!!.getOrThrow()
    }

    /** The script's value, as a string (JSON-decoded when it is one). */
    private fun eval(js: String): String? {
        val done = CountDownLatch(1)
        var value: String? = null
        main { view.evaluateJavascript(js) { value = it; done.countDown() } }
        assertTrue("no answer from $js", done.await(10, TimeUnit.SECONDS))
        return when (val decoded = JSONTokener(value ?: "null").nextValue()) {
            is String -> decoded
            org.json.JSONObject.NULL -> null
            else -> decoded.toString()
        }
    }

    private fun until(seconds: Int = 10, what: String, condition: () -> Boolean) {
        repeat(seconds * 10) {
            if (condition()) return
            Thread.sleep(100)
        }
        throw AssertionError("timed out: $what")
    }

    private fun open(html: String = page, beforeBridge: String? = null) {
        main {
            view = WebView(context)
            WebViewSetup.configure(view)
            view.webViewClient = object : WebViewClient() {
                override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? = when (request.url.toString()) {
                    "${base}frame.html" -> WebResourceResponse("text/html", "utf-8", "<p>frame</p>".byteInputStream())
                    "${base}export.csv" -> WebResourceResponse("text/csv", "utf-8", 200, "OK", mapOf("Content-Disposition" to "attachment"), "flour,500\n".byteInputStream())
                    else -> null
                }
            }
            capture = PageCapture(
                app, ownersHosts = { emptySet() }, isLocked = { false }, isOnScreen = { onScreen }, scope = scope,
                permissions = { needed, done -> asked += needed; done(true) }, stillCapturing = { false }, destroy = {},
            )
            downloads = PageDownloads(app.id, app.host, folder, scope, mayStart = { true }, isLocked = { false }, client = { refetch }, freeBytes = { DownloadShelf.freeBytes(context) })
            view.webChromeClient = object : WebChromeClient() {
                override fun onPermissionRequest(request: PermissionRequest) = capture.onPermissionRequest(request)
            }
            view.setDownloadListener(downloads)
            beforeBridge?.let { WebViewCompat.addDocumentStartJavaScript(view, it, setOf("*")) }
            PageBridge(view, app.startURL, capture, downloads)
            view.loadDataWithBaseURL(base, html, "text/html", "utf-8", null)
        }
        until(what = "the page loads") { eval("document.readyState") == "complete" }
    }

    private fun shelf(): List<File> = DownloadShelf.waiting.value[app.id].orEmpty().map { it.file }

    @After
    fun close() {
        main {
            if (::view.isInitialized) view.destroy()
            scope.cancel()
        }
        folder.deleteRecursively()
    }

    @Test
    fun testPagesStillSeeMobileSafari() {
        open()
        val agent = eval("navigator.userAgent")!!
        assertTrue(agent, agent.startsWith("Mozilla/5.0 (Linux; Android "))
        assertTrue("WebView's own marker stays", agent.contains("; wv)"))
        assertTrue(agent, Regex(" Mobile Safari/\\S+ Ovenlight/${Regex.escape(BuildConfig.VERSION_NAME)}$").containsMatchIn(agent))
    }

    @Test
    fun testServiceWorkersAreHidden() {
        open()
        assertEquals("false", eval("String('serviceWorker' in navigator)"))
        until(what = "the frame loads") { eval("document.querySelector('iframe').contentDocument.readyState") == "complete" }
        assertEquals("in the app's own frames too", "false", eval("String('serviceWorker' in document.querySelector('iframe').contentWindow.navigator)"))
    }

    @Test
    fun testAnAnchorsDownloadNameIsKept() {
        open()
        eval("document.getElementById('file').click()")
        until(what = "the refetched file") { shelf().isNotEmpty() }
        val file = shelf().single()
        assertEquals("the page's name, not the server's", "Recipes export.csv", file.name)
        assertEquals("flour,500\n", file.readText())
        assertTrue("the view's user agent: $refetchAgent", refetchAgent.orEmpty().endsWith("Ovenlight/${BuildConfig.VERSION_NAME}"))
    }

    @Test
    fun testABlobDownloadArrivesThoughThePageRevokedIt() {
        open()
        eval("blobDownload()")
        until(what = "the blob's file") { shelf().isNotEmpty() }
        val file = shelf().single()
        assertEquals("note.txt", file.name)
        assertEquals("hello", file.readText())
    }

    @Test
    fun testADataDownloadNeedsTheClick() {
        open()
        main { downloads.onDownloadStart("data:text/plain,nobody%20clicked", "agent", null, "text/plain", 0) }
        Thread.sleep(1_000)
        assertTrue("no click, no file", shelf().isEmpty())
        eval("document.getElementById('data').click()")
        until(what = "the clicked data URL's file") { shelf().isNotEmpty() }
        assertEquals("from a click", shelf().single().readText())
    }

    @Test
    fun testCaptureIsDeniedOffScreen() {
        onScreen = false
        open()
        eval("navigator.mediaDevices.getUserMedia({ audio: true }).then(() => window.result = 'granted', e => window.result = e.name)")
        until(what = "getUserMedia's answer") { eval("window.result") != null }
        assertEquals("NotAllowedError", eval("window.result"))
        assertTrue("not even Android's permission is asked", asked.isEmpty())
        assertNull(capture.prompt.value)
    }

    @Test
    fun testTheStopMessageEndsTheTracks() {
        // A stand-in camera, set before the page script wraps getUserMedia.
        val fake = "MediaDevices.prototype.getUserMedia = function () { const c = document.createElement('canvas'); c.getContext('2d'); return Promise.resolve(c.captureStream()); };"
        open(beforeBridge = fake)
        eval("navigator.mediaDevices.getUserMedia({ video: true }).then(s => { window.track = s.getVideoTracks()[0]; window.copy = window.track.clone(); })")
        until(what = "the fake track") { eval("window.copy && window.copy.readyState") == "live" }
        // The page's report that it captures crosses to Ovenlight on its own.
        Thread.sleep(500)
        main { capture.stop() }
        until(what = "the tracks to end") { eval("window.track.readyState + ',' + window.copy.readyState") == "ended,ended" }
    }
}
