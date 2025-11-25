package com.example.vitality.freddure

import android.content.Context
import android.util.Log
import com.robotemi.sdk.Robot
import com.robotemi.sdk.TtsRequest
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicReference

object FreddureReader {

    private const val TAG = "Freddure"
    private const val FILE_NAME = "freddure.json"

    // Cache interna per evitare di ricaricare sempre il file
    private val cache = AtomicReference<List<String>?>(null)

    /**
     * Restituisce una freddura casuale dal file assets/freddure.json
     * Il file viene caricato solo la prima volta.
     */
    fun loadRandomFreddura(context: Context): String? {
        try {
            // Se già in cache → uso quella
            cache.get()?.let { list ->
                if (list.isNotEmpty()) {
                    val pick = list.random()
                    Log.d(TAG, "Freddura (cache): $pick")
                    return pick
                }
                return null
            }

            // Altrimenti carico da assets
            val jsonStr = context.assets
                .open(FILE_NAME)
                .bufferedReader()
                .use { it.readText() }

            val json = JSONObject(jsonStr)
            val arr = json.getJSONArray("freddure")

            if (arr.length() == 0) {
                Log.w(TAG, "freddure.json è vuoto!")
                return null
            }

            // Converto in list
            val list = List(arr.length()) { i -> arr.getString(i) }

            // Salvo in cache
            cache.set(list)

            val pick = list.random()
            Log.d(TAG, "Freddura (first load): $pick")
            return pick

        } catch (e: Exception) {
            Log.e(TAG, "Errore lettura freddure.json: ${e.message}")
            return null
        }
    }

    /**
     * 🎤 Legge una freddura con il robot Temi usando lo stesso TTS del coaching.
     */
    fun speakRandomFreddura(robot: Robot, context: Context) {
        val text = loadRandomFreddura(context)
        if (text == null) {
            Log.e(TAG, "Nessuna freddura trovata.")
            return
        }

        try {
            val req = TtsRequest.create(text, false)
            robot.speak(req)
            Log.e(TAG, "🗣 Robot sta leggendo la freddura: $text")
        } catch (e: Exception) {
            Log.e(TAG, "Errore TTS Temi: ${e.message}")
        }
    }
}
