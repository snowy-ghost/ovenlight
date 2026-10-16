package com.snowyghost.ovenlight

import java.io.File
import java.net.URI
import java.time.Instant
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class MembershipTests {
    @get:Rule
    val temp = TemporaryFolder()
    private val dir: File get() = temp.root

    private fun invite(host: String = "echo-board.taildef456.ts.net", id: String = "inv1", control: String = "https://controlplane.tailscale.com") =
        InviteLink(control = URI(control), key = "tskey-$id", owner = "Riley", app = "echo-board", invite = id, host = host, name = "Echo Board", to = "Sam")

    /**
     * iOS also checks here that the file is excluded from backup. Android keeps every file out
     * of backups and device transfer by the manifest's rules, which an instrumented test checks.
     */
    @Test
    fun testPersistsAndReloads() {
        val membership = Membership(invite()).copy(pendingClaims = listOf(PendingClaim(invite(), Instant.ofEpochSecond(1_000))))
        val store = MembershipStore(dir)
        store.upsert(membership)
        store.update(membership.id) { it.copy(joined = true) }

        val reopened = MembershipStore(dir)
        assertEquals(1, reopened.memberships.size)
        assertNotNull(reopened[membership.id]?.hostName)
        assertNotEquals("each membership its own name", membership.hostName, Membership(invite()).hostName)
        assertEquals(true, reopened[membership.id]?.joined)
        assertEquals("tskey-inv1", reopened[membership.id]?.pendingClaims?.first()?.invite?.key)
        assertEquals(
            "another app of the same owner reuses the node",
            membership.id,
            reopened.membership(invite("notes.taildef456.ts.net", id = "inv2"))?.id,
        )
        assertNull("another network", reopened.membership(invite("echo.tail0000.ts.net")))
        assertNull("another control server", reopened.membership(invite(control = "https://headscale.example.com")))
        reopened.delete(membership.id)
        assertEquals(emptyList<Membership>(), MembershipStore(dir).memberships)
    }

    @Test
    fun testJoinKeyOnlyUntilJoined() {
        var membership = Membership(invite())
        assertNull(membership.joinKey)
        membership = membership.copy(pendingClaims = listOf(PendingClaim(invite(), Instant.now())))
        assertEquals("tskey-inv1", membership.joinKey)
        membership = membership.copy(joined = true)
        assertNull("a joined node restarts from its state, never the spent key", membership.joinKey)
    }

    @Test
    fun testSharedWebAppSurvivesSaving() {
        val app = WebApp(name = "Coach", startURL = URI("https://coach.tailabc123.ts.net/"))
        assertFalse(app.isShared)
        val shared = app.copy(membershipID = "m1", sharedBy = "Riley")
        val again = ovenlightJSON.decodeFromString(WebApp.serializer(), ovenlightJSON.encodeToString(WebApp.serializer(), shared))
        assertEquals("Riley", again.sharedBy)
        assertTrue(again.isShared && again.hasConnector)
    }

    @Test
    fun testSharedAppsAreAddedOnceAndNeverMergedWithOwnApps() = runTest {
        val registry = offlineRegistry(dir)
        val first = registry.addShared(
            name = "Echo Board", host = "Echo-Board.taildef456.ts.net.", slug = "echo-board", membershipID = "m1", sharedBy = "Riley",
        )
        val again = registry.addShared(
            name = "Echo Board", host = "echo-board.taildef456.ts.net", slug = "echo-board", membershipID = "m1", sharedBy = "Riley",
        )
        assertEquals(first.id, again.id)
        assertEquals("https://echo-board.taildef456.ts.net/", first.startURL.toString())
        val found = DiscoveredApp("echo-board.taildef456.ts.net", OvenlightManifest(name = "Mine", version = 1))
        registry.mergeDiscovered(listOf(found))
        assertEquals("discovery never adopts a shared app's tile", 2, registry.apps.size)
        assertEquals("Echo Board", registry.apps.firstOrNull { it.isShared }?.name)

        registry.remove(first.id)
        registry.mergeDiscovered(emptyList())
        assertEquals(
            "removing a shared app doesn't dismiss its host",
            "Echo Board",
            registry.addShared(
                name = "Echo Board", host = "echo-board.taildef456.ts.net", slug = "echo-board", membershipID = "m1", sharedBy = "Riley",
            ).name,
        )
    }

    @Test
    fun testEachClaimGivesTheOwnersAppsTheirCurrentName() {
        val registry = offlineRegistry(dir)
        registry.addShared(name = "Echo Board", host = "echo-board.taildef456.ts.net", slug = "echo-board", membershipID = "m1", sharedBy = "Riley")
        registry.addShared(name = "Notes", host = "notes.tail0000.ts.net", slug = "notes", membershipID = "m2", sharedBy = "Kim")
        registry.addShared(name = "Notes", host = "notes.taildef456.ts.net", slug = "notes", membershipID = "m1", sharedBy = "Riley Q")
        assertEquals("every app of that owner, no one else's", listOf("Riley Q", "Kim", "Riley Q"), registry.apps.map { it.sharedBy })
        assertEquals(listOf("Riley Q", "Kim", "Riley Q"), offlineRegistry(dir).apps.map { it.sharedBy })
    }
}
