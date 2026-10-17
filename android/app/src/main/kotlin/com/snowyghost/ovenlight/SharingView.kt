package com.snowyghost.ovenlight

import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.os.Build
import android.os.PersistableBundle
import android.text.format.DateFormat
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.PredictiveBackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.SeekableTransitionState
import androidx.compose.animation.core.rememberTransition
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withLink
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.WriterException
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import java.net.URI
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** The owner's People & sharing. */
object SharingList {
    /**
     * How many of the tailnet's tagged devices, which Tailscale's plans count, the owner's
     * shareable apps and guests' devices take. Null before a connector has counted them.
     */
    fun taggedDevices(machines: List<OwnerSharing.Machine>): String? {
        val counts = machines.mapNotNull { it.guests.taggedDevices }
        val all = counts.maxOfOrNull { it.tailnet } ?: return null
        val used = minOf(counts.sumOf { it.apps + it.guests }, all)
        if (used <= 0) return null
        val share = when {
            used < all -> "$used of the $all tagged devices"
            used == 1L -> "the only tagged device"
            else -> "all $all tagged devices"
        }
        return "Your shareable apps and your guests' devices use $share in your Tailscale network. Tailscale's plans include a set number and charge for more."
    }
}

/**
 * What the sharing screens show and do, from the model (see [sharingState]), so tests can drive
 * them without an [AppModel]. Admin credentials never leave the computers: Ovenlight asks each
 * connector over the owner's own node.
 */
data class SharingState(
    val machines: StateFlow<List<OwnerSharing.Machine>>,
    /** The owner is connected, which the admin API needs. */
    val isAvailable: Boolean = true,
    /** Nothing listed yet, and a refresh under way. */
    val isLoading: Boolean = false,
    val problem: String? = null,
    /** The owner's own app at a connector app's host, for its icon. */
    val localApp: (AdminApp) -> WebApp? = { null },
    val icon: (WebApp) -> Bitmap? = { null },
    val refresh: suspend () -> Unit = {},
    val refreshIfStale: suspend () -> Unit = {},
    val client: (OwnerSharing.Machine) -> AdminClient? = { null },
    /** The screen lock again, before anything that changes who can open an app (see UnlockPrompt.confirm). */
    val confirm: suspend (reason: String) -> Boolean = { true },
    /** Opens the invite composer: for someone new or already shared with, or another device for [person]. */
    val compose: (OwnerSharing.Machine, AdminApp, person: GuestPerson?) -> Unit = { _, _, _ -> },
    val openLink: (URI) -> Unit = {},
)

/** The model's sharing, for the screens and the composer. */
@Composable
fun sharingState(model: AppModel): SharingState {
    val context = LocalContext.current
    val activity = LocalActivity.current as? MainActivity
    val sharing = model.sharing
    val machines by sharing.machines.collectAsState()
    val loading by sharing.isLoading.collectAsState()
    val problem by sharing.problem.collectAsState()
    val apps by model.registry.appsFlow.collectAsState()
    val ownerEnabled by model.nodes.ownerEnabled.collectAsState()
    val ownerState by model.nodes.owner.state.collectAsState()
    return SharingState(
        sharing.machines,
        isAvailable = ownerEnabled && ownerState == NodeState.Ready,
        isLoading = loading && machines.isEmpty(),
        problem = problem,
        localApp = { admin -> apps.firstOrNull { !it.isShared && Discovery.normalizedHost(it.host) == admin.host } },
        icon = { IconDecoder.load(model.registry.iconFile(it)) },
        refresh = { sharing.refresh(model.registry.apps) },
        refreshIfStale = { sharing.refreshIfStale(model.registry.apps) },
        client = sharing::client,
        confirm = { reason -> activity?.confirmOwner(reason) ?: false },
        compose = { machine, app, person -> model.composer.value = InviteComposer(machine, app, person) },
        openLink = { CustomTabs.open(context, it, model.lifecycle) },
    )
}

/** The pages of People & sharing, each over the one it was opened from. */
sealed interface SharingPage {
    data object List : SharingPage

    data class App(val machineID: String, val slug: String) : SharingPage

    data object People : SharingPage

    data class Person(val machineID: String, val personID: String) : SharingPage

    /** Feedback across computers, or one app's. */
    data class Feedback(val machineID: String? = null, val slug: String? = null) : SharingPage

    data class FeedbackItem(val machineID: String, val id: String) : SharingPage
}

/** People & sharing from the model, starting at [root]: the list, or one app's people from its menu. */
@Composable
fun SharingView(model: AppModel, root: SharingPage, onClose: () -> Unit) = SharingScreen(sharingState(model), root, onClose, model.scope)

/**
 * People & sharing, and the pages it opens. Changes run in [scope], which outlives the screen, so
 * a removal confirmed with the screen lock finishes even if the screen closes.
 */
@Composable
fun SharingScreen(state: SharingState, root: SharingPage, onClose: () -> Unit, scope: CoroutineScope) {
    val pages = remember { mutableStateListOf(root) }
    val shots = remember { ScreenshotCache() }
    // Each page keeps its place while others open over it, as Settings does under Acknowledgements.
    val scrolls = remember { mutableMapOf<SharingPage, ScrollState>() }
    val open: (SharingPage) -> Unit = { pages += it }
    val back: () -> Unit = { if (pages.size > 1) pages.removeAt(pages.lastIndex) else onClose() }
    PageStack(pages) { page ->
        val scroll = scrolls.getOrPut(page) { ScrollState(0) }
        when (page) {
            SharingPage.List -> SharingListPage(state, scroll, open, back)
            is SharingPage.App -> SharingAppPage(state, page, scroll, open, back, scope)
            SharingPage.People -> SharingPeopleList(state, scroll, open, back, scope)
            is SharingPage.Person -> SharingPersonView(state, page, scroll, back, scope)
            is SharingPage.Feedback -> FeedbackInbox(state, page, scroll, shots, open, back)
            is SharingPage.FeedbackItem -> FeedbackDetail(state, page, scroll, shots, back)
        }
    }
}

/**
 * Pages over pages within one screen: each slides in over the one before, as Settings opens
 * Acknowledgements, and Back, with its preview under the finger, slides the top one away.
 */
@Composable
private fun <T : Any> PageStack(pages: SnapshotStateList<T>, content: @Composable (T) -> Unit) {
    val top = pages.last()
    val state = remember { SeekableTransitionState(top) }
    LaunchedEffect(top) { state.animateTo(top, screenCurve) }
    val scope = rememberCoroutineScope()
    PredictiveBackHandler(pages.size > 1) { events ->
        // A page can go some other way mid-gesture (a person removed, say), so the one below is read each time.
        try {
            events.collect { event -> pages.getOrNull(pages.size - 2)?.let { state.seekTo(event.progress, targetState = it) } }
            if (pages.size > 1) pages.removeAt(pages.lastIndex)
        } catch (cancelled: CancellationException) {
            scope.launch { state.animateTo(pages.last(), screenCurve) }
            throw cancelled
        }
    }
    rememberTransition(state, label = "pages").AnimatedContent(
        transitionSpec = {
            // A page going away is no longer in the stack, or is above the one it reveals.
            val from = pages.indexOf(initialState)
            if (from >= 0 && from < pages.indexOf(targetState)) {
                slideInHorizontally(screenTween()) { it } togetherWith slideOutHorizontally(screenTween()) { -it / 3 }
            } else {
                (slideInHorizontally(screenTween()) { -it / 3 } togetherWith slideOutHorizontally(screenTween()) { it }).apply { targetContentZIndex = -1f }
            } using SizeTransform(clip = false)
        },
    ) { content(it) }
}

@Composable
private fun SharingListPage(state: SharingState, scroll: ScrollState, open: (SharingPage) -> Unit, back: () -> Unit) {
    val machines by state.machines.collectAsState()
    val feedbackCount = machines.sumOf { it.feedback.size }
    val peopleCount = machines.sumOf { GuestPerson.all(it.guests.guests).size }
    LaunchedEffect(Unit) { state.refresh() }
    GroupedScreen(stringResource(R.string.launcher_people_sharing), onBack = back, scroll = scroll, refresh = state.refresh) {
        Column(verticalArrangement = Arrangement.spacedBy(24.dp)) {
            when {
                !state.isAvailable -> Unavailable(stringResource(R.string.sharing_not_connected), stringResource(R.string.sharing_not_connected_detail))
                state.isLoading -> Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                machines.isEmpty() -> Unavailable(stringResource(R.string.sharing_no_computers), state.problem ?: stringResource(R.string.sharing_no_computers_detail))
            }
            for (machine in machines) {
                SettingsGroup(header = if (machines.size > 1) machine.adminHost else stringResource(R.string.sharing_apps)) {
                    for (app in machine.apps) {
                        row { shape ->
                            SettingsRow(
                                shape, app.name, supporting = appSummary(app), leading = { AdminAppIcon(state, app) },
                                onClick = { open(SharingPage.App(machine.id, app.slug)) },
                            )
                        }
                    }
                }
            }
            if (machines.isNotEmpty()) {
                val tagged = SharingList.taggedDevices(machines)
                SettingsGroup(footer = tagged?.let { { TaggedDevicesFooter(it, state.openLink) } }) {
                    if (peopleCount > 0) {
                        row { shape ->
                            SettingsRow(shape, stringResource(R.string.sharing_people), icon = R.drawable.ic_group, trailing = { Count(peopleCount) }, onClick = { open(SharingPage.People) })
                        }
                    }
                    row { shape ->
                        SettingsRow(
                            shape, stringResource(R.string.sharing_feedback), icon = R.drawable.ic_feedback,
                            trailing = if (feedbackCount > 0) ({ Count(feedbackCount) }) else null, onClick = { open(SharingPage.Feedback()) },
                        )
                    }
                }
            }
        }
    }
}

/** "Online · 2 people", or who it's for when it isn't shareable. */
@Composable
private fun appSummary(app: AdminApp): String {
    val state = stringResource(if (app.online) R.string.sharing_online else R.string.sharing_offline)
    return when {
        !app.shareable -> stringResource(R.string.sharing_only_you, state)
        app.guests == 0L -> stringResource(R.string.sharing_not_shared_yet, state)
        else -> pluralStringResource(R.plurals.sharing_people_count, app.guests.coerceAtMost(Int.MAX_VALUE.toLong()).toInt(), state, app.guests)
    }
}

/** The tagged devices, with Learn more opening Tailscale's pricing in a Custom Tab, as Settings opens the privacy policy. */
@Composable
private fun TaggedDevicesFooter(text: String, openLink: (URI) -> Unit) {
    val link = stringResource(R.string.sharing_learn_more)
    val primary = MaterialTheme.colorScheme.primary
    val annotated = remember(text, link, primary) {
        buildAnnotatedString {
            append("$text ")
            val url = AppLinks.tailscalePricing
            withLink(LinkAnnotation.Url(url.toString(), TextLinkStyles(SpanStyle(color = primary))) { openLink(url) }) { append(link) }
        }
    }
    Text(annotated, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 16.dp))
}

@Composable
private fun Count(count: Int) = Text("$count", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)

/** A connector's app with its icon from the launcher, or a plain tile when it isn't there. */
@Composable
internal fun AdminAppIcon(state: SharingState, app: AdminApp) {
    val local = state.localApp(app)
    val tile = local ?: WebApp(name = app.name, startURL = app.url?.let(::uri) ?: URI("https://${app.slug}.invalid/"))
    val image = remember(local?.id, local?.iconFile) { local?.let(state.icon) }
    AppIconView(tile, image, 36.dp)
}

/** When there's nothing to list: what, and why or what to do. */
@Composable
internal fun Unavailable(title: String, detail: String? = null) {
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 48.dp).semantics(mergeDescendants = true) {},
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(title, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
        detail?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center) }
    }
}

/** What went wrong, above the rest of a page. */
@Composable
internal fun SharingError(message: String) {
    SettingsGroup {
        row { shape ->
            SettingsRow(shape, message, leading = { Icon(painterResource(R.drawable.ic_warning), contentDescription = null, tint = MaterialTheme.colorScheme.tertiary) })
        }
    }
}

/** A row's more button, whose menu takes the place of iOS's swipe actions; [label] names the row for TalkBack. */
@Composable
internal fun RowMenu(label: String, items: @Composable ColumnScope.(close: () -> Unit) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { expanded = true }) {
            Icon(painterResource(R.drawable.ic_more_vert), contentDescription = stringResource(R.string.sharing_options, label))
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) { items { expanded = false } }
    }
}

/** One app: share it, and see and remove the people it's shared with. */
@Composable
private fun SharingAppPage(state: SharingState, page: SharingPage.App, scroll: ScrollState, open: (SharingPage) -> Unit, back: () -> Unit, scope: CoroutineScope) {
    val machines by state.machines.collectAsState()
    val machine = machines.firstOrNull { it.id == page.machineID }
    val app = machine?.apps?.firstOrNull { it.slug == page.slug }
    var removing by remember { mutableStateOf<GuestPerson?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    val resources = LocalResources.current
    LaunchedEffect(Unit) { state.refreshIfStale() }
    GroupedScreen(app?.name ?: stringResource(R.string.sharing_app), onBack = back, scroll = scroll, refresh = state.refresh) {
        Column(verticalArrangement = Arrangement.spacedBy(24.dp)) {
            if (machine == null || app == null) return@Column Unavailable(stringResource(R.string.sharing_app_not_found))
            val people = GuestPerson.all(machine.guests.guests.filter { it.app == page.slug })
            val invites = machine.guests.invites.filter { it.app == page.slug && it.state == AdminInvite.sent }
            val feedback = machine.feedback.count { it.app == page.slug }
            error?.let { SharingError(it) }
            SettingsGroup(
                footer = {
                    Footer(if (app.shareable) stringResource(R.string.sharing_share_footer) else stringResource(R.string.sharing_not_shareable_footer, app.slug))
                },
            ) {
                row { shape ->
                    if (app.shareable) {
                        SettingsRow(
                            shape, stringResource(R.string.sharing_share_app, app.name), icon = R.drawable.ic_share, color = MaterialTheme.colorScheme.primary,
                            onClick = { state.compose(machine, app, null) },
                        )
                    } else {
                        SettingsRow(shape, stringResource(R.string.sharing_only_for_you, app.name))
                    }
                }
            }
            if (app.shareable) {
                SettingsGroup(header = stringResource(R.string.sharing_people)) {
                    if (people.isEmpty()) row { shape -> SettingsRow(shape, stringResource(R.string.sharing_no_one_yet), color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    for (person in people) {
                        row { shape ->
                            SettingsRow(
                                shape, person.name, supporting = person.summary(machine.guests.openedSince),
                                trailing = {
                                    RowMenu(person.name) { close ->
                                        AppMenuItem(close, stringResource(R.string.sharing_add_device_for, person.name), R.drawable.ic_add) { state.compose(machine, app, person) }
                                        AppMenuItem(close, stringResource(R.string.sharing_remove_person, person.name), R.drawable.ic_person_remove, destructive = true) { removing = person }
                                    }
                                },
                                onClick = { open(SharingPage.Person(machine.id, person.id)) },
                            )
                        }
                    }
                }
                if (invites.isNotEmpty()) {
                    SettingsGroup(header = stringResource(R.string.sharing_open_invites)) {
                        for (invite in invites) {
                            row { shape ->
                                val title = stringResource(R.string.sharing_invite_for, invite.to)
                                SettingsRow(
                                    shape, title,
                                    supporting = stringResource(if (invite.review == true) R.string.sharing_review_unused else R.string.sharing_unused, invite.expiresIn),
                                    trailing = {
                                        RowMenu(title) { close ->
                                            AppMenuItem(close, stringResource(R.string.sharing_cancel_invite), R.drawable.ic_close, destructive = true) {
                                                scope.launch { error = cancel(state, machine, invite) }
                                            }
                                        }
                                    },
                                )
                            }
                        }
                    }
                }
            }
            SettingsGroup {
                row { shape ->
                    SettingsRow(
                        shape, stringResource(R.string.sharing_feedback), icon = R.drawable.ic_feedback,
                        trailing = if (feedback > 0) ({ Count(feedback) }) else null, onClick = { open(SharingPage.Feedback(machine.id, page.slug)) },
                    )
                }
            }
        }
    }
    val person = removing
    if (person != null && machine != null) {
        Confirmation(
            stringResource(R.string.sharing_remove_title, person.name),
            stringResource(R.string.sharing_remove_from_app_message, person.name, app?.name ?: page.slug),
            stringResource(R.string.sharing_remove), onDismiss = { removing = null },
        ) {
            scope.launch {
                if (!state.confirm(resources.getString(R.string.sharing_remove_person, person.name))) return@launch
                val client = state.client(machine) ?: return@launch
                error = try {
                    client.removeGuest(person, page.slug).errors?.firstOrNull()
                } catch (failure: AdminError) {
                    failure.message
                }
                state.refresh()
            }
        }
    }
}

/** Cancels an invite no one has used: what went wrong, or null. */
private suspend fun cancel(state: SharingState, machine: OwnerSharing.Machine, invite: AdminInvite): String? {
    val client = state.client(machine) ?: return null
    val error = try {
        client.cancelInvite(invite.id)
        null
    } catch (failure: AdminError) {
        failure.message
    }
    state.refresh()
    return error
}

/**
 * Sharing one app while its dialog is open: who to invite, then the invite itself. The app
 * model holds it rather than the dialog, so the lock, which takes the screen apart, never loses
 * an invite's link, which is shown only once. It keeps the computer and app as they were when it
 * opened, so no refresh can empty it.
 */
@Stable
class InviteComposer(
    val machine: OwnerSharing.Machine,
    val app: AdminApp,
    /** Another device for someone already shared with; null for someone new or already shared with. */
    val person: GuestPerson? = null,
) {
    val name = TextFieldState()
    var working by mutableStateOf(false)
    var error by mutableStateOf<String?>(null)
    var result by mutableStateOf<AdminShareResult?>(null)

    val trimmed: String get() = name.text.toString().trimmingWhitespaceAndNewlines()
    val choices: List<GuestPerson> get() = GuestPerson.invitable(machine.guests)

    /** Someone already shared with or invited who has the typed name. */
    val namesake: GuestPerson? get() = GuestPerson.everyone(machine.guests).firstOrNull { it.name.equals(trimmed, ignoreCase = true) }

    /** A new person's name, as Create takes it. */
    val canCreate: Boolean get() = trimmed.isNotEmpty() && trimmed.graphemeCount() <= 64 && namesake == null

    /** Invites someone new by name, or, given [chosen], someone already shared with, once the screen lock says so. */
    suspend fun create(chosen: GuestPerson?, sharing: SharingState, reason: (who: String) -> String) {
        val who = chosen?.name ?: trimmed
        if (who.isEmpty() || (chosen == null && namesake != null) || working) return
        if (!sharing.confirm(reason(who))) return
        val client = sharing.client(machine) ?: run {
            error = AdminError.Unreachable.message
            return
        }
        working = true
        val made = try {
            if (chosen != null) client.createInvite(chosen, app.slug) else client.createInvite(to = trimmed, app = app.slug)
        } catch (failure: AdminError) {
            error = failure.message
            null
        } finally {
            working = false
        }
        if (made != null) {
            result = made
            sharing.refresh()
        }
    }
}

/** The invite composer over whatever opened it: People & sharing, an app's menu or an open app (see [InviteComposer]). */
@Composable
fun InviteComposerLayer(model: AppModel) {
    val composer by model.composer.collectAsState()
    val shown = composer ?: return
    InviteComposerDialog(shown, sharingState(model), model.scope, onDismiss = { if (model.composer.value === shown) model.composer.value = null })
}

/** Who to invite, then the invite itself, as a full-screen dialog; [scope] outlives it, as the composer does. */
@Composable
fun InviteComposerDialog(composer: InviteComposer, sharing: SharingState, scope: CoroutineScope, onDismiss: () -> Unit) {
    // As Send feedback: a stale keyboard's back callback would swallow the next Back.
    val keyboard = LocalSoftwareKeyboardController.current
    DisposableEffect(Unit) { onDispose { keyboard?.hide() } }
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false, dismissOnBackPress = !composer.working),
    ) {
        FullScreenDialogBars()
        val result = composer.result
        if (result != null) InviteResultView(result, composer.app.name, onDismiss) else InviteForm(composer, sharing, scope, onDismiss)
    }
}

@Composable
private fun InviteForm(composer: InviteComposer, sharing: SharingState, scope: CoroutineScope, onDismiss: () -> Unit) {
    val resources = LocalResources.current
    val app = composer.app
    val error = composer.error
    fun create(chosen: GuestPerson?) = scope.launch {
        composer.create(chosen, sharing) { who -> resources.getString(R.string.sharing_reason_share, app.name, who) }
    }
    val target = composer.person
    GroupedScreen(
        // Not while the invite is made, whose link shows only here.
        stringResource(R.string.sharing_share_title, app.name), onBack = { if (!composer.working) onDismiss() }, scroll = rememberScrollState(),
        navigationIcon = R.drawable.ic_close, navigationLabel = stringResource(R.string.sharing_close),
        actions = {
            if (composer.working) {
                val label = stringResource(R.string.sharing_creating)
                Box(Modifier.size(48.dp).semantics { contentDescription = label }, contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.5.dp)
                }
            } else {
                TextButton(onClick = { create(target) }, enabled = target != null || composer.canCreate) { Text(stringResource(R.string.sharing_create)) }
            }
        },
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(24.dp)) {
            if (target != null) {
                SettingsGroup(footer = { Footer(error ?: stringResource(R.string.sharing_adds_a_phone, target.name, app.name)) }) {
                    row { shape -> SettingsRow(shape, target.name, trailing = { Text(target.devices, color = MaterialTheme.colorScheme.onSurfaceVariant) }) }
                }
                return@Column
            }
            NewPersonField(composer, onDone = { create(null) })
            val choices = composer.choices
            if (choices.isNotEmpty()) {
                SettingsGroup(header = stringResource(R.string.sharing_shared_with), footer = { Footer(stringResource(R.string.sharing_shared_with_footer)) }) {
                    for (person in choices) {
                        row { shape ->
                            val value = when {
                                person.guests.any { it.app == app.slug } -> stringResource(R.string.sharing_add_a_device)
                                person.guests.isEmpty() -> stringResource(R.string.sharing_invited)
                                else -> person.devices
                            }
                            SettingsRow(
                                shape, person.name, trailing = { Text(value, color = MaterialTheme.colorScheme.onSurfaceVariant) },
                                onClick = { if (!composer.working) create(person) },
                            )
                        }
                    }
                }
            }
        }
    }
}

/** Someone new by name; under the field, what went wrong, a name already used, or what the name is for. */
@Composable
private fun NewPersonField(composer: InviteComposer, onDone: () -> Unit) {
    val app = composer.app
    val trimmed = composer.trimmed
    val namesake = composer.namesake
    val error = composer.error
    val focus = remember { FocusRequester() }
    // Here, in the dialog's own window, so the field is there to take focus.
    LaunchedEffect(Unit) { focus.requestFocus() }
    val supporting = when {
        error != null -> error
        namesake != null -> stringResource(
            if (composer.choices.any { it.id == namesake.id }) R.string.sharing_namesake_listed else R.string.sharing_namesake_invited, namesake.name,
        )
        trimmed.isEmpty() -> stringResource(R.string.sharing_new_footer_they, app.name)
        else -> stringResource(R.string.sharing_new_footer_named, trimmed, app.name)
    }
    Column {
        Text(
            stringResource(R.string.sharing_someone_new), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 8.dp),
        )
        OutlinedTextField(
            composer.name,
            label = { Text(stringResource(R.string.sharing_their_name)) },
            lineLimits = TextFieldLineLimits.SingleLine,
            isError = error != null || namesake != null,
            supportingText = { Text(supporting) },
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words, imeAction = ImeAction.Done),
            onKeyboardAction = { onDone() },
            modifier = Modifier.fillMaxWidth().focusRequester(focus),
        )
    }
}

/** A fresh invite: its code for in person, and the link to send, shown this once. */
@Composable
private fun InviteResultView(result: AdminShareResult, appName: String, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val haptics = LocalHapticFeedback.current
    val invite = result.invite
    var copied by remember { mutableStateOf(false) }
    val subject = stringResource(R.string.sharing_invite_subject, appName)
    val clipLabel = stringResource(R.string.sharing_invite_link)
    val code = remember(result.link) { result.link?.let(QRCode::image) }
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    GroupedScreen("", onBack = onDismiss, scroll = rememberScrollState(), navigationIcon = R.drawable.ic_close, navigationLabel = stringResource(R.string.sharing_close)) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 8.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(20.dp)) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(stringResource(R.string.sharing_invite_title, invite.to), style = OvenType.displayTitle, textAlign = TextAlign.Center)
                Text(
                    stringResource(R.string.sharing_invite_expires, invite.expiresIn, DateFormat.getTimeFormat(context).format(Date.from(invite.expires))),
                    style = MaterialTheme.typography.bodyMedium, color = muted, textAlign = TextAlign.Center,
                )
            }
            if (code != null) {
                val plate = RoundedCornerShape(20.dp)
                val image = remember(code) { code.asImageBitmap() }
                // Black on white in dark mode too, so any camera reads it.
                Image(
                    image, contentDescription = stringResource(R.string.sharing_invite_code), filterQuality = FilterQuality.None, contentScale = ContentScale.Fit,
                    modifier = Modifier.widthIn(max = 260.dp).fillMaxWidth().aspectRatio(1f).clip(plate).background(Color.White)
                        .border(0.5.dp, MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f), plate).padding(16.dp),
                )
                Text(stringResource(R.string.sharing_in_person), style = MaterialTheme.typography.bodySmall, color = muted, textAlign = TextAlign.Center)
            }
            Column(Modifier.widthIn(max = 400.dp).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(onClick = { sendInvite(context, result.message, subject) }, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(painterResource(R.drawable.ic_share), contentDescription = null)
                        Text(stringResource(R.string.sharing_send_invite), style = MaterialTheme.typography.titleMedium)
                    }
                }
                OutlinedButton(
                    onClick = {
                        copyLink(context, clipLabel, result.link.orEmpty())
                        if (!copied) haptics.performHapticFeedback(HapticFeedbackType.Confirm)
                        copied = true
                    },
                    modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
                ) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(painterResource(if (copied) R.drawable.ic_check else R.drawable.ic_content_copy), contentDescription = null)
                        Text(stringResource(if (copied) R.string.sharing_copied else R.string.sharing_copy_link))
                    }
                }
            }
            Text(stringResource(R.string.sharing_invite_footer, invite.to, appName), style = MaterialTheme.typography.bodySmall, color = muted, textAlign = TextAlign.Center)
        }
    }
}

/**
 * The invite through the Android Sharesheet: the connector's message, which ends with the link,
 * as its text, in a task of its own, since a target takes the person to another app. The link
 * rides only in that text, which no log line shows.
 */
private fun sendInvite(context: Context, message: String, subject: String) {
    val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, message)
        .putExtra(Intent.EXTRA_SUBJECT, subject).putExtra(Intent.EXTRA_TITLE, subject)
    context.startOrFalse(Intent.createChooser(send, null).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
}

/**
 * The link on the clipboard, marked sensitive so Android's clipboard preview hides it: it's a
 * live credential until used. Android can't expire it with the invite, as iOS does, so the
 * invite says to send it privately.
 */
private fun copyLink(context: Context, label: String, link: String) {
    val clip = ClipData.newPlainText(label, link)
    if (Build.VERSION.SDK_INT >= 33) clip.description.extras = PersistableBundle().apply { putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, true) }
    context.getSystemService(ClipboardManager::class.java).setPrimaryClip(clip)
}

object QRCode {
    /** [text] as a QR code (error correction M), one pixel to a module, for drawing larger without smoothing. */
    fun image(text: String): Bitmap? {
        val matrix = try {
            // The plate around it is the quiet zone.
            QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, 0, 0, mapOf(EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M, EncodeHintType.MARGIN to 0))
        } catch (_: WriterException) {
            return null
        }
        val pixels = IntArray(matrix.width * matrix.height) { if (matrix[it % matrix.width, it / matrix.width]) android.graphics.Color.BLACK else android.graphics.Color.WHITE }
        return Bitmap.createBitmap(pixels, matrix.width, matrix.height, Bitmap.Config.ARGB_8888)
    }
}

/** Notes and screenshots people sent from Ovenlight, newest first, across computers or for one app. */
@Composable
private fun FeedbackInbox(state: SharingState, page: SharingPage.Feedback, scroll: ScrollState, shots: ScreenshotCache, open: (SharingPage) -> Unit, back: () -> Unit) {
    val machines by state.machines.collectAsState()
    val items = machines.flatMap { machine -> machine.feedback.map { machine to it } }
        .filter { (machine, item) -> page.machineID == null || (machine.id == page.machineID && item.app == page.slug) }
        .sortedByDescending { it.second.at }
    GroupedScreen(stringResource(R.string.sharing_feedback), onBack = back, scroll = scroll) {
        if (items.isEmpty()) return@GroupedScreen Unavailable(stringResource(R.string.feedback_inbox_none), stringResource(R.string.feedback_inbox_none_detail))
        SettingsGroup {
            for ((machine, item) in items) {
                row { shape ->
                    SettingsRow(
                        shape, item.note.ifEmpty { stringResource(R.string.feedback_inbox_screenshot_only) },
                        supporting = feedbackMeta(machine, item),
                        color = if (item.note.isEmpty()) MaterialTheme.colorScheme.onSurfaceVariant else Color.Unspecified, maxLines = 3,
                        leading = if (item.hasScreenshot) ({ Thumbnail(state, machine, item, shots) }) else null,
                        onClick = { open(SharingPage.FeedbackItem(machine.id, item.id)) },
                    )
                }
            }
        }
    }
}

@Composable
private fun Thumbnail(state: SharingState, machine: OwnerSharing.Machine, item: AdminFeedback, shots: ScreenshotCache) {
    LaunchedEffect(item.id) { shots.load(item.id, ScreenshotCache.Size.Thumbnail, state.client(machine)) }
    val shape = RoundedCornerShape(6.dp)
    val image = shots.image(item.id, ScreenshotCache.Size.Thumbnail)
    val bitmap = remember(image) { image?.asImageBitmap() }
    Box(
        Modifier.size(44.dp, 72.dp).clip(shape).background(MaterialTheme.colorScheme.surfaceContainerHighest)
            .border(0.5.dp, MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f), shape),
    ) {
        bitmap?.let { Image(it, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.size(44.dp, 72.dp)) }
    }
}

/** "Sam · House Guide · 2 hours ago". */
@Composable
private fun feedbackMeta(machine: OwnerSharing.Machine, item: AdminFeedback): String {
    val app = machine.apps.firstOrNull { it.slug == item.app }?.name ?: item.app
    val who = if (item.isFromGuest) item.from else stringResource(R.string.feedback_inbox_you)
    return "$who · $app · ${RelativePhrase.relative(item.at, Instant.now(), ZoneId.systemDefault(), RelativePhrase.allUnits)}"
}

/** One piece of feedback: the note, who sent it from where, and the screenshot. */
@Composable
private fun FeedbackDetail(state: SharingState, page: SharingPage.FeedbackItem, scroll: ScrollState, shots: ScreenshotCache, back: () -> Unit) {
    val machines by state.machines.collectAsState()
    val machine = machines.firstOrNull { it.id == page.machineID }
    val item = machine?.feedback?.firstOrNull { it.id == page.id }
    val context = LocalContext.current
    LaunchedEffect(item?.id) { if (machine != null && item != null) shots.load(item.id, ScreenshotCache.Size.Screen, state.client(machine)) }
    val title = item?.let { found -> machine.apps.firstOrNull { it.slug == found.app }?.name ?: found.app }.orEmpty()
    GroupedScreen(title, onBack = back, scroll = scroll) {
        if (item == null) return@GroupedScreen
        Column(Modifier.padding(horizontal = 4.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            if (item.note.isNotEmpty()) SelectionContainer { Text(item.note, style = MaterialTheme.typography.bodyLarge) }
            SelectionContainer {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    val small = MaterialTheme.typography.bodySmall
                    val muted = MaterialTheme.colorScheme.onSurfaceVariant
                    Text(stringResource(if (item.isFromGuest) R.string.feedback_inbox_from_guest else R.string.feedback_inbox_from, item.from), style = small, color = muted)
                    item.device?.takeIf { it.isNotEmpty() }?.let { Text(it, style = small, color = muted) }
                    Text(sentAt(context, item.at), style = small, color = muted)
                    item.pageUrl?.takeIf { it.isNotEmpty() }?.let { Text(it, style = small, color = muted, maxLines = 2) }
                }
            }
            if (item.hasScreenshot) {
                val image = shots.image(item.id, ScreenshotCache.Size.Screen)
                val bitmap = remember(image) { image?.asImageBitmap() }
                if (bitmap != null) {
                    val shape = RoundedCornerShape(14.dp)
                    Image(
                        bitmap, contentDescription = stringResource(R.string.feedback_inbox_screenshot), contentScale = ContentScale.Fit,
                        modifier = Modifier.fillMaxWidth().clip(shape).border(0.5.dp, MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f), shape),
                    )
                } else {
                    Box(Modifier.fillMaxWidth().heightIn(min = 200.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                }
            }
        }
    }
}

/** "Oct 17, 2026 at 4:30 PM", the time as the phone shows times. */
private fun sentAt(context: Context, at: Instant): String =
    "${DateTimeFormatter.ofPattern("MMM d, yyyy", Locale.US).format(at.atZone(ZoneId.systemDefault()))} at ${DateFormat.getTimeFormat(context).format(Date.from(at))}"

/**
 * Screenshots fetched from the connectors, kept while People & sharing is open: small for the
 * list, screen-sized for the one open, never at full size, since a guest chooses what the
 * connector stores.
 */
@Stable
class ScreenshotCache {
    /** The most pixels on the long side: a row's thumbnail, and the largest screen, rounded up. */
    enum class Size(val pixels: Int) {
        Thumbnail(240),
        Screen(3000),
    }

    private val images = mutableStateMapOf<Pair<String, Size>, Bitmap>()
    private val loading = mutableSetOf<Pair<String, Size>>()

    fun image(id: String, size: Size): Bitmap? = images[id to size]

    suspend fun load(id: String, size: Size, client: AdminClient?) {
        val key = id to size
        if (key in images || key in loading || client == null) return
        loading += key
        try {
            val data = try {
                client.screenshot(id)
            } catch (_: AdminError) {
                return
            }
            withContext(Dispatchers.Default) { IconDecoder.decode(data, size.pixels) }?.let { images[key] = it }
        } finally {
            loading -= key
        }
    }
}
