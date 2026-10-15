package com.snowyghost.ovenlight

import android.content.Intent
import androidx.activity.ComponentActivity
import androidx.compose.runtime.Composable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** Release builds have no launch extras and no lab: see src/debug. */
object DebugLaunch {
    val active: StateFlow<Boolean> = MutableStateFlow(false)

    @Suppress("UNUSED_PARAMETER")
    fun receive(activity: ComponentActivity, intent: Intent) = Unit

    @Composable
    fun Content() = Unit
}
