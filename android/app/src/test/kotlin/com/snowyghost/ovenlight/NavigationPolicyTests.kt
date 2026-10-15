package com.snowyghost.ovenlight

import java.net.URI
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NavigationPolicyTests {
    private val host = "alex-mac.example.ts.net"

    private fun decide(text: String, mainFrame: Boolean = true, tapped: Boolean = false): NavigationPolicy.Decision =
        NavigationPolicy.decide(URI(text), appHost = host, isMainFrame = mainFrame, tapped = tapped)

    @Test
    fun testSameHostStaysInApp() {
        assertEquals(NavigationPolicy.Decision.Allow, decide("https://alex-mac.example.ts.net:8443/cards"))
    }

    @Test
    fun testOnlyTheAppsOwnPagesPopUpAPost() {
        val post = NavigationPolicy.Request(URI("https://alex-mac.example.ts.net/api/notes"), method = "POST", body = "x".toByteArray())
        val own = NavigationPolicy.popupRequest(post, fromScheme = "https", host = "Alex-Mac.example.ts.net.", appHost = host)
        assertEquals("POST", own.method)
        for ((scheme, other) in listOf<Pair<String?, String?>>("https" to "widget.example.com", "http" to host, null to null)) {
            val sent = NavigationPolicy.popupRequest(post, fromScheme = scheme, host = other, appHost = host)
            assertEquals("GET", sent.method)
            assertNull(sent.body)
            assertEquals(post.url, sent.url)
        }
    }

    @Test
    fun testOtherHostLeavesAppButIframesLoad() {
        val url = URI("https://accounts.google.com/o/oauth2")
        assertEquals(NavigationPolicy.Decision.OpenExternally(url), decide(url.toString()))
        assertEquals(NavigationPolicy.Decision.Allow, decide(url.toString(), mainFrame = false))
    }

    @Test
    fun testOtherAppsOpenOnlyFromATapInTheMainFrame() {
        val mail = URI("mailto:me@example.com")
        assertEquals(NavigationPolicy.Decision.OpenExternally(mail), decide(mail.toString(), tapped = true))
        assertEquals("a script can't open other apps", NavigationPolicy.Decision.Refuse, decide(mail.toString()))
        assertEquals("nor can a frame", NavigationPolicy.Decision.Refuse, decide(mail.toString(), mainFrame = false, tapped = true))
        assertEquals(NavigationPolicy.Decision.Allow, decide("about:blank"))
    }

    @Test
    fun testThePageShowsOnlyTheAppsOwnBlobs() {
        assertEquals(NavigationPolicy.Decision.Allow, decide("blob:https://Alex-Mac.example.ts.net:8443/6f1c"))
        assertEquals("a frame of another site made it", NavigationPolicy.Decision.Refuse, decide("blob:https://evil.example.com/6f1c"))
        assertEquals(NavigationPolicy.Decision.Refuse, decide("blob:null/6f1c"))
        assertEquals("a frame shows its own", NavigationPolicy.Decision.Allow, decide("blob:https://evil.example.com/6f1c", mainFrame = false))
    }

    /** The pure half. iOS also checks that the bundle declares only this scheme; Android's manifest filters need an instrumented test. */
    @Test
    fun testPagesNeverOpenOvenlightsOwnLinks() {
        for (link in listOf("ovenlight://join?v=1&key=k", "Ovenlight://join?v=1", "ovenlight://open?app=Coach")) {
            assertEquals(link, NavigationPolicy.Decision.Refuse, decide(link, tapped = true))
        }
    }

    /** Android asks before FaceTime too: it isn't a system app there, so nothing else would confirm it. */
    @Test
    fun testOvenlightAsksBeforeOpeningAnyAppIOSDoesnt() {
        for (link in listOf("sms:+15550100", "shortcuts://run-shortcut?name=x", "mailto:me@example.com", "FaceTime:me@example.com")) {
            assertTrue(link, NavigationPolicy.asksBeforeOpening(URI(link)))
        }
        assertFalse("the dialer asks before a call", NavigationPolicy.asksBeforeOpening(URI("tel:+15550100")))
        val long = URI("shortcuts://run-shortcut?name=" + "x".repeat(100))
        assertEquals("quoted and cut short", 82, NavigationPolicy.target(long).graphemeCount())
    }

    /** Android only: links that would start another app's activity or read the phone's files. */
    @Test
    fun testRefusesIntentsAndLocalFiles() {
        for (link in listOf(
            "intent://scan/#Intent;scheme=zxing;package=com.google.zxing.client.android;end",
            "INTENT://settings#Intent;action=android.settings.SETTINGS;end",
            "android-app://com.example.app/https/example.com/",
            "file:///sdcard/Download/notes.txt",
            "content://com.android.contacts/contacts",
        )) {
            assertEquals(link, NavigationPolicy.Decision.Refuse, decide(link, tapped = true))
            assertEquals(link, NavigationPolicy.Decision.Refuse, decide(link, mainFrame = false, tapped = true))
        }
    }
}
