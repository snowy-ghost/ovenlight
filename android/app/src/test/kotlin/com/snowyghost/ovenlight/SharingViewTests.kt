package com.snowyghost.ovenlight

import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasAnyAncestor
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
                AdminGuest("n1", "p1", "Sam", "book-club", "ovenlight-4k2x9q", claimedAt = ago(120), lastOpened = ago(64)),
                AdminGuest("n4", "p1", "Sam", "book-club", "ovenlight-m7c1pw", claimedAt = ago(63), lastOpened = now),
                AdminGuest("n2", "p2", "Kim", "book-club", "ovenlight-r5j8de", claimedAt = ago(30), lastOpened = ago(1)),
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

    private fun show(state: SharingState, root: SharingPage = SharingPage.List) {
        composeRule.setContent { OvenlightTheme { SharingScreen(state, root, onClose = {}, scope = rememberCoroutineScope()) } }
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
        inDialog("Sam can't open Book Club anymore, on any device. Each of their devices is also removed from your Tailscale network unless it has another of your apps.")
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
        composeRule.onNodeWithText("People").assertDoesNotExist()
    }

    @Test
    fun testSelectingPeopleRemovesSeveralButNeverAppReview() {
        val reviewed = machine.copy(
            guests = machine.guests.copy(guests = machine.guests.guests + AdminGuest("n9", "p4", "App Review", "book-club", "ovenlight-x", claimedAt = ago(2))),
        )
        show(state(listOf(reviewed)), SharingPage.People)
        // App Review joined last and opened nothing since, so it's last; its open review invite keeps it out of a selection.
        text("Those who opened your apps most recently come first. Removing someone also removes their devices from your Tailscale network.").assertIsDisplayed()
        composeRule.onNodeWithText("Kim").performTouchInput { longClick() }
        composeRule.onNodeWithText("1 selected").assertIsDisplayed()
        composeRule.onNodeWithText("Sam").performClick()
        composeRule.onNodeWithText("App Review").performClick()
        composeRule.onNodeWithText("2 selected").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Remove").performClick()
        inDialog("Remove 2 people?").assertIsDisplayed()
        inDialog("They can't open your apps anymore, on any device, and their devices are removed from your Tailscale network.").assertIsDisplayed()
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
        inDialog("Sam can't open your apps on this device anymore, and it's removed from your Tailscale network.").assertIsDisplayed()
        inDialog("Remove").performClick()
        composeRule.waitForIdle()
        assertEquals(listOf("Remove Sam's device"), confirmed)
        assertEquals("the device that joined first", listOf("DELETE /v1/guests/n1"), requests.map { it.line() })
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
        composeRule.onNodeWithContentDescription("Invite code").assertIsDisplayed()
        inDialog("Jo can join without your approval and reaches only Book Club. Until it's used, the link works like a key, so send it privately.").assertIsDisplayed()
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
