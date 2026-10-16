package com.snowyghost.ovenlight

import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.PackageManager
import androidx.activity.ComponentActivity
import androidx.browser.customtabs.CustomTabsClient
import androidx.browser.customtabs.CustomTabsIntent
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

    /**
     * Opens [url]; false when nothing on the phone can show it. Ovenlight's own tab is an
     * excursion (see AppLifecycle), which opening one begins, so a sign-in or a page read in it
     * finds everything as it left it.
     */
    fun open(context: Context, url: URI, lifecycle: AppLifecycle): Boolean {
        val uri = url.toString().toUri()
        val browser = browser(context)
        // With no browser to name, Android would hand Ovenlight's own App Link back to Ovenlight.
        if (browser == null && NavigationPolicy.isOvenlightLink(url)) return false
        val tabs = browser != null && CustomTabsClient.getPackageName(context, listOf(browser), true) == browser
        val intent = if (tabs) {
            CustomTabsIntent.Builder().setShowTitle(true).setShareState(CustomTabsIntent.SHARE_STATE_OFF).build().intent.setData(uri)
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
