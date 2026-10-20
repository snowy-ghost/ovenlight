package com.snowyghost.ovenlight

import java.net.URI
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Test

class AppMenuPlanTests {
    private val t0: Instant = Instant.ofEpochSecond(1_790_000_000)

    private fun admin(shareable: Boolean) =
        AdminApp(slug = "coach", name = "Coach", url = "https://coach.tail1.ts.net/", online = true, shareable = shareable, guests = 0)

    private fun guest(name: String, app: String = "coach", removed: Boolean = false) = AdminGuest(
        deviceId = "n$name", person = "p$name", name = name, app = app, claimedAt = t0,
        removedAt = if (removed) t0 else null,
    )

    private fun invite(to: String, app: String = "coach") =
        AdminInvite(id = "i$to", to = to, person = "p$to", app = app, state = AdminInvite.sent, expires = t0)

    @Test
    fun testGroupsDevicesByPerson() {
        val pad = guest("Sam").copy(deviceId = "nPad")
        val sam = guest("Sam")
        val people = GuestPerson.all(listOf(sam, guest("Kim"), pad, guest("Sam", app = "notes"), guest("Lee", removed = true)))
        assertEquals(listOf("Sam", "Kim"), people.map { it.name })
        assertEquals("2 devices", people[0].devices)
        assertEquals("1 device", people[1].devices)
        assertEquals("pKim", people[1].id)
    }

    @Test
    fun testOwnerShareableAppCountsActiveGuestsOfThatApp() {
        val app = WebApp(name = "Coach", startURL = URI("https://coach.tail1.ts.net/"))
        val guests = AdminGuestList(guests = listOf(guest("Sam"), guest("Kim"), guest("Lee", removed = true), guest("Max", app = "notes")))
        val plan = AppMenuPlan.plan(app, admin(shareable = true) to guests, listOf(app))
        assertEquals(AppMenuPlan(share = AppMenuPlan.Share.Available, people = 2), plan)
        assertEquals("People (2)", AppMenuPlan.peopleTitle(2))
        assertEquals("People", AppMenuPlan.peopleTitle(0))
    }

    @Test
    fun testOwnerAppNotShareableOffersNoPeople() {
        val app = WebApp(name = "Coach", startURL = URI("https://coach.tail1.ts.net/"))
        val plan = AppMenuPlan.plan(app, admin(shareable = false) to AdminGuestList(guests = listOf(guest("Sam"))), listOf(app))
        assertEquals(AppMenuPlan(share = AppMenuPlan.Share.NotShareable), plan)
        CopyRules.assertNeutral(AppMenuPlan.notShareableDetail("coach"))
    }

    @Test
    fun testAppWithoutConnectorOffersNothing() {
        val app = WebApp(name = "Example", startURL = URI("https://example.com/"))
        assertEquals(AppMenuPlan(), AppMenuPlan.plan(app, admin = null, listOf(app)))
    }

    @Test
    fun testGuestNeverSeesShareOrPeople() {
        val app = WebApp(name = "Echo", startURL = URI("https://echo.tail5.ts.net/"), membershipID = "m1", sharedBy = "Riley")
        val notes = WebApp(name = "Notes", startURL = URI("https://notes.tail5.ts.net/"), membershipID = "m1", sharedBy = "Riley")
        val other = WebApp(name = "Pantry", startURL = URI("https://pantry.tail6.ts.net/"), membershipID = "m2", sharedBy = "Kim")
        // Even if the owner's view of a same-host app were at hand.
        val plan = AppMenuPlan.plan(app, admin(shareable = true) to AdminGuestList(), listOf(app, notes, other))
        assertEquals(AppMenuPlan(reportProblem = true, leaveOwner = "Riley"), plan)
        // Android only: with Riley's one app here, Leave would do just what Remove app does.
        assertEquals(AppMenuPlan(reportProblem = true), AppMenuPlan.plan(app, admin = null, listOf(app, other)))
    }

    @Test
    fun testInviteChoicesLeaveOutAppReview() {
        val review = invite("App Review").copy(review = true)
        val list = AdminGuestList(guests = listOf(guest("Sam")), invites = listOf(invite("Kim", app = "notes"), review))
        assertEquals(listOf("Sam", "Kim"), GuestPerson.invitable(list).map { it.name })
        assertEquals("still a name taken", listOf("Sam", "Kim", "App Review"), GuestPerson.everyone(list).map { it.name })
    }
}
