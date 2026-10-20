package com.snowyghost.ovenlight

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.core.net.toUri
import androidx.webkit.WebViewCompat
import java.io.IOException
import java.io.InputStream
import java.net.URI
import kotlinx.coroutines.launch

/** Where people reach the team behind Ovenlight. */
object AppLinks {
    const val supportEmail = "team@snowyghost.com"
    val privacyPolicy = URI("https://ovenlight.app/privacy/android")

    /** What Tailscale's plans include, tagged devices among them. */
    val tailscalePricing = URI("https://tailscale.com/pricing")

    /** How to publish apps from your own computers. */
    const val ownComputersHelp = "https://ovenlight.app/support#own-computer"
}

/** A message to the team, opened in the person's mail app for them to finish and send. */
data class MailDraft(val to: String = AppLinks.supportEmail, val subject: String, val body: String) {
    /** The draft as a mailto: link, which is how Android hands it to a mail app. */
    val mailtoURL: URI?
        get() = uri("mailto:$to?subject=${encoded(subject)}&body=${encoded(body)}")

    companion object {
        /**
         * Contact us: room to write, then the lines Report a Problem ends with, and how the
         * owner's own computers are connected ([computers]) when they're on.
         */
        fun contactUs(version: String, android: String, webView: String, model: String, computers: String?): MailDraft {
            val lines = listOf("", "", "") + device(version, android, webView, model) + listOfNotNull(computers?.let { "Your computers: $it" })
            return MailDraft(subject = "Ovenlight", body = lines.joinToString("\n"))
        }

        /** Contact us as things are now. */
        fun contactUs(context: Context, nodes: NodeManager): MailDraft = contactUs(
            version, Build.VERSION.RELEASE, webView(context), model,
            computers = if (nodes.ownerEnabled.value) connection(nodes.owner.state.value, listedOffline = false) else null,
        )

        /**
         * Report a Problem for one app: what it is and where it comes from, how its connection
         * is doing (whether it was refused, for an app with no node) and where opening it got,
         * with room at the top for what went wrong. Nothing else is attached. Where iOS ends with
         * its `iOS` version, Android gives [android] (`Build.VERSION.RELEASE`), [webView] (the
         * WebView package's version) and [model] (`Build.MANUFACTURER` and `Build.MODEL`).
         */
        fun problemReport(
            appName: String, host: String, sharedBy: String?, connection: String?, opening: String? = null,
            version: String, android: String, webView: String, model: String,
        ): MailDraft {
            val lines = mutableListOf("What happened:", "", "", "", "App: $appName", "Address: $host")
            sharedBy?.let { lines += "Shared by: $it" }
            connection?.let { lines += "Connection: $it" }
            opening?.let { lines += "Opening: $it" }
            lines += device(version, android, webView, model)
            return MailDraft(subject = "Ovenlight report: $appName", body = lines.joinToString("\n"))
        }

        private fun device(version: String, android: String, webView: String, model: String) =
            listOf("Ovenlight: $version", "Android: $android", "WebView: $webView", "Model: $model")

        /**
         * Report a Problem for one app as it is now: its connection, where opening it got, and
         * Android's lines where iOS has its version: the release, the WebView build (which
         * decides most page bugs) and the phone's model.
         */
        fun problemReport(context: Context, nodes: NodeManager, pool: WebViewPool, app: WebApp): MailDraft = problemReport(
            appName = app.name, host = app.host, sharedBy = app.sharedBy,
            connection = nodes.node(app)?.let { connection(it.state.value, it.isListedOffline(app.host)) }
                ?: "Refused (on no known tailnet)".takeUnless { app.isShared },
            opening = pool.opening(app.id),
            version = version,
            android = Build.VERSION.RELEASE,
            webView = webView(context),
            model = model,
        )

        private val version: String get() = "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})"
        private val model: String get() = "${Build.MANUFACTURER} ${Build.MODEL}"
        private fun webView(context: Context): String = WebViewCompat.getCurrentWebViewPackage(context)?.versionName ?: "unknown"

        /**
         * The state as shown, then in detail as the log has it (no sign-in link), and whether
         * the network lists the app's computer as offline.
         */
        fun connection(state: NodeState, listedOffline: Boolean): String =
            "${state.summary} (${state.logDescription}${if (listedOffline) ", computer listed offline" else ""})"

        /** Where opening the app got, then what a page that failed to load came to (see [AppLoadGate.loadFailure]). */
        fun opening(gate: AppLoadGate, failure: String?): String = gate.logDescription + (failure?.let { ", $it" } ?: "")

        /**
         * Percent-encoded as iOS encodes a query value: UTF-8, leaving only letters, digits and
         * `!$'()*,-./:;@_~`, so `&+=?#` can't cut an app name short.
         */
        private fun encoded(value: String): String = percentEncoded(value, "!$'()*,-./:;@_~")
    }
}

/**
 * The draft as an email intent: `ACTION_SENDTO` with the mailto: link, and the same fields as
 * extras, since some mail apps read only one or the other.
 */
fun MailDraft.intent(): Intent = Intent(Intent.ACTION_SENDTO, (mailtoURL?.toString() ?: "mailto:$to").toUri())
    .putExtra(Intent.EXTRA_EMAIL, arrayOf(to))
    .putExtra(Intent.EXTRA_SUBJECT, subject)
    .putExtra(Intent.EXTRA_TEXT, body)

/** Opens the draft in the person's mail app; false when the phone has none. */
fun MailDraft.send(context: Context): Boolean = context.startOrFalse(intent())

/** For a phone with no email app: where to write instead, with the address and the filled-in [draft] to copy. */
@Composable
fun NoEmailAppDialog(draft: MailDraft, onDismiss: () -> Unit) {
    val clipboard = LocalClipboard.current
    val haptics = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()
    // Closes once copied: closing first would cancel the copy with the dialog's scope.
    fun copy(label: String, text: String) = scope.launch {
        clipboard.setClipEntry(ClipEntry(ClipData.newPlainText(label, text)))
        haptics.performHapticFeedback(HapticFeedbackType.Confirm)
        onDismiss()
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.mail_no_app_title)) },
        text = { Text(stringResource(R.string.mail_no_app_body, AppLinks.supportEmail)) },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.ok)) } },
        dismissButton = {
            TextButton(onClick = { copy(AppLinks.supportEmail, AppLinks.supportEmail) }) { Text(stringResource(R.string.mail_copy_address)) }
            TextButton(onClick = { copy(draft.subject, draft.body) }) { Text(stringResource(R.string.mail_copy_report)) }
        },
    )
}

/** Every license the app ships, which the build writes into assets/licenses. */
object Acknowledgements {
    val files = listOf("android-libraries.txt", "go-core.txt", "libyuv.txt", "material-symbols.txt", "source-serif-4.txt")

    /** All of them in one text, or null when any is missing; [open] opens an asset by path. */
    fun notices(open: (String) -> InputStream): String? = try {
        files.joinToString("\n\n") { name -> open("licenses/$name").use { strictUTF8(it.readBytes()) } }
    } catch (_: IOException) {
        null
    }

    /** One entry per paragraph, so a lazy list lays out only what shows: the text runs to thousands of lines. */
    fun paragraphs(notices: String?): List<String> =
        (notices ?: "The licenses are missing from this build.").split("\n\n").map { it.trim(*newlines) }.filter { it.isNotEmpty() }

    /** A paragraph's lines, each with the spaces that indent it, so a line too long for the screen wraps under its own text. */
    fun lines(paragraph: String): List<Pair<Int, String>> = paragraph.lines().map { line -> line.takeWhile { it == ' ' }.length.let { it to line.drop(it) } }

    /**
     * A line with no spaces, such as a library's "group:artifact:version" or a URL, kept whole
     * where it fits, and otherwise broken only after a colon: a zero-width space after each `:`,
     * and a word joiner after each `-` and `/`, where it would otherwise break.
     */
    fun unbroken(line: String): String =
        if (line.any(Char::isWhitespace)) line else line.replace(Regex("[-/:]")) { it.value + if (it.value == ":") "\u200B" else "\u2060" }

    /** A title underlined with `=`, the heading of one library's or one file's licenses. */
    fun heading(paragraph: String): String? = paragraph.lines().takeIf { it.size == 2 && it[1].isNotEmpty() && it[1].all { c -> c == '=' } }?.first()

    /** What iOS's `.newlines` trims. */
    private val newlines = charArrayOf('\n', '\r', '\u000B', '\u000C', '\u0085', '\u2028', '\u2029')
}
