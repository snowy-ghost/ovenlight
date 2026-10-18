package com.snowyghost.ovenlight

import android.app.ActivityManager
import android.content.ComponentCallbacks2
import android.content.Context
import android.content.MutableContextWrapper
import java.net.URI
import java.text.BreakIterator
import java.util.Locale
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Keeps a live web view per recently used app, so switching apps doesn't reload them. Each
 * view lives outside composition on a [MutableContextWrapper], its profile named by its app's
 * data store, and is attached to the screen only while its app is open. Main thread only.
 */
class WebViewPool(
    private val context: Context,
    private val nodes: NodeManager,
    private val scope: CoroutineScope,
    /** Ovenlight is locked: nothing downloads, captures or opens a page's dialogs. */
    val isLocked: StateFlow<Boolean>,
) {
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
        DownloadShelf.emptyAtLaunch(context)
        CaptureFileProvider.emptyAtLaunch(context)
        nodes.onLoggingOut = { node -> discard { it.node === node } }
        nodes.onOwnerSuffixChanged = ::discardStaleRoutes
    }

    /** The app's page, made now unless one through its current route is live. */
    fun page(app: WebApp): AppPage {
        pages[app.id]?.let { page ->
            // The app's route changed since its view was made (the owner's tailnet became
            // known, or another one), or its renderer went: the view starts over through the right node.
            if (Routing.mayLoad(page.node, nodes.node(app)) && !page.rendererGone) {
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

    /** Whether the app has a live page; tests ask. */
    internal fun hasPage(id: UUID): Boolean = id in pages

    /** Ends every page's microphone and camera streams, on screen or not, so none runs under the lock. */
    fun stopCapture() = pages.values.forEach { it.capture.stop() }

    /**
     * Pages' timers pause while Ovenlight is away (AppLifecycle's leaving rules), never during an
     * excursion or for the lock. WebView's timers are process-wide, so one view does it for all.
     */
    var timersPaused = false
        set(value) {
            if (field == value) return
            field = value
            // Leaving also ends every download: none runs on while the person is elsewhere.
            if (value) pages.values.forEach { it.downloads.cancel() }
            val view = pages.values.firstOrNull()?.view ?: return
            if (value) view.pauseTimers() else view.resumeTimers()
        }

    /**
     * Memory is short: pages off screen go, down to one. Android sends BACKGROUND once Ovenlight is
     * in the background; UI_HIDDEN also comes for an excursion, when pages must stay.
     */
    fun trimMemory(level: Int, away: Boolean) {
        if (level >= ComponentCallbacks2.TRIM_MEMORY_BACKGROUND || (level == ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN && away)) trim(keeping = 1)
    }

    /** The phone's font size changed: every live page's text follows it. */
    fun fontScaleChanged(fontScale: Float) = pages.values.forEach { WebViewSetup.followTextSize(it.view, fontScale) }

    /** Where opening the app got, while it has a page, for Report a Problem. */
    fun gate(id: UUID): AppLoadGate? = pages[id]?.gate?.value

    /** Every app but [open] is closed, so its Send feedback goes. */
    fun appClosed(open: UUID?) {
        for (page in pages.values) if (page.app.id != open) page.feedback.value = null
    }

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
            listOfNotNull(after?.let(::reduced), "Ovenlight/${version ?: "?"}").filter { it.isNotEmpty() }.joinToString(" ")

        /**
         * WebView's user agent as Android 17 sends it: no phone model, build or Android version
         * (`Linux; Android 10; K; wv`) and Chrome's major version only. Up to Android 16 it names
         * the model and build, which would tell every app a guest opens which phone they carry;
         * iOS sends no model.
         */
        fun reduced(agent: String): String =
            // Up to the bracket before AppleWebKit, since a model may have brackets of its own: moto g(30).
            agent.replaceFirst(Regex("""\(Linux; Android .*?\)(?= AppleWebKit/)"""), "(Linux; Android 10; K; wv)")
                .replace(Regex("""Chrome/(\d+)\.[\d.]+"""), "Chrome/$1.0.0.0")

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

    /**
     * Whether an http(s) download is a file to keep, by the [status] of Ovenlight's own fetch of
     * it, since Android's DownloadListener gives none: a successful one, or one sent as an
     * attachment. 204, 205 and 304 have no body, and the page stays as it is.
     */
    fun isFile(status: Int, disposition: String?): Boolean {
        if (status == 204 || status == 205 || status == 304) return false
        // The type is the part before any `;`, in any case, with spaces allowed around it.
        val type = disposition?.split(';', limit = 2)?.first()?.trimmingWhitespace() ?: ""
        return status in 200..<300 || type.equals("attachment", ignoreCase = true)
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

    /**
     * A file's name from its address, for the notice when it fails: the last part of an http or
     * https path. A blob or data URL, or one with no path, has none.
     */
    fun name(url: String): String? {
        val uri = NavigationPolicy.pageURI(url)?.takeIf { it.scheme?.lowercase() in setOf("http", "https") } ?: return null
        val last = uri.path.orEmpty().substringAfterLast('/').ifEmpty { return null }
        return fileName(last)
    }

    /** Well under the 255 bytes ext4 and f2fs allow, as on iOS. */
    const val maxFileNameBytes = 200

    /**
     * Path separators, colons and iOS's `.controlCharacters` (Cc and Cf). A lone surrogate,
     * which a page's script can put in a name and no Swift string holds, too.
     */
    private fun Int.isUnsafeInFileName(): Boolean =
        this == '/'.code || this == '\\'.code || this == ':'.code || this in blankLetters ||
            Character.getType(this).let {
                it == Character.CONTROL.toInt() || it == Character.FORMAT.toInt() || it == Character.SURROGATE.toInt() ||
                    // Android only: line and paragraph breaks, and every space but the plain one, which
                    // the sheet would show as nothing and so hide a name's real extension.
                    it == Character.LINE_SEPARATOR.toInt() || it == Character.PARAGRAPH_SEPARATOR.toInt() ||
                    it == Character.SPACE_SEPARATOR.toInt() && this != ' '.code
            }

    /** Letters that draw as nothing: Braille's blank and the Hangul fillers. */
    private val blankLetters = setOf(0x2800, 0x3164, 0x115F, 0x1160, 0xFFA0)

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
