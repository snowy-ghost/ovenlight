package com.snowyghost.ovenlight

import com.snowyghost.ovenlight.DownloadPolicy.ResponseAction
import java.net.URI
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
        fun action(status: Int?, disposition: String?, canShow: Boolean = true) = DownloadPolicy.action(status, disposition, canShow)
        assertEquals(ResponseAction.Download, action(200, "attachment; filename=\"export.csv\""))
        assertEquals(ResponseAction.Download, action(200, " Attachment"))
        assertEquals("space before the semicolon", ResponseAction.Download, action(200, "attachment ; filename=export.csv"))
        assertEquals(ResponseAction.Download, action(200, "ATTACHMENT;filename=export.csv"))
        assertEquals("a zip, say", ResponseAction.Download, action(200, null, canShow = false))
        assertEquals("a blob WebKit can't show", ResponseAction.Download, action(null, null, canShow = false))
        assertEquals(ResponseAction.Show, action(200, "inline; filename=\"report.pdf\""))
        assertEquals(ResponseAction.Show, action(200, "attachments-are-not-this"))
        assertEquals(ResponseAction.Show, action(200, null))
        assertEquals(ResponseAction.Show, action(200, ""))
        // No body, and no Content-Type, which WebKit takes as application/octet-stream.
        assertEquals("WebKit stays on the page itself", ResponseAction.Show, action(204, null, canShow = false))
        assertEquals(ResponseAction.Show, action(205, null, canShow = false))
        assertEquals(ResponseAction.Show, action(204, "attachment; filename=export.csv"))
        assertEquals(ResponseAction.Show, action(304, "attachment; filename=export.csv", canShow = false))
        assertEquals("an error page isn't a file", ResponseAction.Show, action(404, null, canShow = false))
        assertEquals(ResponseAction.Show, action(500, null, canShow = false))
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
}
