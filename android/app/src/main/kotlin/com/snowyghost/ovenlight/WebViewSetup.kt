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
import androidx.webkit.WebSettingsCompat
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import androidx.webkit.WebViewOutcomeReceiver
import androidx.webkit.WebViewStartUpConfig
import androidx.webkit.WebViewStartUpResult
import androidx.webkit.WebViewStartupException
import java.util.concurrent.Executors
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Starts WebView behind the lock and readies it for apps: nothing else touches WebView
 * before startUpWebView's callback, where the requirement check, the queued profile
 * deletions and the proxy override run, and no WebView is made until the override's own
 * callback has run.
 */
object WebViewSetup {
    sealed interface State {
        data object Starting : State
        /** This phone's WebView is too old or lacks a feature Ovenlight needs. */
        data class NeedsUpdate(val webViewPackage: String) : State
        data object Ready : State
    }

    const val MINIMUM_MAJOR_VERSION = 140
    val requiredFeatures = listOf(
        WebViewFeature.PROXY_OVERRIDE,
        WebViewFeature.MULTI_PROFILE,
        WebViewFeature.DELETE_BROWSING_DATA,
        WebViewFeature.DOCUMENT_START_SCRIPT,
        WebViewFeature.WEB_MESSAGE_LISTENER,
        WebViewFeature.WEB_MESSAGE_ARRAY_BUFFER,
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
            mutableState.value = State.NeedsUpdate(webView?.packageName ?: DEFAULT_WEBVIEW_PACKAGE)
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
        if (WebViewFeature.isFeatureSupported(WebViewFeature.ALGORITHMIC_DARKENING)) {
            WebSettingsCompat.setAlgorithmicDarkeningAllowed(view.settings, false)
        }
        if (WebViewFeature.isFeatureSupported(WebViewFeature.SAFE_BROWSING_ENABLE)) {
            WebSettingsCompat.setSafeBrowsingEnabled(view.settings, false)
        }
    }

    /**
     * Sends every request of every WebView to [proxy]: no implicit loopback or link-local
     * bypass and no direct rule, so a proxy that refuses or is gone means no load, never
     * a direct one.
     */
    fun overrideProxy(context: Context, proxy: String, done: () -> Unit) {
        // Without the override nothing may load, so done never runs and no view is made.
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.PROXY_OVERRIDE)) return
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
object ProfileDeletions {
    private const val KEY = "pendingProfileDeletions"

    private fun preferences(context: Context) = context.getSharedPreferences("webview", Context.MODE_PRIVATE)

    fun enqueue(context: Context, profileName: String) {
        val pending = preferences(context).getStringSet(KEY, emptySet()).orEmpty()
        preferences(context).edit(commit = true) { putStringSet(KEY, pending + profileName) }
    }

    fun run(context: Context) {
        val pending = preferences(context).getStringSet(KEY, emptySet()).orEmpty()
        if (pending.isEmpty() || !WebViewFeature.isFeatureSupported(WebViewFeature.MULTI_PROFILE)) return
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
