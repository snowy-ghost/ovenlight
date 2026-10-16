package com.snowyghost.ovenlight

import androidx.activity.OnBackPressedDispatcher
import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.net.URI
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Settings in each state it has, in light, dark and at twice the text size, with every tappable thing labeled. */
@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "w411dp-h891dp")
// Real drawing, which the icons need.
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SettingsViewTests {
    @get:Rule
    val composeRule = createComposeRule()

    private val look = mutableStateOf(Look.Light)
    private lateinit var back: OnBackPressedDispatcher

    private fun show(onClose: () -> Unit = {}, state: () -> SettingsState) {
        composeRule.setContent {
            back = LocalOnBackPressedDispatcherOwner.current!!.onBackPressedDispatcher
            Looked(look.value) { SettingsScreen(state(), onClose) }
        }
    }

    /** Runs [check] in each look, then the label checks on everything showing. */
    private fun inEveryLook(check: () -> Unit) = composeRule.inEveryLook(look) { check(); composeRule.checkAccessibility() }

    /** Scrolled to, so it's on screen at any text size. */
    private fun row(text: String, substring: Boolean = false): SemanticsNodeInteraction =
        composeRule.onNodeWithText(text, substring = substring).performScrollTo()

    private fun assertShows(vararg texts: String) = texts.forEach { row(it).assertIsDisplayed() }

    private fun assertLacks(vararg texts: String) = texts.forEach { composeRule.onNodeWithText(it).assertDoesNotExist() }

    private fun inDialog(text: String) = composeRule.onNode(hasText(text) and hasAnyAncestor(isDialog()))

    private fun shownText(): List<String> {
        fun texts(node: SemanticsNode): List<String> =
            node.config.getOrNull(SemanticsProperties.Text).orEmpty().map { it.text } +
                node.config.getOrNull(SemanticsProperties.ContentDescription).orEmpty() + node.children.flatMap(::texts)
        return composeRule.onAllNodes(isRoot(), useUnmergedTree = true).fetchSemanticsNodes().flatMap(::texts)
    }

    private val dimitri = TailnetStatus.User(loginName = "dimitri@example.com", displayName = "Dimitri")

    @Test
    fun testAFreshInstallOffersTheLockAndYourOwnComputers() {
        var used = false
        show { SettingsState(version = "1.0.0 (12)", useMyOwnComputers = { used = true }) }
        inEveryLook {
            assertShows(
                "Require screen lock", "After 1 minute", "How long Ovenlight can stay in the background before it locks.",
                "Use my own computers", "Open apps that run on your own computers. You'll sign in once to connect them.",
                "Support", "Contact us", "Privacy policy", "Acknowledgements", "Version", "1.0.0 (12)",
            )
            assertLacks("Shared with you", "Your computers", "Sign in to your computers", "Advanced", "Set screen lock")
            shownText().forEach(CopyRules::assertNeutral)
        }
        row("Use my own computers").performClick()
        assertTrue(used)
    }

    @Test
    fun testAGuestSeesEachOwnersApps() {
        show {
            SettingsState(
                shared = listOf(
                    SharedOwner("riley", "Riley", listOf("Recipe Box", "Garden Log")),
                    SharedOwner("sam", "Sam", emptyList()),
                ),
            )
        }
        inEveryLook {
            assertShows("Shared with you", "Riley", "Sam", "Joining…", "Leaving removes their apps and everything they stored on this phone.")
            row("Recipe Box", substring = true).assertIsDisplayed()
            row("Garden Log", substring = true).assertIsDisplayed()
            assertEquals(2, composeRule.onAllNodesWithText("Leave").fetchSemanticsNodes().size)
            // TalkBack hears whose apps each Leave is for.
            composeRule.onNodeWithContentDescription(ConnectionCopy.leaveApps("Riley")).assertExists()
            composeRule.onNodeWithContentDescription(ConnectionCopy.leaveApps("Sam")).assertExists()
            // Still not set up for their own computers, so nothing names the network.
            assertShows("Use my own computers")
            assertLacks("Advanced")
            shownText().forEach(CopyRules::assertNeutral)
        }
    }

    @Test
    fun testLeavingAsksFirst() {
        val left = mutableListOf<String>()
        show { SettingsState(shared = listOf(SharedOwner("riley", "Riley", listOf("Recipe Box")), SharedOwner("sam", "Sam", listOf("Notes"))), leave = { left += it }) }
        inEveryLook {
            composeRule.onAllNodesWithText("Leave")[0].performScrollTo().performClick()
            inDialog(ConnectionCopy.leaveTitle("Riley")).assertIsDisplayed()
            inDialog(ConnectionCopy.leaveDetail("Riley")).assertIsDisplayed()
            composeRule.checkAccessibility()
            inDialog("Cancel").performClick()
            assertTrue(left.isEmpty())
            composeRule.onNode(isDialog()).assertDoesNotExist()
        }
        composeRule.onAllNodesWithText("Leave")[1].performScrollTo().performClick()
        inDialog(ConnectionCopy.leaveTitle("Sam")).assertIsDisplayed()
        inDialog("Leave").performClick()
        assertEquals(listOf("sam"), left)
        composeRule.onNode(isDialog()).assertDoesNotExist()
    }

    @Test
    fun testBeforeTheSignInPageTheStateShows() {
        var stopped = false
        show { SettingsState(owner = OwnerSettings(NodeState.Starting, hasNoAccount = true, deviceName = "pixel-9"), stopUsingMyOwnComputers = { stopped = true }) }
        inEveryLook {
            assertShows("Your computers", "Stop using my own computers", "Advanced", "Status", "This phone", "pixel-9")
            assertEquals(2, composeRule.onAllNodesWithText(NodeState.Connecting.summary).fetchSemanticsNodes().size)
            assertLacks("Use my own computers", "Sign in to your computers", "Sign out", "Approve this phone", "Tailscale account", "Tailnet")
        }
        row("Stop using my own computers").performClick()
        assertTrue(stopped)
    }

    @Test
    fun testTheOwnerSigningIn() {
        val login = URI("https://login.example.com/a/1234")
        var signedIn: URI? = null
        show { SettingsState(owner = OwnerSettings(NodeState.NeedsLogin(login), hasNoAccount = true, deviceName = "pixel-9"), signIn = { signedIn = it }) }
        inEveryLook {
            assertShows("Sign in to your computers", "Stop using my own computers", "Advanced", NodeState.NeedsLogin(login).summary, "pixel-9")
            assertLacks("Your computers", "Use my own computers", "Sign out", "Approve this phone", "Tailscale account")
        }
        row("Sign in to your computers").performClick()
        assertEquals(login, signedIn)
    }

    @Test
    fun testAwaitingApproval() {
        var approving = false
        show {
            SettingsState(owner = OwnerSettings(NodeState.AwaitingApproval, account = dimitri, deviceName = "pixel-9"), approve = { approving = true })
        }
        inEveryLook {
            assertShows("Your computers", "Approve this phone", "Sign out", "Tailscale account", "dimitri@example.com")
            assertEquals(2, composeRule.onAllNodesWithText(NodeState.AwaitingApproval.summary).fetchSemanticsNodes().size)
            assertLacks("Stop using my own computers", "Sign in to your computers")
        }
        row("Approve this phone").performClick()
        assertTrue(approving)
    }

    @Test
    fun testConnectedShowsTheAccountAndAdvanced() {
        show {
            SettingsState(
                owner = OwnerSettings(NodeState.Ready, account = dimitri, tailnet = "dimitri@example.com", deviceName = "pixel-9"),
                version = "1.0.0 (12)",
            )
        }
        inEveryLook {
            assertShows(
                "Your computers", "Connected as Dimitri", "Sign out",
                "Advanced", "Status", NodeState.Ready.summary, "Tailscale account", "Tailnet", "This phone", "pixel-9",
            )
            assertEquals(2, composeRule.onAllNodesWithText("dimitri@example.com").fetchSemanticsNodes().size)
            assertLacks("Approve this phone", "Stop using my own computers", "Sign in to your computers", "Use my own computers")
        }
    }

    @Test
    fun testConnectedWithoutADisplayNameShowsTheLogin() {
        show { SettingsState(owner = OwnerSettings(NodeState.Ready, account = TailnetStatus.User("dimitri@example.com", ""), deviceName = "pixel-9")) }
        inEveryLook { assertShows("Connected as dimitri@example.com") }
    }

    @Test
    fun testSignOutAsksFirst() {
        var signedOut = 0
        show { SettingsState(owner = OwnerSettings(NodeState.Ready, account = dimitri, deviceName = "pixel-9"), signOut = { signedOut++ }) }
        inEveryLook {
            row("Sign out").performClick()
            inDialog("Sign out of your computers?").assertIsDisplayed()
            inDialog("Your apps stay in Ovenlight. Sign in again to open them.").assertIsDisplayed()
            composeRule.checkAccessibility()
            inDialog("Cancel").performClick()
            assertEquals(0, signedOut)
        }
        row("Sign out").performClick()
        inDialog("Sign out").performClick()
        assertEquals(1, signedOut)
        composeRule.onNode(isDialog()).assertDoesNotExist()
    }

    @Test
    fun testWithNoScreenLockSettingsSaysSo() {
        var opened = false
        show { SettingsState(noScreenLock = true, setScreenLock = { opened = true }) }
        inEveryLook {
            assertShows("Require screen lock", "Ovenlight can't lock until this phone has a screen lock.", "Set screen lock")
            assertLacks("How long Ovenlight can stay in the background before it locks.")
        }
        row("Set screen lock").performClick()
        assertTrue(opened)
    }

    @Test
    fun testTheDelayDialogChoosesEachValue() {
        var state by mutableStateOf(SettingsState())
        state = state.copy(setLockAfter = { state = state.copy(lockAfterSeconds = it) })
        show { state }
        val choices = listOf("Immediately" to 0, "After 5 minutes" to 300, "After 15 minutes" to 900, "After 1 minute" to 60)
        inEveryLook {
            for ((label, seconds) in choices) {
                row("Require screen lock").performClick()
                for ((each, _) in choices) inDialog(each).assertIsDisplayed()
                composeRule.checkAccessibility()
                inDialog(label).performClick()
                composeRule.onNode(isDialog()).assertDoesNotExist()
                assertEquals(seconds, state.lockAfterSeconds)
                assertShows(label)
            }
            // Cancel keeps the delay.
            row("Require screen lock").performClick()
            inDialog("Cancel").performClick()
            assertEquals(60, state.lockAfterSeconds)
        }
    }

    @Test
    fun testAcknowledgementsShowsTheNotices() {
        var closed = false
        show(onClose = { closed = true }) { SettingsState(notices = { "Go standard library\nBSD-3-Clause\n\n\nSource Serif 4\nSIL OFL 1.1" }) }
        inEveryLook {
            row("Acknowledgements").performClick()
            composeRule.onNodeWithText("Go standard library", substring = true).assertIsDisplayed()
            composeRule.onNodeWithText("Source Serif 4", substring = true).assertIsDisplayed()
            assertLacks("Require screen lock")
            composeRule.checkAccessibility()
            // The back arrow and Back both return to Settings, not out of it.
            if (look.value == Look.Dark) {
                composeRule.runOnUiThread { back.onBackPressed() }
            } else {
                composeRule.onNodeWithContentDescription("Back").performClick()
            }
            assertShows("Require screen lock")
            assertFalse(closed)
        }
        composeRule.onNodeWithContentDescription("Back").performClick()
        assertTrue(closed)
    }

    @Test
    fun testComingBackFromAcknowledgementsKeepsThePlace() {
        show { SettingsState(owner = OwnerSettings(NodeState.Ready, account = dimitri, tailnet = "dimitri@example.com", deviceName = "pixel-9")) }
        inEveryLook {
            row("Acknowledgements").performClick()
            composeRule.onNodeWithContentDescription("Back").performClick()
            // Not scrolled to again: still where it was.
            composeRule.onNodeWithText("Acknowledgements").assertIsDisplayed()
        }
    }

    @Test
    fun testAcknowledgementsSaysWhenTheLicensesAreMissing() {
        show { SettingsState(notices = { null }) }
        inEveryLook {
            row("Acknowledgements").performClick()
            composeRule.onNodeWithText("The licenses are missing from this build.").assertIsDisplayed()
            composeRule.checkAccessibility()
            composeRule.onNodeWithContentDescription("Back").performClick()
        }
    }

    @Test
    fun testContactUsWithNoEmailAppSaysWhereToWrite() {
        var privacy = false
        show { SettingsState(contactUs = { false }, privacyPolicy = { privacy = true }) }
        inEveryLook {
            row("Contact us").performClick()
            inDialog("No email app").assertIsDisplayed()
            inDialog("Write to team@snowyghost.com.").assertIsDisplayed()
            inDialog("Copy address").assertIsDisplayed()
            composeRule.checkAccessibility()
            inDialog("OK").performClick()
            composeRule.onNode(isDialog()).assertDoesNotExist()
        }
        row("Privacy policy").performClick()
        assertTrue(privacy)
    }
}
