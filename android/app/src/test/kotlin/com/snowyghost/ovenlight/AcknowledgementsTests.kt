package com.snowyghost.ovenlight

import java.io.File
import java.io.FileNotFoundException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AcknowledgementsTests {
    /** Opens an asset as the APK would, from the folders the build merges into it. */
    private fun asset(path: String) = System.getProperty("ovenlight.assetDirs")!!.split(File.pathSeparator)
        .map { File(it, path) }.firstOrNull { it.isFile }?.inputStream() ?: throw FileNotFoundException(path)

    /** The build writes the libraries' and the Go core's licenses into the assets the app ships. */
    @Test
    fun testTheAppShipsTheLicenses() {
        val notices = Acknowledgements.notices(::asset)
        assertNotNull(notices)
        assertTrue(notices!!.contains("Go standard library"))
        assertTrue(notices.contains("tailscale.com"))
        assertTrue(notices.contains("androidx.compose.ui:ui"))
        assertTrue(notices.contains("Source Serif 4"))
    }

    @Test
    fun testAMissingFileSaysSo() {
        assertNull(Acknowledgements.notices { throw FileNotFoundException(it) })
        assertEquals(listOf("The licenses are missing from this build."), Acknowledgements.paragraphs(null))
        assertEquals(listOf("A", "B"), Acknowledgements.paragraphs("\nA\n\n\n\nB\n"))
    }
}
