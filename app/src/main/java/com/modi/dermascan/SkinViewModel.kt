package com.modi.dermascan

import android.graphics.Bitmap
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import java.io.ByteArrayOutputStream
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject

data class SkinUiState(
    val bitmap: Bitmap? = null,
    val isLoading: Boolean = false,
    val resultText: String? = null
)

class SkinViewModel : ViewModel() {

    private val httpClient = OkHttpClient()

    private val _uiState = MutableStateFlow(SkinUiState())
    val uiState: StateFlow<SkinUiState> = _uiState

    fun setImage(bitmap: Bitmap) {
        _uiState.value = _uiState.value.copy(bitmap = bitmap, resultText = null)
    }

    fun analyzeImage() {
        val bitmap = _uiState.value.bitmap ?: return

        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true, resultText = null)


            val result = try {
                detectSkinDisease(bitmap)
            } catch (e: Exception) {
                e.message ?: "Unable to analyze image. Please try again."
            }

            _uiState.value = _uiState.value.copy(
                isLoading = false,
                resultText = result
            )
        }
    }

    private suspend fun detectSkinDisease(bitmap: Bitmap): String = withContext(Dispatchers.IO) {
        val imageBytes = ByteArrayOutputStream().use { stream ->
            bitmap.compress(Bitmap.CompressFormat.JPEG, 90, stream)
            stream.toByteArray()
        }

        val requestBody = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart(
                "file",
                "capture.jpg",
                imageBytes.toRequestBody("image/jpeg".toMediaType())
            )
            .build()

        val request = Request.Builder()
            .url(PREDICT_URL)
            .post(requestBody)
            .build()

        httpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw IOException("Prediction failed (${response.code})")
            }

            val payload = response.body?.string()?.takeIf { it.isNotBlank() }
                ?: throw IOException("Prediction failed (empty response)")

            parsePrediction(payload)
        }
    }

    private fun parsePrediction(payload: String): String {
        val json = runCatching { JSONObject(payload) }.getOrNull()
            ?: return payload

        val predictions = json.optJSONArray("predictions") ?: return payload
        if (predictions.length() == 0) return payload

        var bestName: String? = null
        var bestProbability = Double.MIN_VALUE
        for (i in 0 until predictions.length()) {
            val entry = predictions.optJSONObject(i) ?: continue
            val prob = entry.optDouble("probability", Double.MIN_VALUE)
            if (prob > bestProbability) {
                bestProbability = prob
                bestName = entry.optString("class_name", null)
            }
        }

        return bestName?.let { name ->
            val percent = (bestProbability * 100).coerceIn(0.0, 100.0)
            "%s (%.2f%% confidence)".format(name, percent)
        } ?: payload
    }

    companion object {
        private const val PREDICT_URL = "http://10.0.2.2:5000/predict"
    }
}
