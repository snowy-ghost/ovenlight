package com.snowyghost.ovenlight

import java.io.File
import java.net.URI
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import okio.Buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

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
private val guideInvite = echoInvite.copy(
    app = "house-guide", invite = "5c7e9a1b3d", host = "house-guide.taildef456.ts.net", name = "House Guide",
    key = "tskey-auth-" + "kGuest0-NotReal", to = "Sam",
)
private const val guideClaimURL = "https://house-guide.taildef456.ts.net/__ovenlight/claim"
private const val guidePeer = """"k1":{"HostName":"house-guide","DNSName":"house-guide.taildef456.ts.net.","OS":"linux","Online":true,"UserID":1}"""

private fun answer(status: Int, json: String, code: String? = null) =
    FetchResponse(status, json.toByteArray(), if (code == null) emptyMap() else mapOf(ConnectorError.header to code))

// Android only: iOS doesn't unit test its managers, and the plan asks for the join and claim flow.
@OptIn(ExperimentalCoroutinesApi::class)
class GuestManagerTests {
    @get:Rule
    val temp = TemporaryFolder()
    private val folder: File get() = temp.root

    private class Setup(val clients: FakeClients, val nodes: NodeManager, val registry: AppRegistry, val router: Router, val guests: GuestManager)

    private fun TestScope.setup(clients: FakeClients = FakeClients(), online: () -> Boolean = { true }): Setup {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val ovenlight = File(folder, "Ovenlight").apply { mkdirs() }
        val nodes = NodeManager(
            clients, MemoryStore(), MembershipStore(ovenlight), File(folder, "tailscale"),
            backgroundScope, dispatcher, { testScheduler.currentTime }, {},
        )
        val registry = offlineRegistry(ovenlight)
        val router = Router()
        val guests = GuestManager(nodes, registry, router, backgroundScope, { testScheduler.currentTime }, {}, isOnline = online)
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
        assertTrue("it kept trying for the claim's budget", testScheduler.currentTime - started >= Claim.budget.inWholeMilliseconds)
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
        assertEquals(ConnectionCopy.noLongerShared(listOf("Echo Board"), "Riley"), s.guests.notice.value?.message)
    }

    /** A join that fails for good with its screen closed says why once the screens have closed; with its screen open, the screen does. */
    @Test
    fun testAJoinThatFailsWithItsScreenClosedSaysWhy() = runTest {
        val s = setup()
        s.clients.anyStatus = status("NeedsLogin", suffix = "")
        s.clients.upError = "tsnet.Up: backend: authkey already used"
        s.router.present(RootSheet.Join(JoinRequest(Result.success(echoInvite))))
        s.router.sheetAppeared()
        s.guests.accept(echoInvite)
        assertEquals(JoinPhase.Failed(JoinError.KeyUsed), s.guests.phases.value[echoInvite.invite])
        assertNull("its screen says so itself", s.guests.notice.value)

        s.router.sheet.value = null
        s.router.sheetDismissed()
        s.guests.accept(echoInvite)
        assertEquals("This invite was already used", s.guests.notice.value?.title)
        assertEquals("Each invite works once. Ask Riley for a new one.", s.guests.notice.value?.message)
    }

    /** With no network, a join says so at once rather than after the wait, keeps the invite, and goes on once there is one. */
    @Test
    fun testJoiningOfflineFailsAtOnce() = runTest {
        var online = false
        val s = setup(online = { online })
        s.clients.anyStatus = guestRunning
        s.clients.respond = { request -> if (request.url.toString() == claimURL) answer(200, "{}") else null }
        val started = testScheduler.currentTime
        s.guests.accept(echoInvite)
        assertEquals(JoinPhase.Failed(JoinError.Network), s.guests.phases.value[echoInvite.invite])
        assertTrue("at once", testScheduler.currentTime - started < 1_000)
        assertEquals("kept for Try again", listOf(echoInvite.invite), s.nodes.memberships.memberships.single().pendingClaims.map { it.invite.invite })
        assertTrue(s.registry.apps.isEmpty())

        online = true
        s.guests.resumePending()
        advanceTimeBy(5_000)
        assertEquals(JoinPhase.Joined(s.registry.apps.single().id), s.guests.phases.value[echoInvite.invite])
    }

    /**
     * After Leave the same invite asks to join again: one still waiting forgets how its join
     * went, and one that joined points at an app that's gone, which the join screen asks again
     * for (testJoiningThenOpeningTheApp). Then it joins again.
     */
    @Test
    fun testJoiningAgainAfterLeavingAsksAgain() = runTest {
        var online = true
        val s = setup(online = { online })
        s.clients.anyStatus = guestRunning
        s.clients.respond = { request -> if (request.url.toString() == claimURL) answer(200, "{}") else null }
        s.guests.accept(echoInvite)
        assertEquals(JoinPhase.Joined(s.registry.apps.single().id), s.guests.phases.value[echoInvite.invite])
        s.guests.leave(s.nodes.memberships.memberships.single().id)
        val joined = s.guests.phases.value[echoInvite.invite] as JoinPhase.Joined
        assertTrue("its app is gone", s.registry.apps.none { it.id == joined.app })

        online = false
        s.guests.accept(echoInvite)
        assertEquals(JoinPhase.Failed(JoinError.Network), s.guests.phases.value[echoInvite.invite])
        s.guests.leave(s.nodes.memberships.memberships.single().id)
        assertNull(s.guests.phases.value[echoInvite.invite])

        online = true
        s.guests.accept(echoInvite)
        assertEquals("it joins again", JoinPhase.Joined(s.registry.apps.single().id), s.guests.phases.value[echoInvite.invite])
    }

    /**
     * The owner removing a joined device drops its invites still waiting, and how their joins
     * went: one opened again asks to join, not Try Again with nothing left to try.
     */
    @Test
    fun testALostLoginClearsItsJoinPhases() = runTest {
        val s = setup()
        val notesInvite = echoInvite.copy(app = "notes", invite = "8b2d4e6f1a", host = "notes.taildef456.ts.net", name = "Notes", key = "tskey-auth-" + "kGuest2-NotReal")
        val notesPeer = """"k3":{"HostName":"notes","DNSName":"notes.taildef456.ts.net.","OS":"linux","Online":true,"UserID":1}"""
        s.clients.anyStatus = status("Running", peers = "$echoPeer,$notesPeer", suffix = "taildef456.ts.net")
        s.clients.respond = { request ->
            when (request.url.toString()) {
                claimURL -> answer(200, "{}")
                "https://notes.taildef456.ts.net/__ovenlight/claim" -> answer(503, "busy")
                else -> null
            }
        }
        s.guests.accept(echoInvite)
        s.guests.accept(notesInvite)
        assertEquals(JoinPhase.Failed(JoinError.Network), s.guests.phases.value[notesInvite.invite])

        s.clients.anyStatus = status("NeedsLogin", suffix = "")
        advanceTimeBy(30_000)
        assertTrue("the owner removed this device", s.registry.apps.isEmpty())
        assertNull(s.guests.phases.value[notesInvite.invite])
    }

    /** Sam's phone, joined for House Guide. Echo Board's invite names "Sam Lee", so the node can't reach Echo Board yet. */
    private suspend fun TestScope.joinedForHouseGuide(): Setup {
        val s = setup()
        s.clients.anyStatus = status("Running", peers = guidePeer, suffix = "taildef456.ts.net")
        s.clients.respond = { request -> if (request.url.toString() == guideClaimURL) answer(200, "{}") else null }
        s.guests.accept(guideInvite)
        s.clients.requests.clear()
        return s
    }

    /** The claim goes through House Guide, and Echo Board is added once the connector's retag brings it among the peers. */
    @Test
    fun testAnInviteUnderANewNameIsClaimedThroughAnotherApp() = runTest {
        val s = joinedForHouseGuide()
        s.clients.respond = { request ->
            if (request.url.toString() == guideClaimURL) {
                backgroundScope.launch {
                    delay(4_000)
                    s.clients.anyStatus = status("Running", peers = "$guidePeer,$echoPeer", suffix = "taildef456.ts.net")
                }
                answer(200, """{"app":"echo-board","appName":"Echo","owner":"Riley"}""")
            } else {
                null
            }
        }
        val started = testScheduler.currentTime
        s.guests.accept(echoInvite)

        val claim = s.clients.requests.single { it.url.encodedPath == Claim.path }
        assertEquals(guideClaimURL, claim.url.toString())
        assertEquals("""{"key":"${echoInvite.key}"}""", Buffer().also { claim.body!!.writeTo(it) }.readUtf8())
        assertTrue("added once Echo Board is among the peers", testScheduler.currentTime - started in 4_000..5_000)
        val app = s.registry.apps.single { it.host == echoInvite.host }
        assertEquals("Echo", app.name)
        assertEquals(JoinPhase.Joined(app.id), s.guests.phases.value[echoInvite.invite])
        val membership = s.nodes.memberships.memberships.single()
        assertEquals(membership.id, app.membershipID)
        assertEquals("Sam Lee", membership.guestName)
        assertEquals(emptyList<PendingClaim>(), membership.pendingClaims)
    }

    /**
     * With no other app answering, the claim goes to the invite's own host as before. Claimed
     * through another, the app waits half a minute at most for its host to appear.
     */
    @Test
    fun testAClaimThroughAnotherAppWaitsForItsHost() = runTest {
        val s = joinedForHouseGuide()
        s.clients.respond = { null }
        s.guests.accept(echoInvite)
        assertEquals(JoinPhase.Failed(JoinError.Network), s.guests.phases.value[echoInvite.invite])
        val claims = s.clients.requests.filter { it.url.encodedPath == Claim.path }.map { it.url.toString() }
        assertEquals(guideClaimURL, claims.first())
        assertTrue("then the invite's own host, until the budget ends", claims.size > 2 && claims.drop(1).all { it == claimURL })

        s.clients.respond = { request -> if (request.url.toString() == guideClaimURL) answer(200, "{}") else null }
        val started = testScheduler.currentTime
        s.guests.retry(echoInvite.invite)
        assertTrue(testScheduler.currentTime - started in 30_000..31_000)
        val app = s.registry.apps.single { it.host == echoInvite.host }
        assertEquals(JoinPhase.Joined(app.id), s.guests.phases.value[echoInvite.invite])
    }

    /** A shared app's tile follows the name its connector gives it now, unless the guest renamed it. */
    @Test
    fun testASharedAppTakesItsNewName() = runTest {
        val s = setup()
        s.clients.anyStatus = guestRunning
        var appName = "Echo Board"
        s.clients.respond = { request ->
            when (request.url.encodedPath) {
                "/__ovenlight/claim" -> answer(200, "{}")
                "/__ovenlight/whoami" -> answer(200, """{"role":"guest","app":"echo-board","appName":"$appName","owner":"Riley"}""")
                else -> null
            }
        }
        s.guests.accept(echoInvite)
        advanceTimeBy(5_000)
        val app = s.registry.apps.single()
        appName = "Echo Live"
        s.nodes.rediscover()
        assertEquals("Echo Live", s.registry.apps.single().name)
        s.registry.rename(app.id, "Our Board")
        appName = "Echo Two"
        s.nodes.rediscover()
        assertEquals("a name the guest gave it stays", "Our Board", s.registry.apps.single().name)
    }
}
