package com.snowyghost.ovenlight

import org.junit.Assert.assertEquals
import org.junit.Test

class FileChoiceTests {
    private val types = mapOf("pdf" to "application/pdf", "png" to "image/png", "jpg" to "image/jpeg", "mov" to "video/quicktime")

    private fun kind(vararg accept: String, capture: Boolean = false) = FileChoice.kind(accept.toList(), capture) { types[it] }

    @Test
    fun testImagesAndVideoOpenThePhotoPicker() {
        assertEquals(FileChoice.Kind.Media(images = true, videos = false, single = null), kind("image/*"))
        assertEquals(FileChoice.Kind.Media(images = false, videos = true, single = null), kind("video/*"))
        assertEquals(FileChoice.Kind.Media(images = true, videos = true, single = null), kind("image/*", "video/*"))
        assertEquals("one type, comma-joined or not", FileChoice.Kind.Media(images = true, videos = false, single = "image/png"), kind(" .PNG, image/png"))
        assertEquals(FileChoice.Kind.Media(images = true, videos = false, single = null), kind(".png,.jpg"))
    }

    @Test
    fun testEverythingElseOpensDocuments() {
        assertEquals(FileChoice.Kind.Documents(listOf("*/*")), kind())
        assertEquals(FileChoice.Kind.Documents(listOf("*/*")), kind(""))
        assertEquals(FileChoice.Kind.Documents(listOf("application/pdf")), kind(".pdf"))
        assertEquals(FileChoice.Kind.Documents(listOf("image/*", "application/pdf")), kind("image/*", "application/pdf"))
        assertEquals("a name nobody knows takes any file", FileChoice.Kind.Documents(listOf("*/*")), kind("image/*", ".heic2"))
    }

    @Test
    fun testCaptureOpensTheCameraWhereAPhotoOrVideoWouldDo() {
        assertEquals(FileChoice.Kind.Camera(video = false), kind("image/*", capture = true))
        assertEquals(FileChoice.Kind.Camera(video = false), kind(capture = true))
        assertEquals(FileChoice.Kind.Camera(video = true), kind("video/*", ".mov", capture = true))
        assertEquals(FileChoice.Kind.Camera(video = false), kind("image/*", "video/*", capture = true))
        assertEquals(FileChoice.Kind.Documents(listOf("application/pdf")), kind(".pdf", capture = true))
    }
}
