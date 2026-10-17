package com.snowyghost.ovenlight

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.net.URI
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The join screen in each state it has, in light, dark and at twice the text size, with every
 * tappable thing labeled and nothing that names the network underneath.
 */
@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "w412dp-h915dp")
// Native graphics, which vector icons need to draw.
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class JoinViewsTests {
    @get:Rule
    val composeRule = createComposeRule()

    private val look = mutableStateOf(Look.Light)
    private var state by mutableStateOf(JoinState())
    private var closed = 0

    private val link = "https://ovenlight.app/join#v=1&control=https%3A%2F%2Fcontrolplane.tailscale.com&key=tskey-auth-k1&owner=Riley" +
        "&app=echo-board&invite=i1&host=echo-board.taildef456.ts.net&name=Echo+Board&to=Sam"
    private val invite = InviteLink.parse(URI(link))
    private val echo = WebApp(name = "Echo Board", startURL = URI("https://echo-board.taildef456.ts.net/"), membershipID = "m1", sharedBy = "Riley")

    /** Whether the screen is composed: the lock takes it away, and the router's request brings it back. */
    private var showing by mutableStateOf(true)

    private fun show(request: JoinRequest = JoinRequest(Result.success(invite))) {
        composeRule.setContent { Looked(look.value) { if (showing) JoinFlowScreen(request, state, onClose = { closed += 1 }) } }
    }

    private fun shownText(): List<String> {
        fun texts(node: SemanticsNode): List<String> =
            node.config.getOrNull(SemanticsProperties.Text).orEmpty().map { it.text } +
                node.config.getOrNull(SemanticsProperties.ContentDescription).orEmpty() + node.children.flatMap(::texts)
        return composeRule.onAllNodes(isRoot(), useUnmergedTree = true).fetchSemanticsNodes().flatMap(::texts)
    }

    /** [texts] in each look, then the label checks, and the copy rules on everything showing. */
    private fun inEveryLook(vararg texts: String) = composeRule.inEveryLook(look) {
        for (text in texts) composeRule.onNodeWithText(text).performScrollTo().assertIsDisplayed()
        composeRule.checkAccessibility()
        shownText().forEach(CopyRules::assertNeutral)
    }

    private fun tap(text: String) = composeRule.onNodeWithText(text).performScrollTo().performClick()

    @Test
    fun testTheEntryFindsAnInviteInAPastedMessageAndTheLockKeepsIt() {
        val clipboard = ApplicationProvider.getApplicationContext<Context>().getSystemService(ClipboardManager::class.java)
        state = JoinState(canScan = true)
        show(JoinRequest())
        inEveryLook(ConnectionCopy.invitePrompt, "Paste", "Or type it", "Invite link", "Scan invite code")
        composeRule.onNodeWithText(ConnectionCopy.joinWithInvite).assertIsDisplayed()
        composeRule.onNodeWithText("Continue").assertIsNotEnabled()
        clipboard.setPrimaryClip(ClipData.newPlainText(null, "Pizza night? Here's Echo Board: $link"))
        tap("Paste")
        inEveryLook(ConnectionCopy.joinTitle("Riley", "Echo Board"), "Join", "Not now")
        showing = false
        composeRule.waitForIdle()
        showing = true
        inEveryLook(ConnectionCopy.joinTitle("Riley", "Echo Board"))
    }

    @Test
    fun testTypedTextThatIsntAnInviteSaysSo() {
        show(JoinRequest())
        composeRule.onNodeWithText("Scan invite code").assertDoesNotExist()
        composeRule.onNode(hasSetTextAction()).performTextInput("https://ovenlight.app/support")
        tap("Continue")
        inEveryLook(InviteLink.ParseError.NotAnInvite.message, "Invite link")
    }

    @Test
    fun testALinkThatIsntUsableOffersAnother() {
        show(JoinRequest(URI("ovenlight://join?v=2&control=https%3A%2F%2Fcontrolplane.tailscale.com")))
        inEveryLook("Can't use this invite", InviteLink.ParseError.UnsupportedVersion.message, "Try another invite")
        tap("Try another invite")
        inEveryLook(ConnectionCopy.invitePrompt)
    }

    @Test
    fun testTheQuestionNamesWhoInvitedWhom() {
        val accepted = mutableListOf<InviteLink>()
        state = JoinState(accept = { accepted += it })
        show()
        inEveryLook(ConnectionCopy.joinTitle("Riley", "Echo Board"), "Join", "Not now")
        // TalkBack also hears the address, which reads as code to a friend.
        composeRule.onNodeWithContentDescription(
            "${ConnectionCopy.joinDetail("Riley", "Echo Board", "Sam")} ${ConnectionCopy.appAddress("echo-board.taildef456.ts.net")}",
        ).assertIsDisplayed()
        composeRule.onNodeWithText(ConnectionCopy.privateServer("controlplane.tailscale.com", "Riley")).assertDoesNotExist()
        tap("Join")
        assertEquals(listOf(invite), accepted)
        tap("Not now")
        assertEquals(1, closed)
    }

    @Test
    fun testAnotherConnectionServerIsNamed() {
        show(JoinRequest(Result.success(invite.copy(control = URI("https://hs.example.com")))))
        inEveryLook(ConnectionCopy.privateServer("hs.example.com", "Riley"), "Join")
    }

    @Test
    fun testJoiningThenOpeningTheApp() {
        val opened = mutableListOf<WebApp>()
        state = JoinState(phases = mapOf("i1" to JoinPhase.Claiming), open = { opened += it })
        show()
        inEveryLook(ConnectionCopy.joining("Riley", "Echo Board"), ConnectionCopy.joiningDetail)
        composeRule.onNodeWithText("Join").assertDoesNotExist()
        state = state.copy(phases = mapOf("i1" to JoinPhase.Joined(echo.id)), apps = listOf(echo))
        inEveryLook(ConnectionCopy.joined("Echo Board"), "It's with your apps now, shared by Riley.", "Open Echo Board")
        tap("Open Echo Board")
        assertEquals(listOf(echo), opened)
    }

    @Test
    fun testAnAppAlreadyHereCanBeJoinedAgain() {
        val accepted = mutableListOf<InviteLink>()
        val opened = mutableListOf<WebApp>()
        state = JoinState(preflight = { Preflight.AlreadyAdded(echo) }, accept = { accepted += it }, open = { opened += it })
        show()
        inEveryLook(ConnectionCopy.joined("Echo Board"), "Open Echo Board", "Join again")
        tap("Join again")
        tap("Open Echo Board")
        assertEquals(listOf(invite), accepted)
        assertEquals(listOf(echo), opened)
    }

    @Test
    fun testANetworkFailureOffersTryAgainOrStop() {
        val retried = mutableListOf<InviteLink>()
        val canceled = mutableListOf<InviteLink>()
        state = JoinState(
            phases = mapOf("i1" to JoinPhase.Failed(JoinError.Network)), retry = { retried += it },
            cancel = { invite, done ->
                canceled += invite
                done()
            },
        )
        show()
        inEveryLook(JoinError.Network.title("Riley", "Echo Board"), JoinError.Network.message("Riley", "Echo Board"), "Try again", "Stop joining")
        tap("Try again")
        assertEquals(listOf(invite), retried)
        tap("Stop joining")
        assertEquals(listOf(invite), canceled)
        assertEquals("closed once the invite is withdrawn", 1, closed)
    }

    @Test
    fun testAFinalFailureAndAnOwnAppAreDone() {
        state = JoinState(phases = mapOf("i1" to JoinPhase.Failed(JoinError.UsedOnAnotherDevice)))
        show()
        inEveryLook(JoinError.UsedOnAnotherDevice.title("Riley", "Echo Board"), "Done")
        composeRule.onNodeWithText("Try again").assertDoesNotExist()
        state = JoinState(preflight = { Preflight.OwnApp })
        inEveryLook(JoinError.OwnApp.title("Riley", "Echo Board"), JoinError.OwnApp.message("Riley", "Echo Board"), "Done")
        tap("Done")
        assertEquals(1, closed)
    }
}
