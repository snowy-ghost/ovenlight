package com.snowyghost.ovenlight

import android.content.res.Configuration
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.MutableState
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.unit.Density

/** The looks each screen is checked in: light, dark, and at twice the text size. */
enum class Look(val dark: Boolean, val fontScale: Float) { Light(false, 1f), Dark(true, 1f), LargeText(false, 2f) }

/** [content] in Ovenlight's theme, in [look]. */
@Composable
fun Looked(look: Look, content: @Composable () -> Unit) {
    val configuration = Configuration(LocalConfiguration.current).apply {
        uiMode = (uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or
            (if (look.dark) Configuration.UI_MODE_NIGHT_YES else Configuration.UI_MODE_NIGHT_NO)
        fontScale = look.fontScale
    }
    CompositionLocalProvider(LocalConfiguration provides configuration, LocalDensity provides Density(LocalDensity.current.density, look.fontScale)) {
        OvenlightTheme(content)
    }
}

/** Runs [check] in each look in turn, set on [look] (which the content shows [Looked] in). */
fun ComposeContentTestRule.inEveryLook(look: MutableState<Look>, check: () -> Unit) {
    for (each in Look.entries) {
        look.value = each
        waitForIdle()
        check()
    }
}
