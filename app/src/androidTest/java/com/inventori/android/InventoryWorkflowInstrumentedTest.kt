package com.inventori.android

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.widget.Button
import android.widget.EditText
import android.widget.Spinner
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Direction
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import androidx.core.content.FileProvider
import java.io.ByteArrayOutputStream
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class InventoryWorkflowInstrumentedTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val packageName get() = context.packageName

    @Test
    fun nestedLocationsManualPhotoAndDocumentsPersistAfterActivityRestart() {
        resetLocalState()
        var scenario = ActivityScenario.launch(MainActivity::class.java)
        val device = UiDevice.getInstance(instrumentation)
        continueOnboarding(device)

        tapId(device, "home_locations")
        createLocation(device, "Home", null, null)
        createLocation(device, "Basement", "Room", "Home")
        createLocation(device, "Box 4", "Box / bin", "Home → Basement")
        device.findObject(By.text("‹")).click()
        device.waitForIdle()

        tapId(device, "home_add_item")
        scenario.onActivity { activity ->
            activity.findViewById<EditText>(R.id.item_name).setText("UI Test Camera")
            activity.findViewById<EditText>(R.id.item_category).setText("Electronics")
            activity.findViewById<EditText>(R.id.item_brand).setText("Acme")
            activity.findViewById<EditText>(R.id.item_model).setText("A1")
            activity.findViewById<EditText>(R.id.item_serial).setText("SERIAL-42")
            activity.findViewById<Spinner>(R.id.item_location).setSelection(3)
        }

        val media = File(context.filesDir, "media").apply { mkdirs() }
        val image = File(media, "instrumented-image.png")
        val bitmap = Bitmap.createBitmap(32, 32, Bitmap.Config.ARGB_8888)
        ByteArrayOutputStream().use { stream ->
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream)
            image.writeBytes(stream.toByteArray())
        }
        bitmap.recycle()
        val receipt = File(media, "instrumented-receipt.pdf").apply { writeText("receipt fixture") }
        val appraisal = File(media, "instrumented-appraisal.pdf").apply { writeText("appraisal fixture") }
        val photoUri = providerUri(image)
        val receiptUri = providerUri(receipt)
        val appraisalUri = providerUri(appraisal)
        scenario.onActivity { activity ->
            activity.onActivityResult(1002, Activity.RESULT_OK, Intent().setData(photoUri))
            activity.onActivityResult(1005, Activity.RESULT_OK, Intent().setData(receiptUri))
            activity.onActivityResult(1006, Activity.RESULT_OK, Intent().setData(appraisalUri))
        }
        scenario.onActivity { it.findViewById<Button>(R.id.item_save).performClick() }

        val db = InventoryDatabase(context)
        val saved = db.items().single { it.name == "UI Test Camera" }
        assertEquals("Home → Basement → Box 4", db.locationPath(saved.locationId))
        assertEquals("Acme", saved.brand)
        assertEquals("A1", saved.model)
        assertEquals("SERIAL-42", saved.serialNumber)
        assertTrue(File(saved.photoPath!!).exists())
        assertTrue(File(saved.receiptPath!!).exists())
        assertTrue(File(saved.documentPath!!).exists())
        db.close()

        scenario.close()
        scenario = ActivityScenario.launch(MainActivity::class.java)
        device.wait(Until.hasObject(By.text("UI Test Camera")), 5_000)
        assertTrue(device.hasObject(By.text("Home → Basement → Box 4")))
        scenario.close()
    }

    @Test
    fun multipleMockDraftsCanBeEditedAddedAndRemovedBeforeSaving() {
        resetLocalState()
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        val device = UiDevice.getInstance(instrumentation)
        continueOnboarding(device)
        tapId(device, "ai_multiple")
        tapText(device, "Use mock sample")
        assertTrue(device.hasObject(By.text("Detected power drill")))
        assertTrue(device.hasObject(By.text("Detected battery pack")))

        tapId(device, "ai_draft_edit_0")
        val firstName = device.findObject(By.res(packageName, "ai_edit_name"))
        firstName.clear()
        firstName.setText("Edited detected drill")
        device.pressBack()
        tapText(device, "Apply")
        scrollToBottom(device)
        tapText(device, "＋ Add another item")
        scrollToBottom(device)
        val editButtons = device.findObjects(By.text("Edit"))
        editButtons.last().click()
        val addedName = device.findObject(By.res(packageName, "ai_edit_name"))
        addedName.clear()
        addedName.setText("Added during review")
        device.pressBack()
        tapText(device, "Apply")

        scrollToTop(device)
        device.findObjects(By.text("Remove")).first().click()
        assertFalse(device.hasObject(By.text("Edited detected drill")))
        scrollToBottom(device)
        tapId(device, "ai_confirm_save")
        val db = InventoryDatabase(context)
        val saved = db.items()
        assertEquals(2, saved.size)
        assertTrue(saved.any { it.name == "Detected battery pack" })
        assertTrue(saved.any { it.name == "Added during review" })
        db.close()
        scenario.close()
    }

    @Test
    fun offlineEngineSupportsSearchMovesQuantityCheckoutReturnAndPdf() {
        resetLocalState()
        val db = InventoryDatabase(context)
        val home = db.addLocation("Home", "property", null)
        val basement = db.addLocation("Basement", "room", home)
        val storage = db.addLocation("Storage Room", "storage", basement)
        val shelf = db.addLocation("Shelf", "shelf", storage)
        val box = db.addLocation("Box 4", "box", shelf)
        val media = File(context.filesDir, "media").apply { mkdirs() }
        val photo = File(media, "engine-photo.jpg").apply { writeText("local image fixture") }
        val receipt = File(media, "engine-receipt.pdf").apply { writeText("local receipt fixture") }
        val appraisal = File(media, "engine-document.pdf").apply { writeText("local document fixture") }

        val uniqueId = db.saveItem(
            InventoryItem(
                name = "Circular saw",
                category = "Tools",
                brand = "Acme",
                model = "CS-9",
                serialNumber = "CIR-778",
                purchasePrice = 149.99,
                replacementValue = 220.0,
                notes = "Blade guard intact",
                photoPath = photo.absolutePath,
                receiptPath = receipt.absolutePath,
                documentPath = appraisal.absolutePath,
                locationId = box
            )
        )
        val quantityId = db.saveItem(
            InventoryItem(
                itemType = InventoryItem.TYPE_QUANTITY,
                name = "Packing boxes",
                category = "Moving",
                quantity = 3,
                locationId = shelf
            )
        )
        assertEquals("Home → Basement → Storage Room → Shelf → Box 4", db.locationPath(db.item(uniqueId)?.locationId))
        listOf("Circular saw", "Acme", "Tools", "CS-9", "CIR-778", "Box 4").forEach { term ->
            assertTrue("Search missed " + term, db.searchItems(term).any { it.id == uniqueId })
        }
        db.setStatus(uniqueId, InventoryItem.STATUS_CHECKED_OUT)
        assertEquals(InventoryItem.STATUS_CHECKED_OUT, db.item(uniqueId)?.status)
        db.setStatus(uniqueId, InventoryItem.STATUS_RETURNED)
        assertEquals(InventoryItem.STATUS_RETURNED, db.item(uniqueId)?.status)
        db.moveItem(uniqueId, home)
        assertEquals("Home", db.locationPath(db.item(uniqueId)?.locationId))

        db.setQuantity(quantityId, 4)
        db.setQuantity(quantityId, 3)
        assertEquals(3, db.item(quantityId)?.quantity)
        db.setQuantity(quantityId, 12)
        assertEquals(12, db.item(quantityId)?.quantity)

        val pdf = ByteArrayOutputStream()
        InventoryPdfExporter.export(context, pdf, db.items(), db)
        val pdfBytes = pdf.toByteArray()
        assertTrue(pdfBytes.size > 500)
        assertEquals('%'.code.toByte(), pdfBytes[0])
        assertEquals('P'.code.toByte(), pdfBytes[1])
        assertEquals('D'.code.toByte(), pdfBytes[2])
        assertEquals('F'.code.toByte(), pdfBytes[3])

        db.close()
        val reopened = InventoryDatabase(context)
        assertNotNull(reopened.item(uniqueId))
        assertEquals(12, reopened.item(quantityId)?.quantity)
        assertTrue(reopened.item(uniqueId)?.receiptPath?.let(::File)?.exists() == true)
        assertTrue(reopened.item(uniqueId)?.documentPath?.let(::File)?.exists() == true)
        reopened.close()
    }

    private fun resetLocalState() {
        context.deleteDatabase("inventori.db")
        context.getSharedPreferences("inventori", Context.MODE_PRIVATE).edit().clear().commit()
    }

    private fun continueOnboarding(device: UiDevice) {
        assertTrue(device.wait(Until.hasObject(By.res(packageName, "onboarding_continue")), 5_000))
        tapId(device, "onboarding_continue")
    }

    private fun createLocation(device: UiDevice, name: String, type: String?, parent: String?) {
        tapId(device, "locations_add_button")
        val nameInput = device.wait(Until.findObject(By.res(packageName, "location_name_input")), 5_000)
            ?: error("Location name field did not appear")
        nameInput.setText(name)
        device.pressBack()
        if (type != null) {
            device.findObject(By.res(packageName, "location_type_spinner")).click()
            tapText(device, type)
        }
        if (parent != null) {
            device.findObject(By.res(packageName, "location_parent_spinner")).click()
            tapText(device, parent)
        }
        tapId(device, "location_save_button")
        device.waitForIdle()
    }

    private fun providerUri(file: File): Uri =
        FileProvider.getUriForFile(context, "com.inventori.android.fileprovider", file)

    private fun tapId(device: UiDevice, id: String) {
        val target = device.findObject(By.res(packageName, id))
        assertNotNull("Missing UI control: " + id, target)
        target.click()
        device.waitForIdle()
    }

    private fun tapText(device: UiDevice, text: String) {
        val target = device.findObject(By.text(text))
        assertNotNull("Missing UI text: " + text, target)
        target.click()
        device.waitForIdle()
    }

    private fun scrollToBottom(device: UiDevice) {
        val view = device.findObject(By.scrollable(true)) ?: return
        repeat(12) {
            if (!view.scroll(Direction.DOWN, 0.82f)) return
        }
    }

    private fun scrollToTop(device: UiDevice) {
        val view = device.findObject(By.scrollable(true)) ?: return
        repeat(12) {
            if (!view.scroll(Direction.UP, 0.82f)) return
        }
    }
}
