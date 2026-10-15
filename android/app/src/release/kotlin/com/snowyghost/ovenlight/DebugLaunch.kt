package com.snowyghost.ovenlight

import android.content.Intent
import androidx.activity.ComponentActivity
import androidx.compose.runtime.Composable

/** Release builds have no launch extras and no lab: see src/debug. */
object DebugLaunch {
    @Suppress("UNUSED_PARAMETER")
    fun receive(activity: ComponentActivity, intent: Intent) = Unit

    @Composable
    fun Content() = Unit
}
