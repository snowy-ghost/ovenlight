package com.snowyghost.ovenlight

import java.net.URI
import java.time.Instant
import java.util.UUID
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** The rules iOS keeps inside GuestManager, which Android's manager calls (not on iOS, so no Swift twin). */
class GuestManagerRulesTests {
    private val echo = "echo-board.taildef456.ts.net"

    private fun invite(host: String = echo, id: String = "i0", to: String = "Sam") = InviteLink(
        control = URI("https://controlplane.tailscale.com"), key = "k-$id", owner = "Riley", app = "echo-board", invite = id,
        host = host, name = "Echo Board", to = to,
    )

    @Test
    fun testPreflightFindsOwnAppsAndAppsAlreadyAdded() {
        val m = Membership(invite(), id = "m1")
        val shared = WebApp(name = "Echo", startURL = URI("https://Echo-Board.taildef456.ts.net./"), membershipID = "m1")
        val lookup = { id: String -> if (id == "m1") m else null }
        assertEquals(Preflight.OwnApp, Preflight.of(invite(), "taildef456.ts.net", listOf(shared), lookup))
        assertEquals("the owner's network is another one", Preflight.AlreadyAdded(shared), Preflight.of(invite(), "tail0000.ts.net", listOf(shared), lookup))
        assertEquals(Preflight.Ready, Preflight.of(invite("notes.taildef456.ts.net"), null, listOf(shared), lookup))
        assertEquals("an app whose membership is gone", Preflight.Ready, Preflight.of(invite(), null, listOf(shared)) { null })
    }

    @Test
    fun testAFailedJoinKeepsOnlyARetryableInviteWithNoAppYet() {
        val app = WebApp(name = "Echo", startURL = URI("https://$echo/"), membershipID = "m1")
        val network = JoinFailure.of(JoinError.Network, invite(), "m1", emptyList())
        assertEquals(JoinFailure(JoinPhase.Failed(JoinError.Network), dropsInvite = false), network)
        assertEquals(JoinFailure(JoinPhase.Failed(JoinError.KeyUsed), dropsInvite = true), JoinFailure.of(JoinError.KeyUsed, invite(), "m1", emptyList()))
        assertEquals(
            "an old link for an app already here still leaves the app",
            JoinFailure(JoinPhase.Joined(app.id), dropsInvite = true),
            JoinFailure.of(JoinError.Network, invite(), "m1", listOf(app)),
        )
        assertEquals(
            JoinFailure(JoinPhase.Failed(JoinError.WrongNetwork), dropsInvite = true),
            JoinFailure.of(JoinError.WrongNetwork, invite(), "m1", listOf(app)),
        )
        assertEquals("another membership's app", JoinPhase.Failed(JoinError.KeyUsed), JoinFailure.of(JoinError.KeyUsed, invite(), "m2", listOf(app)).phase)
    }

    @Test
    fun testAClaimTriesWithinItsBudget() {
        var left = Claim.budget
        val waits = mutableListOf<Duration>()
        while (true) {
            val wait = Claim.wait(after = waits.size, left = left) ?: break
            waits += wait
            left -= wait
        }
        assertEquals(listOf(500.milliseconds, 1.seconds, 2.seconds, 4.seconds, 4.seconds), waits.take(5))
        assertEquals("the waits alone end at the budget", Claim.budget, waits.fold(Duration.ZERO, Duration::plus))
        assertEquals(8.seconds, Claim.tryTimeout(left = 20.seconds))
        assertEquals("no longer than what's left", 5.seconds, Claim.tryTimeout(left = 5.seconds))
        assertEquals("the try after a last wait still gets a moment", 2.seconds, Claim.tryTimeout(left = Duration.ZERO))
    }

    @Test
    fun testPendingInvitesQueueAndTheClaimedOneLeaves() {
        val start = Membership(invite(), id = "m1").copy(guestName = "Sam")
        val waiting = start.awaiting(invite(id = "i0"), Instant.EPOCH).awaiting(invite("notes.taildef456.ts.net", id = "i1"), Instant.EPOCH)
        assertEquals(listOf("i0", "i1"), waiting.pendingClaims.map { it.invite.invite })
        assertSame("an invite already waiting isn't added twice", waiting, waiting.awaiting(invite(id = "i1")))
        assertEquals(listOf("i1", "i0"), waiting.puttingFirst("i1").pendingClaims.map { it.invite.invite })
        assertSame(waiting, waiting.puttingFirst("i0"))
        assertSame(waiting, waiting.puttingFirst("unknown"))
        assertTrue(GuestCleanup.isRegistering(waiting.puttingFirst("i1"), with = "i1"))

        assertEquals(listOf(echo, "notes.taildef456.ts.net"), GuestCleanup.guestHosts(waiting, "m1", emptyList()))

        val claimed = waiting.claimed(invite(id = "i0", to = ""), owner = "Riley Q")
        assertEquals(listOf("i1"), claimed.pendingClaims.map { it.invite.invite })
        assertEquals("Riley Q", claimed.ownerName)
        assertEquals("an invite without a name keeps the guest's", "Sam", claimed.guestName)
        assertEquals("Lee", waiting.claimed(invite(id = "i0", to = "Lee"), owner = "Riley").guestName)
    }

    @Test
    fun testSharedAppsOfAMembershipThatsGoneAreStranded() {
        val m = Membership(invite(), id = "m1")
        val kept = WebApp(name = "Echo", startURL = URI("https://$echo/"), membershipID = "m1")
        val gone = WebApp(name = "Notes", startURL = URI("https://notes.taildef456.ts.net/"), membershipID = "m2")
        val own = WebApp(name = "Coach", startURL = URI("https://coach.tailabc123.ts.net/"))
        assertEquals(listOf(gone), GuestCleanup.stranded(listOf(kept, gone, own)) { if (it == "m1") m else null })
        assertEquals(listOf(echo), GuestCleanup.guestHosts(null, "m1", listOf(kept, gone, own)))
    }

    @Test
    fun testAnEndedShareIsNamedByItsFirstAppsOwner() {
        val shared = WebApp(name = "Echo", startURL = URI("https://$echo/"), membershipID = "m1", sharedBy = "Riley")
        val notes = shared.copy(id = UUID.randomUUID(), name = "Notes", sharedBy = null)
        val notice = Notice.accessEnded(listOf(shared, notes), listed = listOf(shared, notes))!!
        assertEquals(ConnectionCopy.noLongerSharedTitle, notice.title)
        assertEquals(ConnectionCopy.noLongerShared(listOf("Echo", "Notes"), "Riley"), notice.message)
        val later = Notice.accessEnded(listOf(shared, notes), listed = listOf(notes))
        assertEquals("only what's still listed", ConnectionCopy.noLongerShared(listOf("Notes"), "Its owner"), later?.message)
        assertNull("a second sweep of the same apps says nothing", Notice.accessEnded(listOf(shared), listed = listOf(notes)))
        assertNull(Notice.accessEnded(emptyList(), listed = listOf(shared)))
        CopyRules.assertNeutral(notice.message)
    }

    /** Every join error says "phone" for the guest's device (GuestCopyTests checks they are neutral). */
    @Test
    fun testJoinErrorsAreNeutralAndSayPhone() {
        for (error in JoinError.entries) {
            for (text in listOf(error.title("Riley", "Echo Board"), error.message("Riley", "Echo Board"))) {
                assertFalse(text, text.contains("iPhone"))
            }
        }
        assertEquals("This invite was used on another phone", JoinError.UsedOnAnotherDevice.title("Riley", "Echo Board"))
        assertTrue(JoinError.SomeoneElses.message("Riley", "Echo Board").startsWith("This phone already joined Riley’s apps"))
    }
}
