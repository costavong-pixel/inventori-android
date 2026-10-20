package com.inventori.android

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID

data class AiRecognitionResult(
    val items: List<InventoryItem>,
    val source: String,
    val charged: Boolean = false,
    val scansUsed: Int? = null,
    val scansLimit: Int? = null,
    val scansRemaining: Int? = null,
    val inputTokens: Int? = null,
    val outputTokens: Int? = null,
    val estimatedCostUsd: Double? = null,
    val model: String? = null
)

interface InventoryAiRecognizer {
    fun recognize(photoPath: String?, expectedItems: Int, workspaceType: String): AiRecognitionResult
}

class MockInventoryAiRecognizer : InventoryAiRecognizer {
    override fun recognize(photoPath: String?, expectedItems: Int, workspaceType: String): AiRecognitionResult {
        val suggestions = if (expectedItems > 1) {
            listOf(
                InventoryItem(name = "Detected power drill", category = "Tools", quantity = 1),
                InventoryItem(name = "Detected battery pack", category = "Tools", quantity = 1)
            )
        } else {
            listOf(InventoryItem(name = "Detected item", category = "Uncategorized", quantity = 1))
        }
        return AiRecognitionResult(
            items = suggestions.map { item -> item.copy(photoPath = photoPath) },
            source = SOURCE_MOCK
        )
    }

    companion object {
        const val SOURCE_MOCK = "mock"
    }
}

/**
 * Sends resized image bytes to Inventori's backend. The OpenAI key stays on the server and is
 * never bundled in the APK. When no backend URL is configured, the local deterministic preview
 * stays available for development/testing.
 */
class BackendInventoryAiRecognizer(
    context: Context,
    backendUrl: String,
    private val fallback: InventoryAiRecognizer = MockInventoryAiRecognizer()
) : InventoryAiRecognizer {
    private val appContext = context.applicationContext
    private val preferences = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val baseUrl = backendUrl.trim().trimEnd('/')

    val configured: Boolean get() = baseUrl.isNotBlank()

    override fun recognize(photoPath: String?, expectedItems: Int, workspaceType: String): AiRecognitionResult {
        if (photoPath.isNullOrBlank() || !configured) {
            return fallback.recognize(photoPath, expectedItems, workspaceType)
        }
        requireSecureBackend()
        val installId = preferences.getString(KEY_INSTALL_ID, null)
            ?: UUID.randomUUID().toString().also {
                preferences.edit().putString(KEY_INSTALL_ID, it).apply()
            }
        val token = preferences.getString(KEY_INSTALL_TOKEN, null)
            ?: register(installId).also {
                preferences.edit().putString(KEY_INSTALL_TOKEN, it).apply()
            }

        val request = JSONObject()
            .put("install_id", installId)
            .put("install_token", token)
            .put("workspace_type", workspaceType)
            .put("expected_items", expectedItems.coerceIn(1, 20))
            .put("image_data_url", imageAsDataUrl(photoPath))

        val response = postJson("/v1/analyze", request)
        if (!response.optBoolean("ok", false)) {
            error(response.optString("error", "AI scan failed"))
        }
        val itemsJson = response.getJSONArray("items")
        val items = buildList {
            for (index in 0 until itemsJson.length()) {
                val value = itemsJson.getJSONObject(index)
                add(
                    InventoryItem(
                        name = value.optString("name"),
                        category = value.optString("category"),
                        brand = value.optString("brand"),
                        model = value.optString("model"),
                        serialNumber = value.optString("serial_number"),
                        quantity = value.optInt("quantity", 1).coerceAtLeast(1),
                        photoPath = photoPath
                    )
                )
            }
        }
        val usage = response.optJSONObject("usage")
        return AiRecognitionResult(
            items = items,
            source = SOURCE_BACKEND,
            charged = response.optBoolean("charged", false),
            scansUsed = response.optIntOrNull("scans_used"),
            scansLimit = response.optIntOrNull("scans_limit"),
            scansRemaining = response.optIntOrNull("scans_remaining"),
            inputTokens = usage?.optIntOrNull("input_tokens"),
            outputTokens = usage?.optIntOrNull("output_tokens"),
            estimatedCostUsd = usage?.optDoubleOrNull("estimated_cost_usd"),
            model = response.optString("model").takeIf { it.isNotBlank() }
        )
    }

    private fun register(installId: String): String {
        val response = postJson("/v1/register", JSONObject().put("install_id", installId))
        if (!response.optBoolean("ok", false)) error(response.optString("error", "AI registration failed"))
        response.optIntOrNull("scans_remaining")?.let {
            preferences.edit().putInt(KEY_LAST_REMAINING, it).apply()
        }
        return response.getString("install_token")
    }

    private fun postJson(path: String, body: JSONObject): JSONObject {
        val connection = (URL(baseUrl + path).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 20_000
            readTimeout = 75_000
            doOutput = true
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("Accept", "application/json")
        }
        connection.outputStream.use { output ->
            output.write(body.toString().toByteArray(Charsets.UTF_8))
        }
        val status = connection.responseCode
        val stream = if (status in 200..299) connection.inputStream else connection.errorStream
        val text = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
        connection.disconnect()
        val json = runCatching { JSONObject(text) }.getOrElse {
            error("AI backend returned HTTP " + status)
        }
        if (status !in 200..299) {
            error(json.optString("error", "AI backend returned HTTP " + status))
        }
        json.optIntOrNull("scans_remaining")?.let {
            preferences.edit().putInt(KEY_LAST_REMAINING, it).apply()
        }
        return json
    }

    private fun requireSecureBackend() {
        val lower = baseUrl.lowercase()
        if (!lower.startsWith("https://") && !lower.startsWith("http://10.0.2.2")) {
            error("Inventori AI backend must use HTTPS")
        }
    }

    private fun imageAsDataUrl(photoPath: String): String {
        val file = File(photoPath)
        require(file.exists()) { "Photo file is missing" }

        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(photoPath, bounds)
        var sample = 1
        while (bounds.outWidth / sample > 1920 || bounds.outHeight / sample > 1920) sample *= 2
        val bitmap = BitmapFactory.decodeFile(
            photoPath,
            BitmapFactory.Options().apply { inSampleSize = sample }
        ) ?: error("Could not decode photo")

        val maxSide = 1280
        val largest = maxOf(bitmap.width, bitmap.height)
        val resized = if (largest > maxSide) {
            val scale = maxSide.toFloat() / largest.toFloat()
            Bitmap.createScaledBitmap(
                bitmap,
                (bitmap.width * scale).toInt().coerceAtLeast(1),
                (bitmap.height * scale).toInt().coerceAtLeast(1),
                true
            )
        } else bitmap

        val bytes = ByteArrayOutputStream().use { output ->
            resized.compress(Bitmap.CompressFormat.JPEG, 84, output)
            output.toByteArray()
        }
        if (resized !== bitmap) resized.recycle()
        bitmap.recycle()
        return "data:image/jpeg;base64," + Base64.encodeToString(bytes, Base64.NO_WRAP)
    }

    private fun JSONObject.optIntOrNull(key: String): Int? =
        if (has(key) && !isNull(key)) optInt(key) else null

    private fun JSONObject.optDoubleOrNull(key: String): Double? =
        if (has(key) && !isNull(key)) optDouble(key) else null

    companion object {
        const val SOURCE_BACKEND = "backend"
        private const val PREFS = "inventori_ai"
        private const val KEY_INSTALL_ID = "install_id"
        private const val KEY_INSTALL_TOKEN = "install_token"
        const val KEY_LAST_REMAINING = "last_remaining"
    }
}
