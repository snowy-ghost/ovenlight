package com.snowyghost.ovenlight

import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sign
import kotlin.math.truncate

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
