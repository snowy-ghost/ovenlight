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
import androidx.webkit.WebMessageCompat
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
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith

/**
 * The page script and its bridge in a real WebView, on a local page at an https origin
 * (loadDataWithBaseURL; its subresources come from shouldInterceptRequest, so nothing reaches
 * the network): what a page sees, the downloads it starts, and the capture rules.
 */
@RunWith(AndroidJUnit4::class)
class PageScriptTests {
    @get:Rule
    val temp = TemporaryFolder()

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val base = "https://recipes.example.test/"
    private val app = WebApp(name = "Recipe Box", startURL = URI(base))
    private val scope = MainScope()
    private lateinit var view: WebView
    private lateinit var capture: PageCapture
    private lateinit var downloads: PageDownloads
    private var onScreen = true
    private val asked = mutableListOf<List<String>>()
    private var refetchAgent: String? = null

    /** Every message the bridge heard: a string's text, or a buffer's size. */
    private lateinit var bridge: PageBridge
    private val bodyHeld = CountDownLatch(1)
    @Volatile private var answers = 0
    private val heard: MutableList<Any> = java.util.Collections.synchronizedList(mutableListOf())
    private val stringLimit = PageDownloads.HREF_LIMIT + PageMessage.MAX_HEADER
    private val bufferLimit = PageDownloads.BLOB_LIMIT + PageMessage.MAX_HEADER + 4

    /** Answers every download's refetch, as the proxy would, with a server-given name. */
    private val refetch = OkHttpClient.Builder().addInterceptor { chain ->
        refetchAgent = chain.request().header("User-Agent")
        Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
            .header("Content-Disposition", "attachment; filename=\"server.csv\"").body("flour,500\n".toResponseBody()).build()
    }.build()

    /** A frame of the app's own origin, with downloads of its own. */
    private val frame = """
        <!doctype html><meta charset="utf-8"><title>frame</title>
        <a id="data" download="framed.txt" href="data:text/plain,from%20a%20frame">data</a>
        <script>
        function blobDownload() {
          const a = document.createElement("a");
          a.href = URL.createObjectURL(new Blob(["framed blob"], { type: "text/plain" }));
          a.download = "framed-note.txt";
          a.click();
          URL.revokeObjectURL(a.href);
        }
        </script>
    """.trimIndent()

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

    /** Loads [html] at the app's origin, or [url] (served below), and waits for the page script's answer. */
    private fun open(html: String = page, beforeBridge: String? = null, until: String = "complete", url: String? = null, answered: Boolean = true) {
        main {
            view = WebView(context)
            WebViewSetup.configure(view)
            view.webViewClient = object : WebViewClient() {
                override fun onPageStarted(view: WebView, url: String?, favicon: android.graphics.Bitmap?) = bridge.documentStarted()
                override fun onPageFinished(view: WebView, url: String?) = bridge.documentFinished()
                override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? = when (request.url.toString()) {
                    "${base}frame.html" -> WebResourceResponse("text/html", "utf-8", frame.byteInputStream())
                    // Headers at once, then a body that waits: the frame commits before its first bytes.
                    "${base}stalled.html" -> WebResourceResponse("text/html", "utf-8", object : java.io.InputStream() {
                        override fun read(): Int {
                            bodyHeld.await(15, TimeUnit.SECONDS)
                            return -1
                        }
                    })
                    // A main document whose body takes its time, so the first offers land before the script listens.
                    "${base}slow.html" -> WebResourceResponse("text/html", "utf-8", object : java.io.InputStream() {
                        private val body = page.byteInputStream()
                        private var waited = false
                        override fun read(): Int {
                            if (!waited) {
                                waited = true
                                Thread.sleep(2_000)
                            }
                            return body.read()
                        }
                    })
                    "${base}export.csv" -> WebResourceResponse("text/csv", "utf-8", 200, "OK", mapOf("Content-Disposition" to "attachment"), "flour,500\n".byteInputStream())
                    else -> null
                }
            }
            capture = PageCapture(
                app, ownersHosts = { emptySet() }, isLocked = { false }, isOnScreen = { onScreen }, scope = scope,
                permissions = { needed, done -> asked += needed; done(true) }, stillCapturing = { false }, destroy = {},
            )
            downloads = PageDownloads(app.id, app.host, temp.root, scope, mayStart = { true }, isLocked = { false }, client = { refetch }, freeBytes = { DownloadShelf.freeBytes(context) })
            view.webChromeClient = object : WebChromeClient() {
                override fun onPermissionRequest(request: PermissionRequest) = capture.onPermissionRequest(request)
            }
            view.setDownloadListener(downloads)
            beforeBridge?.let { WebViewCompat.addDocumentStartJavaScript(view, it, setOf("*")) }
            bridge = PageBridge(view, app.startURL, capture, downloads, scope) { message ->
                when {
                    message.type == WebMessageCompat.TYPE_ARRAY_BUFFER -> heard += message.arrayBuffer.size
                    message.data == PageBridge.ANSWER -> answers += 1
                    else -> heard += message.data.orEmpty()
                }
            }
            if (url != null) view.loadUrl(url) else view.loadDataWithBaseURL(base, html, "text/html", "utf-8", null)
        }
        // The view's first document, about:blank, is complete at once: wait for this one.
        until(what = "the page loads") { eval("location.href + ' ' + document.readyState") == "${url ?: base} $until" }
        if (answered) until(what = "the page script's answer") { answers > 0 }
    }

    private fun shelf(): List<File> = DownloadShelf.waiting.value[app.id].orEmpty().map { it.file }

    @After
    fun close() {
        bodyHeld.countDown()
        main {
            if (::view.isInitialized) view.destroy()
            scope.cancel()
        }
    }

    @Test
    fun testPagesStillSeeMobileSafari() {
        open()
        val agent = eval("navigator.userAgent")!!
        assertTrue(agent, agent.startsWith("Mozilla/5.0 (Linux; Android "))
        assertTrue("WebView's own marker stays", agent.contains("; wv)"))
        assertTrue(agent, Regex(" Mobile Safari/\\S+ Ovenlight/${Regex.escape(BuildConfig.VERSION_NAME)}$").containsMatchIn(agent))
        assertTrue("no phone model or build: $agent", agent.startsWith("Mozilla/5.0 (Linux; Android 10; K; wv)"))
        // Nor through client hints, which a page can ask for whatever the string says.
        eval(
            "window.hints = 'pending'; navigator.userAgentData ? navigator.userAgentData" +
                ".getHighEntropyValues(['model', 'platformVersion']).then(h => window.hints = JSON.stringify(h), " +
                "e => window.hints = 'failed') : (window.hints = 'none')",
        )
        until(what = "the client hints") { eval("window.hints") != "pending" }
        val hints = eval("window.hints")
        assertEquals("no model in the client hints: $hints", "", eval("JSON.parse(window.hints).model"))
        assertEquals("Android 10 in the client hints: $hints", "10.0.0", eval("JSON.parse(window.hints).platformVersion"))
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

    /** Nothing over the bridge's caps: each message within them, as Java measures it. */
    private fun assertHeardWithinCaps() = synchronized(heard) {
        for (message in heard) when (message) {
            is String -> assertTrue("a ${message.length}-character message", message.length <= stringLimit)
            is Int -> assertTrue("a $message-byte buffer", message <= bufferLimit)
        }
    }

    @Test
    fun testAPageThatReplacesBuiltInsStillSendsWhatItShould() {
        open(
            """
            <!doctype html><meta charset="utf-8"><title>page</title>
            <a id="data" download="d.txt" href="data:text/plain,poisoned">data</a>
            <a id="blob" download="b.txt">blob</a>
            <script>
            const real = new Blob(["hi"], { type: "text/plain" });
            document.getElementById("blob").href = URL.createObjectURL(real);
            // Everything the page script uses to build and send a report, made to blow it up.
            const huge = "x".repeat($stringLimit + 1);
            JSON.stringify = () => huge;
            Blob.prototype.arrayBuffer = () => Promise.resolve(new ArrayBuffer($bufferLimit + 1));
            Object.defineProperty(Blob.prototype, "size", { get: () => 1 });
            TextEncoder.prototype.encode = () => new Uint8Array($bufferLimit + 1);
            Object.defineProperty(ArrayBuffer.prototype, "byteLength", { get: () => 1 });
            const then = Promise.prototype.then, apply = Reflect.apply;
            Promise.prototype.then = function (ok, fail) { return apply(then, this, [() => ok && ok(new ArrayBuffer($bufferLimit + 1)), fail]); };
            Function.prototype.call = function () { return huge; };
            window.setTimeout = f => f();
            window.Blob = function () { return new real.constructor([huge]); };
            </script>
            """.trimIndent(),
        )
        eval("document.getElementById('data').click(); document.getElementById('blob').click(); 'clicked'")
        until(what = "both reports") { heard.size >= 2 }
        Thread.sleep(1_000)
        assertHeardWithinCaps()
        synchronized(heard) {
            assertTrue(heard.toString(), """{"t":"click","href":"data:text/plain,poisoned","name":"d.txt"}""" in heard)
            assertEquals("the blob's message, intact: 4 + its header + 2 bytes", 1, heard.count { it is Int })
        }
        until(what = "the blob's file") { shelf().any { it.name == "b.txt" } }
        assertEquals("hi", shelf().single { it.name == "b.txt" }.readText())
    }

    @Test
    fun testAReportOverTheCapNeverLeavesThePage() {
        open()
        // An anchor whose download name alone is over the cap: its report is never sent.
        eval("const a = document.getElementById('data'); a.download = 'n'.repeat($stringLimit); a.click(); 'clicked'")
        Thread.sleep(1_500)
        assertTrue("heard ${heard.size}", heard.isEmpty())
        eval("document.getElementById('data').download = 'd.txt'; document.getElementById('data').click(); 'clicked'")
        until(what = "the report") { heard.isNotEmpty() }
        assertEquals("""{"t":"click","href":"data:text/plain,from%20a%20click","name":"d.txt"}""", heard.single())
    }

    @Test
    fun testWhichFramesGetThePageScript() {
        open(
            """
            <!doctype html><meta charset="utf-8"><title>page</title>
            <iframe id="blank" src="about:blank"></iframe>
            <iframe id="srcdoc" srcdoc="<p>inside</p>"></iframe>
            <script>
            const made = document.createElement("iframe");
            made.id = "made";
            document.body.appendChild(made);
            </script>
            """.trimIndent(),
        )
        until(what = "the frames load") { eval("document.getElementById('srcdoc').contentDocument.readyState") == "complete" }
        val hidden = listOf("blank", "srcdoc", "made").associateWith { id ->
            eval("String(!('serviceWorker' in document.getElementById('$id').contentWindow.navigator))")
        }
        // A srcdoc frame gets the script; an about:blank one, by src or made by a script, doesn't,
        // so it relays nothing of its own (and Ovenlight offers its port to the main frame alone).
        assertEquals(mapOf("blank" to "false", "srcdoc" to "true", "made" to "false"), hidden)
    }

    @Test
    fun testAFrameSentToAboutBlankGetsThePageScript() {
        open()
        until(what = "the frame loads") { eval("frames[0].document.readyState") == "complete" }
        eval("frames[0].location = 'about:blank'; 'sent'")
        until(what = "about:blank") { eval("frames[0].location.href") == "about:blank" }
        assertEquals("false", eval("String('serviceWorker' in frames[0].navigator)"))
    }

    @Test
    fun testNothingOverTheCapLeavesWhateverAPageBends() {
        open(
            """
            <!doctype html><meta charset="utf-8"><title>page</title>
            <a id="data" download="d.txt" href="data:text/plain,bent">data</a>
            <a id="blob" download="b.txt">blob</a>
            <script>
            document.getElementById("blob").href = URL.createObjectURL(new Blob(["hi"]));
            // What built-ins still look up on the page's objects, each made to hand over too much.
            const huge = "x".repeat($stringLimit + 1);
            Object.prototype.toJSON = () => huge;
            Object.defineProperty(ArrayBuffer.prototype, "then", {
              configurable: true,
              get() { delete ArrayBuffer.prototype.then; return ok => ok(new ArrayBuffer($bufferLimit + 1)); },
            });
            const values = Array.prototype[Symbol.iterator];
            Array.prototype[Symbol.iterator] = function () { return values.call([...values.call(this), new Uint8Array($bufferLimit + 1)]); };
            </script>
            """.trimIndent(),
        )
        eval("document.getElementById('data').click(); document.getElementById('blob').click(); 'clicked'")
        Thread.sleep(2_000)
        assertHeardWithinCaps()
        assertTrue("heard ${heard.size}", heard.isEmpty())
    }

    @Test
    fun testBigReportsShareABudget() {
        open()
        // Twenty reports of a million characters each, in one go: about a blob's worth goes.
        eval(
            "const a = document.getElementById('data'); a.download = 'n'.repeat(1 << 20);" +
                "for (let i = 0; i < 20; i++) a.click(); 'clicked'",
        )
        until(what = "some reports") { heard.isNotEmpty() }
        Thread.sleep(1_500)
        synchronized(heard) {
            assertTrue("heard ${heard.size}", heard.size in 1..19)
            // The budget refills as the reports go out, but over moments, not twenty times over.
            assertTrue(heard.sumOf { (it as String).length.toLong() * 2 } <= 2L * bufferLimit)
        }
    }

    @Test
    fun testAFrameWaitingForItsBodyHasNothingToTake() {
        open(
            """
            <!doctype html><meta charset="utf-8"><title>page</title>
            <iframe src="stalled.html"></iframe>
            <script>
            // The frame's window exists before its page script runs: look for anything to reach Ovenlight with.
            window.found = [];
            const poll = () => {
              try {
                for (const name of Object.getOwnPropertyNames(frames[0])) if (/ovenlight/i.test(name)) window.found.push(name);
              } catch (e) {}
              if (window.found.length === 0) setTimeout(poll, 2);
            };
            poll();
            </script>
            """.trimIndent(),
            // Its load waits on the frame's body.
            until = "interactive",
        )
        Thread.sleep(3_000)
        assertEquals("[]", eval("JSON.stringify(window.found)"))
        // What the page can post itself: an offer from a window (Ovenlight's comes from none), and
        // relays over the caps, which the main frame's own send() drops.
        eval(
            "const channel = new MessageChannel(); channel.port1.onmessage = e => window.answeredForged = e.data;" +
                "postMessage('${PageBridge.OFFER}', '*', [channel.port2]);" +
                "postMessage({ 'ovenlight-relay': 'x'.repeat($stringLimit + 1) }, '*');" +
                "postMessage({ 'ovenlight-relay': new ArrayBuffer($bufferLimit + 1) }, '*'); 'posted'",
        )
        Thread.sleep(1_500)
        assertNull("the page script answers no offer a window made", eval("window.answeredForged"))
        assertHeardWithinCaps()
        assertTrue("heard ${heard.size}", heard.isEmpty())
    }

    @Test
    fun testASlowDocumentStillGetsItsChannel() {
        open(url = "${base}slow.html")
        eval("document.getElementById('data').click(); 'clicked'")
        until(what = "the report") { heard.isNotEmpty() }
        assertEquals("""{"t":"click","href":"data:text/plain,from%20a%20click","name":"d.txt"}""", heard.single())
    }

    @Test
    fun testADocumentReplacedMidHandshakeGetsTheChannel() {
        // A first document that commits but never gets its body, so Ovenlight's offers to it go unanswered.
        open(url = "${base}stalled.html", until = "loading", answered = false)
        Thread.sleep(1_000)
        assertEquals(0, answers)
        main { view.loadDataWithBaseURL(base, page, "text/html", "utf-8", null) }
        until(what = "the new document's answer") { answers >= 1 && eval("document.title + ' ' + document.readyState") == "page complete" }
        eval("document.getElementById('data').click(); 'clicked'")
        until(what = "the report") { heard.isNotEmpty() }
        Thread.sleep(500)
        assertEquals("once, on the channel the new document holds", 1, heard.size)
    }

    @Test
    fun testAFramesDownloadsGoThroughTheMainFrame() {
        open()
        until(what = "the frame loads") { eval("frames[0].document.readyState") == "complete" }
        eval("frames[0].document.getElementById('data').click(); 'clicked'")
        until(what = "the frame's data file") { shelf().any { it.name == "framed.txt" } }
        assertEquals("from a frame", shelf().single { it.name == "framed.txt" }.readText())
        eval("frames[0].blobDownload(); 'clicked'")
        until(what = "the frame's blob file") { shelf().any { it.name == "framed-note.txt" } }
        assertEquals("framed blob", shelf().single { it.name == "framed-note.txt" }.readText())
    }

    @Test
    fun testTheStopReachesAFramesTracks() {
        val fake = "MediaDevices.prototype.getUserMedia = function () { const c = document.createElement('canvas'); c.getContext('2d'); return Promise.resolve(c.captureStream()); };"
        open(beforeBridge = fake)
        until(what = "the frame loads") { eval("frames[0].document.readyState") == "complete" }
        eval("frames[0].navigator.mediaDevices.getUserMedia({ video: true }).then(s => { window.track = s.getVideoTracks()[0]; }); 'asked'")
        until(what = "the frame's fake track") { eval("window.track && window.track.readyState") == "live" }
        // The frame's report that it captures goes up through the main frame on its own.
        Thread.sleep(500)
        main { capture.stop() }
        until(what = "the frame's track to end") { eval("window.track.readyState") == "ended" }
    }

    /** Records any message a page's own listeners see that carries a port or the offer. */
    private val watcher = """
        window.seen = [];
        const look = how => e => { if (e.ports.length || e.data === "${PageBridge.OFFER}") window.seen.push(how); };
        addEventListener("message", look("capture"), true);
        addEventListener("message", look("bubble"));
        window.onmessage = look("onmessage");
    """.trimIndent()

    /** Offers again, as for a new document, and waits for the page script's answer. */
    private fun offerAgain() {
        val before = answers
        main { bridge.documentStarted() }
        until(what = "the answer to the new offer") { answers > before }
    }

    @Test
    fun testThePageNeverSeesAnOffer() {
        open("<!doctype html><meta charset=\"utf-8\"><title>page</title><script>$watcher</script>")
        offerAgain()
        offerAgain()
        assertEquals("[]", eval("JSON.stringify(window.seen)"))
    }

    /**
     * The page script's listener comes back first after document.open(), for listeners the page
     * adds once it returns. Page code that runs inside open() or write() can still get ahead of
     * it, a known residual (see page.js).
     */
    @Test
    fun testAListenerAddedAfterDocumentOpenDoesntGetTheOffer() {
        open("<!doctype html><meta charset=\"utf-8\"><title>page</title>")
        // From a task, where document.open() works: it takes every listener off the window.
        eval("setTimeout(() => { document.open(); $watcher document.write('<title>opened</title>'); document.close(); }); 'scheduled'")
        until(what = "the document to open") { eval("document.title") == "opened" }
        offerAgain()
        assertEquals("[]", eval("JSON.stringify(window.seen)"))
    }

    @Test
    fun testAnAboutBlankFrameCantRelayPastTheCaps() {
        open()
        eval("const f = document.createElement('iframe'); document.body.appendChild(f); 'made'")
        // The frame has no page script, but its own code can post to the main frame as itself.
        eval(
            "const w = document.querySelector('iframe:last-of-type').contentWindow;" +
                "w.eval(\"parent.postMessage({ 'ovenlight-relay': 'x'.repeat($stringLimit + 1) }, '*');" +
                "parent.postMessage({ 'ovenlight-relay': new ArrayBuffer($bufferLimit + 1) }, '*')\"); 'posted'",
        )
        Thread.sleep(1_500)
        assertHeardWithinCaps()
        assertTrue("heard ${heard.size}", heard.isEmpty())
    }
}
