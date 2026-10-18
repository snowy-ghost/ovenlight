package com.snowyghost.ovenlight

import androidx.test.ext.junit.runners.AndroidJUnit4
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Under Robolectric for the phone's ICU, which orders and matches names as iOS does. */
@RunWith(AndroidJUnit4::class)
class GuestActivityTests {
    private val zone = ZoneId.of("America/Los_Angeles")

    /** Tuesday 13 October 2026, 9:41 in the evening, Pacific time. */
    private val now: Instant = LocalDateTime.of(2026, 10, 13, 21, 41).atZone(zone).toInstant()

    private fun guest(device: String, person: String = "pSam", name: String = "Sam", app: String = "coach", claimed: Instant, opened: Instant? = null) =
        AdminGuest(deviceId = device, person = person, name = name, app = app, deviceName = "", claimedAt = claimed, lastOpened = opened)

    private fun daysAgo(days: Double): Instant = now.minusMillis((86_400_000 * days).toLong())

    @Test
    fun testDaysAsPeopleSayThem() {
        val cases = listOf(
            now.minusSeconds(60 * 60 * 21) to "today", // this morning, just after midnight
            now.plusSeconds(3_600) to "today", // the connector's clock runs ahead
            now.minusSeconds(60 * 60 * 22) to "yesterday", // just before midnight
            daysAgo(3.0) to "3 days ago",
            daysAgo(8.0) to "last week",
            daysAgo(20.0) to "2 weeks ago",
            daysAgo(45.0) to "last month",
            daysAgo(100.0) to "3 months ago",
            daysAgo(400.0) to "last year",
        )
        for ((date, want) in cases) {
            assertEquals("$date", want, GuestActivity.day(date, now, zone))
        }
    }

    @Test
    fun testDaysAreReadOnThisCalendar() {
        val day = AdminAPI.parseDay("2026-09-29", zone)
        assertEquals(LocalDate.of(2026, 9, 29).atStartOfDay(zone).toInstant(), day)
        assertEquals("2 weeks ago", day?.let { GuestActivity.day(it, now, zone) })
        assertNull(AdminAPI.parseDay("yesterday"))
    }

    @Test
    fun testActivityFromTheRecords() {
        val since = daysAgo(30.0)
        val old = guest("nOld", claimed = daysAgo(90.0), opened = daysAgo(40.0))
        val new = guest("nNew", claimed = daysAgo(10.0), opened = daysAgo(2.0))
        assertEquals("the latest of any device", GuestActivity.Opened(daysAgo(2.0)), GuestActivity(listOf(old, new), since))
        val quiet = guest("nQuiet", claimed = daysAgo(90.0))
        assertEquals("joined before the connector began noting", GuestActivity.NotSince(since), GuestActivity(listOf(quiet), since))
        assertEquals(GuestActivity.NotYet, GuestActivity(listOf(guest("nNew", claimed = daysAgo(10.0))), since))
        assertEquals(
            "a connector that doesn't note opens",
            GuestActivity.Joined(daysAgo(90.0)),
            GuestActivity(listOf(quiet, guest("nNew", claimed = daysAgo(10.0))), since = null),
        )

        assertEquals("opened 2 days ago", GuestActivity.Opened(daysAgo(2.0)).phrase(now, zone))
        assertEquals("not opened since Sep 13", GuestActivity.NotSince(since).phrase(now, zone))
        assertEquals("not opened since Sep 8, 2025", GuestActivity.NotSince(daysAgo(400.0)).phrase(now, zone))
        assertEquals("not opened yet", GuestActivity.NotYet.phrase(now, zone))
        assertEquals("Not opened yet", GuestActivity.NotYet.caption)
    }

    @Test
    fun testANewInviteExpiresInDaysNotNextWeek() {
        var fresh = AdminInvite(id = "i", to = "Sam", person = "p", app = "coach", state = AdminInvite.sent, expires = Instant.now().plusSeconds(7 * 86_400 - 60))
        assertEquals("in 7 days", fresh.expiresIn)
        fresh = fresh.copy(expires = Instant.now().plusSeconds(90 * 86_400))
        assertEquals("in 3 months", fresh.expiresIn)
    }

    /**
     * Android only: expiresIn and "joined" spelled out by hand, pinned to what iOS's
     * Date.RelativeFormatStyle says for the same dates (checked on a Mac).
     */
    @Test
    fun testRelativePhrasesSayWhatIOSSays() {
        fun relative(seconds: Long) = RelativePhrase.relative(now.plusSeconds(seconds), now, zone, RelativePhrase.withoutWeeks)
        assertEquals("tomorrow", relative(23 * 3_600 + 40 * 60))
        assertEquals("in 2 days", relative(86_400 + 11 * 3_600 + 59 * 60))
        assertEquals("yesterday", relative(-36 * 3_600))
        assertEquals("2 months ago", relative(-45 * 86_400))
        assertEquals("in 2 hours", relative(90 * 60))
        assertEquals("now", relative(0))
        assertEquals("a half second rounds away from zero", "in 1 second", RelativePhrase.relative(now.plusMillis(500), now, zone, RelativePhrase.withoutWeeks))

        // Months clamp to the month's end, as Apple's calendar adds them.
        val endOfJanuary = java.time.ZonedDateTime.of(2026, 1, 31, 12, 0, 0, 0, zone).toInstant()
        fun fromJanuary31(days: Long) = RelativePhrase.relative(endOfJanuary.plus(java.time.Duration.ofDays(days)), endOfJanuary, zone, RelativePhrase.withoutWeeks)
        assertEquals("Feb 28 is a month after Jan 31", "next month", fromJanuary31(28))
        assertEquals("in 2 months", fromJanuary31(29))
    }

    @Test
    fun testNamesSortAsIOSSortsThem() {
        fun sorted(vararg names: String) = names.toList().shuffled(java.util.Random(7)).sortedWith(standardOrder())
        assertEquals(listOf("Guest 2", "Guest 10", "sam", "Sam"), sorted("Sam", "Guest 10", "sam", "Guest 2"))
        assertEquals("the shorter of names equal as numbers first", listOf("1", "01", "file1", "file01", "file001"), sorted("file001", "01", "file01", "1", "file1"))
        assertEquals("a space before a digit", listOf("Guest 2", "Guest 10", "Guest10"), sorted("Guest10", "Guest 10", "Guest 2"))
        assertEquals("letters before accents across the whole name", listOf("\u00C4", "a1b"), sorted("a1b", "\u00C4"))
    }

    @Test
    fun testPeopleWhoOpenedAppsMostRecentlyComeFirst() {
        val since = daysAgo(30.0)
        val list = AdminGuestList(
            guests = listOf(
                guest("nQuiet", person = "pJo", name = "Jo", claimed = daysAgo(60.0)),
                guest("nKim", person = "pKim", name = "Kim", claimed = daysAgo(20.0), opened = daysAgo(5.0)),
                guest("nSamOld", claimed = daysAgo(90.0), opened = daysAgo(25.0)),
                guest("nSamNew", app = "notes", claimed = daysAgo(10.0), opened = daysAgo(1.0)),
                guest("nAl", person = "pAl", name = "Al", claimed = daysAgo(2.0)),
            ),
            openedSince = since,
        )
        val people = GuestPerson.byActivity(list)
        assertEquals("then those who haven't opened one, by name", listOf("Sam", "Kim", "Al", "Jo"), people.map { it.name })
        assertEquals("every app and device of theirs", 2, people[0].guests.size)
        assertEquals("2 devices", people[0].summary(since).split(" · ").first())
    }

    @Test
    fun testTaggedDevicesSentence() {
        fun machine(tagged: AdminTaggedDevices?) =
            OwnerSharing.Machine(adminHost = "coach.tail1.ts.net", apps = emptyList(), guests = AdminGuestList(taggedDevices = tagged))
        assertEquals(
            "Your shareable apps and your guests’ devices use 4 of the 5 tagged devices in your Tailscale network. Tailscale’s plans include a set number and charge for more.",
            SharingList.taggedDevices(listOf(machine(AdminTaggedDevices(apps = 1, guests = 3, tailnet = 5)))),
        )
        assertTrue(SharingList.taggedDevices(listOf(machine(AdminTaggedDevices(apps = 2, guests = 2, tailnet = 4))))!!.contains("use all 4 tagged devices"))
        assertTrue(SharingList.taggedDevices(listOf(machine(AdminTaggedDevices(apps = 1, guests = 0, tailnet = 1))))!!.contains("use the only tagged device"))
        assertNull("not counted yet", SharingList.taggedDevices(listOf(machine(null))))
        assertNull("nothing of Ovenlight's", SharingList.taggedDevices(listOf(machine(AdminTaggedDevices(apps = 0, guests = 0, tailnet = 3)))))
    }
}
