package com.snowyghost.ovenlight

import android.net.IpPrefix
import android.net.LinkProperties
import java.net.InetAddress
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** The network's NAT64 prefix reaches the Go core in the form its parser reads. */
@RunWith(RobolectricTestRunner::class)
class Nat64PrefixTests {
    @Test
    fun testTheNetworksPrefixIsAddressSlashLength() {
        val properties = LinkProperties()
        assertEquals("", TailnetCore.nat64Prefix(properties))
        // The setter is a system API, which only the network stack calls.
        LinkProperties::class.java.getMethod("setNat64Prefix", IpPrefix::class.java)
            .invoke(properties, IpPrefix(InetAddress.getByName("2001:67c:2b0:db32::"), 96))
        // The JVM writes the address in full and Android compresses it; Go reads either.
        val (address, length) = TailnetCore.nat64Prefix(properties).split("/")
        assertEquals(InetAddress.getByName("2001:67c:2b0:db32::"), InetAddress.getByName(address))
        assertEquals("96", length)
    }
}
