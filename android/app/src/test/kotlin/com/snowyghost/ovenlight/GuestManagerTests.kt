package com.snowyghost.ovenlight

import java.io.File
import java.net.URI
import java.nio.file.Files
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

private val echoInvite = InviteLink.parse(
    URI(
        "ovenlight://join?app=echo-board&control=https%3A%2F%2Fcontrolplane.tailscale.com&host=echo-board.taildef456.ts.net" +
            "&invite=3f9a1c2b7e&key=" + "tskey-auth-" + "kGuest1-NotReal" + "&name=Echo+Board&owner=Riley&to=Sam+Lee&v=1",
    ),
    allowLocalHTTP = false,
)
private const val claimURL = "https://echo-board.taildef456.ts.net/__ovenlight/claim"
private const val echoPeer = """"k2":{"HostName":"echo-board","DNSName":"echo-board.taildef456.ts.net.","OS":"linux","Online":true,"UserID":1}"""
private val guestRunning = status("Running", peers = echoPeer, suffix = "taildef456.ts.net")

private fun answer(status: Int, json: String, code: String? = null) =
    FetchResponse(status, json.toByteArray(), if (code == null) emptyMap() else mapOf(ConnectorError.header to code))

// Android only: iOS doesn't unit test its managers, and the plan asks for the join and claim flow.
@OptIn(ExperimentalCoroutinesApi::class)
class GuestManagerTests {
    private val folder: File = Files.createTempDirectory("guests").toFile()

    @After
    fun cleanUp() {
        folder.deleteRecursively()
    }

    private class Setup(val clients: FakeClients, val nodes: NodeManager, val registry: AppRegistry, val router: Router, val guests: GuestManager)

    private fun TestScope.setup(clients: FakeClients = FakeClients()): Setup {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val ovenlight = File(folder, "Ovenlight").apply { mkdirs() }
        val nodes = NodeManager(
            clients, MemoryStore(), MembershipStore(ovenlight), File(folder, "tailscale"),
            backgroundScope, dispatcher, { testScheduler.currentTime }, {},
        )
        val registry = offlineRegistry(ovenlight)
        val router = Router()
        val guests = GuestManager(nodes, registry, router, backgroundScope, { testScheduler.currentTime }, {})
        return Setup(clients, nodes, registry, router, guests)
    }

    @Test
    fun testAnInviteJoinsClaimsAndAddsTheApp() = runTest {
        val s = setup()
        s.clients.anyStatus = guestRunning
        s.clients.respond = { request ->
            if (request.url.toString() == claimURL) answer(200, """{"app":"echo-board","appName":"Echo","owner":"Riley Chen"}""") else null
        }
        s.guests.accept(echoInvite)

        val app = s.registry.apps.single()
        assertEquals("Echo", app.name)
        assertEquals("Riley Chen", app.sharedBy)
        assertEquals("echo-board.taildef456.ts.net", app.host)
        val membership = s.nodes.memberships.memberships.single()
        assertEquals(membership.id, app.membershipID)
        assertTrue(membership.joined)
        assertEquals("the invite stops waiting", emptyList<PendingClaim>(), membership.pendingClaims)
        assertEquals("Riley Chen", membership.ownerName)
        assertEquals("Sam Lee", membership.guestName)
        assertEquals(JoinPhase.Joined(app.id), s.guests.phases.value[echoInvite.invite])

        val claim = s.clients.requests.first { it.url.toString() == claimURL }
        assertEquals("POST", claim.method)
        assertEquals("""{"key":"${echoInvite.key}"}""", Buffer().also { claim.body!!.writeTo(it) }.readUtf8())
        assertTrue("on the membership's own route", s.clients.fetched.filter { it.second == claimURL }.all { it.first == "node-${membership.id}" })
    }

    @Test
    fun testAConnectorThatDoesntKnowTheDeviceYetIsAskedAgain() = runTest {
        val s = setup()
        s.clients.anyStatus = guestRunning
        var claims = 0
        s.clients.respond = { request ->
            if (request.url.toString() != claimURL) {
                null
            } else if (++claims < 3) {
                answer(403, """{"error":"unknown caller","code":"unknown_caller"}""")
            } else {
                answer(200, """{}""")
            }
        }
        s.guests.accept(echoInvite)
        assertEquals(3, claims)
        assertEquals("the invite's names stand in for the answer's", listOf("Echo Board"), s.registry.apps.map { it.name })
    }

    @Test
    fun testAUsedKeyFailsAndLeavesNothingBehind() = runTest {
        val s = setup()
        s.clients.anyStatus = status("NeedsLogin", suffix = "")
        s.clients.upError = "tsnet.Up: backend: authkey already used"
        s.guests.accept(echoInvite)

        assertEquals(JoinPhase.Failed(JoinError.KeyUsed), s.guests.phases.value[echoInvite.invite])
        assertEquals("the membership leaves with its last invite", emptyList<Membership>(), s.nodes.memberships.memberships)
        assertTrue(s.registry.apps.isEmpty())
        assertTrue(s.clients.nodes.all { it.closed })
        assertFalse("its state is deleted", File(folder, "tailscale").listFiles().orEmpty().any())
        assertTrue("nothing was claimed", s.clients.requests.none { it.url.toString() == claimURL })
    }

    @Test
    fun testANetworkFailureKeepsTheInviteForTryAgain() = runTest {
        val s = setup()
        s.clients.anyStatus = guestRunning
        var claims = 0
        s.clients.respond = { request -> if (request.url.toString() == claimURL) answer(503, "busy").also { claims++ } else null }
        val started = testScheduler.currentTime
        s.guests.accept(echoInvite)

        assertEquals(JoinPhase.Failed(JoinError.Network), s.guests.phases.value[echoInvite.invite])
        assertTrue("it kept trying for the claim's time", testScheduler.currentTime - started >= Claim.claimTime.inWholeMilliseconds)
        assertTrue(claims > 3)
        val membership = s.nodes.memberships.memberships.single()
        assertEquals("kept for Try Again", listOf(echoInvite.invite), membership.pendingClaims.map { it.invite.invite })
        assertEquals(echoInvite.invite, s.guests.membershipID(echoInvite.invite)?.let { s.nodes.memberships[it]?.pendingClaims?.single()?.invite?.invite })

        s.clients.respond = { request -> if (request.url.toString() == claimURL) answer(200, """{"appName":"Echo"}""") else null }
        s.guests.retry(echoInvite.invite)
        assertEquals(listOf("Echo"), s.registry.apps.map { it.name })
        assertTrue(s.guests.phases.value[echoInvite.invite] is JoinPhase.Joined)
    }

    @Test
    fun testAKeyFromAnotherNetworkLogsOutWithoutClaiming() = runTest {
        val s = setup()
        s.clients.anyStatus = status("Running", suffix = "tail0000.ts.net")
        s.guests.accept(echoInvite)

        assertEquals(JoinPhase.Failed(JoinError.WrongNetwork), s.guests.phases.value[echoInvite.invite])
        assertTrue("this join registered the node, so it logs out", s.clients.nodes.single().loggedOut)
        assertTrue(s.clients.requests.none { it.url.toString() == claimURL })
        assertTrue(s.nodes.memberships.memberships.isEmpty())
    }

    @Test
    fun testTheConnectorsRefusalIsFinal() = runTest {
        val s = setup()
        s.clients.anyStatus = guestRunning
        s.clients.respond = { request ->
            if (request.url.toString() == claimURL) answer(403, """{"error":"used","code":"used_elsewhere"}""") else null
        }
        s.guests.accept(echoInvite)
        assertEquals(JoinPhase.Failed(JoinError.UsedOnAnotherDevice), s.guests.phases.value[echoInvite.invite])
        assertEquals("asked once", 1, s.clients.requests.count { it.url.toString() == claimURL })
        assertTrue(s.nodes.memberships.memberships.isEmpty())
    }

    @Test
    fun testCancelingWhileClaimingAddsNothing() = runTest {
        val s = setup()
        s.clients.anyStatus = guestRunning
        s.clients.respond = { request -> if (request.url.toString() == claimURL) answer(503, "busy") else null }
        val accepting = backgroundScope.launch { s.guests.accept(echoInvite) }
        advanceTimeBy(3_000)
        assertEquals(JoinPhase.Claiming, s.guests.phases.value[echoInvite.invite])
        s.clients.respond = { request -> if (request.url.toString() == claimURL) answer(200, "{}") else null }
        s.guests.cancel(echoInvite.invite)
        advanceTimeBy(30_000)
        assertTrue(accepting.isCompleted)
        assertTrue(s.registry.apps.isEmpty())
        assertNull(s.guests.phases.value[echoInvite.invite])
        assertTrue("left with nothing, the membership goes", s.nodes.memberships.memberships.isEmpty())
    }

    @Test
    fun testAnInviteForTheOwnersOwnTailnetIsTheirOwnApp() = runTest {
        val s = setup()
        s.clients.status[Membership.ownerID] = status(
            "Running",
            self = """"Self":{"HostName":"phone","DNSName":"pixel.taildef456.ts.net.","OS":"android","Online":true,"UserID":1},""",
            suffix = "taildef456.ts.net",
        )
        s.nodes.enableOwner()
        advanceTimeBy(1_500)
        assertEquals(Preflight.OwnApp, s.guests.preflight(echoInvite))
        s.guests.accept(echoInvite)
        assertEquals(JoinPhase.Failed(JoinError.OwnApp), s.guests.phases.value[echoInvite.invite])
        assertTrue(s.nodes.memberships.memberships.isEmpty())
    }

    @Test
    fun testTheOwnerStoppingSharingTakesTheAppAway() = runTest {
        val s = setup()
        s.clients.anyStatus = guestRunning
        s.clients.respond = { request -> if (request.url.toString() == claimURL) answer(200, "{}") else null }
        s.guests.accept(echoInvite)
        val app = s.registry.apps.single()
        s.router.open(app)
        s.router.appAppeared()

        // The connector now says this device isn't invited, which the node's next look finds.
        s.clients.respond = { request ->
            if (request.url.encodedPath == "/__ovenlight/whoami") answer(403, """{"error":"not invited","code":"not_invited"}""") else null
        }
        advanceTimeBy(5_000)
        assertNull("the app closes", s.router.openApp.value)
        assertTrue(s.registry.apps.isEmpty())
        assertTrue("its membership leaves with it", s.nodes.memberships.memberships.isEmpty())
        assertTrue(s.clients.nodes.single().loggedOut)
        assertNull("the notice waits for the app to go", s.guests.notice.value)
        s.router.appDismissed()
        assertEquals(listOf("Echo Board"), s.guests.notice.value?.apps)
        assertEquals("Riley", s.guests.notice.value?.owner)
    }
}
