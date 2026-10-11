package com.inventori.android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class InventoryAiRecognizerTest {
    private val recognizer = MockInventoryAiRecognizer()

    @Test
    fun mockProducesOneDraftAndPreservesPhotoReference() {
        val results = recognizer.recognize("/local/photo.jpg", 1)
        assertEquals(1, results.size)
        assertEquals("Detected item", results.single().name)
        assertEquals("/local/photo.jpg", results.single().photoPath)
    }

    @Test
    fun mockProducesMultipleDraftsForOnePhoto() {
        val results = recognizer.recognize("/local/group.jpg", 3)
        assertEquals(2, results.size)
        assertNotNull(results[0].name)
        assertNotNull(results[1].name)
        assertEquals("/local/group.jpg", results[0].photoPath)
    }
}
