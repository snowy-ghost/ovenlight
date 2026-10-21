package com.snowyghost.ovenlight

import android.Manifest
import android.net.Uri
import android.webkit.PermissionRequest
import android.webkit.WebResourceRequest
import android.webkit.WebView
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
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
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowSystemClock

/** A page that asks in a loop, through its capture questions, download sheets or trips to another site, can't trap the person. */
@RunWith(AndroidJUnit4::class)
class PageLoopTests {
    private val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup().get()
    private val job = Job()
    private val scope = CoroutineScope(job)
    private val app = WebApp(name = "Recipe Box", startURL = URI("https://recipes.e2e.ts.net/"))
    private val lifecycle = AppLifecycle(scope, { 0L }, setAway = {}, closeNodes = {}, cameToFront = {})
    private val capture = PageCapture(
        app, ownersHosts = { emptySet() }, isLocked = { false }, isOnScreen = { true }, scope = scope,
        permissions = { _, _, done -> done(true) }, holds = { true }, stillCapturing = { false }, destroy = {},
    )
    private var downloadsStopped = 0
    private val edges = PageEdges(
        app, WebView(activity), activity, scope, MutableStateFlow(false), lifecycle, capture, isShown = { true },
        home = {}, stopDownloads = { downloadsStopped += 1 },
    )

    @get:Rule
    val composeRule = createComposeRule()

    @Before
    fun grantOverlayHiding() {
        // The manifest's normal permission, which Robolectric doesn't grant by itself.
        shadowOf(RuntimeEnvironment.getApplication()).grantPermissions(Manifest.permission.HIDE_OVERLAY_WINDOWS)
    }

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

    /** The app's own page asking for the camera. */
    private class AskForCamera : PermissionRequest() {
        var denied = false
        override fun getOrigin(): Uri = Uri.parse("https://recipes.e2e.ts.net")
        override fun getResources() = arrayOf(RESOURCE_VIDEO_CAPTURE)
        override fun grant(resources: Array<out String>) = Unit
        override fun deny() {
            denied = true
        }
    }

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

    /**
     * WebView names a redirect to another site it was told to stop to the page's callbacks as
     * well, and a POST only there: either way one navigation is one trip, and each goes as one,
     * so the run, the way out and one tab at a time hold for them too.
     */
    @Test
    fun testATripWebViewNamesTwiceOpensOnce() {
        go("https://accounts.example.com/sign-in")
        edges.pageStarted("https://accounts.example.com/sign-in")
        edges.pageFinished("https://accounts.example.com/sign-in")
        assertEquals("a sign-in's redirect opens once", 1, opened())
        edges.pageStarted("https://example.com/post")
        assertEquals("a POST is a trip, one at a time", 0, opened())

        pass(5_000)
        edges.inFront()
        go("https://accounts.example.com/again", tapped = true)
        edges.pageFinished("https://accounts.example.com/again")
        assertEquals("a tapped one opens once too", 1, opened())
    }

    /**
     * Ovenlight's camera question counts in the run with the page's dialogs, across the page's
     * own reloads, which forget a refusal: a page that reloads and asks again as each is refused
     * is offered the way out from the fourth, and taking it refuses the rest at once.
     */
    @Test
    fun testCameraQuestionsInARowOfferTheWayOut() {
        composeRule.setContent { CapturePrompt(capture, edges, app.name) }
        val quiet = "Don’t let this app show more dialogs"
        repeat(4) { n ->
            edges.chrome.onPermissionRequest(AskForCamera())
            composeRule.onNodeWithText("Allow Recipe Box to use the camera?").assertIsDisplayed()
            assertEquals("the way out, from the fourth", n == 3, composeRule.onAllNodesWithText(quiet).fetchSemanticsNodes().isNotEmpty())
            if (n == 3) composeRule.onNodeWithText(quiet).performClick()
            composeRule.onNodeWithText("Don’t allow").performClick()
            capture.documentChanged()
        }
        assertTrue(edges.quiet)
        val after = AskForCamera()
        edges.chrome.onPermissionRequest(after)
        assertTrue("stopped, the page is refused at once", after.denied)
        composeRule.onNodeWithText("Allow Recipe Box to use the camera?").assertDoesNotExist()
    }
}
