package com.snowyghost.ovenlight

import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The proxy's secret answers only the proxy's own CONNECT challenge. */
class ProxyAuthTests {
    private val auth = ProxyAuth("app-store", "secret")

    private fun challenge(request: Request) = Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(407).message("Proxy Authentication Required")
        .header("Proxy-Authenticate", "Basic realm=\"ovenlight-1\"").build()

    private val connect = Request.Builder().url("https://recipes.tail0000.ts.net/").method("CONNECT", null).build()

    @Test
    fun testOnlyTheProxysConnectGetsTheSecret() {
        val answered = auth.authenticate(null, challenge(connect))!!
        assertEquals("Basic YXBwLXN0b3JlOnNlY3JldA==", answered.header("Proxy-Authorization"))
        assertNull("once refused, never again", auth.authenticate(null, challenge(answered)))
        // OkHttp asks the proxy authenticator about an origin's own 407 inside the tunnel too.
        assertNull("an origin's 407 gets nothing", auth.authenticate(null, challenge(Request.Builder().url("https://recipes.tail0000.ts.net/file").build())))
    }
}
