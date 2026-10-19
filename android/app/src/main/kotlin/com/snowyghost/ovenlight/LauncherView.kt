package com.snowyghost.ovenlight

import android.graphics.Bitmap
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuDefaults
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import java.net.URI
import java.util.UUID
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * What the launcher shows and what its controls do, made from the model each time it changes
 * (see [LauncherView]), so tests can give it their own.
 */
data class LauncherState(
    val apps: List<WebApp> = emptyList(),
    val ownerEnabled: Boolean = false,
    /** The owner's node, Idle while Ovenlight isn't set up for their own computers. */
    val ownerState: NodeState = NodeState.Idle,
    /** This phone as the owner's tailnet lists it, for the approval card. */
    val deviceName: String = "",
    /** The owner was connected before and didn't sign out, so a sign-in card means theirs ended. */
    val signedInBefore: Boolean = false,
    /** The phone has no network (see [TailnetCore.online]). */
    val phoneOffline: Boolean = false,
    val memberships: List<Membership> = emptyList(),
    /** Invites being accepted this session, by invite ID. */
    val phases: Map<String, JoinPhase> = emptyMap(),
    /** The apps whose computers aren't answering. */
    /** Apps whose tile says their computer is offline. */
    val offline: Set<UUID> = emptySet(),
    /** Goes up with each icon saved, so a refreshed icon redraws. */
    val iconRevisions: Map<UUID, Int> = emptyMap(),
    /** A screen or an open app covers the launcher: nothing in it takes keyboard focus or TalkBack's. */
    val covered: Boolean = false,
    val icon: (WebApp) -> Bitmap? = { null },
    val lock: () -> Unit = {},
    val present: (RootSheet) -> Unit = {},
    val open: (WebApp) -> Unit = {},
    /** Try again, after the owner's node failed. */
    val retry: () -> Unit = {},
    val enableOwner: () -> Unit = {},
    val move: (UUID, Int) -> Unit = { _, _ -> },
    val rename: (UUID, String) -> Unit = { _, _ -> },
    val refreshIcon: (UUID) -> Unit = {},
    /** Pins an app to the phone's home screen; null where the launcher can't. */
    val addToHomeScreen: ((WebApp) -> Unit)? = null,
    val clearData: (UUID) -> Unit = {},
    val remove: (UUID) -> Unit = {},
    /** Leaves a guest membership: its apps, their data and its node. */
    val leave: (String) -> Unit = {},
    val problemReport: (WebApp) -> MailDraft? = { null },
    val openLink: (String) -> Unit = {},
    /** The connector's view of one of the owner's apps (see [OwnerSharing.info]), for its menu's Share and People. */
    val sharing: (WebApp) -> Pair<OwnerSharing.Machine, AdminApp>? = { null },
    /** A menu opened: its People count and Share come up to date. */
    val refreshSharing: () -> Unit = {},
    val share: (OwnerSharing.Machine, AdminApp) -> Unit = { _, _ -> },
) {
    /** Nothing saved yet: the welcome, in place of the grid. */
    val showsWelcome: Boolean get() = apps.isEmpty() && !ownerEnabled && memberships.isEmpty()

    /** People & Sharing, for an owner who is connected and whose connectors Ovenlight found. */
    val showsSharing: Boolean get() = ownerEnabled && ownerState == NodeState.Ready && apps.any { !it.isShared && it.discovered }

    val pendingInvites: List<InviteLink> get() = memberships.flatMap { m -> m.pendingClaims.map { it.invite } }
}

/** The home screen of apps (LauncherView.swift), from the model. */
@Composable
fun LauncherView(model: AppModel) {
    val nodes = model.nodes
    val registry = model.registry
    val context = LocalContext.current
    val apps by registry.appsFlow.collectAsState()
    val ownerEnabled by nodes.ownerEnabled.collectAsState()
    val ownerState by nodes.owner.state.collectAsState()
    val ownerStatus by nodes.owner.status.collectAsState()
    val ownerAnswers by nodes.owner.answers.collectAsState()
    val online by TailnetCore.online.collectAsState()
    val guestNodes by nodes.guests.collectAsState()
    // Each node's state and answers, so a tile dims or brightens as its computer comes and goes.
    val guestStates = guestNodes.values.map { node -> key(node.id) { node.state.collectAsState().value to node.answers.collectAsState().value } }
    val memberships by nodes.memberships.membershipsFlow.collectAsState()
    val phases by model.guests.phases.collectAsState()
    val iconRevisions by registry.iconRevisions.collectAsState()
    val sheet by model.router.sheet.collectAsState()
    val openApp by model.router.openApp.collectAsState()
    // The menus' Share and People follow what the connectors report.
    val machines by model.sharing.machines.collectAsState()
    val offline = remember(apps, ownerState, ownerStatus, ownerAnswers, guestStates) {
        apps.filter(nodes::isOffline).map { it.id }.toSet()
    }
    val scope = model.scope
    val canPin = remember { HomeShortcuts.isSupported(context) }
    LauncherView(
        LauncherState(
            apps = apps,
            ownerEnabled = ownerEnabled,
            // The owner's own connection only once they set Ovenlight up for their computers.
            ownerState = if (ownerEnabled) ownerState else NodeState.Idle,
            deviceName = NodeManager.deviceName(ownerStatus, nodes.hostName),
            signedInBefore = nodes.owner.magicDNSSuffixSeen != null,
            phoneOffline = !online,
            memberships = memberships,
            phases = phases,
            offline = offline,
            iconRevisions = iconRevisions,
            covered = sheet?.isScreen == true || openApp != null,
            icon = { IconDecoder.load(registry.iconFile(it)) },
            lock = model.lock::lock,
            present = model.router::present,
            open = model.router::open,
            retry = { scope.launch { nodes.restart() } },
            enableOwner = nodes::enableOwner,
            move = registry::move,
            rename = registry::rename,
            refreshIcon = { scope.launch { registry.refreshMetadata(it) } },
            addToHomeScreen = { app: WebApp -> HomeShortcuts.request(context, app, IconDecoder.load(registry.iconFile(app))) }.takeIf { canPin },
            clearData = { scope.launch { registry.clearData(it) } },
            remove = { scope.launch { registry.remove(it) } },
            leave = { scope.launch { model.guests.leave(it) } },
            problemReport = { app -> MailDraft.problemReport(context, nodes, model.pool, app) },
            openLink = { CustomTabs.open(context, URI(it), model.lifecycle) },
            sharing = remember(machines) { { app: WebApp -> model.sharing.info(app) } },
            refreshSharing = { scope.launch { model.refreshSharing() } },
            share = { machine, app -> model.composer.value = InviteComposer(machine, app) },
        ),
    )
}

/**
 * The home screen of apps: the oven's interior with its light on, the lock and the menu on top,
 * the owner's sign-in or approval card and any invite still joining, then the apps. Before
 * anything is saved, the welcome takes the grid's place.
 */
@Composable
fun LauncherView(state: LauncherState) {
    val colors = MaterialTheme.colorScheme
    val haptics = LocalHapticFeedback.current
    val context = LocalContext.current
    // By ID, so the lock, which takes the launcher apart, keeps a rename and its text.
    var renamingID by rememberSaveable { mutableStateOf<UUID?>(null) }
    val renaming = state.apps.firstOrNull { it.id == renamingID }
    var clearing by remember { mutableStateOf<WebApp?>(null) }
    var removing by remember { mutableStateOf<WebApp?>(null) }
    var leaving by remember { mutableStateOf<Membership?>(null) }
    var noMailApp by remember { mutableStateOf<MailDraft?>(null) }
    // Covered by a screen or an app, the launcher stays composed beneath it. Touch doesn't reach
    // it, and this keeps TalkBack and a keyboard's Tab off its hidden buttons and tiles, which an
    // open app's band, drawn over the top bar, would otherwise leave reachable.
    val focus = if (state.covered) Modifier.clearAndSetSemantics {}.focusProperties { onEnter = { cancelFocusChange() } }.focusGroup() else Modifier
    // And focus already inside, on More just tapped from a keyboard, say, lets go as it's covered,
    // as does a dialog of its own, which would stay over an app a link opens. Only focus on
    // Ovenlight's own controls: an app's page takes focus as it opens, and keeps it.
    val focusManager = LocalFocusManager.current
    val view = LocalView.current
    LaunchedEffect(state.covered) {
        if (!state.covered) return@LaunchedEffect
        if (view.isFocused) focusManager.clearFocus(force = true)
        renamingID = null
        clearing = null
        removing = null
        leaving = null
        noMailApp = null
    }
    Box(Modifier.fillMaxSize().background(LocalOvenColors.current.ovenInterior).then(focus)) {
        LampLight(level = 0.6f)
        CompositionLocalProvider(LocalContentColor provides colors.onSurface) {
            Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
                HomeTopBar(state)
                if (state.showsWelcome) {
                    LauncherWelcome(
                        join = { state.present(RootSheet.Join(JoinRequest())) }, useOwnComputers = state.enableOwner,
                        modifier = Modifier.weight(1f),
                    )
                } else {
                    HomeScreenGrid(
                        apps = state.apps, ownerEnabled = state.ownerEnabled, icon = state.icon, iconRevisions = state.iconRevisions,
                        offline = state.offline, open = state.open, move = state.move,
                        addApp = { state.present(RootSheet.AddApp) }, openLink = state.openLink,
                        modifier = Modifier.weight(1f), covered = state.covered,
                        header = {
                            TailnetBanner(state, connect = { state.present(RootSheet.Login(it)) }, approve = { state.present(RootSheet.Approve) })
                            PendingInvitesBanner(
                                state.pendingInvites, state.phases, open = { state.present(RootSheet.Join(JoinRequest(Result.success(it)))) },
                                modifier = Modifier.padding(horizontal = 24.dp),
                            )
                        },
                    ) { app, close ->
                        AppMenu(
                            SharingFixture.apply(app), close, state,
                            rename = { renamingID = app.id },
                            refreshIcon = { state.refreshIcon(app.id) },
                            reportProblem = { state.problemReport(app)?.let { if (!it.send(context)) noMailApp = it } },
                            leave = { leaving = state.memberships.firstOrNull { it.id == app.membershipID } },
                            clearData = { clearing = app },
                            remove = { removing = app },
                        )
                    }
                }
            }
        }
    }

    renaming?.let { app -> RenameDialog(app, onDismiss = { renamingID = null }) { state.rename(app.id, it) } }
    clearing?.let { app ->
        Confirmation(
            title = stringResource(R.string.launcher_clear_data_title, app.name), message = stringResource(R.string.launcher_clear_data_message),
            confirm = stringResource(R.string.launcher_clear_data), onDismiss = { clearing = null },
        ) { state.clearData(app.id) }
    }
    removing?.let { app ->
        Confirmation(
            title = stringResource(R.string.remove_title, app.name),
            message = app.sharedBy?.let(ConnectionCopy::removeSharedDetail)
                // One found on the owner's computers stays removed: discovery skips its host from now on.
                ?: stringResource(if (app.discovered) R.string.launcher_remove_discovered_message else R.string.launcher_remove_message),
            confirm = stringResource(R.string.launcher_remove_app), onDismiss = { removing = null },
        ) {
            haptics.performHapticFeedback(HapticFeedbackType.Confirm)
            state.remove(app.id)
        }
    }
    leaving?.let { membership ->
        LeaveConfirmation(membership.ownerName, onDismiss = { leaving = null }) { state.leave(membership.id) }
    }
    noMailApp?.let { NoEmailAppDialog(it, onDismiss = { noMailApp = null }) }
}

/** The text in the top row and the pill stops growing at 1.3 times, so the row keeps its height. */
@Composable
internal fun CappedFontScale(content: @Composable () -> Unit) {
    val density = LocalDensity.current
    val capped = remember(density) { if (density.fontScale > 1.3f) Density(density.density, 1.3f) else density }
    CompositionLocalProvider(LocalDensity provides capped, content = content)
}

/**
 * The launcher's only controls, bare icons as on a phone's home screen: Lock on the start side
 * and the menu on the end, with Ovenlight's connection between them while it matters.
 */
@Composable
private fun HomeTopBar(state: LauncherState) {
    CappedFontScale {
        // Icons 16 dp from the edges, as in a top app bar.
        Row(Modifier.fillMaxWidth().height(56.dp).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            // Nothing is saved while the welcome shows, so there's nothing to lock. Its spot stays, keeping the pill centered.
            Box(Modifier.size(48.dp)) {
                if (!state.showsWelcome) {
                    IconButton(onClick = state.lock) {
                        Icon(painterResource(R.drawable.ic_lock), contentDescription = stringResource(R.string.lock_ovenlight))
                    }
                }
            }
            Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                TailnetStatusPill(state.ownerState, state.phoneOffline, state.retry)
            }
            Box {
                var expanded by remember { mutableStateOf(false) }
                // As the launcher's dialogs: covered, the menu goes rather than stay over what covers it.
                LaunchedEffect(state.covered) { if (state.covered) expanded = false }
                IconButton(onClick = { expanded = true }) {
                    Icon(painterResource(R.drawable.ic_more_vert), contentDescription = stringResource(R.string.launcher_more))
                }
                RoundedMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                    val close = { expanded = false }
                    // Add app opens only the owner's own computers' apps.
                    if (state.ownerEnabled) AppMenuItem(close, stringResource(R.string.launcher_add_app), R.drawable.ic_add) { state.present(RootSheet.AddApp) }
                    AppMenuItem(close, ConnectionCopy.joinWithInvite, R.drawable.ic_person_add) { state.present(RootSheet.Join(JoinRequest())) }
                    if (state.showsSharing) {
                        AppMenuItem(close, stringResource(R.string.launcher_people_sharing), R.drawable.ic_group) { state.present(RootSheet.Sharing()) }
                    }
                    HorizontalDivider()
                    AppMenuItem(close, stringResource(R.string.settings), R.drawable.ic_settings) { state.present(RootSheet.Settings()) }
                }
            }
        }
    }
}

/** What the pill between the corner controls says. */
private sealed interface Pill {
    data object Connecting : Pill
    data object Offline : Pill
    data class Failed(val message: String) : Pill
}

/**
 * Connecting, or You're offline while the phone has no network, or a failure with a retry, in a
 * small pill between the corner controls, fading from one to the next. Nothing when connected or
 * off; signing in and approval have a card instead.
 */
@Composable
private fun TailnetStatusPill(state: NodeState, phoneOffline: Boolean, retry: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    // Only after a beat busy, as an app's placeholder waits, so a quick reconnect never shows it.
    var busyAWhile by remember { mutableStateOf(false) }
    LaunchedEffect(state.isBusy) {
        busyAWhile = false
        if (state.isBusy) {
            delay(700)
            busyAWhile = true
        }
    }
    val shown = when {
        state.isBusy -> if (!busyAWhile) null else if (phoneOffline) Pill.Offline else Pill.Connecting
        state is NodeState.Failed -> Pill.Failed(state.message)
        else -> null
    }
    val pill = Modifier.minimumInteractiveComponentSize().height(32.dp).clip(CircleShape).background(colors.surfaceContainerHigh)
    val text = MaterialTheme.typography.labelLarge
    AnimatedContent(shown, transitionSpec = { fadeIn() togetherWith fadeOut() }, contentAlignment = Alignment.Center, label = "pill") { current ->
        when (current) {
            null -> Unit
            Pill.Connecting, Pill.Offline -> Row(
                pill.semantics(mergeDescendants = true) {}.padding(horizontal = 14.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically,
            ) {
                if (current == Pill.Offline) {
                    Icon(painterResource(R.drawable.ic_wifi_off), contentDescription = null, tint = colors.onSurfaceVariant, modifier = Modifier.size(16.dp))
                } else {
                    CircularProgressIndicator(Modifier.size(16.dp).clearAndSetSemantics {}, strokeWidth = 2.dp)
                }
                Text(if (current == Pill.Offline) ConnectionCopy.offline else ConnectionCopy.connecting, style = text, color = colors.onSurfaceVariant, maxLines = 1)
            }
            is Pill.Failed -> {
                val tryAgain = stringResource(R.string.try_again)
                Row(
                    // Read as what it shows, "Try again" included.
                    pill.clickable(role = Role.Button, onClick = retry).padding(horizontal = 14.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(painterResource(R.drawable.ic_warning), contentDescription = null, tint = colors.tertiary, modifier = Modifier.size(18.dp))
                    // The message gives way to Try again when the pill runs out of room.
                    Text(current.message, style = text, color = colors.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                    Text(tryAgain, style = text, color = colors.primary, maxLines = 1)
                }
            }
        }
    }
}

/**
 * The owner's sign-in or approval card, above the apps until Ovenlight is connected to their
 * tailnet. The only place on the launcher that names Tailscale.
 */
@Composable
private fun TailnetBanner(state: LauncherState, connect: (URI) -> Unit, approve: () -> Unit) {
    val card = Modifier.padding(horizontal = 24.dp, vertical = 6.dp)
    when (val node = state.ownerState) {
        // Once connected before, without a sign-out since, it says the sign-in ended.
        is NodeState.NeedsLogin -> {
            val again = state.signedInBefore
            LauncherCard(
                R.drawable.ic_lan, stringResource(if (again) R.string.launcher_sign_in_again_title else R.string.launcher_connect_title),
                AnnotatedString(stringResource(if (again) R.string.launcher_sign_in_again_body else R.string.launcher_connect_body)),
                stringResource(if (again) R.string.app_sign_in else R.string.launcher_connect), card,
            ) { connect(node.url) }
        }
        NodeState.AwaitingApproval -> LauncherCard(
            R.drawable.ic_verified_user, stringResource(R.string.approve_this_phone), approveBody(state.deviceName), stringResource(R.string.launcher_open_machines),
            card, action = approve,
        )
        else -> Unit
    }
}

/** How to approve this phone, its name in bold. */
@Composable
internal fun approveBody(deviceName: String): AnnotatedString {
    val body = stringResource(R.string.launcher_approve_body, deviceName)
    val start = body.indexOf(deviceName)
    return buildAnnotatedString {
        append(body)
        if (deviceName.isNotEmpty() && start >= 0) addStyle(SpanStyle(fontWeight = FontWeight.Bold), start, start + deviceName.length)
    }
}

/** The launcher's cards, and an invite joining: white on the light, a step up from the dark. */
internal val cardColor: Color
    @Composable get() = MaterialTheme.colorScheme.let { if (isSystemInDarkTheme()) it.surfaceContainerHigh else it.surfaceContainerLowest }

/** A step the owner takes, on the launcher or over an open app: its icon and title, what to do, the button, and [below] that. */
@Composable
internal fun LauncherCard(
    icon: Int,
    title: String,
    body: AnnotatedString?,
    button: String,
    modifier: Modifier = Modifier,
    container: Color = cardColor,
    below: @Composable ColumnScope.() -> Unit = {},
    action: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    ElevatedCard(shape = RoundedCornerShape(28.dp), colors = CardDefaults.elevatedCardColors(containerColor = container), modifier = modifier.fillMaxWidth()) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(40.dp).background(colors.primaryContainer, CircleShape), contentAlignment = Alignment.Center) {
                    Icon(painterResource(icon), contentDescription = null, tint = colors.onPrimaryContainer)
                }
                Text(title, style = MaterialTheme.typography.titleMedium, color = colors.onSurface, modifier = Modifier.semantics { heading() })
            }
            body?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant) }
            Button(onClick = action, modifier = Modifier.fillMaxWidth().height(56.dp)) {
                Text(button, style = MaterialTheme.typography.titleMedium)
            }
            below()
        }
    }
}

/**
 * An app's touch-and-hold menu: a header naming it and where it comes from (iOS's preview
 * card), then what the person can do with it, the destructive actions last.
 */
@Composable
private fun ColumnScope.AppMenu(
    app: WebApp,
    close: () -> Unit,
    state: LauncherState,
    rename: () -> Unit,
    refreshIcon: () -> Unit,
    reportProblem: () -> Unit,
    leave: () -> Unit,
    clearData: () -> Unit,
    remove: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    // The menu is open: bring its People count and Share up to date.
    LaunchedEffect(Unit) { if (!app.isShared) state.refreshSharing() }
    val info = state.sharing(app)
    val plan = AppMenuPlan.plan(app, admin = info?.let { (machine, admin) -> admin to machine.guests }, apps = state.apps)
    Column(Modifier.widthIn(max = 280.dp).padding(horizontal = 12.dp, vertical = 8.dp).semantics(mergeDescendants = true) { heading() }) {
        Text(app.name, style = MaterialTheme.typography.titleSmall, color = colors.onSurface, maxLines = 2, overflow = TextOverflow.Ellipsis)
        Text(
            app.sharedBy?.let(ConnectionCopy::sharedBy) ?: app.host, style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant,
            maxLines = 1, overflow = TextOverflow.Ellipsis,
        )
    }
    state.addToHomeScreen?.let { pin ->
        AppMenuItem(close, stringResource(R.string.launcher_add_to_home_screen), R.drawable.ic_add_to_home_screen) { pin(app) }
    }
    AppMenuItem(close, stringResource(R.string.launcher_rename), R.drawable.ic_edit, action = rename)
    AppMenuItem(close, stringResource(R.string.launcher_refresh_icon), R.drawable.ic_refresh, action = refreshIcon)
    if (plan != AppMenuPlan()) {
        HorizontalDivider()
        when (plan.share) {
            AppMenuPlan.Share.Available -> info?.let { (machine, admin) ->
                AppMenuItem(close, stringResource(R.string.share_ellipsis), R.drawable.ic_share) { state.share(machine, admin) }
            }
            AppMenuPlan.Share.NotShareable -> DropdownMenuItem(
                text = {
                    Column {
                        Text(stringResource(R.string.share_ellipsis))
                        info?.let { Text(AppMenuPlan.notShareableDetail(it.second.slug), style = MaterialTheme.typography.bodySmall) }
                    }
                },
                leadingIcon = { Icon(painterResource(R.drawable.ic_share), contentDescription = null) },
                onClick = {},
                enabled = false,
            )
            AppMenuPlan.Share.Hidden -> Unit
        }
        val people = plan.people
        if (people != null && info != null) {
            AppMenuItem(close, AppMenuPlan.peopleTitle(people), R.drawable.ic_group) { state.present(RootSheet.People(info.first.id, info.second.slug)) }
        }
        if (plan.reportProblem) AppMenuItem(close, ConnectionCopy.reportProblem, R.drawable.ic_flag, action = reportProblem)
        plan.leaveOwner?.let { AppMenuItem(close, ConnectionCopy.leaveApps(it), R.drawable.ic_logout, destructive = true, action = leave) }
    }
    HorizontalDivider()
    AppMenuItem(close, stringResource(R.string.launcher_clear_data), R.drawable.ic_ink_eraser, action = clearData)
    AppMenuItem(close, stringResource(R.string.launcher_remove_app), R.drawable.ic_delete, destructive = true, action = remove)
}

@Composable
private fun RenameDialog(app: WebApp, onDismiss: () -> Unit, save: (String) -> Unit) {
    val name = rememberTextFieldState(app.name)
    val focus = remember { FocusRequester() }
    // Only spaces is no name.
    val trimmed = name.text.toString().trimmingWhitespaceAndNewlines()
    val done = {
        if (trimmed.isNotEmpty()) save(trimmed)
        onDismiss()
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.launcher_rename_title)) },
        text = {
            OutlinedTextField(
                name, label = { Text(stringResource(R.string.launcher_name)) }, lineLimits = TextFieldLineLimits.SingleLine,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done), onKeyboardAction = { done() },
                modifier = Modifier.focusRequester(focus),
            )
            // Here, in the dialog's own window, so the field is there to take focus.
            LaunchedEffect(Unit) { focus.requestFocus() }
        },
        confirmButton = { TextButton(onClick = done, enabled = trimmed.isNotEmpty()) { Text(stringResource(R.string.save)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

/** Add app (AddAppView in LauncherView.swift), from the model. */
@Composable
fun AddAppView(model: AppModel, onClose: () -> Unit) {
    val nodes = model.nodes
    val registry = model.registry
    val ownerEnabled by nodes.ownerEnabled.collectAsState()
    val ownerState by nodes.owner.state.collectAsState()
    val ownerStatus by nodes.owner.status.collectAsState()
    // The owner's tailnet, the only place Add app opens apps from. Null until they sign in.
    val ownerSuffix = remember(ownerEnabled, ownerStatus) { if (ownerEnabled) nodes.owner.magicDNSSuffixSeen else null }
    AddAppDialog(
        ownerSuffix, ownerEnabled, ownerState,
        add = { url ->
            val listed = registry.apps
            val app = registry.add(url)
            if (app in listed) {
                // Already here: it opens rather than coming twice.
                model.router.open(app)
            } else {
                // An app discovery already found (one removed, then added back) joins it at once.
                model.scope.launch { registry.mergeDiscovered(nodes.owner.discovered.value) }
            }
        },
        useOwnComputers = nodes::enableOwner, present = model.router::present, onClose = onClose,
    )
}

/**
 * Add app: an https address on the owner's own computers, once they're connected to them.
 * Before that it says to connect first, with the next step; apps anywhere else come by invite.
 */
@Composable
fun AddAppDialog(
    ownerSuffix: String?,
    ownerEnabled: Boolean,
    ownerState: NodeState,
    add: (URI) -> Unit,
    useOwnComputers: () -> Unit,
    present: (RootSheet) -> Unit,
    onClose: () -> Unit,
) {
    val field = rememberTextFieldState()
    val text = field.text.toString()
    // The address Add was tapped with that isn't on the owner's own computers, until it's changed.
    var refused by remember { mutableStateOf<String?>(null) }
    val haptics = LocalHapticFeedback.current
    val submit = {
        val url = AppAddress.ownURL(text, ownerSuffix)
        if (url == null) {
            refused = text.takeIf { AppAddress.url(it, ownerSuffix) != null }
        } else {
            add(url)
            haptics.performHapticFeedback(HapticFeedbackType.Confirm)
            onClose()
        }
    }
    // Before the owner is connected, the way there.
    val next: Pair<String, () -> Unit>? = when {
        ownerSuffix != null -> null
        !ownerEnabled -> stringResource(R.string.use_my_own_computers) to {
            useOwnComputers()
            onClose()
        }
        ownerState is NodeState.NeedsLogin -> stringResource(R.string.sign_in_to_your_computers) to { present(RootSheet.Login(ownerState.url)) }
        ownerState == NodeState.AwaitingApproval -> stringResource(R.string.approve_this_phone) to { present(RootSheet.Approve) }
        else -> null
    }
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text(stringResource(R.string.launcher_add_app)) },
        text = {
            if (ownerSuffix == null) return@AlertDialog Text(AppAddress.signInFirst)
            val warning = when {
                text.trimmingWhitespace().lowercase().startsWith("http://") -> stringResource(R.string.add_app_insecure)
                refused == text -> AppAddress.notYourComputer
                else -> null
            }
            val focus = remember { FocusRequester() }
            OutlinedTextField(
                field,
                label = { Text(stringResource(R.string.add_app_address)) },
                placeholder = { Text("my-app.$ownerSuffix") },
                lineLimits = TextFieldLineLimits.SingleLine,
                isError = warning != null,
                supportingText = { if (warning == null) Text(stringResource(R.string.add_app_footer)) else FieldWarning(warning) },
                keyboardOptions = KeyboardOptions(
                    capitalization = KeyboardCapitalization.None, autoCorrectEnabled = false, keyboardType = KeyboardType.Uri, imeAction = ImeAction.Done,
                ),
                onKeyboardAction = { submit() },
                modifier = Modifier.focusRequester(focus),
            )
            // Here, in the dialog's own window, so the field is there to take focus.
            LaunchedEffect(Unit) { focus.requestFocus() }
        },
        confirmButton = {
            if (ownerSuffix != null) {
                TextButton(onClick = submit, enabled = AppAddress.url(text, ownerSuffix) != null) { Text(stringResource(R.string.add_app_add)) }
            } else {
                next?.let { (label, action) -> TextButton(onClick = action) { Text(label) } }
            }
        },
        dismissButton = { TextButton(onClick = onClose) { Text(stringResource(R.string.cancel)) } },
    )
}

/** A row of a menu, which closes the menu before it acts; a destructive one is in the error color. */
@Composable
internal fun AppMenuItem(close: () -> Unit, label: String, icon: Int, destructive: Boolean = false, action: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    DropdownMenuItem(
        text = { Text(label) },
        leadingIcon = { Icon(painterResource(icon), contentDescription = null) },
        onClick = {
            close()
            action()
        },
        colors = if (destructive) MenuDefaults.itemColors(textColor = colors.error, leadingIconColor = colors.error) else MenuDefaults.itemColors(),
    )
}

/** Every menu in Ovenlight: a DropdownMenu with Material 3 Expressive's rounder corners. */
@Composable
internal fun RoundedMenu(expanded: Boolean, onDismissRequest: () -> Unit, content: @Composable ColumnScope.() -> Unit) =
    DropdownMenu(expanded = expanded, onDismissRequest = onDismissRequest, shape = RoundedCornerShape(16.dp), content = content)

/** A confirmation whose button is in the error color, as iOS marks destructive ones red. */
@Composable
internal fun Confirmation(
    title: String,
    message: String?,
    confirm: String,
    onDismiss: () -> Unit,
    cancel: String = stringResource(R.string.cancel),
    action: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = message?.let { { Text(it) } },
        confirmButton = {
            TextButton(
                onClick = {
                    onDismiss()
                    action()
                },
                colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
            ) { Text(confirm) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(cancel) } },
    )
}

/** Leaving an owner's apps, asked the same way from the launcher, an app's menu and Settings. */
@Composable
internal fun LeaveConfirmation(owner: String, onDismiss: () -> Unit, leave: () -> Unit) = Confirmation(
    ConnectionCopy.leaveTitle(owner), ConnectionCopy.leaveDetail(owner), stringResource(R.string.leave), onDismiss, action = leave,
)
