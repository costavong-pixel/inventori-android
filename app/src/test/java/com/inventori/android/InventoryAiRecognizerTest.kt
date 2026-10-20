package com.inventori.android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class InventoryAiRecognizerTest {
    private val recognizer = MockInventoryAiRecognizer()

    @Test
    fun mockProducesOneDraftAndPreservesPhotoReference() {
        val results = recognizer.recognize("/local/photo.jpg", 1, "home")
        assertEquals(1, results.items.size)
        assertEquals("Detected item", results.items.single().name)
        assertEquals("/local/photo.jpg", results.items.single().photoPath)
    }

    @Test
    fun mockProducesMultipleDraftsForOnePhoto() {
        val results = recognizer.recognize("/local/group.jpg", 3, "business")
        assertEquals(2, results.items.size)
        assertNotNull(results.items[0].name)
        assertNotNull(results.items[1].name)
        assertEquals("/local/group.jpg", results.items[0].photoPath)
    }
}
