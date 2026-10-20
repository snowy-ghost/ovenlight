package com.snowyghost.ovenlight

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.net.URI
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith

/** The pool's tests from iOS, on a device: they need real web views. Nodes are faked. */
@RunWith(AndroidJUnit4::class)
class WebViewPoolTests {
    @get:Rule
    val composeRule = createComposeRule()

    @get:Rule
    val temp = TemporaryFolder()

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val scope = MainScope()
    private lateinit var nodes: NodeManager
    private lateinit var pool: WebViewPool

    @Before
    fun setUp() {
        runBlocking { withTimeout(30_000) { WebViewSetup.state.first { it == WebViewSetup.State.Ready } } }
        onMain {
            nodes = NodeManager(
                FakeClients(), MemoryStore(), MembershipStore(File(temp.root, "Ovenlight")), File(temp.root, "tailscale"),
                scope, Dispatchers.IO, System::currentTimeMillis, {},
            )
            pool = WebViewPool(context, nodes, scope, isLocked = MutableStateFlow(false))
        }
    }

    @After
    fun tearDown() {
        onMain { scope.cancel() }
    }

    private val funnel = WebApp(name = "Funnel", startURL = URI("https://funnel.tail0000.ts.net/"))
    private val own = WebApp(name = "Foo", startURL = URI("https://foo.taila.ts.net/"))
    private val direct = WebApp(name = "Site", startURL = URI("https://site.example.invalid/"))

    @Test
    fun testAStaleViewNeverLoadsAndIsMadeAgain() = onMain {
        val stale = pool.page(funnel)
        assertSame("before the owner's tailnet is known, a ts.net name waits on the owner's node", nodes.owner, stale.node)
        val revision = pool.revision.value
        // The owner signs in, to a tailnet this host isn't on, while the app waits.
        nodes.rememberOwnerSuffix("tailabc123.ts.net")
        assertTrue("replaced", pool.revision.value > revision)
        assertNull("nothing loaded through the owner's node", stale.view.url)
        val current = pool.page(funnel)
        assertNotSame(stale, current)
        assertNull("made again, refused", current.node)
        pool.discard(funnel.id)
    }

    @Test
    fun testLoggingOutDropsTheViewsTheOwnersNodeCarried() {
        val (ownPage, directPage, revision) = onMain {
            nodes.rememberOwnerSuffix("taila.ts.net")
            val ownPage = pool.page(own)
            assertSame(nodes.owner, ownPage.node)
            Triple(ownPage, pool.page(direct), pool.revision.value)
        }
        // On the main thread, where the pool drops views, while this thread waits.
        val loggingOut = onMain { scope.launch { nodes.owner.logOutAndForget() } }
        runBlocking { withTimeout(30_000) { loggingOut.join() } }
        onMain {
            assertTrue("an app on screen fetches a new view", pool.revision.value > revision)
            assertNotSame(ownPage, pool.page(own))
            assertSame("views on other routes stay", directPage, pool.page(direct))
            pool.discard(own.id)
            pool.discard(direct.id)
        }
    }

    @Test
    fun testANewOwnerTailnetRefusesTheOldOnesApps() = onMain {
        nodes.rememberOwnerSuffix("taila.ts.net")
        val page = pool.page(own)
        nodes.rememberOwnerSuffix("tailb.ts.net")
        assertNull("nothing carries it", nodes.node(own))
        assertTrue(MailDraft.problemReport(context, nodes, pool, own).body.contains("Connection: Refused (on no known tailnet)"))
        assertFalse("dropped at once, not at the next probe or open", pool.hasPage(own.id))
        val fresh = pool.page(own)
        assertNotSame(page, fresh)
        assertNull("another tailnet's name is refused", fresh.node)
        pool.discard(own.id)
    }

    @Test
    fun testAnAppNoLongerListedGetsNoWebView() {
        val model = OvenlightApp.model
        val listed = onMain { model.registry.add(URI("https://listed.tail0000.ts.net/")) }
        val removed = WebApp(name = "Gone", startURL = URI("https://gone.tail0000.ts.net/"))
        var showing by mutableStateOf(listed)
        composeRule.setContent { OvenlightTheme { AppHostView(model, showing, onClose = {}) } }
        composeRule.waitForIdle()
        assertTrue("a listed app gets its view", onMain { model.pool.hasPage(listed.id) })
        composeRule.runOnIdle { showing = removed }
        composeRule.waitForIdle()
        assertFalse("no view, no profile, nothing routed", onMain { model.pool.hasPage(removed.id) })
        // Removing waits on WebView callbacks that come on the main thread, so it runs there
        // while this thread waits.
        val removing = onMain { model.scope.launch { model.registry.remove(listed.id) } }
        runBlocking { withTimeout(30_000) { removing.join() } }
    }
}
