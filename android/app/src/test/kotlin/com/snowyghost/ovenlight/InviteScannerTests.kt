package com.snowyghost.ovenlight

import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The scanner's reading of a camera frame, from a code drawn as the connector's are. On the
 * emulator its camera can't be pointed at one, so the frame is made here.
 */
class InviteScannerTests {
    private val link = "https://ovenlight.app/join#v=1&control=https%3A%2F%2Fcontrolplane.tailscale.com&key=tskey-auth-kX1abc-DEF%2Bghi" +
        "&owner=Riley&app=echo-board&invite=3f9a1c2b7e&host=echo-board.taildef456.ts.net&name=Echo+Board&to=Sam+Lee"

    /**
     * The brightness plane of a [width] by [height] frame, its rows padded to [rowStride] bytes as
     * camera buffers often are, with [text]'s code in the middle on a grey wall.
     */
    private fun frame(text: String, width: Int = 640, height: Int = 480, rowStride: Int = 704): ByteArray {
        val code = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, 300, 300)
        val left = (width - code.width) / 2
        val top = (height - code.height) / 2
        return ByteArray(rowStride * height) { i ->
            val x = i % rowStride - left
            val y = i / rowStride - top
            val inCode = x in 0 until code.width && y in 0 until code.height
            (if (inCode) (if (code[x, y]) 20 else 235) else 128).toByte()
        }
    }

    @Test
    fun testReadsAnInviteCodeFromACameraFrame() {
        assertEquals(link, InviteScanner.invite(frame(link), rowStride = 704, width = 640, height = 480))
    }

    @Test
    fun testACodeThatIsntAnInviteReadsAsNothing() {
        assertNull(InviteScanner.invite(frame("https://ovenlight.app/support"), rowStride = 704, width = 640, height = 480))
        assertNull("a frame with no code", InviteScanner.invite(ByteArray(704 * 480) { 128.toByte() }, rowStride = 704, width = 640, height = 480))
    }
}
