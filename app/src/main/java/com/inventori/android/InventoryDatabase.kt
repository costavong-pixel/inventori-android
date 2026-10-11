package com.inventori.android

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

class InventoryDatabase(context: Context) :
    SQLiteOpenHelper(context, "inventori.db", null, 1) {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """CREATE TABLE locations (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                parent_id INTEGER,
                name TEXT NOT NULL,
                type TEXT NOT NULL,
                FOREIGN KEY(parent_id) REFERENCES locations(id)
            )"""
        )
        db.execSQL(
            """CREATE TABLE items (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                item_type TEXT NOT NULL,
                name TEXT NOT NULL,
                photo_path TEXT,
                category TEXT NOT NULL DEFAULT '',
                brand TEXT NOT NULL DEFAULT '',
                model TEXT NOT NULL DEFAULT '',
                serial_number TEXT NOT NULL DEFAULT '',
                quantity INTEGER NOT NULL DEFAULT 1,
                purchase_price REAL,
                replacement_value REAL,
                notes TEXT NOT NULL DEFAULT '',
                receipt_path TEXT,
                document_path TEXT,
                location_id INTEGER,
                status TEXT NOT NULL DEFAULT 'AVAILABLE',
                created_at INTEGER NOT NULL,
                updated_at INTEGER NOT NULL,
                FOREIGN KEY(location_id) REFERENCES locations(id)
            )"""
        )
        db.execSQL("CREATE INDEX items_name_idx ON items(name COLLATE NOCASE)")
        db.execSQL("CREATE INDEX locations_parent_idx ON locations(parent_id)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit

    fun locations(): List<InventoryLocation> {
        val result = mutableListOf<InventoryLocation>()
        readableDatabase.query("locations", null, null, null, null, null, "name COLLATE NOCASE").use { cursor ->
            while (cursor.moveToNext()) {
                result += InventoryLocation(
                    id = cursor.getLong(cursor.getColumnIndexOrThrow("id")),
                    parentId = cursor.nullableLong("parent_id"),
                    name = cursor.getString(cursor.getColumnIndexOrThrow("name")),
                    type = cursor.getString(cursor.getColumnIndexOrThrow("type"))
                )
            }
        }
        return result
    }

    fun addLocation(name: String, type: String, parentId: Long?): Long {
        val values = ContentValues().apply {
            put("name", name.trim())
            put("type", type)
            if (parentId == null) putNull("parent_id") else put("parent_id", parentId)
        }
        return writableDatabase.insertOrThrow("locations", null, values)
    }

    fun saveItem(item: InventoryItem): Long {
        val now = System.currentTimeMillis()
        val values = ContentValues().apply {
            put("item_type", item.itemType)
            put("name", item.name.trim())
            putNullable("photo_path", item.photoPath)
            put("category", item.category.trim())
            put("brand", item.brand.trim())
            put("model", item.model.trim())
            put("serial_number", item.serialNumber.trim())
            put("quantity", if (item.itemType == InventoryItem.TYPE_UNIQUE) 1 else item.quantity.coerceAtLeast(0))
            putNullableDouble("purchase_price", item.purchasePrice)
            putNullableDouble("replacement_value", item.replacementValue)
            put("notes", item.notes.trim())
            putNullable("receipt_path", item.receiptPath)
            putNullable("document_path", item.documentPath)
            if (item.locationId == null) putNull("location_id") else put("location_id", item.locationId)
            put("status", item.status)
            if (item.id == 0L) put("created_at", now)
            put("updated_at", now)
        }
        if (item.id == 0L) return writableDatabase.insertOrThrow("items", null, values)
        writableDatabase.update("items", values, "id = ?", arrayOf(item.id.toString()))
        return item.id
    }

    fun item(id: Long): InventoryItem? {
        readableDatabase.query("items", null, "id = ?", arrayOf(id.toString()), null, null, null).use { cursor ->
            if (!cursor.moveToFirst()) return null
            return cursor.toInventoryItem()
        }
    }

    fun items(): List<InventoryItem> {
        val result = mutableListOf<InventoryItem>()
        readableDatabase.query("items", null, null, null, null, null, "updated_at DESC, id DESC").use { cursor ->
            while (cursor.moveToNext()) result += cursor.toInventoryItem()
        }
        return result
    }

    fun setQuantity(id: Long, quantity: Int) {
        writableDatabase.update(
            "items",
            ContentValues().apply {
                put("quantity", quantity.coerceAtLeast(0))
                put("updated_at", System.currentTimeMillis())
            },
            "id = ? AND item_type = ?",
            arrayOf(id.toString(), InventoryItem.TYPE_QUANTITY)
        )
    }

    fun setStatus(id: Long, status: String) {
        writableDatabase.update(
            "items",
            ContentValues().apply {
                put("status", status)
                put("updated_at", System.currentTimeMillis())
            },
            "id = ? AND item_type = ?",
            arrayOf(id.toString(), InventoryItem.TYPE_UNIQUE)
        )
    }

    fun moveItem(id: Long, locationId: Long?) {
        writableDatabase.update(
            "items",
            ContentValues().apply {
                if (locationId == null) putNull("location_id") else put("location_id", locationId)
                put("updated_at", System.currentTimeMillis())
            },
            "id = ?",
            arrayOf(id.toString())
        )
    }

    fun deleteItem(id: Long) {
        writableDatabase.delete("items", "id = ?", arrayOf(id.toString()))
    }

    fun locationPath(locationId: Long?): String {
        if (locationId == null) return "No location"
        val byId = locations().associateBy { it.id }
        val names = mutableListOf<String>()
        var current = byId[locationId]
        var depth = 0
        while (current != null && depth < 32) {
            names += current.name
            current = current.parentId?.let(byId::get)
            depth++
        }
        return if (names.isEmpty()) "No location" else names.asReversed().joinToString(" → ")
    }

    fun searchItems(query: String): List<InventoryItem> {
        val normalized = query.trim().lowercase()
        if (normalized.isEmpty()) return items()
        val byId = locations().associateBy { it.id }
        return items().filter { item ->
            val path = locationPathFromMap(item.locationId, byId)
            listOf(item.name, item.brand, item.category, item.model, item.serialNumber, path)
                .any { it.lowercase().contains(normalized) }
        }
    }

    private fun locationPathFromMap(id: Long?, byId: Map<Long, InventoryLocation>): String {
        if (id == null) return ""
        val names = mutableListOf<String>()
        var current = byId[id]
        var depth = 0
        while (current != null && depth < 32) {
            names += current.name
            current = current.parentId?.let(byId::get)
            depth++
        }
        return names.asReversed().joinToString(" ")
    }

    private fun android.database.Cursor.toInventoryItem(): InventoryItem =
        InventoryItem(
            id = getLong(getColumnIndexOrThrow("id")),
            itemType = getString(getColumnIndexOrThrow("item_type")),
            name = getString(getColumnIndexOrThrow("name")),
            photoPath = getString(getColumnIndexOrThrow("photo_path")),
            category = getString(getColumnIndexOrThrow("category")),
            brand = getString(getColumnIndexOrThrow("brand")),
            model = getString(getColumnIndexOrThrow("model")),
            serialNumber = getString(getColumnIndexOrThrow("serial_number")),
            quantity = getInt(getColumnIndexOrThrow("quantity")),
            purchasePrice = nullableDouble("purchase_price"),
            replacementValue = nullableDouble("replacement_value"),
            notes = getString(getColumnIndexOrThrow("notes")),
            receiptPath = getString(getColumnIndexOrThrow("receipt_path")),
            documentPath = getString(getColumnIndexOrThrow("document_path")),
            locationId = nullableLong("location_id"),
            status = getString(getColumnIndexOrThrow("status")),
            createdAt = getLong(getColumnIndexOrThrow("created_at")),
            updatedAt = getLong(getColumnIndexOrThrow("updated_at"))
        )

    private fun android.database.Cursor.nullableLong(column: String): Long? {
        val index = getColumnIndexOrThrow(column)
        return if (isNull(index)) null else getLong(index)
    }

    private fun android.database.Cursor.nullableDouble(column: String): Double? {
        val index = getColumnIndexOrThrow(column)
        return if (isNull(index)) null else getDouble(index)
    }

    private fun ContentValues.putNullable(key: String, value: String?) {
        if (value == null) putNull(key) else put(key, value)
    }

    private fun ContentValues.putNullableDouble(key: String, value: Double?) {
        if (value == null) putNull(key) else put(key, value)
    }
}
