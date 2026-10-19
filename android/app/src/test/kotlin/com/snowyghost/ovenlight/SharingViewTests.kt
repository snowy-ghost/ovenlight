package com.snowyghost.ovenlight

import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeDown
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.zxing.BinaryBitmap
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeReader
import java.io.ByteArrayOutputStream
import java.net.URI
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Request
import okio.Buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * People & sharing, the invite composer and the feedback inbox from hand-made connector answers,
 * with every tappable thing labeled (see checkAccessibility); contrast and touch targets on a
 * device, in ScreenAccessibilityTests.
 */
@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "w411dp-h891dp")
// Real drawing, which the icons and images need.
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SharingViewTests {
    @get:Rule
    val composeRule = createComposeRule()

    private val now = Instant.now()
    private fun ago(days: Long): Instant = now.minusSeconds(86_400 * days)

    private val club = AdminApp("book-club", "Book Club", "https://book-club.tail1.ts.net/", online = true, shareable = true, guests = 2)
    private val budget = AdminApp("budget", "Budget", "https://budget.tail1.ts.net/", online = false, shareable = false)
    private val machine = OwnerSharing.Machine(
        adminHost = "book-club.tail1.ts.net",
        apps = listOf(club, budget),
        guests = AdminGuestList(
            guests = listOf(
                AdminGuest("n1", "p1", "Sam", "book-club", claimedAt = ago(120), lastOpened = ago(64)),
                AdminGuest("n4", "p1", "Sam", "book-club", claimedAt = ago(63), lastOpened = now),
                AdminGuest("n2", "p2", "Kim", "book-club", claimedAt = ago(30), lastOpened = ago(1)),
            ),
            invites = listOf(
                AdminInvite("i3", "Lee", "p3", "book-club", AdminInvite.sent, expires = now.plusSeconds(86_400)),
                AdminInvite("i4", "App Review", "p4", "book-club", AdminInvite.sent, expires = now.plusSeconds(86_400 * 80), review = true),
            ),
            openedSince = ago(200),
            taggedDevices = AdminTaggedDevices(apps = 1, guests = 3, tailnet = 5),
        ),
        feedback = listOf(AdminFeedback("f1", now.minusSeconds(1_800), "book-club", "Sam", "guest", note = "The chapter list stops scrolling.", screenshot = "f1.png")),
    )

    /** Each request the screens send, and what the connector answers. */
    private val requests = mutableListOf<Request>()
    private var answer: (Request) -> FetchResponse? = { FetchResponse(200, """{"removed":[]}""".toByteArray()) }
    private val confirmed = mutableListOf<String>()
    private var refreshes = 0

    private fun state(machines: List<OwnerSharing.Machine> = listOf(machine), confirm: Boolean = true) = SharingState(
        MutableStateFlow(machines),
        client = { AdminClient(it.adminHost) { request -> requests += request; answer(request) } },
        confirm = { reason -> confirmed += reason; confirm },
        refresh = { refreshes += 1 },
    )

    private fun show(state: SharingState, root: SharingPage = SharingPage.List) = show(state, SharingPlace(root))

    private fun show(state: SharingState, place: SharingPlace) {
        composeRule.setContent { OvenlightTheme { SharingScreen(state, place, onClose = {}, scope = rememberCoroutineScope()) } }
        composeRule.checkAccessibility()
    }

    private fun text(text: String) = composeRule.onNodeWithText(text).performScrollTo()

    private fun inDialog(text: String) = composeRule.onNode(hasText(text) and hasAnyAncestor(isDialog()))

    private fun Request.line() = "$method ${url.encodedPath}${url.encodedQuery?.let { "?$it" }.orEmpty()}"

    @Test
    fun testPeopleAndSharingListsEachComputersApps() {
        show(state())
        text("Book Club").assertIsDisplayed()
        text("Online · 2 people").assertIsDisplayed()
        text("Offline · Only you").assertIsDisplayed()
        text("People").assertIsDisplayed()
        text(SharingList.taggedDevices(listOf(machine))!! + " Learn more").assertIsDisplayed()
        composeRule.onNodeWithText("Feedback").performClick()
        text("The chapter list stops scrolling.").assertIsDisplayed()
        text("Sam · Book Club · 30 minutes ago").assertIsDisplayed()
    }

    @Test
    fun testPeopleAndSharingSaysWhyItsEmpty() {
        show(state(emptyList()).copy(isAvailable = false))
        text("Not connected").assertIsDisplayed()
        text("Ovenlight needs to be connected to your computers to manage sharing.").assertIsDisplayed()
    }

    @Test
    fun testNoComputerAnsweringSaysWhatWentWrong() {
        show(state(emptyList()).copy(problem = AdminError.NotOwner.message))
        text("No computers answered").assertIsDisplayed()
        text(AdminError.NotOwner.message).assertIsDisplayed()
    }

    /** Android only: an app or person page with nothing to show, after a refresh that failed, says why. */
    @Test
    fun testAPageWhoseComputerDidntAnswerSaysWhy() {
        show(state(emptyList()).copy(problem = AdminError.Unreachable.message), SharingPage.App(machine.id, "book-club"))
        text("No computers answered").assertIsDisplayed()
        text(AdminError.Unreachable.message).assertIsDisplayed()
        composeRule.onNodeWithText("App not found").assertDoesNotExist()
    }

    @Test
    fun testAnAppsPeopleAndTheirRemoval() {
        show(state(), SharingPage.App(machine.id, "book-club"))
        text("Share Book Club…").assertIsDisplayed()
        text("2 devices · opened today").assertIsDisplayed()
        text("1 device · opened yesterday").assertIsDisplayed()
        text("Unused, expires tomorrow").assertIsDisplayed()
        text("Review invite, unused, expires ${machine.guests.invites[1].expiresIn}").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Sam options").performClick()
        composeRule.onNodeWithText("Add a device for Sam").assertIsDisplayed()
        composeRule.onNodeWithText("Remove Sam").performClick()
        inDialog("Remove Sam?").assertIsDisplayed()
        inDialog("Sam can’t open Book Club anymore, on any device. Each of their devices is also removed from your Tailscale network unless it has another of your apps.")
            .assertIsDisplayed()
        inDialog("Remove").performClick()
        composeRule.waitForIdle()
        assertEquals("the screen lock first, for this", listOf("Remove Sam"), confirmed)
        assertEquals(listOf("DELETE /v1/guests/p1?app=book-club&by=person"), requests.map { it.line() })
        assertEquals("1", requests.single().header(AdminAPI.header))
        assertEquals(1, refreshes)
    }

    @Test
    fun testNothingIsRemovedWithoutTheScreenLock() {
        show(state(confirm = false), SharingPage.App(machine.id, "book-club"))
        composeRule.onNodeWithContentDescription("Kim options").performClick()
        composeRule.onNodeWithText("Remove Kim").performClick()
        inDialog("Remove").performClick()
        composeRule.waitForIdle()
        assertEquals(listOf("Remove Kim"), confirmed)
        assertEquals(emptyList<Request>(), requests)
    }

    @Test
    fun testCancelingAnInviteSaysWhenItFails() {
        answer = { FetchResponse(404, """{"error":"that invite was already used","code":"not_found"}""".toByteArray()) }
        show(state(), SharingPage.App(machine.id, "book-club"))
        composeRule.onNodeWithContentDescription("For Lee options").performScrollTo().performClick()
        composeRule.onNodeWithText("Cancel invite").performClick()
        composeRule.waitForIdle()
        assertEquals("no screen lock for an invite no one used", emptyList<String>(), confirmed)
        assertEquals(listOf("DELETE /v1/invites/i3"), requests.map { it.line() })
        text("That invite was already used").assertIsDisplayed()
    }

    @Test
    fun testAnAppThatIsntShareableSaysHowToShareIt() {
        show(state(), SharingPage.App(machine.id, "budget"))
        text("Budget is only for you.").assertIsDisplayed()
        text("To share it, run this on its computer: ovenlight publish --slug budget --shareable").assertIsDisplayed()
        // Which the footer finds in it, to set in code type.
        assertTrue(ApplicationProvider.getApplicationContext<Context>().getString(R.string.sharing_not_shareable_footer, "budget").contains(SharingList.shareableCommand("budget")))
        composeRule.onNodeWithText("People").assertDoesNotExist()
    }

    @Test
    fun testSelectingPeopleRemovesSeveralButNeverAppReview() {
        val reviewed = machine.copy(
            guests = machine.guests.copy(guests = machine.guests.guests + AdminGuest("n9", "p4", "App Review", "book-club", claimedAt = ago(2))),
        )
        show(state(listOf(reviewed)), SharingPage.People)
        // App Review joined last and opened nothing since, so it's last; its open review invite keeps it out of a selection.
        text("Those who opened your apps most recently come first. Removing someone also removes their devices from your Tailscale network.").assertIsDisplayed()
        composeRule.onNodeWithText("Kim").performTouchInput { longClick() }
        composeRule.onNodeWithText("1 selected").assertIsDisplayed()
        composeRule.onNode(hasText("Kim") and isToggleable()).assertIsOn()
        composeRule.onNode(hasText("App Review") and isToggleable()).assertIsNotEnabled()
        composeRule.onNodeWithText("Sam").performClick()
        composeRule.onNodeWithText("App Review").performClick()
        composeRule.onNodeWithText("2 selected").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Remove").performClick()
        inDialog("Remove 2 people?").assertIsDisplayed()
        inDialog("They can’t open your apps anymore, on any device, and their devices are removed from your Tailscale network.").assertIsDisplayed()
        inDialog("Remove").performClick()
        composeRule.waitForIdle()
        assertEquals(listOf("Remove 2 people"), confirmed)
        assertEquals(setOf("DELETE /v1/guests/p1?by=person", "DELETE /v1/guests/p2?by=person"), requests.map { it.line() }.toSet())
        composeRule.onNodeWithText("People").assertIsDisplayed()
    }

    @Test
    fun testAPersonsAppsAndDevices() {
        show(state(), SharingPage.Person(machine.id, "p1"))
        text("Book Club").assertIsDisplayed()
        assertEquals("the app, and the newer device", 2, composeRule.onAllNodesWithText("Opened today").fetchSemanticsNodes().size)
        text("Devices").assertIsDisplayed()
        text("Remove a device they no longer use, such as an old phone. It leaves your Tailscale network.").assertIsDisplayed()
        val first = DateTimeFormatter.ofPattern("MMM d, yyyy", Locale.US).format(ago(120).atZone(ZoneId.systemDefault()))
        composeRule.onNodeWithContentDescription("Joined $first options").performScrollTo().performClick()
        composeRule.onNodeWithText("Remove").performClick()
        inDialog("Remove this device?").assertIsDisplayed()
        inDialog("Sam can’t open your apps on this device anymore, and it’s removed from your Tailscale network.").assertIsDisplayed()
        inDialog("Remove").performClick()
        composeRule.waitForIdle()
        assertEquals(listOf("Remove Sam’s device"), confirmed)
        assertEquals("the device that joined first", listOf("DELETE /v1/guests/n1"), requests.map { it.line() })
    }

    /** Android's composer stays over the link's screen while it holds links (MainActivity.receive). */
    @Test
    fun testALinkWaitsForUnsentWork() {
        val composer = InviteComposer(machine, club)
        assertFalse("an invite not yet made gives way", composer.holdsLinks)
        composer.working = true
        assertTrue("one being made stays", composer.holdsLinks)
        composer.working = false
        composer.result = AdminShareResult(AdminInvite("i9", "Jo", "p9", "book-club", AdminInvite.sent, expires = now.plusSeconds(86_400)))
        assertTrue("its link shows only once", composer.holdsLinks)
        composer.linkUsed = true
        assertFalse("then the link goes on", composer.holdsLinks)
    }

    @Test
    fun testTheComposerWarnsOfANameAlreadyUsed() {
        val composer = InviteComposer(machine, club)
        composeRule.setContent { OvenlightTheme { InviteComposerDialog(composer, state(), rememberCoroutineScope(), onDismiss = {}) } }
        inDialog("They see this name on the invite, and Book Club knows them by it. You send the invite yourself next, by message or QR code. It works once, on one phone, for 7 days.")
            .assertIsDisplayed()
        composeRule.onNodeWithText("Their name, like Sam").performTextInput("sam")
        inDialog("Sam is already in the list below. Choose them there, or use a name that tells them apart, like Sam L.").assertIsDisplayed()
        inDialog("Create").assertIsNotEnabled()
        composeRule.onNodeWithText("sam").performTextReplacement("App Review")
        inDialog("App Review already has an invite. Use a name that tells them apart, like App Review L.").assertIsDisplayed()
        // Someone already shared with: App Review's unused review invite leaves it out.
        inDialog("Lee").assertIsDisplayed()
        inDialog("Invited").assertIsDisplayed()
        composeRule.checkAccessibility()
    }

    @Test
    fun testAnInviteForSomeoneNewCanBeSentAndCopiedOnce() {
        val link = "https://ovenlight.app/join#v=1&control=https%3A%2F%2Fcontrolplane.tailscale.com&key=tskey-auth-k1&owner=Dimitri&app=book-club&invite=i9&host=book-club.tail1.ts.net&name=Book+Club&to=Jo"
        answer = {
            FetchResponse(
                200,
                """{"invite":{"id":"i9","to":"Jo","person":"p9","app":"book-club","state":"sent","expires":"${now.plusSeconds(86_400 * 7)}"},"link":"$link","message":"I'm sharing Book Club with you. $link"}""".toByteArray(),
            )
        }
        val composer = InviteComposer(machine, club)
        composeRule.setContent { OvenlightTheme { InviteComposerDialog(composer, state(), rememberCoroutineScope(), onDismiss = {}) } }
        composeRule.onNodeWithText("Their name, like Sam").performTextInput("Jo ")
        inDialog("Jo sees this name on the invite, and Book Club knows them by it. You send the invite yourself next, by message or QR code. It works once, on one phone, for 7 days.")
            .assertIsDisplayed()
        inDialog("Create").performClick()
        composeRule.waitForIdle()
        assertEquals(listOf("Share Book Club with Jo"), confirmed)
        val sent = requests.single()
        assertEquals("POST /v1/invites", sent.line())
        val body = Json.parseToJsonElement(Buffer().also { sent.body!!.writeTo(it) }.readUtf8()).jsonObject
        assertEquals(mapOf("to" to "Jo", "app" to "book-club"), body.mapValues { it.value.jsonPrimitive.content })

        inDialog("Invite for Jo").assertIsDisplayed()
        composeRule.onNode(SemanticsMatcher.expectValue(SemanticsProperties.PaneTitle, "Invite for Jo")).assertExists()
        composeRule.onNodeWithContentDescription("Invite code").assertIsDisplayed()
        inDialog("Jo can join without your approval and reaches only Book Club. Until it’s used, the link works like a key, so send it privately.").assertIsDisplayed()
        inDialog("Send invite").performClick()
        val chooser = shadowOf(ApplicationProvider.getApplicationContext<android.app.Application>()).nextStartedActivity
        assertEquals(Intent.ACTION_CHOOSER, chooser.action)
        @Suppress("DEPRECATION") // getParcelableExtra(String, Class) is API 33.
        val send = chooser.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)!!
        assertEquals("the connector's message, which ends with the link", "I'm sharing Book Club with you. $link", send.getStringExtra(Intent.EXTRA_TEXT))
        assertEquals("Book Club in Ovenlight", send.getStringExtra(Intent.EXTRA_SUBJECT))
        assertNull("nothing but the text carries the link", send.data)

        inDialog("Copy link").performClick()
        inDialog("Copied").assertIsDisplayed()
        val clip = ApplicationProvider.getApplicationContext<Context>().getSystemService(ClipboardManager::class.java).primaryClip!!
        assertEquals(link, clip.getItemAt(0).text)
        assertTrue("marked sensitive, so Android's preview hides the key", clip.description.extras!!.getBoolean(ClipDescription.EXTRA_IS_SENSITIVE))
        assertEquals("one invite", 1, requests.size)
    }

    /** The screen opens where its place says, as after the lock took it apart. */
    @Test
    fun testTheScreenComesBackToItsPage() {
        val place = SharingPlace(SharingPage.List)
        place.pages += SharingPage.App(machine.id, "book-club")
        show(state(), place)
        composeRule.onNodeWithText("Share Book Club…").assertIsDisplayed()
        composeRule.runOnIdle { place.pages.removeAt(place.pages.lastIndex) }
        composeRule.onNodeWithText("Apps").assertIsDisplayed()
    }

    /** The link shows only once, so closing a new invite before it's sent or copied asks first. */
    @Test
    fun testClosingAnUnsentInviteAsksFirst() {
        val link = "https://ovenlight.app/join#v=1&invite=i9"
        answer = {
            FetchResponse(
                200,
                """{"invite":{"id":"i9","to":"Jo","person":"p9","app":"book-club","state":"sent","expires":"${now.plusSeconds(86_400)}"},"link":"$link","message":"$link"}""".toByteArray(),
            )
        }
        var dismissed = 0
        composeRule.setContent { OvenlightTheme { InviteComposerDialog(InviteComposer(machine, club), state(), rememberCoroutineScope(), onDismiss = { dismissed += 1 }) } }
        composeRule.onNodeWithText("Their name, like Sam").performTextInput("Jo")
        inDialog("Create").performClick()
        inDialog("Invite for Jo").assertIsDisplayed()
        val close = composeRule.onNodeWithContentDescription("Close")
        close.performClick()
        inDialog("Close without sending?").assertIsDisplayed()
        inDialog("This link won’t show again. The invite stays open until it expires, and you can cancel it in People & sharing.").assertIsDisplayed()
        inDialog("Keep").performClick()
        inDialog("Close without sending?").assertDoesNotExist()
        assertEquals(0, dismissed)
        close.performClick()
        inDialog("Close").performClick()
        assertEquals(1, dismissed)
        // Copied, the link is safe: closing goes at once.
        inDialog("Copy link").performClick()
        close.performClick()
        inDialog("Close without sending?").assertDoesNotExist()
        assertEquals(2, dismissed)
    }

    /**
     * The count is of feedback newer than the newest the inbox showed when last opened, with no
     * number once there's none new; the inbox pulls to refresh.
     */
    @Test
    fun testTheFeedbackCountShowsOnlyNewOnes() {
        val older = AdminFeedback("f0", now.minusSeconds(7_200), "book-club", "Kim", "guest", note = "The cover is blurry.")
        val two = machine.copy(feedback = machine.feedback + older)
        assertEquals("before the inbox was ever opened, all of it", 2, SharingList.newFeedback(two.feedback, seen = null))
        assertEquals(1, SharingList.newFeedback(two.feedback, seen = older.at))
        assertEquals(0, SharingList.newFeedback(two.feedback, seen = machine.feedback[0].at))
        var seen by mutableStateOf<Instant?>(older.at)
        val base = state(listOf(two))
        val place = SharingPlace(SharingPage.List)
        composeRule.setContent {
            OvenlightTheme { SharingScreen(base.copy(feedbackSeen = seen, seeFeedback = { seen = it }), place, onClose = {}, scope = rememberCoroutineScope()) }
        }
        val row = hasText("Feedback") and hasClickAction()
        composeRule.onNode(row and hasText("1")).performScrollTo().performClick()
        text("The cover is blurry.").assertIsDisplayed()
        assertEquals("opening it sees up to the newest", machine.feedback[0].at, seen)
        val before = refreshes
        composeRule.onNode(hasScrollAction()).performTouchInput { swipeDown() }
        composeRule.waitForIdle()
        assertTrue("it pulls to refresh", refreshes > before)
        composeRule.onNodeWithContentDescription("Back").performClick()
        composeRule.onNode(row).performScrollTo().assertIsDisplayed()
        composeRule.onNode(row and (hasText("1") or hasText("2"))).assertDoesNotExist()

        // One app's inbox, from its People page, sees up to its newest too, unless another app
        // has unseen feedback from before that newest, whose count moving the mark would lose.
        val earlier = AdminFeedback("f2", now.minusSeconds(3_600), "garden", "Kim", "guest", note = "Add the bean rows.")
        val later = AdminFeedback("f3", now.minusSeconds(600), "garden", "Kim", "guest", note = "And the peas.")
        val machines = base.machines as MutableStateFlow
        machines.value = listOf(two.copy(feedback = two.feedback + earlier))
        seen = older.at
        composeRule.runOnIdle { place.pages += SharingPage.Feedback(two.id, "book-club") }
        text("The cover is blurry.").assertIsDisplayed()
        assertEquals("Garden's earlier one is still new", older.at, seen)
        composeRule.onNodeWithContentDescription("Back").performClick()
        machines.value = listOf(two.copy(feedback = two.feedback + later))
        composeRule.runOnIdle { place.pages += SharingPage.Feedback(two.id, "book-club") }
        text("The cover is blurry.").assertIsDisplayed()
        assertEquals("Garden's later one stays new as the mark moves", machine.feedback[0].at, seen)
    }

    /**
     * Share says the phone's own connection is down before it asks for the screen lock, as Can't
     * reach does. A node that's retrying is up, and only its computers didn't answer: that's
     * the computer's side, so neither says so then.
     */
    @Test
    fun testCantReachSaysWhenThePhoneNeverConnected() {
        val detail = "Ovenlight couldn’t connect. Check that you’re online, then try again."
        val own = WebApp(name = "Book Club", startURL = URI("https://book-club.tail1.ts.net/"), discovered = true)
        assertEquals(detail, ConnectionCopy.cantReachDetail(own, neverConnected = true))
        assertEquals("a guest hears it too", detail, ConnectionCopy.cantReachDetail(own.copy(membershipID = "m1", sharedBy = "Riley"), neverConnected = true))
        CopyRules.assertNeutral(detail)
        assertTrue(AppLoadGate.neverConnected(NodeState.Starting))
        assertTrue(AppLoadGate.neverConnected(NodeState.Connecting))
        assertFalse("its computers didn't answer", AppLoadGate.neverConnected(NodeState.Retrying(3)))
        var owner by mutableStateOf<NodeState>(NodeState.Connecting)
        val composer = InviteComposer(machine, club)
        composeRule.setContent {
            OvenlightTheme { InviteComposerDialog(composer, state().copy(isAvailable = false, ownerState = owner), rememberCoroutineScope(), onDismiss = {}) }
        }
        composeRule.onNodeWithText("Their name, like Sam").performTextInput("Jo")
        inDialog("Create").performClick()
        inDialog(detail).assertIsDisplayed()
        assertEquals("no screen lock, and nothing sent", emptyList<String>(), confirmed)
        assertTrue(requests.isEmpty())
        owner = NodeState.Retrying(3)
        inDialog("Create").performClick()
        composeRule.waitForIdle()
        assertEquals("the screen lock", 1, confirmed.size)
        assertTrue("then the computer is asked", requests.isNotEmpty())
        inDialog(detail).assertDoesNotExist()
    }

    @Test
    fun testAScreenshotThatWontLoadSaysSo() {
        answer = { FetchResponse(404, """{"error":"no screenshot","code":"not_found"}""".toByteArray()) }
        show(state(), SharingPage.FeedbackItem(machine.id, "f1"))
        text("Couldn’t load the screenshot.").assertIsDisplayed()
        assertEquals(listOf("GET /v1/feedback/f1/screenshot"), requests.map { it.line() })
    }

    @Test
    fun testTheInviteCodeReadsBackAsItsLink() {
        val link = "https://ovenlight.app/join#v=1&key=tskey-auth-kLongEnoughToNeedAFewModules0123456789&owner=Dimitri&app=book-club&invite=i9&host=book-club.tail1.ts.net&to=Jo"
        val code = QRCode.image(link)!!
        // Scaled up and given a quiet zone, as the white plate draws it.
        val drawn = Bitmap.createBitmap(code.width * 10 + 80, code.height * 10 + 80, Bitmap.Config.ARGB_8888)
        Canvas(drawn).apply {
            drawColor(Color.WHITE)
            drawBitmap(Bitmap.createScaledBitmap(code, code.width * 10, code.height * 10, false), 40f, 40f, null)
        }
        val pixels = IntArray(drawn.width * drawn.height).also { drawn.getPixels(it, 0, drawn.width, 0, 0, drawn.width, drawn.height) }
        val read = QRCodeReader().decode(BinaryBitmap(HybridBinarizer(RGBLuminanceSource(drawn.width, drawn.height, pixels))))
        assertEquals(link, read.text)
        assertEquals("M", read.resultMetadata[com.google.zxing.ResultMetadataType.ERROR_CORRECTION_LEVEL])
    }

    @Test
    fun testScreenshotsAreDecodedSmall() {
        val image = Bitmap.createBitmap(3000, 2000, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.rgb(255, 165, 0)) }
        val png = ByteArrayOutputStream().also { image.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
        val thumbnail = IconDecoder.decode(png, ScreenshotCache.Size.Thumbnail.pixels)!!
        assertEquals(listOf(240, 160), listOf(thumbnail.width, thumbnail.height))
        assertNull(IconDecoder.decode("not an image".toByteArray(), 240))
    }

    /** Only owners see these screens, so they may name Tailscale; the rest of the copy rules hold. */
    @Test
    fun testSharingCopyKeepsTheCopyRules() {
        val resources = ApplicationProvider.getApplicationContext<Context>().resources
        val labels = R.string::class.java.fields.filter { field -> listOf("sharing_", "people_", "person_", "feedback_inbox_").any { field.name.startsWith(it) } }
        assertTrue(labels.size > 50)
        for (field in labels) {
            val text = resources.getString(field.getInt(null))
            assertTrue("no em or en dashes: $text", !text.contains('—') && !text.contains('–'))
            assertTrue("a guest's device is a phone, and the host a computer: $text", !text.contains("iPhone") && !text.contains("machine"))
        }
    }
}
