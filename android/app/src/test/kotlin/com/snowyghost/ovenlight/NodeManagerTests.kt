package com.snowyghost.ovenlight

import java.io.File
import java.net.URI
import java.util.UUID
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

private const val ownerSelf = """"Self":{"HostName":"phone","DNSName":"pixel-ab12.tailabc123.ts.net.","OS":"android","Online":true,"UserID":1},"""
private const val laptop = """"k1":{"HostName":"laptop","DNSName":"laptop.tailabc123.ts.net.","OS":"macOS","Online":true,"UserID":1}"""

// Android only: iOS doesn't unit test its managers, and the plan asks for these.
@OptIn(ExperimentalCoroutinesApi::class)
class NodeManagerTests {
    @get:Rule
    val temp = TemporaryFolder()
    private val folder: File get() = temp.root

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
    fun testSigningOutRemovesTheAppsFoundOnYourComputers() = runTest {
        val clients = FakeClients()
        val nodes = manager(clients)
        val deleted = mutableListOf<UUID>()
        val webData = object : AppRegistry.WebData {
            override fun discard(app: UUID) {}
            override suspend fun clear(store: UUID) {}
            override fun delete(store: UUID) {
                deleted += store
            }
            override suspend fun stores() = emptyList<UUID>()
        }
        val registry = AppRegistry(File(folder, "apps"), session = { null }, webData = webData, iconPNG = { null }, scope = backgroundScope)
        // As the app model wires them.
        backgroundScope.launch { nodes.owner.discovered.collect { registry.mergeDiscovered(it) } }
        nodes.onOwnerForgotten = registry::removeDiscovered
        val handAdded = registry.add(URI("https://notes.tailabc123.ts.net/"))
        val shared = registry.addShared("Echo Board", "echo-board.taildef456.ts.net", "echo-board", "m1", "Riley")
        clients.pages["https://laptop.tailabc123.ts.net/.well-known/ovenlight.json"] =
            FetchResponse(200, """{"name":"Recipe Box","version":1}""".toByteArray())
        clients.status[Membership.ownerID] = status("Running", self = ownerSelf, peers = laptop)
        nodes.enableOwner()
        advanceTimeBy(2_500)
        val found = registry.apps.single { it.discovered }
        assertEquals("Recipe Box", found.name)

        // A look for new apps is under way as the owner signs out.
        val held = CompletableDeferred<Unit>()
        clients.beforeAnswer = { held.await() }
        val late = launch { nodes.owner.rediscover() }
        runCurrent()
        nodes.disableOwner()
        held.complete(Unit)
        late.join()
        runCurrent()
        assertEquals("what it found doesn't bring the app back", listOf(handAdded.id, shared.id), registry.apps.map { it.id })
        assertEquals(listOf(found.dataStoreID), deleted)

        clients.beforeAnswer = {}
        nodes.enableOwner()
        advanceTimeBy(2_500)
        assertEquals("signing in again finds it", listOf("Recipe Box"), registry.apps.filter { it.discovered }.map { it.name })
    }

    @Test
    fun testASignOutCutShortFinishesAtLaunch() = runTest {
        val nodes = manager(FakeClients())
        val registry = offlineRegistry(File(folder, "apps"))
        nodes.onOwnerForgotten = registry::removeDiscovered
        // What the process left dying during a Sign Out: owner mode off, the node's state and the
        // apps found still there, and one removed by hand still dismissed.
        val found = listOf(
            DiscoveredApp("recipe-box.tailabc123.ts.net", OvenlightManifest(name = "Recipe Box", version = 1)),
            DiscoveredApp("notes.tailabc123.ts.net", OvenlightManifest(name = "Notes", version = 1)),
        )
        registry.mergeDiscovered(found)
        registry.remove(registry.apps.single { it.name == "Notes" }.id)
        val state = nodes.stateDirectory(Membership.ownerID).apply { mkdirs() }
        File(state, "tailscaled.state").writeText("{}")

        nodes.finishSignOut()
        assertFalse("its login doesn't come back", state.exists())
        assertEquals(emptyList<WebApp>(), registry.apps)
        registry.mergeDiscovered(found)
        assertEquals("signing in again finds them all", listOf("Recipe Box", "Notes"), registry.apps.map { it.name })

        nodes.enableOwner()
        nodes.finishSignOut()
        assertEquals("with owner mode on, nothing goes", 2, registry.apps.size)
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
    fun testANodeThatFailsToStartIsClosedSoTheNextStartWorks() = runTest {
        val clients = FakeClients()
        val nodes = manager(clients)
        clients.status[Membership.ownerID] = status("NeedsLogin")
        clients.startError = "tsnet: listen failed"
        nodes.owner.start()
        assertTrue("the core lets go of it", clients.nodes.single().closed)
        assertFalse(nodes.owner.isRunning)
        assertTrue(nodes.owner.state.value is NodeState.Failed)

        clients.startError = null
        nodes.restart()
        assertEquals(2, clients.nodes.size)
        assertTrue(nodes.owner.isRunning)
    }

    @Test
    fun testACanceledCallerStillFinishesTheStart() = runTest {
        val clients = FakeClients()
        val nodes = manager(clients)
        clients.status[Membership.ownerID] = status("NeedsLogin")
        lateinit var caller: Job
        // The screen that asked goes away while the core is starting the node.
        clients.onStart = { caller.cancel() }
        caller = launch { nodes.owner.start() }
        caller.join()
        assertTrue(caller.isCancelled)
        assertTrue("the started node is kept, not left open in the core", nodes.owner.isRunning)

        clients.onStart = {}
        // Under way (waiting on the core to close the node) when its caller goes.
        caller = launch(start = CoroutineStart.UNDISPATCHED) { nodes.owner.stop() }
        caller.cancel()
        caller.join()
        nodes.owner.start()
        assertEquals("a stop that was asked for still closes, and the next start makes a node", 2, clients.nodes.size)
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
        val guest = nodes.guestNode(membership.id)!!
        clients.status[membership.id] = status("Running")
        guest.start()
        val directory = nodes.stateDirectory(membership.id)
        assertTrue(directory.exists())

        // Twice at once, as Leave and the orphan sweep can: one removal.
        listOf(async { nodes.removeGuest(membership.id) }, async { nodes.removeGuest(membership.id) }).awaitAll()
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

    /** A node coming up is asked four times a second, so an app opened on it loads soon after; a wait for a login stays at once a second. */
    @Test
    fun testANodeComingUpIsPolledQuickly() = runTest {
        val clients = FakeClients()
        val nodes = manager(clients)
        clients.status[Membership.ownerID] = status("Starting")
        nodes.owner.start()
        advanceTimeBy(1_100)
        assertEquals(NodeState.Starting, nodes.owner.state.value)
        assertEquals("at 0, 250, 500, 750 and 1000 ms", 5, clients.statusReads)

        clients.status[Membership.ownerID] = status("NeedsLogin")
        advanceTimeBy(100)
        var reads = clients.statusReads
        advanceTimeBy(3_000)
        assertEquals(NodeState.Starting, nodes.owner.state.value)
        assertEquals(reads + 3, clients.statusReads)

        // Offline, as in a subway: no quicker than once a second.
        nodes.canHear = { false }
        clients.status[Membership.ownerID] = status("Starting")
        advanceTimeBy(1_100)
        reads = clients.statusReads
        advanceTimeBy(3_000)
        assertEquals(reads + 3, clients.statusReads)
    }

    /** Through the poll: an app node that missed is asked each minute, and once every one answered nothing is asked again. */
    @Test
    fun testOnlyAMissedHostIsProbedAgain() = runTest {
        val clients = FakeClients()
        val nodes = manager(clients)
        val manifest = { host: String -> "https://$host.tailabc123.ts.net/.well-known/ovenlight.json" }
        val desktop = """"k2":{"HostName":"desktop","DNSName":"desktop.tailabc123.ts.net.","OS":"linux","Online":true,"Tags":["tag:ovenlight-app-pantry"]}"""
        clients.pages[manifest("laptop")] = FetchResponse(200, """{"name":"Recipe Box","version":1}""".toByteArray())
        clients.status[Membership.ownerID] = status("Running", self = ownerSelf, peers = "$laptop,$desktop")
        nodes.enableOwner()
        advanceTimeBy(2_500)
        assertEquals(NodeState.Ready, nodes.owner.state.value)
        val probes = { clients.fetched.count { it.second == manifest("desktop") } }
        val missed = probes()
        advanceTimeBy(150_000)
        assertTrue("a host that missed is asked again each minute", probes() >= missed + 2)

        clients.pages[manifest("desktop")] = FetchResponse(200, """{"name":"Pantry","version":1}""".toByteArray())
        advanceTimeBy(80_000)
        assertEquals(mapOf("desktop.tailabc123.ts.net" to true, "laptop.tailabc123.ts.net" to true), nodes.owner.answers.value)
        val asked = clients.fetched.size
        advanceTimeBy(300_000)
        assertEquals("every host answered, so nothing is asked again", asked, clients.fetched.size)
    }

    /** A probe canceled under way, as when its app's screen closes, says nothing about the host. */
    @Test
    fun testACanceledProbeLeavesTheHostsAnswer() = runTest {
        val clients = FakeClients()
        val nodes = manager(clients)
        clients.pages["https://laptop.tailabc123.ts.net/.well-known/ovenlight.json"] =
            FetchResponse(200, """{"name":"Recipe Box","version":1}""".toByteArray())
        clients.status[Membership.ownerID] = status("Running", self = ownerSelf, peers = laptop)
        nodes.enableOwner()
        advanceTimeBy(2_500)
        assertEquals(NodeState.Ready, nodes.owner.state.value)
        assertEquals(mapOf("laptop.tailabc123.ts.net" to true), nodes.owner.answers.value)

        val held = CompletableDeferred<Unit>()
        clients.beforeAnswer = { held.await() }
        val probe = launch { nodes.owner.canReach(WebApp(name = "Recipe Box", startURL = URI("https://laptop.tailabc123.ts.net/"))) }
        runCurrent()
        probe.cancel()
        runCurrent()
        assertEquals(mapOf("laptop.tailabc123.ts.net" to true), nodes.owner.answers.value)
    }
}
