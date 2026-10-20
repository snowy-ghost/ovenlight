package com.snowyghost.ovenlight

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import org.junit.Assert.assertTrue

/**
 * The accessibility checks' label rule, on the semantics tree: everything tappable says what it
 * is. Under Robolectric the Accessibility Test Framework evaluates no Compose content, so its
 * full checks (contrast, touch targets and labels) run on a device, in ScreenAccessibilityTests.
 */
fun ComposeContentTestRule.checkAccessibility() {
    for (node in onAllNodes(hasClickAction()).fetchSemanticsNodes()) {
        // A link inside text is read from its span of the text around it.
        if (node.config.any { it.key.name == "LinkTestMarker" }) continue
        val label = node.config.getOrNull(SemanticsProperties.Text).orEmpty().map { it.text } +
            node.config.getOrNull(SemanticsProperties.ContentDescription).orEmpty()
        assertTrue("no label: ${node.config}", label.any { it.isNotBlank() })
    }
}
