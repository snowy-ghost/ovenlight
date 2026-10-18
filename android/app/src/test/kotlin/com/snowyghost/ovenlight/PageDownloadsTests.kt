package com.snowyghost.ovenlight

import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.File
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import okhttp3.CookieJar
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith

/**
 * Which blob and data downloads make a file: WebView's start and the page script's report must
 * meet, from the app's own origin, while its page is on screen and unlocked. http(s) refetches
 * run in the instrumented tests and the e2e downloads scenario.
 */
@RunWith(AndroidJUnit4::class)
class PageDownloadsTests {
    @get:Rule
    val temp = TemporaryFolder()
    private val folder: File get() = temp.root

    private val app = UUID.randomUUID()
    private val host = "recipes.tail0000.ts.net"
    private var clock = 1_000_000L
    private var onScreen = true
    private var locked = false
    private val scope = CoroutineScope(Dispatchers.Unconfined)
    /** What the refetch's server answers: a status, and a body. */
    private var free = Long.MAX_VALUE / 2
    private var answer: Pair<Int, String> = 200 to "flour,500\n"
    private var held: java.util.concurrent.CountDownLatch? = null
    private val server = OkHttpClient.Builder().addInterceptor { chain ->
        held?.await(5, java.util.concurrent.TimeUnit.SECONDS)
        val (code, body) = answer
        Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(code).message("answer")
            .apply { if (code in 300..399) header("Location", "https://pantry.tail0000.ts.net/secret") }
            .header("Content-Disposition", "attachment; filename=\"export.csv\"").body(body.toResponseBody()).build()
    }.build()
    // Made in the test, once the folder is.
    private val downloads by lazy {
        PageDownloads(
            app, host, folder, scope, mayStart = { onScreen && !locked }, isLocked = { locked },
            client = { server }, freeBytes = { free }, now = { clock },
        )
    }

    @After
    fun cleanUp() {
        scope.cancel()
    }

    /** The files on the shelf for the app, once [count] have arrived (or after 2 seconds), in the order they finished. */
    private fun shelf(count: Int = 1): List<File> {
        repeat(200) {
            val files = DownloadShelf.waiting.value[app].orEmpty().map { it.file }
            if (files.size >= count) return files
            Thread.sleep(10)
        }
        return DownloadShelf.waiting.value[app].orEmpty().map { it.file }
    }

    private fun start(url: String, type: String = "") = downloads.onDownloadStart(url, "agent", null, type, 0)

    private fun blob(url: String, bytes: String, name: String = "note.txt", from: String = host) =
        downloads.blob(PageBlob(url, name, "text/plain", bytes.toByteArray()), from)

    @Test
    fun testADataURLNeedsTheClickThatStartedIt() {
        start("data:text/plain,nobody clicked")
        assertTrue("WebView's start alone makes nothing", shelf().isEmpty())
        clock += PageDownloads.PAIR_MILLIS + 1
        downloads.clicked("data:text/plain,nobody clicked", "late.txt", host)
        assertTrue("nor a click that came long after", shelf().isEmpty())

        downloads.clicked("data:text/plain,first", "first.txt", host)
        start("data:text/plain,first")
        start("data:text/plain,second")
        downloads.clicked("data:text/plain,second", "second.txt", host)
        val files = shelf(2).sortedBy { it.name }
        assertEquals(listOf("first.txt", "second.txt"), files.map { it.name })
        assertEquals("second", files[1].readText())

        downloads.clicked("data:text/plain,elsewhere", "x.txt", "widget.example.com")
        start("data:text/plain,elsewhere")
        assertEquals("a click another site's frame reported starts nothing", 2, shelf(3).size)
    }

    @Test
    fun testABlobArrivesWithItsBytesInEitherOrder() {
        val first = "blob:https://$host/6c1f0b2e-1d2a-4c8e-9a51-0b7a3e5d9f10"
        val second = "blob:https://$host/0b7a3e5d-9f10-4c8e-9a51-6c1f0b2e1d2a"
        start(first)
        blob(first, "one", name = "one.txt")
        blob(second, "two", name = "two.txt")
        start(second)
        val files = shelf(2).sortedBy { it.name }
        assertEquals(listOf("one.txt", "two.txt"), files.map { it.name })
        assertEquals("one", files[0].readText())

        blob("blob:https://widget.example.com/6c1f0b2e-1d2a-4c8e-9a51-0b7a3e5d9f10", "x")
        start("blob:https://widget.example.com/6c1f0b2e-1d2a-4c8e-9a51-0b7a3e5d9f10")
        val prevented = "blob:https://$host/1d2a6c1f-0b2e-4c8e-9a51-0b7a3e5d9f10"
        blob(prevented, "the page cancelled the click")
        clock += PageDownloads.PAIR_MILLIS + 1
        start(prevented)
        assertEquals("another site's blob, and bytes no download used, make nothing", 2, shelf(3).size)
    }

    @Test
    fun testNothingStartsOffScreenOrLocked() {
        downloads.clicked("data:text/plain,hidden", "a.txt", host)
        onScreen = false
        start("data:text/plain,hidden")
        onScreen = true
        locked = true
        downloads.clicked("data:text/plain,locked", "b.txt", host)
        start("data:text/plain,locked")
        locked = false
        start("https://cdn.example.com/export.csv")
        start("file:///sdcard/x")
        assertTrue(shelf().isEmpty())
    }

    @Test
    fun testEmptyFilesAreSkippedAndUnnamedOnesNamedForTheirType() {
        downloads.clicked("data:text/plain,", "empty.txt", host)
        start("data:text/plain,")
        downloads.clicked("data:text/plain,named", "", host)
        start("data:text/plain,named")
        val files = shelf()
        assertEquals(1, files.size)
        assertTrue(files[0].name, files[0].name.startsWith("Download"))
        assertEquals("the empty file left no folder behind", 1, folder.listFiles()!!.size)
    }

    @Test
    fun testARefetchFollowsNoRedirect() {
        val client = PageDownloads.client(OkHttpClient.Builder(), CookieJar.NO_COOKIES)
        assertFalse(client.followRedirects)
        assertEquals("no limit on a download that keeps arriving", 0, client.callTimeoutMillis)
        assertEquals("the headers may take minutes", PageDownloads.HEADERS_WAIT_MINUTES * 60_000, client.readTimeoutMillis.toLong())
        answer = 302 to "elsewhere"
        start("https://$host/export.csv")
        assertTrue("a 302 to another app is no file", shelf().isEmpty())
        answer = 200 to "flour,500\n"
        start("https://$host/export.csv")
        assertEquals("flour,500\n", shelf().single().readText())
    }

    @Test
    fun testADownloadLeavesThePhoneRoom() {
        free = PageDownloads.FREE_SPACE_FLOOR + 4
        answer = 200 to "flour,500\n"
        start("https://$host/export.csv")
        downloads.clicked("data:text/plain,hello", "a.txt", host)
        start("data:text/plain,hello")
        assertTrue(shelf().isEmpty())
        assertEquals("and the partial files went", 0, folder.listFiles()!!.size)
    }

    @Test
    fun testCancelEndsADownloadUnderWay() {
        held = java.util.concurrent.CountDownLatch(1)
        var busy = false
        val downloads = PageDownloads(
            app, host, folder, scope, mayStart = { true }, isLocked = { false },
            client = { server }, freeBytes = { free }, busy = { busy = it }, now = { clock },
        )
        downloads.onDownloadStart("https://$host/export.csv", "agent", null, "text/csv", 0)
        assertTrue(busy)
        downloads.cancel()
        held!!.countDown()
        repeat(200) { if (busy) Thread.sleep(10) }
        assertFalse("the page no longer counts as downloading", busy)
        assertTrue("and nothing reached the sheet", shelf().isEmpty())
        assertEquals(0, folder.listFiles()!!.size)
    }

    /** Android only: the page's report of an `<a download>` click can come after WebView's start, over the port. */
    @Test
    fun testAnHttpDownloadTakesTheNameOfAClickReportedJustAfter() {
        downloads.onDownloadStart("https://$host/export.csv", "agent", null, "text/csv", 0)
        Thread.sleep(120)
        downloads.clicked("https://$host/export.csv", "Recipes export.csv", host)
        assertEquals(listOf("Recipes export.csv"), shelf().map { it.name })
    }

    @Test
    fun testADownloadWaitingForItsNameStopsIfThePageLeavesTheScreen() {
        downloads.onDownloadStart("https://$host/export.csv", "agent", null, "text/csv", 0)
        onScreen = false
        assertTrue(shelf().isEmpty())
    }

    /** Android only: the app's own address with characters java.net.URI refuses, as Chromium writes them, still downloads. */
    @Test
    fun testAnAppsOwnOddAddressStillDownloads() {
        start("https://$host/export.csv?q={%22a%22:1}&a|b")
        assertEquals(1, shelf().size)
        start("https://pantry.tail0000.ts.net/x?q={a}")
        assertEquals("another app's stays refused", 1, shelf(2).size)
    }

    @Test
    fun testAtMostThreeDownloadsAtOnce() {
        held = java.util.concurrent.CountDownLatch(1)
        repeat(PageDownloads.CONCURRENT + 1) { start("https://$host/export-$it.csv") }
        held!!.countDown()
        assertEquals(PageDownloads.CONCURRENT, shelf(PageDownloads.CONCURRENT + 1).size)
    }

    @Test
    fun testWhatWaitsToBePairedStaysSmall() {
        onScreen = false
        downloads.clicked("data:text/plain,hidden", "a.txt", host)
        onScreen = true
        start("data:text/plain,hidden")
        assertTrue("a report off screen is dropped", shelf().isEmpty())

        repeat(PageDownloads.PENDING_CLICKS + 1) { downloads.clicked("data:text/plain,$it", "$it.txt", host) }
        start("data:text/plain,0")
        assertTrue("the oldest click went", shelf().isEmpty())
        start("data:text/plain,${PageDownloads.PENDING_CLICKS}")
        assertEquals("${PageDownloads.PENDING_CLICKS}.txt", shelf().single().name)

        val first = "blob:https://$host/6c1f0b2e-1d2a-4c8e-9a51-0b7a3e5d9f10"
        blob(first, "first")
        blob("blob:https://$host/0b7a3e5d-9f10-4c8e-9a51-6c1f0b2e1d2a", "second")
        start(first)
        assertEquals("one blob read waits at a time", 1, shelf(2).size)
    }

    @Test
    fun testOnlySoManyFilesWaitForTheSheet() {
        repeat(PageDownloads.WAITING_FILES + 1) {
            downloads.clicked("data:text/plain,$it", "$it.txt", host)
            start("data:text/plain,$it")
            shelf(minOf(it + 1, PageDownloads.WAITING_FILES))
        }
        assertEquals(PageDownloads.WAITING_FILES, shelf(PageDownloads.WAITING_FILES + 1).size)
        assertEquals("the refused file left nothing behind", PageDownloads.WAITING_FILES, folder.listFiles()!!.size)
    }
}
