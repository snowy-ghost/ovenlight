import SwiftUI

/// Where each icon sits in the launcher's grid: equal columns across the width and rows of
/// one height, so a point in the grid maps to a slot without asking the views. Points are
/// in the grid's own coordinates, which scroll with it.
struct HomeGridLayout: Equatable {
    let width: CGFloat
    let columns: Int
    let iconSize: CGFloat
    /// An icon with its label (one line, or two at accessibility sizes).
    let tileHeight: CGFloat

    static let labelGap: CGFloat = 6
    static let rowGap: CGFloat = 24
    /// From the top of the grid to the first row, a little below the top controls.
    static let topMargin: CGFloat = 20

    init(width: CGFloat, labelHeight: CGFloat, largeText: Bool) {
        self.width = max(width, 1)
        // Four across in portrait (three with large text), as many as fit in landscape.
        let usable = width - 2 * Self.sideMargin(width)
        columns = largeText ? max(3, Int(usable / 140)) : max(4, Int(usable / 92))
        iconSize = largeText ? 72 : (width >= 400 ? 64 : 60)
        tileHeight = iconSize + Self.labelGap + labelHeight
    }

    static func sideMargin(_ width: CGFloat) -> CGFloat { (width * 0.064).rounded() }
    var sideMargin: CGFloat { Self.sideMargin(width) }
    var columnWidth: CGFloat { (width - 2 * sideMargin) / CGFloat(columns) }
    var rowPitch: CGFloat { tileHeight + Self.rowGap }
    /// As wide as its column, so labels get the room the Home Screen gives them.
    var tileSize: CGSize { CGSize(width: max(columnWidth - 4, iconSize), height: tileHeight) }

    func tileCenter(of index: Int) -> CGPoint {
        CGPoint(x: sideMargin + columnWidth * (CGFloat(index % columns) + 0.5),
                y: Self.topMargin + CGFloat(index / columns) * rowPitch + tileHeight / 2)
    }

    /// The slot nearest a tile centered at `point`. Not clamped to the number of apps;
    /// callers do that.
    func slot(near point: CGPoint) -> Int {
        let column = Int(((point.x - sideMargin) / columnWidth).rounded(.down))
        let row = Int(((point.y - Self.topMargin - tileHeight / 2) / rowPitch).rounded())
        return max(row, 0) * columns + min(max(column, 0), columns - 1)
    }
}

/// Reordering, as the Home Screen does it: the moved app lands at the index it was dropped
/// on and everything between shifts over by one.
enum HomeReorder {
    static func moving<Item: Identifiable>(_ items: [Item], id: Item.ID, to index: Int) -> [Item] {
        guard let from = items.firstIndex(where: { $0.id == id }) else { return items }
        var items = items
        let item = items.remove(at: from)
        items.insert(item, at: min(max(index, 0), items.count))
        return items
    }
}

/// The apps as one scrolling grid of icons, like the Home Screen. Long-press an icon for
/// its menu or long-press empty space to edit. While editing, icons jiggle, show a remove
/// badge and can be dragged to a new place among the ones on screen.
struct HomeScreenGrid<Menu: View, Preview: View>: View {
    let apps: [WebApp]
    /// Set up for the owner's own machines: the empty grid invites their apps. A guest's
    /// stays blank while the first invite joins.
    let ownerEnabled: Bool
    @Binding var isEditing: Bool
    let reachability: (WebApp) -> AppReachability?
    let open: (WebApp) -> Void
    /// Moves an app to an index in the list, and saves the order.
    let move: (UUID, Int) -> Void
    let remove: (WebApp) -> Void
    let addApp: () -> Void
    @ViewBuilder let menu: (WebApp) -> Menu
    @ViewBuilder let preview: (WebApp) -> Preview

    @EnvironmentObject private var registry: AppRegistry
    @Environment(\.launchNamespace) private var launchNamespace
    @Environment(\.dynamicTypeSize) private var dynamicTypeSize
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @ScaledMetric(relativeTo: .caption) private var labelLine: CGFloat = 15
    /// The "from <owner>" line under a shared app's name.
    @ScaledMetric(relativeTo: .caption2) private var sharedByLine: CGFloat = 14

    @State private var drag: IconDrag?
    @GestureState private var dragActive = false
    @State private var retarget: Task<Void, Never>?
    @State private var pendingSlot: Int?
    @State private var pickups = 0
    @State private var drops = 0
    /// The layout on screen, for a drag that ends outside its gesture (cancelled).
    @State private var layout: HomeGridLayout?

    private static var space: String { "home" }

    /// An icon being dragged: where the finger is, where on the tile it grabbed it, and
    /// the slot it would drop into. Scrolling stops meanwhile, so grid coordinates hold.
    private struct IconDrag {
        var id: UUID
        var location: CGPoint
        var grab: CGSize
        var target: Int
        var dropping = false
    }

    var body: some View {
        GeometryReader { geo in
            let layout = layout(width: geo.size.width)
            ScrollView {
                let order = displayed
                LazyVGrid(columns: Array(repeating: GridItem(.flexible(), spacing: 0), count: layout.columns),
                          spacing: HomeGridLayout.rowGap) {
                    if apps.isEmpty && ownerEnabled {
                        EmptyHomeSlot(size: layout.iconSize, action: addApp)
                            .frame(width: layout.tileSize.width, height: layout.tileSize.height, alignment: .top)
                    }
                    ForEach(Array(order.enumerated()), id: \.element.id) { index, app in
                        tile(app, index: index, in: order, layout: layout)
                    }
                }
                .padding(.horizontal, layout.sideMargin)
                .padding(.top, HomeGridLayout.topMargin)
                .padding(.bottom, HomeGridLayout.rowGap)
                .animation(.spring(response: 0.38, dampingFraction: 0.82), value: order.map(\.id))
                .frame(maxWidth: .infinity, minHeight: geo.size.height, alignment: .top)
                .background { background }
                .overlay {
                    if apps.isEmpty && ownerEnabled {
                        Text("Apps you publish with the [Ovenlight connector](https://ovenlight.app/support#own-computer) on your computers show up here.")
                            .font(.footnote)
                            .foregroundStyle(.secondary)
                            .multilineTextAlignment(.center)
                            .padding(.horizontal, 2 * layout.sideMargin)
                    }
                }
                .overlay(alignment: .topLeading) { lifted(layout) }
                .coordinateSpace(.named(Self.space))
            }
            .scrollDisabled(drag != nil)
            .scrollBounceBehavior(.basedOnSize)
            .onChange(of: layout, initial: true) { _, new in self.layout = new }
        }
        .onChange(of: dragActive) { _, active in
            if !active, drag != nil { endDrag() }
        }
        .onChange(of: isEditing) { _, editing in
            if !editing { drag = nil }
        }
        .sensoryFeedback(.impact(weight: .medium), trigger: pickups)
        .sensoryFeedback(.impact(weight: .light), trigger: drops)
        .sensoryFeedback(.impact(weight: .heavy, intensity: 0.7), trigger: isEditing) { _, editing in editing }
    }

    private func layout(width: CGFloat) -> HomeGridLayout {
        let large = dynamicTypeSize.isAccessibilitySize
        let sharedBy = apps.contains(where: \.isShared) ? sharedByLine : 0
        return HomeGridLayout(width: width, labelHeight: labelLine * (large ? 2 : 1) + sharedBy, largeText: large)
    }

    /// The apps in the order shown: while dragging, with the dragged one in its target slot.
    private var displayed: [WebApp] {
        guard let drag else { return apps }
        return HomeReorder.moving(apps, id: drag.id, to: drag.target)
    }

    /// Empty space: long-press it to edit, tap it to stop.
    private var background: some View {
        Color.clear
            .contentShape(Rectangle())
            .onTapGesture { if isEditing { withAnimation { isEditing = false } } }
            .simultaneousGesture(LongPressGesture(minimumDuration: 0.5).onEnded { _ in
                if !apps.isEmpty && !isEditing { withAnimation { isEditing = true } }
            })
            .accessibilityHidden(true)
    }

    @ViewBuilder
    private func tile(_ app: WebApp, index: Int, in order: [WebApp], layout: HomeGridLayout) -> some View {
        let content = AppTile(app: app, namespace: launchNamespace, reachability: reachability(app),
                              isEditing: isEditing, iconSize: layout.iconSize)
            .frame(width: layout.tileSize.width, height: layout.tileSize.height, alignment: .top)
        Group {
            if isEditing {
                content
                    .overlay(alignment: .top) {
                        if reduceMotion { EditOutline(iconSize: layout.iconSize) }
                    }
                    .overlay(alignment: .top) {
                        RemoveBadge(name: app.name) { remove(app) }
                            .offset(x: -layout.iconSize / 2 + 3, y: -8)
                    }
                    .modifier(Jiggle(seed: app.id.hashValue, active: !reduceMotion && drag?.id != app.id))
                    .opacity(drag?.id == app.id ? 0.001 : 1)
                    .contentShape(Rectangle())
                    .highPriorityGesture(dragGesture(app, index: index, layout: layout))
                    .transition(.identity)
            } else {
                Button { open(app) } label: { content }
                    .buttonStyle(TilePressStyle())
                    .contextMenu {
                        // As on the Home Screen: Edit first, the destructive actions last.
                        Section {
                            Button { withAnimation { isEditing = true } } label: {
                                Label("Edit Apps", systemImage: "apps.iphone")
                            }
                        }
                        menu(app)
                    } preview: {
                        preview(app)
                    }
                    // Both looks swap in place, as on the Home Screen: Done only stops the jiggle.
                    .transition(.identity)
            }
        }
        .accessibilityActions {
            if index > 0 {
                Button("Move Before \(order[index - 1].name)") { accessibleMove(app, to: index - 1) }
            }
            if index < order.count - 1 {
                Button("Move After \(order[index + 1].name)") { accessibleMove(app, to: index + 1) }
            }
        }
    }

    // MARK: Dragging

    private func dragGesture(_ app: WebApp, index: Int, layout: HomeGridLayout) -> some Gesture {
        DragGesture(minimumDistance: 2, coordinateSpace: .named(Self.space))
            .updating($dragActive) { _, active, _ in active = true }
            .onChanged { value in
                if drag == nil {
                    let center = layout.tileCenter(of: index)
                    drag = IconDrag(id: app.id, location: value.location,
                                    grab: CGSize(width: value.startLocation.x - center.x,
                                                 height: value.startLocation.y - center.y),
                                    target: index)
                    pickups += 1
                } else if drag?.dropping == false {
                    drag?.location = value.location
                }
                retargetSoon(layout)
            }
            .onEnded { _ in endDrag() }
    }

    /// Icons make way once the dragged one rests over a slot for a moment, so passing over
    /// them on the way somewhere else doesn't shuffle the grid.
    private func retargetSoon(_ layout: HomeGridLayout) {
        guard let drag, !drag.dropping else { return }
        let slot = slot(under: drag, layout)
        guard slot != drag.target else {
            retarget?.cancel()
            pendingSlot = nil
            return
        }
        guard slot != pendingSlot else { return }
        pendingSlot = slot
        retarget?.cancel()
        retarget = Task { @MainActor in
            try? await Task.sleep(for: .milliseconds(140))
            guard !Task.isCancelled, self.drag?.dropping == false else { return }
            self.drag?.target = slot
            self.pendingSlot = nil
        }
    }

    private func slot(under drag: IconDrag, _ layout: HomeGridLayout) -> Int {
        let point = CGPoint(x: drag.location.x - drag.grab.width, y: drag.location.y - drag.grab.height)
        return min(layout.slot(near: point), apps.count - 1)
    }

    private func endDrag() {
        retarget?.cancel()
        pendingSlot = nil
        guard var ending = drag, !ending.dropping else { return }
        // A drop lands where the icon is let go, even without resting there first.
        if let layout { ending.target = slot(under: ending, layout) }
        move(ending.id, ending.target)
        drops += 1
        // The icon settles into its slot, then the one in the grid takes its place.
        let slot = layout?.tileCenter(of: ending.target)
            ?? CGPoint(x: ending.location.x - ending.grab.width, y: ending.location.y - ending.grab.height)
        ending.dropping = true
        drag = ending
        withAnimation(.spring(response: 0.3, dampingFraction: 0.85)) {
            drag?.location = CGPoint(x: slot.x + ending.grab.width, y: slot.y + ending.grab.height)
        } completion: {
            if self.drag?.id == ending.id { self.drag = nil }
        }
    }

    /// The dragged icon, above everything, under the finger.
    @ViewBuilder
    private func lifted(_ layout: HomeGridLayout) -> some View {
        if let drag, let app = apps.first(where: { $0.id == drag.id }) {
            let lift = layout.iconSize / 2 - layout.tileHeight / 2
            AppIconView(app: app, image: registry.icon(for: app), size: layout.iconSize)
                .scaleEffect(drag.dropping ? 1 : 1.12)
                .shadow(color: .black.opacity(drag.dropping ? 0.08 : 0.25), radius: drag.dropping ? 3 : 14,
                        y: drag.dropping ? 1 : 8)
                .position(x: drag.location.x - drag.grab.width,
                          y: drag.location.y - drag.grab.height + lift)
                .allowsHitTesting(false)
        }
    }

    /// VoiceOver's stand-in for dragging, one step at a time.
    private func accessibleMove(_ app: WebApp, to index: Int) {
        move(app.id, index)
        AccessibilityNotification.Announcement("\(app.name) moved to position \(index + 1) of \(apps.count)").post()
    }
}
