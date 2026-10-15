package com.snowyghost.ovenlight

import java.io.ByteArrayOutputStream
import java.net.URI
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.text.BreakIterator
import java.util.Locale
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder

/**
 * An invite to one app on someone else's computer, as the owner's connector writes it: an
 * App Link, `https://ovenlight.app/join#v=1&control=&key=&owner=&app=&invite=&host=&name=&to=`,
 * or the same fields as `ovenlight://join?...`. The fields ride in the App Link's fragment,
 * which never reaches the website. The key is a single-use credential until Ovenlight has
 * joined with it.
 */
@Serializable
data class InviteLink(
    /** The owner's control server, where Ovenlight's guest node registers. */
    @Serializable(with = URISerializer::class) val control: URI,
    /** Single-use auth key for the guest node; also proves the claim. */
    val key: String,
    /** How the owner calls themself ("Riley"). */
    val owner: String,
    /** The app's slug on the owner's connector. */
    val app: String,
    /** The invite's ID on the connector. */
    val invite: String,
    /** The app node's name, where Ovenlight claims the invite and opens the app. */
    val host: String,
    /** The app's name. */
    val name: String,
    /** What the owner called the person they invited ("Sam"). */
    val to: String,
) {
    /** Why a link can't be used, with what Ovenlight says about it. Neutral: anyone can see it. */
    sealed class ParseError(override val message: String) : Exception(message) {
        data object NotAnInvite : ParseError("This isn't an Ovenlight invite. Ask for the link again and copy the whole link.")
        data object UnsupportedVersion : ParseError("This invite needs a newer Ovenlight. Update Ovenlight, then open the invite again.")
        data object InsecureControl : ParseError("This invite isn't secure, so Ovenlight won't use it. Ask for a new one.")
        data class MissingField(val field: String) : ParseError(INCOMPLETE)
        data class InvalidField(val field: String) : ParseError(INCOMPLETE)

        private companion object {
            const val INCOMPLETE = "This link is incomplete. Ask for it again and copy the whole link."
        }
    }

    val appURL: URI get() = URI("https://$host/")

    /**
     * The owner's network, as the part of the app's name after its first label. Two invites
     * with the same control server and domain come from the same owner.
     */
    val networkDomain: String get() = networkDomain(host)

    /** Registers with Tailscale's own control server rather than one the owner runs. */
    val usesTailscaleControl: Boolean get() = controlKey(control) == tailscaleKey

    /**
     * Whether a node's MagicDNS suffix is the network this invite names. A guest node must be
     * in it before it claims or counts as joined.
     */
    fun isNetwork(magicDNSSuffix: String?): Boolean {
        val suffix = magicDNSSuffix?.let(Discovery::normalizedHost)
        if (suffix.isNullOrEmpty()) return false
        return suffix == networkDomain
    }

    /** The fields in the connector's order, form-encoded: the query of `ovenlight://join` and the fragment of the App Link. */
    private val encodedFields: String
        get() = listOf(
            "v" to version, "control" to control.toString(), "key" to key, "owner" to owner,
            "app" to app, "invite" to invite, "host" to host, "name" to name, "to" to to,
        ).joinToString("&") { (name, value) -> "$name=${formEncode(value)}" }

    /** The link to send: the App Link, which opens Ovenlight when it's installed. */
    val universalURL: URI get() = URI(joinPage.toString() + "#" + encodedFields)

    /** The same invite as `ovenlight://join?...`, for when the App Link opens the page. */
    val url: URI get() = URI("$scheme://join?$encodedFields")

    companion object {
        const val version = "1"
        const val maxLabel = 64

        /** The link scheme Ovenlight opens. */
        const val scheme = "ovenlight"

        /** Tailscale's control server, by either of its names. */
        val tailscaleControlHosts = setOf("controlplane.tailscale.com", "login.tailscale.com")

        private const val tailscaleKey = "tailscale"

        /** The page App Links open. With Ovenlight installed Android hands them to it; otherwise the page offers Ovenlight. */
        val joinPage: URI = URI("https://ovenlight.app/join")

        /** Paths of the App Link: the site serves the page with and without `.html`, and a trailing slash still reaches it. */
        private val universalPaths = setOf("/join", "/join/", "/join.html")

        // Up to white space as iOS's ICU regex reads \s, the same on the JVM and Android.
        private val linkInText = Regex(
            """(ovenlight://join\?|https://ovenlight\.app/join(\.html|/)?#)[^\t\n\f\r\p{Z}<>"']+""",
            RegexOption.IGNORE_CASE,
        )

        /** Debug builds accept an http control server on this computer (see [parse]). */
        val isDebugBuild: Boolean get() = BuildConfig.DEBUG

        private fun controlKey(url: URI): String {
            val scheme = url.scheme?.lowercase() ?: ""
            val host = Discovery.normalizedHost(url.bareHost ?: "")
            val port = if (url.port != -1) url.port else if (scheme == "http") 80 else 443
            if (scheme == "https" && host in tailscaleControlHosts && port == 443) return tailscaleKey
            return "$scheme://$host:$port"
        }

        fun networkDomain(host: String): String {
            val normalized = Discovery.normalizedHost(host)
            val dot = normalized.indexOf('.')
            return if (dot < 0) normalized else normalized.substring(dot + 1)
        }

        /** Whether a link is an invite, in any form Ovenlight accepts, before checking its fields. */
        fun isInvite(url: URI): Boolean = encodedFields(url) != null

        /** The form-encoded fields of an invite link: the query of `ovenlight://join`, or the fragment of the App Link. */
        private fun encodedFields(url: URI): String? {
            val scheme = url.scheme?.lowercase() ?: ""
            if (scheme == this.scheme) {
                if (url.host?.lowercase() != "join") return null
                return url.rawQuery ?: ""
            }
            if (scheme != "https" || url.host?.lowercase() != joinPage.host || url.port != -1) return null
            // As iOS reads a path, without its trailing slash.
            val path = url.path?.let { if (it.length > 1) it.removeSuffix("/") else it }
            if (path !in universalPaths) return null
            return url.rawFragment?.takeIf { it.isNotEmpty() }
        }

        /**
         * Reads and checks a link. [allowLocalHTTP] accepts an http control server on this
         * computer, for development against a local Headscale; release builds never do.
         */
        fun parse(url: URI, allowLocalHTTP: Boolean = isDebugBuild): InviteLink {
            val fields = encodedFields(url) ?: throw ParseError.NotAnInvite
            val items = fields.split('&').filter { it.isNotEmpty() }.map { pair ->
                val equals = pair.indexOf('=')
                if (equals < 0) pair to "" else pair.substring(0, equals) to pair.substring(equals + 1)
            }
            // The connector form-encodes: "+" is a space, a literal plus arrives as %2B.
            fun value(name: String): String? =
                items.firstOrNull { it.first == name }?.second?.replace("+", "%20")?.let(::removingPercentEncoding)
            fun required(name: String): String =
                value(name)?.trimmingWhitespaceAndNewlines()?.takeIf { it.isNotEmpty() } ?: throw ParseError.MissingField(name)

            if (value("v") != version) throw ParseError.UnsupportedVersion

            val control = uri(required("control"))
            val controlHost = control?.bareHost
            if (control == null || controlHost.isNullOrEmpty() || control.rawUserInfo != null) throw ParseError.InvalidField("control")
            val local = allowLocalHTTP && controlHost.lowercase() in setOf("127.0.0.1", "localhost", "::1")
            when (control.scheme?.lowercase()) {
                "https" -> {}
                "http" -> if (!local) throw ParseError.InsecureControl
                else -> throw ParseError.InsecureControl
            }

            val key = required("key")
            if (key.length > 256 || !key.all { it.code < 128 && !it.isAsciiWhitespace() }) throw ParseError.InvalidField("key")
            val app = required("app")
            if (app.length > 64 || !app.all { it.isAsciiLetterOrDigit() || it == '-' || it == '_' }) throw ParseError.InvalidField("app")
            val invite = required("invite")
            if (invite.length > 64 || !invite.all { it.isAsciiLetterOrDigit() || it == '-' }) throw ParseError.InvalidField("invite")
            val host = Discovery.normalizedHost(required("host"))
            if (!isDNSName(host)) throw ParseError.InvalidField("host")

            return InviteLink(
                control = control, key = key, owner = label(value("owner")) ?: "Someone", app = app, invite = invite,
                host = host, name = label(value("name")) ?: app, to = label(value("to")) ?: "",
            )
        }

        /** Finds an invite in pasted text, for example the whole message the owner sent. */
        fun parse(text: String, allowLocalHTTP: Boolean = isDebugBuild): InviteLink {
            val match = linkInText.find(text) ?: throw ParseError.NotAnInvite
            return parse(uri(match.value) ?: throw ParseError.NotAnInvite, allowLocalHTTP)
        }

        /** A display label: trimmed, without control characters, at most 64 characters as people see them. */
        fun label(text: String?): String? {
            if (text == null) return null
            val visible = StringBuilder()
            text.codePoints().filter { !isHidden(it) }.forEach { visible.appendCodePoint(it) }
            val cleaned = visible.toString().trimmingWhitespaceAndNewlines()
            return if (cleaned.isEmpty()) null else cleaned.graphemePrefix(maxLabel)
        }

        /**
         * Control and invisible format characters, bidi overrides among them, which can make a
         * label read as something else. Not the joiners that emoji sequences and scripts such as
         * Persian and Hindi need (U+200C, U+200D), nor the tags in flags like England's.
         */
        private fun isHidden(codePoint: Int): Boolean = when (codePoint) {
            0x200C, 0x200D, in 0xE0020..0xE007F -> false
            else -> Character.getType(codePoint).let { it == Character.CONTROL.toInt() || it == Character.FORMAT.toInt() }
        }

        /** A multi-label DNS name: letters, digits and hyphens, no IP address, no port. */
        fun isDNSName(host: String): Boolean {
            val labels = host.split('.')
            if (labels.size < 2 || host.length > 253 || host.all { it.isAsciiDigit() || it == '.' }) return false
            return labels.all { label ->
                label.isNotEmpty() && label.length <= 63 && !label.startsWith("-") && !label.endsWith("-") &&
                    label.all { it.isAsciiLetterOrDigit() || it == '-' }
            }
        }

        private fun formEncode(text: String): String {
            val out = StringBuilder()
            for (byte in text.toByteArray(Charsets.UTF_8)) {
                val c = (byte.toInt() and 0xFF).toChar()
                when {
                    c.isAsciiLetterOrDigit() || c in "-._~" -> out.append(c)
                    c == ' ' -> out.append('+')
                    else -> out.append('%').append(String.format(Locale.ROOT, "%02X", byte.toInt() and 0xFF))
                }
            }
            return out.toString()
        }
    }
}

/** java.net.URI as its string, as iOS saves a URL. */
object URISerializer : KSerializer<URI> {
    override val descriptor = PrimitiveSerialDescriptor("com.snowyghost.ovenlight.URI", PrimitiveKind.STRING)

    override fun serialize(encoder: Encoder, value: URI) = encoder.encodeString(value.toString())

    override fun deserialize(decoder: Decoder): URI {
        val text = decoder.decodeString()
        return uri(text) ?: throw SerializationException("not a URL: $text")
    }
}

/** The address, or null when it isn't one, as iOS's `URL(string:)` answers. */
internal fun uri(text: String): URI? = try {
    URI(text)
} catch (_: Exception) {
    null
}

/** A link resolved against this one, as iOS's `URL(string:relativeTo:)` does: a base with no path counts as "/". */
internal fun URI.resolving(reference: String): URI? = try {
    (if (rawAuthority != null && rawPath.isNullOrEmpty()) resolve("/") else this).resolve(reference)
} catch (_: Exception) {
    null
}

/** The host without the brackets java.net.URI keeps around an IPv6 address, as iOS's `URL.host` gives it. */
internal val URI.bareHost: String?
    get() = host?.removeSurrounding("[", "]")

/** Percent-decoded as UTF-8, or null when an escape is malformed or the bytes aren't UTF-8. */
internal fun removingPercentEncoding(text: String): String? {
    val bytes = ByteArrayOutputStream()
    var i = 0
    while (i < text.length) {
        val c = text[i]
        if (c == '%') {
            if (i + 2 >= text.length) return null
            val high = hexDigit(text[i + 1])
            val low = hexDigit(text[i + 2])
            if (high < 0 || low < 0) return null
            bytes.write(high * 16 + low)
            i += 3
        } else {
            bytes.write(c.toString().toByteArray(Charsets.UTF_8))
            i++
        }
    }
    return try {
        Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes.toByteArray()))
            .toString()
    } catch (_: CharacterCodingException) {
        null
    }
}

/** Trimmed of Unicode white space and newlines, as iOS's `.whitespacesAndNewlines` trims. */
internal fun String.trimmingWhitespaceAndNewlines(): String = trim {
    it in '\t'..'\r' || it == '\u0085' || Character.getType(it).let { type ->
        type == Character.SPACE_SEPARATOR.toInt() || type == Character.LINE_SEPARATOR.toInt() ||
            type == Character.PARAGRAPH_SEPARATOR.toInt()
    }
}

/** Trimmed of spaces and tabs, as iOS's `.whitespaces` trims. */
internal fun String.trimmingWhitespace(): String = trim { it == '\t' || Character.getType(it) == Character.SPACE_SEPARATOR.toInt() }

/** The text's first [count] characters as people see them: grapheme clusters, an emoji family or a flag counting once. */
internal fun String.graphemePrefix(count: Int): String {
    val breaks = BreakIterator.getCharacterInstance(Locale.ROOT)
    breaks.setText(this)
    var end = 0
    repeat(count) {
        val next = breaks.next()
        if (next == BreakIterator.DONE) return this
        end = next
    }
    return substring(0, end)
}

/** How many characters people see in the text (see [graphemePrefix]). */
internal fun String.graphemeCount(): Int {
    val breaks = BreakIterator.getCharacterInstance(Locale.ROOT)
    breaks.setText(this)
    var count = 0
    while (breaks.next() != BreakIterator.DONE) count++
    return count
}

private fun hexDigit(c: Char): Int = when (c) {
    in '0'..'9' -> c - '0'
    in 'a'..'f' -> c - 'a' + 10
    in 'A'..'F' -> c - 'A' + 10
    else -> -1
}

private fun Char.isAsciiDigit() = this in '0'..'9'

private fun Char.isAsciiLetterOrDigit() = this in 'a'..'z' || this in 'A'..'Z' || isAsciiDigit()

private fun Char.isAsciiWhitespace() = this == ' ' || this in '\t'..'\r'
