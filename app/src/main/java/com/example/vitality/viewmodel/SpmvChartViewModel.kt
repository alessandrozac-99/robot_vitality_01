// SpmvChartViewModel.kt
package com.example.vitality.ui.dashboard.components

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.google.firebase.database.FirebaseDatabase
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.callbackFlow
import com.example.vitality.data.firebase.FirebaseRepository
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.ValueEventListener

class SpmvChartViewModel(
    private val db: FirebaseDatabase = FirebaseDatabase.getInstance()
) : ViewModel() {

    /** Serie SPMV: (epochMs, spmv) ordinati per tempo */
    private val _spmvSeries = MutableStateFlow<List<Pair<Long, Double>>>(emptyList())
    val spmvSeries: StateFlow<List<Pair<Long, Double>>> = _spmvSeries

    /** Avvia l’osservazione della stanza/giorno correnti. */
    fun observeRoomDay(roomKey: String, dayKey: String) {
        viewModelScope.launch {
            spmvFlow(roomKey, dayKey).collect { _spmvSeries.value = it }
        }
    }

    private fun spmvFlow(roomKey: String, dayKey: String) = callbackFlow<List<Pair<Long, Double>>> {
        val ref = db.getReference("office").child(roomKey).child("snapshots").child(dayKey)
        val listener = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                // figli: HH:mm -> payload { timestamp: Long, spmv: Double?, spmv2..., spmv3... }
                val out = snapshot.children.mapNotNull { node ->
                    val ts = (node.child("timestamp").value as? Number)?.toLong() ?: return@mapNotNull null
                    val sp = (node.child("spmv").value as? Number)?.toDouble() ?: return@mapNotNull null
                    ts to sp
                }.sortedBy { it.first }
                trySend(out)
            }
            override fun onCancelled(error: DatabaseError) { /* no-op */ }
        }
        ref.addValueEventListener(listener)
        awaitClose { ref.removeEventListener(listener) }
    }
}
