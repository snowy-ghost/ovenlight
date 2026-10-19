package com.snowyghost.ovenlight

/**
 * What an app's long-press menu offers for sharing. The owner shares and sees people per app; a
 * guest reports a problem or leaves, and never sees Share or People.
 */
data class AppMenuPlan(
    val share: Share = Share.Hidden,
    /** People and how many have it, when the owner can share the app. */
    val people: Int? = null,
    val reportProblem: Boolean = false,
    /** Whose apps a guest can leave, when that's more than this one: Remove app covers one alone. */
    val leaveOwner: String? = null,
) {
    enum class Share {
        Hidden,
        Available,

        /** Published, but not shareable yet; that takes a typed confirmation on its computer. */
        NotShareable,
    }

    companion object {
        fun notShareableDetail(slug: String) = "Make it shareable first: on your computer, run ${SharingList.shareableCommand(slug)}"

        /** [admin] is the connector's view of the app and its computer's guests, when there is one; [apps], every app on this phone. */
        fun plan(app: WebApp, admin: Pair<AdminApp, AdminGuestList>?, apps: List<WebApp>): AppMenuPlan {
            if (app.isShared) {
                val more = apps.count { it.membershipID == app.membershipID } > 1
                return AppMenuPlan(reportProblem = true, leaveOwner = (app.sharedBy ?: "Its owner").takeIf { more })
            }
            val (adminApp, guests) = admin ?: return AppMenuPlan()
            if (!adminApp.shareable) return AppMenuPlan(share = Share.NotShareable)
            val people = GuestPerson.all(guests.guests.filter { it.app == adminApp.slug }).size
            return AppMenuPlan(share = Share.Available, people = people)
        }

        fun peopleTitle(count: Int): String = if (count > 0) "People ($count)" else "People"
    }
}
