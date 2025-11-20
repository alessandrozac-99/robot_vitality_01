package com.example.vitality.ui.dashboard.components

import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.fillMaxWidth
import com.example.vitality.ui.dashboard.epochMsToDayKey

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DayPickerField(
    dayKey: String,
    onPick: (String) -> Unit,
    showPicker: Boolean,
    onShowPicker: (Boolean) -> Unit,
    pickerState: DatePickerState
) {
    OutlinedTextField(
        value = dayKey,
        onValueChange = {},
        readOnly = true,
        label = { Text("Giorno") },
        modifier = Modifier.fillMaxWidth(),
        trailingIcon = {
            TextButton(onClick = { onShowPicker(true) }) {
                Text("Seleziona")
            }
        }
    )

    if (showPicker) {
        DatePickerDialog(
            onDismissRequest = { onShowPicker(false) },
            confirmButton = {
                TextButton(
                    onClick = {
                        pickerState.selectedDateMillis?.let { ms ->
                            onPick(epochMsToDayKey(ms))
                        }
                        onShowPicker(false)
                    }
                ) { Text("OK") }
            },
            dismissButton = {
                TextButton(onClick = { onShowPicker(false) }) { Text("Annulla") }
            }
        ) {
            DatePicker(state = pickerState)
        }
    }
}
