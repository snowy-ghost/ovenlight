package com.snowyghost.ovenlight

import java.net.URI
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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

    @Test
    fun testTitleFallback() {
        assertEquals("Coach", ManifestParser.title(inHTML = "<title>\n Coach </title>"))
        assertNull(ManifestParser.title(inHTML = "<p>none</p>"))
    }
}
