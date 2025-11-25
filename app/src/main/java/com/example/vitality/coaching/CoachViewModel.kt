package com.example.vitality.coaching

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

class CoachViewModel : ViewModel() {

    private val _awaitingVoice = MutableStateFlow(false)
    val awaitingVoice: StateFlow<Boolean> = _awaitingVoice

    private val _showFallbackPopup = MutableStateFlow(false)
    val showFallbackPopup: StateFlow<Boolean> = _showFallbackPopup

    private var fallbackJob: Job? = null

    fun startListeningForFeedback() {
        _awaitingVoice.value = true
        _showFallbackPopup.value = false

        fallbackJob?.cancel()
        fallbackJob = viewModelScope.launch {
            delay(5000)         // 5 secondi per risposta vocale
            if (_awaitingVoice.value) {
                _showFallbackPopup.value = true
            }
        }
    }

    fun feedbackReceived(yes: Boolean) {
        _awaitingVoice.value = false
        _showFallbackPopup.value = false
        fallbackJob?.cancel()

        Log.e("COACH", "Feedback utente = ${if (yes) "SÌ" else "NO"}")
        // Qui puoi salvare su Firebase o loggare l'evento
    }
}
