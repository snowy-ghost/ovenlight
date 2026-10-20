package com.snowyghost.ovenlight

import androidx.webkit.URLUtilCompat
import java.net.URI
import kotlin.time.Duration.Companion.seconds
import kotlin.time.measureTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The two share sheet tests (testTheShareSheetClosesOnce, testOnlyAShownSheetTakesItsFiles) are
 * iOS presentation only: Android shows Ovenlight's own Save, Share and Open sheet.
 */
class DownloadPolicyTests {
    private val host = "coach.tailabc123.ts.net"

    private fun allows(text: String, from: String? = "Coach.tailabc123.ts.net", locked: Boolean = false): Boolean =
        DownloadPolicy.allows(URI(text), from = from, appHost = host, locked = locked)

    @Test
    fun testOnlyTheAppsOwnFilesDownload() {
        assertTrue(allows("https://coach.tailabc123.ts.net/export.csv"))
        // On Android every http(s) download is this case: the DownloadListener names no frame.
        assertTrue("a response names no frame", allows("https://coach.tailabc123.ts.net/export.csv", from = null))
        assertTrue(allows("blob:https://coach.tailabc123.ts.net/6c1f0b2e-1d2a-4c8e-9a51-0b7a3e5d9f10"))
        assertTrue(allows("data:text/csv,a,b"))
        assertFalse("a data URL is the app's only when its page started it", allows("data:text/csv,a,b", from = null))
        assertFalse(allows("https://cdn.example.com/export.csv"))
        assertFalse(allows("blob:https://widget.example.com/6c1f0b2e-1d2a-4c8e-9a51-0b7a3e5d9f10"))
        assertFalse("a frame of another site starts nothing", allows("https://coach.tailabc123.ts.net/export.csv", from = "widget.example.com"))
        assertFalse("nothing under the lock screen", allows("https://coach.tailabc123.ts.net/export.csv", locked = true))
        assertFalse(allows("javascript:void(0)"))
    }

    @Test
    fun testAttachmentsAndFilesWebKitCantShowDownload() {
        assertTrue(DownloadPolicy.isFile(200, null))
        assertTrue("an error sent as an attachment", DownloadPolicy.isFile(404, " Attachment ; filename=export.csv"))
        assertFalse("no body: the page stays as it is", DownloadPolicy.isFile(204, null))
        assertFalse(DownloadPolicy.isFile(205, null))
        assertFalse(DownloadPolicy.isFile(204, "attachment; filename=export.csv"))
        assertFalse(DownloadPolicy.isFile(304, "attachment; filename=export.csv"))
        assertFalse("an error page isn't a file", DownloadPolicy.isFile(404, null))
        assertFalse(DownloadPolicy.isFile(500, "inline"))
    }

    @Test
    fun testFileNamesAreSafeToWrite() {
        assertEquals("export.csv", DownloadPolicy.fileName("export.csv"))
        assertEquals("-..-Library-apps.json", DownloadPolicy.fileName("../../Library/apps.json"))
        assertEquals("hidden", DownloadPolicy.fileName(".hidden"))
        assertEquals("a-b-c-d.txt", DownloadPolicy.fileName("a:b\\c\u0000d.txt"))
        assertEquals("Download", DownloadPolicy.fileName(" .. "))
        assertEquals("Notes ✏️ May.md", DownloadPolicy.fileName("Notes ✏️ May.md"))
        val long = DownloadPolicy.fileName("é".repeat(300) + ".csv")
        assertTrue(long.toByteArray(Charsets.UTF_8).size <= DownloadPolicy.maxFileNameBytes)
        assertTrue(long.endsWith("é.csv"))
        // Not in the Swift test: Foundation reads "final version" as no extension, so the end is cut.
        assertEquals("a".repeat(200), DownloadPolicy.fileName("a".repeat(250) + ".final version"))
    }

    /**
     * A page's name of any length is cut in time linear in its length, at the same place as
     * before: whole characters as people see them, the extension kept. Cut a character a pass,
     * a name of 400,000 letters held the main thread for over two minutes as its download failed.
     */
    @Test
    fun testALongNameIsCutWithoutStalling() {
        assertEquals("é".repeat(98) + ".csv", DownloadPolicy.fileName("é".repeat(300) + ".csv"))
        val flag = "\uD83C\uDDEC\uD83C\uDDE7"
        assertEquals("a".repeat(184) + flag + ".txt", DownloadPolicy.fileName("a".repeat(184) + flag + flag + ".txt"))
        assertEquals("a".repeat(190) + ".txt", DownloadPolicy.fileName("a".repeat(190) + flag + ".txt"))
        val took = measureTime {
            assertEquals("A".repeat(196) + ".txt", DownloadPolicy.fileName("A".repeat(100_000) + ".txt"))
        }
        assertTrue("took $took", took < 2.seconds)
    }

    /**
     * Android only: a file's name is read from a Content-Disposition header of up to 2 KB.
     * androidx's parser takes time with the square of a header's length, and a failed download's
     * notice reads it on the main thread: 64 KB took over 15 seconds on the JVM.
     */
    @Test
    fun testOnlyAShortDispositionIsRead() {
        fun name(header: String) = DownloadPolicy.disposition(header)?.let(URLUtilCompat::getFilenameFromContentDisposition)
        assertEquals("export.csv", name("attachment; filename=\"export.csv\""))
        val long = "a".repeat(DownloadPolicy.maxFileNameBytes)
        assertEquals(long, name("attachment; filename=\"$long\"; filename*=UTF-8''" + "%61".repeat(DownloadPolicy.maxFileNameBytes)))
        val took = measureTime { assertNull(name("attachment; " + "a".repeat(64_000))) }
        assertTrue("took $took", took < 2.seconds)
    }

    /** Android only: breaks, spaces and blank letters a page could use to push the real extension out of sight. */
    @Test
    fun testANameCantHideItsRealExtension() {
        assertEquals("report.pdf---.apk", DownloadPolicy.fileName("report.pdf\u2028\u2028\u2028.apk"))
        assertEquals("a-b-c.apk", DownloadPolicy.fileName("a\u2029b\u3000c.apk"))
        assertEquals("no-break-space-thin.apk", DownloadPolicy.fileName("no\u00A0break\u2009space\u202Fthin.apk"))
        assertEquals("pdf-----.apk", DownloadPolicy.fileName("pdf\u2800\u3164\u115F\u1160\uFFA0.apk"))
        assertEquals("an ordinary space stays", "Recipes export.csv", DownloadPolicy.fileName("Recipes export.csv"))
    }

    /** The notice's name: the toast says "Couldn't download the file" when there is none. */
    @Test
    fun testAFailedDownloadIsNamedOnlyByARealFileName() {
        assertEquals("report.pdf", DownloadPolicy.name("https://coach.example.com/exports/report.pdf"))
        assertNull("no path", DownloadPolicy.name("https://coach.example.com/"))
        assertNull(DownloadPolicy.name("https://coach.example.com"))
        assertNull(DownloadPolicy.name("blob:https://coach.example.com/6f1c2b3a-0d4e-4f5a-9b8c-7d6e5f4a3b2c"))
        assertNull(DownloadPolicy.name("data:text/csv;base64,YSxi"))
    }
}
