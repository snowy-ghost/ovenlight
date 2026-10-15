package com.snowyghost.ovenlight

import android.os.Build
import android.view.HapticFeedbackConstants
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.keyframes
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

/** The light's level while the lock screen waits, and behind the privacy states. */
const val RESTING_LAMP_LEVEL = 0.35f

/**
 * Shown whenever Ovenlight is locked, and as it fades away after an unlock. The system's
 * unlock prompt opens on its own when Ovenlight comes to the front locked; the button is for
 * trying again.
 *
 * Locked, the light is low; the prompt switches it on, the way you turn on an oven light to
 * look inside. It drops back when the prompt goes, and stays on once Ovenlight unlocks, while
 * the lock screen fades.
 */
@Composable
fun LockScreen(isLocked: Boolean, isAuthenticating: Boolean, lastError: String?, onUnlock: () -> Unit) {
    var appeared by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { appeared = true }
    val level = if (appeared && (isAuthenticating || !isLocked)) 1f else RESTING_LAMP_LEVEL
    val view = LocalView.current
    val density = LocalDensity.current
    // A failed unlock shakes the message, with the iOS keyframes, and a reject haptic.
    val shake = remember { Animatable(0f) }
    LaunchedEffect(lastError) {
        if (lastError == null) return@LaunchedEffect
        if (Build.VERSION.SDK_INT >= 30) view.performHapticFeedback(HapticFeedbackConstants.REJECT)
        shake.animateTo(0f, keyframes {
            durationMillis = 380
            -9f at 70
            8f at 160
            -5f at 240
            3f at 310
        })
    }
    LockLayout(
        level = level,
        message = {
            val lockedDescription = stringResource(R.string.lock_locked_description)
            AnimatedContent(lastError, transitionSpec = { fadeIn() togetherWith fadeOut() }, label = "lock message") { error ->
                Box(
                    Modifier
                        .padding(horizontal = 40.dp)
                        .offset { IntOffset(with(density) { shake.value.dp.toPx() }.roundToInt(), 0) }
                        .clearAndSetSemantics {
                            contentDescription = error ?: lockedDescription
                            liveRegion = LiveRegionMode.Polite
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    if (error == null) {
                        Text(stringResource(R.string.lock_locked), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    } else {
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(painterResource(R.drawable.ic_error), contentDescription = null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(18.dp))
                            Text(error, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
                        }
                    }
                }
            }
        },
        controls = {
            FilledTonalButton(
                onClick = onUnlock,
                modifier = Modifier.padding(horizontal = 40.dp).widthIn(max = 400.dp).fillMaxWidth().height(56.dp),
                contentPadding = ButtonDefaults.ButtonWithIconContentPadding,
            ) {
                Icon(painterResource(R.drawable.ic_lock_open), contentDescription = null, tint = LocalOvenColors.current.ember)
                Spacer(Modifier.size(ButtonDefaults.IconSpacing))
                Text(stringResource(R.string.lock_unlock), style = MaterialTheme.typography.titleMedium)
            }
        },
    )
}

/**
 * Ovenlight while its contents mustn't show, matching the waiting lock screen's layout and
 * light, so a cover that turns into the lock screen doesn't move or brighten.
 */
@Composable
fun PrivacyCover() {
    LockLayout(level = RESTING_LAMP_LEVEL, message = {}, controls = {})
}

/**
 * The wordmark on its rack near the top, clear of the unlock prompt's sheet at the bottom,
 * with any message under it; the controls rest above the navigation bar. The light is the
 * launcher's.
 */
@Composable
private fun LockLayout(level: Float, message: @Composable () -> Unit, controls: @Composable () -> Unit) {
    val largeText = LocalDensity.current.fontScale >= 1.5f
    Box(Modifier.fillMaxSize().background(LocalOvenColors.current.ovenInterior)) {
        LampLight(level)
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val top = maxHeight * (if (largeText) 0.06f else 0.16f)
            Column(Modifier.fillMaxSize().navigationBarsPadding()) {
                // Scrolls only when the largest text sizes don't fit above the controls.
                Column(
                    Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(top = top),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    // The message speaks for the whole block.
                    Text("Ovenlight", style = OvenType.wordmark, color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.clearAndSetSemantics {})
                    OvenRack(120.dp)
                    message()
                }
                Box(Modifier.fillMaxWidth().padding(vertical = 20.dp), contentAlignment = Alignment.Center) { controls() }
            }
        }
    }
}
