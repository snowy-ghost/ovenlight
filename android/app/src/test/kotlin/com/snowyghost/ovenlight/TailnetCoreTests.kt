package com.snowyghost.ovenlight

import java.io.IOException
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.asResponseBody
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import okio.Source
import okio.Timeout
import okio.buffer
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

// Android only: iOS's URLSession reads the bodies itself.
class TailnetCoreTests {
    private fun response(body: okhttp3.ResponseBody) = Response.Builder().request(Request.Builder().url("https://recipes.tail0000.ts.net/claim").build())
        .protocol(Protocol.HTTP_1_1).code(200).message("OK").body(body).build()

    /** A body cut short or too long is no answer, as no response is: a claim that got one may be sent again. */
    @Test
    fun testOnlyAWholeBodyIsAnAnswer() {
        val whole = TailnetCore.answer(response("12345678".toResponseBody()), cap = 8)
        assertEquals(200, whole?.status)
        assertArrayEquals("12345678".toByteArray(), whole?.body)
        assertNull("over the cap", TailnetCore.answer(response("123456789".toResponseBody()), cap = 8))

        val broken = object : Source {
            var sent = false
            override fun read(sink: Buffer, byteCount: Long): Long {
                if (sent) throw IOException("connection reset")
                sent = true
                sink.writeUtf8("{\"ok\":")
                return 6
            }
            override fun timeout() = Timeout.NONE
            override fun close() {}
        }
        assertNull("cut short", TailnetCore.answer(response(broken.buffer().asResponseBody(null, 100)), cap = 8))
    }
}
