package com.snowyghost.ovenlight

import android.Manifest
import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.SystemClock
import android.provider.MediaStore
import android.view.MotionEvent
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.FileProvider
import androidx.lifecycle.Lifecycle
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.net.URI
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * PageEdges on a real WebView, with local pages on an https origin (loadDataWithBaseURL), real
 * taps, and every activity start caught before it leaves the test.
 */
@RunWith(AndroidJUnit4::class)
class PageEdgesTests {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private val base = "https://recipes.e2e.ts.net/"
    private val app = WebApp(name = "Recipe Box", startURL = URI(base))
    private val instrumentation: Instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val scope = MainScope()
    private val locked = MutableStateFlow(false)
    @Volatile private var shown = true
    private lateinit var view: WebView
    private lateinit var edges: PageEdges

    /** The app's start page, for PageEdges' home. */
    private val home = "<title>home</title>"
    @Volatile private var homes = 0

    /** What shouldOverrideUrlLoading saw, and whether it stopped it. */
    private val seen = CopyOnWriteArrayList<Pair<String, Boolean>>()
    private val started = Starts()

    /** Every activity start, caught and answered here (canceled, unless [answer] says otherwise), so nothing opens. */
    private class Starts : Instrumentation.ActivityMonitor() {
        val intents = CopyOnWriteArrayList<Intent>()
        @Volatile var answer: (Intent) -> Instrumentation.ActivityResult = { Instrumentation.ActivityResult(Activity.RESULT_CANCELED, null) }

        override fun onStartActivity(intent: Intent): Instrumentation.ActivityResult {
            intents += intent
            return answer(intent)
        }
    }

    @Before
    fun setUp() {
        runBlocking { withTimeout(30_000) { WebViewSetup.state.first { it == WebViewSetup.State.Ready } } }
        instrumentation.addMonitor(started)
        composeRule.runOnUiThread {
            val activity = composeRule.activity
            view = WebView(activity)
            WebViewSetup.configure(view)
            val lifecycle = AppLifecycle(scope, SystemClock::elapsedRealtime, setAway = {}, closeNodes = {}, cameToFront = {})
            edges = PageEdges(app, view, activity, scope, locked, lifecycle, isShown = { shown }, home = { homes += 1; view.loadDataWithBaseURL(base, home, "text/html", "utf-8", base) })
            view.webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean =
                    edges.shouldOverrideUrlLoading(request).also { seen += request.url.toString() to it }

                override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) = edges.pageStarted(url)

                override fun onPageFinished(view: WebView, url: String?) = edges.pageFinished(url)
            }
            view.webChromeClient = edges.chrome
        }
        composeRule.setContent {
            Box(Modifier.fillMaxSize()) {
                AndroidView({ view }, Modifier.fillMaxSize())
                PageEdgesLayer(edges)
            }
        }
    }

    @After
    fun tearDown() {
        instrumentation.removeMonitor(started)
        composeRule.runOnUiThread { view.destroy() }
        scope.cancel()
    }

    // Helpers

    private fun <T> onMain(block: () -> T): T {
        var value: T? = null
        instrumentation.runOnMainSync { value = block() }
        @Suppress("UNCHECKED_CAST")
        return value as T
    }

    private fun waitFor(what: String, millis: Long = 10_000, condition: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + millis
        while (SystemClock.uptimeMillis() < deadline) {
            if (condition()) return
            Thread.sleep(100)
        }
        fail("timed out waiting for $what")
    }

    private fun title() = onMain { view.title.orEmpty() }

    /** Loads a page of the app's own origin and waits for it. */
    private fun load(body: String) {
        val html = "<!doctype html><meta name=viewport content='width=device-width, initial-scale=1'><title>ready</title><body style='margin:0'>$body"
        onMain { view.loadDataWithBaseURL(base, html, "text/html", "utf-8", base) }
        waitFor("the page") { title() == "ready" && onMain { view.progress } == 100 }
    }

    /** A page that is one big link, so a tap anywhere on it is a tap on the link. */
    private fun loadLink(href: String) =
        load("<a href=\"$href\" style='display:block;position:fixed;inset:0;font-size:40px'>Go</a>")

    /** A real tap in the middle of the page, which WebView counts as a user gesture. */
    private fun tap() {
        val (x, y) = onMain {
            val at = IntArray(2).also(view::getLocationOnScreen)
            (at[0] + view.width / 2f) to (at[1] + view.height / 2f)
        }
        val down = SystemClock.uptimeMillis()
        instrumentation.sendPointerSync(MotionEvent.obtain(down, down, MotionEvent.ACTION_DOWN, x, y, 0))
        instrumentation.sendPointerSync(MotionEvent.obtain(down, down + 50, MotionEvent.ACTION_UP, x, y, 0))
    }

    /** Runs a script; its result arrives in the queue once the page's dialogs are answered. */
    private fun evaluate(script: String): ArrayBlockingQueue<String> {
        val result = ArrayBlockingQueue<String>(1)
        onMain { view.evaluateJavascript(script) { result.offer(it) } }
        return result
    }

    private fun ArrayBlockingQueue<String>.answer(millis: Long = 5_000): String? = poll(millis, TimeUnit.MILLISECONDS)

    private fun tapped(prefix: String) {
        waitFor("the tap on $prefix to reach the policy") { seen.any { it.first.startsWith(prefix) } }
        assertTrue("stopped: $prefix", seen.first { it.first.startsWith(prefix) }.second)
        // Anything the tap led to has had its moment.
        Thread.sleep(500)
        composeRule.waitForIdle()
    }

    // Navigation

    /**
     * A sandboxed frame (an opaque origin, as another site's would be) sends the page to a
     * blob it made, and the page stays the app's. WebView asks shouldOverrideUrlLoading about
     * this one, though not about a page's own `location` change to a blob, which the check as
     * a navigation starts covers.
     */
    @Test
    fun testTheMainFrameShowsOnlyTheAppsOwnBlobs() {
        val frame = "<script>top.location.href = URL.createObjectURL(new Blob([&quot;&lt;title&gt;foreign blob&lt;/title&gt;&quot;], {type: &quot;text/html&quot;}))</script>"
        load("<iframe sandbox='allow-scripts allow-top-navigation' srcdoc='$frame'></iframe>")
        waitFor("the frame's navigation") { seen.any { it.first.startsWith("blob:null/") } }
        assertTrue("stopped", seen.first { it.first.startsWith("blob:null/") }.second)
        Thread.sleep(2_000)
        assertTrue(title(), title() == "ready" || title() == "home")
        assertTrue("on the app's own origin", onMain { view.url.orEmpty() }.startsWith(base))
        assertTrue("nothing opened", started.intents.isEmpty())

        load("<script>var own = URL.createObjectURL(new Blob(['<title>own blob</title>'], {type: 'text/html'}));</script>")
        evaluate("location.href = own")
        waitFor("the app's own blob") { title() == "own blob" }
    }

    @Test
    fun testAnIntentToOvenlightIsRefused() {
        for (link in listOf(
            "intent://join?v=1#Intent;scheme=https;package=com.snowyghost.ovenlight;end",
            "intent://join?v=1#Intent;scheme=ovenlight;end",
            "intent://ovenlight.app/JOIN#Intent;scheme=https;end",
            "intent://open?app=Pantry#Intent;scheme=ovenlight;package=com.example.other;end",
        )) {
            seen.clear()
            loadLink(link)
            tap()
            tapped("intent:")
            assertNull("no question for $link", edges.dialog.value)
            assertTrue("nothing opened for $link", started.intents.isEmpty())
        }

        // The same tap on an intent for another app asks first, and opens it as cleaned.
        seen.clear()
        loadLink("intent://example.com/#Intent;scheme=https;package=com.example.reader;launchFlags=0x10000000;end")
        tap()
        tapped("intent:")
        val asked = edges.dialog.value as PageEdges.Dialog.OpenElsewhere
        composeRule.onNodeWithText("Open in another app?").assertExists()
        assertTrue(asked.target, asked.target.startsWith("“intent://example.com/"))
        assertTrue("nothing opens before the answer", started.intents.isEmpty())
        // Open takes no tap in its first half second, so the page can't lure a double tap onto it.
        composeRule.onNodeWithText("Open").performClick()
        composeRule.waitForIdle()
        assertTrue("an early tap does nothing", started.intents.isEmpty())
        Thread.sleep(600)
        composeRule.onNodeWithText("Open").performClick()
        waitFor("the reader") { started.intents.isNotEmpty() }
        val opened = started.intents.single()
        assertEquals(Intent.ACTION_VIEW, opened.action)
        assertEquals("https://example.com/", opened.dataString)
        assertEquals("com.example.reader", opened.`package`)
        assertEquals(0, opened.flags)
        assertNull(opened.component)
    }

    @Test
    fun testAnIntentsFallbackIsIgnored() {
        loadLink("intent://scan/#Intent;scheme=zxing;package=com.example.missing;S.browser_fallback_url=https%3A%2F%2Ffallback.example%2F;end")
        tap()
        tapped("intent:")
        val asked = edges.dialog.value as PageEdges.Dialog.OpenElsewhere
        assertNull(asked.intent.getStringExtra("browser_fallback_url"))
        Thread.sleep(600)
        composeRule.onNodeWithText("Open").performClick()
        waitFor("the start") { started.intents.isNotEmpty() }
        Thread.sleep(1_000)
        val opened = started.intents.single()
        assertEquals("zxing://scan/", opened.dataString)
        assertNull(opened.getStringExtra("browser_fallback_url"))
        assertFalse("the page never went to the fallback", seen.any { it.first.startsWith("https://fallback.example") })
        assertEquals("ready", title())
    }

    /** As iOS opens it in Safari; a named browser means Ovenlight's App Link can't catch it. */
    @Test
    fun testTheJoinPageOpensInABrowser() {
        // As the site serves it, so no redirect could hand it to Ovenlight's App Link.
        loadLink("https://ovenlight.app/join.html#v=1&key=k")
        tap()
        tapped("https://ovenlight.app/join")
        waitFor("the browser") { started.intents.isNotEmpty() }
        val opened = started.intents.single()
        assertEquals("https://ovenlight.app/join#v=1&key=k", opened.dataString)
        assertTrue("a browser named: ${opened.`package`}", opened.`package` != null && opened.`package` != composeRule.activity.packageName)
    }

    @Test
    fun testTelOpensTheDialerWithoutAsking() {
        loadLink("tel:+15550100")
        tap()
        tapped("tel:")
        waitFor("the dialer") { started.intents.isNotEmpty() }
        val opened = started.intents.single()
        assertEquals(Intent.ACTION_DIAL, opened.action)
        assertEquals("tel:+15550100", opened.dataString)
        assertNull(edges.dialog.value)
    }

    @Test
    fun testMailAsksFirstAndCancelOpensNothing() {
        loadLink("mailto:riley@example.com")
        tap()
        tapped("mailto:")
        composeRule.onNodeWithText("Recipe Box wants to open “mailto:riley@example.com”.").assertExists()
        composeRule.onNodeWithText("Cancel").performClick()
        composeRule.waitForIdle()
        assertNull(edges.dialog.value)
        assertTrue(started.intents.isEmpty())
    }

    @Test
    fun testNothingOpensOffScreenOrLocked() {
        loadLink("tel:+15550100")
        shown = false
        tap()
        tapped("tel:")
        locked.value = true
        shown = true
        seen.clear()
        tap()
        tapped("tel:")
        assertTrue(started.intents.isEmpty())
    }

    /** shouldOverrideUrlLoading never sees a POST: the page goes back, and the address opens outside. */
    @Test
    fun testACrossSitePostIsStopped() {
        // Submitted by the page's script, so the page before it counts as untouched (see PageEdges.committed).
        load("<form id=f method=post action='https://forms.example.com/submit'><input name=a value=1></form>")
        evaluate("document.getElementById('f').submit()")
        waitFor("the address to open outside", 20_000) { started.intents.isNotEmpty() }
        Thread.sleep(2_000)
        assertEquals("one start: ${started.intents.map { "${it.action} ${it.dataString} ${it.`package`}" }}", 1, started.intents.size)
        assertEquals("https://forms.example.com/submit", started.intents.single().dataString)
        // Stopped before it committed the page never left; after, WebView won't step back to a
        // page from loadDataWithBaseURL, so the app starts again from its own page.
        waitFor("the app's page") { title() == "ready" || title() == "home" }
        Thread.sleep(2_000)
        assertTrue(title(), title() == "ready" || title() == "home")
        assertTrue(onMain { view.url.orEmpty() }.startsWith(base))
        assertTrue("never more than one way back", homes <= 1)
        assertFalse("the callback never saw it", seen.any { it.first.contains("forms.example.com") })
    }

    /** An address java.net.URI can't read is stopped too, never let through. */
    @Test
    fun testAPostToAnAddressURICantReadIsStopped() {
        load("<form id=f method=post action='https://forms.example.com/?a|b'><input name=a value=1></form>")
        evaluate("document.getElementById('f').submit()")
        Thread.sleep(3_000)
        assertTrue(title(), title() == "ready" || title() == "home")
        assertTrue(onMain { view.url.orEmpty() }.startsWith(base))
    }

    // Dialogs

    @Test
    fun testDialogsOffScreenOrLockedAreAnsweredAtOnce() {
        load("")
        shown = false
        assertEquals("\"ok no null\"", evaluate("alert('hi'); 'ok ' + (confirm('sure?') ? 'yes' : 'no') + ' ' + prompt('name?', 'Sam')").answer())
        shown = true
        locked.value = true
        assertEquals("\"no\"", evaluate("confirm('sure?') ? 'yes' : 'no'").answer())
        assertNull(edges.dialog.value)
    }

    @Test
    fun testAnAlertOnScreenIsTitledWithTheAppAndAnsweredOnce() {
        load("")
        val result = evaluate("alert('Saved'); 'after'")
        waitFor("the alert") { edges.dialog.value is PageEdges.Dialog.Alert }
        composeRule.onNodeWithText("Recipe Box").assertExists()
        composeRule.onNodeWithText("Saved").assertExists()
        assertNull("the page waits for the answer", result.answer(500))
        composeRule.onNodeWithText("OK").performClick()
        assertEquals("\"after\"", result.answer())
    }

    @Test
    fun testAPromptAnswersWithItsText() {
        load("")
        val result = evaluate("prompt('Your name?', 'Sam')")
        waitFor("the prompt") { edges.dialog.value is PageEdges.Dialog.Prompt }
        composeRule.onNodeWithText("Your name?").assertExists()
        composeRule.onNodeWithText("OK").performClick()
        assertEquals("\"Sam\"", result.answer())
    }

    @Test
    fun testADialogTakenAwayAnswersWithItsFallback() {
        load("")
        val result = evaluate("confirm('Delete it?') ? 'yes' : 'no'")
        waitFor("the confirm") { edges.dialog.value is PageEdges.Dialog.Confirm }
        // The app closing, or the lock, takes the page off screen.
        onMain { edges.takenAway() }
        assertEquals("\"no\"", result.answer())
        assertNull(edges.dialog.value)
    }

    @Test
    fun testBeforeUnloadNeverAsks() {
        load("<script>addEventListener('beforeunload', e => { e.preventDefault(); e.returnValue = 'stay'; });</script>")
        tap() // beforeunload asks only after the person has used the page
        evaluate("location.href = URL.createObjectURL(new Blob(['<title>next</title>'], {type: 'text/html'}))")
        waitFor("the next page") { title() == "next" }
        assertNull(edges.dialog.value)
    }

    @Test
    fun testNothingOpensWhileOvenlightIsAway() {
        load("")
        // ActivityScenario stops the activity by starting one over it, which the monitor would catch.
        instrumentation.removeMonitor(started)
        composeRule.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
        instrumentation.addMonitor(started)
        evaluate("location.href = 'https://forms.example.com/'")
        Thread.sleep(2_000)
        assertTrue("the policy saw it", seen.any { it.first.startsWith("https://forms.example.com") })
        assertTrue(started.intents.isEmpty())
        assertEquals("\"no\"", evaluate("confirm('sure?') ? 'yes' : 'no'").answer())
    }

    // The file input

    /** A page that is one big file input, which reports in its title. */
    private fun loadFileInput(attributes: String = "") = load(
        "<input type=file $attributes style='position:fixed;inset:0;width:100%;height:100%;font-size:40px'>" +
            "<script>const i = document.querySelector('input');" +
            "i.addEventListener('change', () => { document.title = 'picked ' + i.files.length + ' ' + (i.files[0] ? i.files[0].size : ''); });" +
            "i.addEventListener('cancel', () => { document.title = 'canceled'; });</script>",
    )

    @Test
    fun testAFileInputOffScreenGetsNothingAtOnce() {
        loadFileInput("accept='image/*'")
        shown = false
        tap()
        waitFor("the answer") { title() == "canceled" }
        assertTrue("no picker", started.intents.isEmpty())
    }

    @Test
    fun testACanceledPickerAnswersThePageOnce() {
        loadFileInput("accept='.pdf'")
        tap()
        waitFor("the documents picker") { started.intents.isNotEmpty() }
        assertEquals(Intent.ACTION_OPEN_DOCUMENT, started.intents.single().action)
        assertEquals(listOf("application/pdf"), started.intents.single().getStringArrayExtra(Intent.EXTRA_MIME_TYPES)?.toList())
        waitFor("the answer") { title() == "canceled" }
        // The page can ask again, so its first answer did come.
        tap()
        waitFor("a second picker") { started.intents.size == 2 }
    }

    /** As WebKit's menu does, an image input offers the camera beside the photo picker. */
    @Test
    fun testAnImageInputOffersTheCameraAndThePhotoPicker() {
        loadFileInput("accept='image/*'")
        tap()
        composeRule.waitUntil(5_000) { edges.dialog.value is PageEdges.Dialog.ChooseSource }
        composeRule.onNodeWithText("Take photo").assertExists()
        composeRule.onNodeWithText("Choose file").assertDoesNotExist()
        assertTrue("nothing yet", started.intents.isEmpty())
        composeRule.onNodeWithText("Choose photo").performClick()
        waitFor("the photo picker") { started.intents.isNotEmpty() }
        assertEquals(MediaStore.ACTION_PICK_IMAGES, started.intents.single().action)
        waitFor("the answer") { title() == "canceled" }
    }

    @Test
    fun testAnImageInputThatTakesMoreOffersFilesToo() {
        loadFileInput("accept='image/*,.pdf'")
        tap()
        composeRule.waitUntil(5_000) { edges.dialog.value is PageEdges.Dialog.ChooseSource }
        composeRule.onNodeWithText("Choose file").performClick()
        waitFor("the documents picker") { started.intents.isNotEmpty() }
        assertEquals(Intent.ACTION_OPEN_DOCUMENT, started.intents.single().action)
        assertEquals(listOf("image/*", "application/pdf"), started.intents.single().getStringArrayExtra(Intent.EXTRA_MIME_TYPES)?.toList())
        waitFor("the answer") { title() == "canceled" }
    }

    /** With the camera permission already granted; asking for it is the system's own prompt. */
    @Test
    fun testTakePhotoOpensTheCamera() {
        instrumentation.uiAutomation.grantRuntimePermission(composeRule.activity.packageName, Manifest.permission.CAMERA)
        loadFileInput("accept='image/*'")
        tap()
        composeRule.waitUntil(5_000) { edges.dialog.value is PageEdges.Dialog.ChooseSource }
        composeRule.onNodeWithText("Take photo").performClick()
        waitFor("the camera") { started.intents.isNotEmpty() }
        val camera = started.intents.single()
        assertEquals(MediaStore.ACTION_IMAGE_CAPTURE, camera.action)
        @Suppress("DEPRECATION")
        val output = camera.getParcelableExtra<Uri>(MediaStore.EXTRA_OUTPUT)
        assertEquals("${composeRule.activity.packageName}.captures", output?.authority)
        waitFor("the answer") { title() == "canceled" }
    }

    @Test
    fun testTheSheetTakenAwayAnswersWithNothing() {
        loadFileInput("accept='image/*'")
        tap()
        composeRule.waitUntil(5_000) { edges.dialog.value is PageEdges.Dialog.ChooseSource }
        onMain { edges.takenAway() }
        waitFor("the answer") { title() == "canceled" }
        assertTrue(started.intents.isEmpty())
    }

    /** A picker's result waits for the unlock, then reaches the page. */
    @Test
    fun testAPickedFileReachesThePageAfterTheUnlock() {
        val file = onMain { CaptureFileProvider.newFile(composeRule.activity, "txt").apply { writeText("hello") } }
        val uri = FileProvider.getUriForFile(composeRule.activity, CaptureFileProvider.authority(composeRule.activity), file)
        started.answer = {
            locked.value = true // the return from the picker locked Ovenlight
            Instrumentation.ActivityResult(Activity.RESULT_OK, Intent().setData(uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
        }
        loadFileInput()
        tap()
        waitFor("the picker") { started.intents.isNotEmpty() }
        Thread.sleep(1_500)
        assertEquals("nothing while locked", "ready", title())
        locked.value = false
        waitFor("the file in the page") { title() == "picked 1 5" }
        file.delete()
    }

    // Full screen

    @Test
    fun testFullScreenShowsAboveTheAppAndLeavesWithBack() {
        load("<div style='position:fixed;inset:0' onclick='document.documentElement.requestFullscreen()'>Go</div>")
        tap()
        waitFor("full screen") { edges.fullscreen.value != null }
        // A page could draw anything there: the notice names the app and the way out.
        composeRule.onNodeWithText("Recipe Box is full screen").assertExists()
        composeRule.onNodeWithText("Swipe down or press Back to exit").assertExists()
        composeRule.activityRule.scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
        waitFor("full screen to end") { edges.fullscreen.value == null }
        waitFor("the page to know") { evaluate("document.fullscreenElement === null").answer() == "true" }
    }

    // What a page can do to Ovenlight

    /** An address of megabytes reaches no intent, and Ovenlight keeps running. */
    @Test
    fun testAnOversizedAddressLeavesOvenlightRunning() {
        load("")
        evaluate("location.href = 'https://example.com/?' + 'a'.repeat(1500000)")
        waitFor("the policy") { seen.any { it.first.startsWith("https://example.com/?aaaa") } }
        Thread.sleep(1_000)
        assertTrue("nothing started", started.intents.isEmpty())
        // Unwatched, as a real start would go: still nothing, and no crash.
        instrumentation.removeMonitor(started)
        seen.clear()
        loadLink("intent://x#Intent;scheme=nothing;S.a=" + "b".repeat(1_500_000) + ";end")
        tap()
        waitFor("the tap") { seen.any { it.first.startsWith("intent:") } }
        Thread.sleep(2_000)
        assertNull(edges.dialog.value)
        assertEquals(Lifecycle.State.RESUMED, onMain { composeRule.activity.lifecycle.currentState })
        assertEquals("ready", title())
    }

    @Test
    fun testAPagesHugeAlertIsCut() {
        load("")
        val result = evaluate("alert('x'.repeat(5e6)); 'after'")
        waitFor("the alert") { edges.dialog.value is PageEdges.Dialog.Alert }
        assertEquals(4_001, (edges.dialog.value as PageEdges.Dialog.Alert).message.length)
        composeRule.onNodeWithText("OK").performClick()
        assertEquals("\"after\"", result.answer())
    }

    /** Addresses java.net.URI refuses as written, which Chromium writes for an app's own pages. */
    @Test
    fun testTheAppsOwnOddAddressesStayInTheApp() {
        for (suffix in listOf("?q={%22a%22:1}", "?a|b", "?x^y", "a[0]")) {
            load("")
            seen.clear()
            evaluate("location.href = '$base$suffix'")
            // Through the proxy with no route it fails to load, but it stays the app's page.
            waitFor("the navigation to $suffix") { onMain { view.url.orEmpty() } != base && onMain { view.progress } == 100 }
            Thread.sleep(1_000)
            val showing = onMain { view.url.orEmpty() }
            assertTrue("$suffix: $showing", showing.startsWith(base) && showing != base)
            assertTrue("$suffix: allowed if asked: $seen", seen.none { it.second })
            assertEquals("$suffix: never sent back", 0, homes)
        }
    }

    /** Chrome's rule: past three dialogs in five seconds, the page's dialogs can be stopped until it loads again. */
    @Test
    fun testADialogLoopEnds() {
        load("<script>function loop() { for (let i = 0; i < 50; i++) alert('again ' + i); document.title = 'done'; }</script>")
        val result = evaluate("loop()")
        for (i in 0 until 3) {
            waitFor("dialog $i") { (edges.dialog.value as? PageEdges.Dialog.Alert)?.message == "again $i" }
            composeRule.onNodeWithText("Don't let this app show more dialogs").assertDoesNotExist()
            composeRule.onNodeWithText("OK").performClick()
        }
        waitFor("dialog 3") { (edges.dialog.value as? PageEdges.Dialog.Alert)?.message == "again 3" }
        composeRule.onNodeWithText("Don't let this app show more dialogs").performClick()
        composeRule.onNodeWithText("OK").performClick()
        result.answer(10_000)
        waitFor("the loop's end") { title() == "done" }
        assertNull(edges.dialog.value)

        // Loaded again, the page may ask again.
        load("")
        evaluate("alert('back')")
        waitFor("a dialog again") { edges.dialog.value is PageEdges.Dialog.Alert }
        composeRule.onNodeWithText("OK").performClick()
    }

    /** setGeolocationEnabled(false) already fails it before the prompt; the prompt's answer is no too. */
    @Test
    fun testGeolocationIsDenied() {
        load("")
        val result = evaluate(
            "new Promise(done => navigator.geolocation.getCurrentPosition(() => done('granted'), e => done('denied ' + e.code)))" +
                ".then(r => { document.title = r; })",
        )
        result.answer()
        waitFor("the answer") { title().startsWith("denied") }
    }
}
