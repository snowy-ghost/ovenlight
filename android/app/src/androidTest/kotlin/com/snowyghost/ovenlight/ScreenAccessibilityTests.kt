package com.snowyghost.ovenlight

import android.graphics.Bitmap
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.junit4.accessibility.enableAccessibilityChecks
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.tryPerformAccessibilityChecks
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import java.net.URI
import java.time.Instant
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Every screen's states through the Accessibility Test Framework, on a device, where it checks
 * contrast, touch targets and labels. Robolectric's Compose tests skip these checks, so they run
 * here, in light, dark and at the largest font. On Android 10 the framework reaches none of
 * Compose's nodes (every check comes back not run, and the negative control fails), so these run
 * from Android 11; the negative control would show it if a version above stopped reaching them too.
 */
@RunWith(AndroidJUnit4::class)
@SdkSuppress(minSdkVersion = 30)
class ScreenAccessibilityTests {
    @get:Rule
    val composeRule = createComposeRule()

    @Before
    fun checks() {
        composeRule.enableAccessibilityChecks()
    }

    /**
     * Shows [content] in each look and checks every root, dialogs and menus included. [open]
     * opens a menu or dialog once, before the first look; it stays open through the others.
     */
    private fun check(open: () -> Unit = {}, content: @Composable () -> Unit) {
        val look = mutableStateOf(Look.Light)
        composeRule.setContent { Looked(look.value, content) }
        composeRule.waitForIdle()
        open()
        composeRule.inEveryLook(look) {
            composeRule.checkEveryRoot()
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

    private val book = AdminApp("book-club", "Book Club", "https://book-club.tail1.ts.net/", online = true, shareable = true, guests = 2)
    private val machine = OwnerSharing.Machine(
        adminHost = "book-club.tail1.ts.net",
        apps = listOf(book, AdminApp("budget", "Budget", "https://budget.tail1.ts.net/", online = false)),
        guests = AdminGuestList(
            guests = listOf(
                AdminGuest("n1", "p1", "Sam", "book-club", "ovenlight-4k2x9q", claimedAt = Instant.ofEpochSecond(1_780_000_000), lastOpened = Instant.ofEpochSecond(1_784_000_000)),
                AdminGuest("n4", "p1", "Sam", "book-club", "ovenlight-m7c1pw", claimedAt = Instant.ofEpochSecond(1_784_000_000), lastOpened = Instant.now()),
                AdminGuest("n2", "p2", "Kim", "book-club", "ovenlight-r5j8de", claimedAt = Instant.ofEpochSecond(1_786_000_000)),
            ),
            invites = listOf(AdminInvite("i3", "Lee", "p3", "book-club", AdminInvite.sent, expires = Instant.now().plusSeconds(86_400))),
            openedSince = Instant.ofEpochSecond(1_770_000_000),
            taggedDevices = AdminTaggedDevices(apps = 1, guests = 3, tailnet = 5),
        ),
        feedback = listOf(AdminFeedback("f1", Instant.now().minusSeconds(1_800), "book-club", "Sam", "guest", note = "The chapter list stops scrolling.")),
    )
    private val sharing = SharingState(MutableStateFlow(listOf(machine)))

    @Composable
    private fun Sharing(root: SharingPage) = SharingScreen(sharing, SharingPlace(root), onClose = {}, scope = rememberCoroutineScope())

    @Test
    fun testPeopleAndSharing() = check { Sharing(SharingPage.List) }

    @Test
    fun testPeopleAndSharingNotConnected() = check { SharingScreen(sharing.copy(isAvailable = false), SharingPlace(SharingPage.List), onClose = {}, scope = rememberCoroutineScope()) }

    @Test
    fun testAnAppsPeopleWithARowMenu() = check(open = { composeRule.onNodeWithContentDescription("Sam options").performClick() }) {
        Sharing(SharingPage.App(machine.id, "book-club"))
    }

    @Test
    fun testSelectingPeople() = check(open = { composeRule.onNodeWithText("Kim").performTouchInput { longClick() } }) { Sharing(SharingPage.People) }

    @Test
    fun testAPersonsPage() = check { Sharing(SharingPage.Person(machine.id, "p1")) }

    @Test
    fun testAPersonsRemoval() = check(open = { composeRule.onNodeWithText("Remove Sam").performScrollTo().performClick() }) {
        Sharing(SharingPage.Person(machine.id, "p1"))
    }

    @Test
    fun testTheFeedbackInbox() = check { Sharing(SharingPage.Feedback()) }

    @Test
    fun testTheInviteComposer() = check { InviteComposerDialog(InviteComposer(machine, book), sharing, rememberCoroutineScope(), onDismiss = {}) }

    @Test
    fun testANewInvite() = check {
        val composer = InviteComposer(machine, book).apply {
            result = AdminShareResult(
                AdminInvite("i9", "Jo", "p9", "book-club", AdminInvite.sent, expires = Instant.now().plusSeconds(7 * 86_400)),
                link = "https://ovenlight.app/join#v=1&key=tskey-auth-k1&owner=Dimitri&app=book-club&invite=i9&host=book-club.tail1.ts.net&to=Jo",
            )
        }
        InviteComposerDialog(composer, sharing, rememberCoroutineScope(), onDismiss = {})
    }

    @Test
    fun testJoinWithInvite() = check { JoinFlowScreen(JoinRequest(), JoinState(canScan = true), onClose = {}) }

    @Test
    fun testTheJoinQuestionWithAnotherConnectionServer() = check {
        JoinFlowScreen(JoinRequest(Result.success(invite.copy(control = URI("https://hs.example.com")))), JoinState(), onClose = {})
    }

    @Test
    fun testAJoinThatCantReachTheApp() = check {
        JoinFlowScreen(JoinRequest(Result.success(invite)), JoinState(phases = mapOf("i1" to JoinPhase.Failed(JoinError.Network))), onClose = {})
    }

    @Test
    fun testAJoinedApp() = check {
        JoinFlowScreen(JoinRequest(Result.success(invite)), JoinState(phases = mapOf("i1" to JoinPhase.Joined(guide.id)), apps = listOf(guide)), onClose = {})
    }

    @Test
    fun testAddAppBeforeSignIn() = check {
        AddAppDialog(ownerSuffix = null, ownerEnabled = false, ownerState = NodeState.Idle, add = {}, useOwnComputers = {}, present = {}, onClose = {})
    }

    @Test
    fun testAddApp() = check {
        AddAppDialog(ownerSuffix = "tail1.ts.net", ownerEnabled = true, ownerState = NodeState.Ready, add = {}, useOwnComputers = {}, present = {}, onClose = {})
    }

    @Test
    fun testTheLockScreen() = check { LockScreen(isLocked = true, isAuthenticating = false, lastError = null, onUnlock = {}) }

    @Test
    fun testTheLockScreenWithAnError() = check { LockScreen(isLocked = true, isAuthenticating = false, lastError = "Couldn't unlock. Try again.", onUnlock = {}) }

    // The open app's own screens. The tucked handle is left out: it is 20 dp tall by design
    // (design.md), and a pull at the top reaches the pill too.

    private val chromeActions = AppChromeActions(share = {}, sendFeedback = {})

    @Composable
    private fun Chrome(controller: ChromeController, touchExploration: Boolean = false) =
        AppChrome(recipes, icon = null, isLoaded = true, actions = chromeActions, controller = controller, header = MaterialTheme.colorScheme.background, touchExploration = touchExploration)

    @Test
    fun testThePillWithItsTip() = check { Chrome(ChromeController(MemoryStore())) }

    @Test
    fun testTheAppMenu() = check(open = { composeRule.onNodeWithContentDescription("Recipe Box options").performClick() }) {
        Chrome(ChromeController(MemoryStore().apply { putBoolean(ChromeController.TIP_CLOSED_KEY, true) }), touchExploration = true)
    }

    @Test
    fun testSendFeedback() = check {
        val screenshot = Bitmap.createBitmap(90, 160, Bitmap.Config.ARGB_8888).apply { eraseColor(android.graphics.Color.rgb(200, 120, 60)) }
        FeedbackSheet(recipes, FeedbackDraft(screenshot, URI("https://recipe-box.tail1.ts.net/soup")), density = 1f, send = { FeedbackResult.Sent }, onDismiss = {}, scope = rememberCoroutineScope())
    }

    @Test
    fun testADownloadsSheet() = check { DownloadSheetContent("Recipes export.csv", "2.1 kB", canOpen = true, {}, {}, {}) }

    @Test
    fun testTheCapturePrompt() = check { CapturePromptDialog("Allow Recipe Box to use the camera?") {} }

    @Test
    fun testTheWebViewUpdateScreen() = check { WebViewUpdateScreen("com.google.android.webview") }

    @Test
    fun testAcknowledgements() = check(open = { composeRule.onNodeWithText("Acknowledgements").performScrollTo().performClick() }) {
        SettingsScreen(SettingsState(version = "1.0.0 (12)"), onClose = {})
    }

    @Test
    fun testAnInviteThatCantBeUsed() = check {
        JoinFlowScreen(JoinRequest(URI("ovenlight://join?v=2&control=https%3A%2F%2Fcontrolplane.tailscale.com")), JoinState(), onClose = {})
    }

    @Test
    fun testJoining() = check { JoinFlowScreen(JoinRequest(Result.success(invite)), JoinState(phases = mapOf("i1" to JoinPhase.Claiming)), onClose = {}) }
}
