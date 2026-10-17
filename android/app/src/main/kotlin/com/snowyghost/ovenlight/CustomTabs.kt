package com.snowyghost.ovenlight

import android.app.Service
import android.content.ComponentName
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Binder
import androidx.activity.ComponentActivity
import androidx.browser.customtabs.CustomTabsClient
import androidx.browser.customtabs.CustomTabsIntent
import androidx.browser.customtabs.CustomTabsServiceConnection
import androidx.browser.customtabs.CustomTabsSession
import androidx.core.net.toUri
import java.net.URI

/**
 * Web pages outside Ovenlight's apps (Tailscale's sign-in and Machines page, the support and
 * privacy pages) open in a Custom Tab, never a WebView, since Google refuses sign-in in an
 * embedded one. The browser is chosen once and kept, so the sign-in carries over to the
 * approval; where no browser offers Custom Tabs, pages open in one browser through ACTION_VIEW.
 */
object CustomTabs {
    private var chosen: String? = null

    /** The browser [connect] bound to, how, and the session it gave, once it answers. */
    private var connected: String? = null
    private var connection: CustomTabsServiceConnection? = null
    private var session: CustomTabsSession? = null

    /**
     * Connects to the browser ahead of a sign-in or approval. A tab opened with the session it
     * gives (which [open] uses for those pages alone) keeps Ovenlight running while the tab is in
     * front (see [TabKeepAlive]). Without it Android freezes Ovenlight about a minute after the
     * tab covers it, and the node can't see the phone connect until the tab closes.
     */
    fun connect(context: Context) {
        val browser = browser(context) ?: return
        if (connected == browser || CustomTabsClient.getPackageName(context, listOf(browser), true) != browser) return
        disconnect(context)
        val app = context.applicationContext
        val connection = object : CustomTabsServiceConnection() {
            override fun onCustomTabsServiceConnected(name: ComponentName, client: CustomTabsClient) {
                session = client.newSession(null)
            }

            override fun onServiceDisconnected(name: ComponentName) {
                session = null
            }

            // The browser was updated or force-stopped: Ovenlight connects again as it comes to the front.
            override fun onBindingDied(name: ComponentName) = disconnect(app)
        }
        if (systemCall("connect to $browser", false) { CustomTabsClient.bindCustomTabsService(app, browser, connection) }) {
            connected = browser
            this.connection = connection
        }
    }

    /**
     * Lets the browser go once the phone is connected, so a binding that lives with the process
     * can't start the browser again after Android ends it. A tab still open keeps its keep-alive,
     * which the browser holds for the session.
     */
    fun disconnect(context: Context) {
        connection?.let { systemCall("disconnect from $connected", Unit) { context.applicationContext.unbindService(it) } }
        connected = null
        connection = null
        session = null
    }

    /**
     * Opens [url]; false when nothing on the phone can show it. Ovenlight's own tab is an
     * excursion (see AppLifecycle), which opening one begins, so a sign-in or a page read in it
     * finds everything as it left it. Only the sign-in and Machines pages, [keepAlive], go with
     * Ovenlight's session: any other page would get Ovenlight as its referrer, and keep
     * Ovenlight running behind someone else's site.
     */
    fun open(context: Context, url: URI, lifecycle: AppLifecycle, keepAlive: Boolean = false): Boolean {
        val uri = url.toString().toUri()
        val browser = browser(context)
        // With no browser to name, Android would hand Ovenlight's own App Link back to Ovenlight.
        if (browser == null && NavigationPolicy.isOvenlightLink(url)) return false
        val tabs = browser != null && CustomTabsClient.getPackageName(context, listOf(browser), true) == browser
        val intent = if (tabs) {
            val session = session?.takeIf { keepAlive && connected == browser }
            CustomTabsIntent.Builder(session).setShowTitle(true).setShareState(CustomTabsIntent.SHARE_STATE_OFF).build().intent.setData(uri).apply {
                if (session != null) putExtra(EXTRA_KEEP_ALIVE, Intent(context, TabKeepAlive::class.java))
            }
        } else {
            Intent(Intent.ACTION_VIEW, uri).addCategory(Intent.CATEGORY_BROWSABLE)
        }
        browser?.let(intent::setPackage)
        if (context.startOrFalse(intent)) {
            lifecycle.beginExcursion()
            return true
        }
        chosen = null
        return false
    }

    /** The browser pages open in: the default one if it offers Custom Tabs, else any that does, else the default. */
    private fun browser(context: Context): String? {
        chosen?.let { if (installed(context, it)) return it }
        val pick = CustomTabsClient.getPackageName(context, null) ?: defaultBrowser(context)
        chosen = pick
        return pick
    }

    private fun defaultBrowser(context: Context): String? {
        val view = Intent(Intent.ACTION_VIEW, "https://ovenlight.app/".toUri()).addCategory(Intent.CATEGORY_BROWSABLE)
        val match = context.packageManager.resolveActivity(view, PackageManager.MATCH_DEFAULT_ONLY)?.activityInfo?.packageName
        // "android" is the chooser: no default is set, so take the first browser there is.
        return match?.takeIf { it != "android" }
            ?: context.packageManager.queryIntentActivities(view, 0).firstOrNull()?.activityInfo?.packageName
    }

    private fun installed(context: Context, packageName: String): Boolean =
        runCatching { context.packageManager.getPackageInfo(packageName, 0) }.isSuccess

    /** Chrome's: the service its tab binds while in front. androidx.browser has no builder for it. */
    private const val EXTRA_KEEP_ALIVE = "android.support.customtabs.extra.KEEP_ALIVE"
}

/**
 * Bound by the browser while Ovenlight's tab is in front, which makes Ovenlight's process count
 * as visible, so Android doesn't freeze it: the node goes on learning where the sign-in got, and
 * Ovenlight comes back over the tab once the phone is connected. It does nothing else.
 */
class TabKeepAlive : Service() {
    override fun onBind(intent: Intent) = Binder()
}

/**
 * A call that hands a page's address to the system: starting an activity, or asking which
 * ones take an intent. Any of them throws for an intent too big for a binder transaction
 * (TransactionTooLargeException, wrapped in a RuntimeException), as well as for no app or an
 * app closed to others, so each goes through here and comes back [fallback] instead.
 */
internal inline fun <T> systemCall(what: String, fallback: T, call: () -> T): T = try {
    call()
} catch (e: RuntimeException) {
    OvenlightLog.i("couldn't $what: ${e.javaClass.simpleName}")
    fallback
}

/** Starts another app's screen; false when nothing takes it (see [systemCall]). */
internal fun Context.startOrFalse(intent: Intent): Boolean = systemCall("start ${intent.action}", false) { startActivity(intent); true }

/** The activity under a context: a page's context holds it while the page is on screen. */
internal tailrec fun Context.componentActivity(): ComponentActivity? = when (this) {
    is ComponentActivity -> this
    is ContextWrapper -> baseContext.componentActivity()
    else -> null
}
