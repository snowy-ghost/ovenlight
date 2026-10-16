package com.snowyghost.ovenlight

import android.app.KeyguardManager
import android.content.Intent
import android.content.res.Configuration
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeOut
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import java.net.URI
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

class MainActivity : ComponentActivity() {
    private lateinit var unlockPrompt: UnlockPrompt
    private val model get() = OvenlightApp.model
    private val lock get() = model.lock

    /** The screen lock, entered on the system's screen before Android ended the last process. */
    private val screenLockEntered = object : Authenticator {
        override suspend fun authenticate() = Authenticator.Result.Success
    }

    /** The return from a Custom Tab under way. */
    private var returning: Job? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        unlockPrompt = UnlockPrompt(this, confirmedAfterRestart = { lifecycleScope.launch { lock.unlock(screenLockEntered) } })
        // Collected while stopped too: the tab is in front then, and Ovenlight comes back over it.
        // A tab waits for the unlock, as iOS's sheets wait under the lock window.
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.CREATED) {
                combine(model.router.sheet, lock.isLocked, lifecycle.currentStateFlow) { sheet, locked, state ->
                    Triple(sheet, locked, state.isAtLeast(Lifecycle.State.STARTED))
                }.collect { (sheet, locked, started) -> sheetChanged(sheet, locked, started) }
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
    private fun sheetChanged(sheet: RootSheet?, locked: Boolean, started: Boolean) {
        val open = model.openTab
        if (open != null && open.id != sheet?.id) {
            model.openTab = null
            if (!started) {
                startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP))
            }
            model.router.sheetDismissed()
        }
        val url = when (sheet) {
            is RootSheet.Login -> sheet.url
            RootSheet.Approve -> machinesPage
            else -> return
        }
        // A tab opens only from Ovenlight in front and unlocked; one already open (in front of a
        // recreated activity, say) is returned from rather than opened again.
        if (model.openTab != null || locked || !started) return
        model.openTab = sheet
        model.router.sheetAppeared()
        if (!CustomTabs.open(this, url, model.lifecycle)) {
            model.openTab = null
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
        val open = model.openTab ?: return
        model.router.sheet.value = null
        if (open !is RootSheet.Login) return
        val owner = model.nodes.owner
        returning?.cancel()
        returning = lifecycleScope.launch {
            val state = withTimeoutOrNull(10_000) { owner.state.first { it !is NodeState.NeedsLogin && it != NodeState.Idle && !it.isBusy } } ?: owner.state.value
            val router = model.router
            if (state == NodeState.AwaitingApproval && router.sheet.value == null && router.openApp.value == null) router.present(RootSheet.Approve)
        }
    }

    /**
     * Back with nothing left to go back through: Android finishes Ovenlight through this, always
     * on 10 and 11, and later unless the launcher opened it and nothing has arrived since (a link,
     * say). It goes behind instead, so its card stays in Recents however it was opened, and a card
     * never outlives its Ovenlight to start a second one. Overridden here rather than handled as a
     * Back callback, which would take the system's back-to-home animation away.
     */
    override fun finishAfterTransition() {
        moveTaskToBack(true)
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
        val state by WebViewSetup.state.collectAsState()
        // While locked, nothing is in the activity's window, so neither TalkBack nor a keyboard
        // can reach what's behind the lock.
        if (!locked) {
            when (val s = state) {
                is WebViewSetup.State.NeedsUpdate -> WebViewUpdateScreen(s.webViewPackage)
                WebViewSetup.State.Ready -> RootView(model)
                WebViewSetup.State.Starting -> Unit
            }
        }
        LockWindow(lock, locked, onUnlock)
    }
}

/**
 * The lock screen, in a window of its own, added as the lock comes. A window added later stacks
 * above every window the activity already has, so whatever a page left open (a picker, a menu,
 * a dialog Android shows again as Ovenlight returns) stays under the lock and out of reach.
 * After an unlock the lock screen stays lit and fades out, then the window goes.
 */
@Composable
private fun LockWindow(lock: LockManager, locked: Boolean, onUnlock: () -> Unit) {
    val shown = remember { MutableTransitionState(locked) }.apply { targetState = locked }
    if (!shown.currentState && !shown.targetState) return
    val authenticating by lock.isAuthenticating.collectAsState()
    val error by lock.lastError.collectAsState()
    // Back goes to the activity, which has nothing left to go back through, so Ovenlight goes behind.
    val back = LocalOnBackPressedDispatcherOwner.current?.onBackPressedDispatcher
    // Compose makes the dialog's window anew when the density changes (text or display size, a
    // fold), leaving a frame with no lock above a page's picker: the window keeps the density it
    // was made with, and its content gets the current one.
    val density = LocalDensity.current
    val madeWith = remember { density }
    CompositionLocalProvider(LocalDensity provides madeWith) {
        Dialog(
            onDismissRequest = { back?.onBackPressed() },
            properties = DialogProperties(dismissOnClickOutside = false, usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
        ) {
            CompositionLocalProvider(LocalDensity provides density) { LockWindowContent(shown, locked, authenticating, error, onUnlock) }
        }
    }
}

@Composable
private fun LockWindowContent(
    shown: MutableTransitionState<Boolean>, locked: Boolean, authenticating: Boolean, error: String?, onUnlock: () -> Unit,
) {
    FullScreenDialogBars()
    // Not guarded from overlays as Ovenlight's prompts are: a tap on Unlock only opens the
    // system's prompt, and the guard would drop every tap under a screen-dimming app.
    // As the activity's window: no scrim behind the navigation bar, and no dialog animation
    // or dim, so it shows at once and fades over the content alone.
    val view = LocalView.current
    SideEffect {
        view.dialogWindow()?.run {
            isNavigationBarContrastEnforced = false
            setWindowAnimations(0)
            clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
        }
    }
    AnimatedVisibility(shown, enter = EnterTransition.None, exit = fadeOut(tween(300))) {
        LockScreen(isLocked = locked, isAuthenticating = authenticating, lastError = error, onUnlock = onUnlock)
    }
}
