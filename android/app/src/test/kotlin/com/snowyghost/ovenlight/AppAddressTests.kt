package com.snowyghost.ovenlight

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AppAddressTests {
    @Test
    fun testAddsHTTPSToABareHostAndKeepsPortAndPath() {
        assertEquals("https://alex-mac.tailabc123.ts.net:8443/app", AppAddress.url(from = "  alex-mac.tailabc123.ts.net:8443/app ")?.toString())
        assertEquals("example.com", AppAddress.url(from = "HTTPS://example.com")?.host)
    }

    @Test
    fun testRefusesInsecureAndIncompleteAddresses() {
        assertNull(AppAddress.url(from = "http://example.com"))
        assertNull(AppAddress.url(from = "https://"))
        assertNull(AppAddress.url(from = "example"))
        assertNull(AppAddress.url(from = ""))
    }

    @Test
    fun testAddsOnlyAddressesOnTheOwnersOwnTailnet() {
        val own = "tailabc123.ts.net"
        assertEquals("https://coach.tailabc123.ts.net", AppAddress.url(from = "coach.tailabc123.ts.net", ownerSuffix = own)?.toString())
        assertEquals(8443, AppAddress.url(from = "https://Coach.TailABC123.ts.net:8443/app", ownerSuffix = own)?.port)
        assertNull(AppAddress.url(from = "example.com", ownerSuffix = own))
        assertNull("another tailnet's Funnel name", AppAddress.url(from = "recorder.tail0000.ts.net", ownerSuffix = own))
        assertNull(AppAddress.url(from = "tailabc123.ts.net.example.com", ownerSuffix = own))
        assertNull(AppAddress.url(from = "http://coach.tailabc123.ts.net", ownerSuffix = own))
        assertNull("not signed in to their own computers yet", AppAddress.url(from = "coach.tailabc123.ts.net", ownerSuffix = null))
        assertNull(AppAddress.url(from = "coach.tailabc123.ts.net", ownerSuffix = ""))
    }
}
