package com.snowyghost.ovenlight

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
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.shadows.ShadowSystemClock

/** A page that asks in a loop, through its download sheets, can't trap the person. */
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
}
