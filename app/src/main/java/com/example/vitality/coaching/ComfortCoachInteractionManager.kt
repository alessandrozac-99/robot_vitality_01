package com.example.vitality.coaching

import android.util.Log
import com.robotemi.sdk.Robot
import com.robotemi.sdk.TtsRequest
import com.robotemi.sdk.SttLanguage
import com.robotemi.sdk.SttRequest
import kotlinx.coroutines.CompletableDeferred

class ComfortCoachInteractionManager(
    private val robot: Robot
) : Robot.AsrListener {

    private var pendingAnswer: CompletableDeferred<String?>? = null

    init {
        // Registrazione listener ASR del Temi
        robot.addAsrListener(this)
    }

    /**
     * Domanda sì/no usando askQuestion + ascolto ASR diretto (senza assistant).
     */
    suspend fun askYesNo(question: String): Boolean? {

        val responseDeferred = CompletableDeferred<String?>()
        pendingAnswer = responseDeferred

        // 1) TTS della domanda
        val tts = TtsRequest.create(question, true)

        // 2) STT request — VERSIONE CORRETTA PER IL TUO SDK
        val stt = SttRequest(
            languages = listOf(SttLanguage.IT_IT),
            timeout = 10,  // secondi
            multipleConversation = false
        )

        // 3) Temi parla → poi ascolta
        robot.askQuestion(tts, stt)

        // 4) Attesa della risposta ASR
        val answer = responseDeferred.await()
        Log.e("COACH-ASR", "🎤 ASR response: $answer")

        return when {
            answer == null -> null
            answer.contains("sì", true) -> true
            answer.contains("si", true) -> true
            answer.contains("ok", true) -> true
            answer.contains("no", true) -> false
            else -> null
        }
    }

    /**
     * Callback ASR del Temi.
     */
    override fun onAsrResult(asrResult: String, language: SttLanguage) {
        Log.e("COACH-ASR", "📥 Received ASR: $asrResult (lang=$language)")
        pendingAnswer?.complete(asrResult)
        pendingAnswer = null

        // IMPORTANTISSIMO: termina la sessione e NON avvia l’assistente.
        robot.finishConversation()
    }
}
