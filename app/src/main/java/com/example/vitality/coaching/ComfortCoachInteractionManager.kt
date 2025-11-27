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
        robot.addAsrListener(this)
    }

    suspend fun askYesNo(question: String): Boolean? {

        val responseDeferred = CompletableDeferred<String?>()
        pendingAnswer = responseDeferred

        val tts = TtsRequest.create(question, true)

        val stt = SttRequest(
            languages = listOf(SttLanguage.IT_IT),
            timeout = 10,
            multipleConversation = false
        )

        robot.askQuestion(tts, stt)

        val answer = try {
            responseDeferred.await()
        } catch (e: Exception) {
            null
        }

        if (answer == null) {
            Log.e("COACH-ASR", "⏳ Nessuna risposta — chiudo conversation()")
            robot.finishConversation()
            return null
        }

        return normalizeYesNo(answer)
    }

    override fun onAsrResult(asrResult: String, sttLanguage: SttLanguage) {
        Log.e("COACH-ASR", "📥 Received ASR: $asrResult (lang=$sttLanguage)")
        pendingAnswer?.complete(asrResult)
        pendingAnswer = null

        robot.finishConversation()
    }


    private fun normalizeYesNo(txt: String?): Boolean? {
        if (txt == null) return null
        val t = txt.lowercase()

        val yes = listOf("sì", "si", "ok", "va bene", "certo", "ovvio", "chiaro")
        val no = listOf("no", "non credo", "nope")

        return when {
            yes.any { t.contains(it) } -> true
            no.any  { t.contains(it) } -> false
            else -> null
        }
    }
}
