package com.snowyghost.ovenlight

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.Shader
import androidx.compose.ui.graphics.toArgb
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.content.res.ResourcesCompat
import androidx.core.graphics.ColorUtils
import androidx.core.graphics.createBitmap
import androidx.core.graphics.drawable.IconCompat
import androidx.core.net.toUri
import kotlin.math.min

/**
 * Add to Home screen (design.md §4): a pinned shortcut to one app, keyed by its ID. It opens
 * Ovenlight with `ovenlight://open?id=<app ID>`, which the router holds while locked as it holds
 * any link, so the lock always comes first. Never dynamic shortcuts, which would list apps on
 * Ovenlight's icon outside the lock.
 */
object HomeShortcuts {
    /** What the launcher's mask shows of an adaptive icon, its middle 72 units, at a saved icon's full size. */
    private const val SHOWN_PIXELS = IconDecoder.maxPixels

    /** The adaptive icon's whole 108 units. */
    private const val ICON_PIXELS = SHOWN_PIXELS * 108 / 72

    fun isSupported(context: Context): Boolean = ShortcutManagerCompat.isRequestPinShortcutSupported(context)

    /** Asks the launcher to pin the app; its own dialog confirms. */
    fun request(context: Context, app: WebApp, image: Bitmap?) {
        ShortcutManagerCompat.requestPinShortcut(context, shortcut(context, app, image), null)
    }

    /** Pinned shortcuts take their app's new name and icon. */
    fun update(context: Context, apps: List<WebApp>, image: (WebApp) -> Bitmap?) {
        // Android refuses shortcut calls before the phone's first unlock, and a busy one may
        // refuse an update for a while; the next change tries again.
        runCatching {
            val pinned = ShortcutManagerCompat.getShortcuts(context, ShortcutManagerCompat.FLAG_MATCH_PINNED).filter { it.isEnabled }.map { it.id }.toSet()
            val changed = apps.filter { it.id.toString() in pinned }
            if (changed.isNotEmpty()) ShortcutManagerCompat.updateShortcuts(context, changed.map { shortcut(context, it, image(it)) })
        }
    }

    /** A removed app's shortcut stays on the home screen, dimmed, and says why when tapped. */
    fun disable(context: Context, app: WebApp) {
        runCatching { ShortcutManagerCompat.disableShortcuts(context, listOf(app.id.toString()), context.getString(R.string.shortcut_removed)) }
    }

    private fun shortcut(context: Context, app: WebApp, image: Bitmap?): ShortcutInfoCompat {
        val link = Intent(Intent.ACTION_VIEW, "${InviteLink.scheme}://open?id=${app.id}".toUri(), context, MainActivity::class.java)
        val label = app.name.ifEmpty { app.host }
        return ShortcutInfoCompat.Builder(context, app.id.toString())
            .setShortLabel(label)
            .setIcon(IconCompat.createWithAdaptiveBitmap(icon(context, app, image)))
            .setIntent(link)
            .build()
    }

    /**
     * The icon as the launcher's tile draws it (AppIconView): the saved image cropped square, on
     * the app's theme color for any transparent parts, or the monogram on its gradient.
     */
    private fun icon(context: Context, app: WebApp, image: Bitmap?): Bitmap {
        val bitmap = createBitmap(ICON_PIXELS, ICON_PIXELS)
        val canvas = Canvas(bitmap)
        val base = (themeColor(app.themeColorHex) ?: lightOven.ember).toArgb()
        val inset = (ICON_PIXELS - SHOWN_PIXELS) / 2
        val shown = Rect(inset, inset, inset + SHOWN_PIXELS, inset + SHOWN_PIXELS)
        if (image != null) {
            canvas.drawColor(base)
            val side = min(image.width, image.height)
            val left = (image.width - side) / 2
            val top = (image.height - side) / 2
            canvas.drawBitmap(image, Rect(left, top, left + side, top + side), shown, Paint(Paint.FILTER_BITMAP_FLAG))
            return bitmap
        }
        val gradient = LinearGradient(0f, shown.top.toFloat(), 0f, shown.bottom.toFloat(), ColorUtils.blendARGB(base, android.graphics.Color.WHITE, 0.18f), base, Shader.TileMode.CLAMP)
        canvas.drawPaint(Paint().apply { shader = gradient })
        val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = ResourcesCompat.getFont(context, R.font.source_serif_4)
            fontVariationSettings = "'wght' 600, 'opsz' 36"
            textSize = SHOWN_PIXELS * 0.5f
            textAlign = Paint.Align.CENTER
            color = android.graphics.Color.WHITE
        }
        val middle = ICON_PIXELS / 2f - (text.ascent() + text.descent()) / 2
        canvas.drawText(app.name.graphemePrefix(1).uppercase(), ICON_PIXELS / 2f, middle, text)
        return bitmap
    }
}
