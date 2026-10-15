package com.snowyghost.ovenlight

import android.graphics.Bitmap
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector2D
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.VectorConverter
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.changedToUp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CollectionInfo
import androidx.compose.ui.semantics.CollectionItemInfo
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.collectionInfo
import androidx.compose.ui.semantics.collectionItemInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.onLongClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import java.util.UUID
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sign
import kotlin.math.truncate
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Where each icon sits in the launcher's grid: equal columns across the width and rows of one
 * height, so a point in the grid maps to a slot without asking the views. Points are in the
 * grid's own coordinates, which scroll with it, in dp.
 */
class HomeGridLayout(width: Double, labelHeight: Double, largeText: Boolean) {
    data class Point(val x: Double, val y: Double)

    data class Size(val width: Double, val height: Double)

    val width: Double = max(width, 1.0)
    val columns: Int
    val iconSize: Double

    /** An icon with its label (one line, or two at the largest font sizes). */
    val tileHeight: Double

    init {
        // Four across in portrait (three with large text), as many as fit in landscape.
        val usable = width - 2 * sideMargin(width)
        columns = if (largeText) max(3, (usable / 140).toInt()) else max(4, (usable / 92).toInt())
        iconSize = if (largeText) 72.0 else if (width >= 400) 64.0 else 60.0
        tileHeight = iconSize + labelGap + labelHeight
    }

    val sideMargin: Double get() = sideMargin(width)
    val columnWidth: Double get() = (width - 2 * sideMargin) / columns
    val rowPitch: Double get() = tileHeight + rowGap

    /** As wide as its column, so labels get the room the home screen gives them. */
    val tileSize: Size get() = Size(max(columnWidth - 4, iconSize), tileHeight)

    fun tileCenter(index: Int): Point = Point(
        sideMargin + columnWidth * ((index % columns) + 0.5),
        topMargin + (index / columns) * rowPitch + tileHeight / 2,
    )

    /** The slot nearest a tile centered at [point]. Not clamped to the number of apps; callers do that. */
    fun slot(point: Point): Int {
        val column = floor((point.x - sideMargin) / columnWidth).toInt()
        val row = rounded((point.y - topMargin - tileHeight / 2) / rowPitch).toInt()
        return max(row, 0) * columns + min(max(column, 0), columns - 1)
    }

    override fun equals(other: Any?): Boolean =
        other is HomeGridLayout && width == other.width && columns == other.columns && iconSize == other.iconSize && tileHeight == other.tileHeight

    override fun hashCode(): Int = listOf(width, columns, iconSize, tileHeight).hashCode()

    companion object {
        const val labelGap = 6.0
        const val rowGap = 24.0

        /** From the top of the grid to the first row, a little below the top controls. */
        const val topMargin = 20.0

        fun sideMargin(width: Double): Double = rounded(width * 0.064)

        /** To the nearest whole number, halves away from zero, as Swift's `rounded()`. */
        private fun rounded(value: Double): Double {
            val whole = truncate(value)
            return if (abs(value - whole) >= 0.5) whole + sign(value) else whole
        }
    }
}

/**
 * Reordering, as the home screen does it: the moved app lands at the index it was dropped on
 * and everything between shifts over by one.
 */
object HomeReorder {
    fun <Item, ID> moving(items: List<Item>, id: ID, to: Int, idOf: (Item) -> ID): List<Item> {
        val from = items.indexOfFirst { idOf(it) == id }
        if (from < 0) return items
        val moved = items.toMutableList()
        val item = moved.removeAt(from)
        moved.add(min(max(to, 0), moved.size), item)
        return moved
    }
}

/** Where the owner's empty grid sends them to learn about the connector. */
const val CONNECTOR_SUPPORT_URL = "https://ovenlight.app/support#own-computer"

/**
 * An icon being moved: where the finger is, where on the tile it took hold (from the tile's
 * center), and the slot the icon would drop into. In the grid's own pixels, which hold still
 * because scrolling stops meanwhile. [dropped] holds the new order until the saved list has it.
 */
private data class IconDrag(val id: UUID, val location: Offset, val grab: Offset, val target: Int, val dropped: Boolean = false)

/**
 * The apps as one scrolling grid of icons, the way a phone's home screen holds them. Tap an
 * icon to open its app; touch and hold for its [menu]; keep holding and drag to move it, and
 * the others make way once it rests over a slot. [header] scrolls with the grid, above it.
 * Scrolling stops while an icon moves, so it moves among the icons on screen.
 */
@Composable
fun HomeScreenGrid(
    apps: List<WebApp>,
    /** Set up for the owner's own computers: the empty grid invites their apps. A guest's stays blank while the first invite joins. */
    ownerEnabled: Boolean,
    icon: (WebApp) -> Bitmap?,
    iconRevision: Int,
    reachability: Map<UUID, AppReachability>,
    open: (WebApp) -> Unit,
    /** Moves an app to an index in the list, and saves the order. */
    move: (UUID, Int) -> Unit,
    addApp: () -> Unit,
    openLink: (String) -> Unit,
    modifier: Modifier = Modifier,
    header: @Composable ColumnScope.() -> Unit = {},
    menu: @Composable ColumnScope.(app: WebApp, close: () -> Unit) -> Unit,
) {
    val density = LocalDensity.current
    val haptics = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()
    val largeText = density.fontScale >= 1.5f
    val typography = MaterialTheme.typography
    val labelLine = with(density) { typography.bodySmall.lineHeight.toDp() }.value
    val sharedByLine = with(density) { typography.labelSmall.lineHeight.toDp() }.value
    var drag by remember { mutableStateOf<IconDrag?>(null) }
    var menuFor by remember { mutableStateOf<UUID?>(null) }
    var pressed by remember { mutableStateOf<UUID?>(null) }
    // While dragging, the moved app sits in its target slot.
    val order = drag?.let { d -> HomeReorder.moving(apps, d.id, d.target) { it.id } } ?: apps
    LaunchedEffect(apps) { if (drag?.dropped == true) drag = null }
    Column(modifier.verticalScroll(rememberScrollState(), enabled = drag == null)) {
        header()
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val labelHeight = labelLine * (if (largeText) 2 else 1) + (if (apps.any { it.isShared }) sharedByLine else 0f)
            val layout = remember(maxWidth, labelHeight, largeText) { HomeGridLayout(maxWidth.value.toDouble(), labelHeight.toDouble(), largeText) }
            val px = density.density
            val tileSize = layout.tileSize
            fun center(index: Int) = layout.tileCenter(index).let { Offset((it.x * px).toFloat(), (it.y * px).toFloat()) }
            val half = Offset((tileSize.width * px / 2).toFloat(), (tileSize.height * px / 2).toFloat())
            val emptySlot = apps.isEmpty() && ownerEnabled
            val slots = if (emptySlot) 1 else order.size
            val rows = ceil(slots / layout.columns.toDouble()).toInt()
            // Where each icon is drawn, for the moved one to follow the finger.
            val positions = remember { mutableMapOf<UUID, Animatable<Offset, AnimationVector2D>>() }
            val current by rememberUpdatedState(order)
            val currentApps by rememberUpdatedState(apps)
            val currentOpen by rememberUpdatedState(open)
            val currentMove by rememberUpdatedState(move)
            var retarget by remember { mutableStateOf<Job?>(null) }
            var pendingSlot by remember { mutableStateOf<Int?>(null) }

            fun slotUnder(d: IconDrag): Int {
                val point = HomeGridLayout.Point(((d.location.x - d.grab.x) / px).toDouble(), ((d.location.y - d.grab.y) / px).toDouble())
                return min(layout.slot(point), currentApps.size - 1).coerceAtLeast(0)
            }

            // Icons make way once the moved one rests over a slot for a moment, so passing over
            // them on the way somewhere else doesn't shuffle the grid.
            fun retargetSoon() {
                val d = drag?.takeUnless { it.dropped } ?: return
                val slot = slotUnder(d)
                if (slot == d.target) {
                    retarget?.cancel()
                    pendingSlot = null
                    return
                }
                if (slot == pendingSlot) return
                pendingSlot = slot
                retarget?.cancel()
                retarget = scope.launch {
                    delay(140)
                    if (drag?.id == d.id) {
                        drag = drag?.copy(target = slot)
                        haptics.performHapticFeedback(HapticFeedbackType.SegmentTick)
                    }
                    pendingSlot = null
                }
            }

            // A drop lands where the icon is let go, even without resting there first.
            fun endDrag() {
                retarget?.cancel()
                pendingSlot = null
                val d = drag ?: return
                val target = slotUnder(d)
                drag = if (currentApps.indexOfFirst { it.id == d.id } == target) null else d.copy(target = target, dropped = true)
                currentMove(d.id, target)
                haptics.performHapticFeedback(HapticFeedbackType.GestureEnd)
            }

            Column {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height((HomeGridLayout.topMargin + rows * layout.rowPitch).dp)
                        .semantics { collectionInfo = CollectionInfo(rowCount = rows, columnCount = layout.columns) }
                        .pointerInput(layout) {
                            awaitEachGesture {
                                val down = awaitFirstDown()
                                val index = current.indices.firstOrNull { i ->
                                    val c = center(i)
                                    abs(down.position.x - c.x) <= half.x && abs(down.position.y - c.y) <= half.y
                                } ?: return@awaitEachGesture
                                val app = current[index]
                                val slop = viewConfiguration.touchSlop
                                pressed = app.id
                                // True for a tap, false when a scroll took the touch, null once held.
                                val tapped = withTimeoutOrNull(viewConfiguration.longPressTimeoutMillis) {
                                    var up = false
                                    while (true) {
                                        val change = awaitPointerEvent().changes.firstOrNull { it.id == down.id } ?: break
                                        if (change.changedToUp()) {
                                            change.consume()
                                            up = true
                                            break
                                        }
                                        if (change.isConsumed || (change.position - down.position).getDistance() > slop) break
                                    }
                                    up
                                }
                                pressed = null
                                if (tapped == true) currentOpen(app)
                                if (tapped != null) return@awaitEachGesture
                                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                menuFor = app.id
                                // Still held: past the touch slop the menu closes and the icon follows the finger.
                                var moving = false
                                try {
                                    while (true) {
                                        val change = awaitPointerEvent().changes.firstOrNull { it.id == down.id } ?: break
                                        change.consume()
                                        if (!change.pressed) break
                                        if (!moving && (change.position - down.position).getDistance() > slop) {
                                            moving = true
                                            menuFor = null
                                            drag = IconDrag(app.id, change.position, grab = down.position - center(index), target = index)
                                        }
                                        if (!moving) continue
                                        val d = drag?.copy(location = change.position) ?: break
                                        drag = d
                                        val moved = positions[app.id]
                                        scope.launch { moved?.snapTo(d.location - d.grab - half) }
                                        retargetSoon()
                                    }
                                } finally {
                                    if (moving) endDrag()
                                }
                            }
                        },
                ) {
                    if (emptySlot) {
                        EmptyHomeSlot(
                            layout.iconSize.dp, addApp,
                            Modifier.offset { (center(0) - half).round() }.size(tileSize.width.dp, tileSize.height.dp),
                        )
                    }
                    val openLabel = stringResource(R.string.launcher_open)
                    val menuLabel = stringResource(R.string.launcher_show_menu)
                    val newLabel = stringResource(R.string.launcher_new)
                    order.forEachIndexed { index, app ->
                        key(app.id) {
                            val slot = center(index) - half
                            // Each icon glides to its slot as others move; a new layout places them at once.
                            val position = remember(layout) { Animatable(slot, Offset.VectorConverter) }
                            DisposableEffect(position) {
                                positions[app.id] = position
                                onDispose { positions.remove(app.id) }
                            }
                            val moving = drag?.let { it.id == app.id && !it.dropped } == true
                            LaunchedEffect(slot, moving) {
                                if (!moving) position.animateTo(slot, spring(dampingRatio = 0.82f, stiffness = Spring.StiffnessMediumLow))
                            }
                            val image = remember(app.id, app.iconFile, iconRevision) { icon(app) }
                            val status = reachability[app.id]
                            val before = order.getOrNull(index - 1)
                            val after = order.getOrNull(index + 1)
                            val moveBefore = before?.let { stringResource(R.string.launcher_move_before, it.name) }
                            val moveAfter = after?.let { stringResource(R.string.launcher_move_after, it.name) }
                            Box(
                                Modifier
                                    .offset { position.value.round() }
                                    .zIndex(if (moving || menuFor == app.id) 1f else 0f)
                                    .size(tileSize.width.dp, tileSize.height.dp)
                                    .clearAndSetSemantics {
                                        contentDescription = app.sharedBy?.let { "${app.name}, ${ConnectionCopy.sharedBy(it)}" } ?: app.name
                                        val states = listOfNotNull(if (app.isNew) newLabel else null, status?.label)
                                        if (states.isNotEmpty()) stateDescription = states.joinToString(", ")
                                        onClick(label = openLabel) { open(app); true }
                                        onLongClick(label = menuLabel) { menuFor = app.id; true }
                                        // TalkBack's stand-in for dragging, one step at a time. The tile keeps
                                        // focus, since tiles are keyed, and reads its new place in the grid.
                                        customActions = listOfNotNull(
                                            moveBefore?.let { CustomAccessibilityAction(it) { move(app.id, index - 1); true } },
                                            moveAfter?.let { CustomAccessibilityAction(it) { move(app.id, index + 1); true } },
                                        )
                                        collectionItemInfo = CollectionItemInfo(index / layout.columns, 1, index % layout.columns, 1)
                                    },
                            ) {
                                AppTile(
                                    app, image, status, layout.iconSize.dp, largeText,
                                    pressed = pressed == app.id, lifted = moving || menuFor == app.id, labelShown = !moving,
                                )
                                DropdownMenu(expanded = menuFor == app.id, onDismissRequest = { menuFor = null }) {
                                    menu(this, app) { menuFor = null }
                                }
                            }
                        }
                    }
                }
                if (emptySlot) {
                    EmptyGridFooter(openLink, Modifier.padding(horizontal = (2 * layout.sideMargin).dp).padding(bottom = HomeGridLayout.rowGap.dp))
                }
            }
        }
    }
}

private fun Offset.round() = IntOffset(x.roundToInt(), y.roundToInt())

/**
 * One app on the home screen: its icon, dimmed with a badge while its computer is offline, its
 * name after the new-app dot, and who shared it. Pressed, the icon sinks a little; held, it
 * lifts. The grid gives the tile its semantics.
 */
@Composable
fun AppTile(
    app: WebApp,
    image: Bitmap?,
    reachability: AppReachability?,
    iconSize: Dp,
    largeText: Boolean,
    modifier: Modifier = Modifier,
    pressed: Boolean = false,
    lifted: Boolean = false,
    labelShown: Boolean = true,
) {
    val colors = MaterialTheme.colorScheme
    val scale by animateFloatAsState(if (pressed) 0.92f else if (lifted) 1.12f else 1f, label = "tile scale")
    val elevation by animateDpAsState(if (lifted) 12.dp else 2.dp, label = "tile shadow")
    Column(modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.size(iconSize).graphicsLayer { scaleX = scale; scaleY = scale }) {
            AppIconView(
                app, image, iconSize,
                Modifier.graphicsLayer {
                    shadowElevation = elevation.toPx()
                    shape = AppIconShape
                    alpha = if (reachability == AppReachability.Offline) 0.5f else 1f
                },
            )
            if (reachability != null) ReachabilityBadge(Modifier.align(Alignment.TopEnd).offset(4.dp, (-4).dp))
        }
        Spacer(Modifier.height(HomeGridLayout.labelGap.dp))
        Column(Modifier.alpha(if (labelShown) 1f else 0f), horizontalAlignment = Alignment.CenterHorizontally) {
            // The new dot sits before the name, in the ember: it says "arrived" without a badge's alarm.
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                if (app.isNew) {
                    Box(Modifier.size(6.dp).background(colors.primary, CircleShape))
                    Spacer(Modifier.width(3.dp))
                }
                Text(
                    app.name, style = MaterialTheme.typography.bodySmall, color = colors.onSurface, textAlign = TextAlign.Center,
                    maxLines = if (largeText) 2 else 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false),
                )
            }
            app.sharedBy?.let { owner ->
                // Over the light, secondary text stays faint, so higher contrast takes the main color.
                val color = if (prefersHighContrast(LocalContext.current)) colors.onSurface else colors.onSurfaceVariant
                Text(ConnectionCopy.sharedBy(owner), style = MaterialTheme.typography.labelSmall, color = color, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

/** An app's icon: its cached image, or a monogram on its theme color (the ember without one). */
@Composable
fun AppIconView(app: WebApp, image: Bitmap?, size: Dp, modifier: Modifier = Modifier) {
    val outline = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f)
    Box(modifier.size(size).clip(AppIconShape).border(0.5.dp, outline, AppIconShape), contentAlignment = Alignment.Center) {
        if (image != null) {
            val bitmap = remember(image) { image.asImageBitmap() }
            Image(bitmap, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        } else {
            val base = themeColor(app.themeColorHex) ?: LocalOvenColors.current.ember
            val monogram = with(LocalDensity.current) { (size * 0.5f).toSp() }
            Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(lerp(base, Color.White, 0.18f), base))), contentAlignment = Alignment.Center) {
                // Half the icon's size, which doesn't grow with the font size, so neither does this.
                Text(
                    app.name.graphemePrefix(1).uppercase(), color = Color.White, fontSize = monogram, fontWeight = FontWeight.SemiBold,
                    fontFamily = if (size * 0.5f >= 18.dp) SerifDisplay else SerifText,
                )
            }
        }
    }
}

/** A web manifest's theme color (`#RGB` or `#RRGGBB`), or null for anything else, as iOS reads it. */
fun themeColor(hex: String?): Color? {
    var text = hex?.trimmingWhitespace() ?: return null
    if (text.startsWith("#")) text = text.drop(1)
    if (text.length == 3) text = text.map { "$it$it" }.joinToString("")
    if (text.length != 6) return null
    val value = text.toUIntOrNull(16) ?: return null
    return Color(0xFF000000.toInt() or value.toInt())
}

/** The dot an offline app's icon gets on its top end corner, ringed so it reads on any icon. */
@Composable
private fun ReachabilityBadge(modifier: Modifier = Modifier) {
    Box(
        modifier
            .size(18.dp)
            .background(MaterialTheme.colorScheme.surface, CircleShape)
            .padding(2.5.dp)
            .background(MaterialTheme.colorScheme.outline, CircleShape),
    )
}

/** The owner's empty home screen's first slot: an outlined space to add an app into. */
@Composable
private fun EmptyHomeSlot(size: Dp, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) 0.92f else 1f, label = "slot scale")
    val colors = MaterialTheme.colorScheme
    val stroke = colors.onSurface.copy(alpha = 0.25f)
    Column(
        modifier.clickable(interaction, indication = null, role = Role.Button, onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(Modifier.size(size).graphicsLayer { scaleX = scale; scaleY = scale }, contentAlignment = Alignment.Center) {
            Canvas(Modifier.fillMaxSize()) {
                val width = 1.5.dp.toPx()
                val radius = this.size.minDimension * 0.22f
                drawRoundRect(
                    stroke, topLeft = Offset(width / 2, width / 2), size = this.size.copy(this.size.width - width, this.size.height - width),
                    cornerRadius = CornerRadius(radius), style = Stroke(width, pathEffect = PathEffect.dashPathEffect(floatArrayOf(6.dp.toPx(), 5.dp.toPx()))),
                )
            }
            Icon(painterResource(R.drawable.ic_add), contentDescription = null, tint = colors.onSurfaceVariant, modifier = Modifier.size(size * 0.36f))
        }
        Spacer(Modifier.height(HomeGridLayout.labelGap.dp))
        Text(stringResource(R.string.launcher_add_app), style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant, maxLines = 1)
    }
}

/** Under the empty slot: where the owner's apps come from, with the connector linked. */
@Composable
private fun EmptyGridFooter(openLink: (String) -> Unit, modifier: Modifier = Modifier) {
    val link = stringResource(R.string.launcher_connector)
    val text = stringResource(R.string.launcher_empty_footer, link)
    val primary = MaterialTheme.colorScheme.primary
    val annotated = remember(text, link, primary) {
        buildAnnotatedString {
            append(text)
            val start = text.indexOf(link)
            if (start >= 0) {
                addLink(
                    LinkAnnotation.Url(CONNECTOR_SUPPORT_URL, TextLinkStyles(SpanStyle(color = primary))) { openLink(CONNECTOR_SUPPORT_URL) },
                    start, start + link.length,
                )
            }
        }
    }
    Text(
        annotated, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center, modifier = modifier.fillMaxWidth(),
    )
}
