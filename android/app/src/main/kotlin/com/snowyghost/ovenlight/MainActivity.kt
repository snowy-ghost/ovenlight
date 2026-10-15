package com.snowyghost.ovenlight

import android.content.Intent
import android.content.res.Configuration
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        clearNavigationBarScrim()
        if (Build.VERSION.SDK_INT >= 33) setRecentsScreenshotEnabled(false)
        // A launch intent is handled once: not again after a restore from process death,
        // and not when Recents replays it (as after a reboot), so an old invite never
        // replays.
        val handled = savedInstanceState?.getBoolean(HANDLED_LAUNCH) ?: false
        if (!handled && intent.flags and Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY == 0) receive(intent)
        setContent { Root() }
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

    override fun onStart() {
        super.onStart()
        TailnetCore.reportNetwork()
    }

    private fun receive(intent: Intent) {
        DebugLaunch.receive(this, intent)
    }

    private companion object {
        const val HANDLED_LAUNCH = "handledLaunch"
    }
}

@Composable
private fun Root() {
    OvenlightTheme {
        val state by WebViewSetup.state.collectAsState()
        when (val s = state) {
            is WebViewSetup.State.NeedsUpdate -> WebViewUpdateScreen(s.webViewPackage)
            WebViewSetup.State.Ready -> DebugLaunch.Content()
            WebViewSetup.State.Starting -> Unit
        }
    }
}
