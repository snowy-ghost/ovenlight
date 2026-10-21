package com.snowyghost.ovenlight

import android.Manifest
import android.net.Uri
import android.webkit.PermissionRequest
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.snowyghost.ovenlight.MediaCapturePolicy.MediaCaptureType
import java.net.URI
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Ovenlight's side of a page's microphone and camera: who asks, what's remembered, and the stop. */
@RunWith(AndroidJUnit4::class)
@OptIn(ExperimentalCoroutinesApi::class)
class PageCaptureTests {
    private class Request(private val from: String, private vararg val wanted: String) : PermissionRequest() {
        var granted: List<String>? = null
        var denied = false
        override fun getOrigin(): Uri = Uri.parse(from)
        override fun getResources(): Array<out String> = wanted
        override fun grant(resources: Array<out String>) {
            granted = resources.toList()
        }

        override fun deny() {
            denied = true
        }
    }

    private val audio = PermissionRequest.RESOURCE_AUDIO_CAPTURE
    private val video = PermissionRequest.RESOURCE_VIDEO_CAPTURE
    private val own = "https://coach.tailabc123.ts.net"
    private val app = WebApp(name = "Coach", startURL = URI("$own/"), discovered = true)

    private var locked = false
    private var onScreen = true
    private var held = true
    private var capturing = false
    private var destroyed = 0
    private val asked = mutableListOf<List<String>>()

    /** What Android refuses without asking, turned off for Ovenlight for good. */
    private var refusedForGood = emptyList<String>()

    private fun TestScope.capture() = PageCapture(
        app, ownersHosts = { setOf("coach.tailabc123.ts.net") }, isLocked = { locked }, isOnScreen = { onScreen }, scope = this,
        permissions = { needed, refused, done ->
            asked += needed
            needed.filter { it in refusedForGood }.takeIf { it.isNotEmpty() }?.let(refused)
            done(held)
        },
        holds = { held },
        stillCapturing = { capturing },
        destroy = { destroyed += 1 },
    )

    @Test
    fun testTheOwnersOwnAppGetsOnlyTheMicrophoneWithoutAsking() = runTest {
        val capture = capture()
        val request = Request(own, audio)
        capture.onPermissionRequest(request)
        assertEquals(listOf(audio), request.granted)
        assertNull(capture.prompt.value)
        assertEquals("Android's own permission first", listOf(listOf(Manifest.permission.RECORD_AUDIO)), asked)
    }

    /** Android refused for good, without asking: Ovenlight says which, and where to allow it. The camera counts first. */
    @Test
    fun testARefusedCameraOrMicrophoneSaysWhereToAllowIt() = runTest {
        val capture = capture()
        held = false
        fun refused(vararg resources: String): MediaCaptureType? {
            capture.refused.value = null
            // Each in a document of its own: a refusal lasts for the one it came in.
            capture.documentChanged()
            capture.onPermissionRequest(Request(own, *resources))
            return capture.refused.value
        }
        refusedForGood = listOf(Manifest.permission.RECORD_AUDIO)
        assertEquals(MediaCaptureType.Microphone, refused(audio))
        assertEquals(MediaCaptureType.Microphone, refused(video, audio))
        assertNull("the camera isn't asked for", refused(video))
        refusedForGood = listOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO)
        assertEquals(MediaCaptureType.Camera, refused(video))
        assertEquals(MediaCaptureType.Camera, refused(video, audio))
        refusedForGood = emptyList()
        assertNull("Android still asks", refused(video, audio))
        capture.refused.value = MediaCaptureType.Camera
        capture.stop()
        assertNull("the lock or closing the app puts it away", capture.refused.value)
    }

    @Test
    fun testAnAllowLastsForTheDocument() = runTest {
        val capture = capture()
        val first = Request(own, video, audio)
        capture.onPermissionRequest(first)
        val prompt = capture.prompt.value!!
        assertEquals(MediaCaptureType.CameraAndMicrophone, prompt.type)
        assertNull(prompt.otherHost)
        prompt.answer(true)
        assertEquals(listOf(video, audio), first.granted)
        assertNull(capture.prompt.value)

        val again = Request(own, video)
        capture.onPermissionRequest(again)
        assertEquals("asked once in this document", listOf(video), again.granted)

        capture.documentChanged()
        capture.onPermissionRequest(Request(own, video))
        assertTrue("a new document asks again", capture.prompt.value != null)
    }

    /**
     * A refusal lasts for the document, as in WebKit: a page that asks again on each one is
     * refused at once, without Ovenlight's question, Android's or the refused notice coming
     * back, until a new document or the stop. What Android refused is asked about again once
     * Settings allows it.
     */
    @Test
    fun testARefusalLastsForTheDocument() = runTest {
        val capture = capture()
        capture.onPermissionRequest(Request(own, video))
        capture.prompt.value!!.answer(false)
        val again = Request(own, video, audio)
        capture.onPermissionRequest(again)
        capture.onPermissionRequest(Request("https://meet.example.com", video))
        assertTrue(again.denied)
        assertNull("not asked again, whichever frame asks", capture.prompt.value)
        assertEquals("nor Android", 1, asked.size)
        capture.onPermissionRequest(Request("https://meet.example.com", audio))
        assertTrue("what wasn't refused is still asked about", capture.prompt.value != null)
        capture.prompt.value!!.answer(false)

        capture.stop()
        capture.onPermissionRequest(Request(own, video))
        assertTrue("the stop forgets it", capture.prompt.value != null)
        capture.prompt.value!!.answer(false)
        capture.documentChanged()
        capture.onPermissionRequest(Request(own, video))
        assertTrue("and so does a new document", capture.prompt.value != null)
        capture.prompt.value!!.answer(false)

        capture.documentChanged()
        asked.clear()
        held = false
        refusedForGood = listOf(Manifest.permission.CAMERA)
        capture.onPermissionRequest(Request(own, video))
        assertEquals(MediaCaptureType.Camera, capture.refused.value)
        capture.refused.value = null
        capture.onPermissionRequest(Request(own, video))
        assertNull("the notice once a document", capture.refused.value)
        assertEquals("and Android's question once", 1, asked.size)
        held = true
        capture.onPermissionRequest(Request(own, video))
        assertTrue("allowed in Settings, it's asked about again", capture.prompt.value != null)
    }

    @Test
    fun testNothingWhileLockedOrOffScreenOrWithoutAndroidsPermission() = runTest {
        val capture = capture()
        locked = true
        val underLock = Request(own, audio)
        capture.onPermissionRequest(underLock)
        onScreen = false
        locked = false
        val hidden = Request(own, video)
        capture.onPermissionRequest(hidden)
        assertTrue(underLock.denied && hidden.denied)
        assertTrue("not even Android's prompt", asked.isEmpty())

        onScreen = true
        held = false
        val refused = Request(own, video)
        capture.onPermissionRequest(refused)
        assertTrue(refused.denied)
        assertNull(capture.prompt.value)

        val midi = Request(own, PermissionRequest.RESOURCE_MIDI_SYSEX)
        capture.onPermissionRequest(midi)
        assertTrue("only the microphone and camera are asked for", midi.denied)
    }

    @Test
    fun testAFrameOnAnotherHostIsNamed() = runTest {
        val capture = capture()
        capture.onPermissionRequest(Request("https://meet.example.com", audio))
        assertEquals("meet.example.com", capture.prompt.value!!.otherHost)
    }

    @Test
    fun testAPromptGoesWithTheRequestOrTheStop() = runTest {
        val capture = capture()
        val canceled = Request(own, video)
        capture.onPermissionRequest(canceled)
        capture.onPermissionRequestCanceled(canceled)
        assertNull(capture.prompt.value)
        assertNull(canceled.granted)

        val stopped = Request(own, video)
        capture.onPermissionRequest(stopped)
        capture.stop()
        assertNull(capture.prompt.value)
        assertTrue(stopped.denied)
    }

    @Test
    fun testAViewStillCapturingAfterTheStopIsDestroyed() = runTest {
        val capture = capture()
        capture.stop()
        capturing = true
        advanceTimeBy(PageCapture.STOP_WAIT_MILLIS + 1)
        assertEquals("a page never granted capture can't hold any", 0, destroyed)

        capture.onPermissionRequest(Request(own, audio))
        onScreen = false
        capture.stop()
        advanceTimeBy(PageCapture.STOP_WAIT_MILLIS - 1)
        assertEquals("the system is given two seconds to let go", 0, destroyed)
        advanceTimeBy(2)
        runCurrent()
        assertEquals(1, destroyed)

        capture.stop()
        advanceTimeBy(PageCapture.STOP_WAIT_MILLIS / 2)
        capturing = false
        advanceTimeBy(PageCapture.STOP_WAIT_MILLIS)
        assertEquals("let go within the wait, it stays", 1, destroyed)
    }
}
