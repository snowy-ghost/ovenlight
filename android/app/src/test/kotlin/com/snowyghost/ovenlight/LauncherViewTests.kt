package com.snowyghost.ovenlight

import android.app.Application
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusManager
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performFirstLinkClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.requestFocus
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.net.URI
import java.time.Instant
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The launcher from hand-made states: every card, banner, grid size and menu. Labels are checked
 * here (see checkAccessibility); contrast and touch targets on a device, in ScreenAccessibilityTests.
 */
// Native graphics, which vector icons need to draw.
@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "w412dp-h915dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class LauncherViewTests {
    @get:Rule
    val composeRule = createComposeRule()

    private val recipes = WebApp(name = "Recipe Box", startURL = URI("https://recipe-box.tail1.ts.net/"), discovered = true)
    private val guide = WebApp(
        name = "House Guide", startURL = URI("https://house-guide.tail2.ts.net/"), membershipID = "m1", sharedBy = "Riley", isNew = true,
    )
    private val garden = WebApp(name = "Garden Log", startURL = URI("https://garden-log.tail1.ts.net/"), discovered = true)
    private val apps = listOf(recipes, guide, garden)
    private val sharedTile = "House Guide, ${ConnectionCopy.sharedBy("Riley")}"

    private fun invite(id: String, name: String) = InviteLink(
        control = URI("https://controlplane.tailscale.com"), key = "tskey-$id", owner = "Riley", app = name.lowercase().replace(' ', '-'),
        invite = id, host = "${name.lowercase().replace(' ', '-')}.tail2.ts.net", name = name, to = "Sam",
    )

    private fun membership(vararg invites: InviteLink) = Membership(invites.firstOrNull() ?: invite("i0", "House Guide"), id = "m1")
        .copy(pendingClaims = invites.map { PendingClaim(it, Instant.ofEpochSecond(1_000)) })

    private fun show(state: LauncherState, fontScale: Float = 1f) {
        composeRule.setContent {
            OvenlightTheme {
                val density = LocalDensity.current
                CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale)) { LauncherView(state) }
            }
        }
        composeRule.checkAccessibility()
    }

    private fun columns(): Int? = composeRule.onNode(SemanticsMatcher.keyIsDefined(SemanticsProperties.CollectionInfo))
        .fetchSemanticsNode().config.getOrNull(SemanticsProperties.CollectionInfo)?.columnCount

    private fun tile(description: String) = composeRule.onNodeWithContentDescription(description)

    private fun stateOf(description: String) = tile(description).fetchSemanticsNode().config.getOrNull(SemanticsProperties.StateDescription)

    /** Only the owner's two cards name the network; a guest can see every other launcher label. */
    @Test
    fun testGuestsSeeNoNetworkName() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val ownerOnly = setOf("launcher_connect_body", "launcher_approve_body")
        val labels = R.string::class.java.fields.filter { it.name.startsWith("launcher_") && it.name !in ownerOnly }
        assertTrue(labels.size > 30)
        for (field in labels) CopyRules.assertNeutral(context.getString(field.getInt(null), "Riley"))
    }

    @Test
    fun testWelcome() {
        val presented = mutableListOf<RootSheet>()
        var enabled = false
        show(LauncherState(present = { presented += it }, enableOwner = { enabled = true }))
        composeRule.onNodeWithText(LauncherWelcome.title).assertIsDisplayed()
        composeRule.onNodeWithText(LauncherWelcome.message).assertIsDisplayed()
        composeRule.onNodeWithText(ConnectionCopy.joinWithInvite).performClick()
        assertTrue(presented.single() is RootSheet.Join)
        composeRule.onNodeWithText("Use my own computers").performClick()
        assertTrue(enabled)
    }

    @Test
    fun testTopRow() {
        val presented = mutableListOf<RootSheet>()
        var locked = false
        show(LauncherState(apps = listOf(recipes), lock = { locked = true }, present = { presented += it }))
        composeRule.onNodeWithContentDescription("Lock Ovenlight").performClick()
        assertTrue(locked)
        composeRule.onNodeWithContentDescription("More").performClick()
        composeRule.onNodeWithText("People & sharing").assertDoesNotExist()
        composeRule.onNodeWithText("Add app").performClick()
        composeRule.onNodeWithContentDescription("More").performClick()
        composeRule.onNodeWithText(ConnectionCopy.joinWithInvite).performClick()
        composeRule.onNodeWithContentDescription("More").performClick()
        composeRule.onNodeWithText("Settings").performClick()
        assertEquals(RootSheet.AddApp, presented[0])
        assertNull("Join with invite opens the join sheet without an invite", (presented[1] as RootSheet.Join).request.invite)
        assertEquals(RootSheet.Settings, presented[2])
    }

    @Test
    fun testACoveredLauncherTakesNoKeyboardFocus() {
        var covered by mutableStateOf(false)
        lateinit var focus: FocusManager
        composeRule.setContent {
            focus = LocalFocusManager.current
            OvenlightTheme { LauncherView(LauncherState(apps = apps, covered = covered)) }
        }
        composeRule.runOnIdle { repeat(3) { focus.moveFocus(FocusDirection.Next) } }
        assertTrue("uncovered, a keyboard reaches it", composeRule.onAllNodes(isFocused()).fetchSemanticsNodes().isNotEmpty())
        // Covered while something in it has focus, as after More, then Settings, from a keyboard.
        composeRule.runOnIdle { covered = true }
        composeRule.runOnIdle { repeat(10) { focus.moveFocus(FocusDirection.Next) } }
        assertTrue("under a screen, nothing in it takes focus", composeRule.onAllNodes(isFocused()).fetchSemanticsNodes().isEmpty())
    }

    @Test
    fun testPeopleAndSharingForAConnectedOwner() {
        val presented = mutableListOf<RootSheet>()
        show(LauncherState(apps = listOf(recipes), ownerEnabled = true, ownerState = NodeState.Ready, present = { presented += it }))
        composeRule.onNodeWithContentDescription("More").performClick()
        composeRule.onNodeWithText("People & sharing").performClick()
        assertEquals(listOf<RootSheet>(RootSheet.Sharing), presented)
    }

    @Test
    fun testOwnersEmptyGrid() {
        val presented = mutableListOf<RootSheet>()
        val links = mutableListOf<String>()
        show(LauncherState(ownerEnabled = true, ownerState = NodeState.Ready, present = { presented += it }, openLink = { links += it }))
        composeRule.onNodeWithText(LauncherWelcome.title).assertDoesNotExist()
        composeRule.onNodeWithText("Add app").performClick()
        assertEquals(listOf<RootSheet>(RootSheet.AddApp), presented)
        composeRule.onNodeWithText("Apps you publish with the Ovenlight connector on your computers show up here.").assertIsDisplayed().performFirstLinkClick()
        assertEquals(listOf(AppLinks.ownComputersHelp), links)
    }

    @Test
    fun testGridOfOwnAndSharedApps() {
        val opened = mutableListOf<WebApp>()
        show(LauncherState(apps = apps, ownerEnabled = true, ownerState = NodeState.Ready, reachability = mapOf(garden.id to AppReachability.Offline), open = { opened += it }))
        assertEquals(4, columns())
        assertEquals(null, stateOf("Recipe Box"))
        assertEquals("New", stateOf(sharedTile))
        assertEquals(AppReachability.Offline.label, stateOf("Garden Log"))
        composeRule.onNodeWithText(ConnectionCopy.sharedBy("Riley"), useUnmergedTree = true).assertExists()
        tile(sharedTile).assert(SemanticsMatcher("click label Open") { it.config.getOrNull(SemanticsActions.OnClick)?.label == "Open" })
        tile("Recipe Box").performClick()
        assertEquals(listOf(recipes), opened)
    }

    @Test
    fun testThreeColumnsAtTheLargestFont() {
        show(LauncherState(apps = apps), fontScale = 2f)
        assertEquals(3, columns())
        tile(sharedTile).assertIsDisplayed()
    }

    @Test
    @Config(qualifiers = "w915dp-h412dp-land")
    fun testMoreColumnsInLandscape() {
        show(LauncherState(apps = apps))
        assertTrue((columns() ?: 0) >= 7)
    }

    @Test
    fun testConnectCard() {
        val presented = mutableListOf<RootSheet>()
        val login = URI("https://login.tailscale.com/a/abc123")
        show(LauncherState(apps = listOf(recipes), ownerEnabled = true, ownerState = NodeState.NeedsLogin(login), present = { presented += it }))
        composeRule.onNodeWithText("Connect your computers").assertIsDisplayed()
        composeRule.onNodeWithText("Connect").performClick()
        assertEquals(listOf<RootSheet>(RootSheet.Login(login)), presented)
    }

    @Test
    @Config(qualifiers = "+night")
    fun testApproveCard() {
        val presented = mutableListOf<RootSheet>()
        show(LauncherState(ownerEnabled = true, ownerState = NodeState.AwaitingApproval, deviceName = "ovenlight-k2x9", present = { presented += it }))
        composeRule.onNodeWithText("Approve this phone").assertIsDisplayed()
        composeRule.onNodeWithText("find ovenlight-k2x9,", substring = true).assertIsDisplayed()
        composeRule.onNodeWithText("Open Machines page").performClick()
        assertEquals(listOf<RootSheet>(RootSheet.Approve), presented)
    }

    @Test
    fun testPendingInvites() {
        val presented = mutableListOf<RootSheet>()
        val joining = invite("i1", "House Guide")
        val failed = invite("i2", "Book Club")
        show(
            LauncherState(
                memberships = listOf(membership(joining, failed)),
                phases = mapOf("i1" to JoinPhase.Joining, "i2" to JoinPhase.Failed(JoinError.Network)),
                present = { presented += it },
            ),
        )
        composeRule.onNodeWithText(LauncherWelcome.title).assertDoesNotExist()
        composeRule.onNodeWithText(ConnectionCopy.joining("Riley", "House Guide"), substring = true).assertIsDisplayed()
        composeRule.onNodeWithText(JoinError.Network.title("Riley", "Book Club"), substring = true).assertIsDisplayed().performClick()
        assertEquals(failed, (presented.single() as RootSheet.Join).request.invite?.getOrNull())
    }

    @Test
    fun testStatusPillConnecting() {
        show(LauncherState(apps = listOf(recipes), ownerEnabled = true, ownerState = NodeState.Connecting))
        composeRule.onNodeWithText(ConnectionCopy.connecting).assertIsDisplayed()
    }

    @Test
    fun testStatusPillFailure() {
        var retried = false
        show(LauncherState(apps = listOf(recipes), ownerEnabled = true, ownerState = NodeState.Failed(ConnectionCopy.couldNotStart), retry = { retried = true }))
        composeRule.onNodeWithText(ConnectionCopy.connecting).assertDoesNotExist()
        composeRule.onNodeWithText(ConnectionCopy.couldNotStart).assertIsDisplayed().performClick()
        assertTrue(retried)
    }

    @Test
    fun testNoPillWhenConnectedOrOff() {
        show(LauncherState(apps = listOf(recipes), ownerEnabled = false, ownerState = NodeState.Idle))
        composeRule.onNodeWithText(ConnectionCopy.connecting).assertDoesNotExist()
        composeRule.onNodeWithText("Try again").assertDoesNotExist()
    }

    @Test
    fun testMenuForAnOwnApp() {
        show(LauncherState(apps = apps))
        tile("Recipe Box").performTouchInput { longClick() }
        composeRule.onNodeWithText(recipes.host).assertIsDisplayed()
        for (item in listOf("Rename", "Refresh icon", "Sign out and clear data", "Remove app")) composeRule.onNodeWithText(item).assertIsDisplayed()
        composeRule.onNodeWithText(ConnectionCopy.reportProblem).assertDoesNotExist()
        composeRule.onNodeWithText(ConnectionCopy.leaveApps("Riley")).assertDoesNotExist()
    }

    @Test
    fun testMenuForASharedApp() {
        var reported: WebApp? = null
        var left: String? = null
        show(
            LauncherState(
                apps = apps, memberships = listOf(membership()), leave = { left = it },
                problemReport = { reported = it; null },
            ),
        )
        tile(sharedTile).performTouchInput { longClick() }
        composeRule.onNodeWithText(ConnectionCopy.sharedBy("Riley")).assertIsDisplayed()
        composeRule.onNodeWithText(ConnectionCopy.reportProblem).performClick()
        assertEquals(guide, reported)
        tile(sharedTile).performTouchInput { longClick() }
        composeRule.onNodeWithText(ConnectionCopy.leaveApps("Riley")).performClick()
        composeRule.onNodeWithText(ConnectionCopy.leaveTitle("Riley")).assertIsDisplayed()
        composeRule.onNodeWithText(ConnectionCopy.leaveDetail("Riley")).assertIsDisplayed()
        composeRule.onNodeWithText("Leave").performClick()
        assertEquals("m1", left)
    }

    @Test
    fun testRemoveAsks() {
        val removed = mutableListOf<UUID>()
        show(LauncherState(apps = apps, remove = { removed += it }))
        tile(sharedTile).performTouchInput { longClick() }
        composeRule.onNodeWithText("Remove app").performClick()
        composeRule.onNodeWithText("Remove House Guide?").assertIsDisplayed()
        composeRule.onNodeWithText(ConnectionCopy.removeSharedDetail("Riley")).assertIsDisplayed()
        composeRule.onNodeWithText("Cancel").performClick()
        assertTrue(removed.isEmpty())
        tile("Recipe Box").performTouchInput { longClick() }
        composeRule.onNodeWithText("Remove app").performClick()
        composeRule.onNodeWithText("This removes the app from Ovenlight and deletes everything it stored on this phone.").assertIsDisplayed()
        composeRule.onNodeWithText("Remove app").performClick()
        assertEquals(listOf(recipes.id), removed)
    }

    @Test
    fun testSignOutAndClearDataAsks() {
        val cleared = mutableListOf<UUID>()
        show(LauncherState(apps = apps, clearData = { cleared += it }))
        tile("Garden Log").performTouchInput { longClick() }
        composeRule.onNodeWithText("Sign out and clear data").performClick()
        composeRule.onNodeWithText("Sign out of Garden Log?").assertIsDisplayed()
        composeRule.onNodeWithText("Sign out and clear data").performClick()
        assertEquals(listOf(garden.id), cleared)
    }

    @Test
    fun testRename() {
        val renamed = mutableListOf<Pair<UUID, String>>()
        show(LauncherState(apps = apps, rename = { id, name -> renamed += id to name }))
        tile("Recipe Box").performTouchInput { longClick() }
        composeRule.onNodeWithText("Rename").performClick()
        composeRule.onNodeWithText("Rename app").assertIsDisplayed()
        composeRule.onNodeWithText("Recipe Box").performTextReplacement("Kitchen")
        composeRule.onNodeWithText("Save").performClick()
        assertEquals(listOf(recipes.id to "Kitchen"), renamed)
    }

    @Test
    fun testReorderActions() {
        val moves = mutableListOf<Pair<UUID, Int>>()
        show(LauncherState(apps = apps, move = { id, index -> moves += id to index }))
        fun actions(description: String) = tile(description).fetchSemanticsNode().config.getOrNull(SemanticsActions.CustomActions).orEmpty()
        assertEquals(listOf("Move after House Guide"), actions("Recipe Box").map { it.label })
        val middle = actions(sharedTile)
        assertEquals(listOf("Move before Recipe Box", "Move after Garden Log"), middle.map { it.label })
        assertEquals(listOf("Move before House Guide"), actions("Garden Log").map { it.label })
        composeRule.runOnUiThread { middle[1].action() }
        assertEquals(listOf(guide.id to 2), moves)
        val item = tile("Garden Log").fetchSemanticsNode().config.getOrNull(SemanticsProperties.CollectionItemInfo)
        assertEquals(0 to 2, item?.rowIndex to item?.columnIndex)
    }

    /** Shows the grid with a list the moves change, as the registry's does. */
    private fun showMovable(moves: MutableList<Pair<UUID, Int>>): () -> List<WebApp> {
        var order by mutableStateOf(apps)
        composeRule.setContent {
            OvenlightTheme {
                LauncherView(LauncherState(apps = order, move = { id, index -> moves += id to index; order = HomeReorder.moving(order, id, index) { it.id } }))
            }
        }
        return { order }
    }

    /** Holds [from], then drags it to [to] in ten steps [stepMillis] apart and lets go. */
    private fun drag(from: String, to: String, stepMillis: Long) {
        val start = tile(from).fetchSemanticsNode().boundsInRoot.center
        val end = tile(to).fetchSemanticsNode().boundsInRoot.center
        tile(from).performTouchInput {
            down(center)
            advanceEventTime(viewConfiguration.longPressTimeoutMillis + 100)
            for (step in 1..10) moveBy(Offset((end.x - start.x) / 10, (end.y - start.y) / 10), delayMillis = stepMillis)
            up()
        }
        composeRule.waitForIdle()
    }

    @Test
    fun testDragMovesAnApp() {
        val moves = mutableListOf<Pair<UUID, Int>>()
        val order = showMovable(moves)
        drag("Recipe Box", "Garden Log", stepMillis = 30)
        assertEquals(listOf(recipes.id to 2), moves)
        assertEquals(listOf(guide, garden, recipes), order())
    }

    @Test
    fun testADropLandsWhereReleasedWithoutResting() {
        val moves = mutableListOf<Pair<UUID, Int>>()
        val order = showMovable(moves)
        drag("Garden Log", "Recipe Box", stepMillis = 5)
        assertEquals(listOf(garden.id to 0), moves)
        assertEquals(listOf(garden, recipes, guide), order())
    }

    @Test
    fun testADropOnItsOwnSlotKeepsTheOrder() {
        val moves = mutableListOf<Pair<UUID, Int>>()
        val order = showMovable(moves)
        tile("Recipe Box").performTouchInput {
            down(center)
            advanceEventTime(viewConfiguration.longPressTimeoutMillis + 100)
            moveBy(Offset(viewConfiguration.touchSlop * 2, 0f), delayMillis = 30)
            up()
        }
        composeRule.waitForIdle()
        assertEquals(listOf(recipes.id to 0), moves)
        assertEquals(apps, order())
    }

    @Test
    fun testAScrollStartingOnAnIconDoesntOpenIt() {
        val opened = mutableListOf<WebApp>()
        show(LauncherState(apps = apps, open = { opened += it }))
        tile("Recipe Box").performTouchInput {
            down(center)
            moveBy(Offset(0f, 200f), delayMillis = 20)
            up()
        }
        assertTrue(opened.isEmpty())
    }

    @Test
    fun testRightToLeftTapOpensTheTouchedApp() {
        val opened = mutableListOf<WebApp>()
        composeRule.setContent {
            OvenlightTheme {
                CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) { LauncherView(LauncherState(apps = apps, open = { opened += it })) }
            }
        }
        assertTrue(tile("Recipe Box").fetchSemanticsNode().boundsInRoot.left > tile("Garden Log").fetchSemanticsNode().boundsInRoot.left)
        tile("Recipe Box").performClick()
        tile("Garden Log").performClick()
        assertEquals(listOf(recipes, garden), opened)
    }

    @Test
    fun testRefreshIcon() {
        val refreshed = mutableListOf<UUID>()
        show(LauncherState(apps = apps, refreshIcon = { refreshed += it }))
        tile("Garden Log").performTouchInput { longClick() }
        composeRule.onNodeWithText("Refresh icon").performClick()
        assertEquals(listOf(garden.id), refreshed)
    }

    @Test
    fun testNoEmailApp() {
        val context = ApplicationProvider.getApplicationContext<Application>()
        // No app takes the mail link, as on a phone with no email app.
        shadowOf(context).checkActivities(true)
        show(LauncherState(apps = apps, problemReport = { MailDraft(subject = "Ovenlight report: ${it.name}", body = "") }))
        tile(sharedTile).performTouchInput { longClick() }
        composeRule.onNodeWithText(ConnectionCopy.reportProblem).performClick()
        composeRule.onNodeWithText("No email app").assertIsDisplayed()
        composeRule.onNodeWithText("Write to ${AppLinks.supportEmail}.").assertIsDisplayed()
        composeRule.onNodeWithText("Copy address").performClick()
        val clip = context.getSystemService(ClipboardManager::class.java).primaryClip
        assertEquals(AppLinks.supportEmail, clip?.getItemAt(0)?.text?.toString())
        composeRule.onNodeWithText("No email app").assertDoesNotExist()
    }

    @Test
    fun testKeyboardOpensAnApp() {
        val opened = mutableListOf<WebApp>()
        show(LauncherState(apps = apps, open = { opened += it }))
        tile("Garden Log").requestFocus().performKeyInput { pressKey(Key.Enter) }
        assertEquals(listOf(garden), opened)
    }
}
