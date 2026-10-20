package com.snowyghost.ovenlight

import java.net.URI
import kotlin.time.Duration.Companion.seconds
import kotlin.time.measureTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ManifestParserTests {
    private val base = URI("https://alex-mac.example.ts.net:8443/")

    @Test
    fun testFindsManifestAndTouchIconInAnyAttributeOrder() {
        val html = """
        <head><link href="manifest.webmanifest" rel="manifest">
        <link rel='apple-touch-icon' href='/apple-touch-icon.png'></head>
        """.trimIndent()
        val links = ManifestParser.links(inHTML = html, baseURL = base)
        assertEquals("https://alex-mac.example.ts.net:8443/manifest.webmanifest", links.manifest?.toString())
        assertEquals("https://alex-mac.example.ts.net:8443/apple-touch-icon.png", links.touchIcon?.toString())
    }

    @Test
    fun testPicksLargestRasterIconAndSkipsSVG() {
        val manifest = """
        {"name":"Interview Coach","theme_color":"#0e6b66","icons":[
          {"src":"icon-192.png","sizes":"192x192"},
          {"src":"icon-512.png","sizes":"512x512"},
          {"src":"icon.svg","sizes":"any","type":"image/svg+xml"}]}
        """.trimIndent()
        val meta = ManifestParser.metadata(fromManifest = manifest.toByteArray(), manifestURL = base.resolve("manifest.webmanifest"))
        assertEquals("Interview Coach", meta.name)
        assertEquals("#0e6b66", meta.themeColorHex)
        assertEquals("icon-512.png", meta.iconURL?.path?.substringAfterLast('/'))
    }

    /** Android only: java.net.URI resolves these two unlike iOS, so they're pinned here. */
    @Test
    fun testEmptyAndQueryOnlyLinksResolveAsOnIOS() {
        val page = URI("https://alex-mac.example.ts.net/app/page?v=1")
        val html = """<link rel="manifest" href=""><link rel="manifest" href="?v=2"><link rel="apple-touch-icon" href="icon.png">"""
        val links = ManifestParser.links(inHTML = html, baseURL = page)
        assertEquals("an empty link is no link", "https://alex-mac.example.ts.net/app/page?v=2", links.manifest?.toString())
        assertEquals("https://alex-mac.example.ts.net/app/icon.png", links.touchIcon?.toString())
    }

    @Test
    fun testTitleFallback() {
        assertEquals("Coach", ManifestParser.title(inHTML = "<title>\n Coach </title>"))
        assertNull(ManifestParser.title(inHTML = "<p>none</p>"))
    }

    /**
     * A page whose tags never close reads in time linear in its length. Each `<title` or `<link`
     * scanned to the end of the page, so 600 KB of them held the main thread for minutes.
     */
    @Test
    fun testATagThatNeverClosesDoesntStallTheRead() {
        val head = """<title lang="en">Coach</title><link rel="manifest" href="m.json"><link rel="apple-touch-icon" href="t.png">"""
        assertEquals("Coach", ManifestParser.title(head))
        val links = ManifestParser.links(head, base)
        assertEquals("https://alex-mac.example.ts.net:8443/m.json", links.manifest?.toString())
        assertEquals("https://alex-mac.example.ts.net:8443/t.png", links.touchIcon?.toString())
        for (tag in listOf("<title", "<link ")) {
            val page = tag.repeat(50_000)
            val took = measureTime {
                assertNull(ManifestParser.title(page))
                assertNull(ManifestParser.links(page, base).manifest)
            }
            assertTrue("$tag took $took", took < 2.seconds)
        }
    }

    /** An icon whose address is thousands of `..` segments is skipped: java.net.URI took seconds to drop them. */
    @Test
    fun testAnIconAddressOfEndlessDotSegmentsIsSkipped() {
        val src = "a/".repeat(120_000) + "../".repeat(120_000) + "icon.png"
        val manifest = """{"icons":[{"src":"$src","sizes":"512x512"},{"src":"ok.png","sizes":"192x192"}]}"""
        val took = measureTime {
            assertEquals("https://alex-mac.example.ts.net:8443/ok.png", ManifestParser.metadata(manifest.toByteArray(), base).iconURL?.toString())
        }
        assertTrue("took $took", took < 2.seconds)
    }
}
