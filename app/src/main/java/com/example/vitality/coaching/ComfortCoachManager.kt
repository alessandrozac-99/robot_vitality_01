package com.example.vitality.coaching

import android.content.Context
import android.util.Log
import com.robotemi.sdk.Robot
import com.robotemi.sdk.TtsRequest

class ComfortCoachManager(
    private val context: Context,
    private val robot: Robot,
    private val vm: CoachViewModel
) {

    fun askComfortConfirmation() {

        robot.speak(
            TtsRequest.create(
                "Sto lavorando per migliorare il comfort. " +
                        "Dimmi 'sì' se ora va meglio, oppure usa il pulsante sullo schermo.",
                false
            )
        )

        vm.startListeningForFeedback()
    }

    fun onNlpCommand(command: String) {
        when (command) {
            "comfort_yes" -> {
                Log.e("COACH", "NLP → YES")
                vm.feedbackReceived(true)
                robot.speak(TtsRequest.create("Perfetto, sono contento."))
            }

            "comfort_no" -> {
                Log.e("COACH", "NLP → NO")
                vm.feedbackReceived(false)
                robot.speak(TtsRequest.create("Va bene, continuo a verificare la stanza."))
            }
        }
    }
}
