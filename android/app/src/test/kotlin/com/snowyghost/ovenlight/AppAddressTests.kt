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
        assertEquals("https://coach.tailabc123.ts.net", AppAddress.ownURL(from = "coach.tailabc123.ts.net", ownerSuffix = own)?.toString())
        assertEquals(8443, AppAddress.ownURL(from = "https://Coach.TailABC123.ts.net:8443/app", ownerSuffix = own)?.port)
        assertNull(AppAddress.ownURL(from = "example.com", ownerSuffix = own))
        assertNull("another tailnet's Funnel name", AppAddress.ownURL(from = "recorder.tail0000.ts.net", ownerSuffix = own))
        assertNull(AppAddress.ownURL(from = "tailabc123.ts.net.example.com", ownerSuffix = own))
        assertNull(AppAddress.ownURL(from = "http://coach.tailabc123.ts.net", ownerSuffix = own))
        assertNull("not signed in to their own computers yet", AppAddress.ownURL(from = "coach.tailabc123.ts.net", ownerSuffix = null))
        assertNull(AppAddress.ownURL(from = "coach.tailabc123.ts.net", ownerSuffix = ""))
    }

    /** A name without a dot takes the owner's tailnet once it's known, both for Add's button and for what it adds. */
    @Test
    fun testAddAppCompletesABareName() {
        val own = "TailABC123.ts.net."
        assertEquals("https://notes.tailabc123.ts.net", AppAddress.ownURL(from = " notes ", ownerSuffix = own)?.toString())
        assertEquals("https://notes.tailabc123.ts.net:8443/app", AppAddress.ownURL(from = "notes:8443/app", ownerSuffix = own)?.toString())
        assertEquals("notes.tailabc123.ts.net", AppAddress.url(from = "notes", suffix = own)?.host)
        assertNull("no tailnet known yet", AppAddress.ownURL(from = "notes", ownerSuffix = null))
        assertNull(AppAddress.url(from = "notes"))
        assertNull(AppAddress.url(from = "", suffix = own))
        assertNull("still https only", AppAddress.ownURL(from = "http://notes", ownerSuffix = own))
        assertEquals("a full name stays", "notes.example.com", AppAddress.url(from = "notes.example.com", suffix = own)?.host)
    }
}
