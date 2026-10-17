package com.snowyghost.ovenlight

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProxyChallengeTest {
    private val realm = "ovenlight-0123456789abcdef0123456789abcdef"
    private val secret = "s3cret"

    private fun answer(host: String? = "127.0.0.1", realm: String? = this.realm, viewProfile: String? = "store-a", app: String = "store-a") =
        ProxyChallenge.answer(host, realm, viewProfile, app, this.realm, secret)

    @Test
    fun testAnswersTheProxysOwnChallengeWithTheAppsKey() {
        assertEquals(ProxyChallenge.Credential("app-store-a", secret), answer())
    }

    @Test
    fun testCancelsAnyOtherChallenge() {
        assertNull("a server's own 401", answer(host = "recipes.tailabc123.ts.net"))
        assertNull("another local server", answer(host = "localhost"))
        assertNull("another realm on the proxy's host", answer(realm = "ovenlight-ffffffffffffffffffffffffffffffff"))
        assertNull("no realm", answer(realm = null))
        assertNull("no host", answer(host = null))
    }

    @Test
    fun testCancelsAViewOutsideItsAppsProfile() {
        assertNull("the default profile", answer(viewProfile = "Default"))
        assertNull("another app's profile", answer(viewProfile = "store-b"))
        assertNull("no app", answer(viewProfile = "", app = ""))
    }

    @Test
    fun testTheWebViewFloorIsM144() {
        assertTrue(WebViewSetup.meetsRequirement("144.0.7559.59"))
        assertTrue(WebViewSetup.meetsRequirement("145.0.7632.218"))
        // M140 to M143 pass the safe area only to a WebView they take for full screen.
        assertFalse(WebViewSetup.meetsRequirement("143.0.7499.192"))
        assertFalse(WebViewSetup.meetsRequirement("91.0.4472.114"))
        assertFalse(WebViewSetup.meetsRequirement(null))
        assertFalse(WebViewSetup.meetsRequirement("garbage"))
    }
}
