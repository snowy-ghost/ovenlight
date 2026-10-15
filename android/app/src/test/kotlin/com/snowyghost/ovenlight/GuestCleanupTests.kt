package com.snowyghost.ovenlight

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The tests of this class that pin the guest node's probe rules; the cleanup and claim ones come with the guests. */
class GuestCleanupTests {
    private val echo = "echo-board.taildef456.ts.net"
    private val notes = "notes.taildef456.ts.net"

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
        val tile = AppReachability.forTile(echo, Routing.Route.TailnetNode, NodeState.Ready, answers)
        assertEquals(AppReachability.Offline, tile)
        answers[echo] = true
        assertNull(AppReachability.forTile(echo, Routing.Route.TailnetNode, NodeState.Ready, answers))
    }
}
