package com.snowyghost.ovenlight

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/** The home screen of apps (LauncherView.swift). */
@Composable
fun LauncherView(model: AppModel) {
    Box(Modifier.fillMaxSize().background(LocalOvenColors.current.ovenInterior)) {
        LampLight(level = 0.6f)
    }
}
