package com.snowyghost.ovenlight

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.core.graphics.scale
import androidx.webkit.ProfileStore
import androidx.webkit.WebStorageCompat
import com.snowyghost.ovenlight.core.Core
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.UUID
import kotlin.coroutines.resume
import kotlin.math.max
import kotlin.math.roundToInt
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.suspendCancellableCoroutine

/**
 * Each app's data store is a WebView profile named by its data store ID. A profile this
 * process has loaded can't be deleted until the next launch, so ending an app's data deletes
 * its browsing data at once and its profile at the next launch, before any profile loads (see
 * [ProfileDeletions]). Nothing touches the profiles before WebView has started.
 */
@SuppressLint("RequiresFeature") // WebViewSetup is Ready only once it found MULTI_PROFILE.
class WebProfiles(private val context: Context, private val discard: (UUID) -> Unit) : AppRegistry.WebData {
    override fun discard(app: UUID) = discard.invoke(app)

    override fun forget(app: WebApp) {
        StatusBarTint(PreferencesStore(context)).forget(app)
        HomeShortcuts.disable(context, app)
    }

    override suspend fun clear(store: UUID) {
        val name = profileName(store)
        // A view still showing it routes nowhere from now on.
        Core.clearRoute(ProxyChallenge.routeKey(name))
        ProfileDeletions.enqueue(context, name)
        if (!ready()) return
        val profile = ProfileStore.getInstance().getProfile(name) ?: return
        suspendCancellableCoroutine { done -> WebStorageCompat.deleteBrowsingData(profile.webStorage) { if (done.isActive) done.resume(Unit) } }
    }

    override fun delete(store: UUID) {
        val name = profileName(store)
        val deleted = WebViewSetup.state.value == WebViewSetup.State.Ready &&
            runCatching { ProfileStore.getInstance().deleteProfile(name) }.getOrDefault(false)
        if (!deleted) ProfileDeletions.enqueue(context, name)
    }

    override suspend fun stores(): List<UUID> {
        if (!ready()) return emptyList()
        return ProfileStore.getInstance().allProfileNames.mapNotNull { name -> runCatching { UUID.fromString(name) }.getOrNull() }
    }

    /** Waits for WebView to start; false when it can't run Ovenlight's pages. */
    private suspend fun ready(): Boolean = WebViewSetup.state.first { it != WebViewSetup.State.Starting } == WebViewSetup.State.Ready

    companion object {
        fun profileName(store: UUID): String = store.toString()
    }
}

/** An app's icon, decoded small for the launcher. */
object IconDecoder {
    /** The longest side of a saved icon, in pixels: 3 times the largest tile's 96 dp. */
    const val maxPixels = 288

    /** Images larger than this on either side are refused before decoding. */
    const val maxSide = 4096

    /** The icon as a PNG at most [maxPixels] on its longer side, or null when it isn't an image Android reads. */
    fun png(data: ByteArray): ByteArray? {
        val bitmap = decode(data) ?: return null
        return ByteArrayOutputStream().use { out ->
            if (!bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)) return null
            out.toByteArray()
        }
    }

    /** A saved icon, decoded small again: it may come from a build that saved it as the server sent it. */
    fun load(file: File?): Bitmap? = file?.let { runCatching { it.readBytes() }.getOrNull() }?.let(::decode)

    /**
     * The image at most [maxPixels] on its longer side, without making the full-size bitmap
     * first, or null over [maxSide]; feedback screenshots come this way too.
     */
    fun decode(data: ByteArray, maxPixels: Int = this.maxPixels, maxSide: Int = this.maxSide): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(data, 0, data.size, bounds)
        val width = bounds.outWidth
        val height = bounds.outHeight
        if (width <= 0 || height <= 0 || width > maxSide || height > maxSide) return null
        // Decode at the largest power-of-two step that stays at or above the target, then scale.
        var sample = 1
        while (max(width, height) / (sample * 2) >= maxPixels) sample *= 2
        val decoded = BitmapFactory.decodeByteArray(data, 0, data.size, BitmapFactory.Options().apply { inSampleSize = sample }) ?: return null
        val longest = max(decoded.width, decoded.height)
        if (longest <= maxPixels) return decoded
        val scale = maxPixels.toDouble() / longest
        return decoded.scale((decoded.width * scale).roundToInt().coerceAtLeast(1), (decoded.height * scale).roundToInt().coerceAtLeast(1))
    }
}
