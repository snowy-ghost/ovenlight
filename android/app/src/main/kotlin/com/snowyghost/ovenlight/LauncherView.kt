package com.snowyghost.ovenlight

import android.graphics.Bitmap
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalHapticFeedback
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
    val memberships: List<Membership> = emptyList(),
    /** Invites being accepted this session, by invite ID. */
    val phases: Map<String, JoinPhase> = emptyMap(),
    /** The apps whose computers aren't answering. */
    val reachability: Map<UUID, AppReachability> = emptyMap(),
    /** Goes up with each icon saved, so a refreshed icon redraws. */
    val iconRevisions: Map<UUID, Int> = emptyMap(),
    /** A screen covers the launcher: nothing in it takes keyboard focus. */
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
    val guestNodes by nodes.guests.collectAsState()
    // Each node's state and answers, so a tile dims or brightens as its computer comes and goes.
    val guestStates = guestNodes.values.map { node -> key(node.id) { node.state.collectAsState().value to node.answers.collectAsState().value } }
    val memberships by nodes.memberships.membershipsFlow.collectAsState()
    val phases by model.guests.phases.collectAsState()
    val iconRevisions by registry.iconRevisions.collectAsState()
    val sheet by model.router.sheet.collectAsState()
    // The menus' Share and People follow what the connectors report.
    val machines by model.sharing.machines.collectAsState()
    val reachability = remember(apps, ownerState, ownerStatus, ownerAnswers, guestStates) {
        apps.mapNotNull { app -> nodes.reachability(app)?.let { app.id to it } }.toMap()
    }
    val scope = model.scope
    LauncherView(
        LauncherState(
            apps = apps,
            ownerEnabled = ownerEnabled,
            // The owner's own connection only once they set Ovenlight up for their computers.
            ownerState = if (ownerEnabled) ownerState else NodeState.Idle,
            deviceName = NodeManager.deviceName(ownerStatus, nodes.hostName),
            memberships = memberships,
            phases = phases,
            reachability = reachability,
            iconRevisions = iconRevisions,
            covered = sheet?.isScreen == true,
            icon = { IconDecoder.load(registry.iconFile(it)) },
            lock = model.lock::lock,
            present = model.router::present,
            open = model.router::open,
            retry = { scope.launch { nodes.restart() } },
            enableOwner = nodes::enableOwner,
            move = registry::move,
            rename = registry::rename,
            refreshIcon = { scope.launch { registry.refreshMetadata(it) } },
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
    var renaming by remember { mutableStateOf<WebApp?>(null) }
    var clearing by remember { mutableStateOf<WebApp?>(null) }
    var removing by remember { mutableStateOf<WebApp?>(null) }
    var leaving by remember { mutableStateOf<Membership?>(null) }
    var noMailApp by remember { mutableStateOf(false) }
    // Covered by a screen, the launcher stays composed beneath it; touch and TalkBack don't reach
    // it, and this keeps a keyboard's Tab from wrapping onto its hidden buttons and tiles.
    val focus = if (state.covered) Modifier.focusProperties { onEnter = { cancelFocusChange() } }.focusGroup() else Modifier
    // And focus already inside, on More just tapped from a keyboard, say, lets go as it's covered.
    val focusManager = LocalFocusManager.current
    LaunchedEffect(state.covered) { if (state.covered) focusManager.clearFocus(force = true) }
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
                        reachability = state.reachability, open = state.open, move = state.move,
                        addApp = { state.present(RootSheet.AddApp) }, openLink = state.openLink,
                        modifier = Modifier.weight(1f),
                        header = {
                            TailnetBanner(state.ownerState, state.deviceName, connect = { state.present(RootSheet.Login(it)) }, approve = { state.present(RootSheet.Approve) })
                            PendingInvitesBanner(
                                state.pendingInvites, state.phases, open = { state.present(RootSheet.Join(JoinRequest(Result.success(it)))) },
                                modifier = Modifier.padding(horizontal = 24.dp),
                            )
                        },
                    ) { app, close ->
                        AppMenu(
                            SharingFixture.apply(app), close, state,
                            rename = { renaming = app },
                            refreshIcon = { state.refreshIcon(app.id) },
                            reportProblem = { state.problemReport(app)?.let { if (!it.send(context)) noMailApp = true } },
                            leave = { leaving = state.memberships.firstOrNull { it.id == app.membershipID } },
                            clearData = { clearing = app },
                            remove = { removing = app },
                        )
                    }
                }
            }
        }
    }

    renaming?.let { app -> RenameDialog(app, onDismiss = { renaming = null }) { state.rename(app.id, it) } }
    clearing?.let { app ->
        Confirmation(
            title = stringResource(R.string.launcher_sign_out_title, app.name), message = stringResource(R.string.launcher_sign_out_message),
            confirm = stringResource(R.string.launcher_sign_out_clear), onDismiss = { clearing = null },
        ) { state.clearData(app.id) }
    }
    removing?.let { app ->
        Confirmation(
            title = stringResource(R.string.launcher_remove_title, app.name),
            message = app.sharedBy?.let(ConnectionCopy::removeSharedDetail) ?: stringResource(R.string.launcher_remove_message),
            confirm = stringResource(R.string.launcher_remove_app), onDismiss = { removing = null },
        ) {
            haptics.performHapticFeedback(HapticFeedbackType.Confirm)
            state.remove(app.id)
        }
    }
    leaving?.let { membership ->
        LeaveConfirmation(membership.ownerName, onDismiss = { leaving = null }) { state.leave(membership.id) }
    }
    if (noMailApp) NoEmailAppDialog(onDismiss = { noMailApp = false })
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
            IconButton(onClick = state.lock) {
                Icon(painterResource(R.drawable.ic_lock), contentDescription = stringResource(R.string.launcher_lock))
            }
            Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                TailnetStatusPill(state.ownerState, state.retry)
            }
            Box {
                var expanded by remember { mutableStateOf(false) }
                IconButton(onClick = { expanded = true }) {
                    Icon(painterResource(R.drawable.ic_more_vert), contentDescription = stringResource(R.string.launcher_more))
                }
                DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                    @Composable
                    fun item(label: String, sheet: RootSheet) = DropdownMenuItem(text = { Text(label) }, onClick = {
                        expanded = false
                        state.present(sheet)
                    })
                    item(stringResource(R.string.launcher_add_app), RootSheet.AddApp)
                    item(ConnectionCopy.joinWithInvite, RootSheet.Join(JoinRequest()))
                    if (state.showsSharing) item(stringResource(R.string.launcher_people_sharing), RootSheet.Sharing)
                    HorizontalDivider()
                    item(stringResource(R.string.launcher_settings), RootSheet.Settings)
                }
            }
        }
    }
}

/**
 * Connecting, or a failure with a retry, in a small pill between the corner controls. Nothing
 * when connected or off; signing in and approval have a card instead.
 */
@Composable
private fun TailnetStatusPill(state: NodeState, retry: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val pill = Modifier.minimumInteractiveComponentSize().height(32.dp).clip(CircleShape).background(colors.surfaceContainerHigh)
    val text = MaterialTheme.typography.labelLarge
    when {
        state.isBusy -> Row(
            pill.semantics(mergeDescendants = true) {}.padding(horizontal = 14.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically,
        ) {
            CircularProgressIndicator(Modifier.size(16.dp).clearAndSetSemantics {}, strokeWidth = 2.dp)
            Text(ConnectionCopy.connecting, style = text, color = colors.onSurfaceVariant, maxLines = 1)
        }
        state is NodeState.Failed -> {
            val tryAgain = stringResource(R.string.launcher_try_again)
            Row(
                pill.clickable(onClickLabel = tryAgain, role = Role.Button, onClick = retry).padding(horizontal = 14.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(painterResource(R.drawable.ic_warning), contentDescription = null, tint = colors.tertiary, modifier = Modifier.size(18.dp))
                // The message gives way to Try again when the pill runs out of room.
                Text(state.message, style = text, color = colors.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                Text(tryAgain, style = text, color = colors.primary, maxLines = 1, modifier = Modifier.clearAndSetSemantics {})
            }
        }
    }
}

/**
 * The owner's sign-in or approval card, above the apps until Ovenlight is connected to their
 * tailnet. The only place on the launcher that names Tailscale.
 */
@Composable
private fun TailnetBanner(state: NodeState, deviceName: String, connect: (URI) -> Unit, approve: () -> Unit) {
    when (state) {
        is NodeState.NeedsLogin -> LauncherCard(
            R.drawable.ic_lan, stringResource(R.string.launcher_connect_title), AnnotatedString(stringResource(R.string.launcher_connect_body)),
            stringResource(R.string.launcher_connect),
        ) { connect(state.url) }
        NodeState.AwaitingApproval -> {
            val body = stringResource(R.string.launcher_approve_body, deviceName)
            val start = body.indexOf(deviceName)
            val text = buildAnnotatedString {
                append(body)
                if (deviceName.isNotEmpty() && start >= 0) addStyle(SpanStyle(fontWeight = FontWeight.Bold), start, start + deviceName.length)
            }
            LauncherCard(R.drawable.ic_verified_user, stringResource(R.string.launcher_approve_title), text, stringResource(R.string.launcher_open_machines), approve)
        }
        else -> Unit
    }
}

@Composable
private fun LauncherCard(icon: Int, title: String, body: AnnotatedString, button: String, action: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    ElevatedCard(
        shape = RoundedCornerShape(28.dp),
        colors = CardDefaults.elevatedCardColors(containerColor = if (isSystemInDarkTheme()) colors.surfaceContainerHigh else colors.surfaceContainerLowest),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 6.dp),
    ) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(40.dp).background(colors.primaryContainer, CircleShape), contentAlignment = Alignment.Center) {
                    Icon(painterResource(icon), contentDescription = null, tint = colors.onPrimaryContainer)
                }
                Text(title, style = MaterialTheme.typography.titleMedium, color = colors.onSurface, modifier = Modifier.semantics { heading() })
            }
            Text(body, style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant)
            Button(onClick = action, modifier = Modifier.fillMaxWidth().height(56.dp)) {
                Text(button, style = MaterialTheme.typography.titleMedium)
            }
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
    val plan = AppMenuPlan.plan(app, admin = info?.let { (machine, admin) -> admin to machine.guests })
    Column(Modifier.widthIn(max = 280.dp).padding(horizontal = 12.dp, vertical = 8.dp).semantics(mergeDescendants = true) { heading() }) {
        Text(app.name, style = MaterialTheme.typography.titleSmall, color = colors.onSurface, maxLines = 2, overflow = TextOverflow.Ellipsis)
        Text(
            app.sharedBy?.let(ConnectionCopy::sharedBy) ?: app.host, style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant,
            maxLines = 1, overflow = TextOverflow.Ellipsis,
        )
    }
    // Add to Home screen comes first here, with Phase 7's pinned shortcuts.
    AppMenuItem(close, stringResource(R.string.launcher_rename), R.drawable.ic_edit, action = rename)
    AppMenuItem(close, stringResource(R.string.launcher_refresh_icon), R.drawable.ic_refresh, action = refreshIcon)
    if (plan != AppMenuPlan()) {
        HorizontalDivider()
        when (plan.share) {
            AppMenuPlan.Share.Available -> info?.let { (machine, admin) ->
                AppMenuItem(close, stringResource(R.string.sharing_share), R.drawable.ic_share) { state.share(machine, admin) }
            }
            AppMenuPlan.Share.NotShareable -> DropdownMenuItem(
                text = {
                    Column {
                        Text(stringResource(R.string.sharing_share))
                        Text(AppMenuPlan.notShareableDetail, style = MaterialTheme.typography.bodySmall)
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
    AppMenuItem(close, stringResource(R.string.launcher_sign_out_clear), R.drawable.ic_ink_eraser, action = clearData)
    AppMenuItem(close, stringResource(R.string.launcher_remove_app), R.drawable.ic_delete, destructive = true, action = remove)
}

@Composable
private fun RenameDialog(app: WebApp, onDismiss: () -> Unit, save: (String) -> Unit) {
    val name = rememberTextFieldState(app.name)
    val focus = remember { FocusRequester() }
    val done = {
        name.text.toString().takeIf { it.isNotEmpty() }?.let(save)
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
        confirmButton = { TextButton(onClick = done, enabled = name.text.isNotEmpty()) { Text(stringResource(R.string.launcher_save)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.launcher_cancel)) } },
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
            registry.add(url)
            // An app discovery already found (one removed, then added back) joins it at once.
            model.scope.launch { registry.mergeDiscovered(nodes.owner.discovered.value) }
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
        val url = AppAddress.url(text, ownerSuffix)
        if (url == null) {
            refused = text.takeIf { AppAddress.url(it) != null }
        } else {
            add(url)
            haptics.performHapticFeedback(HapticFeedbackType.Confirm)
            onClose()
        }
    }
    // Before the owner is connected, the way there.
    val next: Pair<String, () -> Unit>? = when {
        ownerSuffix != null -> null
        !ownerEnabled -> stringResource(R.string.launcher_use_own_computers) to {
            useOwnComputers()
            onClose()
        }
        ownerState is NodeState.NeedsLogin -> stringResource(R.string.add_app_sign_in) to { present(RootSheet.Login(ownerState.url)) }
        ownerState == NodeState.AwaitingApproval -> stringResource(R.string.add_app_approve) to { present(RootSheet.Approve) }
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
                TextButton(onClick = submit, enabled = AppAddress.url(text) != null) { Text(stringResource(R.string.add_app_add)) }
            } else {
                next?.let { (label, action) -> TextButton(onClick = action) { Text(label) } }
            }
        },
        dismissButton = { TextButton(onClick = onClose) { Text(stringResource(R.string.launcher_cancel)) } },
    )
}

/** A row of an app's menu, which closes the menu before it acts; a destructive one is in the error color. */
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

/** A confirmation whose button is in the error color, as iOS marks destructive ones red. */
@Composable
internal fun Confirmation(title: String, message: String, confirm: String, onDismiss: () -> Unit, action: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(message) },
        confirmButton = {
            TextButton(
                onClick = {
                    onDismiss()
                    action()
                },
                colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
            ) { Text(confirm) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.launcher_cancel)) } },
    )
}

/** Leaving an owner's apps, asked the same way from the launcher, an app's menu and Settings. */
@Composable
internal fun LeaveConfirmation(owner: String, onDismiss: () -> Unit, leave: () -> Unit) = Confirmation(
    ConnectionCopy.leaveTitle(owner), ConnectionCopy.leaveDetail(owner), stringResource(R.string.launcher_leave), onDismiss, leave,
)
