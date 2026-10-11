package com.inventori.android

interface InventoryAiRecognizer {
    fun recognize(photoPath: String?, expectedItems: Int): List<InventoryItem>
}

/**
 * Deterministic local preview implementation. Replace this implementation with a server-side
 * provider adapter when a secured backend is available; never bundle provider credentials in APK.
 */
class MockInventoryAiRecognizer : InventoryAiRecognizer {
    override fun recognize(photoPath: String?, expectedItems: Int): List<InventoryItem> {
        val suggestions = if (expectedItems > 1) {
            listOf(
                InventoryItem(name = "Detected power drill", category = "Tools", quantity = 1),
                InventoryItem(name = "Detected battery pack", category = "Tools", quantity = 1)
            )
        } else {
            listOf(InventoryItem(name = "Detected item", category = "Uncategorized", quantity = 1))
        }
        return suggestions.mapIndexed { index, item ->
            item.copy(photoPath = if (index == 0) photoPath else photoPath)
        }
    }
}
