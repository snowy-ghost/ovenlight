package com.snowyghost.ovenlight

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.icu.text.ListFormatter
import android.provider.Settings as SystemSettings
import androidx.activity.compose.BackHandler
import androidx.annotation.DrawableRes
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import java.net.URI
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Someone who shared apps with this phone, as Settings lists them. */
data class SharedOwner(val membershipID: String, val ownerName: String, val appNames: List<String>)

/** The owner's own computers, once they chose to use them. */
data class OwnerSettings(
    val state: NodeState,
    val account: TailnetStatus.User? = null,
    val tailnet: String? = null,
    /** See [NodeManager.ownerHasNoAccount]: Use my own computers can still be taken back. */
    val hasNoAccount: Boolean = false,
    val deviceName: String = "",
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
    val setScreenLock: () -> Unit = {},
    val leave: (String) -> Unit = {},
    val useMyOwnComputers: () -> Unit = {},
    val signIn: (URI) -> Unit = {},
    val approve: () -> Unit = {},
    val signOut: () -> Unit = {},
    val stopUsingMyOwnComputers: () -> Unit = {},
    /** False when the phone has no email app. */
    val contactUs: () -> Boolean = { true },
    val privacyPolicy: () -> Unit = {},
    /** The licenses for Acknowledgements, or null when the build lacks them. */
    val notices: suspend () -> String? = { null },
)

/** Settings (SettingsView in LauncherView.swift, and SharedSettings.swift). */
@Composable
fun SettingsView(model: AppModel, onClose: () -> Unit) {
    val context = LocalContext.current
    val nodes = model.nodes
    val memberships by nodes.memberships.membershipsFlow.collectAsState()
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
            SharedOwner(membership.id, membership.ownerName, apps.filter { it.membershipID == membership.id }.map { it.name })
        },
        owner = if (!ownerEnabled) null else OwnerSettings(
            state = nodeState,
            account = status?.account,
            tailnet = status?.tailnet?.name?.takeIf { it.isNotEmpty() },
            hasNoAccount = nodes.ownerHasNoAccount,
            deviceName = nodes.deviceName,
        ),
        version = version,
        setLockAfter = { seconds ->
            model.lock.lockAfterSeconds = seconds
            lockAfter = seconds
        },
        setScreenLock = { context.open(Intent(SystemSettings.ACTION_SECURITY_SETTINGS)) },
        leave = { id -> model.scope.launch { model.guests.leave(id) } },
        useMyOwnComputers = {
            nodes.enableOwner()
            onClose()
        },
        signIn = { url -> model.router.present(RootSheet.Login(url)) },
        approve = { model.router.present(RootSheet.Approve) },
        signOut = { model.scope.launch { nodes.signOut() } },
        stopUsingMyOwnComputers = {
            model.scope.launch { nodes.disableOwner() }
            onClose()
        },
        contactUs = { context.open(MailDraft.contactUs(version).intent()) },
        privacyPolicy = { context.open(Intent(Intent.ACTION_VIEW, AppLinks.privacyPolicy.toString().toUri())) },
        notices = { withContext(Dispatchers.IO) { Acknowledgements.notices { context.assets.open(it) } } },
    )
    SettingsScreen(state, onClose)
}

/** Starts another app's screen; false when no app handles it. */
private fun Context.open(intent: Intent): Boolean = try {
    startActivity(intent)
    true
} catch (_: ActivityNotFoundException) {
    false
}

/** Settings, and Acknowledgements over it, which Back returns from. */
@Composable
fun SettingsScreen(state: SettingsState, onClose: () -> Unit) {
    var acknowledgements by remember { mutableStateOf(false) }
    BackHandler(enabled = acknowledgements) { acknowledgements = false }
    AnimatedContent(
        acknowledgements,
        transitionSpec = {
            if (targetState) {
                slideInHorizontally { it } togetherWith slideOutHorizontally { -it / 3 }
            } else {
                slideInHorizontally { -it / 3 } togetherWith slideOutHorizontally { it }
            }
        },
        label = "settings page",
    ) { showing ->
        if (showing) {
            AcknowledgementsScreen(state.notices, onBack = { acknowledgements = false })
        } else {
            SettingsList(state, onClose, onAcknowledgements = { acknowledgements = true })
        }
    }
}

private class LockDelay(val seconds: Int, val label: Int)

private val lockDelays = listOf(
    LockDelay(0, R.string.settings_lock_immediately),
    LockDelay(60, R.string.settings_lock_after_1_minute),
    LockDelay(300, R.string.settings_lock_after_5_minutes),
    LockDelay(900, R.string.settings_lock_after_15_minutes),
)

@Composable
private fun SettingsList(state: SettingsState, onClose: () -> Unit, onAcknowledgements: () -> Unit) {
    var choosingDelay by remember { mutableStateOf(false) }
    var leaving by remember { mutableStateOf<SharedOwner?>(null) }
    var confirmingSignOut by remember { mutableStateOf(false) }
    var noEmailApp by remember { mutableStateOf(false) }
    val error = MaterialTheme.colorScheme.error
    val primary = MaterialTheme.colorScheme.primary
    GroupedScreen(stringResource(R.string.settings_title), onBack = onClose) {
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
                            SettingsRow(
                                shape, owner.ownerName,
                                supporting = if (owner.appNames.isEmpty()) stringResource(R.string.settings_joining) else ListFormatter.getInstance().format(owner.appNames),
                                trailing = {
                                    TextButton(onClick = { leaving = owner }) { Text(stringResource(R.string.settings_leave), color = error) }
                                },
                            )
                        }
                    }
                }
            }
            val owner = state.owner
            if (owner == null) {
                SettingsGroup(footer = { Footer(stringResource(R.string.settings_use_my_own_computers_footer)) }) {
                    row { shape -> SettingsRow(shape, stringResource(R.string.settings_use_my_own_computers), color = primary, onClick = state.useMyOwnComputers) }
                }
            } else {
                OwnerGroups(owner, state, onSignOut = { confirmingSignOut = true })
            }
            SettingsGroup(header = stringResource(R.string.settings_support)) {
                row { shape ->
                    SettingsRow(shape, stringResource(R.string.settings_contact_us), icon = R.drawable.ic_mail, onClick = { if (!state.contactUs()) noEmailApp = true })
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
        AlertDialog(
            onDismissRequest = { choosingDelay = false },
            title = { Text(stringResource(R.string.settings_require_screen_lock)) },
            text = {
                Column(Modifier.selectableGroup()) {
                    for (delay in lockDelays) {
                        val selected = delay.seconds == state.lockAfterSeconds
                        Row(
                            Modifier.fillMaxWidth().heightIn(min = 48.dp).selectable(selected = selected, role = Role.RadioButton) {
                                state.setLockAfter(delay.seconds)
                                choosingDelay = false
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
            confirmButton = { TextButton(onClick = { choosingDelay = false }) { Text(stringResource(R.string.settings_cancel)) } },
        )
    }
    leaving?.let { owner ->
        AlertDialog(
            onDismissRequest = { leaving = null },
            title = { Text(ConnectionCopy.leaveTitle(owner.ownerName)) },
            text = { Text(ConnectionCopy.leaveDetail(owner.ownerName)) },
            confirmButton = {
                TextButton(onClick = {
                    leaving = null
                    state.leave(owner.membershipID)
                }) { Text(stringResource(R.string.settings_leave), color = error) }
            },
            dismissButton = { TextButton(onClick = { leaving = null }) { Text(stringResource(R.string.settings_cancel)) } },
        )
    }
    if (confirmingSignOut) {
        AlertDialog(
            onDismissRequest = { confirmingSignOut = false },
            title = { Text(stringResource(R.string.settings_sign_out_title)) },
            text = { Text(stringResource(R.string.settings_sign_out_detail)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmingSignOut = false
                    state.signOut()
                }) { Text(stringResource(R.string.settings_sign_out), color = error) }
            },
            dismissButton = { TextButton(onClick = { confirmingSignOut = false }) { Text(stringResource(R.string.settings_cancel)) } },
        )
    }
    if (noEmailApp) NoEmailAppDialog(onDismiss = { noEmailApp = false })
}

/**
 * The owner's computers: before signing in, only the way in; then the account or the state
 * with whatever applies to it, and Advanced, the one part of Settings that names Tailscale.
 * A guest never sees either.
 */
@Composable
private fun OwnerGroups(owner: OwnerSettings, state: SettingsState, onSignOut: () -> Unit) {
    val primary = MaterialTheme.colorScheme.primary
    SettingsGroup {
        val login = owner.state as? NodeState.NeedsLogin
        if (login != null) {
            row { shape -> SettingsRow(shape, stringResource(R.string.settings_sign_in), color = primary, onClick = { state.signIn(login.url) }) }
        } else {
            row { shape ->
                SettingsRow(
                    shape, stringResource(R.string.settings_your_computers), icon = R.drawable.ic_computer,
                    supporting = owner.connectedAs?.let { stringResource(R.string.settings_connected_as, it) } ?: owner.state.summary,
                )
            }
            if (owner.state == NodeState.AwaitingApproval) {
                row { shape -> SettingsRow(shape, stringResource(R.string.settings_approve), color = primary, onClick = state.approve) }
            }
            if (owner.account != null) {
                row { shape -> SettingsRow(shape, stringResource(R.string.settings_sign_out), color = MaterialTheme.colorScheme.error, onClick = onSignOut) }
            }
        }
        // While no account is signed in, Use my own computers can still be taken back.
        if (owner.hasNoAccount) {
            row { shape -> SettingsRow(shape, stringResource(R.string.settings_stop_using_my_own_computers), color = primary, onClick = state.stopUsingMyOwnComputers) }
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
    GroupedScreen(stringResource(R.string.settings_acknowledgements), onBack = onBack, scrolls = false) { padding ->
        LazyColumn(Modifier.fillMaxSize(), contentPadding = padding, verticalArrangement = Arrangement.spacedBy(12.dp)) {
            items(paragraphs) { paragraph ->
                SelectionContainer {
                    Text(
                        paragraph,
                        style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                        modifier = Modifier.padding(horizontal = 16.dp),
                    )
                }
            }
        }
    }
}

/**
 * A full-screen destination on the grouped background, which its top app bar shares, with a
 * back arrow. The content gets the bars' padding, and scrolls unless it scrolls itself.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun GroupedScreen(title: String, onBack: () -> Unit, scrolls: Boolean = true, content: @Composable (PaddingValues) -> Unit) {
    val background = LocalOvenColors.current.groupedBackground
    Scaffold(
        containerColor = background,
        topBar = {
            TopAppBar(
                title = { Text(title) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(painterResource(R.drawable.ic_arrow_back), contentDescription = stringResource(R.string.settings_back))
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = background, scrolledContainerColor = background),
            )
        },
    ) { bars ->
        if (!scrolls) return@Scaffold content(PaddingValues(top = bars.calculateTopPadding() + 8.dp, bottom = bars.calculateBottomPadding() + 16.dp))
        Box(Modifier.fillMaxSize().padding(top = bars.calculateTopPadding()), contentAlignment = Alignment.TopCenter) {
            Column(Modifier.widthIn(max = 640.dp).fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp)) {
                Spacer(Modifier.height(8.dp))
                content(PaddingValues())
                Spacer(Modifier.height(bars.calculateBottomPadding() + 24.dp))
            }
        }
    }
}

/** Collects a group's rows, so each can take its place's shape. */
private class GroupRows {
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
private fun SettingsGroup(header: String? = null, footer: (@Composable () -> Unit)? = null, rows: GroupRows.() -> Unit) {
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

private fun segmentShape(index: Int, count: Int, outer: CornerBasedShape, inner: CornerBasedShape): Shape {
    val top = if (index == 0) outer else inner
    val bottom = if (index == count - 1) outer else inner
    return inner.copy(topStart = top.topStart, topEnd = top.topEnd, bottomStart = bottom.bottomStart, bottomEnd = bottom.bottomEnd)
}

@Composable
private fun Footer(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 16.dp))
}

/**
 * One row: a headline, maybe with a value or detail under it, a monochrome icon and a
 * trailing control. A row without [onClick] reads as one element.
 */
@Composable
private fun SettingsRow(
    shape: Shape,
    headline: String,
    supporting: String? = null,
    @DrawableRes icon: Int? = null,
    color: Color = Color.Unspecified,
    trailing: (@Composable () -> Unit)? = null,
    onClick: (() -> Unit)? = null,
) {
    val action = if (onClick != null) Modifier.clickable(role = Role.Button, onClick = onClick) else Modifier.semantics(mergeDescendants = true) {}
    ListItem(
        headlineContent = { Text(headline, color = color) },
        modifier = Modifier.clip(shape).then(action),
        supportingContent = supporting?.let { { Text(it) } },
        leadingContent = icon?.let { { Icon(painterResource(it), contentDescription = null) } },
        trailingContent = trailing,
        colors = ListItemDefaults.colors(containerColor = LocalOvenColors.current.groupedRow, leadingIconColor = MaterialTheme.colorScheme.onSurfaceVariant),
    )
}
