package com.snowyghost.ovenlight

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.FileNotFoundException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AcknowledgementsTests {
    /** The build writes the libraries' and the Go core's licenses into the assets the app ships. */
    @Test
    fun testTheAppShipsTheLicenses() {
        val notices = Acknowledgements.notices(ApplicationProvider.getApplicationContext<Context>().assets::open)
        assertNotNull(notices)
        assertTrue(notices!!.contains("Go standard library"))
        assertTrue(notices.contains("tailscale.com"))
        assertTrue(notices.contains("androidx.compose.ui:ui"))
        assertTrue(notices.contains("Source Serif 4"))
        assertTrue(notices.contains("Material Symbols"))
        assertTrue("CameraX's camera-core links it in", notices.contains("The LibYuv Project Authors"))
        assertTrue(notices.contains("PublicSuffixDatabase.list"))
        assertFalse("a BOM ships no code", notices.contains("compose-bom"))
    }

    @Test
    fun testAMissingFileSaysSo() {
        assertNull(Acknowledgements.notices { throw FileNotFoundException(it) })
        assertEquals(listOf("The licenses are missing from this build."), Acknowledgements.paragraphs(null))
        assertEquals(listOf("A", "B"), Acknowledgements.paragraphs("\nA\n\n\n\nB\n"))
        assertEquals("a CRLF file's returns go too", listOf("A", "B"), Acknowledgements.paragraphs("A\r\n\n\r\n\nB\r\n"))
        assertNull("text that isn't UTF-8 counts as missing", Acknowledgements.notices { java.io.ByteArrayInputStream(byteArrayOf(0xC3.toByte(), 0x28)) })
    }

    /** A module's title underlined with `=` shows as a heading. */
    @Test
    fun testASectionTitleIsAHeading() {
        assertEquals("Go standard library", Acknowledgements.heading("Go standard library\n==================="))
        assertNull(Acknowledgements.heading("Copyright 2009 The Go Authors."))
        assertNull("only = underlines a heading", Acknowledgements.heading("Source Serif\n------------"))
    }

    /** Android only: each line wraps under its own indent. */
    @Test
    fun testEachLineHangsUnderItsOwnIndent() {
        assertEquals(listOf("Apache:\n  androidx.core:core:1.19.1"), Acknowledgements.paragraphs("Apache:\n  androidx.core:core:1.19.1"))
        assertEquals(
            listOf(0 to "Apache:", 2 to "androidx.core:core:1.19.1", 6 to "\"License\" shall mean"),
            Acknowledgements.lines("Apache:\n  androidx.core:core:1.19.1\n      \"License\" shall mean"),
        )
    }

    /** A library's coordinates break only where they don't fit, and then only between their parts. */
    @Test
    fun testCoordinatesStayWhole() {
        assertEquals("androidx.core:\u200Bcore-\u2060ktx:\u200B1.9", Acknowledgements.unbroken("androidx.core:core-ktx:1.9"))
        assertEquals("prose breaks as prose", "Version 2.0 (the", Acknowledgements.unbroken("Version 2.0 (the"))
    }
}
