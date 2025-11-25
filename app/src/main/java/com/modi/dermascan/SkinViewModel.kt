package com.modi.dermascan

import android.graphics.Bitmap
import android.location.Location
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
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
    val resultText: String? = null,
    val nearbyDoctors: List<NearbyDoctor> = emptyList(),
    val isDoctorLoading: Boolean = false,
    val doctorError: String? = null
)

data class NearbyDoctor(
    val name: String,
    val latitude: Double,
    val longitude: Double,
    val address: String?,
    val distanceMeters: Float?
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

    fun fetchNearbyDoctors(latitude: Double, longitude: Double) {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(
                isDoctorLoading = true,
                doctorError = null
            )

            val doctors = try {
                queryNearbyDoctors(latitude, longitude)
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    isDoctorLoading = false,
                    doctorError = e.message ?: "Unable to fetch nearby doctors."
                )
                return@launch
            }

            _uiState.value = _uiState.value.copy(
                isDoctorLoading = false,
                nearbyDoctors = doctors,
                doctorError = if (doctors.isEmpty()) "No dermatologists found nearby." else null
            )
        }
    }

    fun onDoctorPermissionDenied() {
        _uiState.value = _uiState.value.copy(
            doctorError = "Location permission denied. Can't show nearby doctors."
        )
    }

    private suspend fun detectSkinDisease(bitmap: Bitmap): String = withContext(Dispatchers.IO) {
        val imageBytes = ByteArrayOutputStream().use { stream ->
            bitmap.compress(Bitmap.CompressFormat.JPEG, 90, stream)
            stream.toByteArray()
        }

        val requestBody = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart(
                "image",
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

    private suspend fun queryNearbyDoctors(
        latitude: Double,
        longitude: Double
    ): List<NearbyDoctor> = withContext(Dispatchers.IO) {
        val overpassQuery = """
            [out:json][timeout:25];
            (
              node["healthcare"="dermatologist"](around:5000,$latitude,$longitude);
              node["amenity"="doctors"](around:5000,$latitude,$longitude);
            );
            out body;
        """.trimIndent()

        val encodedQuery = "data=${URLEncoder.encode(overpassQuery, StandardCharsets.UTF_8.toString())}"
        val requestBody = encodedQuery.toRequestBody("application/x-www-form-urlencoded".toMediaType())

        val request = Request.Builder()
            .url(OVERPASS_URL)
            .post(requestBody)
            .build()

        httpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw IOException("Doctor lookup failed (${response.code})")
            }

            val payload = response.body?.string()?.takeIf { it.isNotBlank() }
                ?: throw IOException("Doctor lookup failed (empty response)")

            parseDoctors(payload, latitude, longitude)
        }
    }

    private fun parseDoctors(
        payload: String,
        userLat: Double,
        userLng: Double
    ): List<NearbyDoctor> {
        val json = runCatching { JSONObject(payload) }.getOrNull() ?: return emptyList()
        val elements = json.optJSONArray("elements") ?: return emptyList()

        val results = mutableListOf<NearbyDoctor>()
        for (i in 0 until elements.length()) {
            val element = elements.optJSONObject(i) ?: continue
            val lat = element.optDouble("lat", Double.NaN)
            val lon = element.optDouble("lon", Double.NaN)
            if (lat.isNaN() || lon.isNaN()) continue

            val tags = element.optJSONObject("tags")
            val name = tags?.optString("name")?.takeIf { it.isNotBlank() }
                ?: continue

            val address = listOfNotNull(
                tags?.optString("addr:street")?.takeIf { it.isNotBlank() },
                tags?.optString("addr:city")?.takeIf { it.isNotBlank() },
                tags?.optString("addr:state")?.takeIf { it.isNotBlank() }
            ).joinToString(separator = ", ").ifBlank { null }

            val distance = computeDistance(userLat, userLng, lat, lon)

            results.add(
                NearbyDoctor(
                    name = name,
                    latitude = lat,
                    longitude = lon,
                    address = address,
                    distanceMeters = distance
                )
            )
        }

        return results.sortedBy { it.distanceMeters ?: Float.MAX_VALUE }
    }

    private fun computeDistance(
        startLat: Double,
        startLng: Double,
        endLat: Double,
        endLng: Double
    ): Float? {
        return runCatching {
            val result = FloatArray(1)
            Location.distanceBetween(startLat, startLng, endLat, endLng, result)
            result.firstOrNull()
        }.getOrNull()
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
        private const val OVERPASS_URL = "https://overpass-api.de/api/interpreter"
    }
}
