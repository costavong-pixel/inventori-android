package com.inventori.android

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.FileProvider
import java.io.File
import java.io.FileOutputStream
import java.text.NumberFormat

class MainActivity : Activity() {
    private lateinit var database: InventoryDatabase
    private val preferences by lazy { getSharedPreferences("inventori", MODE_PRIVATE) }
    private val aiRecognizer: InventoryAiRecognizer = MockInventoryAiRecognizer()
    private var selectedMode = MODE_HOME
    private var formPhotoPath: String? = null
    private var formReceiptPath: String? = null
    private var formDocumentPath: String? = null
    private var formPhotoStatus: TextView? = null
    private var receiptStatus: TextView? = null
    private var documentStatus: TextView? = null
    private var cameraOutputPath: String? = null
    private var pendingAiCount = 0
    private var detectedItems = mutableListOf<InventoryItem>()
    private lateinit var detectedLocationSpinner: Spinner
    private var formExistingItem: InventoryItem? = null
    private lateinit var formName: EditText
    private lateinit var formCategory: EditText
    private lateinit var formBrand: EditText
    private lateinit var formModel: EditText
    private lateinit var formSerial: EditText
    private lateinit var formQuantity: EditText
    private lateinit var formPurchase: EditText
    private lateinit var formReplacement: EditText
    private lateinit var formNotes: EditText
    private lateinit var formType: Spinner
    private lateinit var formLocation: Spinner

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        database = InventoryDatabase(this)
        selectedMode = preferences.getString(KEY_MODE, MODE_HOME) ?: MODE_HOME
        if (preferences.getBoolean(KEY_ONBOARDED, false)) showHome() else showOnboarding()
    }

    override fun onDestroy() {
        if (::database.isInitialized) database.close()
        super.onDestroy()
    }

    private fun showOnboarding() {
        val body = screen("Inventori", null)
        body.addView(spacer(18))
        val hero = card()
        hero.addView(label("Know what you own.", 27, COLOR_INK, true))
        hero.addView(spacer(8))
        hero.addView(label("Keep a local record for your home, move, or small business. Add photos, locations, values, and documents.", 16, COLOR_MUTED))
        body.addView(hero)
        body.addView(spacer(18))
        body.addView(label("Choose a starting mode", 18, COLOR_INK, true))
        body.addView(spacer(8))
        val modes = listOf(
            MODE_HOME to "Home Inventory",
            MODE_MOVING to "Moving / Packing",
            MODE_BUSINESS to "Small Business"
        )
        modes.forEach { (mode, title) ->
            val choice = button(title + if (selectedMode == mode) "  ✓" else "", primary = selectedMode == mode) {
                selectedMode = mode
                showOnboarding()
            }
            body.addView(choice, matchWrap())
        }
        body.addView(spacer(8))
        body.addView(label("Works offline. No account needed. AI preview uses a local mock and asks you to confirm before saving.", 13, COLOR_MUTED))
        body.addView(spacer(20))
        body.addView(button("Continue", R.id.onboarding_continue, true) {
            preferences.edit().putBoolean(KEY_ONBOARDED, true).putString(KEY_MODE, selectedMode).apply()
            showHome()
        }, matchWrap())
    }

    private fun showHome(searchQuery: String = "") {
        val modeTitle = modeTitle(selectedMode)
        val body = screen(modeTitle, null)
        val summary = card()
        val allItems = database.items()
        summary.addView(label("One simple inventory", 19, COLOR_INK, true))
        summary.addView(spacer(4))
        summary.addView(label(
            allItems.size.toString() + (if (allItems.size == 1) " item" else " items") +
                "  ·  Replacement estimates " + money(allItems.mapNotNull { it.replacementValue }.sum()),
            14,
            COLOR_MUTED
        ))
        body.addView(summary, matchWrap())
        body.addView(spacer(10))

        val modeRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        listOf(MODE_HOME, MODE_MOVING, MODE_BUSINESS).forEach { mode ->
            val modeButton = button(modeShortTitle(mode), primary = selectedMode == mode) {
                selectedMode = mode
                preferences.edit().putString(KEY_MODE, mode).apply()
                showHome(searchQuery)
            }
            modeRow.addView(modeButton, LinearLayout.LayoutParams(0, dp(48), 1f).apply {
                marginEnd = dp(4)
            })
        }
        body.addView(modeRow)
        body.addView(spacer(8))

        val search = EditText(this).apply {
            id = R.id.home_search
            hint = "Search name, brand, model, serial, location…"
            isSingleLine = true
            imeOptions = EditorInfo.IME_ACTION_SEARCH
            setText(searchQuery)
            setPadding(dp(14), dp(12), dp(14), dp(12))
            setBackground(rounded(COLOR_WHITE, COLOR_BORDER, 12))
        }
        body.addView(search, matchWrap())
        body.addView(spacer(8))

        val actions = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        actions.addView(button("＋ Add item", R.id.home_add_item, true) {
            showItemForm(null)
        }, LinearLayout.LayoutParams(0, dp(48), 1f).apply { marginEnd = dp(5) })
        actions.addView(button("Locations", R.id.home_locations) {
            showLocations()
        }, LinearLayout.LayoutParams(0, dp(48), 1f).apply { marginStart = dp(5) })
        body.addView(actions)
        body.addView(spacer(8))
        val tools = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        tools.addView(button("AI draft · 1", R.id.ai_one) { chooseAiInput(1) },
            LinearLayout.LayoutParams(0, dp(44), 1f).apply { marginEnd = dp(5) })
        tools.addView(button("AI draft · multiple", R.id.ai_multiple) { chooseAiInput(3) },
            LinearLayout.LayoutParams(0, dp(44), 1f).apply { marginStart = dp(5) })
        body.addView(tools)
        body.addView(spacer(8))
        body.addView(button("Export inventory PDF", R.id.home_export_pdf) { requestPdfExport() }, matchWrap())
        body.addView(spacer(6))
        body.addView(label("PDF values are user-entered estimates and do not guarantee an insurance claim.", 12, COLOR_MUTED))
        body.addView(spacer(14))
        body.addView(label("Your items", 20, COLOR_INK, true))
        body.addView(spacer(4))
        val results = LinearLayout(this).apply {
            id = R.id.home_items
            orientation = LinearLayout.VERTICAL
        }
        body.addView(results, matchWrap())
        renderItems(results, database.searchItems(searchQuery), searchQuery)
        search.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                val query = s?.toString().orEmpty()
                renderItems(results, database.searchItems(query), query)
            }
            override fun afterTextChanged(s: Editable?) = Unit
        })
    }

    private fun renderItems(container: LinearLayout, items: List<InventoryItem>, query: String) {
        container.removeAllViews()
        if (items.isEmpty()) {
            val empty = card()
            empty.addView(label("No items yet", 17, COLOR_INK, true))
            empty.addView(label("Add an item manually or try the mock AI draft to review a suggested item.", 14, COLOR_MUTED))
            container.addView(empty, matchWrap())
            return
        }
        items.forEach { item ->
            val itemCard = card()
            val titleRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
            val title = label(item.name, 18, COLOR_INK, true)
            titleRow.addView(title, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            val typeLabel = if (item.itemType == InventoryItem.TYPE_UNIQUE) "UNIQUE" else "QTY " + item.quantity
            titleRow.addView(label(typeLabel, 11, COLOR_GREEN, true))
            itemCard.addView(titleRow)
            itemCard.addView(spacer(4))
            val location = database.locationPath(item.locationId)
            itemCard.addView(label(location, 14, COLOR_GREEN, true))
            val details = listOf(item.category, item.brand, item.model, item.serialNumber)
                .filter { it.isNotBlank() }.joinToString(" · ")
            if (details.isNotBlank()) itemCard.addView(label(details, 13, COLOR_MUTED))
            val valueLine = buildString {
                if (item.replacementValue != null) append("Est. replacement " + money(item.replacementValue))
                else if (item.purchasePrice != null) append("Purchase " + money(item.purchasePrice))
                if (item.itemType == InventoryItem.TYPE_UNIQUE) {
                    if (isNotEmpty()) append(" · ")
                    append(statusTitle(item.status))
                }
            }
            if (valueLine.isNotBlank()) itemCard.addView(label(valueLine, 13, COLOR_MUTED))
            val actionRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            if (item.itemType == InventoryItem.TYPE_QUANTITY) {
                actionRow.addView(button("−1") { database.setQuantity(item.id, item.quantity - 1); showHome(query) },
                    LinearLayout.LayoutParams(0, dp(42), 1f).apply { marginEnd = dp(4) })
                actionRow.addView(button("+1") { database.setQuantity(item.id, item.quantity + 1); showHome(query) },
                    LinearLayout.LayoutParams(0, dp(42), 1f).apply { marginEnd = dp(4) })
            }
            actionRow.addView(button("Details") { showItemDetail(item.id) },
                LinearLayout.LayoutParams(0, dp(42), 1f))
            itemCard.addView(spacer(4))
            itemCard.addView(actionRow, matchWrap())
            container.addView(itemCard, matchWrap())
            container.addView(spacer(8))
        }
    }

    private fun showLocations() {
        val body = screen("Locations", { showHome() })
        body.addView(label("Build a place tree that makes storage easy to find.", 15, COLOR_MUTED))
        body.addView(spacer(10))
        body.addView(button("＋ Add location", R.id.locations_add_button, true) { showAddLocationDialog() }, matchWrap())
        body.addView(spacer(12))
        val locations = database.locations().sortedBy { database.locationPath(it.id).lowercase() }
        if (locations.isEmpty()) {
            val empty = card()
            empty.addView(label("No locations yet", 17, COLOR_INK, true))
            empty.addView(label("Create a property or business, then add rooms, storage areas, shelves, and boxes.", 14, COLOR_MUTED))
            body.addView(empty, matchWrap())
        } else {
            locations.forEach { location ->
                val node = card()
                node.addView(label(database.locationPath(location.id), 16, COLOR_INK, true))
                node.addView(label(locationTypeTitle(location.type), 13, COLOR_MUTED))
                body.addView(node, matchWrap())
                body.addView(spacer(6))
            }
        }
    }

    private fun showAddLocationDialog() {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(22), dp(12), dp(22), dp(2))
        }
        val name = EditText(this).apply {
            id = R.id.location_name_input
            hint = "Location name (e.g. Home, Basement, Box 4)"
            isSingleLine = true
        }
        box.addView(name, matchWrap())
        box.addView(spacer(8))
        box.addView(label("Type", 13, COLOR_MUTED, true))
        val typeNames = listOf("Property / business", "Room", "Storage area", "Shelf", "Box / bin", "Business location")
        val typeValues = listOf("property", "room", "storage", "shelf", "box", "business")
        val typeSpinner = spinner(typeNames).apply { id = R.id.location_type_spinner }
        box.addView(typeSpinner, matchWrap())
        box.addView(spacer(8))
        box.addView(label("Inside", 13, COLOR_MUTED, true))
        val parents = listOf<InventoryLocation?>(null) + database.locations()
        val parentNames = listOf("No parent (top level)") + parents.drop(1).map {
            database.locationPath(it!!.id)
        }
        val parentSpinner = spinner(parentNames).apply { id = R.id.location_parent_spinner }
        box.addView(parentSpinner, matchWrap())
        val dialog = AlertDialog.Builder(this)
            .setTitle("New location")
            .setView(box)
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Create", null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).apply {
                id = R.id.location_save_button
                setOnClickListener {
                val locationName = name.text.toString().trim()
                if (locationName.isEmpty()) {
                    name.error = "Enter a name"
                    return@setOnClickListener
                }
                val type = typeValues[typeSpinner.selectedItemPosition]
                val parent = parents[parentSpinner.selectedItemPosition]
                database.addLocation(locationName, type, parent?.id)
                dialog.dismiss()
                showLocations()
                }
            }
        }
        dialog.show()
    }

    private fun showItemForm(existing: InventoryItem?) {
        formExistingItem = existing
        formPhotoPath = existing?.photoPath
        formReceiptPath = existing?.receiptPath
        formDocumentPath = existing?.documentPath
        val body = screen(if (existing == null) "Add item" else "Edit item", { showHome() })
        body.addView(label("Enter what you know. Price, replacement value, receipt, and document are optional.", 14, COLOR_MUTED))
        body.addView(spacer(10))
        body.addView(label("Item type", 13, COLOR_MUTED, true))
        formType = spinner(listOf("Unique item", "Quantity item")).apply { id = R.id.item_type_spinner }
        formType.setSelection(if (existing?.itemType == InventoryItem.TYPE_QUANTITY) 1 else 0)
        body.addView(formType, matchWrap())
        formName = addField(body, "Name", R.id.item_name, "e.g. Cordless drill", existing?.name.orEmpty())
        formCategory = addField(body, "Category", R.id.item_category, "Tools, electronics, furniture…", existing?.category.orEmpty())
        formBrand = addField(body, "Brand", R.id.item_brand, "Brand", existing?.brand.orEmpty())
        formModel = addField(body, "Model", R.id.item_model, "Model", existing?.model.orEmpty())
        formSerial = addField(body, "Serial number", R.id.item_serial, "Serial number", existing?.serialNumber.orEmpty())
        formQuantity = addField(body, "Quantity", R.id.item_quantity, "1", (existing?.quantity ?: 1).toString(), NUMBER_INPUT)
        formPurchase = addField(body, "Purchase price", R.id.item_purchase_price, "Optional", existing?.purchasePrice?.toString().orEmpty(), DECIMAL_INPUT)
        formReplacement = addField(body, "Estimated replacement value", R.id.item_replacement_value, "Your estimate · optional", existing?.replacementValue?.toString().orEmpty(), DECIMAL_INPUT)
        body.addView(label("Location", 13, COLOR_MUTED, true))
        val locations = locationOptions()
        formLocation = spinner(locations.map { it.second }).apply { id = R.id.item_location }
        val selectedLocationIndex = locations.indexOfFirst { it.first == existing?.locationId }.let { if (it < 0) 0 else it }
        formLocation.setSelection(selectedLocationIndex)
        body.addView(formLocation, matchWrap())
        formNotes = addField(body, "Notes", R.id.item_notes, "Condition, packing instructions, or other details", existing?.notes.orEmpty(), multiline = true)
        body.addView(spacer(8))
        val photoCard = card()
        photoCard.addView(label("Item photo", 16, COLOR_INK, true))
        formPhotoStatus = label(if (formPhotoPath.isNullOrBlank()) "No photo attached" else "Photo attached", 13, COLOR_MUTED)
        photoCard.addView(formPhotoStatus)
        val photoActions = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        photoActions.addView(button("Take photo", R.id.item_take_photo) { startCamera(REQUEST_ITEM_CAMERA) },
            LinearLayout.LayoutParams(0, dp(44), 1f).apply { marginEnd = dp(4) })
        photoActions.addView(button("Gallery", R.id.item_pick_photo) { startGallery(REQUEST_ITEM_GALLERY) },
            LinearLayout.LayoutParams(0, dp(44), 1f).apply { marginStart = dp(4) })
        photoCard.addView(photoActions, matchWrap())
        body.addView(photoCard, matchWrap())
        body.addView(spacer(8))
        val attachments = card()
        attachments.addView(label("Documents", 16, COLOR_INK, true))
        receiptStatus = label(if (formReceiptPath == null) "Receipt: not attached" else "Receipt: attached", 13, COLOR_MUTED)
        documentStatus = label(if (formDocumentPath == null) "Appraisal / document: not attached" else "Appraisal / document: attached", 13, COLOR_MUTED)
        attachments.addView(receiptStatus)
        attachments.addView(button("Attach receipt", R.id.item_pick_receipt) { startDocumentPicker(REQUEST_RECEIPT) }, matchWrap())
        attachments.addView(documentStatus)
        attachments.addView(button("Attach appraisal / document", R.id.item_pick_document) { startDocumentPicker(REQUEST_DOCUMENT) }, matchWrap())
        body.addView(attachments, matchWrap())
        body.addView(spacer(12))
        body.addView(button(if (existing == null) "Save item" else "Save changes", R.id.item_save, true) {
            saveFormItem()
        }, matchWrap())
    }

    private fun saveFormItem() {
        val name = formName.text.toString().trim()
        if (name.isEmpty()) {
            formName.error = "Name is required"
            formName.requestFocus()
            return
        }
        val type = if (formType.selectedItemPosition == 1) InventoryItem.TYPE_QUANTITY else InventoryItem.TYPE_UNIQUE
        val quantity = formQuantity.text.toString().toIntOrNull()
        if (type == InventoryItem.TYPE_QUANTITY && (quantity == null || quantity < 0)) {
            formQuantity.error = "Enter a quantity of 0 or more"
            return
        }
        val purchase = parsePrice(formPurchase.text.toString())
        val replacement = parsePrice(formReplacement.text.toString())
        if (formPurchase.text.isNotBlank() && purchase == null) {
            formPurchase.error = "Enter a valid amount"
            return
        }
        if (formReplacement.text.isNotBlank() && replacement == null) {
            formReplacement.error = "Enter a valid amount"
            return
        }
        val locations = locationOptions()
        val location = locations.getOrNull(formLocation.selectedItemPosition)?.first
        val base = formExistingItem ?: InventoryItem()
        val item = base.copy(
            itemType = type,
            name = name,
            photoPath = formPhotoPath,
            category = formCategory.text.toString(),
            brand = formBrand.text.toString(),
            model = formModel.text.toString(),
            serialNumber = formSerial.text.toString(),
            quantity = if (type == InventoryItem.TYPE_UNIQUE) 1 else quantity ?: 0,
            purchasePrice = purchase,
            replacementValue = replacement,
            notes = formNotes.text.toString(),
            receiptPath = formReceiptPath,
            documentPath = formDocumentPath,
            locationId = location,
            status = if (type == InventoryItem.TYPE_QUANTITY) InventoryItem.STATUS_AVAILABLE else base.status
        )
        database.saveItem(item)
        toast(if (item.id == 0L) "Item saved" else "Changes saved")
        showHome()
    }

    private fun showItemDetail(id: Long) {
        val item = database.item(id) ?: return showHome()
        val body = screen(item.name, { showHome() })
        if (!item.photoPath.isNullOrBlank()) {
            val image = ImageView(this).apply {
                adjustViewBounds = true
                scaleType = ImageView.ScaleType.CENTER_CROP
                setImageBitmap(BitmapFactory.decodeFile(item.photoPath))
                setBackground(rounded(COLOR_WHITE, COLOR_BORDER, 14))
            }
            body.addView(image, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(220)))
            body.addView(spacer(8))
        }
        val info = card()
        info.addView(label(if (item.itemType == InventoryItem.TYPE_UNIQUE) "Unique item" else "Quantity item · " + item.quantity + " units", 16, COLOR_GREEN, true))
        info.addView(label("Location: " + database.locationPath(item.locationId), 15, COLOR_INK, true))
        listOf(
            "Category" to item.category,
            "Brand" to item.brand,
            "Model" to item.model,
            "Serial number" to item.serialNumber,
            "Purchase price" to item.purchasePrice?.let(::money).orEmpty(),
            "Estimated replacement" to item.replacementValue?.let(::money).orEmpty(),
            "Notes" to item.notes,
            "Receipt" to if (item.receiptPath.isNullOrBlank()) "Not attached" else "Attached",
            "Appraisal / document" to if (item.documentPath.isNullOrBlank()) "Not attached" else "Attached",
            "Status" to statusTitle(item.status),
            "Created" to dateText(item.createdAt),
            "Updated" to dateText(item.updatedAt)
        ).filter { it.second.isNotBlank() }.forEach { (field, value) ->
            info.addView(label(field, 12, COLOR_MUTED, true))
            info.addView(label(value, 15, COLOR_INK))
            info.addView(spacer(4))
        }
        body.addView(info, matchWrap())
        body.addView(spacer(8))
        if (item.itemType == InventoryItem.TYPE_UNIQUE) {
            when (item.status) {
                InventoryItem.STATUS_CHECKED_OUT -> body.addView(button("Mark returned", primary = true) {
                    database.setStatus(id, InventoryItem.STATUS_RETURNED)
                    showItemDetail(id)
                }, matchWrap())
                else -> body.addView(button("Check out", primary = true) {
                    database.setStatus(id, InventoryItem.STATUS_CHECKED_OUT)
                    showItemDetail(id)
                }, matchWrap())
            }
        } else {
            val qty = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            qty.addView(button("−1") { database.setQuantity(id, item.quantity - 1); showItemDetail(id) },
                LinearLayout.LayoutParams(0, dp(48), 1f).apply { marginEnd = dp(5) })
            qty.addView(button("+1") { database.setQuantity(id, item.quantity + 1); showItemDetail(id) },
                LinearLayout.LayoutParams(0, dp(48), 1f).apply { marginStart = dp(5) })
            body.addView(qty)
            body.addView(button("Edit quantity / item", primary = true) { showItemForm(item) }, matchWrap())
        }
        body.addView(button("Move location") { showMoveDialog(item) }, matchWrap())
        body.addView(button("Edit item") { showItemForm(item) }, matchWrap())
        body.addView(button("Delete item") {
            AlertDialog.Builder(this)
                .setTitle("Delete item?")
                .setMessage("This removes the item record from this device.")
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Delete") { _, _ -> database.deleteItem(id); showHome() }
                .show()
        }, matchWrap())
    }

    private fun showMoveDialog(item: InventoryItem) {
        val options = locationOptions()
        val spinner = spinner(options.map { it.second })
        val index = options.indexOfFirst { it.first == item.locationId }
        spinner.setSelection(if (index < 0) 0 else index)
        AlertDialog.Builder(this)
            .setTitle("Move to location")
            .setView(spinner)
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Move") { _, _ ->
                database.moveItem(item.id, options.getOrNull(spinner.selectedItemPosition)?.first)
                showItemDetail(item.id)
            }
            .show()
    }

    private fun chooseAiInput(count: Int) {
        pendingAiCount = count
        AlertDialog.Builder(this)
            .setTitle("AI item draft")
            .setMessage("The V1 local mock demonstrates the confirmation flow. It does not send photos to an AI service.")
            .setItems(arrayOf("Take a photo", "Choose from gallery", "Use mock sample")) { _, choice ->
                when (choice) {
                    0 -> startCamera(REQUEST_AI_CAMERA)
                    1 -> startGallery(REQUEST_AI_GALLERY)
                    else -> runAiRecognition(null, count)
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun runAiRecognition(photoPath: String?, count: Int) {
        detectedItems = aiRecognizer.recognize(photoPath, count).toMutableList()
        showAiConfirmation()
    }

    private fun showAiConfirmation() {
        val body = screen("Review AI draft", { showHome() })
        body.addView(label("Mock preview · check every field before saving. You can edit, remove, or add detected items.", 14, COLOR_MUTED))
        body.addView(spacer(10))
        detectedItems.forEachIndexed { index, item ->
            val draft = card()
            draft.addView(label(item.name.ifBlank { "Unnamed item" }, 17, COLOR_INK, true))
            draft.addView(label(listOf(item.category, item.brand, item.model).filter { it.isNotBlank() }.joinToString(" · "), 13, COLOR_MUTED))
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            row.addView(button("Edit") {
                editDetectedItem(index)
            }.apply { if (index == 0) id = R.id.ai_draft_edit_0 },
                LinearLayout.LayoutParams(0, dp(42), 1f).apply { marginEnd = dp(5) })
            row.addView(button("Remove") {
                detectedItems.removeAt(index)
                showAiConfirmation()
            }, LinearLayout.LayoutParams(0, dp(42), 1f).apply { marginStart = dp(5) })
            draft.addView(row, matchWrap())
            body.addView(draft, matchWrap())
            body.addView(spacer(6))
        }
        body.addView(button("＋ Add another item") {
            detectedItems.add(InventoryItem(name = "", category = "", photoPath = detectedItems.firstOrNull()?.photoPath))
            showAiConfirmation()
        }, matchWrap())
        body.addView(spacer(10))
        body.addView(label("Save location", 13, COLOR_MUTED, true))
        val locations = locationOptions()
        detectedLocationSpinner = spinner(locations.map { it.second })
        body.addView(detectedLocationSpinner, matchWrap())
        body.addView(spacer(10))
        body.addView(button("Confirm and save " + detectedItems.size + " item(s)", R.id.ai_confirm_save, true) {
            val location = locations.getOrNull(detectedLocationSpinner.selectedItemPosition)?.first
            detectedItems.filter { it.name.isNotBlank() }.forEach { draft ->
                database.saveItem(draft.copy(locationId = location))
            }
            toast("Confirmed items saved")
            showHome()
        }, matchWrap())
    }

    private fun editDetectedItem(index: Int) {
        val item = detectedItems.getOrNull(index) ?: return
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(8), dp(20), 0) }
        fun dialogField(hint: String, value: String, viewId: Int): EditText = EditText(this).apply {
            id = viewId
            this.hint = hint
            setText(value)
            isSingleLine = true
        }.also { box.addView(it, matchWrap()) }
        val name = dialogField("Name", item.name, R.id.ai_edit_name)
        val category = dialogField("Category", item.category, R.id.ai_edit_category)
        val brand = dialogField("Brand", item.brand, R.id.ai_edit_brand)
        val model = dialogField("Model", item.model, R.id.ai_edit_model)
        val serial = dialogField("Serial number", item.serialNumber, R.id.ai_edit_serial)
        val quantity = dialogField("Quantity", item.quantity.toString(), R.id.ai_edit_quantity)
        val purchase = dialogField("Purchase price", item.purchasePrice?.toString().orEmpty(), R.id.ai_edit_purchase)
        val replacement = dialogField("Estimated replacement value", item.replacementValue?.toString().orEmpty(), R.id.ai_edit_replacement)
        val notes = dialogField("Notes", item.notes, R.id.ai_edit_notes)
        AlertDialog.Builder(this)
            .setTitle("Edit detected item")
            .setView(box)
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Apply") { _, _ ->
                detectedItems[index] = item.copy(
                    name = name.text.toString().trim(),
                    category = category.text.toString().trim(),
                    brand = brand.text.toString().trim(),
                    model = model.text.toString().trim(),
                    serialNumber = serial.text.toString().trim(),
                    quantity = quantity.text.toString().toIntOrNull()?.coerceAtLeast(0) ?: 1,
                    purchasePrice = parsePrice(purchase.text.toString()),
                    replacementValue = parsePrice(replacement.text.toString()),
                    notes = notes.text.toString()
                )
                showAiConfirmation()
            }
            .show()
    }

    private fun requestPdfExport() {
        val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "application/pdf"
            putExtra(Intent.EXTRA_TITLE, "Inventori-Inventory.pdf")
        }
        startActivityForResult(intent, REQUEST_EXPORT_PDF)
    }

    private fun startCamera(requestCode: Int) {
        val intent = Intent(MediaStore.ACTION_IMAGE_CAPTURE)
        val directory = File(filesDir, "media")
        directory.mkdirs()
        val file = File(directory, "camera-" + System.currentTimeMillis() + ".jpg")
        val uri = FileProvider.getUriForFile(this, "com.inventori.android.fileprovider", file)
        cameraOutputPath = file.absolutePath
        intent.putExtra(MediaStore.EXTRA_OUTPUT, uri)
        intent.addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_READ_URI_PERMISSION)
        runCatching { startActivityForResult(intent, requestCode) }
            .onFailure { toast("No camera app is available") }
    }

    private fun startGallery(requestCode: Int) {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "image/*"
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivityForResult(intent, requestCode)
    }

    private fun startDocumentPicker(requestCode: Int) {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivityForResult(intent, requestCode)
    }

    @Deprecated("Handled through Android document and camera activities")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode != RESULT_OK) {
            if (requestCode == REQUEST_AI_CAMERA || requestCode == REQUEST_AI_GALLERY) pendingAiCount = 0
            return
        }
        when (requestCode) {
            REQUEST_ITEM_CAMERA -> {
                formPhotoPath = cameraOutputPath
                formPhotoStatus?.text = if (formPhotoPath == null) "No photo attached" else "Photo attached"
            }
            REQUEST_ITEM_GALLERY -> {
                formPhotoPath = data?.data?.let { copyUriIntoPrivateFiles(it, "photo") }
                formPhotoStatus?.text = if (formPhotoPath == null) "No photo attached" else "Photo attached"
            }
            REQUEST_AI_CAMERA -> {
                runAiRecognition(cameraOutputPath, pendingAiCount)
                pendingAiCount = 0
            }
            REQUEST_AI_GALLERY -> {
                runAiRecognition(data?.data?.let { copyUriIntoPrivateFiles(it, "photo") }, pendingAiCount)
                pendingAiCount = 0
            }
            REQUEST_RECEIPT -> {
                formReceiptPath = data?.data?.let { copyUriIntoPrivateFiles(it, "receipt") }
                receiptStatus?.text = if (formReceiptPath == null) "Receipt: not attached" else "Receipt: attached"
            }
            REQUEST_DOCUMENT -> {
                formDocumentPath = data?.data?.let { copyUriIntoPrivateFiles(it, "document") }
                documentStatus?.text = if (formDocumentPath == null) "Appraisal / document: not attached" else "Appraisal / document: attached"
            }
            REQUEST_EXPORT_PDF -> {
                val uri = data?.data ?: return
                runCatching {
                    contentResolver.openOutputStream(uri)?.use { output ->
                        InventoryPdfExporter.export(this, output, database.items(), database)
                    } ?: error("Could not open the selected PDF location")
                }.onSuccess {
                    toast("Inventory PDF exported")
                }.onFailure {
                    toast("PDF export failed: " + (it.message ?: "unknown error"))
                }
            }
        }
    }

    private fun copyUriIntoPrivateFiles(uri: Uri, prefix: String): String? = runCatching {
        val mime = contentResolver.getType(uri).orEmpty()
        val extension = when {
            mime.contains("pdf") -> ".pdf"
            mime.contains("png") -> ".png"
            mime.contains("jpeg") || mime.contains("jpg") -> ".jpg"
            mime.contains("word") -> ".docx"
            else -> ".bin"
        }
        val directory = File(filesDir, "media").apply { mkdirs() }
        val destination = File(directory, prefix + "-" + System.currentTimeMillis() + extension)
        contentResolver.openInputStream(uri)?.use { input ->
            FileOutputStream(destination).use { output -> input.copyTo(output) }
        } ?: return null
        destination.absolutePath
    }.getOrNull()

    private fun addField(
        body: LinearLayout,
        title: String,
        id: Int,
        hintText: String,
        value: String,
        inputType: Int = android.text.InputType.TYPE_CLASS_TEXT,
        multiline: Boolean = false
    ): EditText {
        body.addView(label(title, 13, COLOR_MUTED, true))
        val input = EditText(this).apply {
            this.id = id
            hint = hintText
            setText(value)
            this.inputType = inputType
            if (multiline) {
                minLines = 3
                gravity = Gravity.TOP or Gravity.START
            } else {
            isSingleLine = true
            }
            setPadding(dp(12), dp(10), dp(12), dp(10))
            setBackground(rounded(COLOR_WHITE, COLOR_BORDER, 10))
        }
        body.addView(input, matchWrap())
        body.addView(spacer(6))
        return input
    }

    private fun locationOptions(): List<Pair<Long?, String>> =
        listOf(null to "No location") + database.locations()
            .sortedBy { database.locationPath(it.id).lowercase() }
            .map { it.id to database.locationPath(it.id) }

    private fun screen(title: String, back: (() -> Unit)?): LinearLayout {
        val outer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(COLOR_BACKGROUND)
        }
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(10), dp(16), dp(10))
        }
        if (back != null) {
            header.addView(button("‹", primary = false, action = back).apply {
                textSize = 25f
                minWidth = dp(46)
            })
        }
        val heading = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        heading.addView(label("INVENTORI", 11, COLOR_GREEN, true))
        heading.addView(label(title, 22, COLOR_INK, true))
        header.addView(heading, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        outer.addView(header, matchWrap())
        val scroll = ScrollView(this).apply { clipToPadding = false }
        val body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(8), dp(16), dp(26))
        }
        scroll.addView(body)
        outer.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        setContentView(outer)
        return body
    }

    private fun card(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(14), dp(12), dp(14), dp(12))
        setBackground(rounded(COLOR_WHITE, COLOR_BORDER, 14))
    }

    private fun label(value: String, size: Int, color: Int, bold: Boolean = false): TextView =
        TextView(this).apply {
            text = value
            textSize = size.toFloat()
            setTextColor(color)
            if (bold) setTypeface(typeface, android.graphics.Typeface.BOLD)
            setPadding(0, dp(2), 0, dp(2))
        }

    private fun button(
        title: String,
        id: Int = View.generateViewId(),
        primary: Boolean = false,
        action: () -> Unit
    ): Button = Button(this).apply {
        this.id = id
        text = title
        isAllCaps = false
        textSize = 14f
        minHeight = dp(42)
        setPadding(dp(10), dp(4), dp(10), dp(4))
        backgroundTintList = ColorStateList.valueOf(if (primary) COLOR_GREEN else COLOR_BUTTON)
        setTextColor(if (primary) COLOR_WHITE else COLOR_INK)
        setOnClickListener { action() }
    }

    private fun spinner(options: List<String>): Spinner = Spinner(this).apply {
        adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_item, options).also {
            it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        }
    }

    private fun spacer(height: Int): View = View(this).apply {
        layoutParams = LinearLayout.LayoutParams(1, dp(height))
    }

    private fun rounded(fill: Int, stroke: Int, radius: Int) =
        android.graphics.drawable.GradientDrawable().apply {
            setColor(fill)
            setCornerRadius(dp(radius).toFloat())
            setStroke(dp(1), stroke)
        }

    private fun matchWrap() = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT,
        ViewGroup.LayoutParams.WRAP_CONTENT
    )

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun toast(message: String) = Toast.makeText(this, message, Toast.LENGTH_SHORT).show()

    private fun searchText(edit: EditText): String = edit.text?.toString().orEmpty()

    private fun parsePrice(value: String): Double? =
        value.trim().replace(",", "").removePrefix("$").takeIf { it.isNotBlank() }?.toDoubleOrNull()

    private fun money(value: Double): String = NumberFormat.getCurrencyInstance().format(value)

    private fun dateText(epoch: Long): String =
        java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.MEDIUM, java.text.DateFormat.SHORT)
            .format(java.util.Date(epoch))

    private fun statusTitle(status: String): String = when (status) {
        InventoryItem.STATUS_CHECKED_OUT -> "Checked out"
        InventoryItem.STATUS_RETURNED -> "Returned"
        else -> "Available"
    }

    private fun modeTitle(mode: String): String = when (mode) {
        MODE_MOVING -> "Moving / Packing"
        MODE_BUSINESS -> "Small Business"
        else -> "Home Inventory"
    }

    private fun modeShortTitle(mode: String): String = when (mode) {
        MODE_MOVING -> "Moving"
        MODE_BUSINESS -> "Business"
        else -> "Home"
    }

    private fun locationTypeTitle(type: String): String = when (type) {
        "property" -> "Property"
        "room" -> "Room"
        "storage" -> "Storage area"
        "shelf" -> "Shelf"
        "box" -> "Box / bin"
        "business" -> "Business location"
        else -> type
    }

    companion object {
        private const val MODE_HOME = "home"
        private const val MODE_MOVING = "moving"
        private const val MODE_BUSINESS = "business"
        private const val KEY_MODE = "mode"
        private const val KEY_ONBOARDED = "onboarded"
        private const val REQUEST_ITEM_CAMERA = 1001
        private const val REQUEST_ITEM_GALLERY = 1002
        private const val REQUEST_AI_CAMERA = 1003
        private const val REQUEST_AI_GALLERY = 1004
        private const val REQUEST_RECEIPT = 1005
        private const val REQUEST_DOCUMENT = 1006
        private const val REQUEST_EXPORT_PDF = 1007
        private val COLOR_BACKGROUND = 0xFFF6F7F2.toInt()
        private val COLOR_WHITE = 0xFFFFFFFF.toInt()
        private val COLOR_INK = 0xFF192521.toInt()
        private val COLOR_MUTED = 0xFF66736D.toInt()
        private val COLOR_GREEN = 0xFF1E6B5B.toInt()
        private val COLOR_BORDER = 0xFFE1E7E2.toInt()
        private val COLOR_BUTTON = 0xFFE9EFEB.toInt()
        private val NUMBER_INPUT = android.text.InputType.TYPE_CLASS_NUMBER
        private val DECIMAL_INPUT =
            android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL
    }
}
