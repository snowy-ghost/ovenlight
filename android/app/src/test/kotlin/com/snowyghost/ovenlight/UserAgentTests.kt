package com.snowyghost.ovenlight

import org.junit.Assert.assertEquals
import org.junit.Test

/** What a page itself sees, in a real WebView, is testPagesStillSeeMobileSafari (PageScriptTests). */
class UserAgentTests {
    @Test
    fun testEndsWithOvenlightAndItsVersion() {
        assertEquals("Mobile/15E148 Ovenlight/1.2", WebViewPool.userAgentName(after = "Mobile/15E148", version = "1.2"))
        assertEquals("Ovenlight/1.2", WebViewPool.userAgentName(after = null, version = "1.2"))
    }

    @Test
    fun testNamesNoPhoneModelBuildOrAndroidVersion() {
        val android16 = "Mozilla/5.0 (Linux; Android 16; Pixel 9 Pro Build/BP2A.250605.031; wv) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Version/4.0 Chrome/145.0.7632.159 Mobile Safari/537.36"
        assertEquals(
            "Mozilla/5.0 (Linux; Android 10; K; wv) AppleWebKit/537.36 (KHTML, like Gecko) Version/4.0 " +
                "Chrome/145.0.0.0 Mobile Safari/537.36 Ovenlight/1.2",
            WebViewPool.userAgentName(after = android16, version = "1.2"),
        )
        val android17 = "Mozilla/5.0 (Linux; Android 10; K; wv) AppleWebKit/537.36 (KHTML, like Gecko) Version/4.0 " +
            "Chrome/150.0.0.0 Mobile Safari/537.36"
        assertEquals("$android17 Ovenlight/1.2", WebViewPool.userAgentName(after = android17, version = "1.2"))
    }
}
