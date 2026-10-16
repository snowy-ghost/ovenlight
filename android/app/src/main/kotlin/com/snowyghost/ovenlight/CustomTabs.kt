package com.snowyghost.ovenlight

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.browser.customtabs.CustomTabColorSchemeParams
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

    /** Opens [url]; false when nothing on the phone can show it. */
    fun open(context: Context, url: URI, toolbarColor: Int? = null): Boolean {
        val uri = url.toString().toUri()
        val browser = browser(context)
        // With no browser to name, Android would hand Ovenlight's own App Link back to Ovenlight.
        if (browser == null && NavigationPolicy.isOvenlightLink(url)) return false
        val tabs = browser != null && CustomTabsClient.getPackageName(context, listOf(browser), true) == browser
        val intent = if (tabs) {
            val builder = CustomTabsIntent.Builder().setShowTitle(true).setShareState(CustomTabsIntent.SHARE_STATE_OFF)
            toolbarColor?.let { builder.setDefaultColorSchemeParams(CustomTabColorSchemeParams.Builder().setToolbarColor(it).build()) }
            builder.build().intent.setData(uri)
        } else {
            Intent(Intent.ACTION_VIEW, uri).addCategory(Intent.CATEGORY_BROWSABLE)
        }
        browser?.let(intent::setPackage)
        return try {
            context.startActivity(intent)
            true
        } catch (_: ActivityNotFoundException) {
            chosen = null
            false
        }
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
