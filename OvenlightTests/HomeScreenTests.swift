import XCTest
@testable import Ovenlight

final class HomeGridLayoutTests: XCTestCase {
    /// The width of an iPhone 17 Pro Max.
    private let proMax = HomeGridLayout(width: 440, labelHeight: 15, largeText: false)

    func testFourColumnsInsideTheMargins() {
        XCTAssertEqual(proMax.columns, 4)
        XCTAssertEqual(proMax.iconSize, 64)
        XCTAssertEqual(HomeGridLayout(width: 375, labelHeight: 15, largeText: false).iconSize, 60)
        XCTAssertGreaterThan(proMax.tileCenter(of: 0).x - proMax.tileSize.width / 2, 0)
        XCTAssertLessThan(proMax.tileCenter(of: 3).x + proMax.tileSize.width / 2, 440)
        // The first row sits a little below the top controls, and rows follow one pitch.
        XCTAssertEqual(proMax.tileCenter(of: 0).y - proMax.tileHeight / 2, HomeGridLayout.topMargin, accuracy: 0.001)
        XCTAssertEqual(proMax.tileCenter(of: 9).y - proMax.tileCenter(of: 1).y, 2 * proMax.rowPitch, accuracy: 0.001)
    }

    func testLargeTextGetsThreeColumnsAndBiggerIcons() {
        let large = HomeGridLayout(width: 440, labelHeight: 2 * 30, largeText: true)
        XCTAssertEqual(large.columns, 3)
        XCTAssertEqual(large.iconSize, 72)
        XCTAssertGreaterThanOrEqual(large.tileSize.width, 72)
        XCTAssertLessThanOrEqual(large.tileCenter(of: 2).x + large.tileSize.width / 2, 440)
        XCTAssertEqual(large.tileCenter(of: 3).x, large.tileCenter(of: 0).x, accuracy: 0.001, "the fourth app starts the next row")
    }

    func testLandscapeFitsMoreColumns() {
        // An iPhone 17 Pro on its side, inside the safe area.
        XCTAssertEqual(HomeGridLayout(width: 750, labelHeight: 15, largeText: false).columns, 7)
        XCTAssertEqual(HomeGridLayout(width: 750, labelHeight: 2 * 30, largeText: true).columns, 4)
    }

    func testEverySlotIsFoundFromItsOwnCenterAndNearIt() {
        for index in 0..<60 {
            let center = proMax.tileCenter(of: index)
            XCTAssertEqual(proMax.slot(near: center), index)
            let nudged = CGPoint(x: center.x + proMax.columnWidth * 0.4, y: center.y - proMax.rowPitch * 0.4)
            XCTAssertEqual(proMax.slot(near: nudged), index)
        }
    }

    func testPointsOutsideTheGridClampToTheNearestSlot() {
        XCTAssertEqual(proMax.slot(near: CGPoint(x: -30, y: -50)), 0)
        XCTAssertEqual(proMax.slot(near: CGPoint(x: 470, y: 2)), 3)
        XCTAssertEqual(proMax.slot(near: CGPoint(x: 470, y: proMax.tileCenter(of: 20).y)), 23)
    }
}

final class HomeReorderTests: XCTestCase {
    private struct Item: Identifiable, Equatable { let id: String }
    private let items = ["a", "b", "c", "d", "e"].map(Item.init)

    private func order(_ id: String, _ index: Int) -> String {
        HomeReorder.moving(items, id: id, to: index).map(\.id).joined()
    }

    func testMovesForwardAndBackToTheDroppedIndex() {
        XCTAssertEqual(order("a", 2), "bcade")
        XCTAssertEqual(order("e", 1), "aebcd")
        XCTAssertEqual(order("c", 2), "abcde")
    }

    func testClampsToTheEndsAndIgnoresUnknownIDs() {
        XCTAssertEqual(order("b", 99), "acdeb")
        XCTAssertEqual(order("d", -3), "dabce")
        XCTAssertEqual(order("z", 0), "abcde")
    }

    @MainActor
    func testRegistryMoveSavesTheOrder() throws {
        let dir = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        defer { try? FileManager.default.removeItem(at: dir) }
        let registry = AppRegistry(directory: dir, session: { _ in nil })
        let apps = ["one", "two", "three", "four"].map { registry.add(url: URL(string: "https://\($0).example.com/")!) }

        registry.move(apps[0].id, to: 2)
        XCTAssertEqual(registry.apps.map(\.id), [apps[1], apps[2], apps[0], apps[3]].map(\.id))
        registry.move(apps[3].id, to: 0)

        let reopened = AppRegistry(directory: dir, session: { _ in nil })
        XCTAssertEqual(reopened.apps.map(\.id), [apps[3], apps[1], apps[2], apps[0]].map(\.id))
    }
}

/// The blue dot on apps that arrived on their own and haven't been opened.
@MainActor
final class NewAppDotTests: XCTestCase {
    private var dir: URL!

    override func setUp() {
        dir = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
    }

    override func tearDown() {
        try? FileManager.default.removeItem(at: dir)
    }

    private func registry() -> AppRegistry { AppRegistry(directory: dir, session: { _ in nil }) }

    func testSharedAndDiscoveredAppsAreNewUntilOpened() async {
        let registry = registry()
        let added = registry.add(url: URL(string: "https://typed.example.com/")!)
        let shared = registry.addShared(name: "Echo Board", host: "echo-board.taildef456.ts.net", slug: "echo-board",
                                        membershipID: "m1", sharedBy: "Riley")
        await registry.mergeDiscovered([DiscoveredApp(host: "notes.tailabc123.ts.net", manifest: OvenlightManifest(name: "Notes", version: 1))])
        let notes = registry.apps.first { $0.name == "Notes" }!
        XCTAssertEqual(registry.apps.map(\.isNew), [false, true, true], "an app the user added by address isn't new")

        registry.markOpened(shared.id)
        XCTAssertEqual(registry.apps.map(\.isNew), [false, false, true])

        // Persisted per app: a relaunch keeps both the cleared dot and the remaining one.
        let reopened = self.registry()
        XCTAssertEqual(reopened.apps.map(\.id), [added.id, shared.id, notes.id])
        XCTAssertEqual(reopened.apps.map(\.isNew), [false, false, true])

        // Discovery finding it again, or opening it twice, never brings the dot back.
        await reopened.mergeDiscovered([DiscoveredApp(host: "notes.tailabc123.ts.net", manifest: OvenlightManifest(name: "Notes", version: 1))])
        XCTAssertTrue(reopened.apps[2].isNew)
        reopened.markOpened(notes.id)
        reopened.markOpened(notes.id)
        await reopened.mergeDiscovered([DiscoveredApp(host: "notes.tailabc123.ts.net", manifest: OvenlightManifest(name: "Notes 2", version: 1))])
        XCTAssertFalse(self.registry().apps.contains(where: \.isNew))
    }
}
