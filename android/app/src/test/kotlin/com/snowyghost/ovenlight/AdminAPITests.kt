package com.snowyghost.ovenlight

import java.io.File
import java.net.URI
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

@OptIn(ExperimentalCoroutinesApi::class)
class AdminAPITests {
    @get:Rule
    val temp = TemporaryFolder()

    /**
     * The connector's own examples of everything Ovenlight reads (connector/wire.go writes
     * them): each must decode in full, and a new one needs a line here.
     */
    @Test
    fun testDecodesEveryGoldenFileFromTheConnector() {
        val wire = File(System.getProperty("ovenlight.wireDir")!!)
        val files = wire.listFiles { file -> file.extension == "json" }.orEmpty().sortedBy { it.name }
        assertFalse(files.isEmpty())
        fun json(data: ByteArray): JsonElement = Json.parseToJsonElement(data.decodeToString())
        fun count(data: ByteArray, key: String? = null): Int {
            val list = if (key == null) json(data) else json(data).jsonObject[key]
            return (list as? JsonArray)?.size ?: 0
        }
        fun string(data: ByteArray, key: String): String? = ((json(data) as? JsonObject)?.get(key) as? JsonPrimitive)?.takeIf { it.isString }?.content
        for (file in files) {
            val name = file.nameWithoutExtension
            val data = file.readBytes()
            when {
                name == "apps" -> {
                    val apps = AdminAPI.decodeEach(AdminApp.decoder, data).elements
                    assertEquals(count(data), apps.size)
                    assertEquals(listOf("coach.tail1.ts.net", null), apps.map { it.host })
                }
                name == "guests" -> {
                    val list = AdminAPI.decode(AdminGuestList.decoder, data)
                    assertEquals(count(data, "guests"), list.guests.size)
                    assertEquals(count(data, "invites"), list.invites.size)
                    assertEquals(listOf(true, false, false), list.guests.map { it.lastOpened != null })
                    assertNotNull(list.openedSince)
                    assertEquals(AdminTaggedDevices(apps = 1, guests = 3, tailnet = 5), list.taggedDevices)
                }
                name == "invite-canceled" -> AdminAPI.decode(AdminInvite.decoder, data)
                name == "invite-created" -> {
                    val result = AdminAPI.decode(AdminShareResult.decoder, data)
                    val sent = result.sentMessage!!
                    assertEquals(sent, result.message)
                    val link = result.link!!
                    assertTrue(sent.endsWith(link))
                    assertEquals("without the connector's message, just the link", link, result.copy(sentMessage = null).message)

                    // The links the connector writes read back as the invite it made.
                    val invite = InviteLink.parse(URI(link), allowLocalHTTP = false)
                    val appLink = InviteLink.parse(URI(string(data, "appLink")!!), allowLocalHTTP = false)
                    assertEquals("the universal link and the app link carry the same invite", invite, appLink)
                    assertEquals(result.invite.id, invite.invite)
                    assertEquals(result.invite.app, invite.app)
                    assertEquals(result.invite.to, invite.to)
                    assertEquals(result.appName, invite.name)
                    assertEquals(result.owner, invite.owner)
                    assertEquals("coach.tail1.ts.net", invite.host)
                    assertEquals("https://controlplane.tailscale.com", invite.control.toString())
                    assertEquals("tskey-auth-kExample1CNTRL-ExampleSecretNotReal", invite.key)
                    assertEquals("round trip", invite, InviteLink.parse(invite.universalURL, allowLocalHTTP = false))
                    assertEquals("round trip", invite, InviteLink.parse(invite.url, allowLocalHTTP = false))
                }
                name == "guest-removed" || name == "guest-removed-with-errors" ->
                    assertEquals(count(data, "removed"), AdminAPI.decode(AdminRevokeResult.decoder, data).removed.size)
                name == "feedback" ->
                    assertEquals(listOf(true, false), AdminAPI.decodeEach(AdminFeedback.decoder, data).elements.map { it.hasScreenshot })
                name == "claim" -> assertTrue(name, Claim.interpret(200, body = data) is Claim.Step.Claimed)
                name == "whoami-guest" || name == "whoami-owner" -> assertEquals(Reach.Reached, GuestProbe.classify(200, body = data))
                // The phone reads only the status; the answer stays an object with the item's ID.
                name == "feedback-sent" -> assertNotNull(string(data, "id"))
                name == "site-manifest" -> {
                    val manifest = OvenlightManifest.parse(data)!!
                    assertEquals(string(data, "name"), manifest.name)
                    assertEquals(string(data, "slug"), manifest.slug)
                    assertEquals(string(data, "icon"), manifest.icon)
                    assertEquals(string(data, "themeColor"), manifest.themeColor)
                    assertNotNull("the icon is on the app's own host", DiscoveredApp("coach.tail1.ts.net", manifest).iconURL)
                }
                name.startsWith("error-") -> {
                    assertEquals(name.removePrefix("error-"), ConnectorError(null, data)?.code)
                    assertNotNull(AdminAPI.errorMessage(data))
                }
                else -> fail("$name.json: decode it here")
            }
        }
    }

    @Test
    fun testOneUnreadableRecordDoesntCostTheRest() {
        val apps = """[{"slug":"coach","name":"Coach"},{"name":"No slug"},{"slug":"notes","name":"Notes","guests":"many","newField":{}}]"""
        val each = AdminAPI.decodeEach(AdminApp.decoder, apps.toByteArray())
        assertEquals(listOf(1), each.skipped)
        val decoded = each.elements
        assertEquals(listOf("coach", "notes"), decoded.map { it.slug })
        assertEquals("what isn't essential falls back", listOf(0L, 0L), decoded.map { it.guests })
        assertEquals("counts are 64 bits, as iOS's Int", 3_000_000_000L, AdminAPI.decodeEach(AdminApp.decoder, """[{"slug":"a","name":"A","guests":3000000000}]""".toByteArray()).elements.single().guests)

        val guests = """{"guests":[{"deviceId":"7","person":"p1","app":"echo","claimedAt":"2026-09-28T17:05:00Z"},{"deviceId":"8"}],"invites":null}"""
        val list = AdminAPI.decode(AdminGuestList.decoder, guests.toByteArray())
        assertEquals(listOf("7"), list.guests.map { it.deviceId })
        assertEquals(emptyList<AdminInvite>(), list.invites)
    }

    @Test
    fun testRequestsCarryTheHeaderOnlyForChanges() {
        val get = AdminAPI.request(host = "echo.e2e.ovenlight.test", method = "GET", path = "/v1/apps")!!
        assertEquals("https://echo.e2e.ovenlight.test:8443/v1/apps", get.url.toString())
        assertNull(get.header(AdminAPI.header))
        val post = AdminAPI.request(host = "echo.e2e.ovenlight.test", method = "POST", path = "/v1/invites", body = mapOf("to" to "Sam", "app" to "echo"))!!
        assertEquals("1", post.header("X-Ovenlight-Request"))
        val body = ovenlightJSON.decodeFromString(MapSerializer(String.serializer(), String.serializer()), post.body!!.decodeToString())
        assertEquals(mapOf("to" to "Sam", "app" to "echo"), body)
        val delete = AdminAPI.request(host = "h.test", method = "DELETE", path = AdminAPI.path("/v1/guests/", "Sam Lee/x"))!!
        assertEquals("/v1/guests/Sam%20Lee%2Fx", delete.url.rawPath)
        assertEquals("1", delete.header(AdminAPI.header))
        assertEquals("the admin API is for the owner only", AdminAPI.errorMessage("""{"error":"the admin API is for the owner only"}""".toByteArray()))
    }

    @Test
    fun testAsksOnlyOnlineMachines() {
        val json = """
        {"BackendState":"Running","CurrentTailnet":{"Name":"owen@","MagicDNSSuffix":"tailabc123.ts.net"},
         "Self":{"DNSName":"phone.tailabc123.ts.net.","OS":"iOS","UserID":1},
         "Peer":{"a":{"DNSName":"coach.tailabc123.ts.net.","OS":"linux","Online":true,"Tags":["tag:ovenlight-app-coach"]},
                 "b":{"DNSName":"notes.tailabc123.ts.net.","OS":"linux","Online":false,"Tags":["tag:ovenlight-app-notes"]}}}
        """
        val status = TailnetStatus.decode(json)!!
        val apps = listOf("coach", "notes").map { WebApp(name = it, startURL = URI("https://$it.tailabc123.ts.net/"), discovered = true) }
        assertEquals(
            "an offline computer can only time out",
            listOf("coach.tailabc123.ts.net"),
            OwnerSharing.candidateHosts(apps, discovered = emptyList(), ownersHosts = Discovery.candidateHosts(status).toSet()),
        )
    }

    @Test
    fun testFirstRoundAsksEachKnownMachineOnce() {
        fun app(slug: String) = AdminApp(slug = slug, name = slug, url = "https://$slug.t.ts.net/", online = true, shareable = true, guests = 0)
        val candidates = listOf("coach", "echo", "notes", "web").map { "$it.t.ts.net" }
        assertEquals("nothing known: every host", candidates, OwnerSharing.firstRound(candidates, known = emptyList()))
        val known = listOf(
            OwnerSharing.Machine(adminHost = "notes.t.ts.net", apps = listOf(app("coach"), app("notes"))),
            OwnerSharing.Machine(adminHost = "gone.t.ts.net", apps = listOf(app("echo"))),
        )
        assertEquals(
            "the host a computer answered on first, and hosts no computer still online listed",
            listOf("notes.t.ts.net", "echo.t.ts.net", "web.t.ts.net"),
            OwnerSharing.firstRound(candidates, known),
        )
    }

    @Test
    fun testGoTimestamps() {
        assertNotNull(AdminAPI.parseDate("2026-09-28T17:03:00Z"))
        assertNotNull(AdminAPI.parseDate("2026-09-28T17:03:00-04:00"))
        assertEquals(1_790_629_380.999, AdminAPI.parseDate("2026-09-28T21:03:00.999999999Z")!!.toEpochMilli() / 1000.0, 0.001)
        assertEquals(1_790_629_380.5, AdminAPI.parseDate("2026-09-28T21:03:00.5Z")!!.toEpochMilli() / 1000.0, 0.001)
        assertNull(AdminAPI.parseDate("yesterday"))
    }

    // Android only from here: iOS doesn't unit test its admin client and refresh.

    @Test
    fun testAGetAsksAgainAfterNoAnswerAndAChangeIsSentOnce() = runTest {
        val sent = mutableListOf<String>()
        val client = AdminClient("echo.t.ts.net") { request ->
            sent += "${request.method} ${request.url}"
            null
        }
        assertEquals(AdminError.Unreachable, runCatching { client.guests() }.exceptionOrNull())
        assertEquals("a lost first handshake gets two more tries", 3, sent.size)
        sent.clear()
        assertEquals(AdminError.Unreachable, runCatching { client.createInvite(to = "Sam", app = "echo") }.exceptionOrNull())
        assertEquals("a change that may have arrived isn't sent again", listOf("POST https://echo.t.ts.net:8443/v1/invites"), sent)
    }

    @Test
    fun testTheConnectorsAnswers() = runTest {
        var answer = FetchResponse(403, """{"error":"the admin API is for the owner only","code":"owner_only"}""".toByteArray())
        val requests = mutableListOf<okhttp3.Request>()
        val client = AdminClient("echo.t.ts.net") { request ->
            requests += request
            answer
        }
        assertEquals(AdminError.NotOwner, runCatching { client.apps() }.exceptionOrNull())
        answer = FetchResponse(400, """{"error":"that name is taken","code":"bad_request"}""".toByteArray())
        assertEquals("That name is taken", runCatching { client.createInvite(to = "Sam", app = "echo") }.exceptionOrNull()?.message)
        answer = FetchResponse(502)
        assertEquals(
            "The Ovenlight connector on this computer returned an error (502). Run ovenlight doctor there.",
            runCatching { client.cancelInvite("i1") }.exceptionOrNull()?.message,
        )
        answer = FetchResponse(200, "<html>".toByteArray())
        assertEquals(
            "The Ovenlight connector on this computer sent a reply this version of the app doesn't understand. Update both to the latest version.",
            runCatching { client.guests() }.exceptionOrNull()?.message,
        )
        answer = FetchResponse(200, """{"removed":[],"errors":["couldn't remove one device"]}""".toByteArray())
        assertEquals(listOf("couldn't remove one device"), client.removeGuest(GuestPerson("p 1", "Sam", emptyList()), "echo").errors)
        val removal = requests.last()
        assertEquals("DELETE", removal.method)
        assertEquals("https://echo.t.ts.net:8443/v1/guests/p%201?app=echo&by=person", removal.url.toString())
        assertEquals("1", removal.header(AdminAPI.header))
    }

    private val appNodes = listOf("coach", "notes").joinToString(",") {
        """"$it":{"HostName":"$it","DNSName":"$it.tailabc123.ts.net.","OS":"linux","Online":true,"Tags":["tag:ovenlight-app-$it"]}"""
    }
    private val ownApps = listOf("coach", "notes").map { WebApp(name = it, startURL = URI("https://$it.tailabc123.ts.net/"), discovered = true) }

    /**
     * The owner signed in, with two app nodes on one computer, whose connector's admin API answers
     * with [respond]. The apps themselves answer, so the node counts as connected.
     */
    private fun TestScope.connectedOwner(respond: (okhttp3.Request) -> FetchResponse?): NodeManager {
        val clients = FakeClients()
        val self = """"Self":{"HostName":"phone","DNSName":"pixel-ab12.tailabc123.ts.net.","OS":"android","Online":true,"UserID":1},"""
        clients.status[Membership.ownerID] = status("Running", self = self, peers = appNodes)
        clients.respond = { if (it.url.port == AdminAPI.port) respond(it) else FetchResponse(404) }
        val nodes = NodeManager(
            clients, MemoryStore(), MembershipStore(File(temp.root, "Ovenlight").apply { mkdirs() }), File(temp.root, "tailscale"),
            backgroundScope, StandardTestDispatcher(testScheduler), { testScheduler.currentTime }, {},
        )
        nodes.enableOwner()
        advanceTimeBy(2_500)
        assertTrue(nodes.ownerIsConnected)
        return nodes
    }

    @Test
    fun testOneComputerAnswersForAllItsApps() = runTest {
        val asked = mutableListOf<String>()
        val nodes = connectedOwner { request ->
            asked += "${request.url.host}${request.url.encodedPath}"
            when (request.url.encodedPath) {
                "/v1/apps" -> FetchResponse(200, """[{"slug":"coach","name":"Coach","url":"https://coach.tailabc123.ts.net/","shareable":true},{"slug":"notes","name":"Notes","url":"https://notes.tailabc123.ts.net/"}]""".toByteArray())
                "/v1/guests" -> FetchResponse(200, """{"guests":[{"deviceId":"7","person":"p1","name":"Sam","app":"coach","claimedAt":"2026-09-28T17:05:00Z"}]}""".toByteArray())
                else -> FetchResponse(200, "[]".toByteArray())
            }
        }
        val sharing = OwnerSharing(nodes, backgroundScope, { testScheduler.currentTime }, {})
        sharing.refresh(ownApps)
        val machine = sharing.machines.value.single()
        assertEquals("coach.tailabc123.ts.net", machine.adminHost)
        assertEquals(listOf("coach", "notes"), machine.apps.map { it.slug })
        assertEquals(listOf("Sam"), machine.guests.guests.map { it.name })
        assertEquals("the other app's node isn't asked for the computer's details", listOf("coach.tailabc123.ts.net"), asked.filter { "/v1/guests" in it }.map { it.substringBefore('/') })
        assertEquals(machine to machine.apps[0], sharing.info(ownApps[0]))

        asked.clear()
        sharing.refreshIfStale(ownApps)
        assertEquals("fresh for 15 seconds", emptyList<String>(), asked)
        advanceTimeBy(15_000)
        sharing.refreshIfStale(ownApps)
        assertEquals("then asked again, through the host that answered", setOf("coach.tailabc123.ts.net"), asked.map { it.substringBefore('/') }.toSet())

        sharing.forget()
        assertEquals("signing out forgets it all", emptyList<OwnerSharing.Machine>(), sharing.machines.value)
        asked.clear()
        sharing.refreshIfStale(ownApps)
        assertTrue("and the next menu asks afresh", asked.isNotEmpty())
    }

    @Test
    fun testARefreshThatOnlyFailedSaysWhyAndIsntFresh() = runTest {
        var asked = 0
        val nodes = connectedOwner {
            asked += 1
            null
        }
        val sharing = OwnerSharing(nodes, backgroundScope, { testScheduler.currentTime }, {})
        sharing.refresh(ownApps)
        assertEquals(emptyList<OwnerSharing.Machine>(), sharing.machines.value)
        assertEquals(AdminError.Unreachable.message, sharing.problem.value)
        val before = asked
        sharing.refreshIfStale(ownApps)
        assertTrue("the next menu asks again", asked > before)
    }
}
