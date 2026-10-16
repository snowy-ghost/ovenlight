package com.snowyghost.ovenlight

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.text.format.Formatter
import android.util.Log
import android.webkit.CookieManager
import android.webkit.DownloadListener
import android.webkit.MimeTypeMap
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.webkit.URLUtilCompat
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.URI
import java.util.Base64
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * One page's downloads (iOS's WKDownload half of the WebCoordinator). WebView never downloads:
 * its DownloadListener hands over a URL and cancels. So an http(s) file is fetched again through
 * the proxy on the app's route, with the profile's cookies and the view's user agent; a blob's
 * bytes come over the page bridge, read at the click; and a data URL is decoded here, once the
 * page script reported the click that started it. For a blob or data URL, WebView's start and
 * the page's report are matched by URL, in either order, so only a click that really started a
 * download makes a file. Each file gets a folder of its own under cacheDir/Downloads (emptied at
 * launch) and waits on [DownloadShelf] for Ovenlight's Save, Share and Open sheet. Main thread only.
 */
class PageDownloads(
    private val appID: UUID,
    private val appHost: String,
    private val folder: File,
    private val scope: CoroutineScope,
    /** The page is on screen and Ovenlight unlocked: only then may a download start. */
    private val mayStart: () -> Boolean,
    private val isLocked: () -> Boolean,
    /** A client through the proxy on the app's route key, with the profile's cookies. */
    private val client: () -> OkHttpClient,
    /** Downloads are under way; the pool keeps such a page (see [AppPage.isDownloading]). */
    private val busy: (Boolean) -> Unit = {},
    private val now: () -> Long = SystemClock::elapsedRealtime,
) : DownloadListener {
    private class Started(val type: String, val at: Long)
    private class Clicked(val name: String, val fromHost: String, val at: Long)
    private class Read(val blob: PageMessage.Blob, val fromHost: String, val at: Long)

    /** Blob and data URLs WebView started, and what the page script reported, by URL. */
    private val started = mutableMapOf<String, Started>()
    private val clicks = mutableMapOf<String, Clicked>()
    private val reads = mutableMapOf<String, Read>()
    private val jobs = mutableSetOf<Job>()

    override fun onDownloadStart(url: String, userAgent: String?, contentDisposition: String?, mimetype: String?, contentLength: Long) {
        forgetStale()
        // Not deferred: a script's click would start one under the lock or another app.
        if (!mayStart()) return log("refused a download while off screen or locked")
        when (url.substringBefore(':').lowercase()) {
            "http", "https" -> {
                val uri = runCatching { URI(url) }.getOrNull()
                if (uri == null || !DownloadPolicy.allows(uri, from = null, appHost = appHost, locked = isLocked())) return log("refused a download from another site")
                fetch(url, userAgent, contentDisposition, mimetype, clicks.remove(url)?.name)
            }
            "blob", "data" -> {
                started[url] = Started(mimetype.orEmpty(), now())
                match(url)
            }
            else -> log("refused a ${url.substringBefore(':')} download")
        }
    }

    /** The page script saw a click on an `<a download>`: its name, and for a data URL, the go-ahead. */
    fun clicked(href: String, name: String, fromHost: String) {
        forgetStale()
        clicks[href] = Clicked(name, fromHost, now())
        if (href.startsWith("data:", ignoreCase = true)) match(href)
    }

    /** A blob's bytes, which the page script read as its `<a download>` was clicked. */
    fun blob(blob: PageMessage.Blob, fromHost: String) {
        forgetStale()
        reads[blob.href] = Read(blob, fromHost, now())
        match(blob.href)
    }

    /** The page is going: its downloads stop, and their partial files go. */
    fun cancel() {
        jobs.toList().forEach { it.cancel() }
        started.clear()
        clicks.clear()
        reads.clear()
    }

    private fun match(url: String) {
        val start = started[url]
        if (start == null) return later()
        if (url.startsWith("blob:", ignoreCase = true)) {
            val read = reads.remove(url) ?: return later()
            started.remove(url)
            val uri = runCatching { URI(url) }.getOrNull() ?: return
            if (!DownloadPolicy.allows(uri, from = read.fromHost, appHost = appHost, locked = isLocked())) return log("refused a blob download")
            val type = read.blob.type.ifEmpty { start.type }
            save(read.blob.name) { DataURL.Decoded(type, read.blob.bytes) }
        } else {
            val click = clicks.remove(url) ?: return later()
            started.remove(url)
            // A data URL has no origin of its own: only the app's page reporting the click counts.
            val uri = URI("data", url.substringAfter(':').take(64), null)
            if (!DownloadPolicy.allows(uri, from = click.fromHost, appHost = appHost, locked = isLocked())) return log("refused a data download")
            save(click.name) { DataURL.decode(url) }
        }
    }

    /** Whatever half waits for the other is forgotten after [PAIR_MILLIS], bytes and all. */
    private fun later() {
        scope.launch {
            delay(PAIR_MILLIS + 100)
            forgetStale()
        }
    }

    private fun forgetStale() {
        val since = now() - PAIR_MILLIS
        started.values.removeAll { it.at < since }
        clicks.values.removeAll { it.at < since }
        reads.values.removeAll { it.at < since }
    }

    private fun fetch(url: String, userAgent: String?, disposition: String?, type: String?, name: String?) {
        val client = client()
        track {
            val request = Request.Builder().url(url).apply { userAgent?.let { header("User-Agent", it) } }.build()
            val call = client.newCall(request)
            currentCoroutineContext()[Job]?.invokeOnCompletion { if (it != null) call.cancel() }
            call.execute().use { response ->
                val sent = response.header("Content-Disposition") ?: disposition
                if (DownloadPolicy.action(response.code, sent, canShow = false) == DownloadPolicy.ResponseAction.Show) {
                    log("a download answered ${response.code}")
                    return@track null
                }
                val sentType = response.header("Content-Type")?.substringBefore(';')?.trim() ?: type
                write(name?.ifEmpty { null } ?: URLUtilCompat.guessFileName(url, sent, sentType)) { out -> response.body.byteStream().copyTo(out) }
            }
        }
    }

    /** An empty [name] names the file after its type. */
    private fun save(name: String, decode: () -> DataURL.Decoded?) {
        track {
            val decoded = decode() ?: return@track null
            write(name.ifEmpty { guessedName(decoded.type) }) { it.write(decoded.bytes) }
        }
    }

    /** Runs a download off the main thread; a file it makes waits for the sheet. */
    private fun track(work: suspend () -> File?) {
        val job = scope.launch {
            try {
                withContext(Dispatchers.IO) { work()?.let { DownloadShelf.add(appID, it) } }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log("a download failed: ${e.message}")
            }
        }
        jobs += job
        busy(true)
        job.invokeOnCompletion {
            scope.launch {
                jobs -= job
                busy(jobs.isNotEmpty())
            }
        }
    }

    /** Writes a file in a folder of its own, keeping the page's name; an empty one is no file. */
    private fun write(name: String, body: (OutputStream) -> Unit): File? {
        val directory = File(folder, UUID.randomUUID().toString())
        if (!directory.mkdirs()) throw IOException("no folder for the download")
        val file = File(directory, DownloadPolicy.fileName(name))
        try {
            file.outputStream().use(body)
        } catch (e: Throwable) {
            directory.deleteRecursively()
            throw e
        }
        if (file.length() == 0L) {
            directory.deleteRecursively()
            return null
        }
        return file
    }

    private fun guessedName(type: String): String =
        "Download" + (MimeTypeMap.getSingleton().getExtensionFromMimeType(type.lowercase())?.let { ".$it" } ?: "")

    private fun log(line: String) {
        Log.i(TAG, "$appHost: $line")
    }

    companion object {
        private const val TAG = "Ovenlight"

        /** The largest blob or data URL that comes over the page bridge. */
        const val BLOB_LIMIT = 32 * 1024 * 1024

        /** How long WebView's start and the page script's report wait for each other. */
        const val PAIR_MILLIS = 5_000L
    }
}

/**
 * Finished files waiting for their app's page to show them, oldest first (iOS's
 * waitingDownloads). They outlive the app's view; all of them go at the next launch.
 */
object DownloadShelf {
    data class Download(val file: File, /** What Open offers it as; null when it doesn't. */ val type: String?)

    private val files = MutableStateFlow<Map<UUID, List<Download>>>(emptyMap())
    val waiting: StateFlow<Map<UUID, List<Download>>> = files

    /** Reads the file's type, so call it off the main thread. */
    fun add(app: UUID, file: File) {
        val download = Download(file, DownloadFiles.openableType(DownloadFiles.head(file)))
        files.update { it + (app to it[app].orEmpty() + download) }
    }

    fun remove(app: UUID, download: Download) = files.update { map ->
        val left = map[app].orEmpty() - download
        if (left.isEmpty()) map - app else map + (app to left)
    }

    fun folder(context: Context) = File(context.cacheDir, "Downloads")

    /** Last session's files: saved, shared, opened or left waiting. */
    fun emptyAtLaunch(context: Context) {
        folder(context).deleteRecursively()
    }
}

/** A `data:` URL's bytes, as fetch decodes them: percent-decoded, then base64 if it says so. */
object DataURL {
    class Decoded(val type: String, val bytes: ByteArray)

    fun decode(url: String): Decoded? {
        if (!url.startsWith("data:", ignoreCase = true)) return null
        val comma = url.indexOf(',').takeIf { it >= 0 } ?: return null
        val meta = url.substring(5, comma)
        val body = percentDecoded(url.substring(comma + 1))
        val type = meta.substringBefore(';').trim().ifEmpty { "text/plain" }
        if (!meta.trimEnd().endsWith(";base64", ignoreCase = true)) return Decoded(type, body)
        return runCatching { Decoded(type, Base64.getMimeDecoder().decode(body)) }.getOrNull()
    }

    private fun percentDecoded(text: String): ByteArray {
        val bytes = text.toByteArray(Charsets.UTF_8)
        val out = ByteArrayOutputStream(bytes.size)
        var i = 0
        while (i < bytes.size) {
            if (bytes[i] == '%'.code.toByte() && i + 2 < bytes.size) {
                val high = Character.digit(bytes[i + 1].toInt(), 16)
                val low = Character.digit(bytes[i + 2].toInt(), 16)
                if (high >= 0 && low >= 0) {
                    out.write(high * 16 + low)
                    i += 3
                    continue
                }
            }
            out.write(bytes[i].toInt())
            i += 1
        }
        return out.toByteArray()
    }
}

/**
 * What a finished file is, from its first bytes rather than its name or the server's word, and
 * so what Open may hand it to another app as: PDFs, images, audio, video and plain text. Never
 * HTML, SVG, XML or an APK, which would run or install what a page wrote.
 */
object DownloadFiles {
    const val HEAD = 4096
    const val ANY = "application/octet-stream"

    fun head(file: File): ByteArray = runCatching { file.inputStream().use(::head) }.getOrDefault(ByteArray(0))

    fun head(input: InputStream): ByteArray {
        val buffer = ByteArray(HEAD)
        var count = 0
        while (count < HEAD) {
            val read = input.read(buffer, count, HEAD - count)
            if (read < 0) break
            count += read
        }
        return buffer.copyOf(count)
    }

    fun openableType(head: ByteArray): String? {
        fun bytes(at: Int, vararg expected: Int) = head.size >= at + expected.size && expected.indices.all { head[at + it] == expected[it].toByte() }
        fun text(at: Int, expected: String) = bytes(at, *expected.map { it.code }.toIntArray())
        val brand = if (text(4, "ftyp") && head.size >= 12) head.decodeToString(8, 12) else null
        return when {
            text(0, "%PDF-") -> "application/pdf"
            bytes(0, 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A) -> "image/png"
            bytes(0, 0xFF, 0xD8, 0xFF) -> "image/jpeg"
            text(0, "GIF87a") || text(0, "GIF89a") -> "image/gif"
            text(0, "RIFF") && text(8, "WEBP") -> "image/webp"
            text(0, "RIFF") && text(8, "WAVE") -> "audio/wav"
            brand in setOf("heic", "heix", "mif1") -> "image/heic"
            brand in setOf("M4A ", "M4B ") -> "audio/mp4"
            brand != null -> "video/mp4"
            text(0, "ID3") || bytes(0, 0xFF, 0xFB) || bytes(0, 0xFF, 0xF3) || bytes(0, 0xFF, 0xF2) -> "audio/mpeg"
            text(0, "OggS") -> "audio/ogg"
            text(0, "fLaC") -> "audio/flac"
            bytes(0, 0x1A, 0x45, 0xDF, 0xA3) -> "video/webm"
            isPlainText(head) -> "text/plain"
            else -> null
        }
    }

    /**
     * UTF-8 with no control characters but tabs, line and page breaks, and not markup: nothing
     * whose first character is `<`, so no HTML, XML or SVG.
     */
    private fun isPlainText(head: ByteArray): Boolean {
        if (head.isEmpty()) return false
        if (head.any { val c = it.toInt() and 0xFF; c < 0x20 && c != 0x09 && c != 0x0A && c != 0x0C && c != 0x0D || c == 0x7F }) return false
        // A head cut short may end inside a character.
        val text = (0..minOf(3, head.size - 1)).firstNotNullOfOrNull { cut ->
            if (cut > 0 && head.size < HEAD) null else utf8(head.copyOf(head.size - cut))
        } ?: return false
        return !text.trimStart('\uFEFF', ' ', '\t', '\n', '\r', '\u000C').startsWith('<')
    }

    private fun utf8(bytes: ByteArray): String? = runCatching {
        Charsets.UTF_8.newDecoder().decode(java.nio.ByteBuffer.wrap(bytes)).toString()
    }.getOrNull()

    /** The type everything outside Ovenlight sees: the one Open offers, or none in particular. */
    fun sharedType(type: String?) = type ?: ANY
}

/**
 * The finished files, to other apps, through a read grant on each intent. It reports a file's
 * type from its bytes ([DownloadFiles]), never from its name, so a page's `.html` reaches no
 * one as a web page.
 */
class DownloadProvider : FileProvider() {
    override fun getType(uri: Uri): String = DownloadFiles.sharedType(
        runCatching { openFile(uri, "r")?.let { ParcelFileDescriptor.AutoCloseInputStream(it).use(DownloadFiles::head) } }
            .getOrNull()?.let(DownloadFiles::openableType),
    )

    override fun getTypeAnonymous(uri: Uri): String = DownloadFiles.ANY

    companion object {
        fun uri(context: Context, file: File): Uri = getUriForFile(context, "${context.packageName}.downloads", file)
    }
}

/**
 * The profile's own cookies for each URL a download fetches, redirects included, so none goes to
 * a host it wasn't set for, which a hand-set Cookie header would. Cookies a response sets go
 * back into the profile.
 */
class ProfileCookieJar(private val cookies: CookieManager) : CookieJar {
    override fun loadForRequest(url: HttpUrl): List<Cookie> =
        cookies.getCookie(url.toString())?.split(';')?.mapNotNull { Cookie.parse(url, it.trim()) }.orEmpty()

    override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
        cookies.forEach { this.cookies.setCookie(url.toString(), it.toString()) }
    }
}

/**
 * Ovenlight's sheet for the app's oldest finished file, while its page is on screen: Save
 * through the system's create-document picker, Share through the Sharesheet, and Open for the
 * types [DownloadFiles] lets out. Every way out is the person's choice. Closing it, or taking
 * one, lets the next file show; the file stays on disk until the next launch.
 */
@Composable
fun DownloadSheet(model: AppModel, page: AppPage) {
    val waiting by DownloadShelf.waiting.collectAsState()
    val download = waiting[page.app.id]?.firstOrNull() ?: return
    // Each file gets a sheet of its own, which shows itself as it comes.
    key(download) { DownloadSheet(model, page.app, download) }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DownloadSheet(model: AppModel, app: WebApp, download: DownloadShelf.Download) {
    val context = LocalContext.current
    val sheet = rememberModalBottomSheetState()
    val scope = rememberCoroutineScope()
    val uri = remember { DownloadProvider.uri(context, download.file) }
    val canOpen = remember { download.type != null && context.packageManager.resolveActivity(open(uri, download.type), PackageManager.MATCH_DEFAULT_ONLY) != null }
    val size = remember { Formatter.formatShortFileSize(context, download.file.length()) }
    val type = DownloadFiles.sharedType(download.type)
    fun done(action: () -> Unit) {
        action()
        scope.launch { sheet.hide() }.invokeOnCompletion { DownloadShelf.remove(app.id, download) }
    }
    ModalBottomSheet(onDismissRequest = { DownloadShelf.remove(app.id, download) }, sheetState = sheet) {
        DownloadSheetContent(
            name = download.file.name,
            size = size,
            canOpen = canOpen,
            onSave = {
                done {
                    // The picker hands its answer back: an excursion, not leaving.
                    model.lifecycle.beginExcursion()
                    SaveDocument.launch(context, download.file, type, model.scope)
                }
            },
            // The Sharesheet and Open take the person to another app, so they are leaving.
            onShare = {
                done {
                    val send = Intent(Intent.ACTION_SEND).setType(type).putExtra(Intent.EXTRA_STREAM, uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    send.clipData = ClipData.newRawUri(download.file.name, uri)
                    context.startActivity(Intent.createChooser(send, null))
                }
            },
            onOpen = {
                val openAs = download.type ?: return@DownloadSheetContent
                done {
                    try {
                        context.startActivity(open(uri, openAs))
                    } catch (_: ActivityNotFoundException) {
                        // Whatever opened it is gone since the sheet looked.
                    }
                }
            },
        )
    }
}

/**
 * Save, through the system's create-document picker. Registered on the activity outside
 * composition, so the picker's answer reaches the file it was opened for even after the sheet
 * has gone, or the lock came meanwhile.
 */
private object SaveDocument {
    private var next = 0

    fun launch(context: Context, file: File, type: String, scope: CoroutineScope) {
        val activity = generateSequence(context) { (it as? ContextWrapper)?.baseContext }.firstNotNullOfOrNull { it as? ComponentActivity } ?: return
        val failed = activity.getString(R.string.download_save_failed, file.name)
        var launcher: ActivityResultLauncher<String>? = null
        launcher = activity.activityResultRegistry.register("ovenlight.save.${next++}", ActivityResultContracts.CreateDocument(type)) { target ->
            launcher?.unregister()
            target ?: return@register
            scope.launch {
                val saved = withContext(Dispatchers.IO) {
                    runCatching { activity.contentResolver.openOutputStream(target)?.use { out -> file.inputStream().use { it.copyTo(out) } } != null }.getOrDefault(false)
                }
                if (!saved) Toast.makeText(activity, failed, Toast.LENGTH_LONG).show()
            }
        }
        launcher.launch(file.name)
    }
}

private fun open(uri: Uri, type: String) = Intent(Intent.ACTION_VIEW).setDataAndType(uri, type).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)

@Composable
internal fun DownloadSheetContent(name: String, size: String, canOpen: Boolean, onSave: () -> Unit, onShare: () -> Unit, onOpen: () -> Unit) {
    Column(Modifier.navigationBarsPadding().padding(bottom = 8.dp)) {
        ListItem(
            headlineContent = { Text(name, maxLines = 3, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleMedium) },
            supportingContent = { Text(size) },
            leadingContent = { Icon(painterResource(R.drawable.ic_description), contentDescription = null) },
            colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
        )
        HorizontalDivider()
        SheetAction(R.drawable.ic_download, stringResource(R.string.download_save), onSave)
        SheetAction(R.drawable.ic_share, stringResource(R.string.download_share), onShare)
        if (canOpen) SheetAction(R.drawable.ic_open_in_new, stringResource(R.string.download_open), onOpen)
    }
}

@Composable
private fun SheetAction(icon: Int, label: String, onClick: () -> Unit) {
    ListItem(
        headlineContent = { Text(label) },
        leadingContent = { Icon(painterResource(icon), contentDescription = null) },
        colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
        modifier = Modifier.clickable(onClick = onClick),
    )
}
