package com.snowyghost.ovenlight

import java.io.File
import java.net.URI
import java.nio.ByteBuffer
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** What the page script may send, and the script itself as the app ships it. */
class PageBridgeTests {
    @Test
    fun testReadsOnlyTheMessagesThePageScriptSends() {
        assertEquals(PageMessage.Capture, PageMessage.parse("""{"t":"capture"}"""))
        assertEquals(PageMessage.Click("blob:https://a.example/1", "x.csv"), PageMessage.parse("""{"t":"click","href":"blob:https://a.example/1","name":"x.csv"}"""))
        assertNull("no name", PageMessage.parse("""{"t":"click","href":"https://a.example/x"}"""))
        assertNull("a number for a name", PageMessage.parse("""{"t":"click","href":"https://a.example/x","name":3}"""))
        assertNull(PageMessage.parse("""{"t":"grant","resource":"camera"}"""))
        assertNull(PageMessage.parse("""["capture"]"""))
        assertNull(PageMessage.parse("capture"))
    }

    private fun blobMessage(header: String, body: ByteArray, length: Int? = null): ByteArray {
        val head = header.toByteArray()
        return ByteBuffer.allocate(4 + head.size + body.size).putInt(length ?: head.size).put(head).put(body).array()
    }

    @Test
    fun testReadsABlobsHeaderAndBytes() {
        val blob = PageMessage.blob(blobMessage("""{"href":"blob:https://a.example/1","name":"n.txt","type":"text/plain"}""", "hello".toByteArray()))!!
        assertEquals("blob:https://a.example/1", blob.href)
        assertEquals("n.txt", blob.name)
        assertEquals("text/plain", blob.type)
        assertArrayEquals("hello".toByteArray(), blob.bytes)
        assertNull("only a blob URL", PageMessage.blob(blobMessage("""{"href":"https://a.example/1","name":""}""", ByteArray(1))))
        assertNull("a header longer than the message", PageMessage.blob(blobMessage("""{"href":"blob:x"}""", ByteArray(0), length = 999)))
        assertNull("a negative header length", PageMessage.blob(blobMessage("""{"href":"blob:x"}""", ByteArray(0), length = -1)))
        assertNull(PageMessage.blob(ByteArray(3)))
    }

    @Test
    fun testTheBridgeIsOnlyForTheAppsOwnOrigin() {
        assertEquals("https://recipes.tail0000.ts.net", PageBridge.originRule(URI("https://Recipes.tail0000.ts.net/start?x=1")))
        assertEquals("https://recipes.example.com:8443", PageBridge.originRule(URI("https://recipes.example.com:8443/")))
        assertNull(PageBridge.originRule(URI("ovenlight://open?app=x")))
    }

    @Test
    fun testThePageScriptGetsItsLimitsAndWords() {
        val dirs = System.getProperty("ovenlight.assetDirs")!!.split(File.pathSeparator)
        val script = dirs.map { File(it, "page.js") }.first { it.isFile }.readText()
        assertTrue(script.contains("const LIMIT = __LIMIT__, HREF_LIMIT = __HREF_LIMIT__, HEADER_LIMIT = __HEADER_LIMIT__;"))
        assertTrue(script.contains("const OFFER = \"__OFFER__\", ANSWER = \"__ANSWER__\", STOP = \"__STOP__\""))
        assertTrue("no binding left in any frame", !script.contains("__ovenlight"))
    }
}
