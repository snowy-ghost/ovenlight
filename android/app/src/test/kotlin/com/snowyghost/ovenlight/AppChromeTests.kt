package com.snowyghost.ovenlight

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
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

/** The open app's chrome in each state: tucked, shown, with its tip, and its menu for an owner's app and a shared one. */
@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "w412dp-h915dp")
// Real drawing, which the icons need.
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AppChromeTests {
    @get:Rule
    val composeRule = createComposeRule()

    private val recipes = WebApp(name = "Recipe Box", startURL = URI("https://recipe-box.tail1.ts.net/"), discovered = true)
    private val guide = WebApp(name = "House Guide", startURL = URI("https://house-guide.tail2.ts.net/"), membershipID = "m1", sharedBy = "Riley")

    private val look = mutableStateOf(Look.Light)
    private var loaded by mutableStateOf(false)
    private val done = mutableListOf<String>()

    private fun actions(feedback: Boolean = true, share: Boolean = false, leave: String? = null) = AppChromeActions(
        home = { done += "home" },
        reload = { done += "reload" },
        share = if (share) ({ done += "share" }) else null,
        sendFeedback = if (feedback) ({ done += "feedback" }) else null,
        reportProblem = { done += "report" },
        leave = leave?.let { AppChromeActions.Leave(it) { done += "leave" } },
        lock = { done += "lock" },
    )

    /** A store whose tip was closed already, unless [tip]. */
    private fun controller(tip: Boolean = false) = ChromeController(MemoryStore().apply { if (!tip) putBoolean(ChromeController.TIP_CLOSED_KEY, true) })

    private fun show(app: WebApp, controller: ChromeController, actions: AppChromeActions = actions(), touchExploration: Boolean = false) {
        composeRule.setContent {
            Looked(look.value) { AppChrome(app, icon = null, isLoaded = loaded, actions = actions, controller = controller, touchExploration = touchExploration) }
        }
    }

    /** Runs [check] in each look, then the label checks on everything showing. */
    private fun inEveryLook(check: () -> Unit) = composeRule.inEveryLook(look) { check(); composeRule.checkAccessibility() }

    private fun node(description: String) = composeRule.onNodeWithContentDescription(description)

    private fun assertTucked() {
        node("Show app menu").assertIsDisplayed()
        node("All apps").assertDoesNotExist()
    }

    private fun assertShown(app: WebApp) {
        node("All apps").assertIsDisplayed()
        node("${app.name} options").assertIsDisplayed()
        node("Show app menu").assertDoesNotExist()
    }

    @Test
    fun testAppsOpenTuckedAwayAndTheHandleBringsThePillBack() {
        val controller = controller()
        show(recipes, controller)
        inEveryLook { assertTucked() }
        node("Show app menu").performClick()
        inEveryLook { assertShown(recipes) }
        node("All apps").performClick()
        assertEquals(listOf("home"), done)
    }

    @Test
    fun testTheTipShowsThePillUntilItIsClosed() {
        val store = MemoryStore()
        val controller = ChromeController(store)
        loaded = true
        show(recipes, controller)
        inEveryLook {
            assertShown(recipes)
            composeRule.onNodeWithText("Your apps are up here").assertIsDisplayed()
            composeRule.onNodeWithText("This bar tucks away while you use an app. Tap the handle at the top of the screen, or scroll up, to bring it back.").assertIsDisplayed()
        }
        // Loaded and past the auto-hide, the tip keeps it out.
        composeRule.mainClock.advanceTimeBy(ChromeController.AUTO_HIDE_MILLIS + 500)
        assertShown(recipes)
        // The page's touches can't hide it either while the tip is due.
        controller.hide()
        composeRule.waitForIdle()
        assertShown(recipes)

        composeRule.onNodeWithText("Got it").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Your apps are up here").assertDoesNotExist()
        assertTrue("closed for good", store.getBoolean(ChromeController.TIP_CLOSED_KEY))
        assertFalse(ChromeController(store).tipShowing)
        // Then it tucks itself away after the load like any other time.
        composeRule.mainClock.advanceTimeBy(ChromeController.AUTO_HIDE_MILLIS + 500)
        composeRule.waitForIdle()
        assertTucked()
    }

    @Test
    fun testItTucksAwayOnScrollAndComesBackOnAPull() {
        val controller = controller()
        show(recipes, controller)
        controller.reveal(fromHandle = false)
        composeRule.waitForIdle()
        assertShown(recipes)
        controller.hide()
        composeRule.waitForIdle()
        assertTucked()
        controller.reveal(fromHandle = false)
        composeRule.waitForIdle()
        assertShown(recipes)
        // Once revealed, the person has it: a load doesn't hide it on its own.
        loaded = true
        composeRule.mainClock.advanceTimeBy(ChromeController.AUTO_HIDE_MILLIS + 500)
        composeRule.waitForIdle()
        assertShown(recipes)
    }

    @Test
    fun testTalkBackKeepsItShown() {
        val controller = controller()
        show(recipes, controller, touchExploration = true)
        inEveryLook { assertShown(recipes) }
        controller.hide()
        composeRule.waitForIdle()
        assertShown(recipes)
    }

    @Test
    fun testTheSnapshotFrameShowsNoChrome() {
        val controller = controller()
        controller.reveal()
        show(recipes, controller)
        assertShown(recipes)
        // On the very next frame, with no time for an exit animation, it's gone.
        composeRule.mainClock.autoAdvance = false
        controller.hiddenForSnapshot = true
        Snapshot.sendApplyNotifications()
        composeRule.mainClock.advanceTimeByFrame()
        node("All apps").assertDoesNotExist()
        node("Show app menu").assertDoesNotExist()
        controller.hiddenForSnapshot = false
        Snapshot.sendApplyNotifications()
        composeRule.mainClock.advanceTimeByFrame()
        assertShown(recipes)
        composeRule.mainClock.autoAdvance = true
    }

    private fun openMenu(app: WebApp) {
        node("${app.name} options").performClick()
        composeRule.waitForIdle()
    }

    private fun assertMenu(vararg items: String) {
        for (item in items) composeRule.onNodeWithText(item).assertIsDisplayed()
    }

    private fun assertNotInMenu(vararg items: String) {
        for (item in items) composeRule.onNodeWithText(item).assertDoesNotExist()
    }

    @Test
    fun testTheOwnersMenu() {
        val controller = controller()
        controller.reveal()
        show(recipes, controller, actions(share = true))
        inEveryLook {
            openMenu(recipes)
            assertMenu("Reload", "Share…", "Send feedback", "Report a problem", "Lock Ovenlight")
            assertNotInMenu("Leave Riley's apps")
            composeRule.onNodeWithText("Reload").performClick()
            composeRule.waitForIdle()
        }
        assertEquals(listOf("reload", "reload", "reload"), done)
        done.clear()
        for (item in listOf("Share…", "Send feedback", "Report a problem", "Lock Ovenlight")) {
            openMenu(recipes)
            composeRule.onNodeWithText(item).performClick()
            composeRule.waitForIdle()
        }
        assertEquals(listOf("share", "feedback", "report", "lock"), done)
    }

    @Test
    fun testAnOwnersAppBeforeItLoadsOrWithoutSharingOffersLess() {
        val controller = controller()
        controller.reveal()
        show(recipes, controller, actions(feedback = false))
        openMenu(recipes)
        assertMenu("Reload", "Report a problem", "Lock Ovenlight")
        assertNotInMenu("Share…", "Send feedback")
        composeRule.checkAccessibility()
    }

    @Test
    fun testASharedAppsMenu() {
        val controller = controller()
        controller.reveal()
        show(guide, controller, actions(leave = "Riley"))
        inEveryLook {
            openMenu(guide)
            assertMenu("Reload", "Send feedback to Riley", "Report a problem", "Leave Riley's apps", "Lock Ovenlight")
            assertNotInMenu("Share…", "Send feedback")
            composeRule.onNodeWithText("Leave Riley's apps").performClick()
            composeRule.waitForIdle()
        }
        assertEquals(listOf("leave", "leave", "leave"), done)
    }

    /** Nothing a guest sees in the chrome or Send feedback names the network. */
    @Test
    fun testGuestsSeeNoNetworkName() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val labels = R.string::class.java.fields.filter { it.name.startsWith("chrome_") || it.name.startsWith("feedback_") }
        assertTrue(labels.size > 15)
        for (field in labels) CopyRules.assertNeutral(context.getString(field.getInt(null), "Riley", "House Guide"))
    }
}
