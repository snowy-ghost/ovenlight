package com.snowyghost.ovenlight

import java.net.URI
import java.util.Locale
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DiscoveryTests {
    @Test
    fun testManifestParsing() {
        val json = """{"name":"Interview Coach","slug":"interview-coach","icon":"/icon-512.png","themeColor":"#0e6b66","version":1}"""
        val manifest = OvenlightManifest.parse(json.toByteArray())!!
        assertEquals("Interview Coach", manifest.name)
        assertEquals("#0e6b66", manifest.themeColor)
        val app = DiscoveredApp("interview-coach.tailabc123.ts.net", manifest)
        assertEquals("https://interview-coach.tailabc123.ts.net/", app.startURL.toString())
        assertEquals("https://interview-coach.tailabc123.ts.net/icon-512.png", app.iconURL?.toString())

        assertNull("needs a name", OvenlightManifest.parse("""{"name":"","version":1}""".toByteArray()))
        assertNull("and not just spaces and control characters", OvenlightManifest.parse("""{"name":" \u0007\n","version":1}""".toByteArray()))
        val spoof = OvenlightManifest.parse("""{"name":" Bank${"\u202E"}\u0000 Login \n","version":1}""".toByteArray())!!
        assertEquals("cleaned like an invite's names", "Bank Login", spoof.name)
        val long = OvenlightManifest.parse("""{"name":"${"x".repeat(100)}","version":1}""".toByteArray())!!
        assertEquals(InviteLink.maxLabel, long.name.graphemeCount())
        assertNull("needs a version", OvenlightManifest.parse("""{"name":"X","version":0}""".toByteArray()))
        assertNull(OvenlightManifest.parse("<html>".toByteArray()))
        val offHost = DiscoveredApp("a.tailabc123.ts.net", OvenlightManifest(name = "A", icon = "https://cdn.example.com/i.png", version = 1))
        assertNull("icons elsewhere can't be fetched through the node", offHost.iconURL)
    }

    @Test
    fun testMergeDeDupesByHostAndKeepsUserNames() {
        val manual = WebApp(name = "My coach", startURL = URI("https://interview-coach.tailabc123.ts.net/"), manifestName = null)
        val found = listOf(
            DiscoveredApp("Interview-Coach.tailabc123.ts.net.", OvenlightManifest(name = "Interview Coach", themeColor = "#0e6b66", version = 1)),
            DiscoveredApp("interview-coach.tailabc123.ts.net", OvenlightManifest(name = "Duplicate", version = 1)),
            DiscoveredApp("notes.tailabc123.ts.net", OvenlightManifest(name = "Notes", version = 1)),
            DiscoveredApp("old.tailabc123.ts.net", OvenlightManifest(name = "Old", version = 1)),
        )
        val merged = DiscoveryMerge.merge(found, into = listOf(manual), dismissedHosts = setOf("old.tailabc123.ts.net"))
        assertEquals("one tile per host, user's name kept, dismissed skipped", listOf("My coach", "Notes"), merged.map { it.name })
        assertEquals(manual.id, merged[0].id)
        assertEquals("#0e6b66", merged[0].themeColorHex)
        assertTrue("a tile added by its address is a connector's app once found", merged[0].discovered)
        assertTrue(merged[1].discovered)

        // A name that came from the manifest follows the manifest.
        val renamed = DiscoveryMerge.merge(
            listOf(DiscoveredApp("notes.tailabc123.ts.net", OvenlightManifest(name = "Notes 2", version = 1))),
            into = merged,
            dismissedHosts = emptySet(),
        )
        assertEquals("Notes 2", renamed[1].name)
    }

    /** Self is user 42 on tailabc123.ts.net; tagged nodes belong to the tagged-devices user. */
    private val trustStatus = """
    {"BackendState":"Running","Self":{"DNSName":"ovenlight-abc123.tailabc123.ts.net.","OS":"iOS","Online":true,"UserID":42},
     "Peer":{"k1":{"DNSName":"alex-mac.tailabc123.ts.net.","OS":"macOS","Online":true,"UserID":42},
             "k2":{"DNSName":"coach.tailabc123.ts.net.","OS":"linux","Online":true,"UserID":9000,"Tags":["tag:ovenlight-app-coach"]},
             "k3":{"DNSName":"coworker.tailabc123.ts.net.","OS":"macOS","Online":true,"UserID":7},
             "k4":{"DNSName":"ci.tailabc123.ts.net.","OS":"linux","Online":true,"UserID":9000,"Tags":["tag:ci"]},
             "k5":{"DNSName":"echo.tail99.ts.net.","OS":"linux","Online":true,"UserID":55,"Tags":["tag:ovenlight-app-echo"]},
             "k6":{"DNSName":"laptop.tail99.ts.net.","OS":"macOS","Online":true,"UserID":42},
             "k7":{"DNSName":"notes.tailabc123.ts.net.","OS":"macOS","Online":false,"UserID":42}},
     "CurrentTailnet":{"Name":"alex@example.com","MagicDNSSuffix":"tailabc123.ts.net"}}
    """

    @Test
    fun testOnlyTheOwnersOwnMachinesAreTrusted() {
        val status = TailnetStatus.decode(trustStatus)!!
        assertEquals(
            "not another user's computer, another tag, or anything shared in from another tailnet",
            setOf("alex-mac.tailabc123.ts.net", "coach.tailabc123.ts.net", "notes.tailabc123.ts.net"),
            Discovery.ownersHosts(status),
        )
        assertEquals("online ones only", listOf("alex-mac.tailabc123.ts.net", "coach.tailabc123.ts.net"), Discovery.candidateHosts(status))
        val noSelf = TailnetStatus.decode(trustStatus.replace(""""UserID":42}""", """"UserID":0}"""))!!
        assertEquals("no signed-in user, no owner", emptyList<String>(), Discovery.candidateHosts(noSelf))
        val taggedSelf = TailnetStatus.decode(
            trustStatus.replace(""""OS":"iOS","Online":true,"UserID":42}""", """"OS":"iOS","Online":true,"UserID":9000,"Tags":["tag:phone"]}"""),
        )!!
        assertEquals(
            "a tagged self shares the tagged-devices user; only app tags count then",
            setOf("coach.tailabc123.ts.net"),
            Discovery.ownersHosts(taggedSelf),
        )
        assertFalse(OvenlightTag.isApp("tag:ovenlight-app"))
        assertFalse(OvenlightTag.isApp("tag:ovenlight-apple"))
    }

    @Test
    fun testAListingCoversOnlyHostsItCanVouchFor() {
        val owners = Discovery.ownersHosts(TailnetStatus.decode(trustStatus)!!)
        val json = """
        [{"slug":"coach","name":"Coach","url":"https://coach.tailabc123.ts.net/","state":"running","online":true,"shareable":true,"guests":0},
         {"slug":"cw","name":"Coworker","url":"https://coworker.tailabc123.ts.net/","state":"running","online":true,"shareable":false,"guests":0},
         {"slug":"echo","name":"Echo","url":"https://echo.tail99.ts.net/","state":"running","online":true,"shareable":false,"guests":0},
         {"slug":"web","name":"Web","url":"https://example.com/","state":"running","online":true,"shareable":false,"guests":0},
         {"slug":"off","name":"Off","state":"stopped","online":false,"shareable":false,"guests":0}]
        """
        // iOS decodes the list as a whole, so every record must decode.
        val each = AdminAPI.decodeEach(AdminApp.decoder, json.toByteArray())
        assertEquals(emptyList<Int>(), each.skipped)
        val listed = each.elements

        val listing = OwnerSharing.vouched(listed, ownersHosts = owners)
        assertEquals("only the owner's own computers", setOf("coach.tailabc123.ts.net"), listing.covered)
        assertEquals(listOf("coach", "off"), listing.apps.map { it.slug })

        val shared = WebApp(name = "Echo", startURL = URI("https://echo.tail99.ts.net/"), discovered = true, membershipID = "m1")
        val apps = listOf(WebApp(name = "Coworker", startURL = URI("https://coworker.tailabc123.ts.net/"), discovered = true), shared)
        val found = listOf(DiscoveredApp("coach.tailabc123.ts.net", OvenlightManifest(name = "Coach", version = 1)))
        assertEquals(listOf("coach.tailabc123.ts.net"), OwnerSharing.candidateHosts(apps, discovered = found, ownersHosts = owners))
    }

    /** A computer that drops the probe (a firewall, or just asleep) mustn't hold up apps on the others. */
    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun testTheFirstAnswerCountsWithoutWaitingForTheSlowest() = runTest {
        // Serves every host a connector's manifest at once, except `slow`, which fails after `slowDelay`.
        val slow = "slow.tailabc123.ts.net"
        val slowDelay = 1_000L
        val fetch: Fetch = { url ->
            if (url.host == slow) {
                delay(slowDelay)
                null
            } else {
                FetchResponse(200, """{"name":"Coach","version":1}""".toByteArray())
            }
        }
        val start = currentTime
        var answeredAfter: Long? = null
        val outcome = Discovery.probe(listOf("coach.tailabc123.ts.net", slow), fetch) {
            assertNull("once", answeredAfter)
            answeredAfter = currentTime - start
        }
        assertTrue(answeredAfter!! < slowDelay / 2)
        assertTrue("discovery still hears from every host", currentTime - start >= slowDelay)
        assertEquals(mapOf("coach.tailabc123.ts.net" to true, slow to false), outcome.answered)
        assertEquals(listOf("Coach"), outcome.apps.map { it.manifest.name })

        var called = false
        Discovery.probe(listOf(slow), fetch) { called = true }
        assertFalse("nothing answered", called)
    }

    /** Android only: a fetch that throws, as OkHttp does when a computer refuses, costs only its own host. */
    @Test
    fun testOneHostsFailureCostsTheOthersNothing() = runTest {
        val fetch: Fetch = { url ->
            if (url.host == "off.tailabc123.ts.net") throw java.io.IOException("refused")
            FetchResponse(200, """{"name":"Coach","version":1}""".toByteArray())
        }
        val outcome = Discovery.probe(listOf("coach.tailabc123.ts.net", "off.tailabc123.ts.net"), fetch)
        assertEquals(mapOf("coach.tailabc123.ts.net" to true, "off.tailabc123.ts.net" to false), outcome.answered)
        assertEquals(listOf("Coach"), outcome.apps.map { it.manifest.name })
        assertEquals(Reach.Unreachable, GuestProbe.whoami("off.tailabc123.ts.net", fetch))
    }

    /** A ready node looks again when computers come or go, and a minute after a look that missed a host; not on a timer otherwise, which would keep every app node's connection out of idle. */
    @Test
    fun testLooksAgainOnlyOnAChangeOrAfterAMiss() {
        val t0 = 1_790_000_000_000L
        assertTrue(Discovery.lookAgain(hostsChanged = true, lastLookMillis = t0, missed = false, nowMillis = t0))
        assertFalse("every host answered: no timer", Discovery.lookAgain(hostsChanged = false, lastLookMillis = t0, missed = false, nowMillis = t0 + 600_000))
        assertFalse(Discovery.lookAgain(hostsChanged = false, lastLookMillis = t0, missed = true, nowMillis = t0 + 30_000))
        assertTrue(Discovery.lookAgain(hostsChanged = false, lastLookMillis = t0, missed = true, nowMillis = t0 + 61_000))
    }

    /**
     * A host on no known tailnet is refused, never loaded directly, as on iPhone. Android's twin
     * leaves out the iPhone's `localhost` cases: only its debug builds load that directly.
     */
    @Test
    fun testRoutesOnlyTheOwnersTailnetThroughTheOwnersNode() {
        val own = "tailabc123.ts.net"
        assertEquals(Routing.Route.TailnetNode, Routing.route("interview-coach.tailabc123.ts.net", own))
        assertEquals(Routing.Route.TailnetNode, Routing.route("Interview-Coach.TAILABC123.ts.net.", "TailABC123.ts.net."))
        assertEquals("another tailnet's Funnel name", Routing.Route.Refused, Routing.route("recorder.tail0000.ts.net", own))
        assertFalse("no tailnet known: the owner's node carries nothing yet", Routing.isOnOwnersTailnet("interview-coach.tailabc123.ts.net", null))
        assertEquals(Routing.Route.Refused, Routing.route("eviltailabc123.ts.net", own))
        assertEquals(Routing.Route.Refused, Routing.route("example.com", own))
        assertEquals(Routing.Route.Refused, Routing.route("tailabc123.ts.net.example.com", own))

        // Until the owner's tailnet is known (never signed in, or not yet reported), every
        // MagicDNS name goes through the owner's node rather than to public DNS.
        for (unknown in listOf(null, "")) {
            assertEquals(Routing.Route.TailnetNode, Routing.route("interview-coach.tailabc123.ts.net", unknown))
            assertEquals(Routing.Route.TailnetNode, Routing.route("Recorder.Tail0000.TS.NET.", unknown))
            assertEquals(Routing.Route.Refused, Routing.route("example.com", unknown))
            assertEquals(Routing.Route.Refused, Routing.route("ts.net.example.com", unknown))
        }
    }

    /** Android only: a phone set to Turkish lowercases "I" to a dotless "ı", which no host name has. */
    @Test
    fun testHostsLowercaseTheSameInEveryLocale() {
        val saved = Locale.getDefault()
        Locale.setDefault(Locale.forLanguageTag("tr-TR"))
        try {
            assertEquals("illustrator.tailabc123.ts.net", Discovery.normalizedHost("ILLUSTRATOR.TAILABC123.TS.NET."))
            assertEquals(Routing.Route.TailnetNode, Routing.route("ILLUSTRATOR.tailabc123.ts.net", "TAILABC123.TS.NET"))
            assertEquals("tailabc123.ts.net", InviteLink.networkDomain("ILLUSTRATOR.TAILABC123.TS.NET"))
        } finally {
            Locale.setDefault(saved)
        }
    }
}
