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
        createLocation(scenario, device, "Home", null, null)
        createLocation(scenario, device, "Basement", "Room", "Home")
        createLocation(scenario, device, "Box 4", "Box / bin", "Home → Basement")
        scenario.onActivity { activity ->
            val backButton = activity.findViewById<Button>(R.id.screen_back)
            assertNotNull("Missing location screen back button", backButton)
            backButton.performClick()
        }
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
        tapId(device, "ai_mock_sample")
        assertTrue(device.hasObject(By.text("Detected power drill")))
        assertTrue(device.hasObject(By.text("Detected battery pack")))

        tapId(device, "ai_draft_edit_0")
        scenario.onActivity { activity ->
            activity.findViewById<EditText>(R.id.ai_edit_name).setText("Edited detected drill")
            activity.findViewById<Button>(R.id.ai_edit_apply).performClick()
        }
        scenario.onActivity { activity -> activity.findViewById<Button>(R.id.ai_add_draft).performClick() }
        scenario.onActivity { activity -> activity.findViewById<Button>(R.id.ai_draft_edit_2).performClick() }
        scenario.onActivity { activity ->
            activity.findViewById<EditText>(R.id.ai_edit_name).setText("Added during review")
            activity.findViewById<Button>(R.id.ai_edit_apply).performClick()
        }
        scenario.onActivity { activity -> activity.findViewById<Button>(R.id.ai_draft_remove_0).performClick() }
        assertFalse(device.hasObject(By.text("Edited detected drill")))
        scenario.onActivity { it.findViewById<Button>(R.id.ai_confirm_save).performClick() }
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

    private fun createLocation(
        scenario: ActivityScenario<MainActivity>,
        device: UiDevice,
        name: String,
        type: String?,
        parent: String?
    ) {
        tapId(device, "locations_add_button")
        scenario.onActivity { activity ->
            activity.findViewById<EditText>(R.id.location_name_input).setText(name)
            val typeSpinner = activity.findViewById<Spinner>(R.id.location_type_spinner)
            if (type != null) {
                typeSpinner.setSelection((0 until typeSpinner.count).first {
                    typeSpinner.getItemAtPosition(it).toString() == type
                })
            }
            val parentSpinner = activity.findViewById<Spinner>(R.id.location_parent_spinner)
            if (parent != null) {
                parentSpinner.setSelection((0 until parentSpinner.count).first {
                    parentSpinner.getItemAtPosition(it).toString() == parent
                })
            }
            activity.findViewById<Button>(R.id.location_save_button).performClick()
        }
        device.waitForIdle()
    }

    private fun providerUri(file: File): Uri =
        FileProvider.getUriForFile(context, "com.inventori.android.fileprovider", file)

    private fun tapId(device: UiDevice, id: String) {
        val selector = By.res(packageName, id)
        assertTrue("Timed out waiting for UI control: " + id, device.wait(Until.hasObject(selector), 5_000))
        val target = device.findObject(selector)
        assertNotNull("Missing UI control: " + id, target)
        target.click()
        device.waitForIdle()
    }

}
