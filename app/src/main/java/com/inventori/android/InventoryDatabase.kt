package com.inventori.android

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

class InventoryDatabase(context: Context) :
    SQLiteOpenHelper(context, "inventori.db", null, 3) {

    override fun onCreate(db: SQLiteDatabase) {
        createWorkspaceTable(db)
        db.execSQL(
            """CREATE TABLE locations (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                workspace_id INTEGER NOT NULL,
                parent_id INTEGER,
                name TEXT NOT NULL,
                type TEXT NOT NULL,
                FOREIGN KEY(workspace_id) REFERENCES workspaces(id),
                FOREIGN KEY(parent_id) REFERENCES locations(id)
            )"""
        )
        db.execSQL(
            """CREATE TABLE items (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                workspace_id INTEGER NOT NULL,
                item_type TEXT NOT NULL,
                name TEXT NOT NULL,
                photo_path TEXT,
                category TEXT NOT NULL DEFAULT '',
                brand TEXT NOT NULL DEFAULT '',
                model TEXT NOT NULL DEFAULT '',
                serial_number TEXT NOT NULL DEFAULT '',
                identifier_type TEXT NOT NULL DEFAULT 'NONE',
                identifier_value TEXT NOT NULL DEFAULT '',
                quantity INTEGER NOT NULL DEFAULT 1,
                purchase_price REAL,
                replacement_value REAL,
                notes TEXT NOT NULL DEFAULT '',
                receipt_path TEXT,
                document_path TEXT,
                evidence_latitude REAL,
                evidence_longitude REAL,
                evidence_accuracy_meters REAL,
                evidence_captured_at INTEGER,
                location_id INTEGER,
                status TEXT NOT NULL DEFAULT 'AVAILABLE',
                created_at INTEGER NOT NULL,
                updated_at INTEGER NOT NULL,
                FOREIGN KEY(workspace_id) REFERENCES workspaces(id),
                FOREIGN KEY(location_id) REFERENCES locations(id)
            )"""
        )
        createIndexes(db)
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) {
            db.execSQL("ALTER TABLE items ADD COLUMN identifier_type TEXT NOT NULL DEFAULT 'NONE'")
            db.execSQL("ALTER TABLE items ADD COLUMN identifier_value TEXT NOT NULL DEFAULT ''")
            db.execSQL("ALTER TABLE items ADD COLUMN evidence_latitude REAL")
            db.execSQL("ALTER TABLE items ADD COLUMN evidence_longitude REAL")
            db.execSQL("ALTER TABLE items ADD COLUMN evidence_accuracy_meters REAL")
            db.execSQL("ALTER TABLE items ADD COLUMN evidence_captured_at INTEGER")
        }
        if (oldVersion < 3) {
            createWorkspaceTable(db)
            val values = ContentValues().apply {
                put("id", LEGACY_WORKSPACE_ID)
                put("name", "Imported Inventory")
                put("type", WORKSPACE_LEGACY)
                put("created_at", System.currentTimeMillis())
            }
            db.insertWithOnConflict("workspaces", null, values, SQLiteDatabase.CONFLICT_IGNORE)
            db.execSQL("ALTER TABLE locations ADD COLUMN workspace_id INTEGER NOT NULL DEFAULT $LEGACY_WORKSPACE_ID")
            db.execSQL("ALTER TABLE items ADD COLUMN workspace_id INTEGER NOT NULL DEFAULT $LEGACY_WORKSPACE_ID")
        }
        createIndexes(db)
    }

    private fun createWorkspaceTable(db: SQLiteDatabase) {
        db.execSQL(
            """CREATE TABLE IF NOT EXISTS workspaces (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                name TEXT NOT NULL,
                type TEXT NOT NULL,
                created_at INTEGER NOT NULL
            )"""
        )
    }

    private fun createIndexes(db: SQLiteDatabase) {
        db.execSQL("CREATE INDEX IF NOT EXISTS items_name_idx ON items(name COLLATE NOCASE)")
        db.execSQL("CREATE INDEX IF NOT EXISTS items_identifier_idx ON items(identifier_value COLLATE NOCASE)")
        db.execSQL("CREATE INDEX IF NOT EXISTS items_workspace_idx ON items(workspace_id)")
        db.execSQL("CREATE INDEX IF NOT EXISTS locations_parent_idx ON locations(parent_id)")
        db.execSQL("CREATE INDEX IF NOT EXISTS locations_workspace_idx ON locations(workspace_id)")
    }

    fun workspaces(): List<InventoryWorkspace> {
        val result = mutableListOf<InventoryWorkspace>()
        readableDatabase.query("workspaces", null, null, null, null, null, "created_at ASC, id ASC").use { cursor ->
            while (cursor.moveToNext()) {
                result += InventoryWorkspace(
                    id = cursor.getLong(cursor.getColumnIndexOrThrow("id")),
                    name = cursor.getString(cursor.getColumnIndexOrThrow("name")),
                    type = cursor.getString(cursor.getColumnIndexOrThrow("type")),
                    createdAt = cursor.getLong(cursor.getColumnIndexOrThrow("created_at"))
                )
            }
        }
        return result
    }

    fun workspace(id: Long): InventoryWorkspace? {
        readableDatabase.query("workspaces", null, "id = ?", arrayOf(id.toString()), null, null, null).use { cursor ->
            if (!cursor.moveToFirst()) return null
            return InventoryWorkspace(
                id = cursor.getLong(cursor.getColumnIndexOrThrow("id")),
                name = cursor.getString(cursor.getColumnIndexOrThrow("name")),
                type = cursor.getString(cursor.getColumnIndexOrThrow("type")),
                createdAt = cursor.getLong(cursor.getColumnIndexOrThrow("created_at"))
            )
        }
    }

    fun createWorkspace(name: String, type: String): Long {
        val values = ContentValues().apply {
            put("name", name.trim().ifBlank { "My Inventory" })
            put("type", type)
            put("created_at", System.currentTimeMillis())
        }
        return writableDatabase.insertOrThrow("workspaces", null, values)
    }

    fun adoptLegacyWorkspace(type: String, name: String): Long? {
        val legacy = workspaces().firstOrNull { it.type == WORKSPACE_LEGACY } ?: return null
        writableDatabase.update(
            "workspaces",
            ContentValues().apply {
                put("type", type)
                put("name", name.trim().ifBlank { "My Inventory" })
            },
            "id = ?",
            arrayOf(legacy.id.toString())
        )
        return legacy.id
    }

    fun defaultWorkspaceId(): Long {
        return workspaces().firstOrNull()?.id ?: createWorkspace("My Home", "home")
    }

    fun locations(workspaceId: Long): List<InventoryLocation> {
        val result = mutableListOf<InventoryLocation>()
        readableDatabase.query(
            "locations",
            null,
            "workspace_id = ?",
            arrayOf(workspaceId.toString()),
            null,
            null,
            "name COLLATE NOCASE"
        ).use { cursor ->
            while (cursor.moveToNext()) result += cursor.toInventoryLocation()
        }
        return result
    }

    fun locations(): List<InventoryLocation> {
        val result = mutableListOf<InventoryLocation>()
        readableDatabase.query("locations", null, null, null, null, null, "name COLLATE NOCASE").use { cursor ->
            while (cursor.moveToNext()) result += cursor.toInventoryLocation()
        }
        return result
    }

    fun addLocation(workspaceId: Long, name: String, type: String, parentId: Long?): Long {
        require(workspace(workspaceId) != null) { "Workspace does not exist" }
        if (parentId != null) {
            val parent = location(parentId) ?: error("Parent location does not exist")
            require(parent.workspaceId == workspaceId) { "Parent location belongs to another workspace" }
        }
        val values = ContentValues().apply {
            put("workspace_id", workspaceId)
            put("name", name.trim())
            put("type", type)
            if (parentId == null) putNull("parent_id") else put("parent_id", parentId)
        }
        return writableDatabase.insertOrThrow("locations", null, values)
    }

    fun addLocation(name: String, type: String, parentId: Long?): Long =
        addLocation(defaultWorkspaceId(), name, type, parentId)

    fun location(id: Long): InventoryLocation? {
        readableDatabase.query("locations", null, "id = ?", arrayOf(id.toString()), null, null, null).use { cursor ->
            if (!cursor.moveToFirst()) return null
            return cursor.toInventoryLocation()
        }
    }

    fun saveItem(item: InventoryItem): Long {
        val now = System.currentTimeMillis()
        val workspaceId = when {
            item.workspaceId > 0 -> item.workspaceId
            item.locationId != null -> location(item.locationId)?.workspaceId ?: defaultWorkspaceId()
            else -> defaultWorkspaceId()
        }
        require(workspace(workspaceId) != null) { "Workspace does not exist" }
        if (item.locationId != null) {
            val location = location(item.locationId) ?: error("Location does not exist")
            require(location.workspaceId == workspaceId) { "Location belongs to another workspace" }
        }
        val values = ContentValues().apply {
            put("workspace_id", workspaceId)
            put("item_type", item.itemType)
            put("name", item.name.trim())
            putNullable("photo_path", item.photoPath)
            put("category", item.category.trim())
            put("brand", item.brand.trim())
            put("model", item.model.trim())
            put("serial_number", item.serialNumber.trim())
            put("identifier_type", item.identifierType)
            put("identifier_value", item.identifierValue.trim())
            put("quantity", if (item.itemType == InventoryItem.TYPE_UNIQUE) 1 else item.quantity.coerceAtLeast(0))
            putNullableDouble("purchase_price", item.purchasePrice)
            putNullableDouble("replacement_value", item.replacementValue)
            put("notes", item.notes.trim())
            putNullable("receipt_path", item.receiptPath)
            putNullable("document_path", item.documentPath)
            putNullableDouble("evidence_latitude", item.evidenceLatitude)
            putNullableDouble("evidence_longitude", item.evidenceLongitude)
            putNullableFloat("evidence_accuracy_meters", item.evidenceAccuracyMeters)
            if (item.evidenceCapturedAt == null) putNull("evidence_captured_at") else put("evidence_captured_at", item.evidenceCapturedAt)
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

    fun items(workspaceId: Long): List<InventoryItem> {
        val result = mutableListOf<InventoryItem>()
        readableDatabase.query(
            "items",
            null,
            "workspace_id = ?",
            arrayOf(workspaceId.toString()),
            null,
            null,
            "updated_at DESC, id DESC"
        ).use { cursor ->
            while (cursor.moveToNext()) result += cursor.toInventoryItem()
        }
        return result
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
        val item = item(id) ?: return
        if (locationId != null) {
            val location = location(locationId) ?: error("Location does not exist")
            require(location.workspaceId == item.workspaceId) { "Cannot move an item into another workspace's location" }
        }
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
        val location = location(locationId) ?: return "No location"
        val byId = locations(location.workspaceId).associateBy { it.id }
        val names = mutableListOf<String>()
        var current: InventoryLocation? = location
        var depth = 0
        while (current != null && depth < 32) {
            names += current.name
            current = current.parentId?.let(byId::get)
            depth++
        }
        return names.asReversed().joinToString(" → ").ifBlank { "No location" }
    }

    fun searchItems(workspaceId: Long, query: String): List<InventoryItem> {
        val normalized = query.trim().lowercase()
        if (normalized.isEmpty()) return items(workspaceId)
        val byId = locations(workspaceId).associateBy { it.id }
        return items(workspaceId).filter { item ->
            val path = locationPathFromMap(item.locationId, byId)
            listOf(item.name, item.brand, item.category, item.model, item.serialNumber, item.identifierType, item.identifierValue, path)
                .any { it.lowercase().contains(normalized) }
        }
    }

    fun searchItems(query: String): List<InventoryItem> {
        val normalized = query.trim().lowercase()
        if (normalized.isEmpty()) return items()
        return items().filter { item ->
            val path = locationPath(item.locationId)
            listOf(item.name, item.brand, item.category, item.model, item.serialNumber, item.identifierType, item.identifierValue, path)
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

    private fun android.database.Cursor.toInventoryLocation(): InventoryLocation =
        InventoryLocation(
            id = getLong(getColumnIndexOrThrow("id")),
            parentId = nullableLong("parent_id"),
            name = getString(getColumnIndexOrThrow("name")),
            type = getString(getColumnIndexOrThrow("type")),
            workspaceId = getLong(getColumnIndexOrThrow("workspace_id"))
        )

    private fun android.database.Cursor.toInventoryItem(): InventoryItem =
        InventoryItem(
            id = getLong(getColumnIndexOrThrow("id")),
            workspaceId = getLong(getColumnIndexOrThrow("workspace_id")),
            itemType = getString(getColumnIndexOrThrow("item_type")),
            name = getString(getColumnIndexOrThrow("name")),
            photoPath = getString(getColumnIndexOrThrow("photo_path")),
            category = getString(getColumnIndexOrThrow("category")),
            brand = getString(getColumnIndexOrThrow("brand")),
            model = getString(getColumnIndexOrThrow("model")),
            serialNumber = getString(getColumnIndexOrThrow("serial_number")),
            identifierType = getString(getColumnIndexOrThrow("identifier_type")),
            identifierValue = getString(getColumnIndexOrThrow("identifier_value")),
            quantity = getInt(getColumnIndexOrThrow("quantity")),
            purchasePrice = nullableDouble("purchase_price"),
            replacementValue = nullableDouble("replacement_value"),
            notes = getString(getColumnIndexOrThrow("notes")),
            receiptPath = getString(getColumnIndexOrThrow("receipt_path")),
            documentPath = getString(getColumnIndexOrThrow("document_path")),
            evidenceLatitude = nullableDouble("evidence_latitude"),
            evidenceLongitude = nullableDouble("evidence_longitude"),
            evidenceAccuracyMeters = nullableFloat("evidence_accuracy_meters"),
            evidenceCapturedAt = nullableLong("evidence_captured_at"),
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

    private fun android.database.Cursor.nullableFloat(column: String): Float? {
        val index = getColumnIndexOrThrow(column)
        return if (isNull(index)) null else getFloat(index)
    }

    private fun ContentValues.putNullable(key: String, value: String?) {
        if (value == null) putNull(key) else put(key, value)
    }

    private fun ContentValues.putNullableDouble(key: String, value: Double?) {
        if (value == null) putNull(key) else put(key, value)
    }

    private fun ContentValues.putNullableFloat(key: String, value: Float?) {
        if (value == null) putNull(key) else put(key, value)
    }

    companion object {
        private const val LEGACY_WORKSPACE_ID = 1L
        const val WORKSPACE_LEGACY = "legacy"
    }
}
