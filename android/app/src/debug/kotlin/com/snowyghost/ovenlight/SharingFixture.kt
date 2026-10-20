package com.snowyghost.ovenlight

import java.time.Duration
import java.time.Instant

/**
 * Screenshots of sharing without a connector: `--ez sharingFixture true` fills in what a
 * connector would report for Book Club (shareable, two guests, an open invite) and Budget (not
 * shareable), and shows Echo Board as shared by Riley. Sam's first phone hasn't opened Book Club
 * for two months, since a new one joined.
 */
object SharingFixture {
    var isOn = false

    fun apply(app: WebApp): WebApp = if (!isOn || app.name != "Echo Board") app else app.copy(membershipID = "fixture", sharedBy = "Riley")

    fun machines(apps: List<WebApp>): List<OwnerSharing.Machine> {
        fun host(name: String) = apps.firstOrNull { it.name == name }?.let { Discovery.normalizedHost(it.host) }
        val club = host("Book Club") ?: return emptyList()
        val budget = host("Budget") ?: return emptyList()
        val now = Instant.now()
        fun ago(days: Double): Instant = now.minus(Duration.ofSeconds((86_400 * days).toLong()))
        val guests = AdminGuestList(
            guests = listOf(
                AdminGuest("n1", "p1", "Sam", "book-club", claimedAt = ago(120.0), lastOpened = ago(64.0)),
                AdminGuest("n4", "p1", "Sam", "book-club", claimedAt = ago(63.0), lastOpened = ago(0.1)),
                AdminGuest("n2", "p2", "Kim", "book-club", claimedAt = ago(30.0), lastOpened = ago(1.0)),
            ),
            invites = listOf(AdminInvite("i3", "Lee", "p3", "book-club", AdminInvite.sent, expires = now.plusSeconds(86_400))),
            openedSince = ago(200.0),
            taggedDevices = AdminTaggedDevices(apps = 1, guests = 3, tailnet = 5),
        )
        return listOf(
            OwnerSharing.Machine(
                adminHost = club,
                apps = listOf(
                    AdminApp("book-club", "Book Club", "https://$club/", online = true, shareable = true, guests = 2),
                    AdminApp("budget", "Budget", "https://$budget/", online = true, shareable = false, guests = 0),
                ),
                guests = guests,
                feedback = listOf(
                    AdminFeedback("f1", now.minusSeconds(1_800), "book-club", "Sam", "guest", note = "The chapter list stops scrolling after chapter 20."),
                ),
            ),
        )
    }
}
