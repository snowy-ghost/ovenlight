import SwiftUI

/// What an app's long-press menu offers for sharing. The owner shares and sees people per
/// app; a guest reports a problem or leaves, and never sees Share or People.
struct AppMenuPlan: Equatable {
    enum Share: Equatable {
        case hidden
        case available
        /// Published, but not shareable yet; that takes a typed confirmation on its computer.
        case notShareable
    }

    var share: Share = .hidden
    /// People and how many have it, when the owner can share the app.
    var people: Int?
    var reportProblem = false
    /// Whose apps a guest can leave.
    var leaveOwner: String?

    static let notShareableDetail = "Make it shareable on your computer first"

    static func plan(for app: WebApp, admin: (app: AdminApp, guests: AdminGuestList)?) -> AppMenuPlan {
        if app.isShared {
            return AppMenuPlan(reportProblem: true, leaveOwner: app.sharedBy ?? "Its Owner")
        }
        guard let admin else { return AppMenuPlan() }
        guard admin.app.shareable else { return AppMenuPlan(share: .notShareable) }
        let people = GuestPerson.all(admin.guests.guests.filter { $0.app == admin.app.slug }).count
        return AppMenuPlan(share: .available, people: people)
    }

    static func peopleTitle(_ count: Int) -> String { count > 0 ? "People (\(count))" : "People" }
}

/// One app's people, from its long-press menu.
struct AppPeopleSheet: View {
    let machineID: String
    let slug: String
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        NavigationStack {
            SharingAppView(machineID: machineID, slug: slug)
                .toolbar { ToolbarItem(placement: .confirmationAction) { Button("Done") { dismiss() } } }
        }
    }
}

#if DEBUG
/// Simulator screenshots of sharing without a connector: `-sharingFixture YES` fills in
/// what a connector would report for Book Club (shareable, two guests, an open
/// invite) and Budget (not shareable), and shows Echo Board as shared by Riley.
enum SharingFixture {
    static var isOn: Bool { UserDefaults.standard.bool(forKey: "sharingFixture") }

    static func apply(to app: WebApp) -> WebApp {
        guard isOn, app.name == "Echo Board" else { return app }
        var shared = app
        shared.membershipID = "fixture"
        shared.sharedBy = "Riley"
        return shared
    }

    static func machines(for apps: [WebApp]) -> [OwnerSharing.Machine] {
        func host(_ name: String) -> String? { apps.first { $0.name == name }.map { Discovery.normalizedHost($0.host) } }
        guard let club = host("Book Club"), let budget = host("Budget") else { return [] }
        let now = Date.now
        var machine = OwnerSharing.Machine(adminHost: club, apps: [
            AdminApp(slug: "book-club", name: "Book Club", url: "https://\(club)/", online: true, shareable: true, guests: 2),
            AdminApp(slug: "budget", name: "Budget", url: "https://\(budget)/", online: true, shareable: false, guests: 0),
        ])
        machine.guests = AdminGuestList(
            guests: [
                AdminGuest(deviceId: "n1", person: "p1", name: "Sam", app: "book-club", deviceName: "ovenlight-4k2x9q", claimedAt: now.addingTimeInterval(-86_400 * 3)),
                AdminGuest(deviceId: "n4", person: "p1", name: "Sam", app: "book-club", deviceName: "ovenlight-m7c1pw", claimedAt: now.addingTimeInterval(-86_400)),
                AdminGuest(deviceId: "n2", person: "p2", name: "Kim", app: "book-club", deviceName: "ovenlight-r5j8de", claimedAt: now.addingTimeInterval(-3_600 * 5)),
            ],
            invites: [AdminInvite(id: "i3", to: "Lee", person: "p3", app: "book-club", state: AdminInvite.sent,
                                  expires: now.addingTimeInterval(86_400))])
        machine.feedback = [AdminFeedback(id: "f1", at: now.addingTimeInterval(-1_800), app: "book-club", from: "Sam", role: "guest",
                                          note: "The chapter list stops scrolling after chapter 20.")]
        return [machine]
    }
}
#endif
