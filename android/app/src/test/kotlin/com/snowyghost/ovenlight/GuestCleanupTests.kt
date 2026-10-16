package com.snowyghost.ovenlight

import java.net.URI
import java.time.Instant
import okio.Buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GuestCleanupTests {
    private val echo = "echo-board.taildef456.ts.net"
    private val notes = "notes.taildef456.ts.net"

    private fun membership(id: String, claiming: List<String> = emptyList(), joined: Boolean = true): Membership {
        val m = Membership(
            InviteLink(control = URI("https://c.example"), key = "k", owner = "Riley", app = "a", invite = "i", host = echo, name = "Echo", to = "Sam"),
            id = id,
        )
        return m.copy(
            joined = joined,
            pendingClaims = claiming.mapIndexed { index, host ->
                PendingClaim(
                    InviteLink(
                        control = m.controlURL, key = "k$index", owner = "Riley", app = "a", invite = "i$index",
                        host = host, name = "App", to = "Sam",
                    ),
                    Instant.now(),
                )
            },
        )
    }

    private fun app(host: String, membershipID: String?) = WebApp(name = host, startURL = URI("https://$host/"), membershipID = membershipID)

    @Test
    fun testOrphansHaveNoAppsAndNoInviteWaiting() {
        val memberships = listOf(membership("a"), membership("b", claiming = listOf(notes)), membership("c"))
        val apps = listOf(app(echo, "a"), app("coach.tailabc123.ts.net", null))
        assertEquals(listOf("c"), GuestCleanup.orphans(memberships, apps))
    }

    @Test
    fun testRevokesOnlyClaimedAppsTheConnectorRefused() {
        val m = membership("a", claiming = listOf(notes))
        val apps = listOf(app(echo, "a"), app(notes, "a"), app("other.taildef456.ts.net", "a"), app(echo, "b"))
        val revoked = GuestCleanup.revokedApps(m, apps, notInvited = setOf(echo, notes))
        assertEquals("not the app being claimed, not another membership's", listOf(echo), revoked.map { it.host })
        assertEquals(
            "hosts arrive normalized from the probe",
            0,
            GuestCleanup.revokedApps(m, apps, notInvited = setOf("Echo-Board.taildef456.ts.net")).size,
        )
    }

    @Test
    fun testLostLoginMeansRemovedOnlyAfterJoining() {
        assertTrue(GuestCleanup.isRemoved(membership("a")))
        assertTrue("a second invite in progress doesn't hide the removal", GuestCleanup.isRemoved(membership("a", claiming = listOf(notes))))
        assertFalse("a first join passes through NeedsLogin", GuestCleanup.isRemoved(membership("a", joined = false)))
    }

    @Test
    fun testWhoamiAndClaimAnswers() {
        fun refusal(code: String, words: String = "refused") = """{"error":"$words","code":"$code"}""".toByteArray()
        assertEquals(Reach.Reached, GuestProbe.classify(403, header = "unknown_caller", body = "Unknown caller.".toByteArray()))
        assertEquals(Reach.Reached, GuestProbe.classify(200, body = """{"role":"guest"}""".toByteArray()))
        assertEquals("any answer proves the path", Reach.Reached, GuestProbe.classify(502, body = ByteArray(0)))

        val ok = """{"name":"Sam","userId":"guest:7","app":"echo-board","appName":"Echo Board","owner":"Riley"}""".toByteArray()
        assertEquals(Claim.Step.Claimed(ClaimResponse(app = "echo-board", appName = "Echo Board", owner = "Riley")), Claim.interpret(200, body = ok))
        assertEquals(
            "the connector hasn't seen the device yet",
            Claim.Step.Retry,
            Claim.interpret(403, header = "unknown_caller", body = "Unknown caller.".toByteArray()),
        )
        assertEquals(Claim.Step.Retry, Claim.interpret(500, body = refusal("internal")))
        assertEquals(Claim.Step.Retry, Claim.interpret(500, body = ByteArray(0)))
        assertEquals(Claim.Step.Retry, Claim.interpret(200, body = "<html>".toByteArray()))

        // Each refusal is named by its code, in the JSON or the header, whatever the words.
        assertEquals(Claim.Step.Failed(JoinError.UsedOnAnotherDevice), Claim.interpret(403, body = refusal("used_elsewhere")))
        assertEquals(Claim.Step.Failed(JoinError.SomeoneElses), Claim.interpret(403, body = refusal("other_person", "not invited: another guest")))
        assertEquals(Claim.Step.Failed(JoinError.NotInvited), Claim.interpret(403, body = refusal("not_invited")))
        assertEquals(Claim.Step.Failed(JoinError.NotInvited), Claim.interpret(400, body = refusal("bad_request")))
        assertEquals(Claim.Step.Failed(JoinError.NotInvited), Claim.interpret(404, header = "not_found", body = "404 page not found".toByteArray()))
        assertEquals(Claim.Step.Retry, Claim.interpret(403, body = refusal("rate_limited", "not invited")))
        assertEquals(Claim.Step.Failed(JoinError.UsedOnAnotherDevice), Claim.interpret(403, header = "used_elsewhere", body = "Refused.".toByteArray()))
        assertEquals(Reach.NotInvited, GuestProbe.classify(403, header = "not_invited", body = "Refused.".toByteArray()))
        assertEquals(Reach.Reached, GuestProbe.classify(403, header = "owner_only", body = "You're not invited.".toByteArray()))

        // Without a code this version knows, nothing ends access or deletes anything, whatever
        // the words say: the answer is an unknown failure, tried again.
        for (words in listOf(
            refusal("from_the_future", "not invited"), """{"error":"not invited"}""".toByteArray(),
            "You're not invited to this app.\n".toByteArray(),
            """{"error":"not invited: this device already belongs to another guest"}""".toByteArray(),
            """{"error":"this invite was already used by another device"}""".toByteArray(),
        )) {
            for (status in listOf(400, 403, 404)) {
                assertEquals("$status ${words.decodeToString()}", Claim.Step.Retry, Claim.interpret(status, body = words))
                assertEquals("$status ${words.decodeToString()}", Reach.Reached, GuestProbe.classify(status, body = words))
            }
        }
        assertEquals(ConnectorError.OwnerOnly, ConnectorError(null, refusal("owner_only")))
        assertEquals("for the owner only", AdminAPI.errorMessage(refusal("owner_only", "for the owner only")))

        val request = Claim.request(
            InviteLink(control = URI("https://c.example"), key = "tskey-1", owner = "R", app = "a", invite = "i", host = echo, name = "E", to = ""),
        )
        assertEquals("https://$echo/__ovenlight/claim", request.url.toString())
        assertEquals("POST", request.method)
        val body = Buffer().also { request.body!!.writeTo(it) }.readUtf8()
        assertEquals(mapOf("key" to "tskey-1"), ovenlightJSON.decodeFromString<Map<String, String>>(body))
    }

    @Test
    fun testJoinErrorsFromTheControlServer() {
        assertEquals(JoinError.KeyUsed, JoinError(upMessage = "tsnet.Up: backend: authkey already used"))
        assertEquals(JoinError.Expired, JoinError(upMessage = "tsnet.Up: backend: authkey expired"))
        assertEquals(JoinError.KeyRefused, JoinError(upMessage = "tsnet.Up: backend: invalid key: unable to validate API key"))
        assertEquals(JoinError.Canceled, JoinError(upMessage = "tsnet.Up: use of closed network connection"))
        assertEquals(JoinError.Network, JoinError(upMessage = "dial tcp: connection refused"))
        assertTrue(JoinError.Network.isRetryable)
        assertFalse(JoinError.KeyUsed.isRetryable)
    }

    @Test
    fun testCancelingStopsOnlyTheNodeRegisteringWithThatInvite() {
        val m = membership("a", claiming = listOf(echo, notes), joined = false)
        assertTrue("the first waiting invite's key registers the node", GuestCleanup.isRegistering(m, with = "i0"))
        assertFalse("a queued invite leaves the node alone", GuestCleanup.isRegistering(m, with = "i1"))
        assertFalse("a joined node keeps the owner's apps", GuestCleanup.isRegistering(membership("a", claiming = listOf(echo)), with = "i0"))
    }

    @Test
    fun testClaimAnswerNamesAreCleanedLikeTheInvites() {
        val invite = InviteLink(
            control = URI("https://c.example"), key = "k", owner = "Riley", app = "echo-board", invite = "i",
            host = echo, name = "Echo Board", to = "Sam",
        )
        val long = "x".repeat(100)
        val names = ClaimResponse(app = " echo\u0007", appName = long, owner = "\nRi\u0000ley ").names(invite)
        assertEquals("Riley", names.owner)
        assertEquals(InviteLink.maxLabel, names.appName.graphemeCount())
        assertEquals("echo", names.app)
        val blank = ClaimResponse(app = null, appName = "  ", owner = "").names(invite)
        assertEquals("the invite's where the answer has none", listOf("Riley", "Echo Board", "echo-board"), listOf(blank.owner, blank.appName, blank.app))
    }

    /** A guest node's status, as the node reports it, with these peers online or not. */
    private fun status(backend: String = "Running", peers: Map<String, Boolean>): TailnetStatus {
        val list = peers.entries.joinToString(",") { """"${it.key}":{"DNSName":"${it.key}.","Online":${it.value}}""" }
        return TailnetStatus.decode("""{"BackendState":"$backend","Peer":{$list}}""")!!
    }

    @Test
    fun testAnAppNodeGoneFromThePeersIsNoLongerShared() {
        val hosts = setOf(echo, notes)
        assertEquals("offline is still listed", emptySet<String>(), GuestProbe.absentHosts(hosts, status(peers = mapOf(echo to true, notes to false))))
        assertEquals(setOf(notes), GuestProbe.absentHosts(hosts, status(peers = mapOf(echo to true))))
        assertNull("every app gone is a partial list, not a revoke", GuestProbe.absentHosts(hosts, status(peers = emptyMap())))
        assertNull(GuestProbe.absentHosts(hosts, status("Starting", peers = mapOf(echo to true))))

        var absence = PeerAbsence()
        repeat(PeerAbsence.pollsToEnd - 1) { assertEquals(emptySet<String>(), absence.record(setOf(notes))) }
        assertEquals("missing for every poll of a few minutes", setOf(notes), absence.record(setOf(notes)))
        assertEquals("again while still missing, for a host skipped while being claimed", setOf(notes), absence.record(setOf(notes)))

        absence = PeerAbsence()
        repeat(PeerAbsence.pollsToEnd - 1) { absence.record(setOf(notes)) }
        absence.record(emptySet())
        assertEquals("back in the list starts over", emptySet<String>(), absence.record(setOf(notes)))
    }

    /** A sleeping computer's apps aren't probed, so their tiles say Offline at once rather than after every probe times out. */
    @Test
    fun testAppsOnAComputerListedOfflineArentProbed() {
        val hosts = listOf(echo, "Notes.taildef456.ts.net.", echo)
        fun probed(peers: Map<String, Boolean>, onlineReported: Boolean = true) =
            GuestProbe.hostsToProbe(hosts, status(peers = peers), onlineReported)
        assertEquals(listOf(echo), probed(mapOf(echo to true, notes to false)))
        assertEquals("one not listed yet is probed", listOf(echo, notes), probed(mapOf(echo to true)))
        assertEquals(listOf(echo, notes), GuestProbe.hostsToProbe(hosts, null, onlineReported = true))
        assertEquals(emptyList<String>(), probed(mapOf(echo to false, notes to false)))

        // A network that never said a peer is online may just not tell this node: every app is
        // probed, as in 1.0.1, so ended access is still noticed.
        assertFalse(GuestProbe.reportsOnline(status(peers = mapOf(echo to false, notes to false))))
        assertEquals(listOf(echo, notes), probed(mapOf(echo to false, notes to false), onlineReported = false))
        assertTrue(GuestProbe.reportsOnline(status(peers = mapOf(echo to true, notes to false))))
        assertEquals(
            setOf("Echo-Board.taildef456.ts.net"),
            Discovery.offlineHosts(setOf("Echo-Board.taildef456.ts.net"), status(peers = mapOf(echo to false))),
        )

        val answers = mutableMapOf<String, Boolean>() // what a node records when nothing was left to probe
        val tile = AppReachability.forTile(echo, NodeState.Ready, answers)
        assertEquals(AppReachability.Offline, tile)
        answers[echo] = true
        assertNull(AppReachability.forTile(echo, NodeState.Ready, answers))
    }

    @Test
    fun testGuestsNeverGetSignInOrOwnerApprovalCopy() {
        val shared = app(echo, "a").copy(sharedBy = "Riley")
        val login = NodeState.NeedsLogin(URI("https://login.example/a"))
        assertNull(NodeActionOverlay.action(login, shared, ownerEnabled = false))
        assertNull(NodeActionOverlay.action(NodeState.Idle, shared, ownerEnabled = false))
        assertNull(NodeActionOverlay.action(NodeState.AwaitingApproval, shared, ownerEnabled = true))
        val own = app("coach.tailabc123.ts.net", null)
        assertEquals(NodeActionOverlay.Action.SignIn, NodeActionOverlay.action(login, own, ownerEnabled = true))
        assertEquals(NodeActionOverlay.Action.SignIn, NodeActionOverlay.action(NodeState.Idle, own, ownerEnabled = false))
        assertEquals(NodeActionOverlay.Action.OwnerApproval, NodeActionOverlay.action(NodeState.AwaitingApproval, own, ownerEnabled = true))
    }
}
