package com.snowyghost.ovenlight

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.net.URI
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Add app before the owner is connected and after, in light, dark and at twice the text size. */
@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "w412dp-h915dp")
// Native graphics, which vector icons need to draw.
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AddAppDialogTests {
    @get:Rule
    val composeRule = createComposeRule()

    private val look = mutableStateOf(Look.Light)
    private var ownerSuffix by mutableStateOf<String?>(null)
    private var ownerEnabled by mutableStateOf(false)
    private var ownerState by mutableStateOf<NodeState>(NodeState.Idle)
    private val added = mutableListOf<URI>()
    private val presented = mutableListOf<RootSheet>()
    private var usedOwnComputers = 0
    private var closed = 0

    private fun show() = composeRule.setContent {
        Looked(look.value) {
            AddAppDialog(
                ownerSuffix, ownerEnabled, ownerState, add = { added += it }, useOwnComputers = { usedOwnComputers += 1 },
                present = { presented += it }, onClose = { closed += 1 },
            )
        }
    }

    private fun inEveryLook(vararg texts: String) = composeRule.inEveryLook(look) {
        for (text in texts) composeRule.onNodeWithText(text).assertIsDisplayed()
        composeRule.checkAccessibility()
    }

    private val field get() = composeRule.onNode(hasSetTextAction())

    @Test
    fun testBeforeSignInItSaysToConnectFirst() {
        show()
        inEveryLook("Add app", AppAddress.signInFirst, "Use my own computers", "Cancel")
        composeRule.onNodeWithText("Use my own computers").performClick()
        assertEquals(1, usedOwnComputers)
        assertEquals(1, closed)

        val signIn = URI("https://login.tailscale.com/a/abc")
        ownerEnabled = true
        ownerState = NodeState.NeedsLogin(signIn)
        inEveryLook(AppAddress.signInFirst, "Sign in to your computers")
        composeRule.onNodeWithText("Sign in to your computers").performClick()
        ownerState = NodeState.AwaitingApproval
        inEveryLook(AppAddress.signInFirst, "Approve this phone")
        composeRule.onNodeWithText("Approve this phone").performClick()
        assertEquals(listOf(RootSheet.Login(signIn), RootSheet.Approve), presented)

        // Connecting: nothing to do but wait.
        ownerState = NodeState.Connecting
        inEveryLook(AppAddress.signInFirst, "Cancel")
        composeRule.onNodeWithText("Approve this phone").assertDoesNotExist()
    }

    @Test
    fun testOnlyAnHttpsAddressOnTheOwnersComputersIsAdded() {
        ownerEnabled = true
        ownerState = NodeState.Ready
        ownerSuffix = "tailabc123.ts.net"
        show()
        inEveryLook("Add app", "Address", "my-app.tailabc123.ts.net", "Ovenlight fetches the app’s name and icon from its web manifest. Each app gets its own private storage.")
        composeRule.onNodeWithText("Add").assertIsNotEnabled()
        field.performTextInput("http://coach.tailabc123.ts.net")
        inEveryLook("Ovenlight only opens secure addresses that start with https.")
        composeRule.onNodeWithText("Add").assertIsNotEnabled()
        field.performTextReplacement("recorder.tail0000.ts.net")
        composeRule.onNodeWithText("Add").assertIsEnabled().performClick()
        inEveryLook(AppAddress.notYourComputer)
        assertEquals(emptyList<URI>(), added)
        field.performTextReplacement("coach.tailabc123.ts.net")
        composeRule.onNodeWithText(AppAddress.notYourComputer).assertDoesNotExist()
        composeRule.onNodeWithText("Add").performClick()
        assertEquals(listOf(URI("https://coach.tailabc123.ts.net")), added)
        assertEquals(1, closed)
    }
}
