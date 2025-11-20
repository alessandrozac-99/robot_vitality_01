package com.example.vitality.viewmodel

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.vitality.data.SmartPlugRepository
import com.example.vitality.data.SmartPlugStatus
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

class SmartPlugViewModel(
    private val repository: SmartPlugRepository = SmartPlugRepository()
) : ViewModel() {

    companion object {
        private const val TAG = "SmartPlugVM"
        private const val POLL_MS = 60_000L
    }

    private val _smartPlugs = MutableStateFlow<List<SmartPlugStatus>>(emptyList())
    val smartPlugs: StateFlow<List<SmartPlugStatus>> = _smartPlugs

    private val _loading = MutableStateFlow(false)
    val loading: StateFlow<Boolean> = _loading

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error

    private var lastRoom: String? = null
    private var pollJob: Job? = null

    /**
     * Calcola il timestamp dell’inizio del minuto successivo.
     */
    private fun nextMinuteBoundaryMs(): Long {
        val now = System.currentTimeMillis()
        val oneMinute = 60_000L
        return ((now / oneMinute) + 1) * oneMinute
    }

    /**
     * Carica le prese per una stanza e avvia polling sincronizzato al minuto.
     */
    fun loadPlugsForRoom(room: String, pollEveryMs: Long? = POLL_MS) {
        viewModelScope.launch(Dispatchers.IO) {
            lastRoom = room

            try {
                _loading.emit(true)
                _error.emit(null)

                val list = repository.fetchPlugsForRoom(room)
                _smartPlugs.emit(list)

            } catch (ce: CancellationException) {
                Log.w(TAG, "cancel '$room'")
            } catch (e: Exception) {
                Log.e(TAG, "❌ error '$room': ${e.message}", e)
                _error.emit(e.localizedMessage ?: "Errore nel caricamento prese")
                _smartPlugs.emit(emptyList())
            } finally {
                _loading.emit(false)
            }

            pollEveryMs?.let { startPollingSync(room) }
        }
    }

    /**
     * Polling sincronizzato esattamente all'inizio di ogni minuto.
     */
    private fun startPollingSync(room: String) {
        pollJob?.cancel()

        pollJob = viewModelScope.launch(Dispatchers.IO) {

            // 🔥 Primo allineamento: aspetta il prossimo minuto esatto
            val firstTick = nextMinuteBoundaryMs()
            delay(firstTick - System.currentTimeMillis())

            var nextTick = firstTick

            while (isActive) {
                try {
                    val data = repository.fetchPlugsForRoom(room)
                    _smartPlugs.emit(data)
                } catch (e: Exception) {
                    Log.e(TAG, "❌ poll '$room': ${e.message}")
                }

                // 🔥 Calcola il minuto successivo
                nextTick += 60_000L
                val wait = nextTick - System.currentTimeMillis()

                delay(wait.coerceAtLeast(0))
            }
        }
    }

    fun clear() {
        pollJob?.cancel()
        _smartPlugs.value = emptyList()
        _error.value = null
        _loading.value = false
        lastRoom = null
    }

    override fun onCleared() {
        try { pollJob?.cancel() } catch (_: Exception) {}
        super.onCleared()
    }
}
