package com.snowyghost.ovenlight

import java.net.URI
import java.util.UUID
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.buildClassSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonEncoder
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * One web app the user can open. Each app gets its own WebView profile, so cookies,
 * localStorage and caches never leak between apps.
 */
@Serializable(with = WebApp.Serializer::class)
data class WebApp(
    val name: String,
    val startURL: URI,
    val themeColorHex: String? = null,
    /** Found on the tailnet through a connector's ovenlight.json, rather than added by URL. */
    val discovered: Boolean = false,
    val id: UUID = UUID.randomUUID(),
    /** File name of the cached icon inside the icons directory, if one was fetched. */
    val iconFile: String? = null,
    val dataStoreID: UUID = UUID.randomUUID(),
    /**
     * The name the app's manifest gave it last, so a later rename in the manifest can replace it
     * without overwriting a name the user chose.
     */
    val manifestName: String? = null,
    /** The app's slug on its connector, from ovenlight.json or the invite. */
    val slug: String? = null,
    /**
     * Set for an app someone else shared: the guest membership whose node reaches it. Null for
     * the owner's own apps and apps added by address.
     */
    val membershipID: String? = null,
    /** Who shared it ("Riley"), for "from Riley" on its tile. */
    val sharedBy: String? = null,
    /**
     * Arrived on its own (shared with this phone, or discovered) and not opened yet: its tile
     * shows the dot a newly installed app gets.
     */
    val isNew: Boolean = false,
) {
    /** Shared with this phone by someone else, as a guest. */
    val isShared: Boolean get() = membershipID != null

    /** Served by an Ovenlight connector, which answers feedback and whoami. */
    val hasConnector: Boolean get() = isShared || discovered

    val host: String get() = startURL.host ?: ""

    /** True unless the user renamed the app. */
    val hasDefaultName: Boolean get() = name.isEmpty() || name == host || name == manifestName

    /**
     * Only what opens the app and finds its data is required; the rest falls back, so a file
     * another version wrote still loads.
     */
    object Serializer : KSerializer<WebApp> {
        override val descriptor = buildClassSerialDescriptor("com.snowyghost.ovenlight.WebApp")

        override fun deserialize(decoder: Decoder): WebApp {
            val fields = (decoder as JsonDecoder).decodeJsonElement() as? JsonObject ?: throw SerializationException("not an object")
            // A shared app without its membership would count as the owner's own.
            val membership = fields["membershipID"]
            if (membership != null && membership != JsonNull && fields.string("membershipID") == null) {
                throw SerializationException("membershipID")
            }
            return WebApp(
                id = fields.uuid("id"),
                startURL = uri(fields.requiredString("startURL")) ?: throw SerializationException("startURL"),
                dataStoreID = fields.uuid("dataStoreID"),
                membershipID = fields.string("membershipID"),
                name = fields.string("name") ?: "",
                themeColorHex = fields.string("themeColorHex"),
                iconFile = fields.string("iconFile"),
                discovered = fields.boolean("discovered") ?: false,
                manifestName = fields.string("manifestName"),
                slug = fields.string("slug"),
                sharedBy = fields.string("sharedBy"),
                isNew = fields.boolean("isNew") ?: false,
            )
        }

        override fun serialize(encoder: Encoder, value: WebApp) {
            (encoder as JsonEncoder).encodeJsonElement(
                buildJsonObject {
                    put("id", value.id.toString().uppercase())
                    put("name", value.name)
                    put("startURL", value.startURL.toString())
                    value.themeColorHex?.let { put("themeColorHex", it) }
                    value.iconFile?.let { put("iconFile", it) }
                    put("dataStoreID", value.dataStoreID.toString().uppercase())
                    put("discovered", value.discovered)
                    value.manifestName?.let { put("manifestName", it) }
                    value.slug?.let { put("slug", it) }
                    value.membershipID?.let { put("membershipID", it) }
                    value.sharedBy?.let { put("sharedBy", it) }
                    put("isNew", value.isNew)
                },
            )
        }

        private val uuidPattern = Regex("[0-9A-Fa-f]{8}-[0-9A-Fa-f]{4}-[0-9A-Fa-f]{4}-[0-9A-Fa-f]{4}-[0-9A-Fa-f]{12}")

        /** A UUID in its one written form, as iOS reads one. */
        private fun JsonObject.uuid(key: String): UUID {
            val text = requiredString(key)
            if (!uuidPattern.matches(text)) throw SerializationException(key)
            return UUID.fromString(text)
        }
    }
}

/**
 * Folds apps found on the tailnet into the user's list: one tile per host, however the app got
 * there, and never one the user removed.
 */
object DiscoveryMerge {
    fun merge(found: List<DiscoveredApp>, into: List<WebApp>, dismissedHosts: Set<String>): List<WebApp> {
        val apps = into.toMutableList()
        val seen = mutableSetOf<String>()
        for (item in found) {
            val host = Discovery.normalizedHost(item.host)
            if (host.isEmpty() || host in dismissedHosts || !seen.add(host)) continue
            // Only the owner's own apps; a shared app belongs to its owner's network.
            val index = apps.indexOfFirst { !it.isShared && Discovery.normalizedHost(it.host) == host }
            if (index >= 0) {
                val app = apps[index]
                apps[index] = app.copy(
                    name = if (app.hasDefaultName) item.manifest.name else app.name,
                    manifestName = item.manifest.name,
                    themeColorHex = item.manifest.themeColor ?: app.themeColorHex,
                    slug = item.manifest.slug ?: app.slug,
                    // Added by its address, it's still a connector's app: Send Feedback and Share.
                    discovered = true,
                )
            } else {
                apps += WebApp(
                    name = item.manifest.name, startURL = item.startURL, themeColorHex = item.manifest.themeColor, discovered = true,
                    manifestName = item.manifest.name, slug = item.manifest.slug, isNew = true,
                )
            }
        }
        return apps
    }
}

/** Parses an app's home page and web manifest into display metadata. */
object ManifestParser {
    data class Metadata(val name: String? = null, val themeColorHex: String? = null, val iconURL: URI? = null)

    data class Links(val manifest: URI?, val touchIcon: URI?)

    private val linkTag = Regex("""<link\b[^>]*>""", RegexOption.IGNORE_CASE)
    private val titleTag = Regex("<title[^>]*>([^<]*)</title>", RegexOption.IGNORE_CASE)
    private val anyTag = Regex("<[^>]+>")
    private val integer = Regex("[+-]?[0-9]+")

    /** Finds `<link rel="manifest">` and `<link rel="apple-touch-icon">` in an HTML page. */
    fun links(inHTML: String, baseURL: URI): Links {
        var manifest: URI? = null
        var touchIcon: URI? = null
        for (tag in linkTag.findAll(inHTML).map { it.value }) {
            val rel = attribute("rel", tag)?.lowercase() ?: continue
            val url = attribute("href", tag)?.let(baseURL::resolving) ?: continue
            val rels = rel.split(' ').filter { it.isNotEmpty() }
            if (manifest == null && "manifest" in rels) manifest = url
            if (touchIcon == null && "apple-touch-icon" in rels) touchIcon = url
        }
        return Links(manifest, touchIcon)
    }

    fun title(inHTML: String): String? {
        val match = titleTag.find(inHTML) ?: return null
        val trimmed = match.value.replace(anyTag, "").trimmingWhitespaceAndNewlines()
        return trimmed.ifEmpty { null }
    }

    /** Reads name, theme color and the best raster icon from a web manifest. */
    fun metadata(fromManifest: ByteArray, manifestURL: URI): Metadata {
        val json = try {
            ovenlightJSON.parseToJsonElement(strictUTF8(fromManifest)) as? JsonObject
        } catch (_: Exception) {
            null
        } ?: return Metadata()
        val name = json.string("name") ?: json.string("short_name")
        val theme = json.string("theme_color")
        var best: Pair<Int, URI>? = null
        // As iOS reads it, a list with anything but objects in it is no list of icons.
        val icons = (json["icons"] as? JsonArray)?.takeIf { list -> list.all { it is JsonObject } }?.map { it as JsonObject } ?: emptyList()
        for (icon in icons) {
            val url = icon.string("src")?.let(manifestURL::resolving) ?: continue
            // Skip SVG, which the icon cache doesn't draw, and anything declared as it.
            if (icon.string("type")?.contains("svg") == true) continue
            if (url.path.orEmpty().substringAfterLast('/').substringAfterLast('.', "").lowercase() == "svg") continue
            val size = largestSize(icon.string("sizes"))
            // Prefer the largest icon up to 512px; anything bigger only wastes download.
            val score = if (size > 512) 512 - (size - 512) / 1000 else size
            if (best == null || score > best.first) best = score to url
        }
        return Metadata(name, theme, best?.second)
    }

    private fun largestSize(sizes: String?): Int {
        if (sizes == null) return 0
        return sizes.split(' ').filter { it.isNotEmpty() }.mapNotNull { token ->
            val parts = token.lowercase().split('x').filter { it.isNotEmpty() }
            if (parts.size == 2 && integer.matches(parts[0])) parts[0].toIntOrNull() else null
        }.maxOrNull() ?: 0
    }

    private fun attribute(name: String, tag: String): String? {
        val pattern = Regex("""\b$name\s*=\s*(?:"([^"]*)"|'([^']*)'|([^\s>]+))""", RegexOption.IGNORE_CASE)
        val match = pattern.find(tag) ?: return null
        return (1..3).firstNotNullOfOrNull { match.groups[it]?.value }
    }
}

/** Decides what happens when an app's page tries to navigate somewhere. */
object NavigationPolicy {
    sealed class Decision {
        data object Allow : Decision()

        /** Leave the app: open in a Custom Tab (web) or hand to the system (mailto, tel...). */
        data class OpenExternally(val url: URI) : Decision()
        data object Refuse : Decision()
    }

    /**
     * Schemes the system itself confirms before acting: Android's dialer opens with the number
     * for the person to call, so `tel:` needs no question. Every other app Ovenlight asks about
     * first, since a page's script can fake a link tap. iOS also leaves out FaceTime, which
     * Android doesn't have.
     */
    val systemConfirms = setOf("tel")

    /**
     * Schemes a page never opens: an `android-app:` link can start any exported activity with
     * extras of the page's choosing, and `file:` and `content:` would read the phone's own
     * files. iOS has none of them. An `intent:` link is another app, asked about first, once
     * PageEdges has taken out everything that could aim it elsewhere.
     */
    val androidRefuses = setOf("android-app", "file", "content")

    /** Mail and messages, which another app composes (`ACTION_SENDTO`). */
    val composes = setOf("mailto", "sms", "smsto", "mms", "mmsto")

    fun asksBeforeOpening(url: URI): Boolean = url.scheme?.lowercase() !in systemConfirms

    /**
     * Ovenlight's own links: its scheme, and every address its App Link takes (the join page,
     * in any letter case and on any port). A page never hands one to Ovenlight, since it could
     * pick the invite: the scheme is refused everywhere, and the join page opens only in a
     * named browser (CustomTabs) at [joinPage], never through a hand-off Ovenlight's App Link
     * could catch.
     */
    fun isOvenlightLink(url: URI): Boolean {
        val scheme = url.scheme?.lowercase()
        if (scheme == InviteLink.scheme) return true
        if (scheme != "http" && scheme != "https") return false
        return Discovery.normalizedHost(url.host ?: "") == InviteLink.joinPage.host && url.path?.lowercase() in InviteLink.universalPaths
    }

    /**
     * The join page as the site serves it without a redirect, keeping the query and the
     * invite in the fragment. The site redirects its other forms to it, and a browser may hand
     * a redirect to the App Link that takes it, which is Ovenlight; a tab's first page from
     * Ovenlight stays in the browser.
     */
    fun joinPage(url: URI): URI =
        URI(InviteLink.joinPage.toString() + (url.rawQuery?.let { "?$it" } ?: "") + (url.rawFragment?.let { "#$it" } ?: ""))

    /** What an `intent:` link may hand another app: no data, or data that is neither the phone's files nor Ovenlight's own link. */
    fun mayCarry(data: URI?): Boolean {
        if (data == null) return true
        val scheme = data.scheme?.lowercase() ?: return false
        return scheme != "intent" && scheme !in androidRefuses && !isOvenlightLink(data)
    }

    /**
     * An intent extra that names an address, which another app might load or read (a
     * `browser_fallback_url`, an output path): anything starting with a scheme or `//`.
     * Plain text that happens to look like one goes too, which costs nothing.
     */
    fun carriesURI(value: String): Boolean {
        val text = value.trimmingWhitespace()
        return text.startsWith("//") || uriScheme.containsMatchIn(text)
    }

    private val uriScheme = Regex("^[A-Za-z][A-Za-z0-9+.-]*:")

    /**
     * The longest address Ovenlight hands to another app or a browser, in characters (about
     * 64 KB). A page can make one of megabytes, which no intent carries.
     */
    const val maxHandOffLength = 64 * 1024

    /**
     * A page's address as java.net.URI, read as Chromium writes it. An http(s) address goes
     * through OkHttp's HttpUrl, which encodes what java.net.URI refuses (`{`, `"`, `|`, `^`,
     * `[` in a path); anything else is read as written. Null, so refused, when it can't be
     * read, or when the encoding would name another host (HttpUrl drops characters such as
     * `{` from one, which Chromium keeps).
     */
    fun pageURI(text: String): URI? {
        val http = text.toHttpUrlOrNull() ?: return uri(text)
        // toUri throws for some userinfo HttpUrl takes, such as a stray `%`.
        val url = runCatching { http.toUri() }.getOrNull() ?: return null
        // java.net.URI leaves the host out for names it won't take (`a_b.com`), as before.
        val host = url.host?.removeSurrounding("[", "]") ?: return url
        return url.takeIf { host.equals(http.host, ignoreCase = true) }
    }

    /** The link as the confirmation names it, cut short. */
    fun target(url: URI): String {
        val text = url.toString()
        return "\u201C" + (if (text.graphemeCount() > 80) text.graphemePrefix(79) + "\u2026" else text) + "\u201D"
    }

    /** [tapped]: a link activated in the main frame. Other apps (mailto, tel...) open only then, never from a script or a frame. */
    fun decide(url: URI, appHost: String, isMainFrame: Boolean, tapped: Boolean): Decision {
        val scheme = url.scheme?.lowercase() ?: ""
        return when (scheme) {
            "http", "https" -> when {
                !isMainFrame -> Decision.Allow
                Discovery.normalizedHost(url.host ?: "") == Discovery.normalizedHost(appHost) -> Decision.Allow
                else -> Decision.OpenExternally(url)
            }
            // A frame of another site can make one too: the page shows only the app's own.
            "blob" -> if (!isMainFrame || blobHost(url) == Discovery.normalizedHost(appHost)) Decision.Allow else Decision.Refuse
            "about", "data", "javascript" -> Decision.Allow
            // Never Ovenlight's own scheme: a page could hand it an invite of its choosing.
            else -> if (tapped && isMainFrame && !isOvenlightLink(url) && scheme !in androidRefuses) {
                Decision.OpenExternally(url)
            } else {
                Decision.Refuse
            }
        }
    }

    /** The host of the page that made a blob URL: `blob:https://host/<uuid>` names its origin. */
    fun blobHost(url: URI): String? = uri(url.toString().drop("blob:".length))?.let { Discovery.normalizedHost(it.host ?: "") }
}

/**
 * Turns what someone types into Add App into an app address. A bare host gets https; anything
 * that isn't https is refused. Add App opens only the owner's own computers: apps anywhere else
 * come by an invite, or by discovery once their owner publishes them.
 */
object AppAddress {
    fun url(from: String): URI? {
        var text = from.trimmingWhitespaceAndNewlines()
        if (!text.contains("://")) text = "https://$text"
        val url = uri(text) ?: return null
        if (url.scheme?.lowercase() != "https" || url.host?.contains('.') != true) return null
        return url
    }

    /** The address, only if it's on the owner's own tailnet. A null [ownerSuffix] (owner mode off, or not signed in yet) takes nothing. */
    fun url(from: String, ownerSuffix: String?): URI? {
        val url = url(from) ?: return null
        return if (Routing.isOnOwnersTailnet(url.host ?: "", ownerSuffix)) url else null
    }

    // Guests can open Add App too, so none of this names the network underneath.
    const val signInFirst = "Add app opens apps on your own computers. Connect to them first, then add an app by its address."
    const val notYourComputer = "Ovenlight opens apps on your own computers. Publish this app with the Ovenlight connector, or ask its owner for an invite."
}
