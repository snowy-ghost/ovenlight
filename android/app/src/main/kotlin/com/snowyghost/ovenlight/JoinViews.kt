package com.snowyghost.ovenlight

import java.net.URI
import java.util.UUID

/** An invite to show in the join sheet: read from a link, or null for the Join with Invite entry. */
class JoinRequest(val invite: Result<InviteLink>? = null) {
    val id: UUID = UUID.randomUUID()

    /** The link's invite, or why it isn't one ([InviteLink.ParseError]). */
    constructor(url: URI) : this(
        try {
            Result.success(InviteLink.parse(url))
        } catch (e: InviteLink.ParseError) {
            Result.failure(e)
        },
    )
}

/** The launcher's first screen, before any app: anyone may see it, so it names no network. */
object LauncherWelcome {
    const val title = "Welcome to Ovenlight"
    const val message = "Open the invite a friend sent you, or connect the computers where your own apps run."
}
