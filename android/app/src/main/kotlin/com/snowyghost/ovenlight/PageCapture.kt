package com.snowyghost.ovenlight

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.webkit.PermissionRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.core.content.ContextCompat
import com.snowyghost.ovenlight.MediaCapturePolicy.MediaCaptureType
import com.snowyghost.ovenlight.MediaCapturePolicy.PermissionDecision
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * One page's microphone and camera (iOS's requestMediaCapturePermissionFor, and its capture
 * stop). WebView has no prompt of its own, so Ovenlight asks, after Android's own runtime
 * permission, and remembers an answer only for the page's current document. WebView can't stop a
 * stream either: on lock and on closing to the launcher the page script stops the tracks it
 * kept, and Ovenlight asks the system whether it still records or holds a camera until it lets
 * go, for up to two seconds. If it still does, a page granted capture since its last load is
 * destroyed, since a page can capture where the script doesn't reach. Main thread only.
 */
@SuppressLint("RequiresFeature") // WebViewSetup is Ready only once it found every feature used here.
class PageCapture(
    private val app: WebApp,
    private val ownersHosts: () -> Set<String>,
    private val isLocked: () -> Boolean,
    private val isOnScreen: () -> Boolean,
    private val scope: CoroutineScope,
    /**
     * Asks Android for these runtime permissions as needed ([RuntimePermissions.request]): those it
     * refused for good, then true once Ovenlight holds them all.
     */
    private val permissions: (List<String>, (List<String>) -> Unit, (Boolean) -> Unit) -> Unit,
    /** Whether Ovenlight holds this runtime permission now, without asking. */
    private val holds: (String) -> Boolean,
    /** Whether Ovenlight still records or holds a camera ([CaptureMonitor]). */
    private val stillCapturing: () -> Boolean,
    /** Ends the page's view for good: the pool drops it. */
    private val destroy: () -> Unit,
) {
    /** Ovenlight's question, while one is open. */
    class Prompt(val type: MediaCaptureType, /** The host asking, when it isn't the app's own. */ val otherHost: String?, val answer: DialogAnswer<Boolean>)

    private val mutablePrompt = MutableStateFlow<Prompt?>(null)
    val prompt: StateFlow<Prompt?> = mutablePrompt

    /** What Android refused without asking when the page last asked, so Ovenlight says where to allow it. */
    val refused = MutableStateFlow<MediaCaptureType?>(null)

    /** The request waiting on Android's permission or Ovenlight's prompt; one at a time. */
    private var pending: PermissionRequest? = null

    /** What the person allowed in this document, by the origin that asked. */
    private val allowed = mutableMapOf<String, Set<String>>()

    /**
     * What the person refused in this document, whichever frame asked: on Ovenlight's question,
     * and on Android's while Android still refuses it (Settings can allow it, as the refused
     * notice says). A later request for any of it is refused at once, as WebKit does, so a page
     * that asks again on each refusal can't keep the question, or the notice, coming.
     */
    private val denied = mutableSetOf<String>()
    private val deniedByAndroid = mutableSetOf<String>()

    /**
     * What Android refused for good, turned off for Ovenlight, as this page asked: whatever
     * document asks for it next is refused at once until Settings allows it or the page stops,
     * since Android would refuse again without a word, and a page that reloads on each refusal
     * would only keep its permission screen opening.
     */
    private val offInSettings = mutableSetOf<String>()

    /** Reaches the page script, which stops its tracks and its frames' (see [PageBridge]). */
    private var stopTracks: (() -> Unit)? = null

    /** Granted capture since the page's last load, so it may hold a stream. */
    private var granted = false

    fun onPermissionRequest(request: PermissionRequest) {
        val resources = request.resources.filter { it == PermissionRequest.RESOURCE_AUDIO_CAPTURE || it == PermissionRequest.RESOURCE_VIDEO_CAPTURE }.toSet()
        val type = typeOf(resources) ?: return request.deny()
        val origin = request.origin
        fun decide() = MediaCapturePolicy.decide(type, origin.scheme.orEmpty(), origin.host.orEmpty(), app, ownersHosts(), isLocked(), isOnScreen())
        val decision = decide()
        if (decision == PermissionDecision.Deny || pending != null || refusedBefore(resources)) return request.deny()
        val key = origin.toString()
        // Without asking, only ever the microphone.
        val grant = if (decision == PermissionDecision.Grant) setOf(PermissionRequest.RESOURCE_AUDIO_CAPTURE) else resources
        val asked = decision == PermissionDecision.Grant || allowed[key]?.containsAll(resources) == true
        pending = request
        val refusedForGood = { denied: List<String> ->
            offInSettings += denied
            refused.value = if (Manifest.permission.CAMERA in denied) MediaCaptureType.Camera else MediaCaptureType.Microphone
        }
        permissions(androidPermissions(resources), refusedForGood) { held ->
            if (pending !== request) return@permissions
            if (decide() == PermissionDecision.Deny) return@permissions finish(request, null)
            if (!held) {
                deniedByAndroid += androidPermissions(resources)
                return@permissions finish(request, null)
            }
            if (asked) return@permissions finish(request, grant)
            mutablePrompt.value = Prompt(type, otherHost(origin.host), DialogAnswer(false) { allow ->
                mutablePrompt.value = null
                if (pending !== request) return@DialogAnswer
                when {
                    decide() == PermissionDecision.Deny -> finish(request, null)
                    allow -> {
                        allowed[key] = allowed[key].orEmpty() + resources
                        finish(request, resources)
                    }
                    else -> {
                        denied += resources
                        finish(request, null)
                    }
                }
            })
        }
    }

    private fun refusedBefore(resources: Set<String>): Boolean =
        resources.any(denied::contains) || androidPermissions(resources).any { (it in deniedByAndroid || it in offInSettings) && !holds(it) }

    fun onPermissionRequestCanceled(request: PermissionRequest) {
        if (pending !== request) return
        pending = null
        mutablePrompt.value?.answer?.dropped()
    }

    /** A frame called getUserMedia; [stop] reaches its page script (see [PageBridge]). */
    fun capturing(stop: () -> Unit) {
        stopTracks = stop
    }

    /** A new document in the main frame: what was allowed or refused, and its streams, went with the old one. */
    fun documentChanged() {
        allowed.clear()
        denied.clear()
        deniedByAndroid.clear()
        stopTracks = null
        granted = false
        dropPending()
    }

    /**
     * Ovenlight locked, or the page closed to the launcher: every track the page script kept
     * stops, and a page that may still capture is destroyed once the system says it does.
     */
    fun stop() {
        stopTracks?.invoke()
        allowed.clear()
        denied.clear()
        deniedByAndroid.clear()
        offInSettings.clear()
        refused.value = null
        dropPending()
        if (!granted) return
        scope.launch {
            // A camera takes a moment to close once its tracks stop (half a second on the
            // emulator), and the system lists it until then.
            repeat((STOP_WAIT_MILLIS / CHECK_EVERY_MILLIS).toInt()) {
                delay(CHECK_EVERY_MILLIS)
                if (isOnScreen() || !stillCapturing()) return@launch
            }
            OvenlightLog.w("${app.host}: still capturing after the stop, so its view goes")
            destroy()
        }
    }

    private fun dropPending() {
        val request = pending ?: return
        pending = null
        mutablePrompt.value?.answer?.dropped()
        request.deny()
    }

    private fun finish(request: PermissionRequest, resources: Set<String>?) {
        pending = null
        if (resources == null) return request.deny()
        granted = true
        request.grant(resources.toTypedArray())
    }

    private fun otherHost(host: String?): String? =
        host?.takeIf { Discovery.normalizedHost(it) != Discovery.normalizedHost(app.host) }

    companion object {
        const val CHECK_EVERY_MILLIS = 250L
        const val STOP_WAIT_MILLIS = 2_000L

        fun typeOf(resources: Set<String>): MediaCaptureType? {
            val audio = PermissionRequest.RESOURCE_AUDIO_CAPTURE in resources
            val video = PermissionRequest.RESOURCE_VIDEO_CAPTURE in resources
            return when {
                audio && video -> MediaCaptureType.CameraAndMicrophone
                audio -> MediaCaptureType.Microphone
                video -> MediaCaptureType.Camera
                else -> null
            }
        }

        fun androidPermissions(resources: Set<String>): List<String> = listOfNotNull(
            Manifest.permission.RECORD_AUDIO.takeIf { PermissionRequest.RESOURCE_AUDIO_CAPTURE in resources },
            Manifest.permission.CAMERA.takeIf { PermissionRequest.RESOURCE_VIDEO_CAPTURE in resources },
        )
    }
}

/**
 * Whether Ovenlight still records or holds a camera. Android lists only the calling app's own
 * recordings; a camera is unavailable while any app holds it, and while Ovenlight is in front
 * that is Ovenlight.
 */
object CaptureMonitor {
    private val unavailable = mutableSetOf<String>()
    private var watching = false

    /** Starts following the cameras, from the first grant on. Main thread only. */
    fun watch(context: Context) {
        if (watching) return
        watching = true
        context.getSystemService(CameraManager::class.java)?.registerAvailabilityCallback(object : CameraManager.AvailabilityCallback() {
            override fun onCameraAvailable(cameraId: String) {
                unavailable -= cameraId
            }

            override fun onCameraUnavailable(cameraId: String) {
                unavailable += cameraId
            }
        }, Handler(Looper.getMainLooper()))
    }

    fun capturing(context: Context): Boolean =
        context.getSystemService(AudioManager::class.java)?.activeRecordingConfigurations?.isNotEmpty() == true || unavailable.isNotEmpty()
}

/**
 * Android's runtime permissions, asked for on [context]'s activity when Ovenlight doesn't hold
 * them yet. Registered outside composition, so the answer arrives even if the page's screen
 * went meanwhile.
 */
object RuntimePermissions {
    /**
     * [refusedForGood] gets those Android refused without asking, turned off for Ovenlight until
     * Settings allows them, before [done] hears false. Android gives no rationale both before a
     * first question and after a dismissed one, so only a refusal of one asked before, at any
     * launch, counts.
     */
    fun request(context: Context, permissions: List<String>, refusedForGood: (List<String>) -> Unit = {}, done: (Boolean) -> Unit) {
        val missing = permissions.filterNot { holds(context, it) }
        if (missing.isEmpty()) return done(true)
        val activity = context.componentActivity() ?: return done(false)
        val store = PreferencesStore(context)
        val quiet = missing.filter { store.getBoolean(askedKey(it)) && !activity.shouldShowRequestPermissionRationale(it) }
        activity.launchOnce(ActivityResultContracts.RequestMultiplePermissions(), missing.toTypedArray(), failed = { done(false) }) { result ->
            missing.forEach { store.putBoolean(askedKey(it), true) }
            quiet.filter { result[it] != true && !activity.shouldShowRequestPermissionRationale(it) }.takeIf { it.isNotEmpty() }?.let(refusedForGood)
            done(missing.all { result[it] == true })
        }
    }

    fun holds(context: Context, permission: String) = ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

    /** Ovenlight's page in the phone's Settings, where its permissions are. */
    fun openSettings(context: Context) =
        context.startOrFalse(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null)))

    private fun askedKey(permission: String) = "askedPermission.$permission"
}

/**
 * Ovenlight's capture question for the page on screen, or where to allow what Android refused for
 * good. Leaving the question unanswered denies.
 */
@Composable
fun CapturePrompt(capture: PageCapture, edges: PageEdges, appName: String) {
    val prompt by capture.prompt.collectAsState()
    val refused by capture.refused.collectAsState()
    prompt?.let { open ->
        DisposableEffect(open) { onDispose { open.answer.dropped() } }
        // In the run with the page's dialogs, counted as it shows and closed as it goes: a page
        // that reloads and asks again as each is refused, so that no document remembers the
        // refusal, is offered the way out.
        var offersQuiet by remember(open) { mutableStateOf(false) }
        var stopMore by remember(open) { mutableStateOf(false) }
        LaunchedEffect(open) { offersQuiet = edges.nextInRun() }
        DisposableEffect(open) { onDispose(edges::closedInRun) }
        // One per question: Allow's wait ([GoAheadButton]) starts as it shows, not as it redraws.
        val answer = remember(open) {
            { allow: Boolean ->
                if (stopMore) edges.quietDialogs()
                open.answer(allow)
            }
        }
        CapturePromptDialog(capturePromptTitle(open, appName), if (offersQuiet) ({ QuietChoice(stopMore) { stopMore = it } }) else null, answer)
    }
    refused?.let { type ->
        val context = LocalContext.current
        val done = { capture.refused.value = null }
        AlertDialog(
            onDismissRequest = done,
            title = { Text(stringResource(if (type == MediaCaptureType.Camera) R.string.capture_refused_camera else R.string.capture_refused_microphone)) },
            text = { Text(stringResource(R.string.capture_refused_body, appName)) },
            confirmButton = {
                TextButton(onClick = {
                    done()
                    RuntimePermissions.openSettings(context)
                }) { Text(stringResource(R.string.open_settings)) }
            },
            dismissButton = { TextButton(onClick = done) { Text(stringResource(R.string.not_now)) } },
        )
    }
}

@Composable
internal fun capturePromptTitle(prompt: PageCapture.Prompt, appName: String): String {
    val other = prompt.otherHost
    return when (prompt.type) {
        MediaCaptureType.Camera ->
            if (other == null) stringResource(R.string.capture_allow_camera, appName) else stringResource(R.string.capture_frame_camera, other, appName)
        MediaCaptureType.Microphone ->
            if (other == null) stringResource(R.string.capture_allow_microphone, appName) else stringResource(R.string.capture_frame_microphone, other, appName)
        MediaCaptureType.CameraAndMicrophone ->
            if (other == null) stringResource(R.string.capture_allow_both, appName) else stringResource(R.string.capture_frame_both, other, appName)
    }
}

@Composable
internal fun CapturePromptDialog(title: String, quiet: (@Composable () -> Unit)? = null, answer: (Boolean) -> Unit) {
    AlertDialog(
        onDismissRequest = { answer(false) },
        title = {
            Text(title)
            GuardedFromOverlays()
        },
        text = quiet,
        confirmButton = { GoAheadButton(answer, stringResource(R.string.capture_allow)) { answer(true) } },
        dismissButton = { TextButton(onClick = { answer(false) }) { Text(stringResource(R.string.capture_dont_allow)) } },
    )
}
