package com.example.vitality.coaching

import android.util.Log
import com.robotemi.sdk.Robot
import com.robotemi.sdk.SttLanguage
import com.robotemi.sdk.SttRequest
import com.robotemi.sdk.TtsRequest
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
import java.util.*

class ComfortCoachInteractionManager(
    private val robot: Robot
) : Robot.AsrListener {

    private var pendingAnswer: CompletableDeferred<String?>? = null
    private var sessionId: String = UUID.randomUUID().toString()

    init {
        robot.addAsrListener(this)
    }

    // ======================================================================
    // 🔥 ASK YES-NO SEMPLICE (VERSIONE STABILE)
    // ======================================================================
    suspend fun askYesNo(question: String): Boolean? {

        sessionId = UUID.randomUUID().toString()
        Log.e("ASR", "🎤 Nuova sessione semplice = $sessionId")

        // Chiude eventuali conversazioni precedenti
        robot.finishConversation()
        Thread.sleep(250)

        val deferred = CompletableDeferred<String?>()
        pendingAnswer = deferred

        val tts = TtsRequest.create(question, true)
        val stt = SttRequest(
            languages = listOf(SttLanguage.IT_IT),
            timeout = 10,
            multipleConversation = false
        )

        Log.e("ASR", "🔊 Ask → \"$question\"")
        robot.askQuestion(tts, stt)

        val rawAnswer = withTimeoutOrNull(12_000) {
            deferred.await()
        }

        robot.finishConversation()
        pendingAnswer = null

        if (rawAnswer == null) {
            Log.e("ASR", "⏳ TIMEOUT session=$sessionId — risposta nulla")
            return null
        }

        val parsed = normalizeYesNo(rawAnswer)
        Log.e("ASR", "📌 Parsed='$parsed' (raw='$rawAnswer')")

        return parsed
    }

    // ======================================================================
    // 🔥 ASR CALLBACK SEMPLICE (AFFIDABILE)
    // ======================================================================
    override fun onAsrResult(asrResult: String, sttLanguage: SttLanguage) {

        Log.e("ASR", "📥 EVENT(session=$sessionId): \"$asrResult\"")

        val pending = pendingAnswer
        if (pending == null || pending.isCompleted) {
            Log.e("ASR", "⚠ Evento ignorato → pendingAnswer non valida")
            return
        }

        pending.complete(asrResult)
        robot.finishConversation()
    }

    // ======================================================================
    // ✔ interpretazione yes/no
    // ======================================================================
    private fun normalizeYesNo(txt: String?): Boolean? {
        if (txt == null) return null

        val t = txt.lowercase()

        val yesTokens = listOf("sì", "si", "ok", "va bene", "certo", "ovvio", "chiaro", "perfetto")
        val noTokens = listOf("no", "non credo", "nope", "negativo")

        return when {
            yesTokens.any { t.contains(it) } -> true
            noTokens.any { t.contains(it) } -> false
            else -> null
        }
    }
}
