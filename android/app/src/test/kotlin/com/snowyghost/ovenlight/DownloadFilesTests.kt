package com.snowyghost.ovenlight

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** What a finished file is, from its bytes, and so what Open may hand it out as. */
class DownloadFilesTests {
    private fun type(text: String) = DownloadFiles.openableType(text.toByteArray())
    private fun type(vararg bytes: Int) = DownloadFiles.openableType(ByteArray(bytes.size) { bytes[it].toByte() })

    @Test
    fun testOpensDocumentsPicturesSoundAndVideo() {
        assertEquals("application/pdf", type("%PDF-1.7\n"))
        assertEquals("image/png", type(0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 0))
        assertEquals("image/jpeg", type(0xFF, 0xD8, 0xFF, 0xE0))
        assertEquals("image/gif", type("GIF89a"))
        assertEquals("image/webp", type("RIFF\u0000\u0000\u0000\u0000WEBPVP8 "))
        assertEquals("audio/wav", type("RIFF\u0000\u0000\u0000\u0000WAVEfmt "))
        assertEquals("video/mp4", type("\u0000\u0000\u0000\u0018ftypisom"))
        assertEquals("audio/mp4", type("\u0000\u0000\u0000\u0018ftypM4A "))
        assertEquals("image/heic", type("\u0000\u0000\u0000\u0018ftypheic"))
        assertEquals("audio/mpeg", type("ID3\u0004"))
        assertEquals("audio/mpeg", type(0xFF, 0xFB, 0x90))
        assertEquals("audio/ogg", type("OggS"))
        assertEquals("audio/flac", type("fLaC"))
        assertEquals("video/webm", type(0x1A, 0x45, 0xDF, 0xA3))
    }

    @Test
    fun testPlainTextOpensButNeverMarkup() {
        assertEquals("text/plain", type("name,amount\nflour,500\n"))
        assertEquals("text/plain", type("\uFEFFcafé crème\r\n"))
        assertNull("HTML", type("<!doctype html><script>alert(1)</script>"))
        assertNull("HTML after a blank line", type("\n  <html>"))
        assertNull("SVG", type("<svg xmlns=\"http://www.w3.org/2000/svg\"/>"))
        assertNull("HTML after a byte order mark", type("\uFEFF<html>"))
        assertNull("control characters", type("a\u0000b"))
        assertNull("not UTF-8", type(0x61, 0xC3, 0x28))
        assertNull("nothing", type())
    }

    @Test
    fun testATextHeadCutInsideACharacterIsStillText() {
        val text = ("a" + "é".repeat(DownloadFiles.HEAD)).toByteArray().copyOf(DownloadFiles.HEAD)
        assertEquals("text/plain", DownloadFiles.openableType(text))
        assertNull("but a shorter file isn't cut short", DownloadFiles.openableType(text.copyOf(102)))
    }

    @Test
    fun testNeverOpensAnAppOrAnArchive() {
        assertNull("an APK is a zip", type(0x50, 0x4B, 0x03, 0x04))
        assertNull(type(0x7F, 0x45, 0x4C, 0x46))
        assertEquals("other apps see no type in particular", DownloadFiles.ANY, DownloadFiles.sharedType(null))
    }

    @Test
    fun testDecodesDataURLsAsFetchDoes() {
        val plain = DataURL.decode("data:,a%20b%2Cc")!!
        assertEquals("text/plain", plain.type)
        assertArrayEquals("a b,c".toByteArray(), plain.bytes)
        val base64 = DataURL.decode("data:text/csv;charset=utf-8;base64,YSxi%0AYw")!!
        assertEquals("text/csv", base64.type)
        assertArrayEquals("a line break and no padding are fine", "a,bc".toByteArray(), base64.bytes)
        assertArrayEquals("unicode stays UTF-8", "é".toByteArray(), DataURL.decode("data:text/plain,é")!!.bytes)
        assertArrayEquals("a broken escape stays as it is", "%zz".toByteArray(), DataURL.decode("data:,%zz")!!.bytes)
        assertNull("no comma", DataURL.decode("data:text/plain"))
        assertNull(DataURL.decode("blob:https://a.example/1"))
    }
}
