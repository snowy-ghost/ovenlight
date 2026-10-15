package com.snowyghost.ovenlight

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.test.tryPerformAccessibilityChecks
import org.junit.Assert.assertTrue

/**
 * The accessibility checks on everything showing. Under Robolectric they evaluate no Compose
 * content ("Composable content is not evaluated in this environment"; ScreenAccessibilityTests
 * runs them on a device), so their label rule is checked on the semantics tree as well:
 * everything tappable says what it is.
 */
fun ComposeContentTestRule.checkAccessibility() {
    onAllNodes(isRoot()).tryPerformAccessibilityChecks()
    for (node in onAllNodes(hasClickAction()).fetchSemanticsNodes()) {
        // A link inside text is read from its span of the text around it.
        if (node.config.any { it.key.name == "LinkTestMarker" }) continue
        val label = node.config.getOrNull(SemanticsProperties.Text).orEmpty().map { it.text } +
            node.config.getOrNull(SemanticsProperties.ContentDescription).orEmpty()
        assertTrue("no label: ${node.config}", label.any { it.isNotBlank() })
    }
}
