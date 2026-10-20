package com.snowyghost.ovenlight

import android.annotation.SuppressLint
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.text.format.Formatter
import android.webkit.CookieManager
import android.webkit.DownloadListener
import android.webkit.MimeTypeMap
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
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
import java.net.URI
import java.util.Base64
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.Call
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
    /** Bytes a download may still take where [folder] lives ([DownloadShelf.freeBytes]). */
    private val freeBytes: () -> Long,
    /** Downloads are under way; the pool keeps such a page (see [AppPage.isDownloading]). */
    private val busy: (Boolean) -> Unit = {},
    /** A download the person started on the page didn't make a file: says so, with its name. */
    private val failed: (String?) -> Unit = {},
    private val now: () -> Long = SystemClock::elapsedRealtime,
) : DownloadListener {
    private class Started(val type: String, val at: Long)
    private class Clicked(val name: String, val fromHost: String, val at: Long)
    private class Read(val blob: PageBlob, val fromHost: String, val at: Long)

    /** Blob and data URLs WebView started, and what the page script reported, by URL. */
    private val started = mutableMapOf<String, Started>()
    private val clicks = mutableMapOf<String, Clicked>()
    private val reads = mutableMapOf<String, Read>()
    private val jobs = mutableSetOf<Job>()

    /** The refetches under way, which [cancel] ends at once: a blocked read never sees its job end. */
    private val calls: MutableSet<Call> = ConcurrentHashMap.newKeySet()

    /** A blob message is being taken apart; another meanwhile is dropped. */
    private var parsing = false

    /** Whether the page may start a download now; the bridge drops reports otherwise. */
    val accepting: Boolean get() = mayStart()

    override fun onDownloadStart(url: String, userAgent: String?, contentDisposition: String?, mimetype: String?, contentLength: Long) {
        forgetStale()
        // Not deferred: a script's click would start one under the lock or another app.
        if (!mayStart()) return log("refused a download while off screen or locked")
        when (url.substringBefore(':').lowercase()) {
            "http", "https" -> {
                // As Chromium writes it: java.net.URI refuses some of an app's own addresses.
                val uri = NavigationPolicy.pageURI(url)
                if (uri == null || !DownloadPolicy.allows(uri, from = null, appHost = appHost, locked = isLocked())) return log("refused a download from another site")
                fetch(url, userAgent, contentDisposition, mimetype, contentLength)
            }
            "blob", "data" -> {
                if (url.length > HREF_LIMIT && url.startsWith("data:", ignoreCase = true)) return log("refused a data URL over the limit")
                started.remove(url)
                started[url] = Started(mimetype.orEmpty(), now())
                keepPendingSmall()
                match(url)
            }
            else -> log("refused a ${url.substringBefore(':')} download")
        }
    }

    /** The page script saw a click on an `<a download>`: its name, and for a data URL, the go-ahead. */
    fun clicked(href: String, name: String, fromHost: String) {
        forgetStale()
        if (!mayStart() || href.length > HREF_LIMIT) return
        clicks.remove(href)
        clicks[href] = Clicked(name, fromHost, now())
        keepPendingSmall()
        if (href.startsWith("data:", ignoreCase = true)) match(href)
    }

    /** The page script's message with a blob's bytes, read at the click; it's taken apart off the main thread. */
    fun blobMessage(message: ByteArray, fromHost: String) {
        if (!mayStart() || parsing) return
        parsing = true
        scope.launch {
            try {
                val blob = withContext(Dispatchers.Default) { PageMessage.blob(message) } ?: return@launch
                blob(blob, fromHost)
            } finally {
                parsing = false
            }
        }
    }

    /** A blob's bytes, which the page script read as its `<a download>` was clicked. */
    fun blob(blob: PageBlob, fromHost: String) {
        forgetStale()
        if (!mayStart()) return
        // One read waits at a time: a newer click replaces it.
        reads.clear()
        reads[blob.href] = Read(blob, fromHost, now())
        keepPendingSmall()
        match(blob.href)
    }

    /** What waits to be paired stays small, the oldest going first. */
    private fun keepPendingSmall() {
        fun bytes() = reads.values.sumOf { it.blob.bytes.size.toLong() } + clicks.keys.sumOf { it.length.toLong() } + started.keys.sumOf { it.length.toLong() }
        while (clicks.size > PENDING_CLICKS) clicks.remove(clicks.keys.first())
        while (started.size > PENDING_CLICKS) started.remove(started.keys.first())
        while (bytes() > PENDING_BYTES && (clicks.isNotEmpty() || started.isNotEmpty())) {
            if (clicks.isNotEmpty()) clicks.remove(clicks.keys.first()) else started.remove(started.keys.first())
        }
    }

    /** The page is going: its downloads stop, and their partial files go. */
    fun cancel() {
        jobs.toList().forEach { it.cancel() }
        calls.forEach { it.cancel() }
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
            // The page's text isn't parsed, since a page can write one java.net.URI refuses.
            val uri = URI("data:,")
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

    private fun fetch(url: String, userAgent: String?, disposition: String?, type: String?, length: Long) {
        var name: String? = null
        val shownName = {
            name?.ifEmpty { null }?.let(DownloadPolicy::fileName)
                ?: disposition?.let(URLUtilCompat::getFilenameFromContentDisposition)?.let(DownloadPolicy::fileName)
                ?: DownloadPolicy.name(url)
        }
        val client = client()
        val named: suspend () -> Boolean = {
            // The page's report of an `<a download>` click comes over a message port, which keeps
            // no order with WebView's start: its name may still be on the way.
            for (wait in 1..CLICK_WAITS) {
                name = clicks.remove(url)?.name
                if (name != null) break
                delay(CLICK_WAIT_MILLIS)
            }
            // The page may have gone off screen meanwhile, without Ovenlight leaving.
            mayStart().also { if (!it) log("refused a download while off screen or locked") }
        }
        track(shownName, first = named) {
            val request = Request.Builder().url(url).apply { userAgent?.let { header("User-Agent", it) } }.build()
            val call = client.newCall(request)
            calls += call
            try {
                call.execute()
            } finally {
                calls -= call
            }.use { response ->
                // WebView hands over the URL it was redirected to; another redirect now could lead
                // anywhere the owner's node reaches, so it isn't followed.
                if (response.code in 300..399) throw IOException("it redirected again")
                val sent = response.header("Content-Disposition") ?: disposition
                if (!DownloadPolicy.isFile(response.code, sent)) {
                    throw IOException("it answered ${response.code}")
                }
                // From the headers on, 30 seconds with nothing arriving ends it.
                response.body.source().timeout().timeout(READ_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                val sentType = response.header("Content-Type")?.substringBefore(';')?.trim() ?: type
                val expected = response.body.contentLength().takeIf { it >= 0 } ?: length
                write(name?.ifEmpty { null } ?: URLUtilCompat.guessFileName(url, sent, sentType), expected, response.body.byteStream())
            }
        }
    }

    /** An empty [name] names the file after its type. */
    private fun save(name: String, decode: () -> DataURL.Decoded?) {
        val shownName = { name.ifEmpty { null }?.let(DownloadPolicy::fileName) }
        track(shownName) {
            val decoded = decode() ?: return@track null
            write(name.ifEmpty { guessedName(decoded.type) }, decoded.bytes.size.toLong(), decoded.bytes.inputStream())
        }
    }

    /**
     * Runs a download off the main thread, after [first] (on the page's own thread) says go; a
     * file it makes waits for the sheet. One that fails says so, under its [name] when it has one.
     */
    private fun track(name: () -> String?, first: suspend () -> Boolean = { true }, work: suspend () -> File?) {
        if (jobs.size >= CONCURRENT) {
            log("refused a download: $CONCURRENT already under way")
            return failed(name())
        }
        val job = scope.launch {
            try {
                if (!first()) return@launch
                withContext(Dispatchers.IO) {
                    val file = work() ?: return@withContext
                    // Cancelled while it finished: the file goes with the rest.
                    if (!isActive) {
                        file.parentFile?.deleteRecursively()
                        return@withContext
                    }
                    if (!DownloadShelf.add(appID, file)) throw IOException("$WAITING_FILES files already wait")
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log("a download failed: ${e.message}")
                // Not one its page ended as it went (see cancel), whose call fails as it stops, nor
                // over the lock or another app.
                if (isActive && mayStart()) failed(name())
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

    /**
     * Writes a file in a folder of its own, keeping the page's name; an empty one is no file. It
     * stops, and the partial file goes, before the phone's free space would fall under the floor.
     */
    private suspend fun write(name: String, expected: Long, input: InputStream): File? {
        val directory = File(folder, UUID.randomUUID().toString())
        if (!directory.mkdirs()) throw IOException("no folder for the download")
        val file = File(directory, DownloadPolicy.fileName(name))
        try {
            if (expected > 0 && freeBytes() - expected < FREE_SPACE_FLOOR) throw IOException("not enough free space")
            file.outputStream().use { out ->
                val buffer = ByteArray(64 * 1024)
                var sinceCheck = 0L
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    currentCoroutineContext().ensureActive()
                    out.write(buffer, 0, read)
                    sinceCheck += read
                    if (sinceCheck >= SPACE_CHECK_BYTES) {
                        sinceCheck = 0
                        if (freeBytes() < FREE_SPACE_FLOOR) throw IOException("the phone is nearly full")
                    }
                }
            }
            if (freeBytes() < FREE_SPACE_FLOOR) throw IOException("the phone is nearly full")
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
        OvenlightLog.i("$appHost: $line")
    }

    companion object {
        // What one page can make Ovenlight hold, run or write: the largest blob over the bridge, the
        // longest URL a click report or data download may carry, the reports and bytes waiting to
        // be paired with WebView's start (and how long they wait), downloads at once, how long one
        // may take, the free space a download must leave on the phone (checked every
        // SPACE_CHECK_BYTES), and the files that may wait for an app's sheet.
        const val BLOB_LIMIT = 32 * 1024 * 1024
        const val HREF_LIMIT = 2 * 1024 * 1024
        const val PENDING_CLICKS = 8

        /** How many times, and how long each, an http download waits for the click report that names it. */
        const val CLICK_WAITS = 10
        const val CLICK_WAIT_MILLIS = 50L
        const val PENDING_BYTES = BLOB_LIMIT.toLong()
        const val PAIR_MILLIS = 5_000L
        const val CONCURRENT = 3
        const val READ_TIMEOUT_SECONDS = 30L
        const val HEADERS_WAIT_MINUTES = 10L
        const val FREE_SPACE_FLOOR = 256L * 1024 * 1024
        const val SPACE_CHECK_BYTES = 1024L * 1024
        const val WAITING_FILES = 10

        /**
         * The client a download is fetched again with: through the proxy on the app's route, the
         * profile's cookies and no redirects. Its server may take minutes to start answering (an
         * export the app builds first, which WebView already waited for once), so the headers
         * get [HEADERS_WAIT_MINUTES]; after them a download stops when nothing arrives for
         * [READ_TIMEOUT_SECONDS] (set in fetch), never for taking long: a large file on a slow
         * link can take an hour.
         */
        fun client(proxied: OkHttpClient.Builder, cookies: CookieJar): OkHttpClient = proxied
            .cookieJar(cookies)
            .followRedirects(false)
            .readTimeout(HEADERS_WAIT_MINUTES, TimeUnit.MINUTES)
            .callTimeout(0, TimeUnit.SECONDS)
            .build()
    }
}

/**
 * Finished files waiting for their app's page to show them, oldest first (iOS's
 * waitingDownloads). They outlive the app's view; all of them go at the next launch, and an
 * app's go when it is removed or its data cleared.
 */
object DownloadShelf {
    data class Download(val file: File, /** What Open offers it as; null when it doesn't. */ val type: String?)

    private val files = MutableStateFlow<Map<UUID, List<Download>>>(emptyMap())
    val waiting: StateFlow<Map<UUID, List<Download>>> = files

    /**
     * Reads the file's type, so call it off the main thread. False, and the file deleted, when
     * [PageDownloads.WAITING_FILES] already wait for the app.
     */
    fun add(app: UUID, file: File): Boolean {
        val download = Download(file, DownloadFiles.openableType(DownloadFiles.head(file)))
        var added = false
        files.update {
            val list = it[app].orEmpty()
            added = list.size < PageDownloads.WAITING_FILES
            if (added) it + (app to list + download) else it
        }
        if (!added) file.parentFile?.deleteRecursively()
        return added
    }

    fun remove(app: UUID, download: Download) = files.update { map ->
        val left = map[app].orEmpty() - download
        if (left.isEmpty()) map - app else map + (app to left)
    }

    /** Drops the app's waiting files and deletes them, when the app goes or its data does. */
    fun drop(app: UUID) {
        var dropped = emptyList<Download>()
        files.update {
            dropped = it[app].orEmpty()
            it - app
        }
        for (download in dropped) download.file.parentFile?.deleteRecursively()
    }

    fun folder(context: Context) = File(context.cacheDir, "Downloads")

    /**
     * The free space where the downloads go. Not StorageManager's allocatable bytes, which count
     * cache the system could clear: a download in cacheDir is such cache, so that figure would
     * stay flat while it fills the phone.
     */
    @SuppressLint("UsableSpace")
    fun freeBytes(context: Context): Long = context.cacheDir.usableSpace

    /** Last session's files: saved, shared, opened or left waiting. */
    fun emptyAtLaunch(context: Context) {
        folder(context).deleteRecursively()
        // Save holds a file's write grant only until it has written; a process that died before
        // that left one behind, which no Save waits for now.
        for (grant in context.contentResolver.persistedUriPermissions) {
            val flags = (if (grant.isReadPermission) Intent.FLAG_GRANT_READ_URI_PERMISSION else 0) or
                (if (grant.isWritePermission) Intent.FLAG_GRANT_WRITE_URI_PERMISSION else 0)
            runCatching { context.contentResolver.releasePersistableUriPermission(grant.uri, flags) }
        }
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
    ModalBottomSheet(onDismissRequest = { DownloadShelf.remove(app.id, download) }, sheetState = sheet, dragHandle = { SheetDragHandle() }) {
        DownloadSheetContent(
            name = download.file.name,
            size = size,
            canOpen = canOpen,
            onSave = {
                done {
                    SaveDocument.launch(context, download.file, model)
                }
            },
            // The Sharesheet and Open take the person to another app, so they are leaving. Each
            // runs in a task of its own, never under Ovenlight's card, where Recents would bring
            // the file back without the unlock; the chooser closes as the person leaves it.
            onShare = {
                done {
                    val send = Intent(Intent.ACTION_SEND).setType(type).putExtra(Intent.EXTRA_STREAM, uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
                    send.clipData = ClipData.newRawUri(download.file.name, uri)
                    context.startActivity(Intent.createChooser(send, null).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                }
            },
            onOpen = {
                val openAs = download.type ?: return@DownloadSheetContent
                done {
                    // Whatever opened it may be gone since the sheet looked.
                    context.startOrFalse(open(uri, openAs))
                }
            },
        )
    }
}

/**
 * Save, through the system's create-document picker. Registered on the activity outside
 * composition, so the picker's answer reaches the file it was opened for even after the sheet
 * has gone, or the lock came meanwhile; then the file is written after the unlock.
 */
private object SaveDocument {
    fun launch(context: Context, file: File, model: AppModel) {
        val activity = context.componentActivity() ?: return
        val failed = activity.getString(R.string.download_save_failed, file.name)
        // The type its name says, which the picker leaves the name alone for: a type sniffed from
        // the bytes would turn report.csv into report.csv.txt.
        val type = MimeTypeMap.getSingleton().getMimeTypeFromExtension(file.extension.lowercase()) ?: DownloadFiles.ANY
        // The picker hands its answer back: an excursion, not leaving.
        model.lifecycle.beginExcursion()
        activity.launchOnce(ActivityResultContracts.CreateDocument(type), file.name, failed = model.lifecycle::excursionEnded) { target ->
            model.lifecycle.excursionEnded()
            target ?: return@launchOnce
            // The picker's grant ends with this activity, which may go before the unlock; a
            // persisted one lasts until the file is written.
            val resolver = activity.applicationContext.contentResolver
            val write = Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            runCatching { resolver.takePersistableUriPermission(target, write) }
            model.scope.launch {
                afterReturnAndUnlock(activity, model.lock.isLocked)
                val saved = withContext(Dispatchers.IO) {
                    runCatching { resolver.openOutputStream(target)?.use { out -> file.inputStream().use { it.copyTo(out) } } != null }.getOrDefault(false)
                }
                runCatching { resolver.releasePersistableUriPermission(target, write) }
                if (!saved) Toast.makeText(activity.applicationContext, failed, Toast.LENGTH_LONG).show()
            }
        }
    }
}

private fun open(uri: Uri, type: String) =
    Intent(Intent.ACTION_VIEW).setDataAndType(uri, type).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)

@Composable
internal fun DownloadSheetContent(name: String, size: String, canOpen: Boolean, onSave: () -> Unit, onShare: () -> Unit, onOpen: () -> Unit) {
    Column(Modifier.navigationBarsPadding().padding(bottom = 8.dp)) {
        ListItem(
            // One line, shortened in the middle, so the real extension always shows.
            headlineContent = { Text(name, maxLines = 1, overflow = TextOverflow.MiddleEllipsis, style = MaterialTheme.typography.titleMedium) },
            supportingContent = { Text(size) },
            leadingContent = { Icon(painterResource(R.drawable.ic_description), contentDescription = null) },
            colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
        )
        HorizontalDivider()
        SheetRow(R.drawable.ic_download, stringResource(R.string.save), onSave)
        SheetRow(R.drawable.ic_share, stringResource(R.string.download_share), onShare)
        if (canOpen) SheetRow(R.drawable.ic_open_in_new, stringResource(R.string.open), onOpen)
    }
}
