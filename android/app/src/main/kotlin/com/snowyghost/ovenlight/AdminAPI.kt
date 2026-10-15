package com.snowyghost.ovenlight

import java.net.URI
import java.text.Collator
import java.time.Instant
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import java.time.temporal.ChronoUnit
import java.time.temporal.TemporalAdjusters
import java.time.temporal.WeekFields
import java.util.Locale
import kotlin.math.abs
import kotlin.math.sign
import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.SerializationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.descriptors.buildClassSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull

// The owner's admin API on the connector: HTTPS on port 8443 of every app node, JSON, owner
// only (WhoIs), closed to guests by policy. Changes need `X-Ovenlight-Request: 1`. Types mirror
// the views in connector/wire.go (its testdata/wire files pin them). Only what identifies a
// record is required; the rest falls back, and each list decodes one record at a time, so a
// connector newer or older than this app still fills its screens.

/** One published app, as `GET /v1/apps` lists it. */
data class AdminApp(
    val slug: String,
    val name: String,
    val url: String? = null,
    /** The app answers on its local port. */
    val online: Boolean = false,
    val shareable: Boolean = false,
    val guests: Long = 0,
) {
    val id: String get() = slug
    val host: String? get() = url?.let(::uri)?.bareHost?.let(Discovery::normalizedHost)

    companion object {
        val decoder = ObjectDecoder("AdminApp") { fields ->
            AdminApp(
                slug = fields.requiredString("slug"),
                name = fields.requiredString("name"),
                url = fields.string("url"),
                online = fields.boolean("online") ?: false,
                shareable = fields.boolean("shareable") ?: false,
                guests = fields.int("guests") ?: 0,
            )
        }
    }
}

data class AdminInvite(
    val id: String,
    val to: String,
    /** Who the invite admits; see [AdminGuest.person]. */
    val person: String,
    val app: String,
    val state: String,
    val expires: Instant,
    /** Made with `ovenlight share --review` for Apple's App Review. */
    val review: Boolean? = null,
) {
    /**
     * When it expires, as people say it: "in 7 days", "tomorrow". Days rather than weeks, or a
     * new invite would expire "next week".
     */
    val expiresIn: String
        get() = RelativePhrase.relative(expires, Instant.now(), ZoneId.systemDefault(), RelativePhrase.withoutWeeks)

    companion object {
        const val sent = "sent"

        val decoder = ObjectDecoder("AdminInvite") { fields ->
            AdminInvite(
                id = fields.requiredString("id"),
                app = fields.requiredString("app"),
                state = fields.requiredString("state"),
                expires = fields.requiredDate("expires"),
                to = fields.string("to") ?: "",
                person = fields.string("person") ?: "",
                review = fields.boolean("review"),
            )
        }
    }
}

data class AdminGuest(
    val deviceId: String,
    /** The person the device belongs to, the same on every device and app of theirs. */
    val person: String,
    val name: String,
    val app: String,
    val deviceName: String,
    val claimedAt: Instant,
    val removedAt: Instant? = null,
    /**
     * The day this device last used the app, if it has since the list's `openedSince`: the
     * start of the day the connector names, on this phone's calendar.
     */
    val lastOpened: Instant? = null,
) {
    val id: String get() = "$deviceId/$app"
    val isActive: Boolean get() = removedAt == null

    companion object {
        val decoder = ObjectDecoder("AdminGuest") { fields ->
            AdminGuest(
                deviceId = fields.requiredString("deviceId"),
                person = fields.requiredString("person"),
                app = fields.requiredString("app"),
                claimedAt = fields.requiredDate("claimedAt"),
                name = fields.string("name") ?: "",
                deviceName = fields.string("deviceName") ?: "",
                removedAt = fields.date("removedAt"),
                lastOpened = fields.string("lastOpened")?.let { AdminAPI.parseDay(it) },
            )
        }
    }
}

/** One person's active devices, for listing and removing guests together. */
data class GuestPerson(val id: String, val name: String, val guests: List<AdminGuest>) {
    /**
     * How many devices, each counted once whatever apps it has: "2 devices". Guest phones join
     * under random names, which would tell the owner nothing.
     */
    val devices: String
        get() {
            val count = guests.map { it.deviceId }.toSet().size
            return if (count == 1) "1 device" else "$count devices"
        }

    /** "2 devices · opened today": how many devices, and when they last opened the apps of [guests]. */
    fun summary(since: Instant?): String = "$devices · ${GuestActivity(guests, since).phrase()}"

    companion object {
        /** Groups active guests by person, in order of who joined first. */
        fun all(guests: List<AdminGuest>): List<GuestPerson> {
            val out = mutableListOf<GuestPerson>()
            for (guest in guests.filter { it.isActive }) {
                val i = out.indexOfFirst { it.id == guest.person }
                if (i >= 0) {
                    out[i] = out[i].copy(name = guest.name, guests = out[i].guests + guest)
                } else {
                    out += GuestPerson(guest.person, guest.name, listOf(guest))
                }
            }
            return out
        }

        /** Everyone shared with or invited on a computer, any app, as the connector counts them when it checks a name. */
        fun everyone(list: AdminGuestList): List<GuestPerson> {
            val out = all(list.guests).toMutableList()
            for (invite in list.invites) {
                if (out.none { it.id == invite.person }) out += GuestPerson(invite.person, invite.to, emptyList())
            }
            return out
        }

        /**
         * Those to offer for another invite: everyone but App Review while its review invite
         * (made in the terminal) is unused. Once used, App Review is a guest like any other,
         * until the review is over and it's revoked.
         */
        fun invitable(list: AdminGuestList): List<GuestPerson> {
            val review = list.invites.filter { it.review == true }.map { it.person }.toSet()
            return everyone(list).filter { it.id !in review }
        }

        /**
         * Everyone with a device in any app, those who opened one most recently first, so the
         * people who stopped gather at the end.
         */
        fun byActivity(list: AdminGuestList): List<GuestPerson> {
            fun last(person: GuestPerson): Instant = GuestActivity(person.guests, list.openedSince).lastOpened ?: Instant.MIN
            return all(list.guests).sortedWith(compareByDescending(::last).thenBy(standardOrder()) { it.name })
        }
    }
}

/**
 * When someone last opened an app, from their guest records: the day the connector noted, or
 * what its silence means given when it began noting (`openedSince`).
 */
sealed class GuestActivity {
    data class Opened(val date: Instant) : GuestActivity()

    /** They joined before the connector began noting, and haven't opened it since then. */
    data class NotSince(val date: Instant) : GuestActivity()

    data object NotYet : GuestActivity()

    /** From a connector that doesn't note opens: only when they joined. */
    data class Joined(val date: Instant) : GuestActivity()

    val lastOpened: Instant? get() = (this as? Opened)?.date

    /** "opened today", "not opened since Oct 14", "not opened yet", or "joined 3 days ago". */
    fun phrase(now: Instant = Instant.now(), zone: ZoneId = ZoneId.systemDefault()): String = when (this) {
        is Opened -> "opened ${day(date, now, zone)}"
        is NotSince -> {
            val sameYear = date.atZone(zone).year == now.atZone(zone).year
            // The app isn't localized, so the date is English like the rest of the phrase.
            val style = DateTimeFormatter.ofPattern(if (sameYear) "MMM d" else "MMM d, yyyy", Locale.US)
            "not opened since ${style.format(date.atZone(zone))}"
        }
        NotYet -> "not opened yet"
        is Joined -> "joined ${RelativePhrase.relative(date, now, zone, RelativePhrase.allUnits)}"
    }

    /** The phrase as a caption of its own: "Opened today". */
    val caption: String get() = phrase().replaceFirstChar { it.uppercase() }

    companion object {
        operator fun invoke(guests: List<AdminGuest>, since: Instant?): GuestActivity {
            val last = guests.mapNotNull { it.lastOpened }.maxOrNull()
            return when {
                last != null -> Opened(last)
                since != null -> if (guests.any { it.claimedAt < since }) NotSince(since) else NotYet
                else -> Joined(guests.minOfOrNull { it.claimedAt } ?: Instant.now())
            }
        }

        /**
         * A day as people say it: "today", "yesterday", "3 days ago", "last week". From midnight
         * to midnight, so it never gets down to hours, and in whole units cut down rather than
         * rounded, as iOS's RelativeDateTimeFormatter counts them.
         */
        fun day(date: Instant, now: Instant = Instant.now(), zone: ZoneId = ZoneId.systemDefault()): String {
            val day = date.atZone(zone).toLocalDate()
            val today = now.atZone(zone).toLocalDate()
            if (!day.isBefore(today)) return "today"
            for (unit in listOf(ChronoUnit.YEARS, ChronoUnit.MONTHS, ChronoUnit.WEEKS, ChronoUnit.DAYS)) {
                val count = today.until(day, unit)
                if (count != 0L) return RelativePhrase.named(count, unit)
            }
            return "today"
        }
    }
}

/**
 * The relative phrases iOS's named presentation writes in English ("tomorrow", "last week",
 * "in 3 months"), from calendar math in the phone's time zone.
 */
internal object RelativePhrase {
    val allUnits = listOf(ChronoUnit.YEARS, ChronoUnit.MONTHS, ChronoUnit.WEEKS, ChronoUnit.DAYS, ChronoUnit.HOURS, ChronoUnit.MINUTES, ChronoUnit.SECONDS)
    val withoutWeeks = allUnits - ChronoUnit.WEEKS

    /**
     * [date] from [now] in the largest of [units] it is away, as iOS's `Date.RelativeFormatStyle`
     * names it. Hours, minutes and seconds round to the nearest, and one that rounds up to a whole
     * larger unit says that unit (23 hours and 40 minutes is "tomorrow"). Days and larger count
     * from the start of the current one, or its end for a date gone by: 1 day and 12 hours from
     * tonight is "in 2 days", 36 hours ago "yesterday".
     */
    fun relative(date: Instant, now: Instant, zone: ZoneId, units: List<ChronoUnit>): String {
        val to = date.atZone(zone)
        // To the whole second, as iOS measures, halves away from zero.
        val millis = now.toEpochMilli() - date.toEpochMilli()
        val from = date.plusMillis(millis.sign * Math.round(abs(millis) / 1000.0) * 1000).atZone(zone)
        val (unit, count) = largest(from, to, units) ?: return named(0, ChronoUnit.SECONDS)
        if (unit == ChronoUnit.HOURS || unit == ChronoUnit.MINUTES || unit == ChronoUnit.SECONDS) {
            val rest = from.plus(count, unit).until(to, if (unit == ChronoUnit.HOURS) ChronoUnit.MINUTES else ChronoUnit.SECONDS)
            val rounded = if (abs(rest) * 2 >= 60) count + (if (rest > 0) 1 else -1) else count
            val (larger, inLarger) = largest(to.minus(rounded, unit), to, units) ?: return named(rounded, unit)
            return if (larger != unit) named(inLarger, larger) else named(rounded, unit)
        }
        val start = startOf(from, unit)
        val base = if (from.isBefore(to)) start else start.plus(1, unit).minusSeconds(1)
        val (aligned, inAligned) = largest(base, to, units) ?: return named(count, unit)
        return named(inAligned, aligned)
    }

    /** The largest of [units] with a whole one between [from] and [to], and how many. */
    private fun largest(from: ZonedDateTime, to: ZonedDateTime, units: List<ChronoUnit>): Pair<ChronoUnit, Long>? =
        units.firstNotNullOfOrNull { unit -> count(from, to, unit).takeIf { it != 0L }?.let { unit to it } }

    /**
     * Whole [unit]s from [from] to [to]. Apple's calendar adds months and years clamped to the
     * month's end, so Feb 28 is a whole month after Jan 31, where java.time counts none.
     */
    private fun count(from: ZonedDateTime, to: ZonedDateTime, unit: ChronoUnit): Long {
        val whole = from.until(to, unit)
        val clamped = (unit == ChronoUnit.MONTHS || unit == ChronoUnit.YEARS) && from.isBefore(to) && !from.plus(whole + 1, unit).isAfter(to)
        return if (clamped) whole + 1 else whole
    }

    /** The start of the day, week (on the phone's first weekday), month or year [time] falls in. */
    private fun startOf(time: ZonedDateTime, unit: ChronoUnit): ZonedDateTime {
        val day = time.toLocalDate()
        val first = when (unit) {
            ChronoUnit.YEARS -> day.withDayOfYear(1)
            ChronoUnit.MONTHS -> day.withDayOfMonth(1)
            ChronoUnit.WEEKS -> day.with(TemporalAdjusters.previousOrSame(WeekFields.of(Locale.getDefault()).firstDayOfWeek))
            else -> day
        }
        return first.atStartOfDay(time.zone)
    }

    /** [count] of [unit] from now, by name where English has one: "yesterday", "next week", "now". */
    fun named(count: Long, unit: ChronoUnit): String {
        val word = when (unit) {
            ChronoUnit.YEARS -> "year"
            ChronoUnit.MONTHS -> "month"
            ChronoUnit.WEEKS -> "week"
            ChronoUnit.DAYS -> "day"
            ChronoUnit.HOURS -> "hour"
            ChronoUnit.MINUTES -> "minute"
            else -> "second"
        }
        val calendarUnit = unit == ChronoUnit.WEEKS || unit == ChronoUnit.MONTHS || unit == ChronoUnit.YEARS
        when {
            count == 0L && unit == ChronoUnit.DAYS -> return "today"
            count == 0L && unit == ChronoUnit.SECONDS -> return "now"
            count == 0L -> return "this $word"
            count == 1L && unit == ChronoUnit.DAYS -> return "tomorrow"
            count == -1L && unit == ChronoUnit.DAYS -> return "yesterday"
            count == 1L && calendarUnit -> return "next $word"
            count == -1L && calendarUnit -> return "last $word"
        }
        val amount = abs(count)
        val counted = if (amount == 1L) "1 $word" else "$amount ${word}s"
        return if (count > 0) "in $counted" else "$counted ago"
    }
}

/**
 * The tailnet's tagged devices, which Tailscale's plans count apart from people's own: the
 * connector's shareable apps and guest devices, and every tagged device.
 */
data class AdminTaggedDevices(val apps: Long, val guests: Long, val tailnet: Long) {
    companion object {
        val decoder = ObjectDecoder("AdminTaggedDevices") { fields ->
            AdminTaggedDevices(apps = fields.requiredInt("apps"), guests = fields.requiredInt("guests"), tailnet = fields.requiredInt("tailnet"))
        }
    }
}

data class AdminGuestList(
    val guests: List<AdminGuest> = emptyList(),
    val invites: List<AdminInvite> = emptyList(),
    /** When the connector began noting when guests open apps; null from one that doesn't. */
    val openedSince: Instant? = null,
    /** As of the connector's last sync with the tailnet. */
    val taggedDevices: AdminTaggedDevices? = null,
) {
    fun hasOpenInvites(person: String, review: Boolean = false): Boolean =
        invites.any { it.person == person && it.state == AdminInvite.sent && (!review || it.review == true) }

    companion object {
        val decoder = ObjectDecoder("AdminGuestList") { fields ->
            AdminGuestList(
                guests = fields.each("guests", AdminGuest.decoder),
                invites = fields.each("invites", AdminInvite.decoder),
                openedSince = fields.date("openedSince"),
                taggedDevices = fields["taggedDevices"]?.let {
                    try {
                        ovenlightJSON.decodeFromJsonElement(AdminTaggedDevices.decoder, it)
                    } catch (_: Exception) {
                        null
                    }
                },
            )
        }
    }
}

/** A minted invite. [link], the App Link to send, carries the key and is shown once. */
data class AdminShareResult(
    val invite: AdminInvite,
    val link: String? = null,
    val appName: String? = null,
    val owner: String? = null,
    /** The connector's wording of the message to send (its `message`), as `ovenlight share` words it. */
    val sentMessage: String? = null,
) {
    /** What the owner sends: the connector's message, or just the link without one. */
    val message: String get() = sentMessage?.takeIf { it.isNotEmpty() } ?: link ?: ""

    companion object {
        /** As iOS's synthesized decoder reads it: the invite required, the rest missing or null, but never of another type. */
        val decoder = ObjectDecoder("AdminShareResult") { fields ->
            AdminShareResult(
                invite = ovenlightJSON.decodeFromJsonElement(AdminInvite.decoder, fields["invite"] ?: throw SerializationException("invite")),
                link = fields.optionalString("link"),
                appName = fields.optionalString("appName"),
                owner = fields.optionalString("owner"),
                sentMessage = fields.optionalString("message"),
            )
        }
    }
}

data class AdminRevokeResult(val removed: List<AdminGuest>, val errors: List<String>? = null) {
    companion object {
        val decoder = ObjectDecoder("AdminRevokeResult") { fields ->
            AdminRevokeResult(
                removed = fields.each("removed", AdminGuest.decoder),
                errors = fields["errors"]?.let {
                    try {
                        ovenlightJSON.decodeFromJsonElement(ListSerializer(String.serializer()), it)
                    } catch (_: Exception) {
                        null
                    }
                },
            )
        }
    }
}

data class AdminFeedback(
    val id: String,
    val at: Instant,
    val app: String,
    val from: String,
    val role: String,
    val device: String? = null,
    val pageUrl: String? = null,
    val note: String,
    val screenshot: String? = null,
) {
    val hasScreenshot: Boolean get() = !screenshot.isNullOrEmpty()
    val isFromGuest: Boolean get() = role == "guest"

    companion object {
        val decoder = ObjectDecoder("AdminFeedback") { fields ->
            AdminFeedback(
                id = fields.requiredString("id"),
                at = fields.requiredDate("at"),
                app = fields.requiredString("app"),
                from = fields.string("from") ?: "",
                role = fields.string("role") ?: "",
                note = fields.string("note") ?: "",
                device = fields.string("device"),
                pageUrl = fields.string("pageUrl"),
                screenshot = fields.string("screenshot"),
            )
        }
    }
}

/** A request to the admin API, for the OkHttp layer to send through the owner's node. */
class AdminRequest(val url: URI, val method: String, val headers: Map<String, String>, val body: ByteArray?) {
    fun header(name: String): String? = headers.entries.firstOrNull { it.key.equals(name, ignoreCase = true) }?.value
}

object AdminAPI {
    const val port = 8443
    const val header = "X-Ovenlight-Request"

    /** Go's time.Time JSON: RFC 3339 with up to nine fractional digits. */
    fun parseDate(text: String): Instant? = try {
        OffsetDateTime.parse(text).toInstant()
    } catch (_: DateTimeParseException) {
        null
    }

    /**
     * A day the connector names as 2026-10-13: its start on this phone's calendar (in [zone]), so
     * it reads as the same day whatever the time zones.
     */
    fun parseDay(text: String, zone: ZoneId = ZoneId.systemDefault()): Instant? {
        // Split as iOS splits it: empty parts dropped, and parts that aren't numbers ignored.
        val parts = text.split('-').filter { it.isNotEmpty() }.mapNotNull { it.toIntOrNull() }
        if (parts.size != 3) return null
        return try {
            LocalDate.of(parts[0], parts[1], parts[2]).atStartOfDay(zone).toInstant()
        } catch (_: Exception) {
            null
        }
    }

    /** Decodes a reply; the decoders read dates with [parseDate]. */
    fun <T> decode(decoder: DeserializationStrategy<T>, data: ByteArray): T = ovenlightJSON.decodeFromString(decoder, strictUTF8(data))

    /** Decodes a list reply one record at a time (see [EachDecoded]). */
    fun <T> decodeEach(decoder: DeserializationStrategy<T>, data: ByteArray): EachDecoded<T> =
        EachDecoded.decode(ovenlightJSON.parseToJsonElement(strictUTF8(data)), decoder)

    fun url(host: String, path: String): URI? = uri("https://$host:$port$path")

    /**
     * Builds a request. Anything that changes something carries the header the connector
     * requires, which a web page can't add without a CORS preflight.
     */
    fun request(host: String, method: String, path: String, body: Map<String, String>? = null): AdminRequest? {
        val url = url(host, path) ?: return null
        val headers = mutableMapOf<String, String>()
        if (method != "GET") headers[header] = "1"
        if (body != null) headers["Content-Type"] = "application/json"
        val bytes = body?.let { ovenlightJSON.encodeToString(MapSerializer(String.serializer(), String.serializer()), it).toByteArray() }
        return AdminRequest(url, method, headers, bytes)
    }

    /** [base] plus [component] percent-encoded as one path segment, its `/` escaped too. */
    fun path(base: String, component: String): String = base + buildString {
        for (byte in component.toByteArray()) {
            val value = byte.toInt() and 0xFF
            val c = value.toChar()
            if (c in 'a'..'z' || c in 'A'..'Z' || c in '0'..'9' || c in PATH_ALLOWED) {
                append(c)
            } else {
                append('%').append(HEX[value shr 4]).append(HEX[value and 0xF])
            }
        }
    }

    /** What iOS's `urlPathAllowed` keeps besides ASCII letters and digits, less `/`. */
    private const val PATH_ALLOWED = "!$&'()*+,-.:;=@_~"
    private const val HEX = "0123456789ABCDEF"

    /** The `{"error": "...", "code": "..."}` the connector answers with. */
    fun errorMessage(data: ByteArray): String? = errorBody(data)?.error

    internal fun errorBody(data: ByteArray): ErrorBody? = try {
        ovenlightJSON.decodeFromString(ErrorBody.serializer(), strictUTF8(data))
    } catch (_: Exception) {
        null
    }
}

/** The connector's JSON error: its words, and the code to act on. */
@Serializable
internal class ErrorBody(val error: String? = null, val code: String? = null)

/**
 * What went wrong, as the connector names it: `code` in its JSON errors, or the
 * `Ovenlight-Error` header. Only a code this version knows acts; anything else is an unknown
 * failure that never ends access or deletes anything.
 */
enum class ConnectorError(val code: String) {
    NotInvited("not_invited"),
    UsedElsewhere("used_elsewhere"),
    OtherPerson("other_person"),
    OwnerOnly("owner_only"),
    NotFound("not_found"),
    BadRequest("bad_request"),
    RateLimited("rate_limited"),
    TooLarge("too_large"),
    Unavailable("unavailable"),
    ;

    companion object {
        const val header = "Ovenlight-Error"

        /** The code in a JSON error body, else the header's. */
        operator fun invoke(header: String?, body: ByteArray): ConnectorError? {
            val code = AdminAPI.errorBody(body)?.code ?: header ?: return null
            val trimmed = code.trimmingWhitespace()
            return entries.firstOrNull { it.code == trimmed }
        }
    }
}

sealed class AdminError : Exception() {
    data object NotOwner : AdminError()

    data object Unreachable : AdminError()

    data class Failed(val reason: String) : AdminError()

    override val message: String
        get() = when (this) {
            NotOwner -> "The Ovenlight connector on this computer is set up for another owner. To make it yours, run ovenlight setup-sharing --owner with your login there."
            Unreachable -> "Couldn't reach the computer. It may be asleep or offline."
            is Failed -> reason.replaceFirstChar { it.uppercase() }
        }
}

/**
 * What the owner's connectors report, for the Sharing screen and the app menu's Share. One
 * connector serves the same admin API on each of its app nodes, so Ovenlight asks one node per
 * computer once it knows which hosts share a computer.
 */
object OwnerSharing {
    data class Machine(
        /** The app node Ovenlight talks to. */
        val adminHost: String,
        val apps: List<AdminApp>,
        val guests: AdminGuestList = AdminGuestList(),
        val feedback: List<AdminFeedback> = emptyList(),
    ) {
        val id: String get() = adminHost
    }

    /** One computer's listing, as [vouched] keeps it. */
    data class Listing(val apps: List<AdminApp>, val covered: Set<String>)

    /** Candidate hosts: the owner's apps found through connectors, only among [ownersHosts] (the ones online now). */
    fun candidateHosts(apps: List<WebApp>, discovered: List<DiscoveredApp>, ownersHosts: Set<String>): List<String> {
        val hosts = apps.filter { !it.isShared && it.discovered }.map { Discovery.normalizedHost(it.host) } +
            discovered.map { Discovery.normalizedHost(it.host) }
        return hosts.toSet().filter { it in ownersHosts }.sorted()
    }

    /**
     * One computer's listing, without apps on hosts it can't vouch for (any but the owner's own
     * computers: a connector can list any name), and the hosts it covers so no other computer is
     * asked about them.
     */
    fun vouched(listed: List<AdminApp>, ownersHosts: Set<String>): Listing {
        val covered = listed.mapNotNull { it.host }.toSet().intersect(ownersHosts)
        return Listing(listed.filter { app -> app.host?.let { it in covered } ?: true }, covered)
    }

    /**
     * The hosts to ask first: each computer that answered last time, through the host it answered
     * on, then every host none of those listed. Answers are taken in this order, so a computer
     * keeps its admin host (and the screens showing it) while that answers.
     */
    fun firstRound(candidates: List<String>, known: List<Machine>): List<String> {
        val hosts = candidates.toSet()
        val kept = known.filter { it.adminHost in hosts }
        val listed = kept.flatMap { machine -> listOf(machine.adminHost) + machine.apps.mapNotNull { it.host } }.toSet()
        return kept.map { it.adminHost }.sorted() + candidates.filter { it !in listed }
    }
}

/** Decodes a JSON object with [decode]: the admin API's records, which Ovenlight reads but never writes. */
class ObjectDecoder<T>(name: String, private val decode: (JsonObject) -> T) : DeserializationStrategy<T> {
    override val descriptor = buildClassSerialDescriptor("com.snowyghost.ovenlight.$name")

    override fun deserialize(decoder: Decoder): T {
        val fields = (decoder as JsonDecoder).decodeJsonElement() as? JsonObject ?: throw SerializationException("not an object")
        return decode(fields)
    }
}

/**
 * An integer field, 64 bits as iOS's Int, or null when it's missing, null or another type. A
 * whole number written as 1.0 counts, as iOS reads it.
 */
private fun JsonObject.int(key: String): Long? {
    val value = (this[key] as? JsonPrimitive)?.takeIf { !it.isString } ?: return null
    value.longOrNull?.let { return it }
    val number = value.doubleOrNull ?: return null
    return if (number % 1.0 == 0.0 && number >= Long.MIN_VALUE.toDouble() && number < Long.MAX_VALUE.toDouble()) number.toLong() else null
}

/** An integer field that must be there and be one. */
private fun JsonObject.requiredInt(key: String): Long = int(key) ?: throw SerializationException(key)

/** A date field as [AdminAPI.parseDate] reads it, or null when it's missing, null or not a date. */
private fun JsonObject.date(key: String): Instant? = string(key)?.let(AdminAPI::parseDate)

/** A date field that must be there and be a date. */
private fun JsonObject.requiredDate(key: String): Instant = date(key) ?: throw SerializationException(key)

/** A string field that may be missing or null, but fails as another type. */
private fun JsonObject.optionalString(key: String): String? {
    val value = this[key]
    if (value == null || value == JsonNull) return null
    return string(key) ?: throw SerializationException(key)
}

/** A list field decoded one record at a time; empty when it's missing or not a list. */
private fun <T> JsonObject.each(key: String, decoder: DeserializationStrategy<T>): List<T> {
    val list = this[key] ?: return emptyList()
    return try {
        EachDecoded.decode(list, decoder).elements
    } catch (_: Exception) {
        emptyList()
    }
}

/**
 * Names as iOS's localizedStandardCompare orders them: in the phone's language ignoring case,
 * numbers by their value ("Guest 2" before "Guest 10"), then lowercase before uppercase.
 */
internal fun standardOrder(): Comparator<String> {
    val loose = Collator.getInstance().apply { strength = Collator.SECONDARY }
    val strict = Collator.getInstance().apply { strength = Collator.TERTIARY }
    val runs = Regex("[0-9]+|[^0-9]+")
    return Comparator { a, b -> compareNames(a, b, loose, strict, runs) }
}

private fun compareNames(a: String, b: String, loose: Collator, strict: Collator, runs: Regex): Int {
    val x = runs.findAll(a).map { it.value }.toList()
    val y = runs.findAll(b).map { it.value }.toList()
    for (i in 0 until minOf(x.size, y.size)) {
        val p = x[i]
        val q = y[i]
        val order = if (p[0].isAsciiDigit() && q[0].isAsciiDigit()) p.toBigInteger().compareTo(q.toBigInteger()) else loose.compare(p, q)
        if (order != 0) return order
    }
    return x.size.compareTo(y.size).takeIf { it != 0 } ?: strict.compare(a, b)
}

private fun Char.isAsciiDigit() = this in '0'..'9'
