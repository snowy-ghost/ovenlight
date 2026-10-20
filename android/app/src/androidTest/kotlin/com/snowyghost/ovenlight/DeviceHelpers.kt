package com.snowyghost.ovenlight

import android.os.SystemClock
import android.view.MotionEvent
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.compose.ui.test.tryPerformAccessibilityChecks
import androidx.test.platform.app.InstrumentationRegistry

// What the device tests share.

/** Runs [block] on the main thread and returns its value; what it throws, it throws here, on the test's thread. */
fun <T> onMain(block: () -> T): T {
    var result: Result<T>? = null
    InstrumentationRegistry.getInstrumentation().runOnMainSync { result = runCatching(block) }
    return result!!.getOrThrow()
}

/** Checks [condition] every 100 ms, and fails naming [what] if it isn't met within [millis]. */
fun waitFor(what: String, millis: Long = 15_000, condition: () -> Boolean) {
    val deadline = SystemClock.uptimeMillis() + millis
    while (SystemClock.uptimeMillis() < deadline) {
        if (condition()) return
        Thread.sleep(100)
    }
    throw AssertionError("timed out waiting for $what")
}

/** A real tap at a point on screen, which WebView counts as a user gesture. */
fun tap(at: Pair<Float, Float>) {
    val (x, y) = at
    val instrumentation = InstrumentationRegistry.getInstrumentation()
    val down = SystemClock.uptimeMillis()
    instrumentation.sendPointerSync(MotionEvent.obtain(down, down, MotionEvent.ACTION_DOWN, x, y, 0))
    instrumentation.sendPointerSync(MotionEvent.obtain(down, down + 50, MotionEvent.ACTION_UP, x, y, 0))
}

/** The Accessibility Test Framework's checks on every root showing, dialogs, menus and sheets included. */
fun ComposeTestRule.checkEveryRoot() = onAllNodes(isRoot()).fetchSemanticsNodes().indices.forEach { onAllNodes(isRoot())[it].tryPerformAccessibilityChecks() }
