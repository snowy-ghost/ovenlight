package com.snowyghost.ovenlight

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.FileNotFoundException
import org.junit.Assert.assertEquals
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
    }

    @Test
    fun testAMissingFileSaysSo() {
        assertNull(Acknowledgements.notices { throw FileNotFoundException(it) })
        assertEquals(listOf("The licenses are missing from this build."), Acknowledgements.paragraphs(null))
        assertEquals(listOf("A", "B"), Acknowledgements.paragraphs("\nA\n\n\n\nB\n"))
        assertEquals("a CRLF file's returns go too", listOf("A", "B"), Acknowledgements.paragraphs("A\r\n\n\r\n\nB\r\n"))
        assertEquals("an indent wraps with its line", listOf("Apache:\n\u00A0\u00A0androidx.core:core:1.19.1"), Acknowledgements.paragraphs("Apache:\n  androidx.core:core:1.19.1"))
        assertEquals("Go standard library", Acknowledgements.heading("Go standard library\n==================="))
        assertNull(Acknowledgements.heading("Copyright 2009 The Go Authors."))
        assertNull("only = underlines a heading", Acknowledgements.heading("Source Serif\n------------"))
        assertNull("text that isn't UTF-8 counts as missing", Acknowledgements.notices { java.io.ByteArrayInputStream(byteArrayOf(0xC3.toByte(), 0x28)) })
    }
}
