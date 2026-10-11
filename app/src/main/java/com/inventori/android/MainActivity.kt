package com.inventori.android

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.BitmapFactory
import android.graphics.drawable.RippleDrawable
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
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

    private val darkUi: Boolean
        get() = (resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK) ==
            android.content.res.Configuration.UI_MODE_NIGHT_YES
    private val COLOR_BACKGROUND: Int get() = if (darkUi) 0xFF111A16.toInt() else 0xFFF5F7F3.toInt()
    private val COLOR_SURFACE: Int get() = if (darkUi) 0xFF1B2520.toInt() else 0xFFFFFFFF.toInt()
    private val COLOR_SURFACE_VARIANT: Int get() = if (darkUi) 0xFF27332C.toInt() else 0xFFE8EEE9.toInt()
    private val COLOR_WHITE: Int get() = COLOR_SURFACE
    private val COLOR_INK: Int get() = if (darkUi) 0xFFE4ECE6.toInt() else 0xFF18231D.toInt()
    private val COLOR_MUTED: Int get() = if (darkUi) 0xFFB1BEB5.toInt() else 0xFF68766D.toInt()
    private val COLOR_GREEN: Int get() = COLOR_PRIMARY
    private val COLOR_PRIMARY: Int get() = if (darkUi) 0xFF9ED0AF.toInt() else 0xFF245D45.toInt()
    private val COLOR_ON_PRIMARY: Int get() = if (darkUi) 0xFF14351F.toInt() else 0xFFFFFFFF.toInt()
    private val COLOR_PRIMARY_CONTAINER: Int get() = if (darkUi) 0xFF294B35.toInt() else 0xFFDCEBDF.toInt()
    private val COLOR_ON_PRIMARY_CONTAINER: Int get() = if (darkUi) 0xFFD6EBDD.toInt() else 0xFF183B28.toInt()
    private val COLOR_BORDER: Int get() = if (darkUi) 0xFF3A4940.toInt() else 0xFFD8E1DA.toInt()
    private val COLOR_BUTTON: Int get() = COLOR_SURFACE_VARIANT

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
        val body = screen("Get started", null)
        body.addView(spacer(12))
        val hero = card(COLOR_PRIMARY_CONTAINER).apply { setPadding(dp(22), dp(24), dp(22), dp(24)) }
        hero.addView(iconView(R.drawable.ic_inventory, COLOR_PRIMARY, 38))
        hero.addView(spacer(16))
        hero.addView(label("Know what you own.", 30, COLOR_INK, true))
        hero.addView(spacer(8))
        hero.addView(label("A clear, local record for your home, your move, or your small business.", 16, COLOR_MUTED))
        body.addView(hero, matchWrap())
        body.addView(spacer(26))
        body.addView(label("MAKE IT YOURS", 12, COLOR_PRIMARY, true))
        body.addView(spacer(5))
        body.addView(label("Where are you keeping inventory?", 21, COLOR_INK, true))
        body.addView(spacer(12))
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
            choice.textSize = 16f
            choice.minHeight = dp(58)
            body.addView(choice, matchWrap())
            body.addView(spacer(8))
        }
        body.addView(label("Works offline with no account. You can change this later.", 14, COLOR_MUTED))
        body.addView(spacer(20))
        body.addView(button("Get started", R.id.onboarding_continue, true) {
            preferences.edit().putBoolean(KEY_ONBOARDED, true).putString(KEY_MODE, selectedMode).apply()
            showHome()
        }, matchWrap())
    }

    private fun showHome(searchQuery: String = "") {
        val modeTitle = modeTitle(selectedMode)
        val body = screen(modeTitle, null, selectedTab = TAB_INVENTORY)
        val allItems = database.items()
        val allLocations = database.locations()
        val totalUnits = allItems.sumOf {
            if (it.itemType == InventoryItem.TYPE_UNIQUE) 1 else it.quantity.coerceAtLeast(0)
        }
        val estimatedValue = allItems.mapNotNull { it.replacementValue }.sum()
        body.addView(label(modeTitle.uppercase(), 12, COLOR_PRIMARY, true))
        body.addView(spacer(4))
        body.addView(label("Your inventory,\nat a glance", 29, COLOR_INK, true))
        body.addView(spacer(16))

        val stats = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        stats.addView(statCard("$totalUnits", "items", R.drawable.ic_inventory),
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = dp(6) })
        stats.addView(statCard("${allLocations.size}", "locations", R.drawable.ic_location),
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = dp(6) })
        body.addView(stats, matchWrap())
        if (estimatedValue > 0) {
            body.addView(spacer(8))
            val value = card(COLOR_SURFACE_VARIANT).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                addView(iconView(R.drawable.ic_reports, COLOR_PRIMARY, 21))
                addView(spacerHorizontal(10))
                addView(label("Estimated replacement value", 13, COLOR_MUTED),
                    LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                addView(label(money(estimatedValue), 15, COLOR_INK, true))
            }
            body.addView(value, matchWrap())
        }
        body.addView(spacer(20))

        val modeRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        listOf(MODE_HOME, MODE_MOVING, MODE_BUSINESS).forEach { mode ->
            val modeButton = button(modeShortTitle(mode), primary = selectedMode == mode) {
                selectedMode = mode
                preferences.edit().putString(KEY_MODE, mode).apply()
                showHome(searchQuery)
            }
            modeRow.addView(modeButton, LinearLayout.LayoutParams(0, dp(48), 1f).apply {
                marginEnd = dp(5)
            })
        }
        body.addView(modeRow)
        body.addView(spacer(16))

        val searchBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), 0, dp(14), 0)
            background = rounded(COLOR_SURFACE_VARIANT, radius = 20)
        }
        searchBar.addView(iconView(R.drawable.ic_search, COLOR_MUTED, 22))
        val search = EditText(this).apply {
            id = R.id.home_search
            hint = "Search your inventory"
            isSingleLine = true
            imeOptions = EditorInfo.IME_ACTION_SEARCH
            setText(searchQuery)
            textSize = 15f
            setTextColor(COLOR_INK)
            setHintTextColor(COLOR_MUTED)
            setPadding(dp(12), dp(13), dp(4), dp(13))
            background = null
        }
        searchBar.addView(search, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        body.addView(searchBar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(54)))
        body.addView(spacer(14))

        val scanCard = LinearLayout(this).apply {
            id = R.id.ai_one
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(18), dp(18), dp(18), dp(18))
            background = rippleBackground(COLOR_PRIMARY, 24)
            isClickable = true
            isFocusable = true
            contentDescription = "Scan item"
            setOnClickListener { chooseAiInput(1) }
        }
        val scanIcon = FrameLayout(this).apply {
            background = rounded(COLOR_PRIMARY_CONTAINER, radius = 20)
            addView(iconView(R.drawable.ic_scan, COLOR_ON_PRIMARY_CONTAINER, 29), FrameLayout.LayoutParams(dp(58), dp(58), Gravity.CENTER))
        }
        scanCard.addView(scanIcon, LinearLayout.LayoutParams(dp(58), dp(58)))
        scanCard.addView(spacerHorizontal(15))
        val scanCopy = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        scanCopy.addView(label("Scan an item", 18, Color.WHITE, true))
        scanCopy.addView(spacer(4))
        scanCopy.addView(label("Capture a photo to start", 13, 0xFFD8E9DE.toInt()))
        scanCard.addView(scanCopy, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        scanCard.addView(iconView(R.drawable.ic_arrow_right, Color.WHITE, 22))
        body.addView(scanCard, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(100)))
        body.addView(spacer(8))

        val actions = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        actions.addView(button("Scan many", R.id.ai_multiple) { chooseAiInput(3) }.apply {
            setPadding(dp(8), dp(7), dp(8), dp(7))
            textSize = 13f
        }, LinearLayout.LayoutParams(0, dp(48), 1f).apply { marginEnd = dp(4) })
        actions.addView(button("Add item", R.id.home_add_item, primary = true) {
            showItemForm(null)
        }.apply {
            setPadding(dp(8), dp(7), dp(8), dp(7))
            textSize = 13f
        }, LinearLayout.LayoutParams(0, dp(48), 1f).apply { marginStart = dp(4); marginEnd = dp(4) })
        actions.addView(button("Locations", R.id.home_locations, leadingIcon = R.drawable.ic_location) {
            showLocations()
        }.apply {
            setPadding(dp(8), dp(7), dp(8), dp(7))
            textSize = 13f
        }, LinearLayout.LayoutParams(0, dp(48), 1f).apply { marginStart = dp(4) })
        body.addView(actions)
        body.addView(spacer(22))
        body.addView(sectionHeading("Recent items", "Updated as you add or edit things"))
        body.addView(spacer(10))
        val results = LinearLayout(this).apply {
            id = R.id.home_items
            orientation = LinearLayout.VERTICAL
        }
        body.addView(results, matchWrap())
        renderItems(results, database.searchItems(searchQuery), searchQuery)
        body.addView(spacer(18))
        val locationHeading = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        locationHeading.addView(sectionHeading("Your locations", "${allLocations.size} places in your inventory"),
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        locationHeading.addView(button("See all") { showLocations() })
        body.addView(locationHeading, matchWrap())
        body.addView(spacer(8))
        if (allLocations.isEmpty()) {
            body.addView(emptyState(R.drawable.ic_location, "Add your first location", "Create a room, shelf, or box so every item is easy to find."), matchWrap())
        } else {
            allLocations.sortedBy { database.locationPath(it.id).lowercase() }.take(3).forEach { location ->
                val locationCard = card(COLOR_SURFACE)
                locationCard.orientation = LinearLayout.HORIZONTAL
                locationCard.gravity = Gravity.CENTER_VERTICAL
                locationCard.addView(iconView(R.drawable.ic_location, COLOR_PRIMARY, 22))
                locationCard.addView(spacerHorizontal(12))
                val locationText = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
                locationText.addView(label(location.name, 15, COLOR_INK, true))
                locationText.addView(label(database.locationPath(location.id), 12, COLOR_MUTED))
                locationCard.addView(locationText, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                body.addView(locationCard, matchWrap())
                body.addView(spacer(6))
            }
        }
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
            val empty = if (query.isBlank()) {
                emptyState(R.drawable.ic_inventory, "Nothing here yet", "Scan a photo or add an item manually to start your inventory.")
            } else {
                emptyState(R.drawable.ic_search, "No matches", "Try a name, brand, model, serial number, or location.")
            }
            container.addView(empty, matchWrap())
            return
        }
        items.forEach { item ->
            val itemCard = card(COLOR_SURFACE)
            itemCard.orientation = LinearLayout.HORIZONTAL
            itemCard.gravity = Gravity.CENTER_VERTICAL
            itemCard.setPadding(dp(12), dp(12), dp(12), dp(12))
            itemCard.addView(itemThumbnail(item), LinearLayout.LayoutParams(dp(76), dp(76)))
            itemCard.addView(spacerHorizontal(13))
            val content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            val title = label(item.name, 17, COLOR_INK, true)
            title.maxLines = 1
            title.ellipsize = android.text.TextUtils.TruncateAt.END
            content.addView(title)
            val details = listOf(item.brand, item.model, item.category).filter { it.isNotBlank() }.joinToString(" · ")
            if (details.isNotBlank()) {
                val detailLabel = label(details, 12, COLOR_MUTED)
                detailLabel.maxLines = 1
                detailLabel.ellipsize = android.text.TextUtils.TruncateAt.END
                content.addView(detailLabel)
            }
            val location = label(database.locationPath(item.locationId), 12, COLOR_PRIMARY, true)
            location.maxLines = 1
            location.ellipsize = android.text.TextUtils.TruncateAt.MIDDLE
            content.addView(location)
            val valueLine = buildString {
                if (item.itemType == InventoryItem.TYPE_QUANTITY) append("Qty ${item.quantity}")
                else append(statusTitle(item.status))
                item.replacementValue?.let { append("  ·  ").append(money(it)) }
                if (item.receiptPath.isNullOrBlank()) append("  ·  No receipt")
            }
            content.addView(label(valueLine, 11, COLOR_MUTED))
            val actionRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
            if (item.itemType == InventoryItem.TYPE_QUANTITY) {
                actionRow.addView(button("−1") { database.setQuantity(item.id, item.quantity - 1); showHome(query) },
                    LinearLayout.LayoutParams(0, dp(38), 1f).apply { marginEnd = dp(4) })
                actionRow.addView(button("+1") { database.setQuantity(item.id, item.quantity + 1); showHome(query) },
                    LinearLayout.LayoutParams(0, dp(38), 1f).apply { marginEnd = dp(4) })
            }
            actionRow.addView(button("Details", primary = false) { showItemDetail(item.id) },
                LinearLayout.LayoutParams(0, dp(38), 1f))
            content.addView(spacer(5))
            content.addView(actionRow, matchWrap())
            itemCard.addView(content, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            container.addView(itemCard, matchWrap())
            container.addView(spacer(8))
        }
    }

    private fun showLocations() {
        val body = screen("Locations", { showHome() }, selectedTab = TAB_LOCATIONS)
        body.addView(label("Places that make every item easier to find.", 15, COLOR_MUTED))
        body.addView(spacer(16))
        body.addView(button("Add a location", R.id.locations_add_button, true, leadingIcon = R.drawable.ic_add) { showAddLocationForm() }, matchWrap())
        body.addView(spacer(14))
        val locations = database.locations().sortedBy { database.locationPath(it.id).lowercase() }
        if (locations.isEmpty()) {
            body.addView(emptyState(R.drawable.ic_location, "No locations yet", "Start with a home or business, then add rooms, storage areas, shelves, and boxes."), matchWrap())
        } else {
            locations.forEach { location ->
                val node = card(COLOR_SURFACE)
                node.orientation = LinearLayout.HORIZONTAL
                node.gravity = Gravity.CENTER_VERTICAL
                node.addView(iconView(if (location.type == "box") R.drawable.ic_box else R.drawable.ic_location, COLOR_PRIMARY, 24))
                node.addView(spacerHorizontal(14))
                val copy = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
                copy.addView(label(location.name, 16, COLOR_INK, true))
                copy.addView(label(database.locationPath(location.id), 13, COLOR_MUTED))
                copy.addView(label(locationTypeTitle(location.type), 12, COLOR_PRIMARY))
                node.addView(copy, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                body.addView(node, matchWrap())
                body.addView(spacer(8))
            }
        }
    }

    private fun showAddLocationForm() {
        val body = screen("New location", { showLocations() })
        body.addView(label("Create a property or business, then add rooms, storage areas, shelves, and boxes.", 14, COLOR_MUTED))
        body.addView(spacer(8))
        val name = addField(body, "Location name", R.id.location_name_input, "Home, Basement, Shelf, Box 4", "")
        body.addView(label("Type", 13, COLOR_MUTED, true))
        val typeNames = listOf("Property / business", "Room", "Storage area", "Shelf", "Box / bin", "Business location")
        val typeValues = listOf("property", "room", "storage", "shelf", "box", "business")
        val typeSpinner = spinner(typeNames).apply { id = R.id.location_type_spinner }
        body.addView(typeSpinner, matchWrap())
        body.addView(spacer(8))
        body.addView(label("Inside", 13, COLOR_MUTED, true))
        val parents = listOf<InventoryLocation?>(null) + database.locations()
        val parentNames = listOf("No parent (top level)") + parents.drop(1).map {
            database.locationPath(it!!.id)
        }
        val parentSpinner = spinner(parentNames).apply { id = R.id.location_parent_spinner }
        body.addView(parentSpinner, matchWrap())
        body.addView(spacer(14))
        body.addView(button("Create location", R.id.location_save_button, true) {
            val locationName = name.text.toString().trim()
            if (locationName.isEmpty()) {
                name.error = "Enter a name"
                name.requestFocus()
                return@button
            }
            val type = typeValues[typeSpinner.selectedItemPosition]
            val parent = parents[parentSpinner.selectedItemPosition]
            database.addLocation(locationName, type, parent?.id)
            showLocations()
        }, matchWrap())
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
        val body = screen("Scan", { showHome() }, selectedTab = TAB_SCAN)
        body.addView(label("Start with a photo", 27, COLOR_INK, true))
        body.addView(spacer(6))
        body.addView(label("Choose an image and review every suggested item before it is saved.", 15, COLOR_MUTED))
        body.addView(spacer(20))
        body.addView(button("Take a photo", leadingIcon = R.drawable.ic_scan) { startCamera(REQUEST_AI_CAMERA) }, matchWrap())
        body.addView(spacer(8))
        body.addView(button("Choose from gallery", leadingIcon = R.drawable.ic_image) { startGallery(REQUEST_AI_GALLERY) }, matchWrap())
        body.addView(spacer(16))
        val mock = card(COLOR_SURFACE_VARIANT)
        mock.addView(label("Preview the review flow", 16, COLOR_INK, true))
        mock.addView(spacer(4))
        mock.addView(label("The V1 recognizer is a local mock. Your photos stay on this device.", 13, COLOR_MUTED))
        mock.addView(spacer(12))
        mock.addView(button("Use mock sample", R.id.ai_mock_sample, true, leadingIcon = R.drawable.ic_inventory) {
            runAiRecognition(null, count)
            pendingAiCount = 0
        }, matchWrap())
        body.addView(mock, matchWrap())
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
            val editId = when (index) {
                0 -> R.id.ai_draft_edit_0
                1 -> R.id.ai_draft_edit_1
                2 -> R.id.ai_draft_edit_2
                else -> View.generateViewId()
            }
            row.addView(button("Edit", editId) {
                editDetectedItem(index)
            },
                LinearLayout.LayoutParams(0, dp(42), 1f).apply { marginEnd = dp(5) })
            val removeId = if (index == 0) R.id.ai_draft_remove_0 else View.generateViewId()
            row.addView(button("Remove", removeId) {
                detectedItems.removeAt(index)
                showAiConfirmation()
            }, LinearLayout.LayoutParams(0, dp(42), 1f).apply { marginStart = dp(5) })
            draft.addView(row, matchWrap())
            body.addView(draft, matchWrap())
            body.addView(spacer(6))
        }
        body.addView(button("＋ Add another item", R.id.ai_add_draft) {
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
        val body = screen("Edit draft item", { showAiConfirmation() })
        val name = addField(body, "Name", R.id.ai_edit_name, "Item name", item.name)
        val category = addField(body, "Category", R.id.ai_edit_category, "Category", item.category)
        val brand = addField(body, "Brand", R.id.ai_edit_brand, "Brand", item.brand)
        val model = addField(body, "Model", R.id.ai_edit_model, "Model", item.model)
        val serial = addField(body, "Serial number", R.id.ai_edit_serial, "Serial number", item.serialNumber)
        val quantity = addField(body, "Quantity", R.id.ai_edit_quantity, "1", item.quantity.toString(), NUMBER_INPUT)
        val purchase = addField(body, "Purchase price", R.id.ai_edit_purchase, "Optional", item.purchasePrice?.toString().orEmpty(), DECIMAL_INPUT)
        val replacement = addField(body, "Estimated replacement value", R.id.ai_edit_replacement, "Optional", item.replacementValue?.toString().orEmpty(), DECIMAL_INPUT)
        val notes = addField(body, "Notes", R.id.ai_edit_notes, "Notes", item.notes, multiline = true)
        body.addView(button("Apply changes", R.id.ai_edit_apply, true) {
            val editedName = name.text.toString().trim()
            if (editedName.isEmpty()) {
                name.error = "Name is required"
                return@button
            }
            detectedItems[index] = item.copy(
                name = editedName,
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
        }, matchWrap())
    }

    private fun requestPdfExport() {
        val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "application/pdf"
            putExtra(Intent.EXTRA_TITLE, "Inventori-Inventory.pdf")
        }
        startActivityForResult(intent, REQUEST_EXPORT_PDF)
    }

    private fun showReports() {
        val body = screen("Reports", { showHome() }, selectedTab = TAB_REPORTS)
        val total = database.items().size
        val locationCount = database.locations().size
        val reportCard = card(COLOR_PRIMARY_CONTAINER).apply { setPadding(dp(22), dp(22), dp(22), dp(22)) }
        reportCard.addView(iconView(R.drawable.ic_reports, COLOR_ON_PRIMARY_CONTAINER, 34))
        reportCard.addView(spacer(12))
        reportCard.addView(label("Your inventory, ready to export", 22, COLOR_INK, true))
        reportCard.addView(spacer(6))
        reportCard.addView(label("A location-organized PDF with the photos and item details you have saved.", 14, COLOR_MUTED))
        body.addView(reportCard, matchWrap())
        body.addView(spacer(16))
        body.addView(label("$total inventory entries  ·  $locationCount locations", 14, COLOR_MUTED))
        body.addView(spacer(14))
        body.addView(button("Export inventory PDF", R.id.home_export_pdf, true, leadingIcon = R.drawable.ic_download) {
            requestPdfExport()
        }, matchWrap())
        body.addView(spacer(10))
        body.addView(label("Values are estimates you entered. The report does not guarantee an insurance claim.", 13, COLOR_MUTED))
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
    public override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
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
            textSize = 15f
            setTextColor(COLOR_INK)
            setHintTextColor(COLOR_MUTED)
            if (multiline) {
                minLines = 3
                gravity = Gravity.TOP or Gravity.START
            } else {
                isSingleLine = true
            }
            setPadding(dp(15), dp(12), dp(15), dp(12))
            background = rounded(COLOR_SURFACE_VARIANT, radius = 16)
        }
        body.addView(input, matchWrap())
        body.addView(spacer(9))
        return input
    }

    private fun locationOptions(): List<Pair<Long?, String>> =
        listOf(null to "No location") + database.locations()
            .sortedBy { database.locationPath(it.id).lowercase() }
            .map { it.id to database.locationPath(it.id) }

    private fun screen(title: String, back: (() -> Unit)?, selectedTab: String? = null): LinearLayout {
        val outer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(COLOR_BACKGROUND)
        }
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(20), dp(12), dp(20), dp(8))
        }
        if (back != null) {
            header.addView(button("‹", primary = false, action = back).apply {
                id = R.id.screen_back
                textSize = 25f
                minWidth = dp(44)
                minHeight = dp(44)
                contentDescription = "Back"
            }, LinearLayout.LayoutParams(dp(48), dp(48)).apply { marginEnd = dp(10) })
        } else {
            val brandMark = FrameLayout(this).apply {
                background = rounded(COLOR_PRIMARY_CONTAINER, radius = 15)
                addView(iconView(R.drawable.ic_inventory, COLOR_ON_PRIMARY_CONTAINER, 25),
                    FrameLayout.LayoutParams(dp(42), dp(42), Gravity.CENTER))
            }
            header.addView(brandMark, LinearLayout.LayoutParams(dp(44), dp(44)).apply { marginEnd = dp(12) })
        }
        val heading = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        if (back == null) {
            heading.addView(label("Inventori", 21, COLOR_INK, true))
            heading.addView(label(title, 13, COLOR_MUTED))
        } else {
            heading.addView(label("INVENTORI", 10, COLOR_PRIMARY, true))
            val titleLabel = label(title, 19, COLOR_INK, true)
            titleLabel.maxLines = 1
            titleLabel.ellipsize = android.text.TextUtils.TruncateAt.END
            heading.addView(titleLabel)
        }
        header.addView(heading, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        outer.addView(header, matchWrap())
        val scroll = ScrollView(this).apply {
            clipToPadding = false
            isFillViewport = true
            overScrollMode = View.OVER_SCROLL_NEVER
        }
        val body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(12), dp(20), dp(26))
        }
        scroll.addView(body)
        outer.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        if (selectedTab != null) outer.addView(bottomNavigation(selectedTab), matchWrap())
        setContentView(outer)
        return body
    }

    private fun card(surface: Int = COLOR_SURFACE): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(17), dp(15), dp(17), dp(15))
        background = rounded(surface, radius = 22)
        elevation = dp(1).toFloat()
    }

    private fun label(value: String, size: Int, color: Int, bold: Boolean = false): TextView =
        TextView(this).apply {
            text = value
            textSize = size.toFloat()
            setTextColor(color)
            typeface = android.graphics.Typeface.create(
                if (bold) "sans-serif-medium" else "sans-serif",
                if (bold) android.graphics.Typeface.NORMAL else android.graphics.Typeface.NORMAL
            )
            setPadding(0, dp(2), 0, dp(2))
            if (size <= 12) letterSpacing = 0.015f
        }

    private fun button(
        title: String,
        id: Int = View.generateViewId(),
        primary: Boolean = false,
        leadingIcon: Int? = null,
        action: () -> Unit
    ): Button = Button(this).apply {
        this.id = id
        text = title
        isAllCaps = false
        textSize = 14f
        typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL)
        minHeight = dp(46)
        setPadding(dp(16), dp(7), dp(16), dp(7))
        background = rippleBackground(if (primary) COLOR_PRIMARY else COLOR_BUTTON, 18)
        setTextColor(if (primary) COLOR_ON_PRIMARY else COLOR_INK)
        elevation = 0f
        leadingIcon?.let { resource ->
            val drawable = androidx.core.content.ContextCompat.getDrawable(this@MainActivity, resource)?.mutate()
            drawable?.setTint(if (primary) COLOR_ON_PRIMARY else COLOR_PRIMARY)
            setCompoundDrawablesRelativeWithIntrinsicBounds(drawable, null, null, null)
            compoundDrawablePadding = dp(9)
        }
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

    private fun rounded(fill: Int, stroke: Int = Color.TRANSPARENT, radius: Int) =
        android.graphics.drawable.GradientDrawable().apply {
            setColor(fill)
            setCornerRadius(dp(radius).toFloat())
            if (stroke != Color.TRANSPARENT) setStroke(dp(1), stroke)
        }

    private fun rippleBackground(fill: Int, radius: Int) = RippleDrawable(
        ColorStateList.valueOf(if (darkUi) 0x33FFFFFF.toInt() else 0x22000000),
        rounded(fill, radius = radius),
        rounded(Color.WHITE, radius = radius)
    )

    private fun iconView(resource: Int, tint: Int, size: Int): ImageView = ImageView(this).apply {
        setImageResource(resource)
        setColorFilter(tint)
        layoutParams = ViewGroup.LayoutParams(dp(size), dp(size))
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    private fun itemThumbnail(item: InventoryItem): ImageView = ImageView(this).apply {
        val path = item.photoPath
        val bitmap = path?.takeIf { File(it).isFile }?.let { filePath ->
            runCatching {
                val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeFile(filePath, options)
                val targetPixels = dp(152)
                var sampleSize = 1
                while (options.outWidth / sampleSize > targetPixels * 2 || options.outHeight / sampleSize > targetPixels * 2) {
                    sampleSize *= 2
                }
                options.inJustDecodeBounds = false
                options.inSampleSize = sampleSize
                BitmapFactory.decodeFile(filePath, options)
            }.getOrNull()
        }
        if (bitmap != null) setImageBitmap(bitmap) else setImageResource(R.drawable.ic_inventory)
        scaleType = if (bitmap != null) ImageView.ScaleType.CENTER_CROP else ImageView.ScaleType.CENTER_INSIDE
        setPadding(if (bitmap == null) dp(20) else 0, if (bitmap == null) dp(20) else 0,
            if (bitmap == null) dp(20) else 0, if (bitmap == null) dp(20) else 0)
        background = rounded(COLOR_SURFACE_VARIANT, radius = 18)
        clipToOutline = true
        contentDescription = if (bitmap == null) "No photo for ${item.name}" else "Photo of ${item.name}"
    }

    private fun statCard(value: String, title: String, icon: Int): LinearLayout = card(COLOR_SURFACE).apply {
        addView(iconView(icon, COLOR_PRIMARY, 22))
        addView(spacer(8))
        addView(label(value, 27, COLOR_INK, true))
        addView(label(title, 13, COLOR_MUTED))
    }

    private fun sectionHeading(title: String, subtitle: String): LinearLayout =
        LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(label(title, 19, COLOR_INK, true))
            addView(label(subtitle, 12, COLOR_MUTED))
        }

    private fun emptyState(icon: Int, title: String, description: String): LinearLayout =
        card(COLOR_SURFACE_VARIANT).apply {
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(22), dp(22), dp(22), dp(22))
            addView(iconView(icon, COLOR_PRIMARY, 32))
            addView(spacer(10))
            val headline = label(title, 17, COLOR_INK, true).apply { gravity = Gravity.CENTER }
            addView(headline, matchWrap())
            val copy = label(description, 14, COLOR_MUTED).apply { gravity = Gravity.CENTER }
            addView(copy, matchWrap())
        }

    private fun spacerHorizontal(width: Int): View = View(this).apply {
        layoutParams = LinearLayout.LayoutParams(dp(width), 1)
    }

    private fun bottomNavigation(selectedTab: String): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(8), dp(7), dp(8), dp(7))
        setBackgroundColor(COLOR_SURFACE)
        elevation = dp(8).toFloat()
        addView(navItem("Inventory", R.id.nav_inventory, R.drawable.ic_inventory, selectedTab == TAB_INVENTORY, false) { showHome() },
            LinearLayout.LayoutParams(0, dp(62), 1f))
        addView(navItem("Scan", R.id.nav_scan, R.drawable.ic_scan, selectedTab == TAB_SCAN, true) { chooseAiInput(1) },
            LinearLayout.LayoutParams(0, dp(68), 1f))
        addView(navItem("Locations", R.id.nav_locations, R.drawable.ic_location, selectedTab == TAB_LOCATIONS, false) { showLocations() },
            LinearLayout.LayoutParams(0, dp(62), 1f))
        addView(navItem("Reports", R.id.nav_reports, R.drawable.ic_reports, selectedTab == TAB_REPORTS, false) { showReports() },
            LinearLayout.LayoutParams(0, dp(62), 1f))
    }

    private fun navItem(title: String, id: Int, icon: Int, selected: Boolean, prominent: Boolean, action: () -> Unit): View =
        LinearLayout(this).apply {
            this.id = id
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            isClickable = true
            isFocusable = true
            isSelected = selected
            contentDescription = "$title tab"
            val iconSize = if (prominent) 47 else 27
            val iconFrame = FrameLayout(this@MainActivity).apply {
                background = if (prominent) rounded(COLOR_PRIMARY, radius = 17)
                    else if (selected) rounded(COLOR_PRIMARY_CONTAINER, radius = 16) else null
                val iconTint = if (prominent) COLOR_ON_PRIMARY else if (selected) COLOR_ON_PRIMARY_CONTAINER else COLOR_MUTED
                addView(iconView(icon, iconTint, if (prominent) 25 else 22),
                    FrameLayout.LayoutParams(dp(iconSize), dp(iconSize), Gravity.CENTER))
            }
            addView(iconFrame, LinearLayout.LayoutParams(dp(iconSize), dp(iconSize)))
            if (!prominent) {
                val label = label(title, 10, if (selected) COLOR_PRIMARY else COLOR_MUTED, selected)
                label.gravity = Gravity.CENTER
                addView(label, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            } else {
                val label = label(title, 10, COLOR_PRIMARY, true)
                label.gravity = Gravity.CENTER
                addView(label, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            }
            setOnClickListener { action() }
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
        private const val TAB_INVENTORY = "inventory"
        private const val TAB_SCAN = "scan"
        private const val TAB_LOCATIONS = "locations"
        private const val TAB_REPORTS = "reports"
        private val NUMBER_INPUT = android.text.InputType.TYPE_CLASS_NUMBER
        private val DECIMAL_INPUT =
            android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL
    }
}
