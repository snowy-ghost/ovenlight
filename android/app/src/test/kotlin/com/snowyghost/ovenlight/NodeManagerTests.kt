package com.snowyghost.ovenlight

import java.io.File
import java.net.URI
import java.nio.file.Files
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** A node that reports whatever status the test sets. */
class FakeNode(val id: String, private val clients: FakeClients) : TailnetNode {
    var started = false
    var closed = false
    var loggedOut = false
    override fun start() {
        check(!closed) { "node closed" }
        started = true
    }
    override fun waitUp(timeoutMillis: Long) {
        clients.upError?.let { throw Exception(it) }
    }
    override fun statusJSON(): String = clients.status[id] ?: error("no status")
    override fun loginInteractive() {
        clients.loginRequests += 1
    }
    override fun logout() {
        loggedOut = true
    }
    override fun close() {
        closed = true
    }
}

/** The Go core and its proxy, faked: nodes report the statuses set here, and fetches answer by URL. */
class FakeClients : TailnetClients {
    val status = mutableMapOf<String, String>()
    val pages = mutableMapOf<String, FetchResponse>()
    val nodes = mutableListOf<FakeNode>()
    val forgotten = mutableListOf<String>()
    val fetched = mutableListOf<Pair<String, String>>()
    var upError: String? = null
    var loginRequests = 0

    override fun newNode(id: String, directory: File, hostName: String, controlURL: String, authKey: String): TailnetNode {
        check(nodes.none { it.id == id && !it.closed }) { "membership $id already has an open node" }
        return FakeNode(id, this).also { nodes += it }
    }

    override fun fetch(routeKey: String, timeoutMillis: Long, totalMillis: Long): Fetch = { uri ->
        fetched += routeKey to uri.toString()
        pages[uri.toString()]
    }

    override fun forget(membershipID: String) {
        forgotten += membershipID
    }
}

private fun status(backend: String, authURL: String = "", peers: String = "", self: String = "") =
    """{"BackendState":"$backend","AuthURL":"$authURL",$self"Peer":{$peers},"User":{"1":{"LoginName":"riley@example.com","DisplayName":"Riley"}},""" +
        """"CurrentTailnet":{"Name":"riley@example.com","MagicDNSSuffix":"tailabc123.ts.net"}}"""

private const val ownerSelf = """"Self":{"HostName":"phone","DNSName":"pixel-ab12.tailabc123.ts.net.","OS":"android","Online":true,"UserID":1},"""
private const val laptop = """"k1":{"HostName":"laptop","DNSName":"laptop.tailabc123.ts.net.","OS":"macOS","Online":true,"UserID":1}"""

// Android only: iOS doesn't unit test its managers, and the plan asks for these.
@OptIn(ExperimentalCoroutinesApi::class)
class NodeManagerTests {
    private val folder: File = Files.createTempDirectory("nodes").toFile()

    @After
    fun cleanUp() {
        folder.deleteRecursively()
    }

    private fun TestScope.manager(clients: FakeClients, preferences: KeyValueStore = MemoryStore()): NodeManager {
        val dispatcher = StandardTestDispatcher(testScheduler)
        return NodeManager(
            clients, preferences, MembershipStore(File(folder, "Ovenlight").apply { mkdirs() }), File(folder, "tailscale"),
            backgroundScope, dispatcher, { testScheduler.currentTime }, {},
        )
    }

    @Test
    fun testTheOwnerSignsInThenFindsTheAppsOnTheirComputers() = runTest {
        val clients = FakeClients()
        val nodes = manager(clients)
        clients.status[Membership.ownerID] = status("NeedsLogin", authURL = "https://login.tailscale.com/a/abc")
        nodes.enableOwner()
        advanceTimeBy(1_500)
        assertEquals(NodeState.NeedsLogin(URI("https://login.tailscale.com/a/abc")), nodes.owner.state.value)
        assertTrue(nodes.ownerHasNoAccount)

        clients.pages["https://laptop.tailabc123.ts.net/.well-known/ovenlight.json"] =
            FetchResponse(200, """{"name":"Recipe Box","version":1}""".toByteArray())
        clients.status[Membership.ownerID] = status("Running", self = ownerSelf, peers = laptop)
        advanceTimeBy(2_500)
        assertEquals(NodeState.Ready, nodes.owner.state.value)
        assertEquals(listOf("laptop.tailabc123.ts.net"), nodes.owner.discovered.value.map { it.host })
        assertEquals("pixel-ab12", nodes.deviceName)
        assertFalse(nodes.ownerHasNoAccount)
        assertTrue("its own requests go on the owner's node key", clients.fetched.all { it.first == "node-owner" })
    }

    @Test
    fun testAClosedNodeIsNeverStartedAgain() = runTest {
        val clients = FakeClients()
        val nodes = manager(clients)
        clients.status[Membership.ownerID] = status("NeedsLogin")
        nodes.enableOwner()
        advanceTimeBy(100)
        nodes.restart()
        runCurrent()
        assertEquals(2, clients.nodes.size)
        assertTrue(clients.nodes[0].closed)
        assertFalse(clients.nodes[1].closed)
        nodes.closeAll()
        assertTrue(clients.nodes[1].closed)
        assertFalse(nodes.owner.isRunning)
    }

    @Test
    fun testAnOwnerWithNoPageAsksForOneOnce() = runTest {
        val clients = FakeClients()
        val nodes = manager(clients)
        clients.status[Membership.ownerID] = status("NeedsLogin")
        nodes.enableOwner()
        // Polls come every second, from the first at 0: the tenth is at 9 s.
        advanceTimeBy(8_500)
        assertEquals(0, clients.loginRequests)
        advanceTimeBy(1_000)
        assertEquals(1, clients.loginRequests)
        advanceTimeBy(30_000)
        assertEquals("only once", 1, clients.loginRequests)
    }

    @Test
    fun testRemovingAGuestLogsOutAndForgetsItsRoute() = runTest {
        val clients = FakeClients()
        val nodes = manager(clients)
        val invite = InviteLink.parse(
            URI(
                "ovenlight://join?app=echo-board&control=https%3A%2F%2Fcontrolplane.tailscale.com&host=echo-board.taildef456.ts.net" +
                    "&invite=3f9a1c2b7e&key=k&name=Echo+Board&owner=Riley&to=Sam+Lee&v=1",
            ),
            allowLocalHTTP = false,
        )
        val membership = Membership(invite)
        nodes.memberships.upsert(membership)
        nodes.syncGuestNodes()
        val guest = nodes.guestNode(membership.id)!!
        clients.status[membership.id] = status("Running")
        guest.start()
        val directory = nodes.stateDirectory(membership.id)
        assertTrue(directory.exists())

        nodes.removeGuest(membership.id)
        assertTrue(clients.nodes.single { it.id == membership.id }.loggedOut)
        assertFalse(directory.exists())
        assertEquals(listOf(membership.id), clients.forgotten)
        assertNull(nodes.guestNode(membership.id))
        assertNull(nodes.memberships[membership.id])
    }

    @Test
    fun testTheWaitReportsTheControlServersAnswer() = runTest {
        val clients = FakeClients()
        val nodes = manager(clients)
        clients.status[Membership.ownerID] = status("NeedsLogin")
        nodes.owner.start()
        assertNull(nodes.owner.waitUntilUp(1_000))
        clients.upError = "tsnet.Up: backend: invalid key: unable to validate API key"
        assertEquals("tsnet.Up: backend: invalid key: unable to validate API key", nodes.owner.waitUntilUp(1_000))
    }
}
