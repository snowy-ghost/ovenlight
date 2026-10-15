package com.snowyghost.ovenlight

import android.app.KeyguardManager
import android.content.Intent
import android.content.res.Configuration
import android.os.Build
import android.os.Bundle
import java.net.URI
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

class MainActivity : ComponentActivity() {
    private lateinit var unlockPrompt: UnlockPrompt
    private val model get() = OvenlightApp.model
    private val lock get() = model.lock

    /** The router's sheet showing in a Custom Tab, and the return from it under way. */
    private var tab: RootSheet? = null
    private var returning: Job? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        unlockPrompt = UnlockPrompt(this)
        lifecycleScope.launch { repeatOnLifecycle(Lifecycle.State.CREATED) { lock.isLocked.collect(::guardWhileLocked) } }
        // Collected while stopped too: the tab is in front then, and Ovenlight comes back over it.
        // A tab waits for the unlock, as iOS's sheets wait under the lock window.
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.CREATED) {
                combine(model.router.sheet, lock.isLocked) { sheet, locked -> sheet to locked }.collect { (sheet, locked) -> sheetChanged(sheet, locked) }
            }
        }
        clearNavigationBarScrim()
        if (Build.VERSION.SDK_INT >= 33) setRecentsScreenshotEnabled(false)
        // A launch intent is handled once: not again after a restore from process death,
        // and not when Recents replays it (as after a reboot), so an old invite never
        // replays.
        val handled = savedInstanceState?.getBoolean(HANDLED_LAUNCH) ?: false
        if (!handled && intent.flags and Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY == 0) receive(intent)
        setContent { Root(OvenlightApp.model, onUnlock = { lifecycleScope.launch { lock.unlock(unlockPrompt) } }) }
    }

    /**
     * Whether to lock is decided here, before anything draws, so a return after the delay
     * shows the lock screen in its first frame.
     */
    override fun onStart() {
        super.onStart()
        lock.willBecomeActive()
        model.lifecycle.activityStarted()
    }

    override fun onResume() {
        super.onResume()
        lock.screenLockChecked(getSystemService(KeyguardManager::class.java).isDeviceSecure)
        lifecycleScope.launch { lock.didBecomeActive(unlockPrompt) }
        returnedFromTab()
    }

    override fun onStop() {
        super.onStop()
        if (isChangingConfigurations) return
        // Android 10's screen lock screen, asked for while locked, stops Ovenlight without
        // the person leaving it: an excursion that doesn't start the lock's clock.
        if (unlockPrompt.showingCredentialScreen) model.lifecycle.beginExcursion() else lock.didEnterBackground()
        model.lifecycle.activityStopped()
    }

    /**
     * Tailscale's sign-in and Machines pages show in a Custom Tab, an excursion. When the
     * router closes the sheet (the phone connected) while the tab is in front, Ovenlight comes
     * back over it.
     */
    private fun sheetChanged(sheet: RootSheet?, locked: Boolean) {
        val open = tab
        if (open != null && open.id != sheet?.id) {
            tab = null
            if (!lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
                startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP))
            }
            model.router.sheetDismissed()
        }
        val url = when (sheet) {
            is RootSheet.Login -> sheet.url
            RootSheet.Approve -> machinesPage
            else -> return
        }
        if (tab != null || locked) return
        tab = sheet
        model.router.sheetAppeared()
        if (CustomTabs.open(this, url)) {
            model.lifecycle.beginExcursion()
        } else {
            tab = null
            model.router.sheet.value = null
            model.router.sheetDismissed()
        }
    }

    /**
     * Back from the tab, closed or left behind: its sheet closes at once, so Connect can open it
     * again. Android may have frozen Ovenlight meanwhile, so the node gets up to 10 seconds to say
     * where the sign-in got: still waiting for approval goes on to the Machines page, as on iOS,
     * unless something else has been opened since.
     */
    private fun returnedFromTab() {
        val open = tab ?: return
        model.router.sheet.value = null
        if (open !is RootSheet.Login) return
        val owner = model.nodes.owner
        returning?.cancel()
        returning = lifecycleScope.launch {
            val state = withTimeoutOrNull(10_000) { owner.state.first { it !is NodeState.NeedsLogin && !it.isBusy } } ?: owner.state.value
            val router = model.router
            if (state == NodeState.AwaitingApproval && router.sheet.value == null && router.openApp.value == null) router.present(RootSheet.Approve)
        }
    }

    /**
     * While locked, the lock screen can't be tapjacked: obscured touches are dropped and other
     * apps' overlays are hidden. Not while unlocked, where it would drop a page's touches under
     * a screen-dimming app.
     */
    private fun guardWhileLocked(locked: Boolean) {
        window.decorView.filterTouchesWhenObscured = locked
        if (Build.VERSION.SDK_INT >= 31) window.setHideOverlayWindows(locked)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        receive(intent)
    }

    // enableEdgeToEdge sets its scrim again on every configuration change, and the activity
    // handles rotation and dark mode itself, so it comes off again after each.
    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        window.decorView.post { clearNavigationBarScrim() }
    }

    private fun clearNavigationBarScrim() {
        window.isNavigationBarContrastEnforced = false
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean(HANDLED_LAUNCH, true)
    }

    /** A link: an App Link to an invite, `ovenlight://join` or `ovenlight://open`, held while locked. */
    private fun receive(intent: Intent) {
        DebugLaunch.receive(this, intent)
        if (intent.action != Intent.ACTION_VIEW) return
        val url = intent.dataString?.let { runCatching { URI(it) }.getOrNull() } ?: return
        OvenlightApp.router.handle(url, OvenlightApp.model.registry.apps)
    }

    private companion object {
        const val HANDLED_LAUNCH = "handledLaunch"

        /** Tailscale's device list, where a new device waits for approval. */
        val machinesPage = URI("https://login.tailscale.com/admin/machines")
    }
}

@Composable
private fun Root(model: AppModel, onUnlock: () -> Unit) {
    val lock = model.lock
    OvenlightTheme {
        val locked by lock.isLocked.collectAsState()
        val authenticating by lock.isAuthenticating.collectAsState()
        val error by lock.lastError.collectAsState()
        val state by WebViewSetup.state.collectAsState()
        Box(Modifier.fillMaxSize()) {
            // While locked, nothing but the lock screen is in the window, so neither TalkBack
            // nor a keyboard nor a page's popup can reach what's behind it.
            if (!locked) {
                when (val s = state) {
                    is WebViewSetup.State.NeedsUpdate -> WebViewUpdateScreen(s.webViewPackage)
                    WebViewSetup.State.Ready -> RootView(model)
                    WebViewSetup.State.Starting -> Unit
                }
            }
            // After an unlock the lock screen stays lit and fades out.
            AnimatedVisibility(locked, enter = EnterTransition.None, exit = fadeOut(tween(300))) {
                LockScreen(isLocked = locked, isAuthenticating = authenticating, lastError = error, onUnlock = onUnlock)
            }
        }
    }
}
