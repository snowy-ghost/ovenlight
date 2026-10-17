package com.snowyghost.ovenlight

import android.Manifest
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.Uri
import android.provider.Settings
import androidx.camera.compose.CameraXViewfinder
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.core.SurfaceRequest
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.lifecycle.awaitInstance
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.ReaderException
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeReader
import java.net.URI
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch

/** An invite to show in the join sheet: read from a link, or null for the Join with Invite entry. */
class JoinRequest(val invite: Result<InviteLink>? = null) {
    val id: UUID = UUID.randomUUID()

    /** The link's invite, or why it isn't one ([InviteLink.ParseError]). */
    constructor(url: URI) : this(
        try {
            Result.success(InviteLink.parse(url))
        } catch (e: InviteLink.ParseError) {
            Result.failure(e)
        },
    )
}

/** What the join screen shows and what its buttons do, made from the model (see [JoinFlowView]), so tests can give it their own. */
data class JoinState(
    /** Invites being accepted this session, by invite ID. */
    val phases: Map<String, JoinPhase> = emptyMap(),
    /** What accepting an invite would do ([GuestManager.preflight]). */
    val preflight: (InviteLink) -> Preflight = { Preflight.Ready },
    val apps: List<WebApp> = emptyList(),
    /** Goes up with each icon saved, so a joined app's letter turns into its icon. */
    val iconRevisions: Map<UUID, Int> = emptyMap(),
    val icon: (WebApp) -> Bitmap? = { null },
    /** The phone has a camera to scan an invite code with. */
    val canScan: Boolean = false,
    val accept: (InviteLink) -> Unit = {},
    val retry: (InviteLink) -> Unit = {},
    /** Stop joining: withdraws from the invite, then calls its second argument. */
    val cancel: (InviteLink, () -> Unit) -> Unit = { _, done -> done() },
    val open: (WebApp) -> Unit = {},
)

/** The join screen (JoinFlowView in JoinViews.swift), from the model. */
@Composable
fun JoinFlowView(model: AppModel, request: JoinRequest, onClose: () -> Unit) {
    val context = LocalContext.current
    val guests = model.guests
    val registry = model.registry
    val nodes = model.nodes
    val phases by guests.phases.collectAsState()
    val apps by registry.appsFlow.collectAsState()
    val iconRevisions by registry.iconRevisions.collectAsState()
    // What a preflight reads, so its answer follows them.
    val memberships by nodes.memberships.membershipsFlow.collectAsState()
    val ownerEnabled by nodes.ownerEnabled.collectAsState()
    val ownerStatus by nodes.owner.status.collectAsState()
    val preflight = remember(apps, memberships, ownerEnabled, ownerStatus) { { invite: InviteLink -> guests.preflight(invite) } }
    val scope = model.scope
    JoinFlowScreen(
        request,
        JoinState(
            phases = phases, preflight = preflight, apps = apps, iconRevisions = iconRevisions,
            icon = { IconDecoder.load(registry.iconFile(it)) },
            canScan = remember { context.packageManager.hasSystemFeature(PackageManager.FEATURE_CAMERA_ANY) },
            // The join goes on in the model's scope when the screen closes; the launcher shows it.
            accept = { scope.launch { guests.accept(it) } },
            retry = { scope.launch { guests.retry(it.invite) } },
            cancel = { invite, done ->
                scope.launch {
                    guests.cancel(invite.invite)
                    done()
                }
            },
            open = model.router::open,
        ),
        onClose,
    )
}

/**
 * The whole join on one screen, as iOS's one sheet: find the invite, confirm it, then follow
 * it through joining and the claim. A full-screen dialog's close button rather than a back
 * arrow, as Material has it for a task with text entry. Nothing in it names the network
 * underneath.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun JoinFlowScreen(request: JoinRequest, state: JoinState, onClose: () -> Unit) {
    var invite by remember(request.id) { mutableStateOf(request.invite) }
    val background = LocalOvenColors.current.groupedBackground
    val title = ConnectionCopy.joinWithInvite
    Scaffold(
        modifier = Modifier.semantics { paneTitle = title },
        containerColor = background,
        topBar = {
            TopAppBar(
                title = { if (invite == null) Text(title) },
                navigationIcon = {
                    IconButton(onClick = onClose) {
                        Icon(painterResource(R.drawable.ic_close), contentDescription = stringResource(R.string.join_close))
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = background, scrolledContainerColor = background),
            )
        },
    ) { bars ->
        Box(Modifier.fillMaxSize().padding(bars).consumeWindowInsets(bars).imePadding(), contentAlignment = Alignment.TopCenter) {
            val current = invite
            when {
                current == null -> InviteEntry(state.canScan, found = { invite = Result.success(it) })
                current.isSuccess -> JoinInvite(current.getOrThrow(), state, onClose)
                else -> CantUseInvite(current.exceptionOrNull()?.message.orEmpty(), tryAnother = { invite = null })
            }
        }
    }
}

/** Paste the link or type it, or scan its code (InviteEntryView). A link inside a whole pasted message counts. */
@Composable
private fun InviteEntry(canScan: Boolean, found: (InviteLink) -> Unit) {
    val context = LocalContext.current
    val colors = MaterialTheme.colorScheme
    val text = rememberTextFieldState()
    var error by remember { mutableStateOf<String?>(null) }
    var scanning by remember { mutableStateOf(false) }
    val use = { value: String ->
        try {
            found(InviteLink.parse(value))
        } catch (e: InviteLink.ParseError) {
            error = e.message
        }
    }
    Column(
        Modifier.widthIn(max = 640.dp).fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(ConnectionCopy.invitePrompt, style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant, modifier = Modifier.padding(horizontal = 16.dp))
        // The clipboard is read only on a tap, and Android says when it is.
        FilledTonalButton(onClick = { use(clipboardText(context)) }, modifier = Modifier.fillMaxWidth().height(56.dp)) {
            Text(stringResource(R.string.join_paste), style = MaterialTheme.typography.titleMedium)
        }
        Text(
            stringResource(R.string.join_type_it), style = MaterialTheme.typography.titleSmall, color = colors.primary,
            modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp).semantics { heading() },
        )
        val monospace = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace)
        Column {
            OutlinedTextField(
                text,
                label = { Text(stringResource(R.string.join_link)) },
                placeholder = { Text("https://ovenlight.app/join#…", style = monospace) },
                textStyle = monospace,
                lineLimits = TextFieldLineLimits.MultiLine(maxHeightInLines = 4),
                isError = error != null,
                supportingText = error?.let { message ->
                    {
                        // Under the field, away from TalkBack's focus, so it's announced.
                        Row(Modifier.semantics { liveRegion = LiveRegionMode.Polite }, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            Icon(painterResource(R.drawable.ic_warning), contentDescription = null, modifier = Modifier.size(16.dp))
                            Text(message)
                        }
                    }
                },
                keyboardOptions = KeyboardOptions(
                    capitalization = KeyboardCapitalization.None, autoCorrectEnabled = false, keyboardType = KeyboardType.Uri, imeAction = ImeAction.Go,
                ),
                onKeyboardAction = { use(text.text.toString()) },
                modifier = Modifier.fillMaxWidth(),
            )
            TextButton(onClick = { use(text.text.toString()) }, enabled = text.text.isNotBlank(), modifier = Modifier.align(Alignment.End)) {
                Text(stringResource(R.string.join_continue))
            }
        }
        if (canScan) {
            OutlinedButton(
                onClick = {
                    RuntimePermissions.request(context, listOf(Manifest.permission.CAMERA)) { granted ->
                        // Turned off for good, the scanner says so, with the way to Settings.
                        if (granted || context.componentActivity()?.shouldShowRequestPermissionRationale(Manifest.permission.CAMERA) == false) scanning = true
                    }
                },
                modifier = Modifier.fillMaxWidth().height(56.dp),
            ) {
                Icon(painterResource(R.drawable.ic_qr_code_scanner), contentDescription = null, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.join_scan), style = MaterialTheme.typography.titleMedium)
            }
        }
    }
    if (scanning) {
        val haptics = LocalHapticFeedback.current
        InviteScannerView(found = {
            scanning = false
            haptics.performHapticFeedback(HapticFeedbackType.Confirm)
            use(it)
        }, cancel = { scanning = false })
    }
}

/** What's on the clipboard as text, or nothing. */
private fun clipboardText(context: Context): String {
    val clip = context.getSystemService(ClipboardManager::class.java)?.primaryClip ?: return ""
    return if (clip.itemCount == 0) "" else clip.getItemAt(0).coerceToText(context).toString()
}

/** A link that isn't an invite Ovenlight can use, and why. */
@Composable
private fun CantUseInvite(message: String, tryAnother: () -> Unit) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(painterResource(R.drawable.ic_error), contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(48.dp))
        Text(
            stringResource(R.string.join_cant_use), Modifier.padding(top = 16.dp).semantics { heading() },
            style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center,
        )
        Text(
            message, Modifier.padding(top = 8.dp),
            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center,
        )
        Button(onClick = tryAnother, modifier = Modifier.padding(top = 24.dp).height(56.dp)) {
            Text(stringResource(R.string.join_try_another), style = MaterialTheme.typography.titleMedium)
        }
    }
}

/** "Join Riley's Echo Board?", then its progress and how it ended (JoinInviteView). */
@Composable
private fun JoinInvite(invite: InviteLink, state: JoinState, close: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val phase = state.phases[invite.invite]
    val preflight = state.preflight(invite)
    // The invited app once it's on this phone, joined now or before. Its icon can't be fetched
    // until joining gives this phone access to its computer; adding the app fetches it, so the
    // letter turns into the real icon a moment after joining.
    val added = (phase as? JoinPhase.Joined)?.let { joined -> state.apps.firstOrNull { it.id == joined.app } }
        ?: (preflight as? Preflight.AlreadyAdded)?.app
    val preview = remember(invite) { WebApp(name = invite.name, startURL = invite.appURL) }
    val image = added?.let { rememberIcon(it, state.iconRevisions[it.id] ?: 0, state.icon) }
    val haptics = LocalHapticFeedback.current
    // Only for a change while the screen shows, not for how the join already stood.
    var felt by remember { mutableStateOf(phase) }
    LaunchedEffect(phase) {
        if (phase != felt) {
            when (phase) {
                is JoinPhase.Joined -> haptics.performHapticFeedback(HapticFeedbackType.Confirm)
                is JoinPhase.Failed -> haptics.performHapticFeedback(HapticFeedbackType.Reject)
                else -> Unit
            }
        }
        felt = phase
    }

    // Anchored just above the buttons rather than floating mid-screen, scrolling only when the
    // largest font sizes don't fit.
    BoxWithConstraints(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        Column(
            Modifier.widthIn(max = 640.dp).verticalScroll(rememberScrollState()).heightIn(min = maxHeight).fillMaxWidth()
                .padding(horizontal = 28.dp).animateContentSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Bottom,
        ) {
            Spacer(Modifier.height(24.dp))
            Box(Modifier.padding(bottom = 22.dp)) {
                AppIconView(added ?: preview, image = image, size = 88.dp)
                // Named in full: the column's own AnimatedVisibility is in scope too.
                androidx.compose.animation.AnimatedVisibility(
                    phase is JoinPhase.Joined, Modifier.align(Alignment.BottomEnd).offset(8.dp, 8.dp),
                    enter = scaleIn() + fadeIn(), exit = scaleOut() + fadeOut(),
                ) {
                    Icon(
                        painterResource(R.drawable.ic_check_circle), contentDescription = null, tint = LocalOvenColors.current.success,
                        modifier = Modifier.size(28.dp).background(LocalOvenColors.current.groupedBackground, CircleShape),
                    )
                }
            }
            Column(Modifier.padding(bottom = 32.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
                @Composable
                fun title(text: String) = Text(text, Modifier.semantics { heading() }, style = OvenType.displayTitle, color = colors.onSurface, textAlign = TextAlign.Center)

                @Composable
                fun detail(text: String, modifier: Modifier = Modifier) =
                    Text(text, modifier, style = MaterialTheme.typography.bodyLarge, color = colors.onSurfaceVariant, textAlign = TextAlign.Center)

                when {
                    phase is JoinPhase.Joined || (phase == null && preflight is Preflight.AlreadyAdded) -> {
                        title(ConnectionCopy.joined(invite.name))
                        detail(stringResource(R.string.join_added, invite.owner))
                    }
                    phase is JoinPhase.Failed -> {
                        title(phase.error.title(invite.owner, invite.name))
                        detail(phase.error.message(invite.owner, invite.name))
                    }
                    phase != null -> {
                        title(ConnectionCopy.joining(invite.owner, invite.name))
                        detail(ConnectionCopy.joiningDetail)
                        CircularProgressIndicator(Modifier.padding(top = 6.dp))
                    }
                    preflight == Preflight.OwnApp -> {
                        title(JoinError.OwnApp.title(invite.owner, invite.name))
                        detail(JoinError.OwnApp.message(invite.owner, invite.name))
                    }
                    else -> {
                        title(ConnectionCopy.joinTitle(invite.owner, invite.name))
                        // The address reads as code to a friend; TalkBack still says it.
                        val text = ConnectionCopy.joinDetail(invite.owner, invite.name, invite.to)
                        val spoken = "$text ${ConnectionCopy.appAddress(invite.host)}"
                        detail(text, Modifier.semantics { contentDescription = spoken })
                        if (!invite.usesTailscaleControl) {
                            Row(Modifier.padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                Icon(painterResource(R.drawable.ic_warning), contentDescription = null, tint = colors.tertiary, modifier = Modifier.size(18.dp))
                                Text(
                                    ConnectionCopy.privateServer(invite.control.bareHost.orEmpty(), invite.owner),
                                    style = MaterialTheme.typography.bodySmall, color = colors.tertiary,
                                )
                            }
                        }
                    }
                }
            }
            Column(Modifier.padding(bottom = 12.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                @Composable
                fun primary(label: String, action: () -> Unit) = Button(onClick = action, modifier = Modifier.fillMaxWidth().height(56.dp)) {
                    Text(label, style = MaterialTheme.typography.titleMedium)
                }

                val open = stringResource(R.string.join_open, invite.name)
                when {
                    phase is JoinPhase.Joined -> primary(open) { added?.let(state.open) ?: close() }
                    phase == null && preflight is Preflight.AlreadyAdded -> {
                        primary(open) { state.open(preflight.app) }
                        // A new invite after the owner removed the app must reach its connector.
                        TextButton(onClick = { state.accept(invite) }) { Text(stringResource(R.string.join_again)) }
                    }
                    phase is JoinPhase.Failed && phase.error.isRetryable -> {
                        primary(stringResource(R.string.join_try_again)) { state.retry(invite) }
                        TextButton(onClick = { state.cancel(invite, close) }, colors = ButtonDefaults.textButtonColors(contentColor = colors.error)) {
                            Text(stringResource(R.string.join_stop))
                        }
                    }
                    phase is JoinPhase.Failed || (phase == null && preflight == Preflight.OwnApp) -> primary(stringResource(R.string.join_done), close)
                    phase != null -> Unit
                    else -> {
                        primary(stringResource(R.string.join_join)) { state.accept(invite) }
                        TextButton(onClick = close) { Text(stringResource(R.string.join_not_now)) }
                    }
                }
            }
        }
    }
}

/**
 * Scans an invite's QR code with the camera, full screen (InviteScannerView). Only an invite
 * link counts. With the camera turned off for Ovenlight for good, it says so, with the way to
 * Settings, and starts once the camera is allowed there.
 */
@Composable
private fun InviteScannerView(found: (String) -> Unit, cancel: () -> Unit) {
    Dialog(onDismissRequest = cancel, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        FullScreenDialogBars()
        val context = LocalContext.current
        val allowed = { ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED }
        var permitted by remember { mutableStateOf(allowed()) }
        LifecycleResumeEffect(Unit) {
            permitted = allowed()
            onPauseOrDispose {}
        }
        Box(Modifier.fillMaxSize().background(Color.Black)) {
            if (permitted) {
                CameraScanner(found)
            } else {
                Column(
                    Modifier.align(Alignment.Center).padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(24.dp),
                ) {
                    Text(stringResource(R.string.join_camera_denied), color = Color.White, style = MaterialTheme.typography.bodyLarge, textAlign = TextAlign.Center)
                    Button(onClick = {
                        context.startOrFalse(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null)))
                    }) { Text(stringResource(R.string.join_open_settings)) }
                }
            }
            Row(
                Modifier.windowInsetsPadding(WindowInsets.safeDrawing).padding(horizontal = 16.dp, vertical = 8.dp).fillMaxWidth()
                    .clip(CircleShape).background(MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.9f)).padding(start = 18.dp, end = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(stringResource(R.string.join_point_at_code), Modifier.weight(1f), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurface)
                TextButton(onClick = cancel) { Text(stringResource(R.string.join_cancel)) }
            }
        }
    }
}

/** The camera's picture, read for an invite code until one is found. The back camera, or the front one where there's no other. */
@Composable
private fun CameraScanner(found: (String) -> Unit) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current
    val currentFound by rememberUpdatedState(found)
    var picture by remember { mutableStateOf<SurfaceRequest?>(null) }
    LaunchedEffect(lifecycle) {
        val provider = ProcessCameraProvider.awaitInstance(context)
        val camera = listOf(CameraSelector.DEFAULT_BACK_CAMERA, CameraSelector.DEFAULT_FRONT_CAMERA)
            .firstOrNull { runCatching { provider.hasCamera(it) }.getOrDefault(false) } ?: return@LaunchedEffect
        val preview = Preview.Builder().build().also { it.setSurfaceProvider { request -> picture = request } }
        val analysis = ImageAnalysis.Builder().setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST).build()
        val frames = Executors.newSingleThreadExecutor()
        val main = ContextCompat.getMainExecutor(context)
        val done = AtomicBoolean(false)
        analysis.setAnalyzer(frames) { image ->
            val link = image.use(InviteScanner::read)
            if (link != null && done.compareAndSet(false, true)) main.execute { currentFound(link) }
        }
        try {
            provider.bindToLifecycle(lifecycle, camera, preview, analysis)
            awaitCancellation()
        } finally {
            provider.unbind(preview, analysis)
            frames.shutdown()
        }
    }
    picture?.let { CameraXViewfinder(it, Modifier.fillMaxSize()) }
}

/** Reads invite codes from the camera's frames with ZXing's QR reader (decision 6: nothing from Play services). */
object InviteScanner {
    private val hints = mapOf(DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.QR_CODE), DecodeHintType.TRY_HARDER to true)

    /** The invite link in a camera frame, read from its brightness. */
    fun read(image: ImageProxy): String? {
        val plane = image.planes[0]
        val bytes = ByteArray(plane.buffer.remaining()).also { plane.buffer.get(it) }
        return invite(bytes, plane.rowStride, image.width, image.height)
    }

    /** The text of a QR code in [luminance] (a byte a pixel, rows [rowStride] bytes apart) when it's an invite link, else null. */
    fun invite(luminance: ByteArray, rowStride: Int, width: Int, height: Int): String? {
        val source = PlanarYUVLuminanceSource(luminance, rowStride, height, 0, 0, width, height, false)
        val text = try {
            QRCodeReader().decode(BinaryBitmap(HybridBinarizer(source)), hints).text
        } catch (_: ReaderException) {
            return null
        }
        return text.takeIf { uri(it)?.let(InviteLink::isInvite) == true }
    }
}

/** The launcher's first screen, before any app: anyone may see it, so it names no network. */
object LauncherWelcome {
    const val title = "Welcome to Ovenlight"
    const val message = "Open the invite a friend sent you, or connect the computers where your own apps run."
}

/**
 * The launcher before anything is set up. Nothing here assumes the person runs their own
 * computers: a friend who got an invite starts here too, and never sees a sign-in. Scrolls only
 * when the largest font sizes don't fit.
 */
@Composable
fun LauncherWelcome(join: () -> Unit, useOwnComputers: () -> Unit, modifier: Modifier = Modifier) {
    val colors = MaterialTheme.colorScheme
    val glow = LocalOvenColors.current.lampGlow.copy(alpha = if (isSystemInDarkTheme()) 0.25f else 0.2f)
    BoxWithConstraints(modifier.fillMaxWidth()) {
        Column(
            Modifier.verticalScroll(rememberScrollState()).heightIn(min = maxHeight).fillMaxWidth().padding(horizontal = 32.dp, vertical = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            // The empty rack, in the light, waiting for apps.
            Box(Modifier.size(270.dp, 64.dp), contentAlignment = Alignment.Center) {
                Canvas(Modifier.matchParentSize().clearAndSetSemantics {}) {
                    scale(scaleX = 1f, scaleY = size.height / size.width) {
                        drawCircle(Brush.radialGradient(listOf(glow, Color.Transparent), center = center, radius = size.width / 2), radius = size.width / 2)
                    }
                }
                OvenRack(250.dp)
            }
            Spacer(Modifier.height(30.dp))
            Text(LauncherWelcome.title, style = OvenType.displayTitle, color = colors.onSurface, textAlign = TextAlign.Center)
            Spacer(Modifier.height(10.dp))
            Text(LauncherWelcome.message, style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant, textAlign = TextAlign.Center)
            Spacer(Modifier.height(24.dp))
            Button(onClick = join, modifier = Modifier.height(56.dp)) {
                Text(ConnectionCopy.joinWithInvite, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(horizontal = 8.dp))
            }
            Spacer(Modifier.height(14.dp))
            TextButton(onClick = useOwnComputers) { Text(stringResource(R.string.launcher_use_own_computers)) }
        }
    }
}

/** Above the apps: invites still joining, or stopped by a failure. Tapping one reopens it in the join screen. */
@Composable
fun PendingInvitesBanner(invites: List<InviteLink>, phases: Map<String, JoinPhase>, open: (InviteLink) -> Unit, modifier: Modifier = Modifier) {
    if (invites.isEmpty()) return
    val colors = MaterialTheme.colorScheme
    val container = if (isSystemInDarkTheme()) colors.surfaceContainerHigh else colors.surfaceContainerLowest
    Column(modifier.padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        for (invite in invites.distinctBy { it.invite }) {
            key(invite.invite) {
                val failed = (phases[invite.invite] as? JoinPhase.Failed)?.error
                val app = remember(invite.invite) { WebApp(name = invite.name, startURL = invite.appURL) }
                Surface(onClick = { open(invite) }, shape = RoundedCornerShape(16.dp), color = container, modifier = Modifier.fillMaxWidth()) {
                    Row(Modifier.padding(12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        AppIconView(app, image = null, size = 36.dp)
                        Column(Modifier.weight(1f)) {
                            Text(invite.name, style = MaterialTheme.typography.titleSmall, color = colors.onSurface)
                            Text(
                                failed?.title(invite.owner, invite.name) ?: ConnectionCopy.joining(invite.owner, invite.name),
                                style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant,
                            )
                        }
                        if (failed != null) {
                            Icon(painterResource(R.drawable.ic_error), contentDescription = null, tint = colors.tertiary)
                        } else {
                            CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
                        }
                    }
                }
            }
        }
    }
}
