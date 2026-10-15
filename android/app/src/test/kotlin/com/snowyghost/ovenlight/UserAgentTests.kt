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
}
