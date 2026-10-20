package com.snowyghost.ovenlight

import org.junit.Assert.assertEquals
import org.junit.Test

class FileChoiceTests {
    private val types = mapOf("pdf" to "application/pdf", "png" to "image/png", "jpg" to "image/jpeg", "mov" to "video/quicktime")

    private fun kind(vararg accept: String, capture: Boolean = false, filesForMedia: Boolean = false) =
        FileChoice.kind(accept.toList(), capture, { types[it] }, filesForMedia)

    private fun choice(media: FileChoice.Kind.Media, documents: List<String>? = null) =
        FileChoice.Kind.Choice(media, documents?.let { FileChoice.Kind.Documents(it) })

    /** As WebKit's menu does: an image input offers the camera beside the photo picker. */
    @Test
    fun testAnImageInputOffersTheCameraAndThePhotoPicker() {
        assertEquals(choice(FileChoice.Kind.Media(images = true, videos = false, single = null)), kind("image/*"))
        assertEquals(choice(FileChoice.Kind.Media(images = true, videos = true, single = null)), kind("image/*", "video/*"))
        assertEquals("one type, comma-joined or not", choice(FileChoice.Kind.Media(images = true, videos = false, single = "image/png")), kind(" .PNG, image/png"))
        assertEquals(choice(FileChoice.Kind.Media(images = true, videos = false, single = null)), kind(".png,.jpg"))
    }

    @Test
    fun testAnImageInputThatTakesMoreOffersFilesToo() {
        assertEquals(
            choice(FileChoice.Kind.Media(images = true, videos = false, single = null), listOf("image/*", "application/pdf")),
            kind("image/*", "application/pdf"),
        )
        assertEquals("a name nobody knows takes any file", choice(FileChoice.Kind.Media(images = true, videos = false, single = null), listOf("*/*")), kind("image/*", ".heic2"))
        assertEquals(choice(FileChoice.Kind.Media(images = true, videos = false, single = null), listOf("*/*")), kind("image/*", "*/*"))
    }

    /** Before Android 11 the photo picker is a module Play services may not have yet. */
    @Test
    fun testMediaInputsOfferFilesBeforeAndroid11() {
        assertEquals(choice(FileChoice.Kind.Media(images = true, videos = false, single = null), listOf("image/*")), kind("image/*", filesForMedia = true))
        assertEquals(choice(FileChoice.Kind.Media(images = true, videos = false, single = "image/png"), listOf("image/png")), kind(".png", filesForMedia = true))
        assertEquals(FileChoice.Kind.Documents(listOf("video/*")), kind("video/*", filesForMedia = true))
        assertEquals(FileChoice.Kind.Camera(video = false), kind("image/*", capture = true, filesForMedia = true))
    }

    @Test
    fun testVideoAloneOpensThePhotoPicker() {
        assertEquals(FileChoice.Kind.Media(images = false, videos = true, single = null), kind("video/*"))
        assertEquals(FileChoice.Kind.Media(images = false, videos = true, single = "video/quicktime"), kind(".mov"))
    }

    @Test
    fun testEverythingElseOpensDocuments() {
        assertEquals(FileChoice.Kind.Documents(listOf("*/*")), kind())
        assertEquals(FileChoice.Kind.Documents(listOf("*/*")), kind(""))
        assertEquals(FileChoice.Kind.Documents(listOf("application/pdf")), kind(".pdf"))
        assertEquals(FileChoice.Kind.Documents(listOf("video/*", "application/pdf")), kind("video/*", "application/pdf"))
        assertEquals("a name nobody knows takes any file", FileChoice.Kind.Documents(listOf("*/*")), kind(".pdf", ".heic2"))
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
