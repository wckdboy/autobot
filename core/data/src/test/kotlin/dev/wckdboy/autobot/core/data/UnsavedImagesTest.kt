package dev.wckdboy.autobot.core.data

import dev.wckdboy.autobot.core.data.model.Conversation
import dev.wckdboy.autobot.core.data.model.GalleryItem
import dev.wckdboy.autobot.shared.panic.IncognitoImages
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class UnsavedImagesTest {
    private val images = UnsavedImages()

    private fun item(id: String) = GalleryItem(
        id = id, createdAt = 1, mode = "TXT2IMG", prompt = "a fox", negativePrompt = "", width = 64, height = 64, seed = 7,
        steps = 4, cfgScale = 1f, sampler = "euler", engine = "test", model = null, durationMs = 10, paramsJson = "{}",
    )

    private fun put(owner: String): Pair<String, ByteArray> {
        val id = images.newId()
        val bytes = byteArrayOf(1, 2, 3)
        images.put(item(id), bytes, byteArrayOf(4), owner)
        return id to bytes
    }

    @Test
    fun idsAreMarkedUnsavedAndGalleryIdsAreRefused() {
        assertTrue(IncognitoImages.isUnsaved(images.newId()))
        assertThrows(IllegalArgumentException::class.java) { images.put(item("1234"), ByteArray(1), ByteArray(1), "x") }
    }

    @Test
    fun droppingAChatDropsItsImagesAndItsSubagentsAndZeroesThem() {
        val (mine, bytes) = put("incognito-a")
        val (sub, _) = put("incognito-a::task-1")
        val (other, _) = put("incognito-b")
        images.drop("incognito-a")
        assertNull(images.get(mine))
        assertNull(images.get(sub))
        assertNotNull(images.get(other))
        assertTrue("dropped bytes are zeroed", bytes.all { it == 0.toByte() })
    }

    @Test
    fun memoryIsBoundedOldestFirst() {
        val first = put(UnsavedImages.IMAGE_SCREEN).first
        repeat(UnsavedImages.MAX) { put(UnsavedImages.IMAGE_SCREEN) }
        assertEquals(UnsavedImages.MAX, images.ids().size)
        assertNull(images.get(first))
    }

    @Test
    fun clearingIncognitoDropsEveryUnsavedImage() {
        val session = IncognitoSession(images)
        val id = IncognitoSession.ID_PREFIX + "1"
        session.create(Conversation(id, "t", null, null, createdAt = 1, updatedAt = 1))
        val (image, _) = put(id)
        val (screen, _) = put(UnsavedImages.IMAGE_SCREEN)
        session.delete(id)
        assertNull(images.get(image))
        assertNotNull(images.get(screen))
        session.clear()
        assertEquals(emptyList<String>(), images.ids())
    }
}
