package com.snowyghost.ovenlight

import java.net.URI
import org.junit.Assert.assertEquals
import org.junit.Test

class GuestCopyTests {
    @Test
    fun testEverythingAGuestCanSeeIsNeutral() {
        val copy = mutableListOf(
            ConnectionCopy.sharedBy("Riley"), ConnectionCopy.joinTitle(owner = "Riley", app = "Echo"),
            ConnectionCopy.joinDetail(owner = "Riley", app = "Echo", to = "Sam"), ConnectionCopy.joinDetail(owner = "Riley", app = "Echo", to = ""),
            ConnectionCopy.joining(owner = "Riley", app = "Echo"), ConnectionCopy.joiningDetail, ConnectionCopy.joined("Echo"),
            ConnectionCopy.noLongerSharedTitle, ConnectionCopy.noLongerShared(apps = listOf("Echo", "Notes"), owner = "Riley"),
            ConnectionCopy.joinWithInvite, ConnectionCopy.invitePrompt, ConnectionCopy.feedbackSent,
            ConnectionCopy.appAddress("echo.taildef456.ts.net"), ConnectionCopy.privateServer("hs.example.com", owner = "Riley"),
            ConnectionCopy.feedbackSentDetail(to = "Riley"), ConnectionCopy.feedbackSentDetail(to = null), ConnectionCopy.feedbackFailed,
            ConnectionCopy.feedbackTooMany, ConnectionCopy.feedbackNetwork, ConnectionCopy.feedbackOffline,
            ConnectionCopy.reportProblem, ConnectionCopy.leaveApps("Riley"), ConnectionCopy.leaveTitle("Riley"),
            ConnectionCopy.leaveDetail("Riley"), ConnectionCopy.removeSharedDetail("Riley"),
            AppAddress.signInFirst, AppAddress.notYourComputer,
            ConnectionCopy.connecting, ConnectionCopy.stopped, ConnectionCopy.couldNotStart,
            ConnectionCopy.connecting(to = "Echo"), ConnectionCopy.cantReach("Echo"),
            ConnectionCopy.cantReachDetail(owner = null), ConnectionCopy.cantReachDetail(owner = "Riley"),
            ConnectionCopy.signIn(toOpen = "Echo"), ConnectionCopy.pageFailed, ConnectionCopy.offline, ConnectionCopy.offlineDetail,
            ConnectionCopy.notConnected, ConnectionCopy.notResponding("Echo", owner = "Riley"), ConnectionCopy.notResponding("Echo", owner = null),
            NodeState.Starting.summary, NodeMachine.next(NodeState.Ready, NodeEvent.Backend("Stopped", null)).summary,
            LauncherWelcome.title, LauncherWelcome.message,
        )
        val report = MailDraft.problemReport(
            appName = "Echo", host = "echo.example.com", sharedBy = "Riley", connection = NodeState.Ready.summary,
            version = "1.0 (3)", android = "16", webView = "145.0.7632.218", model = "Google Pixel 9",
        )
        copy += listOf(report.subject, report.body)
        copy += JoinError.entries.flatMap { listOf(it.title(owner = "Riley", app = "Echo"), it.message(owner = "Riley", app = "Echo")) }
        for (text in copy) CopyRules.assertNeutral(text)
    }

    /** Android only: the owner's own apps say what to do about them; an app someone shared keeps a guest's words. */
    @Test
    fun testTheOwnersOwnAppsSayWhatToDo() {
        val own = WebApp(name = "Notes", startURL = URI("https://notes.tailabc123.ts.net/"))
        val shared = own.copy(membershipID = "m1", sharedBy = "Riley")
        assertEquals("Your computer may be asleep or offline. Wake it, or run ovenlight doctor on it, then try again.", ConnectionCopy.cantReachDetail(own))
        assertEquals("Notes isn’t on the account you’re signed in to. Sign in with the account it’s on, or remove it.", ConnectionCopy.cantReachDetail(own, refused = true))
        assertEquals("Notes isn’t running on your computer. Run ovenlight doctor there, then try again.", ConnectionCopy.notResponding(own))
        assertEquals(ConnectionCopy.cantReachDetail(owner = "Riley"), ConnectionCopy.cantReachDetail(shared, refused = true))
        assertEquals("Notes isn’t responding on Riley’s computer right now. Try again in a moment, or let Riley know.", ConnectionCopy.notResponding(shared))
    }
}
