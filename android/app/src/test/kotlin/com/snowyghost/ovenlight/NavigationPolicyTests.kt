package com.snowyghost.ovenlight

import java.net.URI
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
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

    /** Addresses Chromium writes that java.net.URI refuses as written: read through OkHttp, they stay in the app. */
    @Test
    fun testChromiumsAddressesStayInTheApp() {
        for (suffix in listOf("/?q={%22a%22:1}", "/?a|b", "/?x^y", "/a[0]", "/?q=\"x\"", "/p#a#b")) {
            val text = "https://alex-mac.example.ts.net$suffix"
            val url = NavigationPolicy.pageURI(text)
            assertNotNull(text, url)
            assertEquals(text, NavigationPolicy.Decision.Allow, NavigationPolicy.decide(url!!, host, isMainFrame = true, tapped = false))
        }
        assertEquals(
            "another site's stays another site's",
            NavigationPolicy.Decision.OpenExternally(NavigationPolicy.pageURI("https://forms.example.com/?a|b")!!),
            NavigationPolicy.decide(NavigationPolicy.pageURI("https://forms.example.com/?a|b")!!, host, isMainFrame = true, tapped = false),
        )
        assertEquals("forms.example.com", NavigationPolicy.pageURI("https://forms.example.com/?a|b")?.host)
        assertEquals(URI("blob:https://alex-mac.example.ts.net/6f1c"), NavigationPolicy.pageURI("blob:https://alex-mac.example.ts.net/6f1c"))
        assertNull("anything else unreadable is refused", NavigationPolicy.pageURI("mailto:a b|c"))
        for (text in listOf("https://u%zz@alex-mac.example.ts.net/", "https://u%@alex-mac.example.ts.net/")) {
            assertNull("refused, not thrown: $text", NavigationPolicy.pageURI(text))
        }
        for (text in listOf("https://alex-mac{.example.ts.net/", "https://a{b.example.com/", "https://a%7Bb.example.com/")) {
            assertNull("HttpUrl would name another host: $text", NavigationPolicy.pageURI(text))
        }
        assertEquals("[fd7a::1]", NavigationPolicy.pageURI("https://[fd7a::1]/a|b")?.host)
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
    fun testRefusesAndroidAppLinksAndLocalFiles() {
        for (link in listOf(
            "android-app://com.example.app/https/example.com/",
            "ANDROID-APP://com.example.app/https/example.com/",
            "file:///sdcard/Download/notes.txt",
            "content://com.android.contacts/contacts",
        )) {
            assertEquals(link, NavigationPolicy.Decision.Refuse, decide(link, tapped = true))
            assertEquals(link, NavigationPolicy.Decision.Refuse, decide(link, mainFrame = false, tapped = true))
        }
    }

    /** An intent: link is another app: only from a tap in the main frame, and Ovenlight asks first. */
    @Test
    fun testAnIntentLinkIsAnotherApp() {
        for (link in listOf(
            "intent://scan/#Intent;scheme=zxing;package=com.google.zxing.client.android;end",
            "INTENT://settings#Intent;action=android.settings.SETTINGS;end",
        )) {
            assertEquals(link, NavigationPolicy.Decision.OpenExternally(URI(link)), decide(link, tapped = true))
            assertEquals("a script can't open it: $link", NavigationPolicy.Decision.Refuse, decide(link))
            assertEquals("nor can a frame: $link", NavigationPolicy.Decision.Refuse, decide(link, mainFrame = false, tapped = true))
            assertTrue(link, NavigationPolicy.asksBeforeOpening(URI(link)))
        }
    }

    /**
     * The join page in every form Ovenlight's App Link takes. It opens outside like any web
     * page, as iOS opens it in Safari; only the hand-offs Ovenlight's App Link could catch
     * refuse it (mayCarry, and CustomTabs without a browser to name).
     */
    @Test
    fun testTheJoinPageOpensOutsideLikeAnyWebPage() {
        for (link in listOf(
            "https://ovenlight.app/join#v=1&key=k", "https://ovenlight.app/join/", "https://ovenlight.app/join.html",
            "HTTPS://OVENLIGHT.APP/JOIN", "https://Ovenlight.App./Join.HTML", "https://ovenlight.app:8443/join", "http://ovenlight.app/join",
        )) {
            assertTrue(link, NavigationPolicy.isOvenlightLink(URI(link)))
            assertEquals(link, NavigationPolicy.Decision.OpenExternally(URI(link)), decide(link, tapped = true))
            assertFalse(link, NavigationPolicy.mayCarry(URI(link)))
        }
        for (link in listOf("https://ovenlight.app/", "https://ovenlight.app/support", "https://ovenlight.app/joined", "https://www.ovenlight.app/join")) {
            assertFalse(link, NavigationPolicy.isOvenlightLink(URI(link)))
            assertEquals(link, NavigationPolicy.Decision.OpenExternally(URI(link)), decide(link))
        }
        assertTrue(NavigationPolicy.isOvenlightLink(URI("OVENLIGHT://open?app=Coach")))
        assertEquals("a frame still shows the web page", NavigationPolicy.Decision.Allow, decide("https://ovenlight.app/join", mainFrame = false))
    }

    /** Every form of the join page opens as the one the site serves without a redirect. */
    @Test
    fun testTheJoinPageOpensWithoutARedirect() {
        for (link in listOf("https://ovenlight.app/join.html#v=1&key=k", "http://ovenlight.app/join#v=1&key=k", "HTTPS://OVENLIGHT.APP:8443/Join/#v=1&key=k")) {
            assertEquals(link, URI("https://ovenlight.app/join#v=1&key=k"), NavigationPolicy.joinPage(URI(link)))
        }
        assertEquals(URI("https://ovenlight.app/join?ref=a%20b"), NavigationPolicy.joinPage(URI("https://ovenlight.app/join/?ref=a%20b")))
        assertEquals(URI("https://ovenlight.app/join"), NavigationPolicy.joinPage(URI("https://ovenlight.app/join.html")))
    }

    /** What an intent: link may hand on: nothing of the phone's, nothing of Ovenlight's. */
    @Test
    fun testWhatAnIntentMayCarry() {
        assertTrue(NavigationPolicy.mayCarry(null))
        for (data in listOf("zxing://scan/", "https://example.com/", "geo:0,0?q=Lisbon", "market://details?id=com.example")) {
            assertTrue(data, NavigationPolicy.mayCarry(URI(data)))
        }
        for (data in listOf(
            "content://com.android.contacts/contacts", "FILE:///sdcard/notes.txt", "ovenlight://join?v=1", "https://ovenlight.app/Join/",
            "intent://x#Intent;end", "android-app://com.example.app/", "//scan/",
        )) {
            assertFalse(data, NavigationPolicy.mayCarry(URI(data)))
        }
    }

    @Test
    fun testExtrasThatNameAnAddress() {
        for (value in listOf("https://evil.example/", " content://media/1", "file:///sdcard/a", "//evil.example/", "intent:#Intent;end", "Note: hi")) {
            assertTrue(value, NavigationPolicy.carriesURI(value))
        }
        for (value in listOf("hello", "42", "/sdcard/a", "a b:c", "")) {
            assertFalse(value, NavigationPolicy.carriesURI(value))
        }
    }

    /** A POST commits without the callback seeing it, and is then judged as any main-frame navigation the page started itself. */
    @Test
    fun testAPageTheCallbackNeverSawIsJudgedAsUntapped() {
        assertEquals(NavigationPolicy.Decision.OpenExternally(URI("https://forms.example.com/submit")), decide("https://forms.example.com/submit"))
        assertEquals(NavigationPolicy.Decision.Allow, decide("https://alex-mac.example.ts.net/save"))
        assertEquals(NavigationPolicy.Decision.Refuse, decide("blob:https://evil.example.com/6f1c"))
    }
}
