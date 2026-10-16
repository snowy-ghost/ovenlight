package com.snowyghost.ovenlight

import android.accessibilityservice.AccessibilityServiceInfo
import android.content.pm.ActivityInfo
import android.graphics.Rect
import android.os.SystemClock
import android.view.KeyEvent
import android.view.View
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import android.view.inspector.WindowInspector
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.net.URI
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * An app's page open in the real activity, under the lock and across a trip out. The page is
 * local (loadDataWithBaseURL on the app's own origin), loaded into the app's own view, so the
 * lock, the pool and the lifecycle are Ovenlight's own.
 */
@RunWith(AndroidJUnit4::class)
class OpenPageTests {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val model get() = OvenlightApp.model
    private lateinit var scenario: ActivityScenario<MainActivity>
    private lateinit var app: WebApp
    private lateinit var page: AppPage

    /** The shared model's lock delay, kept long here so the trips out don't lock on return. */
    private var lockAfter: Int? = null

    /** Unlocks as a fingerprint would. */
    private val fingerprint = object : Authenticator {
        override suspend fun authenticate() = Authenticator.Result.Success
    }

    private val html = """
        <!doctype html><meta name=viewport content='width=device-width, initial-scale=1'>
        <title>ready</title><body style='margin:0;padding-top:120px;font-size:24px'>
        <h1>Page under the lock</h1>
        <input id=field aria-label='Page field' style='font-size:24px;width:80%'>
        <input id=when type=date value='2026-03-05' aria-label='Page date' style='font-size:24px;width:80%;margin-top:24px'>
        <input id=tint type=color value='#336699' aria-label='Page color' style='display:block;width:120px;height:60px;margin-top:24px'>
        <closed-date id=inner style='display:block;margin-top:24px'></closed-date>
        <select id=pick aria-label='Page choice' style='position:fixed;left:10%;width:80%;top:70%;height:96px;font-size:32px'>
          <option>Alpha option</option><option>Beta option</option><option>Gamma option</option>
        </select>
        <script>
          window.loadedAt = Math.random();
          window.keys = 0;
          window.ticks = 0;
          addEventListener('keydown', () => { window.keys++; }, true);
          setInterval(() => { window.ticks++; }, 100);
          // Out of every script's reach but the page's own, as a web component's input can be.
          customElements.define('closed-date', class extends HTMLElement {
            constructor() {
              super();
              window.closedDate = Object.assign(document.createElement('input'), { type: 'date', value: '2026-03-05' });
              window.closedDate.style.cssText = 'font-size:24px;width:100%';
              this.attachShadow({ mode: 'closed' }).append(window.closedDate);
            }
          });
        </script>
    """.trimIndent()

    @Before
    fun setUp() {
        runBlocking { withTimeout(30_000) { WebViewSetup.state.first { it == WebViewSetup.State.Ready } } }
        runBlocking { model.lock.unlock(fingerprint) }
        lockAfter = model.lock.lockAfterSeconds
        model.lock.lockAfterSeconds = 300
        // The pill's one-time tip would take the first tap.
        PreferencesStore(instrumentation.targetContext).putBoolean(ChromeController.TIP_CLOSED_KEY, true)
        scenario = ActivityScenario.launch(MainActivity::class.java)
        app = onMain { model.registry.add(URI("https://lock-check.example.invalid/")) }
        onMain { model.router.open(app) }
        waitFor("the app's page on screen") { onMain { model.pool.onScreen == app.id } }
        page = onMain { model.pool.page(app) }
        waitFor("the page's view in the window") { onMain { page.view.isAttachedToWindow } }
        onMain { page.view.loadDataWithBaseURL(app.startURL.toString(), html, "text/html", "utf-8", app.startURL.toString()) }
        waitFor("the page") { evaluate("document.title") == "\"ready\"" && onMain { page.hasLoadedOnce.value } }
    }

    @After
    fun tearDown() {
        // First, so a setUp that failed part way still leaves the shared delay as it found it.
        lockAfter?.let { model.lock.lockAfterSeconds = it }
        runBlocking { model.lock.unlock(fingerprint) }
        onMain { model.router.openApp.value = null }
        // The registry drops the app's views, which WebView takes only on the main thread.
        if (::app.isInitialized) runBlocking(Dispatchers.Main) { model.registry.remove(app.id) }
        if (::scenario.isInitialized) scenario.close()
    }

    // Helpers

    private fun evaluate(script: String): String? {
        val result = ArrayBlockingQueue<String>(1)
        onMain { page.view.evaluateJavascript(script) { result.offer(it) } }
        return result.poll(5, TimeUnit.SECONDS)
    }

    // A read that fails fails the test, so a comparison of two failed reads can't pass.
    private fun number(script: String): Double =
        evaluate(script)?.toDoubleOrNull() ?: throw AssertionError("no number from $script")

    private fun lock() {
        onMain { model.lock.lock() }
        waitFor("the page to leave the window") { onMain { !page.view.isAttachedToWindow } }
        instrumentation.waitForIdleSync()
    }

    private fun unlock() {
        runBlocking { model.lock.unlock(fingerprint) }
        waitFor("the page back in the window") { onMain { page.view.isAttachedToWindow } }
        instrumentation.waitForIdleSync()
    }

    /** Leaves Ovenlight and comes back past the delay, to the lock and the system's prompt. */
    private fun leaveAndComeBackLocked() {
        model.lock.lockAfterSeconds = 0
        scenario.moveToState(Lifecycle.State.CREATED)
        Thread.sleep(500)
        scenario.moveToState(Lifecycle.State.RESUMED)
        waitFor("the lock") { model.lock.isLocked.value }
        model.lock.lockAfterSeconds = 300
    }

    /**
     * A key, as a keyboard sends it to whatever window has focus. Through UiAutomation, since that
     * may be the system's unlock prompt, which Android 10 lets no app's own events reach.
     */
    private fun press(key: Int) {
        val down = SystemClock.uptimeMillis()
        for (action in listOf(KeyEvent.ACTION_DOWN, KeyEvent.ACTION_UP)) {
            instrumentation.uiAutomation.injectInputEvent(KeyEvent(down, SystemClock.uptimeMillis(), action, key, 0), true)
        }
    }

    /** Closes the system's prompt with Back, once it shows: before, Back would go to the lock screen and leave. */
    private fun closeThePrompt() {
        if (!model.lock.isAuthenticating.value) return
        waitFor("the prompt") { find("Unlock Ovenlight") != null }
        press(KeyEvent.KEYCODE_BACK)
        waitFor("the prompt to close") { !model.lock.isAuthenticating.value }
    }

    /** The text, descriptions and classes of every window on screen, as TalkBack reads them. */
    private fun screen(): List<String> {
        val ui = instrumentation.uiAutomation
        ui.serviceInfo = ui.serviceInfo.apply {
            flags = flags or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS or AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS
        }
        Thread.sleep(500)
        val seen = mutableListOf<String>()
        fun walk(node: AccessibilityNodeInfo?) {
            node ?: return
            listOfNotNull(node.text, node.contentDescription, node.className).forEach { seen += it.toString() }
            for (i in 0 until node.childCount) walk(node.getChild(i))
        }
        ui.windows.forEach { walk(it.root) }
        return seen
    }

    /** The middle of what on screen shows or describes itself as [label], in any window but the page's. */
    private fun find(label: String): Pair<Float, Float>? {
        screen()
        fun walk(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
            if (node == null || node.className == "android.webkit.WebView") return null
            if (node.text?.toString() == label || node.contentDescription?.toString() == label) return node
            return (0 until node.childCount).firstNotNullOfOrNull { walk(node.getChild(it)) }
        }
        val node = instrumentation.uiAutomation.windows.firstNotNullOfOrNull { walk(it.root) } ?: return null
        val box = Rect().also(node::getBoundsInScreen)
        return box.exactCenterX() to box.exactCenterY()
    }

    /** The topmost of Ovenlight's windows. */
    private fun topWindow(): List<String> {
        screen()
        val top = instrumentation.uiAutomation.windows.filter { it.type == AccessibilityWindowInfo.TYPE_APPLICATION }.maxBy { it.layer }
        val seen = mutableListOf<String>()
        fun walk(node: AccessibilityNodeInfo?) {
            node ?: return
            listOfNotNull(node.text, node.contentDescription).forEach { seen += it.toString() }
            for (i in 0 until node.childCount) walk(node.getChild(i))
        }
        walk(top.root)
        return seen
    }

    private val pageMarks = listOf("Page under the lock", "Alpha option", "Beta option", "Gamma option", "android.webkit.WebView")

    /** The middle of a page element on screen, or with [atEnd] its far end, where a date input's calendar button is. */
    private fun element(id: String, atEnd: Boolean = false): Pair<Float, Float> {
        val x = if (atEnd) "r.right - 14" else "r.left + r.width / 2"
        val box = evaluate("(r => [$x, r.top + r.height / 2])(document.getElementById('$id').getBoundingClientRect()).join(' ')")
            ?.trim('"')?.split(' ')?.map(String::toFloat) ?: error("no $id")
        return onMain {
            val at = IntArray(2).also(page.view::getLocationOnScreen)
            val scale = page.view.resources.displayMetrics.density
            (at[0] + box[0] * scale) to (at[1] + box[1] * scale)
        }
    }

    // The safe area

    /** The page's `env(safe-area-inset-top)` and the status bar's height, both in CSS pixels (dp). */
    private fun topInsets(): Pair<Double, Double> {
        evaluate("document.body.insertAdjacentHTML('beforeend', \"<div id=inset style='position:fixed;padding-top:env(safe-area-inset-top)'></div>\")")
        val env = number("parseFloat(getComputedStyle(document.getElementById('inset')).paddingTop)")
        evaluate("document.getElementById('inset').remove()")
        val bar = onMain {
            val insets = ViewCompat.getRootWindowInsets(page.view) ?: error("no insets")
            insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()).top / page.view.resources.displayMetrics.density.toDouble()
        }
        return env to bar
    }

    private fun viewTop(): Int = onMain { IntArray(2).also(page.view::getLocationOnScreen)[1] }

    /**
     * A page that asks for `viewport-fit=cover` runs under the status bar and gets its height
     * through `env()`, however the page came on screen: opened, reopened from the pool, after the
     * lock and after a rotation. A page that doesn't sits below the status bar instead, and sees
     * no inset.
     */
    @Test
    fun testACoverPageGetsTheStatusBarAndAnyOtherSitsBelowIt() {
        fun assertCovers(how: String) {
            waitFor("the cover page laid out edge to edge, $how") { viewTop() == 0 }
            val (env, bar) = topInsets()
            assertTrue("$how: env(safe-area-inset-top) $env, the status bar $bar", bar > 0 && kotlin.math.abs(env - bar) < 1.5)
        }
        onMain { page.view.loadDataWithBaseURL(app.startURL.toString(), html.replace("initial-scale=1", "initial-scale=1, viewport-fit=cover"), "text/html", "utf-8", null) }
        waitFor("the cover page") { onMain { page.fitsCover.value } }
        assertCovers("opened")

        onMain { model.router.openApp.value = null }
        waitFor("the page to leave the window") { onMain { !page.view.isAttachedToWindow } }
        onMain { model.router.open(app) }
        waitFor("the page back in the window") { onMain { page.view.isAttachedToWindow } }
        assertCovers("reopened")

        lock()
        unlock()
        assertCovers("unlocked")

        try {
            scenario.onActivity { it.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE }
            waitFor("landscape") { onMain { page.view.width > page.view.height } }
            scenario.onActivity { it.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT }
            waitFor("portrait") { onMain { page.view.width < page.view.height } }
        } finally {
            scenario.onActivity { it.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED }
        }
        assertCovers("rotated and back")

        onMain { page.view.loadDataWithBaseURL(app.startURL.toString(), html, "text/html", "utf-8", null) }
        waitFor("the plain page") { onMain { !page.fitsCover.value } }
        val bar = topInsets().second
        waitFor("the plain page below the status bar") { kotlin.math.abs(viewTop() / onMain { page.view.resources.displayMetrics.density } - bar) < 1 }
        assertEquals("no inset for a page inside the bars", 0.0, topInsets().first, 0.0)
    }

    // The lock

    @Test
    fun testNothingOfThePageIsInTheAccessibilityTreeUnderTheLock() {
        // WebView builds its tree once an accessibility service first asks.
        waitFor("the page in the accessibility tree") { screen().contains("Page under the lock") }
        lock()
        val locked = screen()
        for (mark in pageMarks) assertFalse("$mark under the lock: $locked", locked.contains(mark))
        assertTrue("the lock screen shows: $locked", locked.any { it.contains("Ovenlight is locked") || it.contains("Unlock") })
    }

    @Test
    fun testTheKeyboardReachesNothingInThePageUnderTheLock() {
        evaluate("document.getElementById('field').focus()")
        waitFor("the field focused") { evaluate("document.activeElement.id") == "\"field\"" }
        lock()
        for (key in listOf(KeyEvent.KEYCODE_TAB, KeyEvent.KEYCODE_TAB, KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_A, KeyEvent.KEYCODE_B, KeyEvent.KEYCODE_SPACE)) {
            press(key)
        }
        // Enter or Space may have pressed the lock screen's own Unlock; its prompt goes first.
        Thread.sleep(1_000)
        closeThePrompt()
        unlock()
        assertEquals("no key reached the page", 0.0, number("window.keys"), 0.0)
        assertEquals("nothing typed", "\"\"", evaluate("document.getElementById('field').value"))
        assertEquals("ready", evaluate("document.title")?.trim('"'))

        // Unlocked, the same keys do reach it, so the check above means something.
        tap(element("field"))
        waitFor("the field focused again") { evaluate("document.activeElement.id") == "\"field\"" }
        instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_X)
        waitFor("a key in the page") { number("window.keys") > 0 }
    }

    /**
     * A picker, the choice made in it before the lock, and what takes that choice. A select's list
     * closes as its page leaves the window; the pickers stay open.
     */
    private class Picker(val opens: String, val value: String, val was: String, val choice: String?, val takes: String, val chosen: String, val staysOpen: Boolean = true, val atEnd: Boolean = false)

    /**
     * WebView's select lists and pickers are windows of their own, which leaving composition
     * doesn't close, and nothing reaches one a closed shadow root holds. Whatever a page left
     * open stays under the lock window, on both ways in: the lock is the topmost window, and a tap
     * where the picker's Set was changes nothing. After the unlock the picker is still open, as on
     * iOS, and the same tap does set it, so the tap above was in the right place.
     */
    @Test
    fun testAPickerLeftOpenStaysUnderTheLock() {
        val pickers = listOf(
            Picker("pick", "document.getElementById('pick').value", "Alpha option", null, "Beta option", "Beta option", staysOpen = false),
            Picker("when", "document.getElementById('when').value", "2026-03-05", "10 March 2026", "SET", "2026-03-10", atEnd = true),
            Picker("inner", "window.closedDate.value", "2026-03-05", "10 March 2026", "SET", "2026-03-10", atEnd = true),
            Picker("tint", "document.getElementById('tint').value", "#336699", "Red", "Set", "#ff0000"),
        )
        for (comingBack in listOf(false, true)) for (picker in pickers) {
            val way = if (comingBack) "coming back locked" else "locked in place"
            evaluate("${picker.value} = '${picker.was}'")
            tap(element(picker.opens, picker.atEnd))
            waitFor("${picker.opens}'s picker") { find(picker.takes) != null }
            picker.choice?.let { tap(find(it) ?: error("no ${picker.choice}")) }
            val takes = find(picker.takes) ?: error("no ${picker.takes}")
            if (comingBack) leaveAndComeBackLocked() else lock()
            closeThePrompt()
            val top = topWindow()
            assertTrue("${picker.opens}, $way: the lock isn't the topmost window: $top", "Ovenlight is locked" in top)
            val reachable = screen()
            assertFalse("${picker.opens}, $way: its picker within TalkBack's reach: $reachable", picker.takes in reachable)
            tap(takes)
            Thread.sleep(500)
            // The tap may have pressed the lock screen's own Unlock.
            closeThePrompt()
            unlock()
            assertEquals("${picker.opens}, $way: set under the lock", "\"${picker.was}\"", evaluate(picker.value))
            if (!picker.staysOpen) continue
            waitFor("${picker.opens}'s picker after the unlock, $way") { find(picker.takes) != null }
            tap(takes)
            waitFor("${picker.opens}, $way: set after the unlock") { evaluate(picker.value) == "\"${picker.chosen}\"" }
            waitFor("${picker.opens}'s picker to close") { find(picker.takes) == null }
        }
    }

    /** A text or display size change while locked keeps the lock's window: Compose would make it anew, with a frame between. */
    @Test
    fun testTheLockKeepsItsWindowThroughATextSizeChange() {
        lock()
        var activityWindow: View? = null
        scenario.onActivity { activityWindow = it.window.decorView }
        // With no picker open, the lock's is the one window shown besides the activity's own.
        val lockWindow = { onMain { WindowInspector.getGlobalWindowViews().single { it !== activityWindow && it.isShown } } }
        val before = lockWindow()
        val shell = { command: String -> instrumentation.uiAutomation.executeShellCommand(command).close() }
        try {
            shell("settings put system font_scale 1.3")
            waitFor("the new text size") { onMain { page.view.resources.configuration.fontScale } == 1.3f }
            Thread.sleep(1_000)
            assertTrue("the same lock window", lockWindow() === before)
        } finally {
            shell("settings put system font_scale 1.0")
        }
    }

    // A trip out and back

    /** Ovenlight's own Custom Tab or a picker: for its first minutes the page runs on, and nothing reloads it. */
    @Test
    fun testThePageRunsThroughAnExcursion() {
        val loaded = evaluate("String(window.loadedAt)")
        onMain { model.lifecycle.beginExcursion() }
        scenario.moveToState(Lifecycle.State.CREATED)
        val before = number("window.ticks")
        Thread.sleep(3_000)
        val during = number("window.ticks")
        scenario.moveToState(Lifecycle.State.RESUMED)
        waitFor("the page on screen again") { onMain { page.view.isAttachedToWindow && model.pool.onScreen == app.id } }
        // A page out of sight runs its timers at most once a second (Chromium's throttling of
        // hidden pages), but runs: Ovenlight pauses them only when the person leaves.
        assertTrue("its timers ran while away: $before to $during", during - before >= 2)
        assertEquals("not reloaded", loaded, evaluate("String(window.loadedAt)"))
        val back = number("window.ticks")
        Thread.sleep(1_000)
        assertTrue("and run on", number("window.ticks") - back >= 5)
    }

    /** Leaving pauses the page's timers; the return resumes them, without a reload. */
    @Test
    fun testThePageResumesAfterLeaving() {
        val loaded = evaluate("String(window.loadedAt)")
        scenario.moveToState(Lifecycle.State.CREATED)
        Thread.sleep(500)
        val before = number("window.ticks")
        Thread.sleep(3_000)
        val during = number("window.ticks")
        scenario.moveToState(Lifecycle.State.RESUMED)
        waitFor("the page on screen again") { onMain { page.view.isAttachedToWindow && model.pool.onScreen == app.id } }
        assertTrue("its timers paused while away: $before to $during", during - before <= 1)
        assertEquals("not reloaded", loaded, evaluate("String(window.loadedAt)"))
        val back = number("window.ticks")
        Thread.sleep(1_000)
        assertTrue("and resume", number("window.ticks") - back >= 5)
    }
}
