package com.snowyghost.ovenlight

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.ScrollState
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/** A person on one computer. */
private data class PersonRef(val machineID: String, val person: String)

/**
 * Everyone the owner shares with, across apps, those who opened one most recently first. People
 * who stopped gather at the end, where selecting removes several at once (touch and hold a row,
 * or Select in the menu), and their devices leave the owner's Tailscale network.
 */
@Composable
internal fun SharingPeopleList(state: SharingState, scroll: ScrollState, open: (SharingPage) -> Unit, back: () -> Unit, scope: CoroutineScope) {
    val resources = LocalResources.current
    val machines by state.machines.collectAsState()
    val groups = machines.map { it to GuestPerson.byActivity(it.guests) }.filter { it.second.isNotEmpty() }
    var selecting by remember { mutableStateOf(false) }
    val selection = remember { mutableStateListOf<PersonRef>() }
    var removing by remember { mutableStateOf(emptyList<PersonRef>()) }
    var working by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) { state.refreshIfStale() }
    // Whoever left the list (removed elsewhere, say) can't stay selected.
    LaunchedEffect(groups) {
        val listed = groups.flatMap { (machine, people) -> people.map { PersonRef(machine.id, it.id) } }.toSet()
        selection.retainAll(listed)
    }
    val stopSelecting = {
        selecting = false
        selection.clear()
    }
    BackHandler(selecting, stopSelecting)

    fun name(ref: PersonRef): String = state.machines.value.firstOrNull { it.id == ref.machineID }
        ?.let { machine -> GuestPerson.all(machine.guests.guests).firstOrNull { it.id == ref.person }?.name } ?: resources.getString(R.string.people_them)

    suspend fun remove(refs: List<PersonRef>) {
        val reason = if (refs.size == 1) {
            resources.getString(R.string.sharing_remove_person, name(refs[0]))
        } else {
            resources.getQuantityString(R.plurals.people_reason_several, refs.size, refs.size)
        }
        if (refs.isEmpty() || !state.confirm(reason)) return
        working = true
        val problems = mutableListOf<String>()
        val current = state.machines.value
        if (refs.any { ref -> current.none { it.id == ref.machineID } }) problems += AdminError.Unreachable.message
        for (machine in current) {
            val theirs = refs.filter { it.machineID == machine.id }
            if (theirs.isEmpty()) continue
            val client = state.client(machine)
            if (client == null) {
                problems += AdminError.Unreachable.message
                continue
            }
            for (ref in theirs) {
                try {
                    problems += client.removePerson(ref.person).errors.orEmpty()
                } catch (failure: AdminError) {
                    problems += failure.message
                }
            }
        }
        error = problems.firstOrNull()
        state.refresh()
        working = false
        stopSelecting()
    }

    val title = if (selecting && selection.isNotEmpty()) pluralStringResource(R.plurals.people_selected, selection.size, selection.size) else stringResource(R.string.sharing_people)
    GroupedScreen(
        title, onBack = if (selecting) stopSelecting else back, scroll = scroll, refresh = state.refresh,
        navigationIcon = if (selecting) R.drawable.ic_close else R.drawable.ic_arrow_back,
        navigationLabel = stringResource(if (selecting) R.string.people_done_selecting else R.string.sharing_back),
        actions = {
            when {
                working -> {
                    val label = stringResource(R.string.people_removing)
                    Box(Modifier.size(48.dp).semantics { contentDescription = label }, contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.5.dp)
                    }
                }
                selecting -> IconButton(onClick = { removing = selection.toList() }, enabled = selection.isNotEmpty()) {
                    Icon(painterResource(R.drawable.ic_person_remove), contentDescription = stringResource(R.string.sharing_remove))
                }
                groups.isNotEmpty() -> {
                    var menu by remember { mutableStateOf(false) }
                    Box {
                        IconButton(onClick = { menu = true }) {
                            Icon(painterResource(R.drawable.ic_more_vert), contentDescription = stringResource(R.string.launcher_more))
                        }
                        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                            DropdownMenuItem(text = { Text(stringResource(R.string.people_select)) }, onClick = {
                                menu = false
                                selecting = true
                            })
                        }
                    }
                }
            }
        },
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(24.dp)) {
            error?.let { SharingError(it) }
            if (groups.isEmpty()) Unavailable(stringResource(R.string.sharing_no_one_yet), stringResource(R.string.people_no_one))
            for ((machine, people) in groups) {
                val last = machine.id == groups.last().first.id
                // A connector that doesn't note opens can't order them.
                val footer = listOfNotNull(
                    if (machine.guests.openedSince != null) stringResource(R.string.people_recent_first) else null,
                    stringResource(R.string.people_removing_footer),
                ).joinToString(" ")
                SettingsGroup(header = if (groups.size > 1) machine.adminHost else null, footer = if (last) ({ Footer(footer) }) else null) {
                    for (person in people) {
                        val ref = PersonRef(machine.id, person.id)
                        // Removing them would cancel App Review's unused invites: never in passing.
                        val selectable = !machine.guests.hasOpenInvites(person.id, review = true)
                        val selected = ref in selection
                        row { shape ->
                            SettingsRow(
                                shape, person.name, supporting = person.summary(machine.guests.openedSince),
                                leading = if (selecting) ({ Checkbox(checked = selected, onCheckedChange = null, enabled = selectable) }) else null,
                                trailing = if (selecting) {
                                    null
                                } else {
                                    {
                                        RowMenu(person.name) { close ->
                                            AppMenuItem(close, stringResource(R.string.sharing_remove_person, person.name), R.drawable.ic_person_remove, destructive = true) {
                                                removing = listOf(ref)
                                            }
                                        }
                                    }
                                },
                                onLongClick = if (!selecting && selectable && !working) ({
                                    selecting = true
                                    selection += ref
                                }) else null,
                                onClick = {
                                    when {
                                        !selecting -> open(SharingPage.Person(machine.id, person.id))
                                        selected -> selection -= ref
                                        selectable -> selection += ref
                                    }
                                },
                            )
                        }
                    }
                }
            }
        }
    }

    if (removing.isNotEmpty()) {
        val refs = removing
        val who = if (refs.size == 1) name(refs[0]) else stringResource(R.string.people_they)
        val invited = refs.any { ref -> machines.any { it.id == ref.machineID && it.guests.hasOpenInvites(ref.person) } }
        val message = stringResource(R.string.people_remove_message, who) + if (invited) " " + stringResource(R.string.people_invites_canceled) else ""
        Confirmation(
            if (refs.size == 1) stringResource(R.string.sharing_remove_title, name(refs[0])) else pluralStringResource(R.plurals.people_remove_several_title, refs.size, refs.size),
            message, stringResource(R.string.sharing_remove), onDismiss = { removing = emptyList() },
        ) { scope.launch { remove(refs) } }
    }
}

/** What to remove from one person's page. */
private sealed interface Removal {
    data class App(val app: AdminApp) : Removal

    data class Device(val id: String) : Removal

    data object Everything : Removal
}

/**
 * One person: when they last opened each app, and on each device when they have more than one,
 * with removing them from an app, a device they no longer use, or everything.
 */
@Composable
internal fun SharingPersonView(state: SharingState, page: SharingPage.Person, scroll: ScrollState, back: () -> Unit, scope: CoroutineScope) {
    val resources = LocalResources.current
    val machines by state.machines.collectAsState()
    fun person(machines: List<OwnerSharing.Machine>) =
        machines.firstOrNull { it.id == page.machineID }?.let { machine -> GuestPerson.all(machine.guests.guests).firstOrNull { it.id == page.personID } }
    val machine = machines.firstOrNull { it.id == page.machineID }
    val person = person(machines)
    var removing by remember { mutableStateOf<Removal?>(null) }
    var working by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    fun remove(removal: Removal) = scope.launch {
        val found = person ?: return@launch
        val reason = when (removal) {
            is Removal.App -> resources.getString(R.string.person_reason_from, found.name, removal.app.name)
            is Removal.Device -> resources.getString(R.string.person_reason_device, found.name)
            Removal.Everything -> resources.getString(R.string.sharing_remove_person, found.name)
        }
        if (machine == null || !state.confirm(reason)) return@launch
        val client = state.client(machine) ?: return@launch
        working = true
        error = try {
            when (removal) {
                is Removal.App -> client.removeGuest(found, removal.app.slug)
                is Removal.Device -> client.removeDevice(removal.id)
                Removal.Everything -> client.removePerson(found.id)
            }.errors?.firstOrNull()
        } catch (failure: AdminError) {
            failure.message
        }
        state.refresh()
        working = false
        // Gone from every app: back to the list, unless there's a problem to read here.
        if (person(state.machines.value) == null && error == null) back()
    }

    GroupedScreen(person?.name.orEmpty(), onBack = back, scroll = scroll, refresh = state.refresh) {
        Column(verticalArrangement = Arrangement.spacedBy(24.dp)) {
            error?.let { SharingError(it) }
            if (machine == null || person == null) {
                if (!working) Unavailable(stringResource(R.string.person_not_shared))
                return@Column
            }
            val since = machine.guests.openedSince
            SettingsGroup(header = stringResource(R.string.sharing_apps)) {
                for (app in machine.apps.filter { app -> person.guests.any { it.app == app.slug } }) {
                    row { shape ->
                        SettingsRow(
                            shape, app.name, supporting = GuestActivity(person.guests.filter { it.app == app.slug }, since).caption,
                            leading = { AdminAppIcon(state, app) },
                            trailing = {
                                RowMenu(app.name) { close ->
                                    AppMenuItem(close, stringResource(R.string.sharing_remove), R.drawable.ic_person_remove, destructive = true) { removing = Removal.App(app) }
                                }
                            },
                        )
                    }
                }
            }
            val devices = person.guests.groupBy { it.deviceId }.values.sortedBy { guests -> guests.minOf { it.claimedAt } }
            if (devices.size > 1) {
                SettingsGroup(header = stringResource(R.string.person_devices), footer = { Footer(stringResource(R.string.person_devices_footer)) }) {
                    for (guests in devices) {
                        row { shape ->
                            val joined = stringResource(R.string.person_joined, joinedDay.format(guests.minOf { it.claimedAt }.atZone(ZoneId.systemDefault())))
                            SettingsRow(
                                shape, joined, supporting = GuestActivity(guests, since).caption,
                                trailing = {
                                    RowMenu(joined) { close ->
                                        AppMenuItem(close, stringResource(R.string.sharing_remove), R.drawable.ic_delete, destructive = true) { removing = Removal.Device(guests[0].deviceId) }
                                    }
                                },
                            )
                        }
                    }
                }
            }
            SettingsGroup {
                row { shape ->
                    if (working) {
                        Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                    } else {
                        SettingsRow(shape, stringResource(R.string.sharing_remove_person, person.name), color = MaterialTheme.colorScheme.error, onClick = { removing = Removal.Everything })
                    }
                }
            }
        }
    }

    val removal = removing
    if (removal != null && person != null) {
        val name = person.name
        val title = when (removal) {
            is Removal.App -> stringResource(R.string.person_remove_from_title, name, removal.app.name)
            is Removal.Device -> stringResource(R.string.person_remove_device_title)
            Removal.Everything -> stringResource(R.string.sharing_remove_title, name)
        }
        val message = when (removal) {
            is Removal.App -> stringResource(R.string.sharing_remove_from_app_message, name, removal.app.name)
            is Removal.Device -> stringResource(R.string.person_remove_device_message, name)
            Removal.Everything -> stringResource(R.string.people_remove_message, name) +
                if (machine?.guests?.hasOpenInvites(page.personID) == true) " " + stringResource(R.string.people_invites_canceled) else ""
        }
        Confirmation(title, message, stringResource(R.string.sharing_remove), onDismiss = { removing = null }) { remove(removal) }
    }
}

/** "Oct 14, 2026", as iOS's abbreviated date; the app isn't localized, so it's English as the rest. */
private val joinedDay = DateTimeFormatter.ofPattern("MMM d, yyyy", Locale.US)
