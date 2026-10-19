package com.snowyghost.ovenlight

import android.annotation.SuppressLint
import android.content.Context
import android.webkit.WebSettings
import android.webkit.WebView
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import androidx.webkit.ProfileStore
import androidx.webkit.ProxyConfig
import androidx.webkit.ProxyController
import androidx.webkit.UserAgentMetadata
import androidx.webkit.WebSettingsCompat
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import androidx.webkit.WebViewMediaIntegrityApiStatusConfig
import androidx.webkit.WebViewOutcomeReceiver
import androidx.webkit.WebViewStartUpConfig
import androidx.webkit.WebViewStartUpResult
import androidx.webkit.WebViewStartupException
import java.util.concurrent.Executors
import kotlin.math.roundToInt
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Starts WebView behind the lock and readies it for apps: nothing else touches WebView
 * before startUpWebView's callback, where the requirement check, the queued profile
 * deletions and the proxy override run, and no WebView is made until the override's own
 * callback has run.
 */
@SuppressLint("RequiresFeature") // Ready, and every view, only once every required feature was found.
object WebViewSetup {
    sealed interface State {
        data object Starting : State
        /** This phone's WebView is too old ([version], when that's the reason) or lacks a feature Ovenlight needs. */
        data class NeedsUpdate(val webViewPackage: String, val version: String? = null) : State
        data object Ready : State
    }

    /** The first WebView to pass the safe area to every page, not only to one it takes for full screen. */
    const val MINIMUM_MAJOR_VERSION = 144
    val requiredFeatures = listOf(
        WebViewFeature.PROXY_OVERRIDE,
        WebViewFeature.MULTI_PROFILE,
        WebViewFeature.DELETE_BROWSING_DATA,
        WebViewFeature.DOCUMENT_START_SCRIPT,
        // The page bridge's channel (PageBridge.kt).
        WebViewFeature.POST_WEB_MESSAGE,
        WebViewFeature.CREATE_WEB_MESSAGE_CHANNEL,
        WebViewFeature.WEB_MESSAGE_PORT_POST_MESSAGE,
        WebViewFeature.WEB_MESSAGE_PORT_SET_MESSAGE_CALLBACK,
        WebViewFeature.WEB_MESSAGE_PORT_CLOSE,
        WebViewFeature.WEB_MESSAGE_ARRAY_BUFFER,
        // Media integrity off (configure).
        WebViewFeature.WEBVIEW_MEDIA_INTEGRITY_API_STATUS,
        // The client hints' model and Android version (configure).
        WebViewFeature.USER_AGENT_METADATA,
    )
    private const val DEFAULT_WEBVIEW_PACKAGE = "com.google.android.webview"

    private val mutableState = MutableStateFlow<State>(State.Starting)
    val state: StateFlow<State> = mutableState

    fun start(context: Context) {
        val main = ContextCompat.getMainExecutor(context)
        val config = WebViewStartUpConfig.Builder(Executors.newSingleThreadExecutor()).build()
        WebViewCompat.startUpWebView(context, config, object : WebViewOutcomeReceiver<WebViewStartUpResult, WebViewStartupException> {
            override fun onResult(result: WebViewStartUpResult) = main.execute { afterStartUp(context) }

            // A start-up that fails in the background leaves WebView to start on first use.
            override fun onError(error: WebViewStartupException) = main.execute { afterStartUp(context) }
        })
    }

    private fun afterStartUp(context: Context) {
        val webView = WebViewCompat.getCurrentWebViewPackage(context)
        if (pretendingOld || !meetsRequirement(webView?.versionName) || !requiredFeatures.all(WebViewFeature::isFeatureSupported)) {
            // The version only when it's the reason: a new enough one may lack a feature.
            mutableState.value = State.NeedsUpdate(webView?.packageName ?: DEFAULT_WEBVIEW_PACKAGE, webView?.versionName?.takeUnless(::meetsRequirement))
            return
        }
        ProfileDeletions.run(context)
        WebView.setWebContentsDebuggingEnabled(BuildConfig.DEBUG)
        overrideProxy(context, "http://${TailnetCore.proxyAddress}") { if (!pretendingOld) mutableState.value = State.Ready }
    }

    /** An app view's settings, set once, after its profile. */
    @SuppressLint("SetJavaScriptEnabled")
    fun configure(view: WebView) {
        view.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            allowFileAccess = false
            allowContentAccess = false
            setGeolocationEnabled(false)
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            // window.open and target=_blank navigate in place, with their real initiator.
            setSupportMultipleWindows(false)
            mediaPlaybackRequiresUserGesture = false
            builtInZoomControls = true
            displayZoomControls = false
            useWideViewPort = true
            loadWithOverviewMode = true
            userAgentString = WebViewPool.userAgentName(after = WebSettings.getDefaultUserAgent(view.context))
        }
        followTextSize(view)
        // Client hints say the same: no model and Android 10. Left alone, WebView gives an agent
        // that contains its default (Android 17's is already reduced) the phone's full metadata.
        WebSettingsCompat.setUserAgentMetadata(
            view.settings,
            UserAgentMetadata.Builder(WebSettingsCompat.getUserAgentMetadata(view.settings)).setModel("").setPlatformVersion("10.0.0").build(),
        )
        // WebView offers every frame an integrity check that Play services answers with
        // Ovenlight's identity: off, for every frame a page can make.
        WebSettingsCompat.setWebViewMediaIntegrityApiStatus(
            view.settings,
            WebViewMediaIntegrityApiStatusConfig.Builder(WebViewMediaIntegrityApiStatusConfig.WEBVIEW_MEDIA_INTEGRITY_API_DISABLED).build(),
        )
        if (WebViewFeature.isFeatureSupported(WebViewFeature.ALGORITHMIC_DARKENING)) {
            WebSettingsCompat.setAlgorithmicDarkeningAllowed(view.settings, false)
        }
    }

    /** A page's text follows the phone's font size, as Chrome's does: WebView alone keeps it at 100%. */
    fun followTextSize(view: WebView, fontScale: Float = view.resources.configuration.fontScale) {
        view.settings.textZoom = (fontScale * 100).roundToInt()
    }

    /**
     * Sends every request of every WebView to [proxy]: no implicit loopback or link-local
     * bypass and no direct rule, so a proxy that refuses or is gone means no load, never
     * a direct one.
     */
    fun overrideProxy(context: Context, proxy: String, done: () -> Unit) {
        val config = ProxyConfig.Builder().addProxyRule(proxy).removeImplicitRules().build()
        ProxyController.getInstance().setProxyOverride(config, ContextCompat.getMainExecutor(context), done)
    }

    private var pretendingOld = false

    /**
     * Debug builds show the update screen on request, before or after start-up has run, and
     * then make no web view.
     */
    fun pretendNeedsUpdate() {
        pretendingOld = true
        mutableState.value = State.NeedsUpdate(DEFAULT_WEBVIEW_PACKAGE)
    }

    fun meetsRequirement(versionName: String?): Boolean =
        (versionName?.substringBefore('.')?.toIntOrNull() ?: 0) >= MINIMUM_MAJOR_VERSION
}

/**
 * Profiles whose app's data ended. A profile loaded in this process can't be deleted, so
 * its name is kept, synchronously, and the profile is deleted at the next launch before
 * any profile loads.
 */
@SuppressLint("RequiresFeature") // Runs only once the requirement check found MULTI_PROFILE.
object ProfileDeletions {
    private const val KEY = "pendingProfileDeletions"

    private fun preferences(context: Context) = context.getSharedPreferences("webview", Context.MODE_PRIVATE)

    fun enqueue(context: Context, profileName: String) {
        val pending = preferences(context).getStringSet(KEY, emptySet()).orEmpty()
        preferences(context).edit(commit = true) { putStringSet(KEY, pending + profileName) }
    }

    fun run(context: Context) {
        val pending = preferences(context).getStringSet(KEY, emptySet()).orEmpty()
        if (pending.isEmpty()) return
        val store = ProfileStore.getInstance()
        val existing = store.allProfileNames.toSet()
        val left = pending.filter { name -> name in existing && !runCatching { store.deleteProfile(name) }.getOrDefault(false) }
        preferences(context).edit(commit = true) { putStringSet(KEY, left.toSet()) }
    }
}

/**
 * The answer to a WebView's HTTP auth challenge. Only the proxy's own challenge, from
 * 127.0.0.1 with its realm, gets a credential, and only for a view in its app's own
 * profile, whose name is the app's data store ID. Every other challenge is canceled, so a
 * server's own 401 shows its page.
 */
object ProxyChallenge {
    data class Credential(val routeKey: String, val secret: String)

    fun answer(host: String?, realm: String?, viewProfile: String?, appDataStoreID: String, proxyRealm: String, secret: String): Credential? {
        if (host != "127.0.0.1" || realm != proxyRealm || appDataStoreID.isEmpty() || viewProfile != appDataStoreID) return null
        return Credential(routeKey(appDataStoreID), secret)
    }

    fun routeKey(appDataStoreID: String) = "app-$appDataStoreID"
}
