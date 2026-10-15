package com.snowyghost.ovenlight

import java.io.File
import java.net.URI
import java.nio.file.Files
import java.nio.file.NoSuchFileException
import java.nio.file.StandardCopyOption
import java.security.SecureRandom
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID
import java.util.logging.Logger
import kotlin.math.roundToLong
import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationStrategy
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.buildClassSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonEncoder
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.put

/**
 * Ovenlight's place in someone else's network: a guest device, one per owner whose invite was
 * accepted, with its own node, state directory and local proxy route. The owner's own network
 * is not a stored membership; it is always [ownerID].
 */
@Serializable(with = Membership.Serializer::class)
data class Membership(
    val id: String,
    /** The person who shared ("Riley"). */
    val ownerName: String,
    val controlURL: URI,
    /** The part of the app names after the first label; with the control URL it tells one owner's network from another. */
    val networkDomain: String,
    /** What the owner called this person, as the latest invite said. */
    val guestName: String,
    val created: Instant,
    /**
     * The node registered with an invite key at least once. From then on it restarts from its
     * saved state, and losing its login means the owner removed it.
     */
    val joined: Boolean,
    /** Invites accepted but not claimed yet, with their keys. */
    val pendingClaims: List<PendingClaim>,
    /** This phone's name in the owner's network, its own per membership so owners who compare notes can't tell it's the same phone. */
    val hostName: String,
) {
    constructor(invite: InviteLink, id: String = UUID.randomUUID().toString().uppercase(), created: Instant = savedNow()) : this(
        id = id, ownerName = invite.owner, controlURL = invite.control, networkDomain = invite.networkDomain,
        guestName = invite.to, created = created, joined = false, pendingClaims = emptyList(), hostName = randomHostName(),
    )

    fun matches(invite: InviteLink): Boolean =
        controlURL.toString() == invite.control.toString() && networkDomain == invite.networkDomain

    /** The key a node that hasn't joined yet registers with. */
    val joinKey: String? get() = if (joined) null else pendingClaims.firstOrNull()?.invite?.key

    companion object {
        /** The owner's own node. */
        const val ownerID = "owner"

        private val random = SecureRandom()

        /** `ovenlight-` plus a short random suffix. */
        fun randomHostName(): String {
            val alphabet = "abcdefghijklmnopqrstuvwxyz0123456789"
            return "ovenlight-" + (1..6).map { alphabet[random.nextInt(alphabet.length)] }.joinToString("")
        }
    }

    /**
     * Only what the node runs on is required; the rest falls back, so a file another version
     * wrote still loads.
     */
    object Serializer : KSerializer<Membership> {
        override val descriptor = buildClassSerialDescriptor("com.snowyghost.ovenlight.Membership")

        override fun deserialize(decoder: Decoder): Membership {
            val fields = (decoder as JsonDecoder).decodeJsonElement() as? JsonObject ?: throw SerializationException("not an object")
            val claims = fields["pendingClaims"]?.let {
                try {
                    EachDecoded.decode(it, PendingClaim.serializer()).elements
                } catch (_: Exception) {
                    null
                }
            }
            return Membership(
                id = fields.requiredString("id"),
                controlURL = uri(fields.requiredString("controlURL")) ?: throw SerializationException("controlURL"),
                networkDomain = fields.requiredString("networkDomain"),
                joined = fields.boolean("joined") ?: throw SerializationException("joined"),
                hostName = fields.requiredString("hostName"),
                ownerName = fields.string("ownerName") ?: "",
                guestName = fields.string("guestName") ?: "",
                created = fields["created"]?.let(SavedDate::decode) ?: SavedDate.distantPast,
                pendingClaims = claims ?: emptyList(),
            )
        }

        override fun serialize(encoder: Encoder, value: Membership) {
            val json = (encoder as JsonEncoder).json
            encoder.encodeJsonElement(
                buildJsonObject {
                    put("id", value.id)
                    put("ownerName", value.ownerName)
                    put("controlURL", value.controlURL.toString())
                    put("networkDomain", value.networkDomain)
                    put("guestName", value.guestName)
                    put("created", SavedDate.encode(value.created))
                    put("joined", value.joined)
                    put("pendingClaims", json.encodeToJsonElement(ListSerializer(PendingClaim.serializer()), value.pendingClaims))
                    put("hostName", value.hostName)
                },
            )
        }
    }
}

/** An accepted invite waiting for its claim at the owner's connector. */
@Serializable
data class PendingClaim(val invite: InviteLink, @Serializable(with = SavedDate::class) val accepted: Instant)

/**
 * The guest memberships, saved as JSON in [directory] (the app's `files/Ovenlight`). Main thread
 * only. Android keeps them out of backups and device transfer by the manifest's rules, as it
 * does every file.
 */
class MembershipStore(directory: File) {
    var memberships: List<Membership> = emptyList()
        private set

    /**
     * The file is there but couldn't be read or decoded. Nothing is saved over it, and nothing
     * may clean up after the empty list, until a reload reads it.
     */
    var loadFailed = false
        private set

    /** Memberships another build saved that this one can't read (see [SavedJSON.List]). */
    private var unread: List<JsonElement> = emptyList()

    /** Every saved membership was read, so cleaning up after the list deletes only what it means to. */
    val readInFull: Boolean get() = !loadFailed && unread.isEmpty()

    private val file = File(directory, "memberships.json")

    init {
        directory.mkdirs()
        load()
    }

    /** Reads the file again if it couldn't be read before. */
    fun reloadIfFailed() {
        if (loadFailed) load()
    }

    private fun load() {
        try {
            val list = SavedJSON.read(Membership.serializer(), file) ?: SavedJSON.List()
            memberships = list.elements
            unread = list.unread
            loadFailed = false
            if (unread.isNotEmpty()) SavedJSON.log.severe("memberships: kept ${unread.size} this version can't read")
        } catch (e: Exception) {
            loadFailed = true
            SavedJSON.log.severe("memberships: couldn't read: $e")
        }
    }

    operator fun get(id: String): Membership? = memberships.firstOrNull { it.id == id }

    fun membership(invite: InviteLink): Membership? = memberships.firstOrNull { it.matches(invite) }

    fun upsert(membership: Membership) {
        val index = memberships.indexOfFirst { it.id == membership.id }
        memberships = if (index >= 0) memberships.toMutableList().apply { set(index, membership) } else memberships + membership
        save()
    }

    fun update(id: String, change: (Membership) -> Membership) {
        val index = memberships.indexOfFirst { it.id == id }
        if (index < 0) return
        memberships = memberships.toMutableList().apply { set(index, change(get(index))) }
        save()
    }

    fun delete(id: String) {
        memberships = memberships.filter { it.id != id }
        save()
    }

    private fun save() {
        if (loadFailed) return
        try {
            SavedJSON.write(SavedJSON.encode(Membership.serializer(), memberships, unread), file)
        } catch (e: Exception) {
            SavedJSON.log.severe("memberships: couldn't save: $e")
        }
    }
}

/**
 * Ovenlight's saved lists. A missing file is an empty list; a file that is there but can't be
 * read or decoded throws, and must never be taken for an empty one: the next save would write
 * that over it.
 */
object SavedJSON {
    val log: Logger = Logger.getLogger("com.snowyghost.ovenlight.storage")

    /** A saved list as read. */
    class List<Element>(
        val elements: kotlin.collections.List<Element> = emptyList(),
        /**
         * Entries this version can't read (another build wrote them), as they were. A save writes
         * them back, and while there are any, nothing that deletes what the list doesn't name may
         * run: it would delete what they name.
         */
        val unread: kotlin.collections.List<JsonElement> = emptyList(),
    )

    fun <Element> read(element: DeserializationStrategy<Element>, file: File): List<Element>? {
        val text = read(file) ?: return null
        val raw = ovenlightJSON.parseToJsonElement(text)
        val decoded = EachDecoded.decode(raw, element)
        if (decoded.skipped.isEmpty()) return List(decoded.elements)
        val entries = raw as JsonArray
        return List(decoded.elements, decoded.skipped.filter(entries.indices::contains).map(entries::get))
    }

    /** [elements], then the [unread] entries as they were read. */
    fun <Element> encode(element: SerializationStrategy<Element>, elements: kotlin.collections.List<Element>, unread: kotlin.collections.List<JsonElement>): String {
        val written = elements.map { ovenlightJSON.encodeToJsonElement(element, it) }
        return ovenlightJSON.encodeToString(JsonArray.serializer(), JsonArray(written + unread))
    }

    /** The file's text, or null when there is no file. Any other failure throws. */
    fun read(file: File): String? = try {
        Files.readAllBytes(file.toPath()).decodeToString()
    } catch (_: NoSuchFileException) {
        null
    }

    /** Replaces the file in one step, so a reader sees the old list or the new one, never part of one. */
    fun write(text: String, file: File) {
        val temporary = File(file.parentFile, ".${file.name}.tmp")
        temporary.writeText(text)
        Files.move(temporary.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
    }
}

/**
 * A JSON array decoded one element at a time: an entry this version can't read is skipped rather
 * than costing the rest. Anything but an array still fails.
 */
class EachDecoded<Element>(
    val elements: List<Element> = emptyList(),
    /** The positions of the entries skipped. */
    val skipped: List<Int> = emptyList(),
) {
    companion object {
        fun <Element> decode(json: JsonElement, element: DeserializationStrategy<Element>): EachDecoded<Element> {
            val array = json as? JsonArray ?: throw SerializationException("not an array")
            val elements = mutableListOf<Element>()
            val skipped = mutableListOf<Int>()
            array.forEachIndexed { index, entry ->
                try {
                    elements += ovenlightJSON.decodeFromJsonElement(element, entry)
                } catch (_: Exception) {
                    skipped += index
                }
            }
            return EachDecoded(elements, skipped)
        }
    }
}

/**
 * JSON as Ovenlight reads it: unknown keys ignored, and a null or missing optional taken as its
 * default, as iOS's `decodeIfPresent` does.
 */
internal val ovenlightJSON = Json {
    ignoreUnknownKeys = true
    coerceInputValues = true
}

/**
 * A date as iOS saves one: seconds since 2001-01-01 UTC, as a double. A double carries today's
 * dates to the microsecond exactly, so dates are read to the microsecond and [savedNow] is one.
 */
object SavedDate : KSerializer<Instant> {
    private val reference: Instant = Instant.parse("2001-01-01T00:00:00Z")

    /** iOS's `Date.distantPast`, for a membership saved without its date. */
    val distantPast: Instant = reference.minusSeconds(63_114_076_800)

    override val descriptor = PrimitiveSerialDescriptor("com.snowyghost.ovenlight.SavedDate", PrimitiveKind.DOUBLE)

    fun encode(instant: Instant): Double = ChronoUnit.MICROS.between(reference, instant) / 1_000_000.0

    fun decode(json: JsonElement): Instant? =
        (json as? JsonPrimitive)?.takeIf { !it.isString }?.doubleOrNull?.let { reference.plus((it * 1_000_000).roundToLong(), ChronoUnit.MICROS) }

    override fun serialize(encoder: Encoder, value: Instant) = encoder.encodeDouble(encode(value))

    override fun deserialize(decoder: Decoder): Instant =
        reference.plus((decoder.decodeDouble() * 1_000_000).roundToLong(), ChronoUnit.MICROS)
}

/** Now, to the microsecond a saved date keeps. */
internal fun savedNow(): Instant = Instant.now().truncatedTo(ChronoUnit.MICROS)

/** A string field, or null when it's missing, null or another type. */
internal fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content

/** A string field that must be there: missing, null or another type fails, as iOS's `decode` does. */
internal fun JsonObject.requiredString(key: String): String = string(key) ?: throw SerializationException(key)

/** A boolean field, or null when it's missing, null or another type. */
internal fun JsonObject.boolean(key: String): Boolean? =
    (this[key] as? JsonPrimitive)?.takeIf { !it.isString && it !is JsonNull }?.booleanOrNull
