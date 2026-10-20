package com.snowyghost.ovenlight

import android.app.admin.DevicePolicyManager
import android.content.Intent
import android.icu.text.ListFormatter
import android.provider.Settings as SystemSettings
import androidx.activity.compose.LocalActivity
import androidx.annotation.DrawableRes
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CornerBasedShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberTopAppBarState
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.ParagraphStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextIndent
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import java.net.URI
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Someone who shared apps with this phone, as Settings lists them. */
data class SharedOwner(
    val membershipID: String,
    val ownerName: String,
    val appNames: List<String>,
    /** Invites still joining, before any app is here: each app's name, and why joining failed (null while it goes on). */
    val joining: List<Pair<String, String?>> = emptyList(),
)

/** The owner's own computers, once they chose to use them. */
data class OwnerSettings(
    val state: NodeState,
    val account: TailnetStatus.User? = null,
    val tailnet: String? = null,
    /** See [NodeManager.ownerHasNoAccount]: Use my own computers can still be taken back. */
    val hasNoAccount: Boolean = false,
    val deviceName: String = "",
    /** Apps found on the owner's computers, which Sign out and Stop using my own computers remove, so either asks first. */
    val hasFoundApps: Boolean = false,
) {
    /** Who "Connected as" names: only once connected with an account. */
    val connectedAs: String?
        get() = account?.takeIf { state == NodeState.Ready }?.let { it.displayName.ifEmpty { it.loginName } }
}

/** What Settings shows and what its rows do, so tests can drive it without an [AppModel]. */
data class SettingsState(
    /** Seconds in the background before the lock (see [LockManager.lockAfterSeconds]). */
    val lockAfterSeconds: Int = LockManager.DEFAULT_LOCK_AFTER,
    val noScreenLock: Boolean = false,
    val shared: List<SharedOwner> = emptyList(),
    /** Null until the owner chose Use my own computers. */
    val owner: OwnerSettings? = null,
    val version: String = "",
    val setLockAfter: (Int) -> Unit = {},
    /** The screen lock again, before the lock is turned off (see UnlockPrompt.confirm). */
    val confirm: suspend (reason: String) -> Boolean = { true },
    val setScreenLock: () -> Unit = {},
    val leave: (String) -> Unit = {},
    val useMyOwnComputers: () -> Unit = {},
    val signIn: (URI) -> Unit = {},
    val approve: () -> Unit = {},
    /** Sign out, and Stop using my own computers before a sign-in: one action (see [NodeManager.disableOwner]). */
    val stopUsingMyOwnComputers: () -> Unit = {},
    /** Opens Contact us in the mail app; the draft when the phone has none. */
    val contactUs: () -> MailDraft? = { null },
    val privacyPolicy: () -> Unit = {},
    /** The licenses for Acknowledgements, or null when the build lacks them. */
    val notices: suspend () -> String? = { null },
)

/** Settings (SettingsView in LauncherView.swift, and SharedSettings.swift). */
@Composable
fun SettingsView(model: AppModel, pages: SnapshotStateList<SettingsPage>, onClose: () -> Unit) {
    val context = LocalContext.current
    val activity = LocalActivity.current as? MainActivity
    val nodes = model.nodes
    val memberships by nodes.memberships.membershipsFlow.collectAsState()
    val phases by model.guests.phases.collectAsState()
    val apps by model.registry.appsFlow.collectAsState()
    val ownerEnabled by nodes.ownerEnabled.collectAsState()
    val nodeState by nodes.owner.state.collectAsState()
    val status by nodes.owner.status.collectAsState()
    val noScreenLock by model.lock.noPasscode.collectAsState()
    // The store isn't observable, and only this screen changes it.
    var lockAfter by remember { mutableIntStateOf(model.lock.lockAfterSeconds) }
    val version = "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})"
    val state = SettingsState(
        lockAfterSeconds = lockAfter,
        noScreenLock = noScreenLock,
        shared = memberships.map { membership ->
            // As the launcher's banner says it: a failed invite shows why.
            val joining = membership.pendingClaims.map { it.invite }.distinctBy { it.invite }.map { invite ->
                invite.name to (phases[invite.invite] as? JoinPhase.Failed)?.error?.title(invite.owner, invite.name)
            }
            SharedOwner(membership.id, membership.ownerName, apps.filter { it.membershipID == membership.id }.map { it.name }, joining)
        },
        owner = if (!ownerEnabled) null else OwnerSettings(
            state = nodeState,
            account = status?.account,
            tailnet = status?.tailnet?.name?.takeIf { it.isNotEmpty() },
            hasNoAccount = nodes.ownerHasNoAccount,
            deviceName = nodes.deviceName,
            hasFoundApps = apps.any { !it.isShared && it.discovered },
        ),
        version = version,
        setLockAfter = { seconds ->
            model.lock.lockAfterSeconds = seconds
            lockAfter = seconds
        },
        confirm = { reason -> activity?.confirmOwner(reason) ?: false },
        // Straight to choosing a screen lock, or the security settings where that isn't offered.
        setScreenLock = { context.startOrFalse(Intent(DevicePolicyManager.ACTION_SET_NEW_PASSWORD)) || context.startOrFalse(Intent(SystemSettings.ACTION_SECURITY_SETTINGS)) },
        leave = { id -> model.scope.launch { model.guests.leave(id) } },
        useMyOwnComputers = {
            nodes.enableOwner()
            onClose()
        },
        signIn = { url -> model.router.present(RootSheet.Login(url)) },
        approve = { model.router.present(RootSheet.Approve) },
        stopUsingMyOwnComputers = {
            model.scope.launch { nodes.disableOwner() }
            onClose()
        },
        contactUs = { MailDraft.contactUs(context, nodes).takeUnless { it.send(context) } },
        privacyPolicy = { CustomTabs.open(context, AppLinks.privacyPolicy, model.lifecycle) },
        notices = { withContext(Dispatchers.IO) { Acknowledgements.notices { context.assets.open(it) } } },
    )
    SettingsScreen(state, onClose, pages)
}

/** The pages of Settings: the list, and Acknowledgements over it. */
enum class SettingsPage { List, Acknowledgements }

/** Settings, and Acknowledgements over it, which Back returns from. */
@Composable
fun SettingsScreen(state: SettingsState, onClose: () -> Unit, pages: SnapshotStateList<SettingsPage> = remember { mutableStateListOf(SettingsPage.List) }) {
    // Outlives the slide to Acknowledgements, so coming back keeps the place in the list.
    val scroll = rememberScrollState()
    val back = { if (pages.size > 1) pages.removeAt(pages.lastIndex) }
    PageStack(pages) { page ->
        when (page) {
            SettingsPage.List -> SettingsList(state, scroll, onClose, onAcknowledgements = {
                // A quick second tap doesn't push it twice.
                if (pages.last() != SettingsPage.Acknowledgements) pages += SettingsPage.Acknowledgements
            })
            SettingsPage.Acknowledgements -> AcknowledgementsScreen(state.notices, onBack = back)
        }
    }
}

private class LockDelay(val seconds: Int, val label: Int)

private val lockDelays = listOf(
    LockDelay(0, R.string.settings_lock_immediately),
    LockDelay(60, R.string.settings_lock_after_1_minute),
    LockDelay(300, R.string.settings_lock_after_5_minutes),
    LockDelay(900, R.string.settings_lock_after_15_minutes),
    LockDelay(LockManager.OFF, R.string.settings_lock_off),
)

@Composable
private fun SettingsList(state: SettingsState, scroll: ScrollState, onClose: () -> Unit, onAcknowledgements: () -> Unit) {
    var choosingDelay by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    var leaving by remember { mutableStateOf<SharedOwner?>(null) }
    /** The row asking first, by its name, which the dialog's button repeats. */
    var confirming by remember { mutableStateOf<Int?>(null) }
    var noEmailApp by remember { mutableStateOf<MailDraft?>(null) }
    val error = MaterialTheme.colorScheme.error
    val primary = MaterialTheme.colorScheme.primary
    GroupedScreen(stringResource(R.string.settings), onBack = onClose, scroll = scroll) {
        Column(verticalArrangement = Arrangement.spacedBy(24.dp)) {
            SettingsGroup(
                footer = {
                    if (state.noScreenLock) {
                        Footer(stringResource(R.string.settings_no_screen_lock))
                        TextButton(onClick = state.setScreenLock, modifier = Modifier.padding(horizontal = 4.dp)) {
                            Text(stringResource(R.string.settings_set_screen_lock))
                        }
                    } else {
                        Footer(stringResource(R.string.settings_lock_footer))
                    }
                },
            ) {
                row { shape ->
                    SettingsRow(
                        shape, stringResource(R.string.settings_require_screen_lock), icon = R.drawable.ic_lock,
                        supporting = lockDelays.firstOrNull { it.seconds == state.lockAfterSeconds }?.let { stringResource(it.label) },
                        onClick = { choosingDelay = true },
                    )
                }
            }
            if (state.shared.isNotEmpty()) {
                SettingsGroup(
                    header = stringResource(R.string.settings_shared_with_you),
                    footer = { Footer(stringResource(R.string.settings_shared_footer)) },
                ) {
                    for (owner in state.shared) {
                        row { shape ->
                            val joining = stringResource(R.string.settings_joining)
                            val supporting = when {
                                owner.appNames.isNotEmpty() -> ListFormatter.getInstance().format(owner.appNames)
                                owner.joining.isEmpty() -> joining
                                else -> owner.joining.map { (app, failed) -> stringResource(R.string.settings_joining_app, app, failed ?: joining) }.joinToString("\n")
                            }
                            SettingsRow(
                                shape, owner.ownerName,
                                supporting = supporting,
                                trailing = {
                                    // Each says whose apps, so TalkBack tells one Leave from another.
                                    TextButton(onClick = { leaving = owner }, modifier = Modifier.semantics { contentDescription = ConnectionCopy.leaveApps(owner.ownerName) }) {
                                        Text(stringResource(R.string.leave), color = error)
                                    }
                                },
                            )
                        }
                    }
                }
            }
            val owner = state.owner
            if (owner == null) {
                SettingsGroup(footer = { Footer(stringResource(R.string.settings_use_my_own_computers_footer)) }) {
                    row { shape -> SettingsRow(shape, stringResource(R.string.use_my_own_computers), color = primary, onClick = state.useMyOwnComputers) }
                }
            } else {
                OwnerGroups(owner, state, confirm = { confirming = it })
            }
            SettingsGroup(header = stringResource(R.string.settings_support)) {
                row { shape ->
                    SettingsRow(shape, stringResource(R.string.contact_us), icon = R.drawable.ic_mail, onClick = { noEmailApp = state.contactUs() })
                }
                row { shape -> SettingsRow(shape, stringResource(R.string.settings_privacy_policy), icon = R.drawable.ic_privacy_tip, onClick = state.privacyPolicy) }
                row { shape -> SettingsRow(shape, stringResource(R.string.settings_acknowledgements), icon = R.drawable.ic_description, onClick = onAcknowledgements) }
            }
            SettingsGroup {
                row { shape -> SettingsRow(shape, stringResource(R.string.settings_version), supporting = state.version) }
            }
        }
    }

    if (choosingDelay) {
        val offReason = stringResource(R.string.settings_lock_off_reason)
        AlertDialog(
            onDismissRequest = { choosingDelay = false },
            title = { Text(stringResource(R.string.settings_require_screen_lock)) },
            text = {
                // Scrolls when large text in landscape leaves too little height.
                Column(Modifier.verticalScroll(rememberScrollState()).selectableGroup()) {
                    for (delay in lockDelays) {
                        val selected = delay.seconds == state.lockAfterSeconds
                        Row(
                            Modifier.fillMaxWidth().heightIn(min = 48.dp).selectable(selected = selected, role = Role.RadioButton) {
                                choosingDelay = false
                                // Off asks first, so someone handed the unlocked phone can't turn the lock off.
                                if (!selected) scope.launch { if (delay.seconds != LockManager.OFF || state.confirm(offReason)) state.setLockAfter(delay.seconds) }
                            },
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(selected = selected, onClick = null)
                            Spacer(Modifier.width(16.dp))
                            Text(stringResource(delay.label), style = MaterialTheme.typography.bodyLarge)
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { choosingDelay = false }) { Text(stringResource(R.string.cancel)) } },
        )
    }
    leaving?.let { owner ->
        LeaveConfirmation(owner.ownerName, onDismiss = { leaving = null }) { state.leave(owner.membershipID) }
    }
    confirming?.let { row ->
        Confirmation(
            stringResource(if (row == R.string.settings_sign_out) R.string.settings_sign_out_title else R.string.settings_stop_using_title),
            stringResource(R.string.settings_sign_out_detail), stringResource(row),
            onDismiss = { confirming = null }, action = state.stopUsingMyOwnComputers,
        )
    }
    noEmailApp?.let { NoEmailAppDialog(it, onDismiss = { noEmailApp = null }) }
}

/**
 * The owner's computers: before signing in, only the way in; then the account or the state
 * with whatever applies to it, and Advanced, the one part of Settings that names Tailscale.
 * A guest never sees either.
 */
@Composable
private fun OwnerGroups(owner: OwnerSettings, state: SettingsState, confirm: (Int) -> Unit) {
    val primary = MaterialTheme.colorScheme.primary
    // Every row has an icon, so their text lines up.
    @Composable
    fun icon(drawable: Int) = Icon(painterResource(drawable), contentDescription = null, tint = primary)
    SettingsGroup {
        val login = owner.state as? NodeState.NeedsLogin
        if (login != null) {
            row { shape -> SettingsRow(shape, stringResource(R.string.sign_in_to_your_computers), color = primary, leading = { icon(R.drawable.ic_lan) }, onClick = { state.signIn(login.url) }) }
        } else {
            row { shape ->
                SettingsRow(
                    shape, stringResource(R.string.settings_your_computers), icon = R.drawable.ic_computer,
                    supporting = owner.connectedAs?.let { stringResource(R.string.settings_connected_as, it) } ?: owner.state.summary,
                )
            }
            if (owner.state == NodeState.AwaitingApproval) {
                row { shape -> SettingsRow(shape, stringResource(R.string.approve_this_phone), color = primary, leading = { icon(R.drawable.ic_verified_user) }, onClick = state.approve) }
            }
            if (owner.account != null) {
                row { shape ->
                    val error = MaterialTheme.colorScheme.error
                    SettingsRow(
                        shape, stringResource(R.string.settings_sign_out), color = error,
                        leading = { Icon(painterResource(R.drawable.ic_logout), contentDescription = null, tint = error) },
                        onClick = { confirm(R.string.settings_sign_out) },
                    )
                }
            }
        }
        // While no account is signed in, Use my own computers can still be taken back.
        if (owner.hasNoAccount) {
            val stop = R.string.settings_stop_using_my_own_computers
            row { shape ->
                SettingsRow(shape, stringResource(stop), color = primary, leading = { icon(R.drawable.ic_logout) }, onClick = { if (owner.hasFoundApps) confirm(stop) else state.stopUsingMyOwnComputers() })
            }
        }
    }
    SettingsGroup(header = stringResource(R.string.settings_advanced)) {
        row { shape -> SettingsRow(shape, stringResource(R.string.settings_status), supporting = owner.state.summary) }
        owner.account?.let { account -> row { shape -> SettingsRow(shape, stringResource(R.string.settings_tailscale_account), supporting = account.loginName) } }
        owner.tailnet?.let { tailnet -> row { shape -> SettingsRow(shape, stringResource(R.string.settings_tailnet), supporting = tailnet) } }
        row { shape -> SettingsRow(shape, stringResource(R.string.settings_this_phone), supporting = owner.deviceName) }
    }
}

/** The licenses of everything Ovenlight ships, one selectable paragraph each (AcknowledgementsView in Support.swift). */
@Composable
private fun AcknowledgementsScreen(notices: suspend () -> String?, onBack: () -> Unit) {
    // Read off the main thread: the text runs to thousands of lines.
    val paragraphs by produceState(emptyList<String>()) { value = Acknowledgements.paragraphs(notices()) }
    val style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace)
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val space = remember(style, density) { with(density) { (measurer.measure("0".repeat(10), style).size.width / 10f).toSp() } }
    GroupedScreen(stringResource(R.string.settings_acknowledgements), onBack = onBack) { padding ->
        LazyColumn(Modifier.fillMaxSize(), contentPadding = padding, verticalArrangement = Arrangement.spacedBy(12.dp)) {
            items(paragraphs) { paragraph ->
                SelectionContainer {
                    val heading = Acknowledgements.heading(paragraph)
                    if (heading != null) {
                        Text(heading, style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp).semantics { heading() })
                    } else {
                        // Each line its own paragraph, indented by its spaces, so a line too long for the
                        // screen wraps under its own text. A paragraph style starts a new line by itself.
                        val text = remember(paragraph, space) {
                            buildAnnotatedString {
                                for ((indent, line) in Acknowledgements.lines(paragraph)) {
                                    withStyle(ParagraphStyle(textIndent = TextIndent(space * indent, space * indent))) { append(Acknowledgements.unbroken(line)) }
                                }
                            }
                        }
                        Text(text, style = style, modifier = Modifier.padding(horizontal = 16.dp))
                    }
                }
            }
        }
    }
}

/**
 * A full-screen destination on the grouped background, which its top app bar shares, with a
 * back arrow (or [navigationIcon]) and [actions]. With [scroll] the content scrolls in a
 * centered column; without, it gets the bars' padding and scrolls itself. [refresh] adds a pull
 * to refresh. Its title names the pane for TalkBack, since it replaces another screen in the
 * same window.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun GroupedScreen(
    title: String,
    onBack: () -> Unit,
    scroll: ScrollState? = null,
    @DrawableRes navigationIcon: Int = R.drawable.ic_arrow_back,
    navigationLabel: String = stringResource(R.string.back),
    actions: @Composable RowScope.() -> Unit = {},
    refresh: (suspend () -> Unit)? = null,
    paneTitle: String = title,
    content: @Composable (PaddingValues) -> Unit,
) {
    val background = LocalOvenColors.current.groupedBackground
    val direction = LocalLayoutDirection.current
    // A page coming back keeps its place in [scroll], and its bar starts as tinted as that leaves it.
    val barHeight = with(LocalDensity.current) { TopAppBarDefaults.TopAppBarExpandedHeight.toPx() }
    val bar = TopAppBarDefaults.pinnedScrollBehavior(
        rememberTopAppBarState(initialHeightOffsetLimit = -barHeight, initialContentOffset = -(scroll?.value ?: 0).toFloat()),
    )
    Scaffold(
        modifier = Modifier.nestedScroll(bar.nestedScrollConnection).semantics { this.paneTitle = paneTitle },
        containerColor = background,
        topBar = {
            TopAppBar(
                title = { Text(title) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(painterResource(navigationIcon), contentDescription = navigationLabel)
                    }
                },
                actions = actions,
                colors = scrollingBarColors(background),
                scrollBehavior = bar,
            )
        },
    ) { bars ->
        // Clear of a navigation bar or cutout at the side, in landscape.
        val start = bars.calculateStartPadding(direction)
        val end = bars.calculateEndPadding(direction)
        val top = bars.calculateTopPadding()
        if (scroll == null) {
            val bottom = bars.calculateBottomPadding() + 16.dp
            if (refresh == null) return@Scaffold content(PaddingValues(start = start, top = top + 8.dp, end = end, bottom = bottom))
            // Below the bar, where the pull's indicator shows.
            return@Scaffold Refreshable(refresh, Modifier.fillMaxSize().padding(top = top)) { content(PaddingValues(start = start, top = 8.dp, end = end, bottom = bottom)) }
        }
        val modifier = Modifier.fillMaxSize().padding(start = start, top = top, end = end)
        val column = @Composable {
            Column(Modifier.widthIn(max = 640.dp).fillMaxWidth().verticalScroll(scroll).padding(horizontal = 16.dp)) {
                Spacer(Modifier.height(8.dp))
                content(PaddingValues())
                Spacer(Modifier.height(bars.calculateBottomPadding() + 24.dp))
            }
        }
        if (refresh == null) return@Scaffold Box(modifier, contentAlignment = Alignment.TopCenter) { column() }
        Refreshable(refresh, modifier) { column() }
    }
}

/** Pull to refresh over [content], which scrolls. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun Refreshable(refresh: suspend () -> Unit, modifier: Modifier, content: @Composable () -> Unit) {
    val scope = rememberCoroutineScope()
    var refreshing by remember { mutableStateOf(false) }
    PullToRefreshBox(
        isRefreshing = refreshing,
        onRefresh = {
            scope.launch {
                refreshing = true
                try {
                    refresh()
                } finally {
                    refreshing = false
                }
            }
        },
        modifier = modifier,
        contentAlignment = Alignment.TopCenter,
    ) { content() }
}

/**
 * A top app bar's colors on [background], a step deeper once content scrolls under it, as
 * Material's bars do on surface. The grouped background is already surfaceContainer, so it's High.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun scrollingBarColors(background: Color) =
    TopAppBarDefaults.topAppBarColors(containerColor = background, scrolledContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh)

/** Collects a group's rows, so each can take its place's shape. */
internal class GroupRows {
    val rows = mutableListOf<@Composable (Shape) -> Unit>()

    fun row(content: @Composable (Shape) -> Unit) {
        rows += content
    }
}

/**
 * A group of rows as Material 3 Expressive segments them, as Android 16's own Settings does:
 * round outer corners, small inner ones and a 2 dp gap. Material3 1.4.0 has no
 * SegmentedListItem yet, so it is built from ListItem.
 */
@Composable
internal fun SettingsGroup(header: String? = null, footer: (@Composable () -> Unit)? = null, rows: GroupRows.() -> Unit) {
    val items = GroupRows().apply(rows).rows
    Column {
        header?.let {
            Text(
                it,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 8.dp).semantics { heading() },
            )
        }
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            val outer = MaterialTheme.shapes.large
            val inner = MaterialTheme.shapes.extraSmall
            items.forEachIndexed { index, row -> row(segmentShape(index, items.size, outer, inner)) }
        }
        footer?.let { Column(Modifier.padding(top = 8.dp)) { it() } }
    }
}

internal fun segmentShape(index: Int, count: Int, outer: CornerBasedShape, inner: CornerBasedShape): Shape {
    val top = if (index == 0) outer else inner
    val bottom = if (index == count - 1) outer else inner
    return inner.copy(topStart = top.topStart, topEnd = top.topEnd, bottomStart = bottom.bottomStart, bottomEnd = bottom.bottomEnd)
}

@Composable
internal fun Footer(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 16.dp))
}

/**
 * One row: a headline, maybe with a value or detail under it, a monochrome icon (or another
 * [leading] view) and a trailing control. A row without [onClick] reads as one element.
 */
@Composable
internal fun SettingsRow(
    shape: Shape,
    headline: String,
    supporting: String? = null,
    @DrawableRes icon: Int? = null,
    color: Color = Color.Unspecified,
    maxLines: Int = Int.MAX_VALUE,
    leading: (@Composable () -> Unit)? = icon?.let { { Icon(painterResource(it), contentDescription = null) } },
    trailing: (@Composable () -> Unit)? = null,
    onLongClick: (() -> Unit)? = null,
    /** A row that's selected or not, read as a checkbox; [onClick] toggles it, and without one it can't be. */
    selected: Boolean? = null,
    onClick: (() -> Unit)? = null,
) {
    val action = when {
        selected != null -> Modifier.toggleable(selected, enabled = onClick != null, role = Role.Checkbox) { onClick?.invoke() }
        onClick != null && onLongClick != null -> Modifier.combinedClickable(role = Role.Button, onLongClick = onLongClick, onClick = onClick)
        onClick != null -> Modifier.clickable(role = Role.Button, onClick = onClick)
        else -> Modifier.semantics(mergeDescendants = true) {}
    }
    ListItem(
        headlineContent = { Text(headline, color = color, maxLines = maxLines, overflow = TextOverflow.Ellipsis) },
        modifier = Modifier.clip(shape).then(action),
        supportingContent = supporting?.let { { Text(it) } },
        leadingContent = leading,
        trailingContent = trailing,
        colors = ListItemDefaults.colors(containerColor = LocalOvenColors.current.groupedRow, leadingIconColor = MaterialTheme.colorScheme.onSurfaceVariant),
    )
}
