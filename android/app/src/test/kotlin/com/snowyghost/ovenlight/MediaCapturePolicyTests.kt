package com.snowyghost.ovenlight

import com.snowyghost.ovenlight.MediaCapturePolicy.MediaCaptureType
import com.snowyghost.ovenlight.MediaCapturePolicy.PermissionDecision
import java.net.URI
import org.junit.Assert.assertEquals
import org.junit.Test

class MediaCapturePolicyTests {
    @Test
    fun testOnlyTheOwnersOwnAppsGetTheMicrophone() {
        val app = WebApp(name = "Coach", startURL = URI("https://coach.tailabc123.ts.net/"))
        val shared = app.copy(membershipID = "m1")
        fun decide(
            type: MediaCaptureType, app: WebApp, from: String = "https://Coach.tailabc123.ts.net",
            locked: Boolean = false, onScreen: Boolean = true,
        ): PermissionDecision {
            val url = URI(from)
            return MediaCapturePolicy.decide(type, scheme = url.scheme, host = url.host, app = app,
                ownersHosts = setOf("coach.tailabc123.ts.net"), locked = locked, onScreen = onScreen)
        }
        assertEquals(PermissionDecision.Grant, decide(MediaCaptureType.Microphone, app))
        assertEquals("nothing records under the lock screen", PermissionDecision.Deny, decide(MediaCaptureType.Microphone, app, locked = true))
        assertEquals(PermissionDecision.Deny, decide(MediaCaptureType.Camera, app, locked = true))
        assertEquals("nor a page kept in the background", PermissionDecision.Deny, decide(MediaCaptureType.Microphone, app, onScreen = false))
        assertEquals(PermissionDecision.Deny, decide(MediaCaptureType.Camera, shared, onScreen = false))
        assertEquals(PermissionDecision.Prompt, decide(MediaCaptureType.Camera, app))
        assertEquals(PermissionDecision.Prompt, decide(MediaCaptureType.CameraAndMicrophone, app))
        assertEquals("someone else's app asks", PermissionDecision.Prompt, decide(MediaCaptureType.Microphone, shared))
        assertEquals("a frame from elsewhere asks", PermissionDecision.Prompt,
            decide(MediaCaptureType.Microphone, app, from = "https://evil.example.com"))
        assertEquals(PermissionDecision.Prompt, decide(MediaCaptureType.Microphone, app, from = "http://coach.tailabc123.ts.net"))
        val site = WebApp(name = "Site", startURL = URI("https://recorder.example.com/"))
        assertEquals("a site added by address isn't one of the owner's apps", PermissionDecision.Prompt,
            decide(MediaCaptureType.Microphone, site, from = "https://recorder.example.com"))
        val funnel = WebApp(name = "Funnel", startURL = URI("https://recorder.tail0000.ts.net/"))
        assertEquals("nor is someone's public Funnel address", PermissionDecision.Prompt,
            decide(MediaCaptureType.Microphone, funnel, from = "https://recorder.tail0000.ts.net"))
        val found = WebApp(name = "Found", startURL = URI("https://notes.tailabc123.ts.net/"), discovered = true)
        assertEquals("discovered on the owner's machines", PermissionDecision.Grant,
            decide(MediaCaptureType.Microphone, found, from = "https://notes.tailabc123.ts.net"))
    }
}
