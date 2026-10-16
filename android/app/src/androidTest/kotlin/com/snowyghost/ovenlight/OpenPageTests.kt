package com.snowyghost.ovenlight

import android.accessibilityservice.AccessibilityServiceInfo
import android.view.KeyEvent
import android.view.accessibility.AccessibilityNodeInfo
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
        <select id=pick aria-label='Page choice' style='position:fixed;left:10%;width:80%;top:55%;height:96px;font-size:32px'>
          <option>Alpha option</option><option>Beta option</option><option>Gamma option</option>
        </select>
        <script>
          window.loadedAt = Math.random();
          window.keys = 0;
          window.ticks = 0;
          addEventListener('keydown', () => { window.keys++; }, true);
          setInterval(() => { window.ticks++; }, 100);
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

    /**
     * What the screen showed while the system's prompt, which came with the lock, was closed
     * with Back. The prompt hides the windows under it, and Back may close a picker above it
     * first, so the screen is read before each Back and once the prompt is gone.
     */
    private fun screenThroughThePrompt(): List<String> {
        val seen = screen().toMutableList()
        repeat(3) {
            if (!model.lock.isAuthenticating.value) return@repeat
            instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
            Thread.sleep(1_000)
            seen += screen()
        }
        assertFalse("the prompt didn't close", model.lock.isAuthenticating.value)
        return seen
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

    private val pageMarks = listOf("Page under the lock", "Alpha option", "Beta option", "Gamma option", "android.webkit.WebView")

    /** The middle of a page element, on screen. */
    private fun element(id: String): Pair<Float, Float> {
        val box = evaluate("(r => [r.left + r.width / 2, r.top + r.height / 2])(document.getElementById('$id').getBoundingClientRect()).join(' ')")
            ?.trim('"')?.split(' ')?.map(String::toFloat) ?: error("no $id")
        return onMain {
            val at = IntArray(2).also(page.view::getLocationOnScreen)
            val scale = page.view.resources.displayMetrics.density
            (at[0] + box[0] * scale) to (at[1] + box[1] * scale)
        }
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
            instrumentation.sendKeyDownUpSync(key)
        }
        // Enter or Space may have pressed the lock screen's own Unlock; its prompt goes first.
        Thread.sleep(1_000)
        if (model.lock.isAuthenticating.value) {
            instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
            waitFor("the prompt to close") { !model.lock.isAuthenticating.value }
        }
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

    @Test
    fun testAnOpenSelectNeverShowsOverTheLock() {
        tap(element("pick"))
        waitFor("the select's list") { screen().contains("Beta option") }
        lock()
        val locked = screen()
        for (mark in pageMarks) assertFalse("$mark over the lock: $locked", locked.contains(mark))
        unlock()
        assertEquals("the choice didn't change", "\"Alpha option\"", evaluate("document.getElementById('pick').value"))
    }

    /** WebView's date picker is a window of its own, which leaving composition doesn't close. */
    @Test
    fun testAnOpenDatePickerNeverShowsOverTheLock() {
        tap(element("when"))
        waitFor("the date picker") { screen().any { it.contains("DatePicker") } }
        lock()
        val locked = screen()
        assertFalse("a date picker over the lock: $locked", locked.any { it.contains("DatePicker") })
        unlock()
        assertEquals("the date didn't change", "\"2026-03-05\"", evaluate("document.getElementById('when').value"))
    }

    /** Leaving with the picker open and coming back locked: Android shows an activity's dialogs again as it returns. */
    @Test
    fun testADatePickerLeftOpenIsGoneWhenOvenlightComesBackLocked() {
        tap(element("when"))
        waitFor("the date picker") { screen().any { it.contains("DatePicker") } }
        model.lock.lockAfterSeconds = 0
        scenario.moveToState(Lifecycle.State.CREATED)
        Thread.sleep(500)
        scenario.moveToState(Lifecycle.State.RESUMED)
        waitFor("the lock") { model.lock.isLocked.value }
        val seen = screenThroughThePrompt()
        assertFalse("a date picker over the lock: $seen", seen.any { it.contains("DatePicker") })
        unlock()
        assertEquals("the date didn't change", "\"2026-03-05\"", evaluate("document.getElementById('when').value"))
    }

    /** The color picker too, which a blur doesn't close. */
    @Test
    fun testAColorPickerLeftOpenIsGoneWhenOvenlightComesBackLocked() {
        tap(element("tint"))
        waitFor("the color picker") { screen().any { it.contains("Select color") } }
        model.lock.lockAfterSeconds = 0
        scenario.moveToState(Lifecycle.State.CREATED)
        Thread.sleep(500)
        scenario.moveToState(Lifecycle.State.RESUMED)
        waitFor("the lock") { model.lock.isLocked.value }
        val seen = screenThroughThePrompt()
        assertFalse("a color picker over the lock: $seen", seen.any { it.contains("Select color") })
        unlock()
        assertEquals("the color didn't change", "\"#336699\"", evaluate("document.getElementById('tint').value"))
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
