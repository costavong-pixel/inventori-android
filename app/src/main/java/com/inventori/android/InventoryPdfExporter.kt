package com.inventori.android

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.pdf.PdfDocument
import java.io.OutputStream
import java.text.DateFormat
import java.text.NumberFormat
import java.util.Date
import java.util.Locale

object InventoryPdfExporter {
    private const val PAGE_WIDTH = 595
    private const val PAGE_HEIGHT = 842
    private const val MARGIN = 40f
    private const val ROW_HEIGHT = 172f

    fun export(
        context: Context,
        output: OutputStream,
        items: List<InventoryItem>,
        database: InventoryDatabase
    ) {
        val document = PdfDocument()
        val titlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(25, 52, 43)
            textSize = 22f
            typeface = android.graphics.Typeface.create("sans-serif", android.graphics.Typeface.BOLD)
        }
        val headingPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(30, 107, 91)
            textSize = 12f
            typeface = android.graphics.Typeface.create("sans-serif", android.graphics.Typeface.BOLD)
        }
        val bodyPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(43, 53, 48)
            textSize = 10.5f
        }
        val mutedPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(98, 111, 103)
            textSize = 9f
        }
        val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(224, 231, 226)
            style = Paint.Style.STROKE
            strokeWidth = 1f
        }
        val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
        val itemsByPath = items.sortedWith(
            compareBy<InventoryItem> { database.locationPath(it.locationId).lowercase() }
                .thenBy { it.name.lowercase() }
        )
        var pageNumber = 1
        var page = document.startPage(PdfDocument.PageInfo.Builder(PAGE_WIDTH, PAGE_HEIGHT, pageNumber).create())
        var canvas = page.canvas
        var y = MARGIN
        canvas.drawText("Inventori · Inventory record", MARGIN, y + 22f, titlePaint)
        y += 43f
        canvas.drawText("Generated " + DateFormat.getDateTimeInstance().format(Date()), MARGIN, y, mutedPaint)
        y += 17f
        canvas.drawText("User-entered values are estimates. This document does not guarantee an insurance claim.", MARGIN, y, mutedPaint)
        y += 25f

        if (itemsByPath.isEmpty()) {
            canvas.drawText("No items have been saved yet.", MARGIN, y + 10f, bodyPaint)
        }

        itemsByPath.forEach { item ->
            if (y + ROW_HEIGHT > PAGE_HEIGHT - MARGIN) {
                document.finishPage(page)
                pageNumber++
                page = document.startPage(PdfDocument.PageInfo.Builder(PAGE_WIDTH, PAGE_HEIGHT, pageNumber).create())
                canvas = page.canvas
                y = MARGIN
                canvas.drawText("Inventori · Inventory record · continued", MARGIN, y + 18f, titlePaint)
                y += 32f
            }
            val box = RectF(MARGIN, y, PAGE_WIDTH - MARGIN, y + ROW_HEIGHT - 8f)
            canvas.drawRoundRect(box, 8f, 8f, fillPaint)
            canvas.drawRoundRect(box, 8f, 8f, borderPaint)
            val textX = MARGIN + 12f
            val textRight = PAGE_WIDTH - MARGIN - 82f
            var textY = y + 17f
            canvas.drawText(item.name.ifBlank { "Unnamed item" }, textX, textY, headingPaint)
            textY += 15f
            val path = database.locationPath(item.locationId)
            drawWrapped(canvas, path, textX, textY, textRight - textX, bodyPaint, 11f, 2)
            textY += 23f
            val typeAndQuantity = if (item.itemType == InventoryItem.TYPE_UNIQUE) {
                "Unique · " + statusText(item.status)
            } else {
                "Quantity item · " + item.quantity + " units"
            }
            canvas.drawText("Category: " + item.category.ifBlank { "—" } + "    " + typeAndQuantity, textX, textY, bodyPaint)
            textY += 13f
            canvas.drawText("Brand / model: " + joinOrDash(item.brand, item.model), textX, textY, bodyPaint)
            textY += 13f
            canvas.drawText("Serial: " + item.serialNumber.ifBlank { "—" }, textX, textY, bodyPaint)
            textY += 13f
            val identifier = if (item.identifierValue.isBlank()) "—" else item.identifierType + ": " + item.identifierValue
            drawWrapped(canvas, "Inventory ID: " + identifier, textX, textY, textRight - textX, bodyPaint, 11f, 1)
            textY += 13f
            val purchase = item.purchasePrice?.let { NumberFormat.getCurrencyInstance().format(it) } ?: "—"
            val replacement = item.replacementValue?.let { NumberFormat.getCurrencyInstance().format(it) } ?: "—"
            canvas.drawText("Purchase: " + purchase + "    Estimated replacement: " + replacement, textX, textY, bodyPaint)
            textY += 13f
            canvas.drawText("Receipt: " + if (item.receiptPath.isNullOrBlank()) "not attached" else "attached", textX, textY, bodyPaint)
            val documentLabel = if (item.documentPath.isNullOrBlank()) "not attached" else "attached"
            canvas.drawText("Appraisal / document: " + documentLabel, textX + 180f, textY, bodyPaint)
            if (item.evidenceLatitude != null && item.evidenceLongitude != null) {
                textY += 13f
                val coords = String.format(Locale.US, "%.5f, %.5f", item.evidenceLatitude, item.evidenceLongitude)
                val gpsDate = item.evidenceCapturedAt?.let {
                    " · " + DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(it))
                }.orEmpty()
                canvas.drawText("Evidence GPS: " + coords + gpsDate, textX, textY, bodyPaint)
            }
            if (item.notes.isNotBlank()) {
                textY += 13f
                canvas.drawText("Notes:", textX, textY, bodyPaint)
                drawWrapped(canvas, item.notes, textX + 34f, textY, textRight - textX - 34f, bodyPaint, 11f, 2)
            }

            val bitmap = item.photoPath?.let { pathValue ->
                runCatching { BitmapFactory.decodeFile(pathValue) }.getOrNull()
            }
            if (bitmap != null) {
                drawThumbnail(canvas, bitmap, PAGE_WIDTH - MARGIN - 69f, y + 24f, 56f, 56f)
                if (!bitmap.isRecycled) bitmap.recycle()
            } else {
                val photoBox = RectF(PAGE_WIDTH - MARGIN - 69f, y + 24f, PAGE_WIDTH - MARGIN - 13f, y + 80f)
                canvas.drawRoundRect(photoBox, 4f, 4f, borderPaint)
                canvas.drawText("No photo", photoBox.left + 7f, photoBox.centerY(), mutedPaint)
            }
            y += ROW_HEIGHT
        }
        document.finishPage(page)
        document.writeTo(output)
        document.close()
    }

    private fun drawThumbnail(canvas: android.graphics.Canvas, bitmap: Bitmap, left: Float, top: Float, width: Float, height: Float) {
        val source = Rect(0, 0, bitmap.width, bitmap.height)
        val destination = RectF(left, top, left + width, top + height)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        canvas.drawBitmap(bitmap, source, destination, paint)
        val border = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(224, 231, 226)
            style = Paint.Style.STROKE
            strokeWidth = 1f
        }
        canvas.drawRoundRect(destination, 4f, 4f, border)
    }

    private fun drawWrapped(
        canvas: android.graphics.Canvas,
        value: String,
        x: Float,
        y: Float,
        width: Float,
        paint: Paint,
        lineHeight: Float,
        maxLines: Int
    ) {
        var line = ""
        var lines = 0
        value.split(Regex("\\s+")).forEach { word ->
            val candidate = if (line.isEmpty()) word else line + " " + word
            if (paint.measureText(candidate) > width && line.isNotEmpty()) {
                canvas.drawText(line, x, y + lines * lineHeight, paint)
                lines++
                line = word
                if (lines >= maxLines) return
            } else {
                line = candidate
            }
        }
        if (line.isNotEmpty() && lines < maxLines) canvas.drawText(line, x, y + lines * lineHeight, paint)
    }

    private fun joinOrDash(first: String, second: String): String =
        listOf(first, second).filter { it.isNotBlank() }.joinToString(" / ").ifBlank { "—" }

    private fun statusText(status: String): String = when (status) {
        InventoryItem.STATUS_CHECKED_OUT -> "checked out"
        InventoryItem.STATUS_RETURNED -> "returned"
        else -> "available"
    }
}
