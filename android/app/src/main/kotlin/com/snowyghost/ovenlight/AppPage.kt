package com.snowyghost.ovenlight

import android.annotation.SuppressLint
import android.content.Context
import android.content.MutableContextWrapper
import android.graphics.Bitmap
import android.view.ViewGroup
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.webkit.HttpAuthHandler
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.webkit.WebViewCompat
import com.snowyghost.ovenlight.core.Core
import kotlin.time.Duration
import kotlin.time.TimeSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * One app's web view and everything about loading it (iOS's WebCoordinator): its profile, its
 * route through [node], the gate that waits for the node and probes the app's host before the
 * first navigation, and what the page's client sees. Main thread only.
 */
@SuppressLint("RequiresFeature") // WebViewSetup is Ready only once it found every feature used here.
class AppPage(
    val app: WebApp,
    /** The node the app loads through; null when its route is refused, and nothing loads. */
    val node: MembershipNode?,
    private val pool: WebViewPool,
    private val nodes: NodeManager,
    private val scope: CoroutineScope,
    context: Context,
) {
    private val profileName = WebProfiles.profileName(app.dataStoreID)
    private val routeKey = ProxyChallenge.routeKey(profileName)

    /** Off screen it holds the application's context; on screen, the activity's (see [attach]). */
    val context = MutableContextWrapper(context.applicationContext)
    /**
     * As tall as where it shows: without layout params WebView takes its height as wrap-content,
     * and a page's `100vh` comes out as 0.
     */
    val view = PageWebView(this.context).apply { layoutParams = ViewGroup.LayoutParams(MATCH_PARENT, MATCH_PARENT) }

    /** Links out, other apps, the page's dialogs, file input and full screen; made once the profile is on. */
    val edges: PageEdges

    private val mutableGate = MutableStateFlow(AppLoadGate.Waiting)
    val gate: StateFlow<AppLoadGate> = mutableGate
    private val loaded = MutableStateFlow(false)
    val hasLoadedOnce: StateFlow<Boolean> = loaded
    private val error = MutableStateFlow<String?>(null)
    val loadError: StateFlow<String?> = error

    /**
     * Whether the page lays itself out under the bars, and its colors, read as each page finishes
     * loading and before the first one shows, so it never moves (and again by the chrome).
     */
    val look = MutableStateFlow(PageLook())

    /**
     * Send feedback while it's open. It outlasts the lock, which takes the screen apart, and goes
     * once it's sent or closed, when the app closes, or with the page.
     */
    val feedback = MutableStateFlow<FeedbackDraft?>(null)

    /** A link the person tapped after the first load couldn't reach the computer: say so, once. */
    val cantReachAlert = MutableStateFlow(false)

    /** Downloads are under way (set by [downloads]); the pool keeps such pages. */
    var isDownloading = false

    /** Its renderer went while it was downloading off screen; it goes once they finish. */
    var rendererGone = false
        private set

    /** Its downloads, refetched on its route with its profile's cookies (PageDownloads.kt). */
    val downloads = PageDownloads(
        app.id, app.host, DownloadShelf.folder(context), scope,
        mayStart = { shown && !pool.isLocked() }, isLocked = pool.isLocked,
        client = { PageDownloads.client(TailnetCore.client(routeKey), ProfileCookieJar(WebViewCompat.getProfile(view).cookieManager)) },
        freeBytes = { DownloadShelf.freeBytes(this.context.applicationContext) },
        busy = { busy ->
            isDownloading = busy
            if (!busy && rendererGone) pool.replace(this)
        },
        failed = { name ->
            val app = this.context.applicationContext
            val text = name?.let { app.getString(R.string.download_failed, it) } ?: app.getString(R.string.download_failed_file)
            Toast.makeText(app, text, Toast.LENGTH_LONG).show()
        },
    )

    /** Its microphone and camera (PageCapture.kt). */
    val capture = PageCapture(
        app, ownersHosts = { nodes.ownersHosts }, isLocked = pool.isLocked, isOnScreen = { shown }, scope = scope,
        permissions = { needed, done ->
            CaptureMonitor.watch(this.context)
            RuntimePermissions.request(this.context, needed, done)
        },
        stillCapturing = { CaptureMonitor.capturing(this.context) },
        destroy = { pool.replace(this) },
    )

    /** First navigations that failed at the connection level after the probe answered. */
    private var failedLoads = 0

    /** First pages the connector answered for because the app itself was down. */
    private var appDownLoads = 0

    /** The navigation under way failed, so its finished page doesn't count as loaded. */
    private var navigationFailed = false

    /** An app-down page was shown, so history is cleared once the real page loads. */
    private var clearHistoryOnLoad = false

    /** The page's latest main-frame navigation came from a gesture, not the page itself. */
    private var tapped = false
    private var gateJob: Job? = null
    private var destroyed = false
    private var shown = false

    /** The page script's channel (PageBridge.kt), offered to each new document. */
    private val bridge: PageBridge

    init {
        // The profile goes on before anything else touches the view.
        WebViewCompat.setProfile(view, profileName)
        WebViewSetup.configure(view)
        if (node != null) Core.setRoute(routeKey, node.id) else Core.clearRoute(routeKey)
        edges = PageEdges(
            app, view, this.context, scope, OvenlightApp.lock.isLocked, OvenlightApp.model.lifecycle, capture, isShown = { shown },
            home = { if (routeIsCurrent() && nodeMayCarry && !destroyed) view.loadUrl(app.startURL.toString()) },
        )
        view.webViewClient = Client()
        view.webChromeClient = edges.chrome
        view.setDownloadListener(downloads)
        bridge = PageBridge(view, app.startURL, capture, downloads, scope)
        view.onPause()
    }

    // The view on screen

    /** The view joins the activity's window: dialogs and pickers need the activity. */
    fun attach(activity: Context) {
        context.baseContext = activity
    }

    /** The view left the screen, and holds the application again so it doesn't keep the activity. */
    fun detach() {
        context.baseContext = context.applicationContext
    }

    internal fun onScreen() {
        edges.allowDialogsAgain()
        shown = true
        view.onResume()
    }

    internal fun offScreen() {
        shown = false
        edges.takenAway()
        view.onPause()
        capture.stop()
    }

    // Loading

    /** Every app waits for its host to answer before the first navigation (see AppLoadGate). */
    fun start() = openGate()

    /** Try Again, and Reload. A page that already loaded stays on screen while the path is checked again. */
    fun reload() {
        edges.allowDialogsAgain()
        failedLoads = 0
        appDownLoads = 0
        error.value = null
        mutableGate.value = mutableGate.value.next(AppLoadGate.Event.Retry)
        openGate()
    }

    /** Reopening an app that couldn't reach its computer, or couldn't load, tries again. */
    fun reopened() {
        if (!loaded.value && (mutableGate.value == AppLoadGate.Unreachable || error.value != null)) reload()
    }

    private fun openGate() {
        gateJob?.cancel()
        gateJob = scope.launch { runGate() }
    }

    /** Waits for the node, then probes this app's host until it answers or time runs out. */
    private suspend fun runGate() {
        // A refused route has no way in; a shared app's always has its owner's node.
        if (node == null) {
            if (app.isShared) pool.guests.collectOrphans()
            return giveUp()
        }
        var opened = TimeSource.Monotonic.markNow()
        var probingSince: TimeSource.Monotonic.ValueTimeMark? = null
        while (mutableGate.value == AppLoadGate.Waiting || mutableGate.value == AppLoadGate.Probing) {
            // Not even a probe goes through a node the app's route no longer names.
            if (!routeIsCurrent()) return
            // No network: say so at once, and start over quietly when one comes.
            val online = TailnetCore.online.value
            if (!online) {
                mutableGate.value = mutableGate.value.next(AppLoadGate.Event.PhoneOffline)
                TailnetCore.online.first { it }
                mutableGate.value = mutableGate.value.next(AppLoadGate.Event.PhoneOnline)
                opened = TimeSource.Monotonic.markNow()
                probingSince = null
                continue
            }
            val state = node.state.value
            if (state == NodeState.Ready && nodeMayCarry) {
                mutableGate.value = mutableGate.value.next(AppLoadGate.Event.NodeReady)
                // Its computer is listed offline, asleep most likely: Can't Reach shows at
                // once, and the app opens as soon as the computer answers.
                if (!loaded.value && node.isListedOffline(app.host)) {
                    giveUp()
                    return retryQuietly()
                }
                val since = probingSince ?: TimeSource.Monotonic.markNow().also { probingSince = it }
                when (node.canReach(app)) {
                    Reach.Reached -> {
                        mutableGate.value = mutableGate.value.next(AppLoadGate.Event.Reached)
                        return load()
                    }
                    // The owner stopped sharing it; Ovenlight takes the app away.
                    Reach.NotInvited -> return pool.guests.accessEnded(listOf(app))
                    Reach.Unreachable -> Unit
                }
                if (since.elapsedNow() >= AppLoadGate.reachTimeout) return giveUp()
                delay(1_000)
            } else {
                mutableGate.value = mutableGate.value.next(AppLoadGate.Event.NodeNotReady)
                probingSince = null
                // Sign-in and node failures have their own screens and wait for the owner.
                val left = AppLoadGate.nodeTimeout - opened.elapsedNow()
                if (state.isBusy && !left.isPositive()) return giveUp()
                // Until the node, its tailnet or the phone's network changes, or a busy node's time runs out.
                AppLoadGate.awaitChange(
                    combine(node.state, node.status, TailnetCore.online, ::Triple), Triple(state, node.status.value, online),
                    if (state.isBusy) left else Duration.INFINITE,
                )
            }
        }
    }

    /**
     * Probes the app behind its Can't Reach screen until it answers, Try Again starts over, or
     * the app closes to the launcher, after which opening it again tries again.
     */
    private suspend fun retryQuietly() {
        val node = node ?: return
        while (mutableGate.value == AppLoadGate.Unreachable && routeIsCurrent()) {
            if (node.state.value == NodeState.Ready && nodeMayCarry) {
                when (node.canReach(app)) {
                    Reach.Reached -> {
                        if (mutableGate.value != AppLoadGate.Unreachable) return
                        mutableGate.value = mutableGate.value.next(AppLoadGate.Event.Woke)
                        return load()
                    }
                    Reach.NotInvited -> return pool.guests.accessEnded(listOf(app))
                    Reach.Unreachable -> Unit
                }
            }
            delay(AppLoadGate.quietRetryInterval)
            if (!shown) return
        }
    }

    private fun giveUp() {
        OvenlightLog.i("${app.host}: unreachable")
        mutableGate.value = mutableGate.value.next(AppLoadGate.Event.GaveUp)
        // A page already on screen keeps it; the person's Reload checks the path again.
        if (loaded.value) {
            mutableGate.value = AppLoadGate.Ready
            cantReachAlert.value = true
        }
    }

    private fun load() {
        if (!routeIsCurrent() || !nodeMayCarry || destroyed) return
        error.value = null
        val current = view.url
        if (current != null && current != "about:blank") view.reload() else view.loadUrl(app.startURL.toString())
    }

    /**
     * Checked before every probe and load: the node the app's route names now is the one this
     * view's profile was routed through (see [Routing.mayLoad]). If not, the pool drops this
     * page and the app's screen makes a new one through the right node. Cached pages render with
     * no route at all, so a stale view must never load.
     */
    private fun routeIsCurrent(): Boolean {
        if (Routing.mayLoad(node, nodes.node(app))) return true
        OvenlightLog.i("${app.host}: route changed, making a new view")
        pool.replace(this)
        return false
    }

    /** The owner's node carries only names on the owner's known tailnet; guest nodes carry their own apps. */
    private val nodeMayCarry: Boolean
        get() = node !== nodes.owner || Routing.isOnOwnersTailnet(app.startURL.host.orEmpty(), nodes.owner.magicDNSSuffixSeen)

    /** The first page's app is down on its computer: the placeholder stays while it's tried again, then the error says so. */
    private fun appIsDown() {
        clearHistoryOnLoad = true
        if (appDownLoads >= AppLoadGate.appDownRetries.size) {
            appDownLoads = 0
            error.value = ConnectionCopy.notResponding(app.name)
            return
        }
        val wait = AppLoadGate.appDownRetries[appDownLoads]
        appDownLoads += 1
        OvenlightLog.i("${app.host}: the app is down, trying again in $wait")
        gateJob?.cancel()
        gateJob = scope.launch {
            delay(wait)
            if (routeIsCurrent() && nodeMayCarry && !destroyed) view.loadUrl(app.startURL.toString())
        }
    }

    private fun failed(code: Int, description: String) {
        // Once the app has rendered, a failed load leaves the page as it is. A link the person
        // tapped that can't reach the computer says so; what the page loads by itself never does.
        if (loaded.value) {
            if (tapped && code in retryableThroughNode) cantReachAlert.value = true
            return
        }
        if (code in retryableThroughNode && failedLoads < MAX_FAILED_LOADS) {
            failedLoads += 1
            OvenlightLog.i("${app.host}: error $code, checking the path again")
            mutableGate.value = mutableGate.value.next(AppLoadGate.Event.LoadFailed)
            return openGate()
        }
        failedLoads = 0
        // Chromium's description is a code ("net::ERR_TOO_MANY_REDIRECTS"): the log keeps it, the page says it plainly.
        if (code !in retryableThroughNode) OvenlightLog.i("${app.host}: error $code, $description")
        error.value = if (code in retryableThroughNode) ConnectionCopy.cantReachDetail else ConnectionCopy.pageFailed
    }

    // Leaving

    /** The pool dropped this page: its route goes nowhere, its view stops and goes. */
    internal fun destroy() {
        destroyed = true
        feedback.value = null
        edges.destroy()
        gateJob?.cancel()
        downloads.cancel()
        bridge.close()
        Core.clearRoute(routeKey)
        view.stopLoading()
        (view.parent as? android.view.ViewGroup)?.removeView(view)
        view.destroy()
        detach()
    }

    // It does override onRenderProcessGone: androidx.webkit's check also flags every call to
    // WebViewClient's constructor, which Kotlin's supertype call is (as in DebugLaunch).
    @SuppressLint("MissingOnRenderProcessGone")
    private inner class Client : WebViewClient() {
        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
            if (request.isForMainFrame) tapped = request.hasGesture()
            return edges.shouldOverrideUrlLoading(request)
        }

        override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
            navigationFailed = false
            bridge.documentStarted()
            edges.pageStarted(url)
            capture.documentChanged()
        }

        override fun onPageFinished(view: WebView, url: String?) {
            edges.pageFinished(url)
            bridge.documentFinished()
            if (navigationFailed || url == null || url == "about:blank" || destroyed) return
            if (!loaded.value && clearHistoryOnLoad) {
                // The app-down page never counts, and Back never returns to it.
                clearHistoryOnLoad = false
                view.clearHistory()
            }
            view.evaluateJavascript(PageLook.script) {
                if (destroyed) return@evaluateJavascript
                look.value = PageLook.decoded(it)
                loaded.value = true
            }
            error.value = null
            failedLoads = 0
            appDownLoads = 0
        }

        override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
            if (!request.isForMainFrame) return
            navigationFailed = true
            failed(error.errorCode, error.description.toString())
        }

        override fun onReceivedHttpError(view: WebView, request: WebResourceRequest, response: WebResourceResponse) {
            if (!request.isForMainFrame) return
            val header = response.responseHeaders?.entries?.firstOrNull { it.key.equals(ConnectorError.header, ignoreCase = true) }?.value
            if (!loaded.value && AppLoadGate.appIsDown(response.statusCode, header)) {
                navigationFailed = true
                view.stopLoading()
                return appIsDown()
            }
            // A shared app answering 403 may have been unshared: ask its connector once.
            if (app.isShared && response.statusCode == 403) scope.launch { pool.guests.verifyAccess(app) }
        }

        /** Only the proxy's own challenge, for this view's own profile, gets the route key and secret. */
        override fun onReceivedHttpAuthRequest(view: WebView, handler: HttpAuthHandler, host: String?, realm: String?) {
            val profile = runCatching { WebViewCompat.getProfile(view).name }.getOrNull()
            val credential = ProxyChallenge.answer(host, realm, profile, profileName, TailnetCore.proxyRealm, TailnetCore.proxySecret)
            if (credential != null) handler.proceed(credential.routeKey, credential.secret) else handler.cancel()
        }

        /**
         * The page's renderer is gone (the system reclaimed it, or it crashed). On screen the app
         * starts over in a new view; off screen the pool drops it, and the next open starts over.
         */
        override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
            OvenlightLog.w("${app.host}: the page's renderer went (crashed: ${detail.didCrash()})")
            // Downloads need no renderer, and replacing the view would end them and their route.
            if (isDownloading && !shown) rendererGone = true else pool.replace(this@AppPage)
            return true
        }
    }

    private companion object {
        const val MAX_FAILED_LOADS = 2

        // WebView's codes for a path through the proxy that didn't connect: -11 for a tunnel the
        // proxy refused or couldn't open (502, 503, 504), -8 for one held past 10 s, -5 for the
        // proxy itself gone (Phase 1's G5), and the plain connection failures.
        val retryableThroughNode = setOf(
            WebViewClient.ERROR_FAILED_SSL_HANDSHAKE, WebViewClient.ERROR_TIMEOUT, WebViewClient.ERROR_PROXY_AUTHENTICATION,
            WebViewClient.ERROR_CONNECT, WebViewClient.ERROR_HOST_LOOKUP, WebViewClient.ERROR_IO,
        )
    }
}
