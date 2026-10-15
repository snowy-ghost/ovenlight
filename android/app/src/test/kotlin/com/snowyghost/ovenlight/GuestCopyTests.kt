package com.snowyghost.ovenlight

import org.junit.Test

class GuestCopyTests {
    // MailDraft's problem report and JoinError's titles and messages join this list with
    // Support.kt and Guests.kt.
    @Test
    fun testEverythingAGuestCanSeeIsNeutral() {
        val copy = listOf(
            ConnectionCopy.sharedBy("Riley"), ConnectionCopy.joinTitle(owner = "Riley", app = "Echo"),
            ConnectionCopy.joinDetail(owner = "Riley", app = "Echo", to = "Sam"), ConnectionCopy.joinDetail(owner = "Riley", app = "Echo", to = ""),
            ConnectionCopy.joining(owner = "Riley", app = "Echo"), ConnectionCopy.joiningDetail, ConnectionCopy.joined("Echo"),
            ConnectionCopy.noLongerSharedTitle, ConnectionCopy.noLongerShared(apps = listOf("Echo", "Notes"), owner = "Riley"),
            ConnectionCopy.joinWithInvite, ConnectionCopy.invitePrompt, ConnectionCopy.feedbackSent,
            ConnectionCopy.appAddress("echo.taildef456.ts.net"), ConnectionCopy.privateServer("hs.example.com", owner = "Riley"),
            ConnectionCopy.feedbackSentDetail(to = "Riley"), ConnectionCopy.feedbackSentDetail(to = null), ConnectionCopy.feedbackFailed,
            ConnectionCopy.feedbackTooMany, ConnectionCopy.feedbackNetwork,
            ConnectionCopy.reportProblem, ConnectionCopy.leaveApps("Riley"), ConnectionCopy.leaveTitle("Riley"),
            ConnectionCopy.leaveDetail("Riley"), ConnectionCopy.removeSharedDetail("Riley"),
            AppAddress.signInFirst, AppAddress.notYourComputer,
        )
        for (text in copy) CopyRules.assertNeutral(text)
    }
}
