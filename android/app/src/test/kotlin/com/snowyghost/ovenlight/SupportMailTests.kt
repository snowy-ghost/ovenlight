package com.snowyghost.ovenlight

import java.net.URI
import kotlin.test.assertNotNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Android's report ends with its Android, WebView and model lines where iOS's has `iOS: 26.5`. */
class SupportMailTests {
    private fun report(
        appName: String, host: String, sharedBy: String?, connection: String?, opening: String? = null, version: String,
    ) = MailDraft.problemReport(appName, host, sharedBy, connection, opening, version, android = "16", webView = "141.0.7390.122", model = "Google Pixel 9")

    @Test
    fun testProblemReportForASharedApp() {
        val draft = report(appName = "Echo Board", host = "echo-board.taildef456.ts.net", sharedBy = "Riley", connection = "Connected", version = "0.2.0 (7)")
        assertEquals("team@snowyghost.com", draft.to)
        assertEquals("Ovenlight report: Echo Board", draft.subject)
        assertEquals(
            """
            What happened:



            App: Echo Board
            Address: echo-board.taildef456.ts.net
            Shared by: Riley
            Connection: Connected
            Ovenlight: 0.2.0 (7)
            Android: 16
            WebView: 141.0.7390.122
            Model: Google Pixel 9
            """.trimIndent(),
            draft.body,
        )
    }

    @Test
    fun testProblemReportWithoutAConnection() {
        val draft = report(appName = "Coach", host = "coach.example.com", sharedBy = null, connection = null, version = "1.0 (1)")
        assertFalse(draft.body.contains("Shared by"))
        assertFalse("no node, no connection to report", draft.body.contains("Connection"))
        assertTrue(draft.body.startsWith("What happened:"))
        assertTrue(draft.body.endsWith("App: Coach\nAddress: coach.example.com\nOvenlight: 1.0 (1)\nAndroid: 16\nWebView: 141.0.7390.122\nModel: Google Pixel 9"))
    }

    @Test
    fun testProblemReportSaysWhereTheConnectionAndOpeningAre() {
        val login = NodeState.NeedsLogin(URI("https://login.tailscale.com/a/secret"))
        assertEquals("never the sign-in link", "Not signed in (needsLogin)", MailDraft.connection(login, listedOffline = false))
        val connection = MailDraft.connection(NodeState.Retrying(attempt = 2), listedOffline = true)
        assertEquals("Connecting (retrying(attempt: 2), computer listed offline)", connection)
        val draft = report(appName = "Echo", host = "echo.taildef456.ts.net", sharedBy = "Riley", connection = connection,
            opening = AppLoadGate.Unreachable.logDescription, version = "1.0.2 (1)")
        assertTrue(draft.body.contains("\nConnection: Connecting (retrying(attempt: 2), computer listed offline)\nOpening: unreachable\nOvenlight: 1.0.2 (1)"))
        CopyRules.assertNeutral(draft.body)
    }

    @Test
    fun testMailtoFallbackCarriesSubjectAndBody() {
        val draft = report(appName = "Echo & Notes+", host = "echo.example.com", sharedBy = "Riley", connection = null, version = "1.0 (1)")
        val url = assertNotNull(draft.mailtoURL)
        assertEquals("mailto", url.scheme)
        val (path, query) = url.rawSchemeSpecificPart.split('?', limit = 2)
        assertEquals("team@snowyghost.com", path)
        val items = query.split('&').associate { item -> item.split('=', limit = 2).let { it[0] to removingPercentEncoding(it[1]) } }
        assertEquals("Ovenlight report: Echo & Notes+", items["subject"])
        assertEquals(draft.body, items["body"])
    }
}
