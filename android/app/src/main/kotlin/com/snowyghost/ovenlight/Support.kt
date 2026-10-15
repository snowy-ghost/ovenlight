package com.snowyghost.ovenlight

import java.io.IOException
import java.io.InputStream
import java.net.URI

/** Where people reach the team behind Ovenlight. */
object AppLinks {
    const val supportEmail = "team@snowyghost.com"
    val privacyPolicy = URI("https://ovenlight.app/privacy")
    val tailscalePricing = URI("https://tailscale.com/pricing")
}

/** A message to the team, opened in the person's mail app for them to finish and send. */
data class MailDraft(val to: String = AppLinks.supportEmail, val subject: String, val body: String) {
    /** The draft as a mailto: link, which is how Android hands it to a mail app. */
    val mailtoURL: URI?
        get() = uri("mailto:$to?subject=${encoded(subject)}&body=${encoded(body)}")

    companion object {
        fun contactUs(version: String) = MailDraft(subject = "Ovenlight", body = "\n\nOvenlight $version")

        /**
         * Report a Problem for one app: what it is and where it comes from, how its connection
         * is doing (none for an app loaded directly) and where opening it got, with room at the
         * top for what went wrong. Nothing else is attached. Where iOS ends with its `iOS`
         * version, Android gives [android] (`Build.VERSION.RELEASE`), [webView] (the WebView
         * package's version) and [model] (`Build.MANUFACTURER` and `Build.MODEL`).
         */
        fun problemReport(
            appName: String, host: String, sharedBy: String?, connection: String?, opening: String? = null,
            version: String, android: String, webView: String, model: String,
        ): MailDraft {
            val lines = mutableListOf("What happened:", "", "", "", "App: $appName", "Address: $host")
            sharedBy?.let { lines += "Shared by: $it" }
            connection?.let { lines += "Connection: $it" }
            opening?.let { lines += "Opening: $it" }
            lines += listOf("Ovenlight: $version", "Android: $android", "WebView: $webView", "Model: $model")
            return MailDraft(subject = "Ovenlight report: $appName", body = lines.joinToString("\n"))
        }

        /**
         * The state as shown, then in detail as the log has it (no sign-in link), and whether
         * the network lists the app's computer as offline.
         */
        fun connection(state: NodeState, listedOffline: Boolean): String =
            "${state.summary} (${state.logDescription}${if (listedOffline) ", computer listed offline" else ""})"

        private const val hexDigits = "0123456789ABCDEF"

        /**
         * Percent-encoded as iOS encodes a query value: UTF-8, leaving only letters, digits and
         * `!$'()*,-./:;@_~`, so `&+=?#` can't cut an app name short.
         */
        private fun encoded(value: String): String = buildString {
            for (byte in value.toByteArray(Charsets.UTF_8)) {
                val c = byte.toInt() and 0xFF
                if (c in 'a'.code..'z'.code || c in 'A'.code..'Z'.code || c in '0'.code..'9'.code || c.toChar() in "!$'()*,-./:;@_~") {
                    append(c.toChar())
                } else {
                    append('%').append(hexDigits[c shr 4]).append(hexDigits[c and 0xF])
                }
            }
        }
    }
}

/** Every license the app ships, which the build writes into assets/licenses. */
object Acknowledgements {
    val files = listOf("android-libraries.txt", "go-core.txt", "source-serif-4.txt")

    /** All of them in one text, or null when any is missing; [open] opens an asset by path. */
    fun notices(open: (String) -> InputStream): String? = try {
        files.joinToString("\n\n") { name -> open("licenses/$name").use { strictUTF8(it.readBytes()) } }
    } catch (_: IOException) {
        null
    }

    /** One entry per paragraph, so a lazy list lays out only what shows: the text runs to thousands of lines. */
    fun paragraphs(notices: String?): List<String> =
        (notices ?: "The licenses are missing from this build.").split("\n\n").map { it.trim(*newlines) }.filter { it.isNotEmpty() }

    /** What iOS's `.newlines` trims. */
    private val newlines = charArrayOf('\n', '\r', '\u000B', '\u000C', '\u0085', '\u2028', '\u2029')
}
