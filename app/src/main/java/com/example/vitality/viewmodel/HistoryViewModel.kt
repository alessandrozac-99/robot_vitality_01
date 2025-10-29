package com.example.vitality.viewmodel

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.vitality.data.HistoryRepository
import com.example.vitality.data.HistoricalItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class HistoryViewModel(
    private val repository: HistoryRepository = HistoryRepository()
) : ViewModel() {

    private val _history = MutableStateFlow<List<HistoricalItem>>(emptyList())
    val history: StateFlow<List<HistoricalItem>> = _history

    private val _loading = MutableStateFlow(false)
    val loading: StateFlow<Boolean> = _loading

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error

    private val _currentRoom = MutableStateFlow<String?>(null)
    val currentRoom: StateFlow<String?> = _currentRoom

    // ⬇️ configurazione runtime (finestra/passo) con default 6h / 5m
    private val _uiConfig = MutableStateFlow(HistoryUiConfig())
    val uiConfig: StateFlow<HistoryUiConfig> = _uiConfig

    fun setWindowHours(hours: Int) {
        val newCfg = _uiConfig.value.copy(windowMs = hours * 3600L * 1000L)
        _uiConfig.value = newCfg
        // se è selezionata una stanza, ricarica lo storico con la nuova finestra
        _currentRoom.value?.let { loadHistoryForRoom(it, force = true) }
    }

    fun setStepMinutes(minutes: Int) {
        val newCfg = _uiConfig.value.copy(stepMs = minutes * 60L * 1000L)
        _uiConfig.value = newCfg
        _currentRoom.value?.let { loadHistoryForRoom(it, force = true) }
    }

    fun loadHistoryForRoom(room: String, force: Boolean = false) {
        if (_loading.value && _currentRoom.value == room && !force) return

        _currentRoom.value = room
        _loading.value = true
        _error.value = null

        val cfg = _uiConfig.value
        viewModelScope.launch(Dispatchers.IO) {
            try {
                Log.i("HistoryVM", "📈 Carico storico per '$room' (window=${cfg.windowMs}ms, step=${cfg.stepMs}ms)")
                val data: List<HistoricalItem> = repository.getTemperatureHumidityHistory(
                    roomName = room,
                    windowMs = cfg.windowMs,
                    stepMs = cfg.stepMs
                )
                withContext(Dispatchers.Main) {
                    _history.value = data
                    _loading.value = false
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    _error.value = e.localizedMessage ?: "Errore sconosciuto"
                    _history.value = emptyList()
                    _loading.value = false
                }
            }
        }
    }

    fun refresh() = _currentRoom.value?.let { loadHistoryForRoom(it, force = true) }

    fun clearHistory() {
        _history.value = emptyList()
        _error.value = null
        _loading.value = false
    }
}
