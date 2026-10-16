package com.snowyghost.ovenlight

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.webkit.PermissionRequest
import androidx.activity.ComponentActivity
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.res.stringResource
import androidx.core.content.ContextCompat
import androidx.webkit.JavaScriptReplyProxy
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
 * permission, and remembers an Allow only for the page's current document. WebView can't stop a
 * stream either: on lock and on closing to the launcher the page script stops the tracks it
 * kept, and half a second later Ovenlight asks the system whether it still records or holds a
 * camera. If so, a page granted capture since its last load is destroyed, since a page can
 * capture where the script doesn't reach. Main thread only.
 */
@SuppressLint("RequiresFeature") // WebViewSetup is Ready only once it found every feature used here.
class PageCapture(
    private val app: WebApp,
    private val ownersHosts: () -> Set<String>,
    private val isLocked: () -> Boolean,
    private val isOnScreen: () -> Boolean,
    private val scope: CoroutineScope,
    /** Asks Android for these runtime permissions as needed; true once Ovenlight holds them all. */
    private val permissions: (List<String>, (Boolean) -> Unit) -> Unit,
    /** Whether Ovenlight still records or holds a camera ([CaptureMonitor]). */
    private val stillCapturing: () -> Boolean,
    /** Ends the page's view for good: the pool drops it. */
    private val destroy: () -> Unit,
) {
    /** Ovenlight's question, while one is open. */
    class Prompt(val type: MediaCaptureType, /** The host asking, when it isn't the app's own. */ val otherHost: String?, val answer: DialogAnswer<Boolean>)

    private val mutablePrompt = MutableStateFlow<Prompt?>(null)
    val prompt: StateFlow<Prompt?> = mutablePrompt

    /** The request waiting on Android's permission or Ovenlight's prompt; one at a time. */
    private var pending: PermissionRequest? = null

    /** What the person allowed in this document, by the origin that asked. */
    private val allowed = mutableMapOf<String, Set<String>>()

    /** The frames that called getUserMedia, which the stop goes to. */
    private val frames = mutableSetOf<JavaScriptReplyProxy>()

    /** Granted capture since the page's last load, so it may hold a stream. */
    private var granted = false

    fun onPermissionRequest(request: PermissionRequest) {
        val resources = request.resources.filter { it == PermissionRequest.RESOURCE_AUDIO_CAPTURE || it == PermissionRequest.RESOURCE_VIDEO_CAPTURE }.toSet()
        val type = typeOf(resources) ?: return request.deny()
        val origin = request.origin
        fun decide() = MediaCapturePolicy.decide(type, origin.scheme.orEmpty(), origin.host.orEmpty(), app, ownersHosts(), isLocked(), isOnScreen())
        val decision = decide()
        if (decision == PermissionDecision.Deny || pending != null) return request.deny()
        val key = origin.toString()
        // Without asking, only ever the microphone.
        val grant = if (decision == PermissionDecision.Grant) setOf(PermissionRequest.RESOURCE_AUDIO_CAPTURE) else resources
        val asked = decision == PermissionDecision.Grant || allowed[key]?.containsAll(resources) == true
        pending = request
        permissions(androidPermissions(resources)) { held ->
            if (pending !== request) return@permissions
            if (!held || decide() == PermissionDecision.Deny) return@permissions finish(request, null)
            if (asked) return@permissions finish(request, grant)
            mutablePrompt.value = Prompt(type, otherHost(origin.host), DialogAnswer(false) { allow ->
                mutablePrompt.value = null
                if (pending !== request) return@DialogAnswer
                if (allow && decide() != PermissionDecision.Deny) {
                    allowed[key] = allowed[key].orEmpty() + resources
                    finish(request, resources)
                } else {
                    finish(request, null)
                }
            })
        }
    }

    fun onPermissionRequestCanceled(request: PermissionRequest) {
        if (pending !== request) return
        pending = null
        mutablePrompt.value?.answer?.dropped()
    }

    /** A frame called getUserMedia (see [PageBridge]). */
    fun capturing(frame: JavaScriptReplyProxy) {
        if (frames.size < MAX_FRAMES) frames += frame
    }

    /** A new document in the main frame: what was allowed, and its streams, went with the old one. */
    fun documentChanged() {
        allowed.clear()
        frames.clear()
        granted = false
        dropPending()
    }

    /**
     * Ovenlight locked, or the page closed to the launcher: every track the page script kept
     * stops, and a page that may still capture is destroyed once the system says it does.
     */
    fun stop() {
        frames.forEach { runCatching { it.postMessage(PageBridge.STOP) } }
        allowed.clear()
        dropPending()
        if (!granted) return
        scope.launch {
            delay(CHECK_AFTER_MILLIS)
            if (!isOnScreen() && stillCapturing()) {
                Log.w(TAG, "${app.host}: still capturing after the stop, so its view goes")
                destroy()
            }
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
        private const val TAG = "Ovenlight"
        const val CHECK_AFTER_MILLIS = 500L
        private const val MAX_FRAMES = 64

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
    private var next = 0

    fun request(context: Context, permissions: List<String>, done: (Boolean) -> Unit) {
        val missing = permissions.filter { ContextCompat.checkSelfPermission(context, it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isEmpty()) return done(true)
        val activity = generateSequence(context) { (it as? android.content.ContextWrapper)?.baseContext }.firstNotNullOfOrNull { it as? ComponentActivity }
            ?: return done(false)
        var launcher: ActivityResultLauncher<Array<String>>? = null
        launcher = activity.activityResultRegistry.register("ovenlight.permissions.${next++}", ActivityResultContracts.RequestMultiplePermissions()) { result ->
            launcher?.unregister()
            done(missing.all { result[it] == true })
        }
        launcher.launch(missing.toTypedArray())
    }
}

/** Ovenlight's capture question for the page on screen. Leaving it unanswered denies. */
@Composable
fun CapturePrompt(page: AppPage) {
    val prompt by page.capture.prompt.collectAsState()
    val open = prompt ?: return
    DisposableEffect(open) { onDispose { open.answer.dropped() } }
    CapturePromptDialog(capturePromptTitle(open, page.app.name), open.answer::invoke)
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
internal fun CapturePromptDialog(title: String, answer: (Boolean) -> Unit) {
    AlertDialog(
        onDismissRequest = { answer(false) },
        title = {
            Text(title)
            GuardedFromOverlays()
        },
        // Not at once: the page's own "tap twice here" can't land its second tap on Allow.
        confirmButton = {
            val shownAt = remember(answer) { SystemClock.uptimeMillis() }
            TextButton(onClick = { if (SystemClock.uptimeMillis() - shownAt >= PROMPT_DELAY_MILLIS) answer(true) }) { Text(stringResource(R.string.capture_allow)) }
        },
        dismissButton = { TextButton(onClick = { answer(false) }) { Text(stringResource(R.string.capture_dont_allow)) } },
    )
}
