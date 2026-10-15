package com.snowyghost.ovenlight

import android.content.res.Configuration
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.junit4.accessibility.enableAccessibilityChecks
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.tryPerformAccessibilityChecks
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.net.URI
import java.time.Instant
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Every screen's states through the Accessibility Test Framework, on a device, where it checks
 * contrast, touch targets and labels. Robolectric's Compose tests skip these checks, so they run
 * here, in light, dark and at the largest font.
 */
@RunWith(AndroidJUnit4::class)
class ScreenAccessibilityTests {
    @get:Rule
    val composeRule = createComposeRule()

    private enum class Look(val dark: Boolean, val fontScale: Float) { Light(false, 1f), Dark(true, 1f), LargestText(false, 2f) }

    @Before
    fun checks() {
        composeRule.enableAccessibilityChecks()
    }

    /**
     * Shows [content] in each look and checks every root, dialogs and menus included. [open]
     * opens a menu or dialog once, before the first look; it stays open through the others.
     */
    private fun check(open: () -> Unit = {}, content: @Composable () -> Unit) {
        var look by mutableStateOf(Look.Light)
        composeRule.setContent {
            val base = LocalConfiguration.current
            val configuration = Configuration(base).apply {
                uiMode = (uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or
                    (if (look.dark) Configuration.UI_MODE_NIGHT_YES else Configuration.UI_MODE_NIGHT_NO)
            }
            val density = LocalDensity.current
            CompositionLocalProvider(LocalConfiguration provides configuration, LocalDensity provides Density(density.density, look.fontScale)) {
                OvenlightTheme(content)
            }
        }
        composeRule.waitForIdle()
        open()
        for (each in Look.entries) {
            look = each
            composeRule.waitForIdle()
            composeRule.onAllNodes(isRoot()).fetchSemanticsNodes().indices.forEach { composeRule.onAllNodes(isRoot())[it].tryPerformAccessibilityChecks() }
        }
    }

    private val recipes = WebApp(name = "Recipe Box", startURL = URI("https://recipe-box.tail1.ts.net/"), discovered = true)
    private val guide = WebApp(name = "House Guide", startURL = URI("https://house-guide.tail2.ts.net/"), membershipID = "m1", sharedBy = "Riley", isNew = true)
    private val garden = WebApp(name = "Garden Log", startURL = URI("https://garden-log.tail1.ts.net/"), discovered = true)
    private val invite = InviteLink(
        control = URI("https://controlplane.tailscale.com"), key = "tskey-x", owner = "Riley", app = "pantry", invite = "i1",
        host = "pantry.tail2.ts.net", name = "Pantry", to = "Sam",
    )

    /** The negative control: the checks really run here, unlike under Robolectric. */
    @Test
    fun testTheChecksCatchATinyUnlabeledButton() {
        composeRule.setContent {
            androidx.compose.foundation.layout.Box(androidx.compose.ui.Modifier.size(10.dp).clickable {})
        }
        var caught = false
        try {
            composeRule.onRoot().tryPerformAccessibilityChecks()
        } catch (_: Throwable) {
            caught = true
        }
        assertTrue("the accessibility checks didn't run", caught)
    }

    @Test
    fun testTheLauncherWelcome() = check { LauncherView(LauncherState()) }

    @Test
    fun testTheLauncherGrid() = check {
        LauncherView(
            LauncherState(
                apps = listOf(recipes, guide, garden), ownerEnabled = true, ownerState = NodeState.Ready,
                reachability = mapOf(garden.id to AppReachability.Offline),
            ),
        )
    }

    @Test
    fun testTheOwnersEmptyGridWithTheConnectCard() = check {
        LauncherView(LauncherState(ownerEnabled = true, ownerState = NodeState.NeedsLogin(URI("https://login.tailscale.com/a/abc"))))
    }

    @Test
    fun testTheApproveCardAndAPendingInvite() = check {
        val membership = Membership(invite, id = "m2").copy(pendingClaims = listOf(PendingClaim(invite, Instant.ofEpochSecond(1_000))))
        LauncherView(
            LauncherState(
                apps = listOf(recipes), ownerEnabled = true, ownerState = NodeState.AwaitingApproval, deviceName = "pixel-9",
                memberships = listOf(membership), phases = mapOf("i1" to JoinPhase.Failed(JoinError.Network)),
            ),
        )
    }

    @Test
    fun testTheStatusPillFailing() = check {
        LauncherView(LauncherState(apps = listOf(recipes), ownerEnabled = true, ownerState = NodeState.Failed(ConnectionCopy.couldNotStart)))
    }

    private val grid = LauncherState(apps = listOf(recipes, guide, garden), ownerEnabled = true, ownerState = NodeState.Ready)

    private fun holdTile(description: String) =
        composeRule.onNodeWithContentDescription(description).performSemanticsAction(SemanticsActions.OnLongClick)

    @Test
    fun testTheMoreMenu() = check(open = { composeRule.onNodeWithContentDescription("More").performClick() }) { LauncherView(grid) }

    @Test
    fun testAnOwnAppsMenu() = check(open = { holdTile("Recipe Box") }) { LauncherView(grid) }

    @Test
    fun testASharedAppsMenu() = check(open = { holdTile("House Guide, ${ConnectionCopy.sharedBy("Riley")}") }) { LauncherView(grid) }

    @Test
    fun testTheRenameDialog() = check(open = {
        holdTile("Recipe Box")
        composeRule.onNodeWithText("Rename").performClick()
    }) { LauncherView(grid) }

    @Test
    fun testTheRemoveConfirmation() = check(open = {
        holdTile("Recipe Box")
        composeRule.onNodeWithText("Remove app").performClick()
    }) { LauncherView(grid) }

    @Test
    fun testTheNoEmailAppDialog() = check { NoEmailAppDialog {} }

    @Test
    fun testTheLockDelayDialog() = check(open = { composeRule.onNodeWithText("Require screen lock").performClick() }) {
        SettingsScreen(SettingsState(version = "1.0.0 (12)"), onClose = {})
    }

    @Test
    fun testTheSignOutConfirmation() = check(open = { composeRule.onNodeWithText("Sign out").performScrollTo().performClick() }) {
        SettingsScreen(
            SettingsState(owner = OwnerSettings(NodeState.Ready, TailnetStatus.User("dimitri@example.com", "Dimitri"), "example.com")),
            onClose = {},
        )
    }

    @Test
    fun testSettingsForAGuest() = check {
        SettingsScreen(
            SettingsState(shared = listOf(SharedOwner("m1", "Riley", listOf("House Guide", "Pantry"))), version = "1.0.0 (12)"),
            onClose = {},
        )
    }

    @Test
    fun testSettingsForAConnectedOwnerWithNoScreenLock() = check {
        SettingsScreen(
            SettingsState(
                noScreenLock = true, version = "1.0.0 (12)",
                owner = OwnerSettings(NodeState.Ready, TailnetStatus.User("dimitri@example.com", "Dimitri"), "example.com", deviceName = "pixel-9"),
            ),
            onClose = {},
        )
    }

    @Test
    fun testTheLockScreen() = check { LockScreen(isLocked = true, isAuthenticating = false, lastError = null, onUnlock = {}) }

    @Test
    fun testTheLockScreenWithAnError() = check { LockScreen(isLocked = true, isAuthenticating = false, lastError = "Couldn't unlock. Try again.", onUnlock = {}) }
}
