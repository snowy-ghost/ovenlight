package com.snowyghost.ovenlight

import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.File
import java.nio.file.Files
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Which blob and data downloads make a file: WebView's start and the page script's report must
 * meet, from the app's own origin, while its page is on screen and unlocked. http(s) refetches
 * run in the instrumented tests and the e2e downloads scenario.
 */
@RunWith(AndroidJUnit4::class)
class PageDownloadsTests {
    private val app = UUID.randomUUID()
    private val host = "recipes.tail0000.ts.net"
    private val folder: File = Files.createTempDirectory("downloads").toFile()
    private var clock = 1_000_000L
    private var onScreen = true
    private var locked = false
    private val scope = CoroutineScope(Dispatchers.Unconfined)
    private val downloads = PageDownloads(
        app, host, folder, scope, mayStart = { onScreen && !locked }, isLocked = { locked },
        client = { error("no http(s) download here") }, now = { clock },
    )

    @After
    fun cleanUp() {
        scope.cancel()
        folder.deleteRecursively()
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
        downloads.blob(PageMessage.Blob(url, name, "text/plain", bytes.toByteArray()), from)

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
        // The client would throw if anything fetched these.
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
}
