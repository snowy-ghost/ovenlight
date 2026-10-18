import SwiftUI

/// A person or one of their apps or devices in a sharing list: a name, then a caption such
/// as "2 devices · opened today".
struct GuestPersonRow: View {
    let name: String
    let detail: String

    var body: some View {
        VStack(alignment: .leading, spacing: 2) {
            Text(name)
            Text(detail).font(.caption).foregroundStyle(Color.secondaryText)
        }
        .accessibilityElement(children: .combine)
    }
}

/// Everyone the owner shares with, across apps, those who opened one most recently first.
/// People who stopped gather at the end, where Select removes several at once, and their
/// devices leave the owner's Tailscale network.
struct SharingPeopleList: View {
    @EnvironmentObject private var registry: AppRegistry
    @ObservedObject private var sharing = OwnerSharing.shared
    @State private var editMode = EditMode.inactive
    @State private var selection = Set<Ref>()
    @State private var removing: [Ref] = []
    @State private var working = false
    @State private var error: String?

    /// A person on one machine.
    struct Ref: Hashable {
        var machineID: String
        var person: String
    }

    private struct MachinePeople: Identifiable {
        var machine: OwnerSharing.Machine
        var people: [GuestPerson]
        var id: String { machine.id }
    }

    private var groups: [MachinePeople] {
        sharing.machines.map { MachinePeople(machine: $0, people: GuestPerson.byActivity($0.guests)) }.filter { !$0.people.isEmpty }
    }

    var body: some View {
        let groups = groups
        List(selection: $selection) {
            if let error {
                Section { Label(error, systemImage: "exclamationmark.triangle.fill").labelStyle(.warning) }
                    .ovenlightRows()
            }
            if groups.isEmpty {
                ContentUnavailableView("No One Yet", systemImage: "person.2",
                                       description: Text("People you share apps with appear here."))
                    .ovenlightRows()
            }
            ForEach(groups) { group in
                Section {
                    ForEach(group.people) { person in
                        let ref = Ref(machineID: group.machine.id, person: person.id)
                        let row = GuestPersonRow(name: person.name, detail: person.summary(since: group.machine.guests.openedSince))
                        // While selecting, a plain row: a link there shows dimmed.
                        Group {
                            if editMode.isEditing {
                                row
                            } else {
                                NavigationLink { SharingPersonView(machineID: group.machine.id, personID: person.id) } label: { row }
                            }
                        }
                        .tag(ref)
                        // Removing them would cancel App Review's unused invites: never in passing.
                        .selectionDisabled(group.machine.guests.hasOpenInvites(person.id, review: true))
                        .swipeActions {
                            Button("Remove", role: .destructive) { removing = [ref] }
                        }
                    }
                } header: {
                    if groups.count > 1 { Text(group.machine.adminHost) }
                } footer: {
                    if group.id == groups.last?.id {
                        // A connector that doesn't note opens can't order them.
                        Text((group.machine.guests.openedSince == nil ? "" : "Those who opened your apps most recently come first. ")
                             + "Removing someone also removes their devices from your Tailscale network.")
                    }
                }
                .ovenlightRows()
            }
        }
        .environment(\.editMode, $editMode)
        .ovenlightGroupedBackground()
        .navigationTitle(editMode.isEditing && !selection.isEmpty ? "\(selection.count) Selected" : "People")
        .announcing(error)
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            if !groups.isEmpty {
                ToolbarItem(placement: .topBarTrailing) {
                    Button(editMode.isEditing ? "Done" : "Select") {
                        withAnimation {
                            editMode = editMode.isEditing ? .inactive : .active
                            selection = []
                        }
                    }
                    .disabled(working)
                }
            }
            if editMode.isEditing {
                ToolbarItem(placement: .bottomBar) {
                    if working {
                        ProgressView()
                    } else {
                        Button("Remove", role: .destructive) { removing = Array(selection) }
                            .tint(.red)
                            .disabled(selection.isEmpty)
                    }
                }
            }
        }
        .refreshable { await sharing.refresh(apps: registry.apps) }
        .task { await sharing.refreshIfStale(apps: registry.apps) }
        .animation(.default, value: sharing.machines)
        // Whoever left the list (removed elsewhere, say) can't stay selected.
        .onChange(of: sharing.machines) {
            let listed = Set(groups.flatMap { group in group.people.map { Ref(machineID: group.id, person: $0.id) } })
            selection.formIntersection(listed)
        }
        .alert(removalTitle, isPresented: Binding(get: { !removing.isEmpty }, set: { if !$0 { removing = [] } })) {
            Button("Cancel", role: .cancel) {}
            Button("Remove", role: .destructive) {
                let refs = removing
                Task { await remove(refs) }
            }
        } message: {
            Text(removalMessage)
        }
    }

    private func name(_ ref: Ref) -> String {
        sharing.machines.first { $0.id == ref.machineID }
            .flatMap { GuestPerson.all($0.guests.guests).first { $0.id == ref.person }?.name } ?? "Them"
    }

    private var removalTitle: String {
        removing.count == 1 ? "Remove \(name(removing[0]))?" : "Remove \(removing.count) People?"
    }

    private var removalMessage: String {
        let who = removing.count == 1 ? name(removing[0]) : "They"
        let invited = removing.contains { ref in
            sharing.machines.contains { $0.id == ref.machineID && $0.guests.hasOpenInvites(ref.person) }
        }
        return "\(who) can’t open your apps anymore, on any device, and their devices are removed from your Tailscale network."
            + (invited ? " Their unused invites are canceled too." : "")
    }

    private func remove(_ refs: [Ref]) async {
        guard !refs.isEmpty, await OwnerAuth.confirm(refs.count == 1 ? "Remove \(name(refs[0]))" : "Remove \(refs.count) people") else { return }
        working = true
        var problems: [String] = []
        if refs.contains(where: { ref in !sharing.machines.contains { $0.id == ref.machineID } }) {
            problems.append(AdminError.unreachable.message)
        }
        for machine in sharing.machines {
            let theirs = refs.filter { $0.machineID == machine.id }
            guard !theirs.isEmpty else { continue }
            guard let client = sharing.client(for: machine) else {
                problems.append(AdminError.unreachable.message)
                continue
            }
            for ref in theirs {
                do {
                    problems += try await client.removePerson(ref.person).errors ?? []
                } catch let failure as AdminError {
                    problems.append(failure.message)
                } catch {
                    problems.append(AdminError.unreachable.message)
                }
            }
            client.session.finishTasksAndInvalidate()
        }
        error = problems.first
        await sharing.refresh(apps: registry.apps)
        working = false
        withAnimation {
            selection = []
            editMode = .inactive
        }
    }
}

/// One person: when they last opened each app, and on each device when they have more than
/// one, with removing them from an app, a device they no longer use, or everything.
struct SharingPersonView: View {
    let machineID: String
    let personID: String

    @EnvironmentObject private var registry: AppRegistry
    @Environment(\.dismiss) private var dismiss
    @ObservedObject private var sharing = OwnerSharing.shared
    @State private var removing: Removal?
    @State private var working = false
    @State private var error: String?

    enum Removal: Equatable {
        case app(AdminApp)
        case device(String)
        case everything
    }

    private var machine: OwnerSharing.Machine? { sharing.machines.first { $0.id == machineID } }
    private var person: GuestPerson? {
        machine.flatMap { machine in GuestPerson.all(machine.guests.guests).first { $0.id == personID } }
    }

    var body: some View {
        List {
            if let error {
                Section { Label(error, systemImage: "exclamationmark.triangle.fill").labelStyle(.warning) }
                    .ovenlightRows()
            }
            if let machine, let person {
                let since = machine.guests.openedSince
                Section("Apps") {
                    ForEach(machine.apps.filter { app in person.guests.contains { $0.app == app.slug } }) { app in
                        let guests = person.guests.filter { $0.app == app.slug }
                        HStack(spacing: 12) {
                            AdminAppIcon(app: app)
                            GuestPersonRow(name: app.name, detail: GuestActivity(guests, since: since).caption)
                        }
                        .swipeActions {
                            Button("Remove", role: .destructive) { removing = .app(app) }
                        }
                    }
                }
                .ovenlightRows()
                let devices = Dictionary(grouping: person.guests, by: \.deviceId).values
                    .sorted { $0.map(\.claimedAt).min()! < $1.map(\.claimedAt).min()! }
                if devices.count > 1 {
                    Section {
                        ForEach(devices, id: \.first!.deviceId) { guests in
                            GuestPersonRow(name: "Joined \(guests.map(\.claimedAt).min()!.formatted(date: .abbreviated, time: .omitted))",
                                           detail: GuestActivity(guests, since: since).caption)
                                .swipeActions {
                                    Button("Remove", role: .destructive) { removing = .device(guests[0].deviceId) }
                                }
                        }
                    } header: {
                        Text("Devices")
                    } footer: {
                        Text("Swipe to remove a device they no longer use, such as an old phone. It leaves your Tailscale network.")
                    }
                    .ovenlightRows()
                }
                Section {
                    if working {
                        HStack { Spacer(); ProgressView(); Spacer() }
                    } else {
                        Button("Remove \(person.name)", role: .destructive) { removing = .everything }
                    }
                }
                .ovenlightRows()
            } else if !working {
                ContentUnavailableView("Not Shared Anymore", systemImage: "person.slash")
                    .ovenlightRows()
            }
        }
        .ovenlightGroupedBackground()
        .navigationTitle(person?.name ?? "")
        .announcing(error)
        .navigationBarTitleDisplayMode(.inline)
        .refreshable { await sharing.refresh(apps: registry.apps) }
        .alert(removalTitle, isPresented: Binding(get: { removing != nil }, set: { if !$0 { removing = nil } })) {
            Button("Cancel", role: .cancel) {}
            Button("Remove", role: .destructive) {
                if let removal = removing { Task { await remove(removal) } }
            }
        } message: {
            Text(removalMessage)
        }
    }

    private var name: String { person?.name ?? "" }

    private var removalTitle: String {
        switch removing {
        case .app(let app): "Remove \(name) from \(app.name)?"
        case .device: "Remove This Device?"
        case .everything, nil: "Remove \(name)?"
        }
    }

    private var removalMessage: String {
        switch removing {
        case .app(let app):
            "\(name) can’t open \(app.name) anymore, on any device. Each of their devices is also removed from your Tailscale network unless it has another of your apps."
        case .device:
            "\(name) can’t open your apps on this device anymore, and it’s removed from your Tailscale network."
        case .everything, nil:
            "\(name) can’t open your apps anymore, on any device, and their devices are removed from your Tailscale network."
                + (machine?.guests.hasOpenInvites(personID) == true ? " Their unused invites are canceled too." : "")
        }
    }

    private func remove(_ removal: Removal) async {
        guard let machine, let person else { return }
        let reason = switch removal {
        case .app(let app): "Remove \(person.name) from \(app.name)"
        case .device: "Remove \(person.name)’s device"
        case .everything: "Remove \(person.name)"
        }
        guard await OwnerAuth.confirm(reason), let client = sharing.client(for: machine) else { return }
        defer { client.session.finishTasksAndInvalidate() }
        working = true
        do {
            let result = switch removal {
            case .app(let app): try await client.removeGuest(person, app: app.slug)
            case .device(let id): try await client.removeDevice(id)
            case .everything: try await client.removePerson(person.id)
            }
            error = result.errors?.first
        } catch let failure as AdminError {
            error = failure.message
        } catch {
            self.error = AdminError.unreachable.message
        }
        await sharing.refresh(apps: registry.apps)
        working = false
        // Gone from every app: back to the list, unless there's a problem to read here.
        if self.person == nil, error == nil { dismiss() }
    }
}
