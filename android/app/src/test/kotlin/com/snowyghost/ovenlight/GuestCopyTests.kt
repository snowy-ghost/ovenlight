package com.snowyghost.ovenlight

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
}
