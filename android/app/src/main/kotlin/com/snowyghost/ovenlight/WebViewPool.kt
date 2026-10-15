package com.snowyghost.ovenlight

import android.app.ActivityManager
import android.content.Context
import android.content.MutableContextWrapper
import java.net.URI
import java.text.BreakIterator
import java.util.Locale
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * Keeps a live web view per recently used app, so switching apps doesn't reload them. Each
 * view lives outside composition on a [MutableContextWrapper], its profile named by its app's
 * data store, and is attached to the screen only while its app is open. Main thread only.
 */
class WebViewPool(private val context: Context, private val nodes: NodeManager, private val scope: CoroutineScope) {
    private val pages = mutableMapOf<UUID, AppPage>()
    private val recent = mutableListOf<UUID>()

    /** Goes up when a page is replaced, so its app's screen fetches the new one. */
    val revision = MutableStateFlow(0)

    /** Set once at launch (the guests need the pool's discards through the registry). */
    lateinit var guests: GuestManager

    /** The app on screen, unlocked; its page may download, capture and answer dialogs. */
    var onScreen: UUID? = null
        set(value) {
            if (field == value) return
            field?.let { pages[it]?.offScreen() }
            field = value
            value?.let { pages[it]?.onScreen() }
        }

    private val maxLive = maxLive(context)

    init {
        nodes.onLoggingOut = { node -> discard { it.node === node } }
        nodes.onOwnerSuffixChanged = ::discardStaleRoutes
    }

    /** The app's page, made now unless one through its current route is live. */
    fun page(app: WebApp): AppPage {
        pages[app.id]?.let { page ->
            // The app's route changed since its view was made (the owner's tailnet became
            // known, or another one): the view starts over through the right node.
            if (Routing.mayLoad(page.node, nodes.node(app))) {
                touch(app.id)
                return page
            }
            discard(app.id)
        }
        touch(app.id)
        val page = AppPage(app, nodes.node(app), this, nodes, scope, context)
        pages[app.id] = page
        page.start()
        trim(keeping = maxLive)
        return page
    }

    /** Where opening the app got, while it has a page, for Report a Problem. */
    fun gate(id: UUID): AppLoadGate? = pages[id]?.gate?.value

    /** Drops a page whose route is stale, unless the pool already replaced it. */
    fun replace(page: AppPage) {
        if (pages[page.app.id] !== page) return
        discard(page.app.id)
        revision.value += 1
    }

    /** Drops every page whose app's route changed since it was made (see [Routing.mayLoad]). */
    fun discardStaleRoutes() = discard { !Routing.mayLoad(it.node, nodes.node(it.app)) }

    private fun discard(matches: (AppPage) -> Boolean) {
        val ids = pages.filterValues(matches).keys.toList()
        if (ids.isEmpty()) return
        ids.forEach(::discard)
        revision.value += 1
    }

    fun discard(id: UUID) {
        val page = pages.remove(id) ?: return
        recent.remove(id)
        page.destroy()
    }

    /** Pages off screen go, oldest first, except those downloading, which go at a later trim. */
    fun trim(keeping: Int) {
        for (id in recent.drop(keeping)) {
            val page = pages[id] ?: continue
            if (id != onScreen && !page.isDownloading) discard(id)
        }
    }

    private fun touch(id: UUID) {
        recent.remove(id)
        recent.add(0, id)
    }

    companion object {
        /**
         * The end of every app view's user agent, so a page can tell it runs in Ovenlight.
         * WebView's own (`... Mobile Safari/537.36`) stays before it, so pages still see a phone's
         * browser.
         */
        fun userAgentName(after: String?, version: String? = BuildConfig.VERSION_NAME): String =
            listOfNotNull(after, "Ovenlight/${version ?: "?"}").filter { it.isNotEmpty() }.joinToString(" ")

        /** Live views: 1 on a low-RAM phone, 2 at 4 GB or less, else 4. */
        fun maxLive(context: Context): Int {
            val activity = context.getSystemService(ActivityManager::class.java)
            if (activity.isLowRamDevice) return 1
            val memory = ActivityManager.MemoryInfo().also(activity::getMemoryInfo)
            return if (memory.totalMem <= 4L * 1024 * 1024 * 1024) 2 else 4
        }
    }
}

/**
 * A page's alert, confirm or prompt waits for its answer, and anything can take the dialog
 * away (a sheet the Router presents, access ending, a deep link), so the answer goes through
 * here: the first one counts. Kotlin has no deinit, so whatever drops the dialog calls
 * [dropped], which answers with [fallback] unless an answer came first. Used on the main thread.
 */
class DialogAnswer<Value>(private val fallback: Value, handler: (Value) -> Unit) {
    private var handler: ((Value) -> Unit)? = handler

    operator fun invoke(value: Value) {
        val handler = handler ?: return
        this.handler = null
        handler(value)
    }

    /** The dialog went away unanswered. */
    fun dropped() = invoke(fallback)
}

/**
 * Who gets the microphone without the page asking again (Android still asks once for
 * RECORD_AUDIO): the owner's own apps (discovered, or on one of `ownersHosts`), on their own
 * origin. The camera, shared apps, other sites added by address (someone's public Funnel URL
 * among them) and every other origin get Ovenlight's prompt, since WebView has none of its
 * own. Nothing gets either while Ovenlight is locked, or while the page is kept off screen.
 */
object MediaCapturePolicy {
    enum class MediaCaptureType { Microphone, Camera, CameraAndMicrophone }

    enum class PermissionDecision { Prompt, Grant, Deny }

    fun decide(
        type: MediaCaptureType, scheme: String, host: String, app: WebApp,
        ownersHosts: Set<String>, locked: Boolean, onScreen: Boolean,
    ): PermissionDecision {
        if (locked || !onScreen) return PermissionDecision.Deny
        val own = type == MediaCaptureType.Microphone && !app.isShared &&
            (app.discovered || Discovery.normalizedHost(app.host) in ownersHosts) &&
            scheme.lowercase() == "https" &&
            Discovery.normalizedHost(host) == Discovery.normalizedHost(app.host)
        return if (own) PermissionDecision.Grant else PermissionDecision.Prompt
    }
}

/**
 * Which downloads go ahead: only the app's own files (its host, or a blob or data URL its
 * pages made), and none while Ovenlight is locked. A blob or data URL a frame of another
 * site started never goes ahead. An http(s) one is checked by its host alone, since Android
 * can't tell which frame started it (docs/security.md). A download leaves Ovenlight only
 * through Ovenlight's Save, Share and Open sheet.
 */
object DownloadPolicy {
    /**
     * [from]: the host of the frame that started it, when Ovenlight knows it. iOS's WebKit
     * names the frame of an `<a download>` and no frame for a navigation response. Android's
     * DownloadListener names none, so an http(s) download passes null and is checked by the
     * URL's host alone. A blob or data URL arrives through the page script's report of the
     * click that started it, which passes that message's origin host: a data URL goes ahead
     * only with one from the app's own host.
     */
    fun allows(url: URI, from: String?, appHost: String, locked: Boolean): Boolean {
        val app = Discovery.normalizedHost(appHost)
        if (locked) return false
        if (from != null && Discovery.normalizedHost(from) != app) return false
        return when (url.scheme?.lowercase()) {
            "http", "https" -> Discovery.normalizedHost(url.host ?: "") == app
            "blob" -> NavigationPolicy.blobHost(url) == app
            // Has no origin of its own: only one the app's own page started.
            "data" -> from != null
            else -> false
        }
    }

    enum class ResponseAction { Show, Download }

    /**
     * A response sent as an attachment, or a successful one WebView can't show, is a file to
     * keep (an empty one gets no sheet). [status] is null for a blob or data URL. An http(s)
     * download passes the status of Ovenlight's own fetch of it, since Android's
     * DownloadListener gives none, so an error body is no file there either. 204, 205 and 304
     * have no body, and the page stays as it is. An error the view can't show (one with no
     * Content-Type is `application/octet-stream`) isn't a file.
     */
    fun action(status: Int?, disposition: String?, canShow: Boolean): ResponseAction {
        if (status == 204 || status == 205 || status == 304) return ResponseAction.Show
        val isSuccess = status?.let { it in 200..<300 } ?: true
        // The type is the part before any `;`, in any case, with spaces allowed around it.
        val type = disposition?.split(';', limit = 2)?.first()?.trimmingWhitespace() ?: ""
        val isFile = type.equals("attachment", ignoreCase = true) || !canShow && isSuccess
        return if (isFile) ResponseAction.Download else ResponseAction.Show
    }

    /**
     * The page's name for the file, made safe to write: no folders, no hidden or empty
     * names, and short enough for the file system, keeping the extension.
     */
    fun fileName(suggested: String): String {
        val replaced = buildString {
            suggested.codePoints().forEach { c ->
                if (c.isUnsafeInFileName()) append('-') else appendCodePoint(c)
            }
        }
        var name = replaced.trim { it == '.' || it.isWhitespaceOrNewline() }
        // Foundation takes a suffix with a space in it for no extension.
        val ext = name.substringAfterLast('.', "").takeIf { ' ' !in it } ?: ""
        val keepsExtension = ext.isNotEmpty() && ext.utf8Count <= 16
        while (name.utf8Count > maxFileNameBytes) {
            val stem = if (keepsExtension) name.substringBeforeLast('.') else name
            name = stem.droppingLastCharacter() + (if (keepsExtension) ".$ext" else "")
        }
        return name.ifEmpty { "Download" }
    }

    /** Well under the 255 bytes ext4 and f2fs allow, as on iOS. */
    const val maxFileNameBytes = 200

    /**
     * Path separators, colons and iOS's `.controlCharacters` (Cc and Cf). A lone surrogate,
     * which a page's script can put in a name and no Swift string holds, too.
     */
    private fun Int.isUnsafeInFileName(): Boolean =
        this == '/'.code || this == '\\'.code || this == ':'.code ||
            Character.getType(this).let { it == Character.CONTROL.toInt() || it == Character.FORMAT.toInt() || it == Character.SURROGATE.toInt() }

    private val String.utf8Count: Int get() = toByteArray(Charsets.UTF_8).size

    /** Without its last character as people see it, as Swift's `dropLast()` drops one. */
    private fun String.droppingLastCharacter(): String {
        val breaks = BreakIterator.getCharacterInstance(Locale.ROOT)
        breaks.setText(this)
        breaks.last()
        val start = breaks.previous()
        return if (start == BreakIterator.DONE) "" else substring(0, start)
    }
}
