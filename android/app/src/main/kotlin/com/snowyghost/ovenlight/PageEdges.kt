package com.snowyghost.ovenlight

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.webkit.ConsoleMessage
import android.webkit.GeolocationPermissions
import android.webkit.JsPromptResult
import android.webkit.JsResult
import android.webkit.MimeTypeMap
import android.webkit.PermissionRequest
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalActivity
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContract
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.displayCutoutPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.core.net.toUri
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import java.io.File
import java.io.IOException
import java.net.URI
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Where an app's page meets the rest of the phone (the navigation and UI-delegate halves of
 * iOS's WebCoordinator): links that leave the app, other apps, a POST to another site, the
 * page's alert, confirm and prompt, its file input, fullscreen video and geolocation. Nothing
 * leaves while the page is off screen or Ovenlight is locked, since a page's script can fake a
 * tap and its dialog would show under the app's name over something else. Main thread only.
 */
class PageEdges(
    private val app: WebApp,
    private val view: WebView,
    /** The page's context, whose base is the activity while the page is on screen. */
    private val context: Context,
    private val scope: CoroutineScope,
    private val isLocked: StateFlow<Boolean>,
    private val lifecycle: AppLifecycle,
    private val isShown: () -> Boolean,
    /** Loads the app's start page again, through the same checks as its first load. */
    private val home: () -> Unit,
) {
    val appName: String get() = app.name

    /** A dialog the page or Ovenlight shows over the page, one at a time. */
    sealed class Dialog {
        /** Taken away unanswered: answers with its fallback, unless it was answered. */
        abstract fun dropped()

        /** A page's alert, confirm or prompt; past a few in a row it offers to stop them ([quietDialogs]). */
        sealed class FromPage(val message: String, val offersQuiet: Boolean) : Dialog()

        class Alert(message: String, offersQuiet: Boolean, val answer: DialogAnswer<Unit>) : FromPage(message, offersQuiet) {
            override fun dropped() = answer.dropped()
        }

        class Confirm(message: String, offersQuiet: Boolean, val answer: DialogAnswer<Boolean>) : FromPage(message, offersQuiet) {
            override fun dropped() = answer.dropped()
        }

        class Prompt(message: String, offersQuiet: Boolean, val defaultText: String, val answer: DialogAnswer<String?>) : FromPage(message, offersQuiet) {
            override fun dropped() = answer.dropped()
        }

        /** An image input's sheet: take a photo, choose one, or choose a file when [files]. */
        class ChooseSource(val files: Boolean, val answer: DialogAnswer<Source?>) : Dialog() {
            override fun dropped() = answer.dropped()
        }

        /** "Open in another app?", naming the link as [NavigationPolicy.target] cuts it. */
        class OpenElsewhere(val target: String, val intent: Intent) : Dialog() {
            override fun dropped() = Unit
        }
    }

    private val mutableDialog = MutableStateFlow<Dialog?>(null)
    val dialog: StateFlow<Dialog?> = mutableDialog

    /** A video the page put in full screen, drawn above the app until Back or the page ends it. */
    class Fullscreen(val view: View, val callback: WebChromeClient.CustomViewCallback)

    private val mutableFullscreen = MutableStateFlow<Fullscreen?>(null)
    val fullscreen: StateFlow<Fullscreen?> = mutableFullscreen

    /** Answers the page's camera and microphone requests (PageCapture); the page gets none without it. */
    var capture: PageCapture? = null

    val chrome: WebChromeClient = Chrome()

    /**
     * The page is on screen, unlocked, and Ovenlight is in front: its script keeps running
     * while Ovenlight is away (in a picker, its own Custom Tab or another app), and the lock
     * settles only on the return.
     */
    private val mayAct: Boolean
        get() = isShown() && !isLocked.value && activity()?.lifecycle?.currentState?.isAtLeast(Lifecycle.State.RESUMED) == true

    // Navigation

    /** The page's client's shouldOverrideUrlLoading: true stops the navigation. */
    fun shouldOverrideUrlLoading(request: WebResourceRequest): Boolean {
        val url = NavigationPolicy.pageURI(request.url.toString()) ?: return true
        return when (val decision = NavigationPolicy.decide(url, app.host, request.isForMainFrame, request.hasGesture())) {
            NavigationPolicy.Decision.Allow -> false
            NavigationPolicy.Decision.Refuse -> {
                OvenlightLog.i("${app.host}: refused a ${url.scheme ?: "schemeless"} link")
                true
            }
            is NavigationPolicy.Decision.OpenExternally -> {
                openExternally(decision.url)
                true
            }
        }
    }

    /** A main-frame address as the policy judges one the page went to by itself; one that can't be read is refused. */
    private fun judge(url: String): NavigationPolicy.Decision =
        NavigationPolicy.pageURI(url)?.let { NavigationPolicy.decide(it, app.host, isMainFrame = true, tapped = false) } ?: NavigationPolicy.Decision.Refuse

    /** What the view shows (not what a callback names) is an app's own page. */
    private fun showsAppPage(): Boolean = view.url?.let(::judge) == NavigationPolicy.Decision.Allow

    /** The page's client's onPageStarted, which comes as a main-frame navigation starts. */
    fun pageStarted(url: String?) = skipped(url)

    /** The page's client's onPageFinished, which comes once one has committed, error pages included. */
    fun pageFinished(url: String?) {
        skipped(url)
        val back = returning ?: return
        // The way back's own page, which a callback names only once it has finished.
        if (url == null || judge(url) != NavigationPolicy.Decision.Allow || !showsAppPage()) return
        returning = null
        // Back never returns to the page that was stopped.
        if (back.home) view.clearHistory()
    }

    /**
     * The address a stopped navigation opened outside, and when: one navigation reaches here
     * from more than one callback, and opens once.
     */
    private var stopped: Pair<URI, Long>? = null

    /** The view is on its way back to the app's page from [target], by a step back or by loading it again ([home]). */
    private class Return(val target: String, val home: Boolean, val at: Long)

    private var returning: Return? = null
    private var returnCheck: Job? = null

    /** When each recent way back began, so a page that keeps going elsewhere ends on a blank page, not in a loop. */
    private val returns = ArrayDeque<Long>()

    /**
     * shouldOverrideUrlLoading never sees a POST, nor a page's own move to a blob: URL, so a
     * POST to another site, or a blob of another origin, gets this far. WebView reports one
     * as it starts, as it fails and as its page commits, in no fixed order. Each time it
     * stops, the view goes back if it shows that page by then, and the address opens outside
     * once, as iOS does, though here the request has already gone.
     */
    private fun skipped(url: String?) {
        url ?: return
        val decision = judge(url)
        if (decision == NavigationPolicy.Decision.Allow) return
        val now = SystemClock.elapsedRealtime()
        // While the way back loads, WebView may still name the stopped address, and stopping
        // then would stop the way back. The end of that window checks again.
        val back = returning
        if (back != null && back.target == url && now - back.at < RETURN_MILLIS) return
        OvenlightLog.i("${app.host}: stopped a page that skipped the navigation policy")
        view.stopLoading()
        if (!showsAppPage()) goBack(url, now)
        val last = stopped
        if (decision is NavigationPolicy.Decision.OpenExternally && (last == null || last.first != decision.url || now - last.second > RETURN_MILLIS)) {
            stopped = decision.url to now
            openExternally(decision.url)
        }
    }

    private fun goBack(target: String, now: Long) {
        returns.addLast(now)
        while (now - returns.first() > LOOP_MILLIS) returns.removeFirst()
        returnCheck?.cancel()
        if (returns.size > MAX_RETURNS) {
            OvenlightLog.i("${app.host}: the page keeps leaving the app; leaving it blank")
            returning = null
            view.loadUrl("about:blank")
            return
        }
        // By index, not goBack, which skips pages the person never touched (Chromium's history
        // intervention), as a script's POST leaves the one before it. Where WebView won't step
        // back at all, the app starts again from its own page.
        val step = view.canGoBackOrForward(-1)
        returning = Return(target, home = !step, at = now)
        if (step) view.goBackOrForward(-1) else home()
        returnCheck = scope.launch {
            delay(RETURN_MILLIS)
            returning = null
            view.url?.let(::skipped)
        }
    }

    /** Web pages open in a Custom Tab, an excursion; the dialer opens at once; every other app is asked about first. */
    fun openExternally(url: URI) {
        if (!mayAct) return
        val scheme = url.scheme?.lowercase()
        if (url.toString().length > NavigationPolicy.maxHandOffLength) {
            OvenlightLog.i("${app.host}: refused a $scheme link too long to hand on")
            return
        }
        if (scheme == "http" || scheme == "https") {
            val page = if (NavigationPolicy.isOvenlightLink(url)) NavigationPolicy.joinPage(url) else url
            CustomTabs.open(context, page, lifecycle)
            return
        }
        val intent = handOff(url) ?: run {
            OvenlightLog.i("${app.host}: refused a $scheme link to another app")
            return
        }
        if (NavigationPolicy.asksBeforeOpening(url)) show(Dialog.OpenElsewhere(NavigationPolicy.target(url), intent)) else start(intent)
    }

    /** The intent that hands [url] to another app, or null when it would reach Ovenlight itself. */
    private fun handOff(url: URI): Intent? {
        val intent = when (url.scheme?.lowercase()) {
            "tel" -> Intent(Intent.ACTION_DIAL, url.toString().toUri())
            in NavigationPolicy.composes -> Intent(Intent.ACTION_SENDTO, url.toString().toUri())
            "intent" -> pageIntent(url.toString(), context.packageName) ?: return null
            else -> Intent(Intent.ACTION_VIEW, url.toString().toUri()).addCategory(Intent.CATEGORY_BROWSABLE)
        }
        // Unanswerable counts as Ovenlight's own: refused.
        val own = systemCall("look up a link's app", true) {
            context.packageManager.queryIntentActivities(intent, 0).any { it.activityInfo.packageName == context.packageName }
        }
        return if (own) null else intent
    }

    /** Another app opens only from the page on screen, unlocked; it takes the person out of Ovenlight. */
    private fun start(intent: Intent) {
        if (!mayAct) return
        systemCall("open another app", Unit) { context.startActivity(intent) }
    }

    // Dialogs

    private fun show(dialog: Dialog) {
        if (!mayAct) return dialog.dropped()
        mutableDialog.value?.dropped()
        mutableDialog.value = dialog
    }

    /** The page's dialogs in a row, each within [DIALOG_RUN_GAP_MILLIS] of the last one closing. */
    private var dialogRun = 0
    private var lastDialogClosed: Long? = null

    /**
     * The person stopped this page's dialogs: each is answered with its fallback at once,
     * until they reload the app or open it again ([allowDialogsAgain]). Nothing the page
     * does itself (a reload of its own) lifts it.
     */
    private var quiet = false

    /**
     * A page's alert, confirm or prompt. Past [DIALOG_LOOP_COUNT] in a row, however long each
     * was read, each offers "Don't let this app show more dialogs", as Chrome does, so a page
     * that asks in a loop can't trap the person.
     */
    private fun fromPage(make: (offersQuiet: Boolean) -> Dialog.FromPage) {
        val now = SystemClock.elapsedRealtime()
        val closed = lastDialogClosed
        dialogRun = if (closed != null && now - closed < DIALOG_RUN_GAP_MILLIS) dialogRun + 1 else 1
        val dialog = make(dialogRun > DIALOG_LOOP_COUNT)
        if (quiet) return dialog.dropped()
        show(dialog)
    }

    fun quietDialogs() {
        quiet = true
    }

    /** The person reloaded the app, or it came back on screen (opened again, or unlocked): its page may show dialogs again. */
    fun allowDialogsAgain() {
        quiet = false
    }

    /** The dialog closed: answered, dismissed or let go, it answers once. */
    fun dismiss(dialog: Dialog) {
        if (dialog is Dialog.FromPage) lastDialogClosed = SystemClock.elapsedRealtime()
        if (mutableDialog.value === dialog) mutableDialog.value = null
        dialog.dropped()
    }

    /** "Open" in "Open in another app?". */
    fun open(dialog: Dialog.OpenElsewhere) {
        dismiss(dialog)
        start(dialog.intent)
    }

    /** The page went: as [takenAway], and no way back is checked any more. */
    fun destroy() {
        returnCheck?.cancel()
        takenAway()
    }

    /** The page left the screen (closed, covered, locked) or went: its dialog is answered and full screen ends. A file pick under way stays. */
    fun takenAway() {
        mutableDialog.value?.let(::dismiss)
        exitFullscreen()
    }

    fun exitFullscreen() {
        val screen = mutableFullscreen.value ?: return
        mutableFullscreen.value = null
        screen.callback.onCustomViewHidden()
    }

    // The file input

    /**
     * The photo picker for images and video, documents otherwise, and the camera when the
     * input asks for capture. Each is an excursion, and its result reaches the page once
     * Ovenlight is in front and unlocked. The page's callback is answered exactly once.
     */
    private fun chooseFiles(callback: ValueCallback<Array<Uri>>, params: WebChromeClient.FileChooserParams) {
        val answer = DialogAnswer<Array<Uri>?>(null) { callback.onReceiveValue(it) }
        val activity = activity()
        if (!mayAct || activity == null) return answer.dropped()
        val multiple = params.mode == WebChromeClient.FileChooserParams.MODE_OPEN_MULTIPLE
        val mime = { ext: String -> MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) }
        when (val kind = FileChoice.kind(params.acceptTypes?.toList().orEmpty(), params.isCaptureEnabled, mime)) {
            is FileChoice.Kind.Camera -> capture(activity, kind.video, answer)
            is FileChoice.Kind.Media -> photos(activity, kind, multiple, answer)
            is FileChoice.Kind.Documents -> documents(activity, kind, multiple, answer)
            is FileChoice.Kind.Choice -> show(
                Dialog.ChooseSource(files = kind.documents != null, DialogAnswer<Source?>(null) { source ->
                    when (source) {
                        null -> answer.dropped()
                        Source.Camera -> capture(activity, video = false, answer)
                        Source.Photos -> photos(activity, kind.media, multiple, answer)
                        Source.Files -> kind.documents?.let { documents(activity, it, multiple, answer) } ?: answer.dropped()
                    }
                }),
            )
        }
    }

    /** Where an image input's file comes from, in Ovenlight's sheet. */
    enum class Source { Camera, Photos, Files }

    private fun photos(activity: ComponentActivity, media: FileChoice.Kind.Media, multiple: Boolean, answer: DialogAnswer<Array<Uri>?>) {
        val type = when {
            media.single != null -> ActivityResultContracts.PickVisualMedia.SingleMimeType(media.single)
            media.images && media.videos -> ActivityResultContracts.PickVisualMedia.ImageAndVideo
            media.videos -> ActivityResultContracts.PickVisualMedia.VideoOnly
            else -> ActivityResultContracts.PickVisualMedia.ImageOnly
        }
        val request = PickVisualMediaRequest.Builder().setMediaType(type).build()
        if (multiple) {
            pick(activity, ActivityResultContracts.PickMultipleVisualMedia(), request, answer) { it.toTypedArray().ifEmpty { null } }
        } else {
            pick(activity, ActivityResultContracts.PickVisualMedia(), request, answer) { it?.let { uri -> arrayOf(uri) } }
        }
    }

    private fun documents(activity: ComponentActivity, documents: FileChoice.Kind.Documents, multiple: Boolean, answer: DialogAnswer<Array<Uri>?>) {
        val types = documents.types.toTypedArray()
        if (multiple) {
            pick(activity, ActivityResultContracts.OpenMultipleDocuments(), types, answer) { it.toTypedArray().ifEmpty { null } }
        } else {
            pick(activity, ActivityResultContracts.OpenDocument(), types, answer) { it?.let { uri -> arrayOf(uri) } }
        }
    }

    /**
     * Ovenlight declares the camera permission (for pages and the invite scanner), so the
     * camera app takes a picture for it only once that is granted. The permission's own
     * prompt is drawn over Ovenlight and isn't an excursion.
     */
    private fun capture(activity: ComponentActivity, video: Boolean, answer: DialogAnswer<Array<Uri>?>) {
        val take = {
            val file = CaptureFileProvider.newFile(activity, if (video) "mp4" else "jpg")
            val uri = FileProvider.getUriForFile(activity, CaptureFileProvider.authority(activity), file)
            val contract = if (video) ActivityResultContracts.CaptureVideo() else ActivityResultContracts.TakePicture()
            pick(activity, contract, uri, answer) { taken -> if (taken && file.length() > 0) arrayOf(uri) else null.also { file.delete() } }
        }
        if (ContextCompat.checkSelfPermission(activity, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) return take()
        startForResult(activity, ActivityResultContracts.RequestPermission(), Manifest.permission.CAMERA, excursion = false, failed = answer::dropped) { granted ->
            // The person just answered Ovenlight's own question; the result can come before the resume.
            if (granted && !isLocked.value) take() else answer.dropped()
        }
    }

    private fun <I, O> pick(
        activity: ComponentActivity, contract: ActivityResultContract<I, O>, input: I,
        answer: DialogAnswer<Array<Uri>?>, files: (O) -> Array<Uri>?,
    ) = startForResult(activity, contract, input, excursion = true, failed = answer::dropped) { result ->
        lifecycle.excursionEnded()
        val picked = files(result) ?: return@startForResult answer.dropped()
        // After the unlock: a return from a picker may lock first.
        scope.launch {
            val state = activity.lifecycle.currentStateFlow.first { it.isAtLeast(Lifecycle.State.STARTED) || it == Lifecycle.State.DESTROYED }
            if (state != Lifecycle.State.DESTROYED) isLocked.first { !it }
            withContext(Dispatchers.IO) { picked.forEach { ready(activity, it) } }
            answer(picked)
        }
    }

    /**
     * Opens a picked file until it opens, for up to two seconds. Right after the photo picker
     * returns, the media provider can fail the first open of its URI (EFAULT, while it
     * prepares the picker's path), and WebView, which doesn't try again, then gives the page
     * no file at all.
     */
    private suspend fun ready(activity: ComponentActivity, uri: Uri) {
        repeat(10) {
            try {
                activity.contentResolver.openAssetFileDescriptor(uri, "r")?.close()
                return
            } catch (_: IOException) {
                delay(200)
            } catch (_: SecurityException) {
                return
            }
        }
    }

    /**
     * Registered on the activity's own registry without a lifecycle, so the result arrives
     * even while the lock is up or the page is off screen, and unregistered once it has. An
     * activity made again meanwhile (a change it doesn't handle itself) never gets this
     * result, so its end answers the page with nothing, which lets the page ask again.
     */
    private fun <I, O> startForResult(
        activity: ComponentActivity, contract: ActivityResultContract<I, O>, input: I,
        excursion: Boolean, failed: () -> Unit, done: (O) -> Unit,
    ) {
        var launcher: ActivityResultLauncher<I>? = null
        var ended: LifecycleEventObserver? = null
        val finish = {
            launcher?.unregister()
            ended?.let(activity.lifecycle::removeObserver)
        }
        launcher = activity.activityResultRegistry.register("page-${UUID.randomUUID()}", contract) { result ->
            finish()
            done(result)
        }
        ended = LifecycleEventObserver { _, event ->
            if (event != Lifecycle.Event.ON_DESTROY) return@LifecycleEventObserver
            finish()
            if (excursion) lifecycle.excursionEnded()
            failed()
        }
        activity.lifecycle.addObserver(ended)
        if (excursion) lifecycle.beginExcursion()
        try {
            launcher.launch(input)
        } catch (_: ActivityNotFoundException) {
            finish()
            if (excursion) lifecycle.excursionEnded()
            failed()
        }
    }

    private fun activity(): ComponentActivity? =
        generateSequence(context) { (it as? ContextWrapper)?.baseContext }.firstNotNullOfOrNull { it as? ComponentActivity }

    private inner class Chrome : WebChromeClient() {
        // Unanswered, WebView writes every console message, with its page's address, to the
        // system log. Release builds answer it with nothing.
        override fun onConsoleMessage(consoleMessage: ConsoleMessage): Boolean = !BuildConfig.DEBUG

        override fun onJsAlert(view: WebView, url: String?, message: String?, result: JsResult): Boolean {
            fromPage { quiet -> Dialog.Alert(cut(message), quiet, DialogAnswer(Unit) { result.confirm() }) }
            return true
        }

        override fun onJsConfirm(view: WebView, url: String?, message: String?, result: JsResult): Boolean {
            fromPage { quiet -> Dialog.Confirm(cut(message), quiet, DialogAnswer(false) { if (it) result.confirm() else result.cancel() }) }
            return true
        }

        override fun onJsPrompt(view: WebView, url: String?, message: String?, defaultValue: String?, result: JsPromptResult): Boolean {
            fromPage { quiet ->
                Dialog.Prompt(cut(message), quiet, cut(defaultValue), DialogAnswer<String?>(null) { if (it != null) result.confirm(it) else result.cancel() })
            }
            return true
        }

        /** The page leaves at once, as in WKWebView, which shows no such question. */
        override fun onJsBeforeUnload(view: WebView, url: String?, message: String?, result: JsResult): Boolean {
            result.confirm()
            return true
        }

        override fun onShowFileChooser(view: WebView, callback: ValueCallback<Array<Uri>>, params: FileChooserParams): Boolean {
            chooseFiles(callback, params)
            return true
        }

        override fun onShowCustomView(view: View, callback: CustomViewCallback) {
            if (!mayAct) return callback.onCustomViewHidden()
            exitFullscreen()
            mutableFullscreen.value = Fullscreen(view, callback)
        }

        override fun onHideCustomView() {
            mutableFullscreen.value = null
        }

        /** Ovenlight never gives a page the phone's location, as on iOS (docs/building-apps.md). */
        override fun onPermissionRequest(request: PermissionRequest) {
            capture?.onPermissionRequest(request) ?: request.deny()
        }

        override fun onPermissionRequestCanceled(request: PermissionRequest) {
            capture?.onPermissionRequestCanceled(request)
        }

        override fun onGeolocationPermissionsShowPrompt(origin: String?, callback: GeolocationPermissions.Callback?) {
            callback?.invoke(origin, false, false)
        }
    }

    companion object {
        /** A page's dialog text past this many characters is cut: a page can pass megabytes. */
        private const val MAX_DIALOG_TEXT = 4_000

        /** Chrome's rule: past three dialogs in a row, the page's dialogs can be stopped. */
        private const val DIALOG_LOOP_COUNT = 3

        /** A page's dialog that comes this soon after the last one closed is the next in a row. */
        private const val DIALOG_RUN_GAP_MILLIS = 1_000L

        /** A page's text as its dialog shows it, cut at [MAX_DIALOG_TEXT] without splitting a character. */
        internal fun cut(text: String?): String {
            val value = text.orEmpty()
            if (value.length <= MAX_DIALOG_TEXT) return value
            val end = if (value[MAX_DIALOG_TEXT - 1].isHighSurrogate()) MAX_DIALOG_TEXT - 1 else MAX_DIALOG_TEXT
            return value.substring(0, end) + "\u2026"
        }

        /** How long the way back from a stopped page may take before a new one is stopped again. */
        private const val RETURN_MILLIS = 3_000L

        /** More ways back than this within [LOOP_MILLIS] and the page is left blank. */
        private const val MAX_RETURNS = 3
        private const val LOOP_MILLIS = 30_000L

        /**
         * An `intent:` link as another app may receive it, or null. Parsed without
         * URI_ALLOW_UNSAFE; only a view of something goes ahead; never one for Ovenlight's own
         * package, nor one carrying the phone's files or Ovenlight's own link. Its component
         * and selector go, so the system picks what handles it, as a browser does, and so do
         * its flags and every extra that names an address (`S.browser_fallback_url` among
         * them, which is never followed).
         */
        fun pageIntent(text: String, ownPackage: String): Intent? {
            // A malformed link throws URISyntaxException, or a number format error for a bad extra.
            val intent = runCatching { Intent.parseUri(text, Intent.URI_INTENT_SCHEME) }.getOrNull() ?: return null
            if (intent.action != Intent.ACTION_VIEW || intent.`package`.equals(ownPackage, ignoreCase = true)) return null
            val data = intent.data?.let { uri(it.toString()) ?: return null }
            if (!NavigationPolicy.mayCarry(data)) return null
            intent.component = null
            intent.selector = null
            intent.flags = 0
            intent.clipData = null
            intent.removeExtra("browser_fallback_url")
            intent.extras?.let { extras ->
                for (key in extras.keySet().toList()) {
                    @Suppress("DEPRECATION") // The extras an intent: link can carry are plain values.
                    val value = extras.get(key)
                    if (value is CharSequence && NavigationPolicy.carriesURI(value.toString())) intent.removeExtra(key)
                }
            }
            intent.addCategory(Intent.CATEGORY_BROWSABLE)
            return intent
        }
    }
}

/** Which picker a page's file input opens, from its `accept` and `capture`. */
object FileChoice {
    sealed class Kind {
        data class Camera(val video: Boolean) : Kind()

        /** The photo picker: [single] when the input names exactly one type. */
        data class Media(val images: Boolean, val videos: Boolean, val single: String?) : Kind()
        data class Documents(val types: List<String>) : Kind()

        /**
         * An image input without `capture`: Ovenlight's sheet offers the camera and the photo
         * picker, as WebKit's menu does, and [documents] too when the input takes more than
         * photos and videos.
         */
        data class Choice(val media: Media, val documents: Documents?) : Kind()
    }

    /**
     * [accept] is WebView's list (types, wildcards and `.ext` names); an empty one, or one with
     * a name [mimeType] doesn't know, takes any file. The camera goes only where a photo or
     * video would do.
     */
    fun kind(accept: List<String>, capture: Boolean, mimeType: (String) -> String?): Kind {
        val tokens = accept.flatMap { it.split(',') }.map { it.trimmingWhitespace().lowercase() }.filter { it.isNotEmpty() }
        val types = tokens.map { if (it.startsWith(".")) mimeType(it.drop(1)) else it }
        val any = types.isEmpty() || types.any { it == null || it == "*/*" }
        val known = types.filterNotNull()
        val visual = known.filter { it.startsWith("image/") || it.startsWith("video/") }
        val onlyMedia = !any && visual.size == known.size
        if (capture && (any || onlyMedia)) return Kind.Camera(video = onlyMedia && known.all { it.startsWith("video/") })
        val media = Kind.Media(
            images = visual.any { it.startsWith("image/") }, videos = visual.any { it.startsWith("video/") },
            single = visual.distinct().singleOrNull()?.takeUnless { it.endsWith("/*") },
        )
        val documents = Kind.Documents(if (any) listOf("*/*") else known.distinct())
        return when {
            media.images -> Kind.Choice(media, if (onlyMedia) null else documents)
            onlyMedia -> media
            else -> documents
        }
    }
}

/** Where the camera writes a photo or video for a page, in `cacheDir/Captures`. */
class CaptureFileProvider : FileProvider() {
    companion object {
        fun authority(context: Context) = "${context.packageName}.captures"

        private fun folder(context: Context) = File(context.cacheDir, "Captures")

        fun newFile(context: Context, extension: String): File =
            File.createTempFile("capture-", ".$extension", folder(context).apply { mkdirs() })

        /** Clears the last process's captures, which no page can still hold, as the download shelf does. */
        fun emptyAtLaunch(context: Context) {
            folder(context).listFiles()?.forEach(File::delete)
        }
    }
}

/**
 * The page's dialogs and its fullscreen video, over the app. Goes last in the app's screen, so
 * full screen covers the chrome and its Back comes first. Leaving composition (the app
 * closing, the lock) answers any dialog and ends full screen.
 */
@Composable
fun PageEdgesLayer(edges: PageEdges) {
    val dialog by edges.dialog.collectAsState()
    val fullscreen by edges.fullscreen.collectAsState()
    DisposableEffect(edges) { onDispose(edges::takenAway) }
    fullscreen?.let { key(it) { FullscreenView(it, edges.appName, edges::exitFullscreen) } }
    dialog?.let { key(it) { PageDialog(edges, it) } }
}

/**
 * The page's full screen, with the system bars hidden. A page could draw anything there (a
 * fake lock or system screen), so for its first seconds a notice names the app and the way
 * out, as Chrome's does. Back exits, and so does a swipe that brings the bars back.
 */
@Composable
private fun FullscreenView(screen: PageEdges.Fullscreen, appName: String, exit: () -> Unit) {
    val window = LocalActivity.current?.window
    val currentExit by rememberUpdatedState(exit)
    DisposableEffect(window) {
        val decor = window?.decorView
        val bars = window?.let { WindowCompat.getInsetsController(it, it.decorView) }
        // Not transient: bars a swipe brings back stay, and that ends full screen.
        bars?.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_DEFAULT
        bars?.hide(WindowInsetsCompat.Type.systemBars())
        // Read from the window itself, which knows whether the bars show however they came
        // back. Not systemBars(), whose caption bar never shows on a phone.
        var barsWentAway = false
        val check = object : Runnable {
            override fun run() {
                val visible = decor?.let(ViewCompat::getRootWindowInsets)?.let {
                    it.isVisible(WindowInsetsCompat.Type.statusBars()) || it.isVisible(WindowInsetsCompat.Type.navigationBars())
                }
                if (visible == false) barsWentAway = true else if (visible == true && barsWentAway) return currentExit()
                decor?.postDelayed(this, BARS_CHECK_MILLIS)
            }
        }
        decor?.post(check)
        onDispose {
            decor?.removeCallbacks(check)
            bars?.show(WindowInsetsCompat.Type.systemBars())
        }
    }
    BackHandler(onBack = exit)
    var notice by remember { mutableStateOf(true) }
    LaunchedEffect(Unit) {
        delay(FULLSCREEN_NOTICE_MILLIS)
        notice = false
    }
    Box(Modifier.fillMaxSize().background(Color.Black)) {
        AndroidView(
            factory = { screen.view.also { (it.parent as? ViewGroup)?.removeView(it) } },
            modifier = Modifier.fillMaxSize(),
        )
        AnimatedVisibility(
            notice, Modifier.align(Alignment.TopCenter).displayCutoutPadding().padding(top = 24.dp, start = 16.dp, end = 16.dp),
            enter = fadeIn(), exit = fadeOut(),
        ) {
            Column(
                Modifier.background(Color.Black.copy(alpha = 0.8f), RoundedCornerShape(16.dp)).padding(horizontal = 20.dp, vertical = 12.dp)
                    .semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite },
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(stringResource(R.string.page_fullscreen_notice, appName), color = Color.White, style = MaterialTheme.typography.titleSmall, textAlign = TextAlign.Center)
                Text(stringResource(R.string.page_fullscreen_exit), color = Color.White, style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center)
            }
        }
    }
}

/** How often full screen checks whether a swipe brought the bars back. */
private const val BARS_CHECK_MILLIS = 250L

/** How long full screen's notice shows, as Chrome's does. */
private const val FULLSCREEN_NOTICE_MILLIS = 4_000L

@Composable
private fun PageDialog(edges: PageEdges, dialog: PageEdges.Dialog) {
    // Whatever takes it away answers it with its fallback, once.
    DisposableEffect(dialog) { onDispose(dialog::dropped) }
    // Closed any way the person closes it, a page's dialog stops the rest if they chose so.
    var stopMore by remember(dialog) { mutableStateOf(false) }
    val dismiss = {
        if (stopMore) edges.quietDialogs()
        edges.dismiss(dialog)
    }
    val title: @Composable () -> Unit = { Text(edges.appName) }
    val quiet: @Composable () -> Unit = { if ((dialog as? PageEdges.Dialog.FromPage)?.offersQuiet == true) QuietChoice(stopMore) { stopMore = it } }
    when (dialog) {
        is PageEdges.Dialog.Alert -> AlertDialog(
            onDismissRequest = dismiss, title = title, text = { PageMessage(dialog.message, quiet) },
            confirmButton = { TextButton(onClick = { dialog.answer(Unit); dismiss() }) { Text(stringResource(R.string.page_ok)) } },
        )
        is PageEdges.Dialog.Confirm -> AlertDialog(
            onDismissRequest = dismiss, title = title, text = { PageMessage(dialog.message, quiet) },
            confirmButton = { TextButton(onClick = { dialog.answer(true); dismiss() }) { Text(stringResource(R.string.page_ok)) } },
            dismissButton = { TextButton(onClick = { dialog.answer(false); dismiss() }) { Text(stringResource(R.string.page_cancel)) } },
        )
        is PageEdges.Dialog.Prompt -> {
            var text by rememberSaveable(dialog) { mutableStateOf(dialog.defaultText) }
            AlertDialog(
                onDismissRequest = dismiss, title = title,
                text = {
                    PageMessage(dialog.message) {
                        OutlinedTextField(
                            value = text, onValueChange = { text = it }, singleLine = true,
                            label = { Text(stringResource(R.string.page_prompt_field)) },
                            modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                        )
                        quiet()
                    }
                },
                confirmButton = { TextButton(onClick = { dialog.answer(text); dismiss() }) { Text(stringResource(R.string.page_ok)) } },
                dismissButton = { TextButton(onClick = { dialog.answer(null); dismiss() }) { Text(stringResource(R.string.page_cancel)) } },
            )
        }
        is PageEdges.Dialog.ChooseSource -> SourceSheet(dialog, dismiss)
        is PageEdges.Dialog.OpenElsewhere -> AlertDialog(
            onDismissRequest = dismiss,
            title = { Text(stringResource(R.string.page_open_elsewhere_title)) },
            text = {
                GuardedFromOverlays()
                Text(stringResource(R.string.page_open_elsewhere_body, edges.appName, dialog.target))
            },
            // Not at once: the page's own "tap twice here" can't land its second tap on Open.
            confirmButton = {
                val shownAt = remember(dialog) { SystemClock.uptimeMillis() }
                TextButton(onClick = { if (SystemClock.uptimeMillis() - shownAt >= PROMPT_DELAY_MILLIS) edges.open(dialog) }) {
                    Text(stringResource(R.string.page_open))
                }
            },
            dismissButton = { TextButton(onClick = dismiss) { Text(stringResource(R.string.page_cancel)) } },
        )
    }
}

/** An image input's choice, as a small sheet over the page; a choice or a swipe away closes it. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SourceSheet(dialog: PageEdges.Dialog.ChooseSource, dismiss: () -> Unit) {
    ModalBottomSheet(onDismissRequest = dismiss) {
        val choose = { source: PageEdges.Source ->
            dialog.answer(source)
            dismiss()
        }
        SourceRow(R.drawable.ic_photo_camera, R.string.page_take_photo) { choose(PageEdges.Source.Camera) }
        SourceRow(R.drawable.ic_photo_library, R.string.page_choose_photo) { choose(PageEdges.Source.Photos) }
        if (dialog.files) SourceRow(R.drawable.ic_description, R.string.page_choose_file) { choose(PageEdges.Source.Files) }
        Spacer(Modifier.navigationBarsPadding().padding(bottom = 16.dp))
    }
}

@Composable
private fun SourceRow(icon: Int, label: Int, onClick: () -> Unit) {
    ListItem(
        headlineContent = { Text(stringResource(label)) },
        leadingContent = { Icon(painterResource(icon), contentDescription = null) },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        modifier = Modifier.clickable(onClick = onClick),
    )
}

/** How long Ovenlight's own prompts ("Open in another app?", the capture prompt) ignore their go-ahead after they show. */
internal const val PROMPT_DELAY_MILLIS = 500L

/** A page's message, which can run long. */
@Composable
private fun PageMessage(message: String, below: @Composable () -> Unit = {}) {
    Column {
        // Scrolls in what's left once what goes below it has its room.
        if (message.isNotEmpty()) Text(message, Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()))
        below()
    }
}

/** Chrome's way out of a page's dialog loop. */
@Composable
private fun QuietChoice(checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(top = 8.dp).heightIn(min = 48.dp).toggleable(checked, role = Role.Checkbox, onValueChange = onChange),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(checked = checked, onCheckedChange = null)
        Text(stringResource(R.string.page_quiet_dialogs), Modifier.padding(start = 12.dp), style = MaterialTheme.typography.bodyMedium)
    }
}

/**
 * Ovenlight's own prompt can't be tapjacked: its window drops touches that pass through
 * another app's overlay, and on API 31 and up other apps' overlays hide while it shows.
 */
@Composable
internal fun GuardedFromOverlays() {
    val view = LocalView.current
    DisposableEffect(view) {
        val window = view.dialogWindow()
        window?.decorView?.filterTouchesWhenObscured = true
        if (Build.VERSION.SDK_INT >= 31) window?.setHideOverlayWindows(true)
        onDispose { if (Build.VERSION.SDK_INT >= 31) window?.setHideOverlayWindows(false) }
    }
}
