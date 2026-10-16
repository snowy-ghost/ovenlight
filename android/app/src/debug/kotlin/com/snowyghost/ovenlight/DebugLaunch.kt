package com.snowyghost.ovenlight

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.os.SystemClock
import android.util.Log
import android.webkit.HttpAuthHandler
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.webkit.ProfileStore
import androidx.webkit.WebStorageCompat
import androidx.webkit.WebViewCompat
import com.snowyghost.ovenlight.core.Core
import com.snowyghost.ovenlight.core.Node
import java.io.File
import java.net.URI
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.Request
import org.json.JSONObject

/**
 * Debug builds' launch extras and lab, the counterparts of iOS's launch arguments. The lab
 * starts nodes and opens pages in profiled web views, driven by `am start` extras so the
 * Phase 1 gates run from adb:
 *
 *   --es lab start [--es labId lab] [--es controlURL <url>] [--es authKey <key>]
 *   --es lab login | waitup | status | close | restart       [--es labId lab]
 *   --es lab probe --es labUrl <https url> [--es labKey node-lab]
 *   --es lab open --es labProfile A --es labUrl <url> [--es labId lab]
 *   --es lab page --es labProfile A --es labBase https://lab.e2e.ts.net/   (assets/lab.html)
 *   --es lab eval --es labJs <script>        logs the result of the open view's script
 *   --es lab wipe --es labProfile A          deleteBrowsingData now, deleteProfile next launch
 *   --es lab clearRoute --es labProfile A
 *   --es lab insets                          the page's env(safe-area-inset-*) beside Android's insets
 *   --es lab closedPort | proxy              the proxy override on a closed port, or back
 *   --el labHold <ms>                        how long the proxy holds a CONNECT (default 8000)
 *   --ez fakeOldWebView true                 the update screen
 *   --ez writeProxySecret true               files/proxy.txt: address, secret, realm
 *
 * And the app's own, as iOS's launch arguments are:
 *
 *   --es controlURL <url> --es authKey <key>   the owner's node on a local Headscale, signed in with a key
 *   --es joinInvite <ovenlight://join?...>     opens an invite
 *   --es openApp <name>                        opens an app by name
 *   --ez lockNow true                          locks Ovenlight where it is, as Lock Ovenlight does
 *
 * Results go to logcat under OvenlightLab, never a secret or a sign-in link. Commands wait
 * until WebViewSetup is Ready, which means it found every WebView feature they use.
 */
@SuppressLint("RequiresFeature")
object DebugLaunch {
    private const val TAG = "OvenlightLab"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val nodes = mutableMapOf<String, Node>()
    private val view = MutableStateFlow<WebView?>(null)
    private val status = MutableStateFlow("Lab")
    private val showing = MutableStateFlow(false)

    /** A lab command came in, so the lab shows in place of the launcher. */
    val active: StateFlow<Boolean> = showing
    private var viewProfile = ""

    private fun log(line: String) {
        Log.i(TAG, line)
        status.value = line
    }

    fun receive(activity: ComponentActivity, intent: Intent) {
        if (intent.getBooleanExtra("fakeOldWebView", false)) WebViewSetup.pretendNeedsUpdate()
        receiveAppExtras(activity, intent)
        if (intent.getBooleanExtra("writeProxySecret", false)) {
            File(activity.filesDir, "proxy.txt").writeText(listOf(TailnetCore.proxyAddress, TailnetCore.proxySecret, TailnetCore.proxyRealm).joinToString("\n", postfix = "\n"))
        }
        intent.getLongExtra("labHold", 0).takeIf { it > 0 }?.let(Core::debugAnswerWithin)
        val command = intent.getStringExtra("lab") ?: return
        showing.value = true
        val id = intent.getStringExtra("labId") ?: "lab"
        scope.launch {
            try {
                run(activity, command, id, intent)
            } catch (e: Exception) {
                log("$command $id: failed: ${e.message}")
            }
        }
    }

    private fun receiveAppExtras(activity: ComponentActivity, intent: Intent) {
        if (intent.hasExtra("lab")) return
        val preferences = PreferencesStore(activity)
        intent.getStringExtra("controlURL")?.let { preferences.putString(NodeManager.CONTROL_URL_KEY, it) }
        intent.getStringExtra("authKey")?.let { preferences.putString(NodeManager.AUTH_KEY_KEY, it) }
        val model = OvenlightApp.model
        // A key given now, after the model read the settings, starts the owner's node too.
        if (intent.hasExtra("authKey")) model.nodes.enableOwner()
        intent.getStringExtra("joinInvite")?.let { link -> runCatching { URI(link) }.getOrNull()?.let { model.router.handle(it, model.registry.apps) } }
        if (intent.getBooleanExtra("lockNow", false)) model.lock.lock()
        intent.getStringExtra("openApp")?.let { name ->
            model.router.handle(URI("ovenlight", "open", null, "app=$name", null), model.registry.apps)
        }
    }

    private suspend fun run(activity: ComponentActivity, command: String, id: String, intent: Intent) {
        withTimeoutOrNull(30_000) { WebViewSetup.state.first { it == WebViewSetup.State.Ready } } ?: return log("$command: WebView never got ready")
        when (command) {
            "start" -> {
                val t0 = SystemClock.elapsedRealtime()
                val node = withContext(Dispatchers.IO) {
                    val dir = File(activity.noBackupFilesDir, "tailscale/$id").apply { mkdirs() }
                    Core.newNode(id, dir.path, intent.getStringExtra("labHostname") ?: "ovenlight-lab", intent.getStringExtra("controlURL").orEmpty(), intent.getStringExtra("authKey").orEmpty()).also { it.start() }
                }
                nodes[id] = node
                log("start $id: backend running in ${SystemClock.elapsedRealtime() - t0} ms")
            }
            "login" -> {
                val node = node(id)
                withContext(Dispatchers.IO) { node.loginInteractive() }
                repeat(30) {
                    val st = JSONObject(withContext(Dispatchers.IO) { node.statusJSON() })
                    val url = st.optString("AuthURL")
                    if (url.isNotEmpty()) {
                        log("login $id: ${st.optString("BackendState")}, sign-in link from ${url.toHttpHost()}")
                        return
                    }
                    kotlinx.coroutines.delay(500)
                }
                log("login $id: no sign-in link")
            }
            "waitup" -> {
                val t0 = SystemClock.elapsedRealtime()
                withContext(Dispatchers.IO) { node(id).waitUp(60_000) }
                log("waitup $id: Running in ${SystemClock.elapsedRealtime() - t0} ms")
            }
            "status" -> {
                val st = JSONObject(withContext(Dispatchers.IO) { node(id).statusJSON() })
                val peers = st.optJSONObject("Peer")?.length() ?: 0
                val suffix = st.optJSONObject("CurrentTailnet")?.optString("MagicDNSSuffix").orEmpty()
                log("status $id: ${st.optString("BackendState")}, $peers peers, suffix $suffix, self ${st.optJSONObject("Self")?.optString("DNSName")}")
            }
            "close" -> {
                withContext(Dispatchers.IO) { nodes.remove(id)?.close() }
                log("close $id: closed")
            }
            "restart" -> {
                val t0 = SystemClock.elapsedRealtime()
                val node = withContext(Dispatchers.IO) {
                    nodes.remove(id)?.close()
                    val dir = File(activity.noBackupFilesDir, "tailscale/$id")
                    Core.newNode(id, dir.path, intent.getStringExtra("labHostname") ?: "ovenlight-lab", intent.getStringExtra("controlURL").orEmpty(), "").also {
                        it.start()
                        it.waitUp(60_000)
                    }
                }
                nodes[id] = node
                log("restart $id: Running from its state in ${SystemClock.elapsedRealtime() - t0} ms")
            }
            "probe" -> {
                val url = intent.getStringExtra("labUrl") ?: return log("probe: no labUrl")
                val key = intent.getStringExtra("labKey") ?: "node-$id"
                val t0 = SystemClock.elapsedRealtime()
                val code = withContext(Dispatchers.IO) { client(key).newCall(Request.Builder().url(url).build()).execute().use { it.code } }
                log("probe $key ${url.toHttpHost()}: HTTP $code in ${SystemClock.elapsedRealtime() - t0} ms")
            }
            "open", "page" -> {
                val profile = intent.getStringExtra("labProfile") ?: return log("$command: no labProfile")
                val web = makeView(activity, profile) ?: return
                if (intent.getStringExtra("labRoute") != "none") Core.setRoute(ProxyChallenge.routeKey(profile), id)
                if (command == "open") {
                    web.loadUrl(intent.getStringExtra("labUrl") ?: return log("open: no labUrl"))
                } else {
                    val html = activity.assets.open("lab.html").bufferedReader().readText()
                    web.loadDataWithBaseURL(intent.getStringExtra("labBase") ?: "https://lab.e2e.ts.net/", html, "text/html", "utf-8", null)
                }
            }
            "eval" -> {
                val js = intent.getStringExtra("labJs") ?: return log("eval: no labJs")
                val web = view.value ?: return log("eval: no view")
                web.evaluateJavascript(js) { log("eval $viewProfile: $it") }
            }
            "wipe" -> {
                val profile = intent.getStringExtra("labProfile") ?: return log("wipe: no labProfile")
                if (viewProfile == profile) discardView()
                ProfileDeletions.enqueue(activity, profile)
                Core.clearRoute(ProxyChallenge.routeKey(profile))
                val p = ProfileStore.getInstance().getProfile(profile) ?: return log("wipe $profile: no such profile")
                WebStorageCompat.deleteBrowsingData(p.webStorage) { log("wipe $profile: browsing data deleted") }
            }
            "clearRoute" -> {
                val profile = intent.getStringExtra("labProfile") ?: return log("clearRoute: no labProfile")
                Core.clearRoute(ProxyChallenge.routeKey(profile))
                log("clearRoute $profile: cleared")
            }
            "insets" -> {
                val web = view.value ?: return log("insets: no view")
                val insets = androidx.core.view.ViewCompat.getRootWindowInsets(web) ?: return log("insets: none yet")
                val density = activity.resources.displayMetrics.density
                fun dp(i: androidx.core.graphics.Insets) = "top ${(i.top / density).toInt()} right ${(i.right / density).toInt()} bottom ${(i.bottom / density).toInt()} left ${(i.left / density).toInt()}"
                val bars = insets.getInsets(androidx.core.view.WindowInsetsCompat.Type.systemBars())
                val cutout = insets.getInsets(androidx.core.view.WindowInsetsCompat.Type.displayCutout())
                val ime = insets.getInsets(androidx.core.view.WindowInsetsCompat.Type.ime())
                web.evaluateJavascript("labInsets()") { log("insets $viewProfile: page $it; Android dp: bars ${dp(bars)}; cutout ${dp(cutout)}; keyboard ${dp(ime)}") }
            }
            "closedPort" -> WebViewSetup.overrideProxy(activity, "http://127.0.0.1:1") { log("proxy override: 127.0.0.1:1, a closed port") }
            "proxy" -> WebViewSetup.overrideProxy(activity, "http://${TailnetCore.proxyAddress}") { log("proxy override: the core's proxy") }
            "profiles" -> log("profiles: ${ProfileStore.getInstance().allProfileNames.sorted()}")
            else -> log("unknown lab command $command")
        }
    }

    private fun node(id: String) = nodes[id] ?: error("no node $id; start it first")

    private fun client(routeKey: String) = TailnetCore.client(routeKey).callTimeout(20, TimeUnit.SECONDS).build()

    private fun discardView() {
        view.value?.destroy()
        view.value = null
        viewProfile = ""
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun makeView(context: Context, profile: String): WebView? {
        discardView()
        val web = WebView(context)
        try {
            // Before anything else touches the view. A view left in the default profile
            // would share one credential with every app, so a failure destroys it.
            WebViewCompat.setProfile(web, profile)
        } catch (e: Exception) {
            web.destroy()
            log("profile $profile: setProfile failed: ${e.message}")
            return null
        }
        WebViewSetup.configure(web)
        web.webViewClient = LabClient(profile)
        viewProfile = profile
        view.value = web
        return web
    }

    // It does override onRenderProcessGone: androidx.webkit's check also flags every call
    // to WebViewClient's constructor, which Kotlin's supertype call is.
    @SuppressLint("MissingOnRenderProcessGone")
    private class LabClient(private val profile: String) : WebViewClient() {
        override fun onReceivedHttpAuthRequest(view: WebView, handler: HttpAuthHandler, host: String?, realm: String?) {
            val answer = ProxyChallenge.answer(host, realm, WebViewCompat.getProfile(view).name, profile, TailnetCore.proxyRealm, TailnetCore.proxySecret)
            if (answer == null) {
                log("challenge in $profile from $host, our realm ${realm == TailnetCore.proxyRealm}: canceled")
                handler.cancel()
            } else {
                Log.i(TAG, "challenge in $profile from $host: answered as ${answer.routeKey}")
                handler.proceed(answer.routeKey, answer.secret)
            }
        }

        override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
            Log.i(TAG, "page $profile started ${url?.toHttpHost()}")
        }

        override fun onPageFinished(view: WebView, url: String?) {
            log("page $profile finished ${url?.toHttpHost()}")
        }

        override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
            log("error $profile ${if (request.isForMainFrame) "main frame" else "subresource"} ${request.url.host}: ${error.errorCode} ${error.description}")
        }

        override fun onReceivedHttpError(view: WebView, request: WebResourceRequest, response: WebResourceResponse) {
            log("http $profile ${request.url.host}: ${response.statusCode}")
        }

        override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
            log("renderer gone in $profile, crashed ${detail.didCrash()}")
            if (DebugLaunch.view.value === view) DebugLaunch.discardView() else view.destroy()
            return true
        }
    }

    private fun String.toHttpHost() = runCatching { java.net.URI(this).host }.getOrNull() ?: "?"

    @Composable
    fun Content() {
        val web by view.collectAsState()
        val line by status.collectAsState()
        Box(Modifier.fillMaxSize()) {
            val current = web
            if (current == null) {
                LampLight(level = 0.6f)
                Text(line, Modifier.safeDrawingPadding().padding(16.dp))
            } else {
                key(current) { AndroidView(factory = { current }, modifier = Modifier.fillMaxSize()) }
            }
        }
    }
}
