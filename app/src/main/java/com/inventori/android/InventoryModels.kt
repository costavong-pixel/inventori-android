package com.inventori.android

data class InventoryWorkspace(
    val id: Long,
    val name: String,
    val type: String,
    val createdAt: Long
)

data class InventoryLocation(
    val id: Long,
    val parentId: Long?,
    val name: String,
    val type: String,
    val workspaceId: Long = 0
)

data class InventoryItem(
    val id: Long = 0,
    val workspaceId: Long = 0,
    val itemType: String = TYPE_UNIQUE,
    val name: String = "",
    val photoPath: String? = null,
    val category: String = "",
    val brand: String = "",
    val model: String = "",
    val serialNumber: String = "",
    val identifierType: String = IDENTIFIER_NONE,
    val identifierValue: String = "",
    val quantity: Int = 1,
    val purchasePrice: Double? = null,
    val replacementValue: Double? = null,
    val notes: String = "",
    val receiptPath: String? = null,
    val documentPath: String? = null,
    val evidenceLatitude: Double? = null,
    val evidenceLongitude: Double? = null,
    val evidenceAccuracyMeters: Float? = null,
    val evidenceCapturedAt: Long? = null,
    val locationId: Long? = null,
    val status: String = STATUS_AVAILABLE,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
) {
    companion object {
        const val TYPE_UNIQUE = "UNIQUE"
        const val TYPE_QUANTITY = "QUANTITY"
        const val STATUS_AVAILABLE = "AVAILABLE"
        const val STATUS_CHECKED_OUT = "CHECKED_OUT"
        const val STATUS_RETURNED = "RETURNED"
        const val IDENTIFIER_NONE = "NONE"
        const val IDENTIFIER_QR = "QR"
        const val IDENTIFIER_BARCODE = "BARCODE"
        const val IDENTIFIER_NFC = "NFC"
        const val IDENTIFIER_RFID = "RFID"
        const val IDENTIFIER_OTHER = "OTHER"
    }
}
