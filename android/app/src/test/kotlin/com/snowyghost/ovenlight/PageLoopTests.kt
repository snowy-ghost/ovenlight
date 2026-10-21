package com.snowyghost.ovenlight

import android.net.Uri
import android.webkit.WebResourceRequest
import android.webkit.WebView
import androidx.activity.ComponentActivity
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.net.URI
import java.time.Duration
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowSystemClock

/** A page that asks in a loop, through its download sheets or trips to another site, can't trap the person. */
@RunWith(AndroidJUnit4::class)
class PageLoopTests {
    private val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup().get()
    private val job = Job()
    private val scope = CoroutineScope(job)
    private val app = WebApp(name = "Recipe Box", startURL = URI("https://recipes.e2e.ts.net/"))
    private val lifecycle = AppLifecycle(scope, { 0L }, setAway = {}, closeNodes = {}, cameToFront = {})
    private val capture = PageCapture(
        app, ownersHosts = { emptySet() }, isLocked = { false }, isOnScreen = { true }, scope = scope,
        permissions = { _, _, done -> done(false) }, holds = { false }, stillCapturing = { false }, destroy = {},
    )
    private var downloadsStopped = 0
    private val edges = PageEdges(
        app, WebView(activity), activity, scope, MutableStateFlow(false), lifecycle, capture, isShown = { true },
        home = {}, stopDownloads = { downloadsStopped += 1 },
    )

    @After
    fun tearDown() = job.cancel()

    private fun pass(millis: Long) = ShadowSystemClock.advanceBy(Duration.ofMillis(millis))

    /** The page's main frame going to [to], by a tap or by itself. */
    private class Trip(private val to: String, private val tapped: Boolean = false) : WebResourceRequest {
        override fun getUrl(): Uri = Uri.parse(to)
        override fun isForMainFrame() = true
        override fun isRedirect() = false
        override fun hasGesture() = tapped
        override fun getMethod() = "GET"
        override fun getRequestHeaders(): Map<String, String> = emptyMap()
    }

    private fun go(to: String, tapped: Boolean = false) = edges.shouldOverrideUrlLoading(Trip(to, tapped))

    /** The tabs and other activities opened since the last look. */
    private fun opened() = generateSequence { shadowOf(activity).nextStartedActivity }.count()

    /**
     * Each download sheet counts in a run with the page's dialogs: past three in a row, each
     * within a second of the last one closing, the next offers the way out however long each
     * was read, and taking it stops the page's downloads too, until a reload or a reopen.
     */
    @Test
    fun testDownloadSheetsInARowOfferTheWayOut() {
        repeat(3) {
            assertFalse(edges.nextInRun())
            pass(20_000)
            edges.closedInRun()
            pass(900)
        }
        assertTrue("the fourth in a row", edges.nextInRun())
        edges.quietDialogs()
        assertTrue(edges.quiet)
        assertEquals("its downloads stop, and its waiting files go", 1, downloadsStopped)
        edges.allowDialogsAgain()
        assertFalse("a reload or a reopen lifts it", edges.quiet)

        repeat(10) {
            edges.closedInRun()
            pass(2_000)
            assertFalse("files a few seconds apart never offer it", edges.nextInRun())
        }
    }

    /**
     * The page's own trips to another site count in the run too, one at a time: a sign-in's
     * redirect opens its tab as ever, but a page that sends the person off again as each tab
     * closes is asked about from the fourth, with the way out, which stops its trips as well.
     */
    @Test
    fun testTripsToAnotherSiteInARowOfferTheWayOut() {
        go("https://accounts.example.com/sign-in")
        assertEquals("a sign-in's redirect opens", 1, opened())
        go("https://example.com/?1")
        assertEquals("one at a time", 0, opened())
        repeat(2) {
            pass(5_000)
            edges.inFront()
            pass(15)
            go("https://example.com/?again")
            assertEquals(1, opened())
        }
        edges.inFront()
        go("https://example.com/?3")
        assertEquals("the fourth in a row asks first", 0, opened())
        val asked = edges.dialog.value as PageEdges.Dialog.OpenSite
        assertTrue(asked.offersQuiet)
        go("https://example.com/?4")
        assertSame("and isn't replaced", asked, edges.dialog.value)
        go("https://example.com/tapped", tapped = true)
        assertEquals("a link the person taps opens every time", 1, opened())

        edges.quietDialogs()
        edges.dismiss(asked)
        go("https://example.com/?5")
        assertEquals("stopped, the page goes nowhere", 0, opened())
        assertNull(edges.dialog.value)
        edges.allowDialogsAgain()
        pass(2_000)
        go("https://example.com/?6")
        assertEquals("a reload or a reopen lifts it", 1, opened())
    }
}
