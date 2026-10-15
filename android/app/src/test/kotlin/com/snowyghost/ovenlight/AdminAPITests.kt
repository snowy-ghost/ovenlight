package com.snowyghost.ovenlight

import java.net.URI
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class AdminAPITests {
    @Test
    fun testOneUnreadableRecordDoesntCostTheRest() {
        val apps = """[{"slug":"coach","name":"Coach"},{"name":"No slug"},{"slug":"notes","name":"Notes","guests":"many","newField":{}}]"""
        val each = AdminAPI.decodeEach(AdminApp.decoder, apps.toByteArray())
        assertEquals(listOf(1), each.skipped)
        val decoded = each.elements
        assertEquals(listOf("coach", "notes"), decoded.map { it.slug })
        assertEquals("what isn't essential falls back", listOf(0, 0), decoded.map { it.guests })

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
}
