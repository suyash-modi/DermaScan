package com.modi.dermascan

import android.graphics.Bitmap
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

data class SkinUiState(
    val bitmap: Bitmap? = null,
    val isLoading: Boolean = false,
    val resultText: String? = null
)

class SkinViewModel : ViewModel() {

    private val _uiState = MutableStateFlow(SkinUiState())
    val uiState: StateFlow<SkinUiState> = _uiState

    fun setImage(bitmap: Bitmap) {
        _uiState.value = _uiState.value.copy(bitmap = bitmap, resultText = null)
    }

    fun analyzeImage() {
        val bitmap = _uiState.value.bitmap ?: return

        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true, resultText = null)


            delay(2000)
            val result = detectSkinDisease(bitmap)

            _uiState.value = _uiState.value.copy(
                isLoading = false,
                resultText = result
            )
        }
    }

    // demo function
    private fun detectSkinDisease(bitmap: Bitmap): String {

        val results = listOf(
            "Healthy Skin",
            "Acne Detected",
            "Eczema Detected",
            "Psoriasis Detected",
            "Fungal Infection Suspected"
        )
        return results.random()
    }
}
