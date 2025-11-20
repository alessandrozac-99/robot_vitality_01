package com.example.vitality.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.google.firebase.database.*
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

/**
 * ViewModel unico per storico giornaliero da Firebase:
 *  - sPMV
 *  - t_amb
 *  - rh
 *
 * Struttura Firebase:
 * /office/{room}/snapshots/{day}/{time}/
 */
class ComfortDayViewModel(
    private val db: FirebaseDatabase = FirebaseDatabase.getInstance()
) : ViewModel() {

    data class ComfortPoint(
        val timestamp: Long,
        val spmv: Double?,
        val tAmb: Double?,
        val rh: Double?
    )

    private val _daySeries = MutableStateFlow<List<ComfortPoint>>(emptyList())
    val daySeries: StateFlow<List<ComfortPoint>> = _daySeries

    fun observeRoomDay(room: String, day: String) {
        viewModelScope.launch {
            readDay(room, day).collect { _daySeries.value = it }
        }
    }

    private fun readDay(room: String, day: String) = callbackFlow {
        val ref = db.getReference("office/$room/snapshots/$day")

        val listener = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                val list = snapshot.children.mapNotNull { timeNode ->
                    val ts = timeNode.child("timestamp").getValue(Long::class.java) ?: return@mapNotNull null

                    ComfortPoint(
                        timestamp = ts,
                        spmv = timeNode.child("spmv").getValue(Double::class.java),
                        tAmb = timeNode.child("t_amb").getValue(Double::class.java),
                        rh = timeNode.child("rh").getValue(Double::class.java)
                    )
                }.sortedBy { it.timestamp }

                trySend(list)
            }

            override fun onCancelled(error: DatabaseError) {}
        }

        ref.addValueEventListener(listener)
        awaitClose { ref.removeEventListener(listener) }
    }
}
